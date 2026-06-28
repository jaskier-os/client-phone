package com.repository.navigation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.repository.navigation.db.Converters
import com.repository.navigation.db.NavigationDatabase
import com.repository.navigation.db.JourneySessionDao
import com.repository.navigation.model.JourneySession
import com.repository.navigation.model.TransportMode
import com.yandex.mapkit.geometry.Point
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionExpiryTest {

    private lateinit var db: NavigationDatabase
    private lateinit var dao: JourneySessionDao
    private lateinit var tracker: SessionTracker

    private val origin = Point(55.751244, 37.618423)      // Moscow center
    private val destination = Point(55.753215, 37.622504)  // ~300m away
    private val atDestination = Point(55.753200, 37.622480) // within 200m of destination
    private val farAway = Point(56.0, 38.0)                // clearly not at destination

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NavigationDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.journeySessionDao()
        tracker = SessionTracker(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun makeSession(
        createdAt: Long = 1_000_000L,
        etaSeconds: Long = 600,
    ): JourneySession {
        val etaMs = etaSeconds * 1000
        return JourneySession(
            from = origin,
            to = destination,
            transportMode = TransportMode.DRIVING,
            methodId = "masstransit",
            estimatedEtaSeconds = etaSeconds,
            createdAt = createdAt,
            plannedEndTime = createdAt + etaMs,
            expiryTime = createdAt + 2 * etaMs,
            isActive = true
        )
    }

    @Test
    fun expiryTimeIs2xEta() {
        val session = makeSession(createdAt = 1_000_000L, etaSeconds = 600)
        val etaMs = 600 * 1000L
        assertEquals(1_000_000L + 2 * etaMs, session.expiryTime)
    }

    @Test
    fun restoreBeforeExpiry_resumesNormally() = runBlocking {
        val session = makeSession(createdAt = 1_000_000L, etaSeconds = 600)
        dao.insert(Converters.toEntity(session))

        // Restore at a time before expiry (createdAt + 2x ETA = 2_200_000)
        val now = session.createdAt + 500_000
        val restored = tracker.restoreSession(farAway, now)

        // In-progress journey is resumed and stays active.
        assertNotNull(restored)
        assertTrue(restored!!.isActive)
        assertNotNull(dao.getActiveSession())
    }

    @Test
    fun restoreAfterExpiry_atDestination_deletesSession() = runBlocking {
        val session = makeSession(createdAt = 1_000_000L, etaSeconds = 600)
        dao.insert(Converters.toEntity(session))

        // Past expiry, at destination -- dead journey, deleted.
        val now = session.expiryTime + 1
        val restored = tracker.restoreSession(atDestination, now)

        assertNull(restored)
        assertNull(dao.getActiveSession())
    }

    @Test
    fun restoreAfterExpiry_notAtDestination_deletesSession() = runBlocking {
        val session = makeSession(createdAt = 1_000_000L, etaSeconds = 600)
        dao.insert(Converters.toEntity(session))

        // Past expiry is dead REGARDLESS of distance to destination: a long-abandoned
        // journey must NOT be revived (that stale ACTIVE state re-opened the glasses
        // MAP tab on reconnect/screen-wake). Must delete and return null.
        val now = session.expiryTime + 1
        val restored = tracker.restoreSession(farAway, now)

        assertNull(restored)
        assertNull(dao.getActiveSession())
    }

    @Test
    fun sessionDeactivation_works() = runBlocking {
        val session = makeSession()
        dao.insert(Converters.toEntity(session))

        val active = dao.getActiveSession()
        assertNotNull(active)

        dao.deactivate(active!!.id)

        // No active session after deactivation
        assertNull(dao.getActiveSession())

        // But record still exists
        val record = dao.getById(active.id)
        assertNotNull(record)
        assertFalse(record!!.isActive)
    }

    @Test
    fun deleteAllInactive_cleansUp() = runBlocking {
        val session = makeSession()
        dao.insert(Converters.toEntity(session))

        val active = dao.getActiveSession()!!
        dao.deactivate(active.id)

        dao.deleteAllInactive()

        assertNull(dao.getById(active.id))
    }

    @Test
    fun converters_roundTrip() {
        val session = JourneySession(
            id = 0,
            from = origin,
            to = destination,
            transportMode = TransportMode.TRANSIT,
            methodId = "masstransit",
            estimatedEtaSeconds = 1200,
            createdAt = 5_000_000L,
            plannedEndTime = 6_200_000L,
            expiryTime = 7_400_000L,
            waypoint = Point(55.752, 37.620),
            routePolyline = "encodedPolyline123",
            isActive = true
        )

        val entity = Converters.toEntity(session)
        val restored = Converters.toDomain(entity)

        assertEquals(session.from.latitude, restored.from.latitude, 0.0001)
        assertEquals(session.from.longitude, restored.from.longitude, 0.0001)
        assertEquals(session.to.latitude, restored.to.latitude, 0.0001)
        assertEquals(session.to.longitude, restored.to.longitude, 0.0001)
        assertEquals(session.transportMode, restored.transportMode)
        assertEquals(session.methodId, restored.methodId)
        assertEquals(session.estimatedEtaSeconds, restored.estimatedEtaSeconds)
        assertEquals(session.createdAt, restored.createdAt)
        assertEquals(session.plannedEndTime, restored.plannedEndTime)
        assertEquals(session.expiryTime, restored.expiryTime)
        assertNotNull(restored.waypoint)
        assertEquals(session.waypoint!!.latitude, restored.waypoint!!.latitude, 0.0001)
        assertEquals(session.waypoint!!.longitude, restored.waypoint!!.longitude, 0.0001)
        assertEquals(session.routePolyline, restored.routePolyline)
        assertEquals(session.isActive, restored.isActive)
    }

    @Test
    fun converters_nullWaypoint_roundTrip() {
        val session = JourneySession(
            from = origin,
            to = destination,
            transportMode = TransportMode.WALKING,
            methodId = "pedestrian",
            estimatedEtaSeconds = 300,
            createdAt = 1_000_000L,
            plannedEndTime = 1_300_000L,
            expiryTime = 1_600_000L,
            waypoint = null,
            routePolyline = null,
            isActive = true
        )

        val entity = Converters.toEntity(session)
        val restored = Converters.toDomain(entity)

        assertNull(restored.waypoint)
        assertNull(restored.routePolyline)
    }
}
