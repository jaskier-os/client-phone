package com.repository.listener.wear

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.repository.listener.protocol.RemoteInputProtocol
import com.repository.listener.protocol.RemoteInputProtocol.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Session-establishment tests for the REAL [WatchLinkService].
 *
 * ## Why this exists
 *
 * The shipped watch never sent a single OPEN. `openSession()` kicked off the
 * ASYNCHRONOUS node resolution and then called `sendEvent(OPEN)` immediately;
 * `sendEvent` early-returns while `phoneNodeId` is null, which on a fresh process it
 * always is at that instant. Nothing re-sent it. The glasses adopt no session
 * implicitly, so every action was rejected -- `action for unknown sid` -- for the
 * life of the session, forever.
 *
 * No unit test could have found that, and none did: every individual component was
 * correct. The defect lived in the INTERACTION between an async resolver and a
 * send path that treats "no node yet" as "nothing to do". So these tests drive the
 * real service, with the real worker thread and the real asynchronous resolution
 * shape, and assert on the frames that were genuinely handed to the transport --
 * decoded from the actual wire payload, not from a value the service reported about
 * itself.
 *
 * The existing suites are why this is necessary. `InputPipelineStressTest` discards
 * the coalescer's output and never transmits; `RemoteInputInjectionInstrumentedTest`
 * on the glasses builds a bare router with a fake source. Both were green
 * throughout.
 *
 * Run with `am instrument`, never connectedAndroidTest -- its teardown uninstalls
 * the app under test.
 */
@RunWith(AndroidJUnit4::class)
class SessionEstablishmentTest {

    private class SentFrame(val path: String, val payload: ByteArray) {
        val decoded = RemoteInputProtocol.decodeEvent(payload)
        val type: EventType get() = decoded.event.type
        val sid: Int get() = decoded.event.sid
        val seq: Int get() = decoded.event.seq
        override fun toString() = "$type sid=$sid seq=$seq path=$path"
    }

    private val sent = CopyOnWriteArrayList<SentFrame>()

    /**
     * Builds the service WITHOUT the Android service lifecycle.
     *
     * The lifecycle is not what is under test -- foreground promotion and the
     * notification channel are, in this context, ceremony that would need real
     * permissions to satisfy. The session logic under test lives entirely on the
     * worker thread, which [startForTest] brings up exactly as `onCreate` does.
     */
    /**
     * A [WatchLinkService] with a real base Context attached.
     *
     * Constructing the class alone is not enough: `mintSid` reads SharedPreferences
     * and `recomputeState` touches the complication, both of which go through
     * ContextWrapper and would NPE on the worker thread -- killing the process
     * rather than failing a test. `attachBaseContext` is the documented way to give
     * a Service its Context without the service lifecycle, which is exactly the
     * split wanted here: real session logic, no foreground-service ceremony.
     */
    private fun newBareService(): WatchLinkService {
        val service = WatchLinkService()
        val method = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
        method.isAccessible = true
        method.invoke(
            service,
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().targetContext,
        )
        return service
    }

    private fun newService(
        resolveTo: String?,
        resolveDelayMs: Long = 40L,
    ): WatchLinkService {
        val service = newBareService()
        service.testFrameSink = { path, payload -> sent += SentFrame(path, payload) }
        // Asynchronous BY CONSTRUCTION, and delivered on another thread, because
        // that is the shape the defect lived in. A synchronous stub here would make
        // the test pass against the broken code.
        service.testNodeResolver = { onResolved ->
            Thread {
                Thread.sleep(resolveDelayMs)
                onResolved(resolveTo)
            }.apply { isDaemon = true }.start()
        }
        service.startForTest()
        return service
    }

