package com.repository.navigation

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.repository.navigation.db.Converters
import com.repository.navigation.db.JourneySessionDao
import com.repository.navigation.db.NavigationDatabase
import com.repository.navigation.model.JourneySession
import com.yandex.mapkit.geometry.Point

class SessionTracker private constructor(private val dao: JourneySessionDao) {

    constructor(context: Context) : this(
        NavigationDatabase.getInstance(context).journeySessionDao()
    )

    @VisibleForTesting
    internal constructor(db: NavigationDatabase) : this(db.journeySessionDao())

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
     * - If before expiryTime (createdAt + 2x ETA): resume the in-progress journey.
     * - If past expiryTime: the journey is dead -- delete it and return null.
     *
     * A journey is NOT auto-resumed past its expiry. Previously a past-expiry
     * session that wasn't near its destination had its expiry extended by an hour
     * and was returned, which revived long-abandoned journeys forever: the stale
     * ACTIVE state made the glasses re-open the MAP tab on every reconnect/screen
     * wake (e.g. an FN-button press). [currentLocation] is no longer consulted but
     * is kept in the signature for the caller.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun restoreSession(currentLocation: Point, now: Long = System.currentTimeMillis()): JourneySession? {
        val entity = dao.getActiveSession() ?: return null
        val session = Converters.toDomain(entity)

        if (now <= session.expiryTime) {
            return session
        }

        // Past expiry -- dead journey. Delete so it can never be revived as ACTIVE.
        dao.deleteById(session.id)
        return null
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
}
