package com.repository.navigation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.repository.navigation.db.Converters
import com.repository.navigation.db.NavigationDatabase
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
class NavigationInstrumentedTest {

    private lateinit var db: NavigationDatabase
    private lateinit var sessionTracker: SessionTracker

    private val from = TestCoordinates.RED_SQUARE
    private val to = TestCoordinates.SHEREMETYEVO

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NavigationDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionTracker = SessionTracker(context)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun startSession_createsSession() = runBlocking {
        val now = System.currentTimeMillis()
        val etaSeconds = 3600L
        val etaMs = etaSeconds * 1000
        val session = JourneySession(
            from = from,
            to = to,
            transportMode = TransportMode.DRIVING,
            methodId = "driving_test",
            estimatedEtaSeconds = etaSeconds,
            createdAt = now,
            plannedEndTime = now + etaMs,
            expiryTime = now + 2 * etaMs
        )
        val dao = db.journeySessionDao()
        val id = dao.insert(Converters.toEntity(session))
        assertTrue("Session ID should be > 0", id > 0)

        val stored = dao.getActiveSession()
        assertNotNull(stored)
        assertEquals(id, stored!!.id)
        assertEquals("driving_test", stored.methodId)
    }

    @Test
    fun stopJourney_deletesSession() = runBlocking {
        val now = System.currentTimeMillis()
        val session = JourneySession(
            from = from,
            to = to,
            transportMode = TransportMode.DRIVING,
            methodId = "driving_test",
            estimatedEtaSeconds = 1800,
            createdAt = now,
            plannedEndTime = now + 1_800_000,
            expiryTime = now + 3_600_000
        )
        val dao = db.journeySessionDao()
        val id = dao.insert(Converters.toEntity(session))
        assertNotNull(dao.getActiveSession())

        dao.deleteById(id)
        assertNull(dao.getActiveSession())
    }

    @Test
    fun getActiveSession_returnsStoredEta() = runBlocking {
        val now = System.currentTimeMillis()
        val etaSeconds = 2400L
        val session = JourneySession(
            from = from,
            to = to,
            transportMode = TransportMode.TRANSIT,
            methodId = "transit_0",
            estimatedEtaSeconds = etaSeconds,
            createdAt = now,
            plannedEndTime = now + etaSeconds * 1000,
            expiryTime = now + 2 * etaSeconds * 1000
        )
        val dao = db.journeySessionDao()
        dao.insert(Converters.toEntity(session))

        val stored = dao.getActiveSession()
        assertNotNull(stored)
        assertEquals(etaSeconds, stored!!.estimatedEtaSeconds)
        assertTrue("ETA should be > 0", stored.estimatedEtaSeconds > 0)
    }

    @Test
    fun sessionWithWaypoint_persistsCorrectly() = runBlocking {
        val now = System.currentTimeMillis()
        val waypoint = TestCoordinates.GORKY_PARK
        val session = JourneySession(
            from = from,
            to = to,
            transportMode = TransportMode.DRIVING,
            methodId = "driving_waypoint",
            estimatedEtaSeconds = 4000,
            createdAt = now,
            plannedEndTime = now + 4_000_000,
            expiryTime = now + 8_000_000,
            waypoint = waypoint
        )
        val dao = db.journeySessionDao()
        val id = dao.insert(Converters.toEntity(session))

        val stored = dao.getById(id)
        assertNotNull(stored)
        assertNotNull(stored!!.waypointLat)
        assertNotNull(stored.waypointLng)
        assertEquals(waypoint.latitude, stored.waypointLat!!, 0.0001)
        assertEquals(waypoint.longitude, stored.waypointLng!!, 0.0001)
    }
}
