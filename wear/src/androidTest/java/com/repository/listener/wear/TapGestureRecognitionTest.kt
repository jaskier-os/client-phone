package com.repository.listener.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.repository.listener.protocol.RemoteInputProtocol
import com.repository.listener.protocol.RemoteInputProtocol.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The watch's LOCAL tap recogniser, asserted on the bytes it actually transmits.
 *
 * ## Why this exists
 *
 * A remote double tap used to dispatch a select AND a back on the glasses, 15 ms apart, which
 * cancelled out and made "back" do nothing at all. The cause was architectural: the glasses were
 * asked to tell a single tap from a double one, which cannot be done without deferring the first tap
 * for the whole window -- on top of ~450 ms of transport. Recognition moved here, where the wait is
 * free, and the watch now emits a SEMANTIC ACTION.
 *
 * ## Why it asserts on transmitted frames
 *
 * The property that matters is not "the recogniser returned BACK" but "exactly one frame left this
 * device and it was a BACK". A test that inspected an internal verdict would pass while the
 * coalescer, the sequence counter or the send path emitted a stray SELECT alongside it -- which is
 * the precise shape of the bug being fixed. So this drives the real service, real worker thread,
 * real coalescer and real encoder, and decodes what was handed to the transport.
 *
 * Note the COUNT assertions are as load-bearing as the type ones. `assertTrue(sent.any { BACK })`
 * would be green against the broken code, because the broken code sent a BACK too -- it just sent a
 * SELECT first.
 *
 * Run with `am instrument`, never connectedAndroidTest -- its teardown uninstalls the app.
 */
@RunWith(AndroidJUnit4::class)
class TapGestureRecognitionTest {

    private class SentFrame(val path: String, payload: ByteArray) {
        val decoded = RemoteInputProtocol.decodeEvent(payload)
        val type: EventType get() = decoded.event.type
        val seq: Int get() = decoded.event.seq
        override fun toString() = "$type seq=$seq"
    }

    private val sent = CopyOnWriteArrayList<SentFrame>()

    /** Frames a user could have caused. OPEN/CLOSE/PING are session ceremony. */
    private fun actions() = sent.filter { it.type.isUserAction }

    private fun newService(): WatchLinkService {
        val service = WatchLinkService()
        val attach = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
        attach.isAccessible = true
        attach.invoke(
            service,
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
        )
        service.testFrameSink = { path, payload -> sent += SentFrame(path, payload) }
        // Resolve synchronously-ish so the OPEN is out before the taps; the async SHAPE is
        // exercised by SessionEstablishmentTest and is not what is under test here.
        service.testNodeResolver = { onResolved ->
            Thread { onResolved("phone-node-1") }.apply { isDaemon = true }.start()
        }
        service.startForTest()
        awaitCondition { sent.any { it.type == EventType.OPEN } }
        return service
    }

    private fun awaitCondition(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return false
    }

    /** Long enough for the recognition window to close plus send latency. */
    private fun settle() =
        Thread.sleep(RemoteInputProtocol.DOUBLE_TAP_WINDOW_MS + 600L)

    /**
     * Injects taps with the gap between them SPECIFIED, not slept for.
     *
     * `Thread.sleep(150)` from the instrumentation thread was measured landing 552 ms later on this
     * watch under test load, which the recogniser then correctly called two singles and the test
     * wrongly called a failure. Feeding the tap instant in removes the scheduler from the assertion
     * while leaving everything the timestamp feeds -- worker thread, coalescer, sequence numbering,
     * encoder, send path -- entirely real. It is the same seam ScrollCoalescer already uses.
     */
    private fun tapsWithGaps(service: WatchLinkService, vararg gapsMs: Long) {
        var t = android.os.SystemClock.elapsedRealtime()
        service.onTapAt(t)
        for (gap in gapsMs) {
            t += gap
            Thread.sleep(40) // let the worker actually process the previous tap first
            service.onTapAt(t)
        }
    }

    @Test
    fun oneTapSendsExactlyOneSelectAndNoBack() {
        val service = newService()
        try {
            service.onTap()
            settle()

            assertEquals(
                "one tap must produce exactly one action frame; sent=${actions()}",
                1, actions().size,
            )
            assertEquals(EventType.SELECT, actions().single().type)
        } finally {
            service.stopForTest()
        }
    }

