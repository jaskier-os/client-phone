package com.repository.listener.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.repository.listener.bt.BtProtocol
import com.repository.listener.protocol.RemoteInputProtocol
import com.repository.listener.protocol.RemoteInputProtocol.EventType
import com.google.android.gms.wearable.MessageEvent
import com.repository.listener.protocol.RemoteInputProtocol.RemoteInputEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch

/**
 * Session-establishment and ordering tests for the REAL [WatchInputBridge].
 *
 * ## Why this test exists
 *
 * Two suites already covered this feature and both ran green while not one input
 * event had ever reached the glasses UI:
 *
 *  - `RemoteInputInjectionInstrumentedTest` (glasses) builds a bare router with a
 *    fake source, so it never touches the phone relay at all.
 *  - `InputPipelineStressTest` (watch) posts into a HandlerThread it creates itself
 *    and DISCARDS the coalescer's output, so nothing it asserts depends on a frame
 *    being transmitted.
 *
 * Between them they tested every component except the one that was broken: the
 * bridge's decision about WHICH frames get onto the wire. That is the gap this
 * closes. Everything below drives the production bridge and asserts on what it
 * actually handed to the transport -- never on an intermediate value, and never on
 * a double that reimplements the logic under test.
 *
 * These are instrumented rather than JVM tests because the bridge owns a real
 * HandlerThread and logs through android.util.Log; a JVM double for either would be
 * one more place for the test and the product to diverge.
 */
@RunWith(AndroidJUnit4::class)
class WatchInputBridgeSessionTest {

    /**
     * Records exactly what reached the socket, in order.
     *
     * Deliberately dumb: it decodes nothing and re-derives nothing. Every assertion
     * below reads the positional args the glasses would actually parse, so a change
     * that breaks the wire encoding cannot pass by agreeing with a helper.
     */
    private class RecordingTransport : WatchInputBridge.InputTransport {
        override var isConnected: Boolean = true
        override var onLinkStateChanged: ((Boolean) -> Unit)? = null

        val sent = mutableListOf<List<String>>()

        override fun send(channel: String, vararg args: String): Boolean {
            if (channel != BtProtocol.CH_REMOTE_INPUT) return true
            synchronized(sent) { sent += args.toList() }
            return true
        }

        /** [sid, seq, type] triples in transmit order. */
        fun forwarded(): List<Triple<String, String, String>> = synchronized(sent) {
            sent.map { Triple(it[2], it[3], it[4]) }
        }
    }

    private val transport = RecordingTransport()
    private val statuses = mutableListOf<ByteArray>()
    private lateinit var bridge: WatchInputBridge

    private val key = "watch-input-bridge-session-test-key".toByteArray()

    private fun newBridge(): WatchInputBridge = WatchInputBridge(
        inputClient = transport,
        statusSender = { bits -> synchronized(statuses) { statuses += bits } },
    )

    @After
    fun tearDown() {
        if (::bridge.isInitialized) bridge.shutdown()
    }

    /**
     * Hands one event to the bridge exactly as WatchMessageListenerService would,
     * tag included, then waits for the bridge's worker to drain.
     */
    private fun feed(sid: Int, seq: Int, type: EventType, steps: Int = 0) {
        val event = RemoteInputEvent(
            sid = sid, seq = seq, type = type, steps = steps, wms = seq * 100,
        )
        bridge.onEvent(event, RemoteInputProtocol.computeTagHex(key, event))
    }

