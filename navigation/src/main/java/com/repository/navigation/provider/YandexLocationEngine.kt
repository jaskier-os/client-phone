package com.repository.navigation.provider

import android.util.Log
import com.repository.navigation.JourneyLocationSource
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.location.Location
import com.yandex.mapkit.location.LocationListener
import com.yandex.mapkit.location.LocationStatus
import com.yandex.mapkit.location.Purpose
import com.yandex.mapkit.location.SubscriptionSettings
import com.yandex.mapkit.location.UseInBackground

/**
 * Yandex implementation of [LocationEngine]. Wraps the existing
 * JourneyLocationSource (which owns the real<->simulator swap) and adapts Yandex
 * LocationListener callbacks to neutral GeoFix.
 *
 * CRITICAL (audit S4): keep ONE Yandex LocationListener adapter per GeoFixListener
 * instance, keyed on listener identity, so JourneyLocationSource's identity-based
 * (=== listener) removal across the real<->simulator swap still works. All swap
 * methods stay @Synchronized via the underlying source.
 */
class YandexLocationEngine(
    private val source: JourneyLocationSource
) : LocationEngine {

    companion object {
        private const val TAG = "YandexLocationEngine"
    }

    private val adapters = HashMap<GeoFixListener, LocationListener>()

    private val subscriptionSettings =
        SubscriptionSettings(UseInBackground.ALLOW, Purpose.GENERAL)

    override val mockActive: Boolean get() = source.mockActive

    @Synchronized
    override fun subscribe(listener: GeoFixListener) {
        val adapter = adapters.getOrPut(listener) { makeAdapter(listener) }
        source.subscribe(subscriptionSettings, adapter)
    }

    @Synchronized
    override fun unsubscribe(listener: GeoFixListener) {
        val adapter = adapters.remove(listener) ?: return
        source.unsubscribe(adapter)
    }

    override fun enableMock(polyline: List<Point>, speedMps: Double, onFinished: () -> Unit) =
        source.enableMock(polyline, speedMps, onFinished)

    override fun disableMock() = source.disableMock()

    override fun requestSingleFix(callback: (GeoFix?) -> Unit) {
        // Works without a prior subscribe: spin up a transient Yandex location
        // manager just for one update. Yandex delivers on MAIN already.
        val mgr = MapKitFactory.getInstance().createLocationManager()
        val listener = object : LocationListener {
            override fun onLocationUpdated(location: Location) {
                callback(location.toGeoFix())
            }
            override fun onLocationStatusUpdated(status: LocationStatus) {
                if (status == LocationStatus.NOT_AVAILABLE) {
                    Log.w(TAG, "requestSingleFix: location not available")
                    callback(null)
                }
            }
        }
        mgr.requestSingleUpdate(listener)
    }

    private fun makeAdapter(listener: GeoFixListener): LocationListener =
        object : LocationListener {
            override fun onLocationUpdated(location: Location) {
                listener.onFix(location.toGeoFix())
            }
            override fun onLocationStatusUpdated(status: LocationStatus) {}
        }

    private fun Location.toGeoFix(): GeoFix {
        val pos = position
        return GeoFix(
            lat = pos.latitude,
            lng = pos.longitude,
            headingDeg = heading?.toFloat(),
            speedMps = speed,
            timeMs = System.currentTimeMillis()
        )
    }
}
