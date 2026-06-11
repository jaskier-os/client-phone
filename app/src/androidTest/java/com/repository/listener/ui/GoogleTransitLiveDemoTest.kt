package com.repository.listener.ui

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.repository.listener.MainActivity
import com.repository.listener.config.AppConfig
import com.repository.navigation.NavigationManager
import com.repository.navigation.model.NavigationInstruction
import com.repository.navigation.model.InstructionType
import com.repository.navigation.model.RouteAlternative
import com.repository.navigation.model.RouteInfo
import com.repository.navigation.model.TransitSectionType
import com.repository.navigation.model.TransportMode
import com.repository.navigation.provider.MapProviders
import com.yandex.mapkit.geometry.Point
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Live, recordable demo of the step-advance fix on the GOOGLE provider:
 *   - forces provider = google
 *   - opens the Navigation tab (ViewPager2 position 5, no coordinate taps)
 *   - plans a real 3-point Istanbul TRANSIT journey:
 *       Taksim (Simon Hotel) -> Hagia Sophia (intermediate) -> Buyukada (ferry)
 *   - starts a mock journey along it at a slow speed
 *   - HOLDS the foreground nav screen ~45s so a concurrent screenrecord (phone +
 *     glasses) captures the active step strip advancing leg-to-leg.
 *
 * Not a pass/fail unit test -- it is a driver for visual verification + recording.
 * Run: adb shell am instrument -w -e class \
 *   com.repository.listener.ui.GoogleTransitLiveDemoTest \
 *   com.repository.listener.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class GoogleTransitLiveDemoTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext

    private companion object {
        const val NAV_TAB_INDEX = 5
        // 3-point Istanbul journey, origin first. Multimodal: walk + bus/metro/tram
        // + ferry (Buyukada / Princes' Islands is only reachable by ferry).
        // Origin: Taksim Simon Hotel, just off Taksim Square / Istiklal.
        val TAKSIM_SIMON_HOTEL = Point(41.0369, 28.9858)
        // Intermediate waypoint: Hagia Sophia mosque, Sultanahmet.
        val HAGIA_SOPHIA = Point(41.0086, 28.9802)
        // Destination: Buyukada, the largest of the Princes' Islands.
        val BUYUKADA = Point(40.8575, 29.1230)
    }

    private fun <T> onMain(block: () -> T): T {
        val holder = arrayOfNulls<Any>(1)
        instr.runOnMainSync { holder[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    @Test
    fun driveTransitJourneyForRecording() {
        // 1. Force Google.
        AppConfig.setMapProvider(ctx, AppConfig.MAP_PROVIDER_GOOGLE)
        onMain { MapProviders.switchTo(ctx.applicationContext, AppConfig.MAP_PROVIDER_GOOGLE) }

        // 2. Open the Navigation tab.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        SystemClock.sleep(2000)
        scenario.onActivity { act ->
            val pagerId = act.resources.getIdentifier("viewPager", "id", ctx.packageName)
            act.findViewById<ViewPager2>(pagerId)?.setCurrentItem(NAV_TAB_INDEX, false)
        }
        SystemClock.sleep(1500)

        val manager = NavigationManager.getInstance(ctx)

        // 3. Plan the real 3-point Istanbul ferry transit journey via the active
        //    (Google) provider. origin = Taksim, destination = Buyukada, with Hagia
        //    Sophia as an intermediate waypoint. NOTE: Routes API v2 returns NO
        //    alternative routes when intermediates are present, so this typically
        //    yields a single alternative; the FERRY/bus-leg selection below tolerates
        //    that by falling through to the first returned route.
        val routeLatch = CountDownLatch(1)
        var chosen: RouteAlternative? = null
        onMain {
            val waypoints = listOf(HAGIA_SOPHIA)
            manager.planJourneyAlternatives(TAKSIM_SIMON_HOTEL, BUYUKADA, TransportMode.TRANSIT, waypoints, null) { alts ->
                // Prefer an alternative that has BOTH a ferry (Buyukada forces one)
                // AND a bus leg (so the equivalent-line chips are visible), then any
                // ferry route, then any transit route, then anything returned.
                chosen = alts.firstOrNull { alt ->
                    val types = alt.routeInfo.transitSections.map { it.type }
                    types.contains(TransitSectionType.FERRY) && types.contains(TransitSectionType.BUS)
                } ?: alts.firstOrNull { alt ->
                    alt.routeInfo.transitSections.any { it.type == TransitSectionType.FERRY }
                } ?: alts.firstOrNull { it.routeInfo.transitSections.isNotEmpty() } ?: alts.firstOrNull()
                routeLatch.countDown()
            }
        }
        assertTrue("route alternatives timed out", routeLatch.await(25, TimeUnit.SECONDS))
        val route: RouteInfo = chosen?.routeInfo ?: error("no transit route returned")
        assertTrue("expected transit sections", route.transitSections.isNotEmpty())

        // 4. Select it and start a mock journey along the real polyline. Slow speed so
        //    several step transitions are visible during the recording window.
        onMain {
            chosen?.let { manager.selectAlternative(it) }
            manager.startMockJourneyWithRoute(route, speedMps = 45.0)
        }

        // 4a-bis. Capture the map with the steps panel COLLAPSED so the centered
        //     user arrow + the drawn route are both visible (arrow/path sync check).
        SystemClock.sleep(10000)
        run {
            val shot = instr.uiAutomation.takeScreenshot()
            if (shot != null) {
                val dir = java.io.File(ctx.getExternalFilesDir(null), "additional_test_output").apply { mkdirs() }
                java.io.FileOutputStream(java.io.File(dir, "arrow_sync.png")).use {
                    shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }

        // 4b. Expand the active-steps panel (the "Details" button) so the recording
        //     shows the step strip + the advancing highlight, not just the collapsed
        //     active view. Click by resource-id (no coordinate taps).
        SystemClock.sleep(1500)
        scenario.onActivity { act ->
            val btnId = act.resources.getIdentifier("activeDetailsButton", "id", "com.repository.navigation")
                .let { if (it != 0) it else act.resources.getIdentifier("activeDetailsButton", "id", ctx.packageName) }
            if (btnId != 0) act.findViewById<android.view.View?>(btnId)?.performClick()
        }

        // 5. Hold the foreground nav screen so the recording captures step advancement.
        SystemClock.sleep(45000)

        onMain { manager.stopMockJourney() }
        scenario.close()
    }
}