    /** Polls until [condition] holds or the timeout expires. */
    private fun awaitCondition(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun awaitFrame(predicate: (SentFrame) -> Boolean): Boolean =
        awaitCondition { sent.any(predicate) }

    /**
     * THE regression test for blocker A.
     *
     * Against the shipped code this fails: `session open sid=N` is logged, no OPEN
     * is ever transmitted, and the SCROLL goes out alone into a session the glasses
     * will not recognise.
     */
    @Test
    fun openIsSentOnceTheNodeResolvesEvenThoughItWasUnknownAtSessionStart() {
        val service = newService(resolveTo = "phone-node-1")
        try {
            // A real bezel turn: detents keep coming while node resolution is still
            // in flight. That overlap is the exact ordering that produced the
            // shipped failure. Detents produced strictly BEFORE the node exists are
            // legitimately lost -- input is never queued, because a queued scroll
            // lands stale -- so what must be proven is that the session establishes
            // and the detents that follow are carried, not that the earliest one is.
            repeat(20) {
                service.onRotaryDelta(1.0f, SystemClock.elapsedRealtime())
                Thread.sleep(25)
            }

            assertTrue(
                "no OPEN was ever transmitted; sent=$sent",
                awaitFrame { it.type == EventType.OPEN },
            )
            assertTrue(
                "no SCROLL followed the OPEN; sent=$sent",
                awaitFrame { it.type == EventType.SCROLL },
            )

            val open = sent.first { it.type == EventType.OPEN }
            val firstAction = sent.first { !it.type.ttlExempt }
            assertEquals(
                "the action must belong to the session that was opened",
                open.sid, firstAction.sid,
            )
            // Ordering is a correctness requirement, not a nicety: the phone relay
            // drops anything at or behind what it already forwarded, so an action
            // that precedes its OPEN makes the phone discard the OPEN and the
            // session is then dead on the glasses.
            assertTrue(
                "OPEN must carry a lower seq than the first action; sent=$sent",
                RemoteInputProtocol.seqDifference(firstAction.seq, open.seq) > 0,
            )
            assertEquals(
                "OPEN must go on the open path", RemoteInputProtocol.PATH_OPEN, open.path,
            )
        } finally {
            service.stopForTest()
        }
    }

    /**
     * A session must be re-announced after the node is lost and re-found. Without
     * this the fix would only cover the cold-start case, and a transient link drop
     * would leave the session established on the watch and unknown on the glasses.
     */
    @Test
    fun sessionIsReannouncedAfterNodeLoss() {
        val service = newBareService()
        service.testFrameSink = { path, payload -> sent += SentFrame(path, payload) }
        // AtomicReference, not a captured var: the resolver runs on its own thread
        // and the test mutates this from the test thread.
        val resolveTo = java.util.concurrent.atomic.AtomicReference<String?>("phone-node-1")
        service.testNodeResolver = { onResolved ->
            val target = resolveTo.get()
            Thread {
                Thread.sleep(20)
                onResolved(target)
            }.apply { isDaemon = true }.start()
        }
        service.startForTest()
        try {
            service.onRotaryDelta(1.0f, SystemClock.elapsedRealtime())
            assertTrue("initial OPEN missing; sent=$sent", awaitFrame { it.type == EventType.OPEN })
            val opensBefore = sent.count { it.type == EventType.OPEN }

            // The node goes away and comes back.
            resolveTo.set(null)
            service.resolveForTest()
            Thread.sleep(200)
            resolveTo.set("phone-node-1")
            service.resolveForTest()

            assertTrue(
                "the session was not re-announced after node loss; sent=$sent",
                awaitCondition { sent.count { f -> f.type == EventType.OPEN } > opensBefore },
            )

            val opens = sent.filter { it.type == EventType.OPEN }
            assertEquals(
                "a re-announce must reuse the same sid, or the glasses would treat it " +
                    "as a new session and the watch's own sequence would not match",
                1, opens.map { it.sid }.distinct().size,
            )
        } finally {
            service.stopForTest()
        }
    }

    /**
     * The keepalive must not back off before a detent has ever been seen.
     *
     * `lastDetentMs` starts at 0 and only a real detent writes it, so `now - 0` is
     * the device uptime -- days on this hardware. That made the idle backoff engage
     * on every FRESH session, producing a 30 s keepalive gap against the glasses'
     * 20 s expiry from the very first session: the user saw
     * "Phone app stopped - open it" on a healthy link while the watch logged a
     * ~29 s silence and reopened, forever.
     */
    @Test
    fun keepaliveDoesNotBackOffBeforeTheFirstDetent() {
        val uptime = 886_000_000L // a realistic multi-day elapsedRealtime
        assertEquals(
            "a session with no detent yet must use the ACTIVE cadence; treating " +
                "'never' as 'idle for the whole uptime' is what broke this",
            RemoteInputProtocol.PING_INTERVAL_MS,
            WatchLinkService.pingIntervalFor(lastDetentMs = 0L, nowMs = uptime),
        )
        // A genuinely idle session may still back off.
        assertEquals(
            RemoteInputProtocol.PING_IDLE_BACKOFF_MS,
            WatchLinkService.pingIntervalFor(
                lastDetentMs = uptime - RemoteInputProtocol.IDLE_BEFORE_PING_BACKOFF_MS - 1,
                nowMs = uptime,
            ),
        )
        // ...but no cadence it can return may be slower than the expiry it prevents.
        assertTrue(
            "every cadence this can return must beat the glasses session expiry",
            listOf(
                WatchLinkService.pingIntervalFor(0L, uptime),
                WatchLinkService.pingIntervalFor(uptime - 1, uptime),
                WatchLinkService.pingIntervalFor(1L, uptime),
            ).all { it < RemoteInputProtocol.SESSION_EXPIRY_MS },
        )
    }

    /**
     * The watch must be able to reach a healthy display from its own initial state.
     *
     * The status fold AND-s health bits, so a health-bits-clear starting value is
     * absorbing: no number of healthy frames could ever turn PHONE_SERVICE_ALIVE
     * back on, and the watch showed "Phone app stopped" permanently on a link that
     * was working.
     */
    @Test
    fun theStatusFoldStartsFromAStateHealthCanBeReachedFrom() {
        val service = newService(resolveTo = "phone-node-1")
        try {
            val healthy = RemoteInputProtocol.StatusFlags.decode(
                RemoteInputProtocol.StatusFlags.encode(
                    glassesLinkUp = true, phoneServiceAlive = true, lastSendDropped = false,
                    glassesSinkAttached = true, wakingGlasses = false,
                )
            )
            repeat(3) { service.onStatus(healthy, null) }
            assertTrue(
                "the fold never became healthy; bits=${service.statusBitsForTest()}",
                awaitCondition {
                    RemoteInputProtocol.StatusFlags.isSet(
                        service.statusBitsForTest(),
                        RemoteInputProtocol.StatusFlags.PHONE_SERVICE_ALIVE,
                    )
                },
            )
        } finally {
            service.stopForTest()
        }
    }

    /**
     * A burst must not prepend one OPEN per event. The phone's relay queue is
     * bounded at 8 and drops the newest beyond that, so an OPEN storm would evict
     * the very input it was trying to enable.
     */
    @Test
    fun aBurstDoesNotProduceAnOpenStorm() {
        val service = newService(resolveTo = "phone-node-1")
        try {
            repeat(40) {
                service.onRotaryDelta(1.0f, SystemClock.elapsedRealtime())
                Thread.sleep(10)
            }
            assertTrue("no OPEN at all; sent=$sent", awaitFrame { it.type == EventType.OPEN })
            Thread.sleep(500)

            val opens = sent.count { it.type == EventType.OPEN }
            assertEquals("exactly one OPEN should establish this session; sent=$sent", 1, opens)
        } finally {
            service.stopForTest()
        }
    }

    /**
     * Negative control. Without a node nothing may be transmitted -- and if this
     * ever passed vacuously, so would every test above.
     */
    @Test
    fun nothingIsSentWhileNoNodeExists() {
        val service = newService(resolveTo = null)
        try {
            service.onRotaryDelta(1.0f, SystemClock.elapsedRealtime())
            Thread.sleep(400)
            assertEquals("nothing may be sent with no node; sent=$sent", 0, sent.size)
        } finally {
            service.stopForTest()
        }
    }
}
