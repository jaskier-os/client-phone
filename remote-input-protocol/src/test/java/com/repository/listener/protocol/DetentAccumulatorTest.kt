package com.repository.listener.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The conservation property these tests exist to protect: the accumulator must
 * never silently discard a detent the user produced. An earlier design lost
 * roughly half of a fast spin across three such stages.
 */
class DetentAccumulatorTest {

    private fun acc(threshold: Float = 1.0f) = DetentAccumulator(threshold)

    @Test
    fun emitsOneDetentAtThreshold() {
        assertEquals(1, acc().onDelta(1.0f, 0L))
    }

    @Test
    fun emitsNothingBelowThreshold() {
        assertEquals(0, acc().onDelta(0.4f, 0L))
    }

    @Test
    fun carriesSubDetentRemainderAcrossEvents() {
        val a = acc()
        assertEquals(0, a.onDelta(0.6f, 0L))
        // 0.6 + 0.6 = 1.2 -> one detent, 0.2 carried.
        assertEquals(1, a.onDelta(0.6f, 10L))
        assertEquals(0, a.onDelta(0.6f, 20L))
        assertEquals(1, a.onDelta(0.3f, 30L))
    }

    @Test
    fun negativeDeltasEmitNegativeDetents() {
        assertEquals(-1, acc().onDelta(-1.0f, 0L))
    }

    /** Mirrors AOSP LowResFlingTimeframe: a stale remainder must not combine
     *  with a new gesture and produce a phantom detent. */
    @Test
    fun resetsSubDetentRemainderAfterIdleGap() {
        val a = acc()
        assertEquals(0, a.onDelta(0.9f, 0L))
        // 200 ms later the 0.9 is discarded, so 0.9 alone is again below threshold.
        assertEquals(0, a.onDelta(0.9f, 200L))
        assertEquals(1, a.onDelta(0.2f, 210L))
    }

    @Test
    fun clampsToMaxStepsPerEventAndCarriesTheRest() {
        val a = acc()
        // 10 detents at once, clamped to 4 per event.
        assertEquals(4, a.onDelta(10.0f, 0L))
        assertEquals(6, a.pendingSurplus)
        assertEquals(4, a.drain(1L))
        assertEquals(2, a.drain(2L))
        assertEquals(0, a.pendingSurplus)
    }

    /**
     * The critical conservation test: a hard flick produces far more detents than
     * the ~20/s ceiling passes, and every one must still land eventually.
     */
    @Test
    fun rateLimiterCarriesSurplusInsteadOfDroppingIt() {
        val a = acc()
        val produced = 100
        // onDelta itself delivers the first allowed chunk; count it or the
        // conservation total is short by exactly that chunk.
        var delivered = abs(a.onDelta(produced.toFloat(), 0L))

        var now = 0L
        // Drain across enough one-second windows to pass all 100 steps at 20/s.
        repeat(400) {
            delivered += abs(a.drain(now))
            now += 25L
        }
        assertEquals("every produced detent must be delivered", produced, delivered)
        assertEquals(0, a.pendingSurplus)
    }

    @Test
    fun rateLimiterCapsSustainedThroughputPerSecond() {
        val a = acc()
        a.onDelta(100.0f, 0L)
        var deliveredInFirstSecond = 0
        var now = 0L
        while (now < 1000L) {
            deliveredInFirstSecond += abs(a.drain(now))
            now += 10L
        }
        assertTrue(
            "expected <= 20 steps in the first second, got $deliveredInFirstSecond",
            deliveredInFirstSecond <= DetentAccumulator.MAX_STEPS_PER_SECOND,
        )
    }

    /** A reversal must not deliver the old direction's backlog as new motion. */
    @Test
    fun directionReversalDiscardsOppositeDirectionBacklog() {
        val a = acc()
        assertEquals(4, a.onDelta(10.0f, 0L))
        assertTrue(a.pendingSurplus > 0)
        val reversed = a.onDelta(-3.0f, 10L)
        assertTrue("reversal must emit backwards, got $reversed", reversed <= 0)
        assertTrue("no forward backlog may survive a reversal", a.pendingSurplus <= 0)
    }

    @Test
    fun flushAllEmitsCarriedSurplusForTeardown() {
        val a = acc()
        a.onDelta(10.0f, 0L)
        assertEquals(6, a.flushAll())
        assertEquals(0, a.pendingSurplus)
    }

    @Test
    fun resetClearsAllState() {
        val a = acc()
        a.onDelta(10.0f, 0L)
        a.reset()
        assertEquals(0, a.pendingSurplus)
        assertEquals(0, a.onDelta(0.5f, 1000L))
    }

    /** The drag surface uses the same maths with a pixel threshold. */
    @Test
    fun worksWithAPixelThresholdForTheDragSurface() {
        val a = DetentAccumulator(threshold = 24f)
        assertEquals(0, a.onDelta(12f, 0L))
        assertEquals(1, a.onDelta(12f, 10L))
        assertEquals(2, a.onDelta(48f, 20L))
    }
}
