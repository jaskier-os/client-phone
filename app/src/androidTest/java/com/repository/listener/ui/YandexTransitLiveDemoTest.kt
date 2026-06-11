package com.repository.listener.ui

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.repository.listener.MainActivity
import com.repository.listener.config.AppConfig
import com.repository.navigation.NavigationManager
import com.repository.navigation.model.RouteAlternative
import com.repository.navigation.model.RouteInfo
import com.repository.navigation.model.TransportMode
import com.repository.navigation.provider.MapProviders
import com.yandex.mapkit.geometry.Point
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Live, recordable regression demo on the YANDEX provider (mirror of
 * GoogleTransitLiveDemoTest):
 *   - forces provider = yandex
 *   - opens the Navigation tab (ViewPager2 position 5, no coordinate taps)
 *   - plans a real Moscow TRANSIT journey (Yandex has transit coverage in Russia;
 *     Google does not, which is why the Google demo uses Istanbul)
 *   - starts a mock journey along it at a slow speed
 *   - captures the collapsed map (centered user arrow + drawn route), then expands
 *     the steps panel and HOLDS the foreground nav screen so a concurrent
 *     screenrecord (phone + glasses) captures the active step strip advancing.
 *
 * Not a pass/fail unit test -- it is a driver for visual verification + recording,
 * used to confirm the Yandex path did not regress after the Google/zoom work.
 * Run: adb shell am instrument -w -e class \
 *   com.repository.listener.ui.YandexTransitLiveDemoTest \
 *   com.repository.listener.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class YandexTransitLiveDemoTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext

    private companion object {
        const val NAV_TAB_INDEX = 5
        // Moscow transit journey (Yandex has Russian transit data). Origin first.
        // Komsomolskaya / Three Stations area -> Red Square / Okhotny Ryad.
        val MOSCOW_KOMSOMOLSKAYA = Point(55.7758, 37.6556)
        val MOSCOW_RED_SQUARE = Point(55.7539, 37.6208)
    }

    private fun <T> onMain(block: () -> T): T {
        val holder = arrayOfNulls<Any>(1)
        instr.runOnMainSync { holder[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    @Test
    fun driveTransitJourneyForRecording() {
        // 1. Force Yandex.
        AppConfig.setMapProvider(ctx, AppConfig.MAP_PROVIDER_YANDEX)
        onMain { MapProviders.switchTo(ctx.applicationContext, AppConfig.MAP_PROVIDER_YANDEX) }

        // 2. Open the Navigation tab.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        SystemClock.sleep(2000)
        scenario.onActivity { act ->
            val pagerId = act.resources.getIdentifier("viewPager", "id", ctx.packageName)
            act.findViewById<ViewPager2>(pagerId)?.setCurrentItem(NAV_TAB_INDEX, false)
        }
        SystemClock.sleep(1500)

        val manager = NavigationManager.getInstance(ctx)

        // 3. Plan a real Moscow TRANSIT journey via the active (Yandex) provider.
        val routeLatch = CountDownLatch(1)
        var chosen: RouteAlternative? = null
        onMain {
            manager.planJourneyAlternatives(MOSCOW_KOMSOMOLSKAYA, MOSCOW_RED_SQUARE, TransportMode.TRANSIT, emptyList(), null) { alts ->
                chosen = alts.firstOrNull { it.routeInfo.transitSections.isNotEmpty() } ?: alts.firstOrNull()
                routeLatch.countDown()
            }
        }
        assertTrue("route alternatives timed out", routeLatch.await(25, TimeUnit.SECONDS))
        val route: RouteInfo = chosen?.routeInfo ?: error("no transit route returned")
        assertTrue("expected transit sections", route.transitSections.isNotEmpty())

        // 4. Select it and start a mock journey along the real polyline.
        onMain {
            chosen?.let { manager.selectAlternative(it) }
            manager.startMockJourneyWithRoute(route, speedMps = 25.0)
        }

        // 4a. Capture the map with the steps panel COLLAPSED so the centered user
        //     arrow + the drawn route are both visible (arrow/path sync check).
        SystemClock.sleep(10000)
        run {
            val shot = instr.uiAutomation.takeScreenshot()
            if (shot != null) {
                val dir = java.io.File(ctx.getExternalFilesDir(null), "additional_test_output").apply { mkdirs() }
                java.io.FileOutputStream(java.io.File(dir, "yandex_arrow_sync.png")).use {
                    shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }

        // 4b. Expand the active-steps panel (the "Details" button) by resource-id.
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
