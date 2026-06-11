package com.repository.navigation

import android.content.Context
import com.repository.navigation.db.Converters
import com.repository.navigation.db.NavigationDatabase
import com.repository.navigation.model.JourneySession
import com.yandex.mapkit.geometry.Point

class SessionTracker(context: Context) {

    private val dao = NavigationDatabase.getInstance(context).journeySessionDao()

    companion object {
        private const val EXTENSION_MS = 3_600_000L // 1 hour
        private const val DESTINATION_RADIUS_METERS = 200.0
    }

    suspend fun startSession(session: JourneySession): JourneySession {
        val id = dao.insert(Converters.toEntity(session))
        return session.copy(id = id)
    }

    suspend fun getActiveSession(): JourneySession? {
        return dao.getActiveSession()?.let { Converters.toDomain(it) }
    }

    /**
     * Restore a session after app restart.
     *
     * Rules:
     * - If before expiryTime: resume normally
     * - If past expiryTime AND at destination: delete from DB, return null
     * - If past expiryTime AND NOT at destination: extend expiry by 1 hour, return extended session
     */
    suspend fun restoreSession(currentLocation: Point, now: Long = System.currentTimeMillis()): JourneySession? {
        val entity = dao.getActiveSession() ?: return null
        val session = Converters.toDomain(entity)

        if (now <= session.expiryTime) {
            return session
        }

        // Past expiry
        if (isAtDestination(currentLocation, session.to)) {
            dao.deleteById(session.id)
            return null
        }

        // Past expiry, not at destination -- extend
        val newExpiry = now + EXTENSION_MS
        dao.updateExpiry(session.id, newExpiry)
        return session.copy(expiryTime = newExpiry)
    }

    suspend fun endSession(sessionId: Long) {
        dao.deleteById(sessionId)
    }

    suspend fun updateStepIndex(sessionId: Long, index: Int) {
        dao.updateStepIndex(sessionId, index)
    }

    suspend fun deactivateSession(sessionId: Long) {
        dao.deactivate(sessionId)
    }

    private fun isAtDestination(current: Point, destination: Point): Boolean {
        val distance = distanceMeters(current, destination)
        return distance <= DESTINATION_RADIUS_METERS
    }

    private fun distanceMeters(a: Point, b: Point): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val sinLat = Math.sin(dLat / 2)
        val sinLng = Math.sin(dLng / 2)
        val h = sinLat * sinLat +
            Math.cos(Math.toRadians(a.latitude)) *
            Math.cos(Math.toRadians(b.latitude)) *
            sinLng * sinLng
        return 2 * earthRadius * Math.asin(Math.sqrt(h))
    }
}
