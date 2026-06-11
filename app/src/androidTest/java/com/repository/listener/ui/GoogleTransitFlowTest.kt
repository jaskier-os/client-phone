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
import com.repository.navigation.ArrivalDetector
import com.repository.navigation.NavigationManager
import com.repository.navigation.model.RouteAlternative
import com.repository.navigation.model.TransitSectionType
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
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end proof that the FULL navigation flow works on the Google provider on a
 * physical phone: destination/route planning -> transit route selection -> journey
 * start with a simulated location that actually advances -- and that each stage
 * renders.
 *
 * Domain fact: Google has NO transit data for Moscow (Routes returns {} for RU), so
 * this drives a real transit-covered city (Istanbul). The verified-good showcase is a
 * FERRY route Eminonu -> Kadikoy; if that returns empty at runtime we fall back to
 * Taksim -> Sultanahmet (2 transit legs).
 *
 * No coordinate taps anywhere. The route/transit work is driven through the real public
 * NavigationManager API (real network call to Google Routes). The journey simulation
 * uses startMockJourneyWithRoute on the REAL Istanbul transit polyline so the
 * GoogleLocationEngine simulator advances offline and deterministically.
 *
 * Every stage is a HARD assertion -- the test FAILS if search/route/transit/journey/
 * render does not really happen.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class GoogleTransitFlowTest {

    companion object {
        const val PKG = "com.repository.listener"
        const val RENDER_TIMEOUT_MS = 25000L
        const val ROUTE_TIMEOUT_S = 25L
        const val NAV_TAB_INDEX = 5
        const val TAG = "GoogleTransitFlow"

        // Verified-good FERRY transit pair (the showcase case).
        val EMINONU = Point(41.0175, 28.9700)
        val KADIKOY = Point(40.9900, 29.0250)
        // Fallback: 2 transit legs.
        val TAKSIM = Point(41.0370, 28.9850)
        val SULTANAHMET = Point(41.0054, 28.9768)
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    @Test
    fun googleTransitFlowEndToEnd() {
        val ctx = instrumentation.targetContext
        val appCtx = ctx.applicationContext

        // ---- 1. Force provider = Google -------------------------------------------
        AppConfig.setMapProvider(ctx, AppConfig.MAP_PROVIDER_GOOGLE)
        val switchReturned = runOnMain { MapProviders.switchTo(appCtx, AppConfig.MAP_PROVIDER_GOOGLE) }
        assertEquals(
            "AppConfig pref must read google",
            AppConfig.MAP_PROVIDER_GOOGLE, AppConfig.getMapProvider(ctx)
        )
        assertEquals(
            "MapProviders runtime active must be google. switchTo returned '$switchReturned'",
            AppConfig.MAP_PROVIDER_GOOGLE, MapProviders.active.id
        )

        // ---- 2. Launch + go to Navigation tab; prove the Google map rendered ------
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        SystemClock.sleep(2000)

        val mapContainerId = resolveId(ctx, "mapContainer")
        assertTrue("Could not resolve R.id.mapContainer", mapContainerId != 0)

        scenario.onActivity { act ->
            val pager = act.findViewById<ViewPager2>(resolveId(act, "viewPager"))
            assertNotNull("viewPager not found in MainActivity", pager)
            pager.setCurrentItem(NAV_TAB_INDEX, false)
        }

        val map1 = awaitRenderedSurface(scenario, mapContainerId)
        assertTrue(
            "Google map surface (idle screen) never laid out/shown: " +
                "class=${map1.cls} w=${map1.w} h=${map1.h} shown=${map1.shown} childCount=${map1.childCount}",
            map1.w > 0 && map1.h > 0 && map1.shown
        )

        // ---- 3. ROUTE / TRANSIT via the real Google provider ----------------------
        val nav = NavigationManager.getInstance(appCtx)

        var origin = EMINONU
        var dest = KADIKOY
        var pairLabel = "Eminonu->Kadikoy"
        var alts = planTransit(nav, origin, dest)
        var chosen = pickTransitAlternative(alts)
        if (chosen == null) {
            android.util.Log.w(TAG, "Primary pair returned no transit alt; falling back to Taksim->Sultanahmet")
            origin = TAKSIM
            dest = SULTANAHMET
            pairLabel = "Taksim->Sultanahmet"
            alts = planTransit(nav, origin, dest)
            chosen = pickTransitAlternative(alts)
        }

        assertTrue("planJourneyAlternatives returned no alternatives for $pairLabel", alts.isNotEmpty())
        assertNotNull(
            "No alternative with a real (non-walk) transit section for $pairLabel. " +
                "alts=${alts.size}, sectionTypesPerAlt=" +
                alts.joinToString { a -> a.routeInfo.transitSections.map { it.type }.toString() },
            chosen
        )
        val chosenAlt = chosen!!
        val route = chosenAlt.routeInfo
        assertTrue("Chosen route has empty transitSections", route.transitSections.isNotEmpty())
        val sectionTypes = route.transitSections.map { it.type }
        val transitTypes = sectionTypes.filter { it != TransitSectionType.WALK }
        assertTrue(
            "Chosen route has no non-walk transit section; types=$sectionTypes",
            transitTypes.isNotEmpty()
        )
        assertTrue(
            "Chosen route has empty polyline",
            route.polylinePoints.isNotEmpty()
        )

        // Make it the current route.
        runOnMain { nav.selectAlternative(chosenAlt) }
        val current = nav.getCurrentRoute()
        assertNotNull("getCurrentRoute() null after selectAlternative", current)
        assertTrue(
            "Current route lost its transit sections",
            current!!.transitSections.isNotEmpty() &&
                current.transitSections.any { it.type != TransitSectionType.WALK }
        )
        assertTrue("Current route has empty polyline", current.polylinePoints.isNotEmpty())

        // ---- 4. START + SIMULATE along the real transit polyline ------------------
        val startLat = current.from.latitude
        val startLng = current.from.longitude
        val startedLatch = CountDownLatch(1)
        runOnMain {
            // Large speed so the simulated location advances clearly within ~2.5s.
            nav.startMockJourneyWithRoute(current, speedMps = 60.0) { startedLatch.countDown() }
        }
        assertTrue("Mock journey did not start within 10s", startedLatch.await(10, TimeUnit.SECONDS))

        assertEquals("State must be ACTIVE after starting journey", NavigationManager.State.ACTIVE, nav.getState())
        assertTrue("isMockJourney must be true", nav.isMockJourney)

        // Let the simulator advance. At 60 m/s the simulator covers a lot of path
        // length quickly, but straight-line (haversine) displacement from the origin
        // grows slower on a curving transit polyline, so allow a wider window.
        SystemClock.sleep(4500)
        val fix = nav.getLastKnownLocation()
        assertNotNull("No simulated location fix produced", fix)
        val advancedMeters = ArrivalDetector.haversineMeters(startLat, startLng, fix!!.lat, fix.lng)
        assertTrue(
            "Simulated location did not advance >100m from origin (advanced=${"%.1f".format(advancedMeters)}m). " +
                "Journey is not moving.",
            advancedMeters > 100.0
        )

        // ---- 5. RENDER PROOF of the active journey screen -------------------------
        // The fragment observes NavigationManager state; give it a beat to enter active UI.
        SystemClock.sleep(1500)
        val map2 = awaitRenderedSurface(scenario, mapContainerId)
        assertTrue(
            "Map surface not shown on active journey screen: " +
                "class=${map2.cls} w=${map2.w} h=${map2.h} shown=${map2.shown}",
            map2.w > 0 && map2.h > 0 && map2.shown
        )

        // Hold the active nav screen so a later recording captures it.
        SystemClock.sleep(5000)

        val outDir = File(ctx.getExternalFilesDir(null), "additional_test_output").apply { mkdirs() }
        val shotFile = File(outDir, "google_transit_flow.png")
        assertTrue("UiDevice.takeScreenshot failed", device.takeScreenshot(shotFile))
        val bmp = android.graphics.BitmapFactory.decodeFile(shotFile.absolutePath)
        assertNotNull("Could not decode screenshot", bmp)
        val spread = colorSpread(bmp!!)
        assertTrue(
            "Active journey screen appears blank/uniform (distinctColors=${spread.distinct}, " +
                "stdDev=${"%.2f".format(spread.stdDev)}).",
            spread.distinct >= 50 && spread.stdDev >= 8.0
        )

        // ---- Dump proof file + greppable log -------------------------------------
        val proof = buildString {
            append("provider=${MapProviders.active.id}\n")
            append("switchTo.returned=$switchReturned\n")
            append("pair=$pairLabel from=${startLat},${startLng} to=${dest.latitude},${dest.longitude}\n")
            append("alternatives.count=${alts.size}\n")
            append("chosen.alternativeId=${chosenAlt.alternativeId}\n")
            append("chosen.summary=${chosenAlt.summary}\n")
            append("sectionTypes=$sectionTypes\n")
            append("transitTypes(non-walk)=$transitTypes\n")
            append("polyline.size=${route.polylinePoints.size}\n")
            append("state=${nav.getState()}\n")
            append("isMockJourney=${nav.isMockJourney}\n")
            append("advancedMeters=${"%.1f".format(advancedMeters)}\n")
            append("surface.idle=${map1.cls} ${map1.w}x${map1.h} shown=${map1.shown}\n")
            append("surface.active=${map2.cls} ${map2.w}x${map2.h} shown=${map2.shown}\n")
            append("screenshot.distinctColors=${spread.distinct}\n")
            append("screenshot.stdDev=${spread.stdDev}\n")
        }
        FileOutputStream(File(outDir, "transit_flow_proof.txt")).use { it.write(proof.toByteArray()) }

        android.util.Log.i(
            TAG,
            "TRANSIT FLOW PROOF provider=${MapProviders.active.id} pair=$pairLabel " +
                "alts=${alts.size} sectionTypes=$sectionTypes transit=$transitTypes " +
                "polyline=${route.polylinePoints.size} state=${nav.getState()} " +
                "mock=${nav.isMockJourney} advancedMeters=${"%.1f".format(advancedMeters)} " +
                "surfaceActive=${map2.cls} ${map2.w}x${map2.h} shown=${map2.shown} " +
                "distinctColors=${spread.distinct} stdDev=${"%.2f".format(spread.stdDev)}"
        )

        runOnMain { nav.stopMockJourney() }
    }

    // ---- helpers -------------------------------------------------------------

    private fun planTransit(nav: NavigationManager, from: Point, to: Point): List<RouteAlternative> {
        val latch = CountDownLatch(1)
        val ref = AtomicReference<List<RouteAlternative>>(emptyList())
        runOnMain {
            nav.planJourneyAlternatives(from, to, TransportMode.TRANSIT, emptyList(), null) { result ->
                ref.set(result)
                latch.countDown()
            }
        }
        assertTrue(
            "planJourneyAlternatives did not call back within ${ROUTE_TIMEOUT_S}s (network)",
            latch.await(ROUTE_TIMEOUT_S, TimeUnit.SECONDS)
        )
        return ref.get()
    }

    /** First alternative whose route has at least one non-walk transit section. */
    private fun pickTransitAlternative(alts: List<RouteAlternative>): RouteAlternative? =
        alts.firstOrNull { a ->
            a.routeInfo.transitSections.any { it.type != TransitSectionType.WALK } &&
                a.routeInfo.polylinePoints.isNotEmpty()
        }

    private data class SurfaceState(
        val cls: String, val w: Int, val h: Int, val shown: Boolean, val childCount: Int
    )

    private fun awaitRenderedSurface(
        scenario: ActivityScenario<MainActivity>, mapContainerId: Int
    ): SurfaceState {
        var cls = "<none>"; var w = 0; var h = 0; var shown = false; var childCount = 0
        val deadline = SystemClock.uptimeMillis() + RENDER_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val container = AtomicReference<ViewGroup?>()
            scenario.onActivity { act -> container.set(act.findViewById(mapContainerId)) }
            val c = container.get()
            if (c != null) {
                val s = runOnMain {
                    val surface = findRenderSurface(c)
                    if (surface == null) {
                        SurfaceState("<none>", 0, 0, false, c.childCount)
                    } else {
                        SurfaceState(
                            surface.javaClass.name, surface.width, surface.height,
                            surface.isShown, c.childCount
                        )
                    }
                }
                cls = s.cls; w = s.w; h = s.h; shown = s.shown; childCount = s.childCount
                if (w > 0 && h > 0 && shown) break
            }
            SystemClock.sleep(400)
        }
        return SurfaceState(cls, w, h, shown, childCount)
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

    private fun resolveId(ctx: android.content.Context, name: String): Int =
        ctx.resources.getIdentifier(name, "id", PKG)
            .let { if (it != 0) it else ctx.resources.getIdentifier(name, "id", "com.repository.navigation") }

    private fun <T> runOnMain(block: () -> T): T {
        val holder = arrayOfNulls<Any>(1)
        instrumentation.runOnMainSync { holder[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    private data class Spread(val distinct: Int, val stdDev: Double)

    private fun colorSpread(bmp: Bitmap): Spread {
        val stepX = (bmp.width / 64).coerceAtLeast(1)
        val stepY = (bmp.height / 64).coerceAtLeast(1)
        val colors = HashSet<Int>()
        var sum = 0.0; var sumSq = 0.0; var n = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
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
