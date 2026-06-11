package com.repository.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.repository.navigation.model.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RouteEngine tests that verify setup and constants without calling MapKit routers.
 * Full route query tests require device integration testing with the installed app
 * (MapKit native platform thread + network + location permissions).
 */
@RunWith(AndroidJUnit4::class)
class RouteEngineTest {

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            NavigationModule.init(context)
        }
    }

    @Test
    fun testCoordinatesAreValid() {
        assertNotNull(TestCoordinates.RED_SQUARE)
        assertNotNull(TestCoordinates.BOLSHOI)
        assertNotNull(TestCoordinates.MSU)
        assertNotNull(TestCoordinates.VDNKH)
        assertNotNull(TestCoordinates.GORKY_PARK)
        assertNotNull(TestCoordinates.SHEREMETYEVO)

        // Verify Moscow latitude/longitude ranges
        val coords = listOf(
            TestCoordinates.RED_SQUARE,
            TestCoordinates.BOLSHOI,
            TestCoordinates.MSU,
            TestCoordinates.VDNKH,
            TestCoordinates.GORKY_PARK,
            TestCoordinates.SHEREMETYEVO
        )
        for (point in coords) {
            assert(point.latitude in 55.0..56.5) { "Latitude ${point.latitude} out of Moscow range" }
            assert(point.longitude in 37.0..38.0) { "Longitude ${point.longitude} out of Moscow range" }
        }
    }

    @Test
    fun transportModesAreComplete() {
        val modes = TransportMode.values()
        assertEquals(4, modes.size)
        assert(TransportMode.DRIVING in modes)
        assert(TransportMode.WALKING in modes)
        assert(TransportMode.TRANSIT in modes)
        assert(TransportMode.BICYCLE in modes)
    }

    @Test
    fun mapKitInitializesWithoutCrash() {
        // If we got here, MapKit init + setApiKey succeeded
        val mapKit = com.yandex.mapkit.MapKitFactory.getInstance()
        assertNotNull(mapKit)
    }
}