    /**
     * THE regression test for the reported defect.
     *
     * Against the previous design this fails with two frames: a SELECT (the raw first tap) and a
     * BACK, which is what made the glasses enter something and immediately leave it.
     */
    @Test
    fun twoQuickTapsSendExactlyOneBackAndNoSelect() {
        val service = newService()
        try {
            // 150 ms is a natural double-tap gap, well inside the window.
            tapsWithGaps(service, 150L)
            settle()

            assertEquals(
                "a double tap must produce exactly ONE action frame -- a stray SELECT " +
                    "alongside the BACK is the bug this replaced; sent=${actions()}",
                1, actions().size,
            )
            assertEquals(EventType.BACK, actions().single().type)
        } finally {
            service.stopForTest()
        }
    }

    /**
     * Two taps that are NOT a double must stay two singles.
     *
     * The negative control for the test above: without it, a recogniser that answered BACK to
     * everything would pass, and so would one that swallowed the second tap entirely.
     */
    @Test
    fun twoSlowTapsSendTwoSelects() {
        val service = newService()
        try {
            tapsWithGaps(service, RemoteInputProtocol.DOUBLE_TAP_WINDOW_MS + 150L)
            settle()

            assertEquals(
                "two taps outside the window are two singles; sent=${actions()}",
                2, actions().size,
            )
            assertTrue(
                "both must be SELECT; sent=${actions()}",
                actions().all { it.type == EventType.SELECT },
            )
        } finally {
            service.stopForTest()
        }
    }

    /**
     * A third rapid tap must not produce anything absurd.
     *
     * The second tap CLOSES the window before emitting BACK, so the third opens a fresh one and is
     * judged on its own: BACK then SELECT. What must never happen is a second BACK synthesized by
     * re-using the first tap, or two overlapping windows racing each other.
     */
    @Test
    fun threeRapidTapsAreBackThenSelectNeverTwoBacks() {
        val service = newService()
        try {
            tapsWithGaps(service, 120L, 120L)
            settle()

            assertEquals(
                "three rapid taps must produce exactly two actions; sent=${actions()}",
                2, actions().size,
            )
            assertEquals(
                "the pair resolves first, then the orphan; sent=${actions()}",
                listOf(EventType.BACK, EventType.SELECT),
                actions().map { it.type },
            )
        } finally {
            service.stopForTest()
        }
    }

    /**
     * A tap must never be merged with scroll, and must keep its place in the order.
     *
     * The coalescer merges detent bursts; an action riding that window would arrive out of order
     * relative to the scroll around it and re-aim the user's selection.
     */
    @Test
    fun anActionIsNeverMergedWithScrollAndKeepsItsOrder() {
        val service = newService()
        try {
            repeat(6) {
                service.onRotaryDelta(1.0f, android.os.SystemClock.elapsedRealtime())
                Thread.sleep(35)
            }
            Thread.sleep(200)
            val scrollsBefore = sent.count { it.type == EventType.SCROLL }
            assertTrue("no scroll was transmitted at all; sent=$sent", scrollsBefore > 0)

            service.onTap()
            settle()

            val select = actions().filter { it.type == EventType.SELECT }
            assertEquals("exactly one SELECT expected; sent=${actions()}", 1, select.size)
            // Every scroll that preceded the tap must carry a lower seq than it: seq is minted at
            // emission, so this is the transmitted order, not a reported one.
            val selectSeq = select.single().seq
            assertTrue(
                "the SELECT overtook a scroll that preceded it; sent=$sent",
                sent.filter { it.type == EventType.SCROLL }
                    .take(scrollsBefore)
                    .all { RemoteInputProtocol.seqDifference(selectSeq, it.seq) > 0 },
            )
        } finally {
            service.stopForTest()
        }
    }

    /**
     * A tap still inside its window when the session ends is a SINGLE, not a discarded event.
     *
     * No second tap is coming, so dropping it would silently lose an action the user performed --
     * the same conservation rule the coalescer's own flush exists to satisfy.
     */
    @Test
    fun aTapPendingAtSessionCloseIsEmittedAsSelect() {
        val service = newService()
        try {
            service.onTap()
            Thread.sleep(60) // still well inside the recognition window
            service.closeSessionForTest()
            assertTrue(
                "the pending tap was dropped on session close; sent=${actions()}",
                awaitCondition { actions().any { it.type == EventType.SELECT } },
            )
            assertEquals(
                "and it must be exactly one; sent=${actions()}",
                1, actions().size,
            )
        } finally {
            service.stopForTest()
        }
    }
}
