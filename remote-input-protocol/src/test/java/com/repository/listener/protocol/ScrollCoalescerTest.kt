package com.repository.listener.protocol

import com.repository.listener.protocol.RemoteInputProtocol.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Table-driven proof of the frozen coalescing rules. Time is injected, so every
 * rule is exercised deterministically with no sleeping and no flakiness.
 *
 * The property that matters most: total emitted scroll distance must always equal
 * total input distance. Coalescing may change how detents are grouped; it may
 * never change how many there are.
 */
class ScrollCoalescerTest {

    private data class Emission(val type: EventType, val steps: Int, val timeMs: Long)

    private val emitted = mutableListOf<Emission>()

    private fun coalescer(maxSteps: Int = RemoteInputProtocol.MAX_STEPS_PER_EVENT) =
        ScrollCoalescer(maxSteps = maxSteps) { type, steps, timeMs ->
            emitted += Emission(type, steps, timeMs)
        }

    private fun scrollTotal() = emitted.filter { it.type == EventType.SCROLL }.sumOf { it.steps }

    /** Rule 1: a lone detent is emitted immediately, with no window delay. */
    @Test
    fun singleDetentIsEmittedImmediately() {
        val c = coalescer()
        c.onDetents(1, 0L)
        assertEquals(1, emitted.size)
        assertEquals(Emission(EventType.SCROLL, 1, 0L), emitted[0])
    }

    /** Rule 2: same-direction detents inside the window accumulate into one event. */
    @Test
    fun detentsInsideWindowAccumulateIntoOneEvent() {
        val c = coalescer()
        c.onDetents(1, 0L)   // leading edge, emitted now
        c.onDetents(1, 10L)
        c.onDetents(1, 20L)
        c.onTimeout(60L)
        assertEquals(2, emitted.size)
        assertEquals(1, emitted[0].steps)
        assertEquals(2, emitted[1].steps)
        assertEquals(3, scrollTotal())
    }

    /** Rule 3: the window only closes once its full duration has elapsed. */
    @Test
    fun timeoutBeforeWindowElapsesDoesNothing() {
        val c = coalescer()
        c.onDetents(1, 0L)
        c.onDetents(1, 10L)
        c.onTimeout(30L)
        assertEquals("window must not close early", 1, emitted.size)
        c.onTimeout(60L)
        assertEquals(2, emitted.size)
    }

    /** Rule 4: the cap flushes immediately and carries the surplus onward. */
    @Test
    fun reachingCapFlushesImmediatelyAndCarriesSurplus() {
        val c = coalescer(maxSteps = 4)
        c.onDetents(1, 0L)
        c.onDetents(6, 10L)
        c.onTimeout(100L)
        assertEquals("no detent may be lost at the cap", 7, scrollTotal())
        assertTrue(emitted.all { abs(it.steps) <= 4 })
    }

    @Test
    fun leadingEdgeAboveCapIsSplitNotTruncated() {
        val c = coalescer(maxSteps = 4)
        c.onDetents(10, 0L)
        c.onTimeout(100L)
        assertEquals(10, scrollTotal())
        assertTrue(emitted.all { abs(it.steps) <= 4 })
    }

    /** Rule 5: a direction change flushes the pending event first. */
    @Test
    fun directionChangeFlushesPendingThenStartsFresh() {
        val c = coalescer()
        c.onDetents(1, 0L)
        c.onDetents(2, 10L)
        c.onDetents(-1, 20L)
        assertEquals(3, emitted.size)
        assertEquals(1, emitted[0].steps)
        assertEquals("pending forward detents flush before the reversal", 2, emitted[1].steps)
        assertEquals(-1, emitted[2].steps)
    }

    @Test
    fun directionChangeNeverMergesOppositeDirections() {
        val c = coalescer()
        c.onDetents(3, 0L)
        c.onDetents(-3, 10L)
        c.onTimeout(100L)
        val forward = emitted.filter { it.steps > 0 }.sumOf { it.steps }
        val back = emitted.filter { it.steps < 0 }.sumOf { it.steps }
        assertEquals(3, forward)
        assertEquals(-3, back)
    }

