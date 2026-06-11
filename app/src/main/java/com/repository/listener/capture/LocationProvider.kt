package com.repository.listener.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.repository.listener.capture.locator.LocatorApiClient
import com.repository.listener.capture.locator.LocatorResult
import com.repository.listener.capture.signals.SignalCollector
import com.repository.listener.config.AppConfig
import com.repository.listener.util.LogCollector
import com.repository.navigation.provider.MapProviders
import com.yandex.mapkit.geometry.Point
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves the phone's location.
 *
 * Primary path: Yandex Locator API (WiFi BSSIDs + cell towers + public IP).
 * Fallback path: Google FusedLocationProviderClient (GPS/GNSS/network).
 *
 * The Locator path is tried first because GPS is unreliable in the target
 * deployment environments (jamming, urban canyons, indoor). If the Locator
 * API exhausts retries, returns "unknown", fails fatally, or has no signals
 * to work with, we fall through to the existing Fused path unchanged.
 */
class LocationProvider(private val context: Context) {

    companion object {
        private const val TAG = "LocationProvider"
        private const val TIMEOUT_MS = 10_000L
        private const val GEOCODE_TIMEOUT_MS = 5_000L

        /** Source tag attached to every location JSON for downstream observability. */
        const val SOURCE_LOCATOR = "locator"
        const val SOURCE_FUSED = "fused"
        const val SOURCE_GPS = "gps"
    }

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val locatorClient = LocatorApiClient(context)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var backgroundCallback: LocationCallback? = null

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    @Volatile private var currentHeadingDegrees: Float? = null