    /**
     * Drains the bridge's worker by round-tripping a status push through it.
     *
     * `pushStatus` re-posts itself onto the same looper when called from another
     * thread, so once its callback has fired every event posted before it has been
     * processed. Polling `sent.size` instead would let a test pass by reading the
     * list before a dropped frame could have been added.
     */
    private fun drain() {
        val latch = CountDownLatch(1)
        synchronized(statuses) { statuses.clear() }
        bridge.onPing(0)
        // onPing forces a status push on the worker; wait for it to land.
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (synchronized(statuses) { statuses.isNotEmpty() }) { latch.countDown(); break }
            Thread.sleep(5)
        }
        assertTrue("bridge worker did not drain", latch.count == 0L)
    }

    /**
     * THE regression test for blocker B.
     *
     * The bridge outlives sessions, so a second session legitimately restarts its
     * sequence at 1. The shipped guard compared `seq` alone and never reset, so it
     * judged the new session against the old one's high-water mark and dropped it
     * whole -- `dropping stale seq=1 last=2`. Every session after the first was
     * permanently dead.
     *
     * Asserted on the frames that reached the socket, so it fails for the real
     * reason (nothing transmitted) rather than on a counter the bridge maintains
     * about itself.
     */
    @Test
    fun newSessionIsForwardedAfterAPreviousSessionAdvancedTheSequence() {
        bridge = newBridge()

        feed(sid = 26, seq = 1, type = EventType.OPEN)
        feed(sid = 26, seq = 2, type = EventType.SCROLL, steps = 1)
        drain()

        // Session 27: a fresh watch process. seq restarts at 1, BELOW the 2 the
        // previous session reached.
        feed(sid = 27, seq = 1, type = EventType.OPEN)
        feed(sid = 27, seq = 2, type = EventType.SCROLL, steps = 1)
        feed(sid = 27, seq = 3, type = EventType.SELECT)
        drain()

        val forwarded = transport.forwarded()
        val session27 = forwarded.filter { it.first == "27" }
        assertEquals(
            "every frame of the new session must reach the wire; got $forwarded",
            listOf(
                Triple("27", "1", "OPEN"),
                Triple("27", "2", "SCROLL"),
                Triple("27", "3", "SELECT"),
            ),
            session27,
        )
    }

    /**
     * OPEN must never be dropped by the ordering guard.
     *
     * The Data Layer is reliable but NOT order-guaranteed, and OPEN travels on its
     * own message path, so a SCROLL genuinely can arrive first. With a seq-only
     * guard the SCROLL raised the water mark past the OPEN and the OPEN was then
     * discarded -- after which the glasses reject every action of that session for
     * an unknown sid, forever. Losing the OPEN is not a late frame, it is a dead
     * session.
     */
    @Test
    fun openSurvivesBeingOvertakenByItsOwnSessionsFirstAction() {
        bridge = newBridge()

        // SCROLL (seq=2) overtakes OPEN (seq=1).
        feed(sid = 40, seq = 2, type = EventType.SCROLL, steps = 1)
        feed(sid = 40, seq = 1, type = EventType.OPEN)
        feed(sid = 40, seq = 3, type = EventType.SELECT)
        drain()

        val forwarded = transport.forwarded()
        assertTrue(
            "the OPEN must reach the glasses even when it arrives late; got $forwarded",
            forwarded.contains(Triple("40", "1", "OPEN")),
        )
        assertTrue(
            "a later action must still be forwarded after the late OPEN; got $forwarded",
            forwarded.contains(Triple("40", "3", "SELECT")),
        )
    }

    /**
     * The genuine protection the guard exists for must survive the fix: within one
     * session, a duplicate or rewound action is still refused. Fixing blocker B by
     * deleting the guard would pass the two tests above and silently reintroduce
     * this.
     */
    @Test
    fun withinOneSessionAStaleActionIsStillDropped() {
        bridge = newBridge()

        feed(sid = 50, seq = 1, type = EventType.OPEN)
        feed(sid = 50, seq = 2, type = EventType.SCROLL, steps = 1)
        feed(sid = 50, seq = 3, type = EventType.SCROLL, steps = 1)
        drain()
        // Replays of both a duplicate and a rewound sequence.
        feed(sid = 50, seq = 3, type = EventType.SELECT)
        feed(sid = 50, seq = 2, type = EventType.SELECT)
        drain()

        val selects = transport.forwarded().filter { it.third == "SELECT" }
        assertEquals("a replayed action must not reach the wire", emptyList<Any>(), selects)
    }

    /** A frame from a session the watch has already superseded is a replay. */
    @Test
    fun anOlderSessionIsDropped() {
        bridge = newBridge()

        feed(sid = 60, seq = 1, type = EventType.OPEN)
        feed(sid = 60, seq = 2, type = EventType.SCROLL, steps = 1)
        drain()
        feed(sid = 59, seq = 9, type = EventType.SELECT)
        drain()

        assertTrue(
            "a superseded session must not reach the wire",
            transport.forwarded().none { it.first == "59" },
        )
    }

    /**
     * PING must reach the glasses, not merely be answered.
     *
     * The glasses expire a session that has been silent for
     * [RemoteInputProtocol.SESSION_EXPIRY_MS] and then REJECT a PING for the sid
     * they just dropped, so a keepalive the phone swallows keeps nothing alive. The
     * shipped code answered the watch and returned, which made every session die
     * 20 s after it opened even once its OPEN was delivered.
     *
     * Driven through the REAL [WatchMessageListenerService.onMessageReceived],
     * because that is the file that decides this. Feeding PING straight into
     * `bridge.onEvent` would assert nothing: it would stay green with the old
     * `when { PING -> onPing; else -> onEvent }` restored, i.e. the regression test
     * would not test the regression.
     */
    @Test
    fun pingIsBothAnsweredAndForwardedByTheListenerService() {
        bridge = newBridge()
        WatchMessageListenerService.bridge = bridge
        try {
            val listener = WatchMessageListenerService()

            val open = RemoteInputEvent(sid = 70, seq = 1, type = EventType.OPEN, steps = 0, wms = 1)
            listener.onMessageReceived(
                messageEvent(RemoteInputProtocol.PATH_OPEN, RemoteInputProtocol.encodeEvent(key, open)),
            )
            val ping = RemoteInputEvent(sid = 70, seq = 2, type = EventType.PING, steps = 0, wms = 2)
            synchronized(statuses) { statuses.clear() }
            listener.onMessageReceived(
                messageEvent(RemoteInputProtocol.PATH_EVENT, RemoteInputProtocol.encodeEvent(key, ping)),
            )
            drain()

            assertTrue(
                "PING must be relayed so the glasses' session does not expire; got " +
                    transport.forwarded(),
                transport.forwarded().contains(Triple("70", "2", "PING")),
            )
            assertTrue(
                "the watch must still get its correlated status reply, or its RTT " +
                    "measurement and link display break",
                synchronized(statuses) { statuses.any { it.size > 1 } },
            )
        } finally {
            WatchMessageListenerService.bridge = null
        }
    }

    /** A minimal real [MessageEvent]; GMS ships it as an interface. */
    private fun messageEvent(path: String, payload: ByteArray): MessageEvent =
        object : MessageEvent {
            override fun getRequestId(): Int = 1
            override fun getPath(): String = path
            override fun getData(): ByteArray = payload
            override fun getSourceNodeId(): String = "test-node"
        }

    /**
     * The forwarded args must be the watch's own, byte for byte.
     *
     * The tag covers sid, seq, type, steps and wms, so any rewrite here produces a
     * tuple the glasses can never verify -- which presents as the feature being
     * completely dead with only a rate-limited "rejected" line to go on.
     */
    @Test
    fun forwardedArgsAreVerbatimAndStillVerify() {
        bridge = newBridge()

        val event = RemoteInputEvent(
            sid = 80, seq = 1, type = EventType.SCROLL, steps = -3, wms = 123456,
        )
        val tagHex = RemoteInputProtocol.computeTagHex(key, event)
        bridge.onEvent(event, tagHex)
        drain()

        val args = synchronized(transport.sent) { transport.sent.single() }
        val (decoded, decodedTag) = RemoteInputProtocol.fromRfcommArgs(args)
        assertEquals("the relayed event must be the watch's event", event, decoded)
        assertTrue(
            "the relayed tag must still verify against the watch's key",
            RemoteInputProtocol.verifyTagHex(key, decoded, decodedTag),
        )
    }

    /**
     * A negative control. Without this the suite could pass while the transport was
     * never consulted at all -- which is exactly the failure mode that let three
     * defects ship: assertions that hold whether or not anything is transmitted.
     */
    @Test
    fun nothingIsForwardedWhileTheLinkIsDown() {
        bridge = newBridge()
        transport.isConnected = false

        feed(sid = 90, seq = 1, type = EventType.OPEN)
        feed(sid = 90, seq = 2, type = EventType.SCROLL, steps = 1)
        drain()

        assertEquals(
            "a down link must forward nothing",
            emptyList<Any>(), transport.forwarded(),
        )

        transport.isConnected = true
        feed(sid = 90, seq = 3, type = EventType.SCROLL, steps = 1)
        drain()
        assertTrue(
            "the transport must actually be reachable in this harness",
            transport.forwarded().isNotEmpty(),
        )
    }

}
