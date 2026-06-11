package com.repository.navigation.provider

import com.repository.navigation.ArrivalDetector
import com.yandex.mapkit.geometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM test of the location-stream contract that NavigationManager relies on,
 * exercised through a deterministic [FakeLocationEngine]. The fake records its
 * subscribers and lets the test emit GeoFix values by hand, so we can verify:
 *  - the ordered GeoFix stream produced by walking a route polyline,
 *  - that the same downstream consumer wiring NavigationManager uses
 *    (ArrivalDetector) fires once the stream reaches the destination.
 *
 * NavigationManager itself is NOT constructed here: it needs a Context + Room DB
 * + Dispatchers.Main, none of which exist on the plain JVM classpath. The full
 * NavigationManager journey is covered in androidTest (GoogleMockJourneyTest).
 */
class FakeLocationEngineTest {

    /**
     * Minimal [LocationEngine] that drives subscribers from a fabricated polyline
     * via [GoogleLocationEngine.step], the same pure tick the Google simulator
     * uses in production. No Handler/Looper involved.
     */
    private class FakeLocationEngine : LocationEngine {
        val subscribers = LinkedHashSet<GeoFixListener>()
        private var mock = false
        override val mockActive: Boolean get() = mock

        override fun subscribe(listener: GeoFixListener) { subscribers.add(listener) }
        override fun unsubscribe(listener: GeoFixListener) { subscribers.remove(listener) }
        override fun disableMock() { mock = false }
        override fun requestSingleFix(callback: (GeoFix?) -> Unit) { callback(null) }

        /** Walk the polyline to completion, dispatching each step's fix synchronously. */
        override fun enableMock(polyline: List<Point>, speedMps: Double, onFinished: () -> Unit) {
            mock = true
            var idx = 0
            var prog = 0.0
            var guard = 0
            while (guard++ < 1_000_000) {
                val r = GoogleLocationEngine.step(polyline, idx, prog, speedMps)
                for (l in subscribers.toList()) l.onFix(r.fix)
                idx = r.nextSegIndex
                prog = r.nextSegProgressM
                if (r.finished) break
            }
            mock = false
            onFinished()
        }
    }

    private val origin = Point(55.7539, 37.6208)
    private val dest = Point(55.7620, 37.6208)  // ~900m due north
    private val route = listOf(origin, dest)

    @Test
    fun subscribeAndUnsubscribeTrackedByIdentity() {
        val engine = FakeLocationEngine()
        val l = GeoFixListener { }
        engine.subscribe(l)
        assertTrue(engine.subscribers.contains(l))
        engine.unsubscribe(l)
        assertFalse(engine.subscribers.contains(l))
    }

    @Test
    fun enableMockProducesOrderedStreamStartingAtOriginEndingAtDest() {
        val engine = FakeLocationEngine()
        val received = ArrayList<GeoFix>()
        engine.subscribe { received.add(it) }

        var finished = false
        engine.enableMock(route, speedMps = 80.0) { finished = true }

        assertTrue(finished)
        assertTrue("expected multiple fixes, got ${received.size}", received.size >= 2)
        // First fix is the origin, last is the destination with speed 0.
        assertEquals(origin.latitude, received.first().lat, 1e-9)
        assertEquals(origin.longitude, received.first().lng, 1e-9)
        assertEquals(dest.latitude, received.last().lat, 1e-9)
        assertEquals(dest.longitude, received.last().lng, 1e-9)
        assertEquals(0.0, received.last().speedMps!!, 1e-9)
        // Latitude must be non-decreasing (route is strictly northbound).
        var prevLat = -1000.0
        for (f in received) {
            assertTrue("latitude regressed: ${f.lat} after $prevLat", f.lat >= prevLat - 1e-9)
            prevLat = f.lat
        }
    }

    @Test
    fun arrivalDetectorFiresOnceWhenStreamReachesDestination() {
        val engine = FakeLocationEngine()
        var arrivals = 0
        val detector = ArrivalDetector(arrivalRadiusMeters = 50.0) { arrivals++ }
        detector.setDestination(dest.latitude, dest.longitude)

        // Wire the detector to the stream exactly like NavigationManager does.
        engine.subscribe { fix -> detector.updatePosition(fix.lat, fix.lng) }
        engine.enableMock(route, speedMps = 40.0) {}

        assertEquals("arrival should fire exactly once near destination", 1, arrivals)
    }

    @Test
    fun mockActiveIsFalseAfterStreamCompletes() {
        val engine = FakeLocationEngine()
        engine.subscribe { }
        engine.enableMock(route, speedMps = 80.0) {}
        assertFalse(engine.mockActive)
    }
}