    private val headingListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
            if (azimuth < 0) azimuth += 360f
            currentHeadingDegrees = azimuth
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Log permission state and location provider status for diagnostics. */
    fun diagnose() {
        val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
        val bg = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val msg = "DIAG fine=${if (fine == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"} " +
                "bg=${if (bg == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"} " +
                "gps=$gpsEnabled network=$networkEnabled"
        Log.d(TAG, msg)
        LogCollector.i(TAG, msg)
    }

    /** Start a passive background listener so the fused provider stays warm. */
    @SuppressLint("MissingPermission")
    fun startBackgroundUpdates() {
        diagnose()
        if (backgroundCallback != null) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5 * 60_000L)
            .setMinUpdateIntervalMillis(60_000L)
            .build()
        backgroundCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation
                if (loc != null) {
                    Log.d(TAG, "BG_UPDATE lat=${loc.latitude} lng=${loc.longitude} acc=${loc.accuracy}m")
                }
            }
        }
        fusedClient.requestLocationUpdates(request, backgroundCallback!!, Looper.getMainLooper())
        LogCollector.i(TAG, "Background location updates started (5min interval)")

        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor != null) {
            sensorManager.registerListener(headingListener, rotationSensor, SensorManager.SENSOR_DELAY_NORMAL)
            LogCollector.i(TAG, "Compass heading sensor registered")
        } else {
            LogCollector.w(TAG, "TYPE_ROTATION_VECTOR sensor not available, heading will be null")
        }
    }

    fun stopBackgroundUpdates() {
        sensorManager.unregisterListener(headingListener)
        backgroundCallback?.let { fusedClient.removeLocationUpdates(it) }
        backgroundCallback = null
    }

    /** Shut down the provider's coroutine scope. Call from Service.onDestroy. */
    fun shutdown() {
        sensorManager.unregisterListener(headingListener)
        stopBackgroundUpdates()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    fun getCurrentLocation(callback: (JSONObject?) -> Unit) {
        val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "getCurrentLocation called but ACCESS_FINE_LOCATION is DENIED")
            LogCollector.e(TAG, "getCurrentLocation called but ACCESS_FINE_LOCATION is DENIED")
            callback(null)
            return
        }
        Log.d(TAG, "getCurrentLocation starting (locator-primary)")
        val callbackOnce = OnceCallback(callback)
        scope.launch { runLocatorThenFused(callbackOnce) }
    }

    /**
     * Primary path. Collects signals, hits the Locator API (with rate-limit
     * and 3-retry exponential backoff inside the client), and on ANY failure
     * mode falls through to the Fused provider.
     */
    private suspend fun runLocatorThenFused(callback: OnceCallback) {
        if (AppConfig.getLocatorApiPreference(context) == AppConfig.LOCATOR_API_GPS) {
            Log.d(TAG, "LOCATOR_SKIP user preference is Regular GPS, going straight to fused")
            LogCollector.i(TAG, "Locator skipped per user preference (Regular GPS); using FusedLocationProviderClient")
            withContext(Dispatchers.Main) {
                attemptLocation(0, callback)
            }
            return
        }

        val signals = try {
            SignalCollector.collect(context)
        } catch (e: Exception) {
            Log.w(TAG, "SIGNALS_FAILED ${e.message}, going to fused")
            null
        }

        val locatorResult: LocatorResult? = if (signals != null && !signals.isEmpty()) {
            try {
                locatorClient.locate(signals)
            } catch (e: Exception) {
                Log.w(TAG, "LOCATOR_EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                LogCollector.w(TAG, "Locator call threw: ${e.message}")
                null
            }
        } else {
            Log.d(TAG, "LOCATOR_SKIP no signals available")
            null
        }

        if (locatorResult != null) {
            deliverLocatorSuccess(locatorResult, callback)
        } else {
            Log.d(TAG, "FALLBACK_FUSED starting fused provider path")
            LogCollector.i(TAG, "Falling back to FusedLocationProviderClient")
            withContext(Dispatchers.Main) {
                attemptLocation(0, callback)
            }
        }
    }

    private suspend fun deliverLocatorSuccess(result: LocatorResult, callback: OnceCallback) {
        val json = JSONObject().apply {
            put("lat", result.lat)
            put("lng", result.lon)
            put("accuracy", result.accuracy)
            put("source", SOURCE_LOCATOR)
            currentHeadingDegrees?.let { put("heading", it.toDouble()) }
        }
        withContext(Dispatchers.Main) {
            reverseGeocode(result.lat, result.lon) { address ->
                if (address != null) json.put("address", address)
                callback(json)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attemptLocation(attempt: Int, callback: OnceCallback) {
        val maxAttempts = 5         // 5 attempts x 3s = 15s total
        val retryDelayMs = 3_000L
        val cancellation = CancellationTokenSource()

        try {
            fusedClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellation.token
            ).addOnSuccessListener { location ->
                if (location != null) {
                    Log.d(TAG, "FUSED_OK attempt=${attempt + 1} lat=${location.latitude} lng=${location.longitude} acc=${location.accuracy}m")
                    LogCollector.i(TAG, "Fused location (attempt ${attempt + 1}): ${location.latitude}, ${location.longitude} acc=${location.accuracy}m")
                    val json = locationToJson(location)
                    reverseGeocode(location.latitude, location.longitude) { address ->
                        if (address != null) json.put("address", address)
                        callback(json)
                    }
                } else if (attempt + 1 < maxAttempts) {
                    Log.d(TAG, "FUSED_NULL attempt=${attempt + 1}/$maxAttempts, retrying")
                    LogCollector.w(TAG, "Fused attempt ${attempt + 1}/$maxAttempts returned null, retrying in ${retryDelayMs}ms")
                    Handler(Looper.getMainLooper()).postDelayed({
                        attemptLocation(attempt + 1, callback)
                    }, retryDelayMs)
                } else {
                    Log.e(TAG, "FUSED_EXHAUSTED all $maxAttempts attempts returned null, trying raw GPS")
                    LogCollector.e(TAG, "All $maxAttempts fused attempts failed; falling back to raw GPS provider")
                    attemptRawGps(callback)
                }
            }.addOnFailureListener { e ->
                if (attempt + 1 < maxAttempts) {
                    Log.d(TAG, "FUSED_FAIL attempt=${attempt + 1}/$maxAttempts: ${e.message}")
                    LogCollector.w(TAG, "Fused attempt ${attempt + 1}/$maxAttempts failed: ${e.message}, retrying")
                    Handler(Looper.getMainLooper()).postDelayed({
                        attemptLocation(attempt + 1, callback)
                    }, retryDelayMs)
                } else {
                    Log.e(TAG, "FUSED_FAIL_EXHAUSTED all $maxAttempts attempts: ${e.message}, trying raw GPS")
                    LogCollector.e(TAG, "All $maxAttempts fused attempts failed: ${e.message}; falling back to raw GPS provider")
                    attemptRawGps(callback)
                }
            }

            Handler(Looper.getMainLooper()).postDelayed({
                cancellation.cancel()
            }, TIMEOUT_MS)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Fused request exception: ${e.message}; falling back to raw GPS provider")
            attemptRawGps(callback)
        }
    }

    /**
     * Final tier: request a fresh fix directly from LocationManager.GPS_PROVIDER.
     * Bypasses Google Play Services entirely. NEVER returns a cached fix --
     * `getCurrentLocation` always asks the GPS chip for a new measurement.
     * On any failure (provider off, timeout, exception) we return null. We do
     * not fall back to last-known cached coordinates by design.
     */
    @SuppressLint("MissingPermission")
    private fun attemptRawGps(callback: OnceCallback) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.e(TAG, "RAW_GPS_DISABLED GPS_PROVIDER is off")
            LogCollector.e(TAG, "Raw GPS unavailable: GPS_PROVIDER disabled in system settings")
            callback(null)
            return
        }

        val cancellation = CancellationSignal()
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            cancellation.cancel()
            Log.e(TAG, "RAW_GPS_TIMEOUT no fix within ${TIMEOUT_MS}ms")
            LogCollector.e(TAG, "Raw GPS request timed out (${TIMEOUT_MS}ms)")
            callback(null)
        }
        handler.postDelayed(timeout, TIMEOUT_MS)

        try {
            locationManager.getCurrentLocation(
                LocationManager.GPS_PROVIDER,
                cancellation,
                context.mainExecutor
            ) { location ->
                handler.removeCallbacks(timeout)
                if (location != null) {
                    Log.d(TAG, "RAW_GPS_OK lat=${location.latitude} lng=${location.longitude} acc=${location.accuracy}m")
                    LogCollector.i(TAG, "Raw GPS fix: ${location.latitude}, ${location.longitude} acc=${location.accuracy}m")
                    val json = locationToJson(location).apply { put("source", SOURCE_GPS) }
                    reverseGeocode(location.latitude, location.longitude) { address ->
                        if (address != null) json.put("address", address)
                        callback(json)
                    }
                } else {
                    Log.e(TAG, "RAW_GPS_NULL provider returned null")
                    LogCollector.e(TAG, "Raw GPS returned null location")
                    callback(null)
                }
            }
        } catch (e: Exception) {
            handler.removeCallbacks(timeout)
            Log.e(TAG, "RAW_GPS_EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
            LogCollector.e(TAG, "Raw GPS request exception: ${e.message}")
            callback(null)
        }
    }

    private fun reverseGeocode(lat: Double, lng: Double, callback: (String?) -> Unit) {
        val responded = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())

        // Timeout: return null address if geocoding takes too long
        val timeoutRunnable = Runnable {
            if (responded.compareAndSet(false, true)) {
                LogCollector.w(TAG, "Reverse geocode timed out")
                callback(null)
            }
        }
        handler.postDelayed(timeoutRunnable, GEOCODE_TIMEOUT_MS)

        // Route through the active map provider's geocoder so this path works
        // regardless of which SDK is active (and needs no MapKit init when Google
        // is selected).
        try {
            MapProviders.active.geocoder.reverseGeocode(Point(lat, lng)) { result ->
                handler.removeCallbacks(timeoutRunnable)
                if (responded.compareAndSet(false, true)) {
                    val address = result?.fullAddress?.ifBlank { null } ?: result?.name
                    if (address != null) {
                        LogCollector.d(TAG, "Reverse geocoded: $address")
                    }
                    callback(address)
                }
            }
        } catch (e: Exception) {
            handler.removeCallbacks(timeoutRunnable)
            if (responded.compareAndSet(false, true)) {
                LogCollector.e(TAG, "Reverse geocode exception: ${e.message}")
                callback(null)
            }
        }
    }

    private fun locationToJson(location: Location): JSONObject {
        return JSONObject().apply {
            put("lat", location.latitude)
            put("lng", location.longitude)
            put("accuracy", location.accuracy.toDouble())
            put("source", SOURCE_FUSED)
            if (location.hasAltitude()) put("altitude", location.altitude)
            if (location.hasSpeed()) put("speed", location.speed.toDouble())
            currentHeadingDegrees?.let { put("heading", it.toDouble()) }
        }
    }

    /**
     * Wraps the external callback so at most one value is delivered even if
     * both the Locator path and the Fused path race to finish. Necessary
     * because the Fused fallback is kicked off inside the coroutine on Main
     * dispatcher and could theoretically deliver before a late Locator
     * result arrives (although the current flow is strictly sequential --
     * this is belt-and-braces against future refactors).
     *
     * Thread-safe: the AtomicBoolean CAS guarantees only the first invoke()
     * reaches the delegate regardless of which thread fires first.
     */
    private class OnceCallback(private val delegate: (JSONObject?) -> Unit) : (JSONObject?) -> Unit {
        private val fired = AtomicBoolean(false)
        override fun invoke(value: JSONObject?) {
            if (fired.compareAndSet(false, true)) {
                delegate(value)
            }
        }
    }
}
