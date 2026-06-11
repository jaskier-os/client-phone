package com.repository.navigation.provider

import com.yandex.mapkit.geometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Google mock-location simulator math.
 *
 * Handler(Looper.getMainLooper()).postDelayed never fires on the plain JVM
 * unit-test classpath (unitTests.isReturnDefaultValues = true returns a stub
 * Handler whose post/postDelayed are no-ops), so the time-driven tick loop
 * cannot be exercised directly. Instead the per-tick math was extracted into
 * the pure, side-effect-free [GoogleLocationEngine.step] seam, and this test
 * drives that step function the same way the production Runnable does: emit at
 * the current state, then feed the returned (segIndex, segProgressM) back in
 * for the next tick. This reproduces the exact emission stream the real
 * simulator would produce, deterministically and without a Looper.
 */
class GoogleMockSimulatorTest {

    // A two-segment L-shaped route. Leg 1 heads roughly east, leg 2 roughly north.
    private val origin = Point(55.7539, 37.6208)        // Red Square
    private val mid = Point(55.7539, 37.6300)           // east of origin (same lat)
    private val dest = Point(55.7620, 37.6300)          // north of mid (same lng)
    private val route = listOf(origin, mid, dest)

    private fun haversine(a: Point, b: Point): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(a.latitude)) * Math.cos(Math.toRadians(b.latitude)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
    }

    /** Drive [GoogleLocationEngine.step] to completion, returning every emitted fix. */
    private fun runToFinish(
        polyline: List<Point>,
        speedMps: Double,
        maxSteps: Int = 100_000
    ): List<GeoFix> {
        val fixes = ArrayList<GeoFix>()
        var idx = 0
        var prog = 0.0
        var steps = 0
        while (steps++ < maxSteps) {
            val r = GoogleLocationEngine.step(polyline, idx, prog, speedMps)
            fixes.add(r.fix)
            idx = r.nextSegIndex
            prog = r.nextSegProgressM
            if (r.finished) break
        }
        assertTrue("simulator did not finish within $maxSteps steps", steps < maxSteps)
        return fixes
    }

    @Test
    fun firstEmissionIsRouteOrigin() {
        val first = GoogleLocationEngine.step(route, 0, 0.0, 5.0)
        assertEquals(origin.latitude, first.fix.lat, 1e-9)
        assertEquals(origin.longitude, first.fix.lng, 1e-9)
        assertFalse(first.finished)
        // Heading on leg 1 points east-ish (~90 deg) toward mid.
        assertNotNull(first.fix.headingDeg)
        assertEquals(90.0, first.fix.headingDeg!!.toDouble(), 5.0)
    }

    @Test
    fun progressAdvancesBySpeedPerTick() {
        val speed = 7.5
        val r0 = GoogleLocationEngine.step(route, 0, 0.0, speed)
        // After the first tick, segProgressM advanced by exactly speed * 1s.
        assertEquals(speed, r0.nextSegProgressM, 1e-9)
        assertEquals(0, r0.nextSegIndex)
    }

    @Test
    fun positionMonotonicallyAdvancesAlongRoute() {
        val speed = 50.0  // large step so the route finishes in a few ticks
        val fixes = runToFinish(route, speed)
        assertTrue("expected several emissions, got ${fixes.size}", fixes.size >= 3)
        // Cumulative distance from origin must be non-decreasing along the stream.
        var prevDistFromOrigin = -1.0
        for (f in fixes) {
            val d = haversine(origin, Point(f.lat, f.lng))
            assertTrue(
                "distance-from-origin regressed: $d after $prevDistFromOrigin",
                d >= prevDistFromOrigin - 1e-6
            )
            prevDistFromOrigin = d
        }
    }

    @Test
    fun interpolatedPointLiesOnFirstSegment() {
        // Half a leg-1 length in: the interpolated point shares origin's latitude
        // (leg 1 is constant-latitude) and lies between origin and mid in longitude.
        val leg1 = haversine(origin, mid)
        val r = GoogleLocationEngine.step(route, 0, leg1 / 2.0, 5.0)
        assertEquals(origin.latitude, r.fix.lat, 1e-6)
        assertTrue(r.fix.lng > origin.longitude && r.fix.lng < mid.longitude)
        assertEquals(0, r.nextSegIndex)
    }

    @Test
    fun crossesIntoSecondSegmentAndBearingTurnsNorth() {
        val leg1 = haversine(origin, mid)
        // Progress just past the end of leg 1 forces the loop to roll over to seg 1.
        val r = GoogleLocationEngine.step(route, 0, leg1 + 1.0, 5.0)
        assertEquals(1, r.nextSegIndex)
        // On leg 2 (constant longitude, heading north) bearing is ~0/360 deg.
        assertNotNull(r.fix.headingDeg)
        val hd = r.fix.headingDeg!!.toDouble()
        val northErr = Math.min(hd, 360.0 - hd)
        assertTrue("expected ~north bearing, got $hd", northErr < 5.0)
    }

    @Test
    fun finalEmissionIsLastPointWithZeroSpeed() {
        val fixes = runToFinish(route, 50.0)
        val last = fixes.last()
        assertEquals(dest.latitude, last.lat, 1e-9)
        assertEquals(dest.longitude, last.lng, 1e-9)
        assertEquals(0.0, last.speedMps!!, 1e-9)
        assertEquals(null, last.headingDeg)
    }

    @Test
    fun stepReportsFinishedExactlyOnce() {
        val speed = 50.0
        var finishedCount = 0
        var idx = 0
        var prog = 0.0
        var guard = 0
        while (guard++ < 100_000) {
            val r = GoogleLocationEngine.step(route, idx, prog, speed)
            if (r.finished) {
                finishedCount++
                break
            }
            idx = r.nextSegIndex
            prog = r.nextSegProgressM
        }
        assertEquals(1, finishedCount)
    }

    @Test
    fun zeroLengthSegmentIsSkippedWithoutStalling() {
        // A duplicated vertex (zero-length segment) must not trap the walk.
        val withDup = listOf(origin, origin, mid)
        val fixes = runToFinish(withDup, 30.0)
        val last = fixes.last()
        assertEquals(mid.latitude, last.lat, 1e-9)
        assertEquals(mid.longitude, last.lng, 1e-9)
    }
}
