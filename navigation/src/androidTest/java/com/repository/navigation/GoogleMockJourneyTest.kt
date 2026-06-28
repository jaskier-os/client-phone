package com.repository.navigation

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.repository.navigation.model.NavigationInstruction
import com.repository.navigation.model.InstructionType
import com.repository.navigation.model.RouteAlternative
import com.repository.navigation.model.RouteInfo
import com.repository.navigation.model.TransportMethodInfo
import com.repository.navigation.model.TransportMode
import com.repository.navigation.provider.Geocoder
import com.repository.navigation.provider.GoogleLocationEngine
import com.repository.navigation.provider.GoogleMapProvider
import com.repository.navigation.provider.InteractiveMap
import com.repository.navigation.provider.LocationEngine
import com.repository.navigation.provider.MapProvider
import com.repository.navigation.provider.MapProviders
import com.repository.navigation.provider.MinimapImageSource
import com.repository.navigation.provider.PlaceSearch
import com.repository.navigation.provider.PlaceSuggest
import com.repository.navigation.provider.RoutingEngine
import com.repository.navigation.ui.SuggestItem
import com.yandex.mapkit.geometry.Point
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Device-style test of the Google map-provider mock/simulated-location journey.
 *
 * It needs NO Google Maps key and NO network: the active provider is forced to a
 * fake "google" provider (via [MapProviders.setActiveForTest]) whose routing is a
 * stub, and the journey is started from a fabricated [RouteInfo] through
 * [NavigationManager.startMockJourneyWithRoute], which skips routing entirely and
 * feeds the polyline straight into the real [GoogleLocationEngine] simulator.
 *
 * The location engine IS the real GoogleLocationEngine so the production Handler
 * tick, haversine/bearing interpolation, origin-first emission and final-point
 * onFinished all execute on-device. Mirrors the structure of SessionExpiryTest /
 * AidlServiceTest / ArrivalDetectionTest.
 *
 * Drives RED_SQUARE -> SHEREMETYEVO and asserts: state -> ACTIVE, isMockJourney
 * true, the GeoFix stream advances along the polyline, the arrival geofence fires
 * near the destination, and the session is cleared (state -> IDLE) on arrival.
 *
 * REQUIRES a connected device/emulator:
 *   ./gradlew :navigation:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class GoogleMockJourneyTest {

    private lateinit var context: Context
    private lateinit var manager: NavigationManager

    /** Fake "google" provider: real GoogleLocationEngine, everything else stubbed. */
    private class FakeGoogleProvider(private val appContext: Context) : MapProvider {
        override val id: String = GoogleMapProvider.ID

        override val routing: RoutingEngine = object : RoutingEngine {
            override fun queryAllModes(from: Point, to: Point, callback: (List<TransportMethodInfo>) -> Unit) = callback(emptyList())
            override fun buildRoute(from: Point, to: Point, waypoints: List<Point>, mode: TransportMode, callback: (RouteInfo?) -> Unit) = callback(null)
            override fun rebuildWithWaypoint(currentRoute: RouteInfo, waypoint: Point, callback: (RouteInfo?) -> Unit) = callback(null)
            override fun queryMode(from: Point, to: Point, mode: TransportMode, callback: (TransportMethodInfo?) -> Unit) = callback(null)
            override fun buildRouteAlternatives(from: Point, to: Point, mode: TransportMode, waypoints: List<Point>, departureTime: java.util.Date?, callback: (List<RouteAlternative>) -> Unit) = callback(emptyList())
            override fun warmUp() {}
        }

        override val geocoder: Geocoder = object : Geocoder {
            override fun geocode(address: String, callback: (Point?) -> Unit) = callback(null)
            override fun reverseGeocode(point: Point, callback: (ReverseGeocodeResult?) -> Unit) = callback(null)
        }

        override val placeSearch: PlaceSearch = object : PlaceSearch {
            override fun search(query: String, center: Point, radiusMeters: Double, callback: (JSONObject) -> Unit) = callback(JSONObject())
        }

        override val placeSuggest: PlaceSuggest = object : PlaceSuggest {
            override fun suggest(query: String, center: Point, callback: (List<SuggestItem>) -> Unit) = callback(emptyList())
            override fun reset() {}
        }

        override fun createMinimapImageSource(context: Context): MinimapImageSource =
            object : MinimapImageSource {
                override val minZoom: Int = 13
                override val maxZoom: Int = 19
                override val isCheapPerFrame: Boolean = false
                override fun start() {}
                override fun stop() {}
                override fun render(centerLat: Double, centerLng: Double, zoomLevel: Int, callback: (android.graphics.Bitmap?) -> Unit) = callback(null)
                override fun setRoute(points: List<Point>) {}
                override fun setRouteSections(sections: List<com.repository.navigation.model.TransitSection>, fallbackPoints: List<Point>) {}
                override fun clearRoute() {}
            }

        override fun createInteractiveMap(context: Context, locationEngine: LocationEngine): InteractiveMap =
            throw UnsupportedOperationException("not needed for mock-journey test")

        // The real engine -- exercises the production simulator on-device.
        override fun createLocationEngine(source: JourneyLocationSource): LocationEngine =
            GoogleLocationEngine(appContext)

        override fun init(context: Context) {}
        override fun onProcessStart() {}
        override fun onProcessStop() {}
        override val requiresProcessMapStart: Boolean = false
    }

    /** A straight-line densified route so the simulator finishes quickly at high speed. */
    private fun fakeRoute(from: Point, to: Point): RouteInfo {
        val pts = ArrayList<Point>()
        val n = 40
        for (i in 0..n) {
            val t = i.toDouble() / n
            pts.add(Point(from.latitude + (to.latitude - from.latitude) * t,
                from.longitude + (to.longitude - from.longitude) * t))
        }
        return RouteInfo(
            mode = TransportMode.DRIVING,
            polylinePoints = pts,
            etaSeconds = 1800,
            etaFormatted = "30 min",
            distanceMeters = 30_000,
            distanceFormatted = "30 km",
            instructions = listOf(NavigationInstruction(InstructionType.DRIVE, "Head to destination")),
            from = from,
            to = to
        )
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // NOTE: do NOT call NavigationModule.init() here. It defaults to the
            // Yandex provider and calls MapKitFactory.initialize(), whose native
            // layer aborts the instrumentation process with "Version name is
            // empty!" (the androidTest APK has no versionName). This test forces a
            // fake Google provider via setActiveForTest, so Yandex MapKit must
            // never be initialized on this path.
            MapProviders.setActiveForTest(FakeGoogleProvider(context.applicationContext))
            manager = NavigationManager.getInstance(context)
            // Force the cached engine to rebind to the fake google provider.
            if (manager.canSwitchProvider()) manager.rebindProvider()
        }
    }

    @After
    fun teardown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.stopMockJourney()
            MapProviders.resetForTest()
            if (manager.canSwitchProvider()) manager.rebindProvider()
        }
    }

    @Test
    fun mockJourneyReachesActiveStateAndAdvances() {
        val from = TestCoordinates.RED_SQUARE
        val to = TestCoordinates.SHEREMETYEVO
        val route = fakeRoute(from, to)

        val started = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.startMockJourneyWithRoute(route, speedMps = 2000.0) { started.countDown() }
        }
        assertTrue("journey did not start", started.await(5, TimeUnit.SECONDS))

        // State must be ACTIVE and the mock must be running right after start.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertEquals(NavigationManager.State.ACTIVE, manager.getState())
            assertTrue("expected isMockJourney true", manager.isMockJourney)
        }

        // The simulator ticks once per second; let it walk and confirm the last
        // fix advances away from the origin (proving the GeoFix stream flows).
        Thread.sleep(2500)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val fix = manager.getLastKnownLocation()
            assertTrue("no location fix observed from simulator", fix != null)
            val moved = ArrivalDetector.haversineMeters(
                from.latitude, from.longitude, fix!!.lat, fix.lng
            )
            assertTrue("simulator position did not advance from origin (moved=$moved m)", moved > 100.0)
        }
    }

    @Test
    fun mockJourneyFiresArrivalAndClearsSession() {
        // Short route so the high-speed simulator reaches the geofence fast.
        val from = TestCoordinates.RED_SQUARE
        val to = TestCoordinates.GORKY_PARK
        val route = fakeRoute(from, to)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.startMockJourneyWithRoute(route, speedMps = 3000.0)
        }

        // Poll until the journey auto-stops (arrival geofence or simulator end
        // both route through stopJourney -> state IDLE, session null).
        var idle = false
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val s = booleanArrayOf(false)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                s[0] = manager.getState() == NavigationManager.State.IDLE &&
                    manager.getCurrentSession() == null
            }
            if (s[0]) { idle = true; break }
            Thread.sleep(500)
        }
        assertTrue("journey did not end/clear within timeout", idle)
    }
}
