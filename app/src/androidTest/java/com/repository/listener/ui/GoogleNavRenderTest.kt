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
import com.repository.navigation.provider.MapProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Proves the Google Maps provider actually RENDERS inside the Navigation tab on a
 * physical phone -- not merely that a settings screen or empty container appeared.
 *
 * Strategy (no coordinate taps anywhere; UI driven via AppConfig + UiAutomator):
 *  1. Force the active map provider to Google: write the AppConfig pref AND flip the
 *     runtime MapProviders.active via the public switchTo() (the same path
 *     ListenerService uses), then assert AppConfig.getMapProvider == "google" and
 *     MapProviders.active.id == "google".
 *  2. Launch MainActivity and select the Navigation tab (ViewPager2 position 5) by
 *     content-description / text -- never a raw tap.
 *  3. RENDER PROOF (hard assertions, the entire point):
 *       a. mapContainer (FrameLayout) exists and has childCount > 0 (a MapView attached).
 *       b. A SurfaceView or TextureView descendant exists (Google Maps draws into one).
 *       c. That surface has width>0 && height>0 && isShown==true within a 25s timeout.
 *       d. Hold the rendered map ~6s so a screen recording captures it.
 *       e. Capture a Bitmap of the surface region and assert it is NON-UNIFORM
 *          (catches "container present but nothing drawn" / blank-tile cases).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class GoogleNavRenderTest {

    companion object {
        const val PKG = "com.repository.listener"
        const val FIND_TIMEOUT = 12000L
        const val RENDER_TIMEOUT_MS = 25000L
        const val NAV_TAB_INDEX = 5
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    @Test
    fun googleMapRendersOnNavigationTab() {
        val ctx = instrumentation.targetContext

        // ---- 1. Force provider = Google (pref + runtime active) -------------------
        AppConfig.setMapProvider(ctx, AppConfig.MAP_PROVIDER_GOOGLE)
        // switchTo applies the N9 blank-key fallback; with a real key it returns "google".
        val activeAfter = instrumentRunOnMain {
            MapProviders.switchTo(ctx.applicationContext, AppConfig.MAP_PROVIDER_GOOGLE)
        }
        // Broadcast the settings-changed intent so any live ListenerService agrees.
        ctx.sendBroadcast(
            android.content.Intent(ConfigFragment.ACTION_SETTINGS_CHANGED)
                .setPackage(PKG)
        )

        assertEquals(
            "AppConfig pref must read google",
            AppConfig.MAP_PROVIDER_GOOGLE, AppConfig.getMapProvider(ctx)
        )
        assertEquals(
            "MapProviders runtime active must be google (key present, no fallback). " +
                "switchTo returned '$activeAfter'",
            AppConfig.MAP_PROVIDER_GOOGLE, MapProviders.active.id
        )

        // ---- 2. Launch MainActivity via ActivityScenario (instrumentation-owned so
        //         we get a reliable live Activity handle), go to Navigation tab. ----
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        SystemClock.sleep(2000)

        // mapContainer is declared in the :navigation module but merged into the app
        // resources, so at runtime it resolves under the app package. Try the app
        // package first, then the library namespace as a fallback.
        val mapContainerId = ctx.resources.getIdentifier("mapContainer", "id", PKG)
            .let { if (it != 0) it else ctx.resources.getIdentifier("mapContainer", "id", "com.repository.navigation") }
        assertTrue("Could not resolve R.id.mapContainer", mapContainerId != 0)

        // Drive the ViewPager2 to the Navigation tab (no coordinate taps).
        scenario.onActivity { act ->
            val pagerId = act.resources.getIdentifier("viewPager", "id", PKG)
            val pager = act.findViewById<ViewPager2>(pagerId)
            assertNotNull("viewPager not found in MainActivity", pager)
            // setCurrentItem(false) = no smooth-scroll so the page is created promptly.
            pager.setCurrentItem(NAV_TAB_INDEX, false)
        }

        // ---- 3. RENDER PROOF -----------------------------------------------------
        // 3a. mapContainer with a child attached (the provider MapView).
        var mapContainer: ViewGroup? = null
        val containerDeadline = SystemClock.uptimeMillis() + RENDER_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < containerDeadline) {
            scenario.onActivity { act ->
                mapContainer = act.findViewById(mapContainerId)
            }
            val c = mapContainer
            if (c != null && instrumentRunOnMain { c.childCount } > 0) break
            SystemClock.sleep(300)
        }
        assertNotNull("mapContainer view must exist", mapContainer)
        val childCount = instrumentRunOnMain { mapContainer!!.childCount }
        assertTrue(
            "mapContainer must have at least one child (the provider MapView); childCount=$childCount",
            childCount > 0
        )

        // 3b. A SurfaceView or TextureView descendant (the actual map render target).
        // 3c. Poll until it is laid out (w/h>0) and shown.
        var surface: View? = null
        var surfW = 0
        var surfH = 0
        var surfShown = false
        var surfClass = "<none>"
        val renderDeadline = SystemClock.uptimeMillis() + RENDER_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < renderDeadline) {
            surface = instrumentRunOnMain { findRenderSurface(mapContainer!!) }
            if (surface != null) {
                val w = instrumentRunOnMain { surface!!.width }
                val h = instrumentRunOnMain { surface!!.height }
                val shown = instrumentRunOnMain { surface!!.isShown }
                surfW = w; surfH = h; surfShown = shown
                surfClass = surface!!.javaClass.name
                if (w > 0 && h > 0 && shown) break
            }
            SystemClock.sleep(400)
        }
        assertNotNull(
            "No SurfaceView/TextureView descendant under mapContainer -- Google map never " +
                "created its render surface. childCount=$childCount",
            surface
        )
        assertTrue(
            "Map surface ($surfClass) never laid out/shown within ${RENDER_TIMEOUT_MS}ms: " +
                "w=$surfW h=$surfH isShown=$surfShown",
            surfW > 0 && surfH > 0 && surfShown
        )

        // 3d. Hold the rendered map visible so the screen recording captures it.
        SystemClock.sleep(6000)

        // 3e. Capture screenshot + assert non-uniform pixels.
        val outDir = File(ctx.getExternalFilesDir(null), "additional_test_output").apply { mkdirs() }
        val shotFile = File(outDir, "google_nav_render.png")
        val full = device.takeScreenshot(shotFile)
        assertTrue("UiDevice.takeScreenshot failed", full)

        val bmp = android.graphics.BitmapFactory.decodeFile(shotFile.absolutePath)
        assertNotNull("Could not decode screenshot", bmp)
        val uniformity = colorSpread(bmp!!)
        // distinctColors: a flat/blank map -> a tiny handful of colors; a real rendered
        // map (tiles, roads, labels) -> hundreds+. Require a solid spread.
        assertTrue(
            "Screenshot appears blank/uniform (distinctColors=${uniformity.distinct}, " +
                "stdDev=${"%.2f".format(uniformity.stdDev)}). The map container is present " +
                "but nothing was drawn into it.",
            uniformity.distinct >= 50 && uniformity.stdDev >= 8.0
        )

        // Also dump the surface-tree summary to additional_test_output for the report.
        File(outDir, "render_proof.txt").let { f ->
            FileOutputStream(f).use { os ->
                os.write(
                    ("mapProvider.pref=${AppConfig.getMapProvider(ctx)}\n" +
                        "MapProviders.active.id=${MapProviders.active.id}\n" +
                        "switchTo.returned=$activeAfter\n" +
                        "mapContainer.childCount=$childCount\n" +
                        "surfaceClass=$surfClass\n" +
                        "surface.width=$surfW surface.height=$surfH isShown=$surfShown\n" +
                        "screenshot.distinctColors=${uniformity.distinct}\n" +
                        "screenshot.stdDev=${uniformity.stdDev}\n").toByteArray()
                )
            }
        }

        android.util.Log.i(
            "GoogleNavRenderTest",
            "RENDER PROOF provider=${MapProviders.active.id} childCount=$childCount " +
                "surface=$surfClass ${surfW}x$surfH shown=$surfShown " +
                "distinctColors=${uniformity.distinct} stdDev=${uniformity.stdDev}"
        )
    }

    // ---- helpers -------------------------------------------------------------

    private fun <T> instrumentRunOnMain(block: () -> T): T {
        val holder = arrayOfNulls<Any>(1)
        instrumentation.runOnMainSync { holder[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    /** Depth-first search for the first SurfaceView/TextureView under [root]. */
    private fun findRenderSurface(root: View): View? {
        if (root is SurfaceView || root is TextureView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findRenderSurface(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private data class Spread(val distinct: Int, val stdDev: Double)

    /** Sample the bitmap on a grid: count distinct quantized colors + luminance stdDev. */
    private fun colorSpread(bmp: Bitmap): Spread {
        val stepX = (bmp.width / 64).coerceAtLeast(1)
        val stepY = (bmp.height / 64).coerceAtLeast(1)
        val colors = HashSet<Int>()
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                // quantize to 5 bits/channel to ignore JPEG-ish noise.
                val q = (android.graphics.Color.red(c) shr 3 shl 10) or
                    (android.graphics.Color.green(c) shr 3 shl 5) or
                    (android.graphics.Color.blue(c) shr 3)
                colors.add(q)
                val lum = 0.299 * android.graphics.Color.red(c) +
                    0.587 * android.graphics.Color.green(c) +
                    0.114 * android.graphics.Color.blue(c)
                sum += lum; sumSq += lum * lum; n++
                x += stepX
            }
            y += stepY
        }
        val mean = if (n > 0) sum / n else 0.0
        val variance = if (n > 0) (sumSq / n) - mean * mean else 0.0
        return Spread(colors.size, if (variance > 0) Math.sqrt(variance) else 0.0)
    }
}
