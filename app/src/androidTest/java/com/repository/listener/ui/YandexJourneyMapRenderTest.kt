package com.repository.listener.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.viewpager2.widget.ViewPager2
import com.repository.listener.MainActivity
import com.repository.listener.config.AppConfig
import com.repository.navigation.NavigationManager
import com.repository.navigation.model.TransportMode
import com.repository.navigation.provider.MapProviders
import com.yandex.mapkit.geometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression test for the "map blank during a journey" bug on the default Yandex
 * provider. The symptom was NOT a missing render surface -- the map drew fine -- it
 * was the Yandex UserLocationView accuracy circle ballooning into a giant opaque
 * blue disc that covered the whole map (streets/route hidden underneath). It only
 * appears once a journey supplies a location fix with a large accuracy, so this
 * test starts a SIMULATED journey (NavigationManager.startMockJourney) before
 * inspecting the map.
 *
 * Strategy (no coordinate taps; UI driven via AppConfig + ViewPager2.setCurrentItem):
 *  1. Force provider = Yandex (pref + runtime MapProviders.switchTo).
 *  2. Start a mock driving journey across central Moscow (real route + simulator).
 *  3. Open the Navigation tab; wait for the map surface to lay out + show.
 *  4. Capture the map region and assert NO single quantized color occupies more
 *     than DOMINANT_MAX of the sampled pixels. The blue-blob bug drove one color
 *     (the accuracy fill) to ~70-90% coverage; with the fix (transparent accuracy
 *     circle) the map's streets/route/labels keep the dominant color well below.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class YandexJourneyMapRenderTest {

    companion object {
        const val PKG = "com.repository.listener"
        const val RENDER_TIMEOUT_MS = 25000L
        const val NAV_TAB_INDEX = 5
        // Central Moscow: Tverskaya area -> Red Square. Real route, ~2 km.
        val FROM = Point(55.7649, 37.6049)
        val TO = Point(55.7539, 37.6208)
        // A correctly rendered map never has a single color filling most of the view.
        // The accuracy-circle bug pushed one solid blue past ~0.70. Fail above this.
        const val DOMINANT_MAX = 0.55
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    @Test
    fun yandexJourneyMapNotCoveredByAccuracyCircle() {
        val ctx = instrumentation.targetContext

        // ---- 1. Force provider = Yandex -----------------------------------------
        AppConfig.setMapProvider(ctx, AppConfig.MAP_PROVIDER_YANDEX)
        val activeAfter = instrumentRunOnMain {
            MapProviders.switchTo(ctx.applicationContext, AppConfig.MAP_PROVIDER_YANDEX)
        }
        assertEquals(
            "MapProviders runtime active must be yandex. switchTo returned '$activeAfter'",
            AppConfig.MAP_PROVIDER_YANDEX, MapProviders.active.id
        )

        // ---- 2. Start a simulated journey (drives the user-location fix) ---------
        val navManager = NavigationManager.getInstance(ctx.applicationContext)
        val startedLatch = CountDownLatch(1)
        val errorHolder = arrayOfNulls<String>(1)
        instrumentRunOnMain {
            navManager.startMockJourney(
                from = FROM,
                to = TO,
                mode = TransportMode.DRIVING,
                onError = { msg -> errorHolder[0] = msg; startedLatch.countDown() },
                callback = { _, _ -> startedLatch.countDown() }
            )
        }
        assertTrue(
            "startMockJourney never completed (route build timed out)",
            startedLatch.await(30, TimeUnit.SECONDS)
        )
        if (errorHolder[0] != null) {
            // A routing failure is an environment problem, not the bug under test.
            throw AssertionError("Route build failed: ${errorHolder[0]}")
        }
        // Give the simulator a moment to emit its first fix so the accuracy circle
        // (the thing under test) actually exists by the time the map is shown.
        SystemClock.sleep(3000)
        assertTrue("journey must be ACTIVE", navManager.getState() == NavigationManager.State.ACTIVE)

        // ---- 3. Launch MainActivity, open Navigation tab ------------------------
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        SystemClock.sleep(2000)

        val mapContainerId = ctx.resources.getIdentifier("mapContainer", "id", PKG)
            .let { if (it != 0) it else ctx.resources.getIdentifier("mapContainer", "id", "com.repository.navigation") }
        assertTrue("Could not resolve R.id.mapContainer", mapContainerId != 0)

        scenario.onActivity { act ->
            val pagerId = act.resources.getIdentifier("viewPager", "id", PKG)
            val pager = act.findViewById<ViewPager2>(pagerId)
            assertNotNull("viewPager not found in MainActivity", pager)
            pager.setCurrentItem(NAV_TAB_INDEX, false)
        }

        // Wait for the map render surface to lay out + show.
        var mapContainer: ViewGroup? = null
        val containerDeadline = SystemClock.uptimeMillis() + RENDER_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < containerDeadline) {
            scenario.onActivity { act -> mapContainer = act.findViewById(mapContainerId) }
            val c = mapContainer
            if (c != null && instrumentRunOnMain { c.childCount } > 0) break
            SystemClock.sleep(300)
        }
        assertNotNull("mapContainer view must exist", mapContainer)

        var surface: View? = null
        val renderDeadline = SystemClock.uptimeMillis() + RENDER_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < renderDeadline) {
            surface = instrumentRunOnMain { findRenderSurface(mapContainer!!) }
            if (surface != null) {
                val w = instrumentRunOnMain { surface!!.width }
                val h = instrumentRunOnMain { surface!!.height }
                val shown = instrumentRunOnMain { surface!!.isShown }
                if (w > 0 && h > 0 && shown) break
            }
            SystemClock.sleep(400)
        }
        assertNotNull("Yandex map never created its render surface", surface)

        // Let tiles + route + the (now-transparent) accuracy circle settle, and hold
        // visible so a screen recording captures the state.
        SystemClock.sleep(6000)

        // ---- 4. Dominant-color check over the map region ------------------------
        val outDir = File(ctx.getExternalFilesDir(null), "additional_test_output").apply { mkdirs() }
        val shotFile = File(outDir, "yandex_journey_map.png")
        assertTrue("UiDevice.takeScreenshot failed", device.takeScreenshot(shotFile))
        val bmp = android.graphics.BitmapFactory.decodeFile(shotFile.absolutePath)
        assertNotNull("Could not decode screenshot", bmp)

        // Sample only the upper map area (above the bottom sheet) to avoid the dark
        // sheet skewing the histogram. Map spans y in [statusBar, ~0.65*H].
        val top = (bmp!!.height * 0.10).toInt()
        val bottom = (bmp.height * 0.60).toInt()
        val hist = dominantColor(bmp, top, bottom)

        File(outDir, "yandex_journey_proof.txt").let { f ->
            FileOutputStream(f).use { os ->
                os.write(
                    ("provider=${MapProviders.active.id}\n" +
                        "journeyState=${navManager.getState()}\n" +
                        "sampledPixels=${hist.total}\n" +
                        "dominantColorFraction=${"%.3f".format(hist.fraction)}\n" +
                        "distinctColors=${hist.distinct}\n").toByteArray()
                )
            }
        }
        android.util.Log.i(
            "YandexJourneyMapRenderTest",
            "RENDER PROOF provider=${MapProviders.active.id} dominantFraction=" +
                "${"%.3f".format(hist.fraction)} distinct=${hist.distinct}"
        )

        // Stop the simulated journey before asserting (clean teardown regardless).
        instrumentRunOnMain { navManager.stopMockJourney() }

        assertTrue(
            "Map is dominated by a single solid color (fraction=${"%.3f".format(hist.fraction)} > " +
                "$DOMINANT_MAX, distinctColors=${hist.distinct}). The Yandex user-location " +
                "accuracy circle is covering the map (the blue-blob regression).",
            hist.fraction <= DOMINANT_MAX
        )
    }

    // ---- helpers -------------------------------------------------------------

    private fun <T> instrumentRunOnMain(block: () -> T): T {
        val holder = arrayOfNulls<Any>(1)
        instrumentation.runOnMainSync { holder[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    private fun findRenderSurface(root: View): View? {
        if (root is SurfaceView || root is TextureView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findRenderSurface(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private data class Hist(val fraction: Double, val distinct: Int, val total: Int)

    /** Quantized-color histogram over [top,bottom). Returns the most-common color's fraction. */
    private fun dominantColor(bmp: Bitmap, top: Int, bottom: Int): Hist {
        val stepX = (bmp.width / 96).coerceAtLeast(1)
        val stepY = ((bottom - top) / 96).coerceAtLeast(1)
        val counts = HashMap<Int, Int>()
        var total = 0
        var y = top
        while (y < bottom) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                val q = (android.graphics.Color.red(c) shr 3 shl 10) or
                    (android.graphics.Color.green(c) shr 3 shl 5) or
                    (android.graphics.Color.blue(c) shr 3)
                counts[q] = (counts[q] ?: 0) + 1
                total++
                x += stepX
            }
            y += stepY
        }
        val max = counts.values.maxOrNull() ?: 0
        return Hist(if (total > 0) max.toDouble() / total else 1.0, counts.size, total)
    }
}
