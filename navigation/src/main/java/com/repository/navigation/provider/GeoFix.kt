package com.repository.navigation.provider

/**
 * Provider-neutral single location fix. Decouples the navigation stack from
 * Yandex's com.yandex.mapkit.location.Location so a Google FusedLocation source
 * (or a synthetic mock ticker) can feed the same downstream listeners.
 */
data class GeoFix(
    val lat: Double,
    val lng: Double,
    val headingDeg: Float?,
    val speedMps: Double?,
    val timeMs: Long
)

/**
 * Receives [GeoFix] updates. Callbacks are always delivered on the MAIN thread
 * (Yandex impl is already main-thread; Google impls hop to IO then post back).
 * Identity (===) of the listener instance is significant: LocationEngine
 * implementations key their internal adapter map on it so the real<->mock swap
 * can re-target the same subscriber.
 */
fun interface GeoFixListener {
    fun onFix(fix: GeoFix)
}
