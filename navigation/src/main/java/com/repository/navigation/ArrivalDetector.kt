package com.repository.navigation

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ArrivalDetector(
    private val arrivalRadiusMeters: Double = 50.0,
    private val onArrival: () -> Unit
) {

    @Volatile private var destLat: Double = 0.0
    @Volatile private var destLng: Double = 0.0
    @Volatile private var hasDestination: Boolean = false
    @Volatile private var arrived: Boolean = false

    fun setDestination(lat: Double, lng: Double) {
        destLat = lat
        destLng = lng
        hasDestination = true
        arrived = false
    }

    fun updatePosition(lat: Double, lng: Double) {
        if (!hasDestination || arrived) return
        val distance = haversineMeters(lat, lng, destLat, destLng)
        if (distance <= arrivalRadiusMeters) {
            arrived = true
            onArrival()
        }
    }

    fun reset() {
        hasDestination = false
        arrived = false
        destLat = 0.0
        destLng = 0.0
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val sinLat = sin(dLat / 2)
            val sinLng = sin(dLng / 2)
            val h = sinLat * sinLat +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sinLng * sinLng
            return 2 * EARTH_RADIUS_METERS * asin(sqrt(h))
        }
    }
}
