package com.repository.navigation.provider

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.yandex.mapkit.geometry.Point

/**
 * [LocationEngine] backed by Google Play Services' fused location provider, with
 * a hand-rolled polyline simulator for mock journeys.
 *
 * Behaviour mirrors [YandexLocationEngine]:
 *  - Subscriber identity is by [GeoFixListener] reference. A per-listener
 *    adapter (here, the shared fused callback fans out to all listeners) is
 *    tracked so unsubscribe removes exactly the right subscriber.
 *  - enableMock swaps the source: real fused updates stop and the SAME
 *    subscribers are driven by the simulator. disableMock resumes real fixes
 *    for the same subscribers.
 *  - Every GeoFix delivery and every callback is dispatched on the MAIN thread.
 */
class GoogleLocationEngine(
    context: Context
) : LocationEngine {

    private val appContext = context.applicationContext
    private val fused: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)

    private val main = Handler(Looper.getMainLooper())

    // Subscriber set. Synchronized like Yandex; identity is by listener reference.
    private val listeners = LinkedHashSet<GeoFixListener>()

    private var mockSimulator: MockSimulator? = null

    // Whether the single shared fused callback is currently registered.
    private var realUpdatesActive = false

    @Volatile
    private var _mockActive: Boolean = false
    override val mockActive: Boolean get() = _mockActive

    // One shared callback fans out to all current subscribers. Fused already
    // delivers on the main looper (we request it explicitly), but we re-post to
    // be defensive about the threading contract.
    private val sharedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val fix = toGeoFix(loc)
            dispatch(fix)
        }
    }

    private fun toGeoFix(loc: Location): GeoFix = GeoFix(
        lat = loc.latitude,
        lng = loc.longitude,
        headingDeg = if (loc.hasBearing()) loc.bearing else null,
        speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
        timeMs = if (loc.time != 0L) loc.time else System.currentTimeMillis()
    )

    private fun dispatch(fix: GeoFix) {
        val snapshot: List<GeoFixListener>
        synchronized(this) { snapshot = listeners.toList() }
        main.post { for (l in snapshot) l.onFix(fix) }
    }

    @SuppressLint("MissingPermission")
    private fun startRealUpdates() {
        if (realUpdatesActive) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        try {
            fused.requestLocationUpdates(request, sharedCallback, Looper.getMainLooper())
            realUpdatesActive = true
        } catch (e: SecurityException) {
            Log.e(TAG, "startRealUpdates: missing location permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "startRealUpdates failed", e)
        }
    }

    private fun stopRealUpdates() {
        if (!realUpdatesActive) return
        try {
            fused.removeLocationUpdates(sharedCallback)
        } catch (e: Exception) {
            Log.e(TAG, "stopRealUpdates failed", e)
        }
        realUpdatesActive = false
    }

    @Synchronized
    override fun subscribe(listener: GeoFixListener) {
        if (!listeners.add(listener)) return
        // Start real updates only when not mocking and this is the first sub.
        if (!_mockActive && listeners.size == 1) {
            startRealUpdates()
        }
    }

    @Synchronized
    override fun unsubscribe(listener: GeoFixListener) {
        if (!listeners.remove(listener)) return
        if (listeners.isEmpty()) {
            stopRealUpdates()
        }
    }

    @Synchronized
    override fun enableMock(polyline: List<Point>, speedMps: Double, onFinished: () -> Unit) {
        if (polyline.size < 2) {
            Log.w(TAG, "enableMock: polyline needs >=2 points, ignoring")
            return
        }
        // Stop real updates; the SAME subscribers will now be driven by the sim.
        stopRealUpdates()
        _mockActive = true
        val sim = MockSimulator(polyline, speedMps) { fix -> dispatch(fix) }
        sim.start {
            // onFinished runs on main (Handler). Stop mock state and notify.
            synchronized(this) {
                _mockActive = false
                mockSimulator = null
            }
            onFinished()
        }
        mockSimulator = sim
    }

    @Synchronized
    override fun disableMock() {
        mockSimulator?.stop()
        mockSimulator = null
        if (!_mockActive) return
        _mockActive = false
        // Resume real updates for existing subscribers.
        if (listeners.isNotEmpty()) {
            startRealUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    override fun requestSingleFix(callback: (GeoFix?) -> Unit) {
        // Works even with no subscribers (uses its own one-shot request).
        // Guard so the callback fires exactly once, and a main-thread timeout so
        // getCurrentLocation can never hang restoreSession forever.
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val cts = com.google.android.gms.tasks.CancellationTokenSource()
        val timeout = Runnable {
            if (done.compareAndSet(false, true)) {
                cts.cancel()
                Log.w(TAG, "requestSingleFix: timed out after ${SINGLE_FIX_TIMEOUT_MS}ms")
                callback(null)
            }
        }
        main.postDelayed(timeout, SINGLE_FIX_TIMEOUT_MS)
        try {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        if (done.compareAndSet(false, true)) {
                            main.removeCallbacks(timeout)
                            main.post { callback(toGeoFix(loc)) }
                        }
                    } else {
                        // Fall back to last known location (also guarded).
                        lastLocationFallback(done, timeout, callback)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "requestSingleFix: getCurrentLocation failed", e)
                    lastLocationFallback(done, timeout, callback)
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "requestSingleFix: missing location permission", e)
            if (done.compareAndSet(false, true)) {
                main.removeCallbacks(timeout)
                main.post { callback(null) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestSingleFix failed", e)
            if (done.compareAndSet(false, true)) {
                main.removeCallbacks(timeout)
                main.post { callback(null) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastLocationFallback(
        done: java.util.concurrent.atomic.AtomicBoolean,
        timeout: Runnable,
        callback: (GeoFix?) -> Unit
    ) {
        try {
            fused.lastLocation
                .addOnSuccessListener { loc ->
                    if (done.compareAndSet(false, true)) {
                        main.removeCallbacks(timeout)
                        main.post { callback(loc?.let { toGeoFix(it) }) }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "requestSingleFix: lastLocation failed", e)
                    if (done.compareAndSet(false, true)) {
                        main.removeCallbacks(timeout)
                        main.post { callback(null) }
                    }
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "lastLocationFallback: missing location permission", e)
            if (done.compareAndSet(false, true)) {
                main.removeCallbacks(timeout)
                main.post { callback(null) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "lastLocationFallback failed", e)
            if (done.compareAndSet(false, true)) {
                main.removeCallbacks(timeout)
                main.post { callback(null) }
            }
        }
    }

    /**
     * Walks [polyline] at [speedMps], ticking every ~1s. At each tick it
     * advances speed*dt metres along the segments (linear lat/lng interpolation
     * between vertices, segment lengths measured by haversine) and emits a
     * GeoFix with heading = bearing toward the next vertex. Emits on MAIN.
     */
    private class MockSimulator(
        private val polyline: List<Point>,
        private val speedMps: Double,
        private val emit: (GeoFix) -> Unit
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var onFinished: (() -> Unit)? = null
        private var segIndex = 0
        private var segProgressM = 0.0
        private var running = false

        fun start(onFinished: () -> Unit) {
            this.onFinished = onFinished
            running = true
            handler.post(tick)
        }

        fun stop() {
            running = false
            handler.removeCallbacks(tick)
        }

        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                // Emit at the current position FIRST (first tick is the route
                // origin, polyline[0], since segIndex=0 and segProgressM=0), then
                // advance one tick worth of distance for the next emission. All of
                // the per-tick math lives in the pure [step] function so it can be
                // unit-tested on the plain JVM (the Handler never fires there).
                val result = step(polyline, segIndex, segProgressM, speedMps)
                segIndex = result.nextSegIndex
                segProgressM = result.nextSegProgressM
                emit(result.fix)
                if (result.finished) {
                    running = false
                    onFinished?.invoke()
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }
    }

    /** One pure simulator tick result: the GeoFix to emit plus the advanced state. */
    @VisibleForTesting
    internal data class SimStep(
        val fix: GeoFix,
        val nextSegIndex: Int,
        val nextSegProgressM: Double,
        val finished: Boolean
    )

    companion object {
        private const val TAG = "GoogleLocationEngine"
        private const val SINGLE_FIX_TIMEOUT_MS = 8000L

        /**
         * Pure per-tick step of the mock simulator, mirroring the original tick
         * loop exactly: from [segIndex]/[segProgressM] it walks forward over
         * zero-or-fully-consumed segments, emits a GeoFix at the interpolated
         * position with heading toward the next vertex (speed [speedMps]), and
         * advances [speedMps] metres for the next call. When the end of
         * [polyline] is reached it emits the last point with speed 0, heading
         * null, and finished=true. Extracted as a @VisibleForTesting seam because
         * Handler.postDelayed never fires on the JVM unit-test classpath.
         */
        @VisibleForTesting
        internal fun step(
            polyline: List<Point>,
            segIndex: Int,
            segProgressM: Double,
            speedMps: Double
        ): SimStep {
            var idx = segIndex
            var prog = segProgressM
            while (idx < polyline.size - 1) {
                val a = polyline[idx]
                val b = polyline[idx + 1]
                val segLen = haversine(a.latitude, a.longitude, b.latitude, b.longitude)
                // A zero-length segment (duplicate consecutive vertex) carries no
                // distance: skip it instead of emitting on it, otherwise the walk
                // is pinned on the duplicate vertex forever and onFinished never
                // fires. Only emit while there is real remaining distance.
                if (prog < segLen) {
                    val frac = prog / segLen
                    val lat = a.latitude + (b.latitude - a.latitude) * frac
                    val lng = a.longitude + (b.longitude - a.longitude) * frac
                    val hd = bearing(a.latitude, a.longitude, b.latitude, b.longitude).toFloat()
                    return SimStep(
                        fix = GeoFix(
                            lat = lat,
                            lng = lng,
                            headingDeg = hd,
                            speedMps = speedMps,
                            timeMs = System.currentTimeMillis()
                        ),
                        nextSegIndex = idx,
                        nextSegProgressM = prog + speedMps * 1.0,
                        finished = false
                    )
                }
                prog -= segLen
                idx++
            }
            val last = polyline.last()
            return SimStep(
                fix = GeoFix(
                    lat = last.latitude,
                    lng = last.longitude,
                    headingDeg = null,
                    speedMps = 0.0,
                    timeMs = System.currentTimeMillis()
                ),
                nextSegIndex = idx,
                nextSegProgressM = prog,
                finished = true
            )
        }

        private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
            return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        }

        private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLon = Math.toRadians(lon2 - lon1)
            val y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2))
            val x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2)) -
                Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon)
            return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
        }
    }
}