    /** Rule 6: a discrete event flushes any pending SCROLL first, preserving order. */
    @Test
    fun selectFlushesPendingScrollFirst() {
        val c = coalescer()
        c.onDetents(1, 0L)
        c.onDetents(2, 10L)
        c.onDiscreteEvent(EventType.SELECT, 20L)
        assertEquals(3, emitted.size)
        assertEquals(EventType.SCROLL, emitted[1].type)
        assertEquals(2, emitted[1].steps)
        assertEquals(EventType.SELECT, emitted[2].type)
        assertEquals("SELECT never carries steps", 0, emitted[2].steps)
    }

    @Test
    fun discreteEventsAreNeverCoalesced() {
        val c = coalescer()
        c.onDiscreteEvent(EventType.SELECT, 0L)
        c.onDiscreteEvent(EventType.SELECT, 5L)
        c.onDiscreteEvent(EventType.BACK, 10L)
        assertEquals(3, emitted.size)
    }

    @Test
    fun backAndPingPassThroughUntouched() {
        val c = coalescer()
        c.onDiscreteEvent(EventType.BACK, 0L)
        c.onDiscreteEvent(EventType.PING, 1L)
        assertEquals(listOf(EventType.BACK, EventType.PING), emitted.map { it.type })
    }

    /** The stamp reflects when the user acted, not when the window closed, so
     *  age on the glasses measures real input latency. */
    @Test
    fun coalescedEventIsStampedWithFirstDetentTime() {
        val c = coalescer()
        c.onDetents(1, 0L)
        c.onDetents(1, 12L)
        c.onDetents(1, 30L)
        c.onTimeout(60L)
        assertEquals("stamp is the first merged detent, not the flush", 12L, emitted[1].timeMs)
    }

    @Test
    fun windowReopensAfterFlush() {
        val c = coalescer()
        c.onDetents(1, 0L)
        c.onTimeout(60L)
        assertNull("idle coalescer has no pending timeout", c.nextTimeoutAtMs())
        c.onDetents(1, 100L)
        assertEquals(2, emitted.size)
        assertEquals("a new gesture is a fresh leading edge", 100L, emitted[1].timeMs)
    }

    @Test
    fun nextTimeoutTracksTheOpenWindow() {
        val c = coalescer()
        assertNull(c.nextTimeoutAtMs())
        c.onDetents(1, 0L)
        assertEquals(RemoteInputProtocol.COALESCE_WINDOW_MS, c.nextTimeoutAtMs())
    }

    @Test
    fun zeroDetentsAreIgnored() {
        val c = coalescer()
        c.onDetents(0, 0L)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun flushOnIdleCoalescerEmitsNothing() {
        val c = coalescer()
        c.flush(0L)
        assertTrue(emitted.isEmpty())
    }

    /**
     * The headline property, over a realistic fast spin: a burst far above both
     * the window and the cap must conserve total scroll distance exactly.
     */
    @Test
    fun conservesTotalScrollDistanceOverAFastSpin() {
        val c = coalescer()
        var expected = 0
        var now = 0L
        repeat(120) {
            c.onDetents(3, now)
            expected += 3
            now += 7L
            c.onTimeout(now)
        }
        c.flush(now)
        assertEquals("coalescing must never change total distance", expected, scrollTotal())
    }

    @Test
    fun conservesDistanceAcrossRepeatedDirectionChanges() {
        val c = coalescer()
        var now = 0L
        var expected = 0
        repeat(40) { i ->
            val steps = if (i % 2 == 0) 2 else -2
            c.onDetents(steps, now)
            expected += steps
            now += 15L
            c.onTimeout(now)
        }
        c.flush(now)
        assertEquals(expected, scrollTotal())
    }

    /** Every emitted event must be encodable, i.e. within the int8 steps range. */
    @Test
    fun everyEmissionIsWithinTheWireStepsRange() {
        val c = coalescer()
        var now = 0L
        repeat(50) {
            c.onDetents(9, now)
            now += 5L
        }
        c.flush(now)
        assertTrue(
            "emitted steps must fit the int8 wire field and the cap",
            emitted.all { abs(it.steps) in 1..RemoteInputProtocol.MAX_STEPS_PER_EVENT },
        )
    }

    @Test
    fun resetClearsPendingWindow() {
        val c = coalescer()
        c.onDetents(1, 0L)
        c.onDetents(5, 10L)
        c.reset()
        emitted.clear()
        c.onTimeout(1000L)
        assertTrue(emitted.isEmpty())
        assertNull(c.nextTimeoutAtMs())
    }
}
