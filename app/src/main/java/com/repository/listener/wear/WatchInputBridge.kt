package com.repository.listener.wear

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.repository.listener.bt.BtProtocol
import com.repository.listener.bt.InputRfcommClient
import com.repository.listener.protocol.RemoteInputProtocol
import com.repository.listener.protocol.RemoteInputProtocol.RemoteInputEvent
import com.repository.listener.protocol.RemoteInputProtocol.StatusFlags

/**
 * Forwards remote input from the watch onto the dedicated glasses input socket.
 *
 * The bridge deliberately does NOT coalesce and does NOT hold the HMAC key.
 * Coalescing happens on the watch, before the tag is computed, because the tag
 * covers `seq` and `steps` and any merge here would rewrite both and produce a
 * tuple the glasses could never verify. For the same reason the bridge forwards
 * every authenticated field VERBATIM.
 *
 * Not holding the key is also a deliberate security decision: the watch signs and
 * the glasses verify, so a phone compromise cannot mint input events. The cost is
 * that the status backchannel is unauthenticated, which is why the watch treats
 * it as advisory only.
 *
 * Threading: everything runs on [worker]. `onMessageReceived` hands off here
 * immediately, so a slow RFCOMM write can never block a GMS Binder thread.
 */
class WatchInputBridge(
    private val inputClient: InputTransport,
    private val statusSender: (ByteArray) -> Unit,
) {

    /**
     * The slice of [InputRfcommClient] this bridge uses.
     *
     * Extracted so the bridge's session and ordering rules can be exercised against
     * a recording double on the JVM. The previous tests all built their own routers
     * and fake sources, so they ran green while every real session was being dropped
     * here -- the guard itself was never the thing under test.
     */
    interface InputTransport {
        val isConnected: Boolean
        fun send(channel: String, vararg args: String): Boolean
        var onLinkStateChanged: ((Boolean) -> Unit)?
    }

    companion object {
        private const val TAG = "WatchInputBridge"

        /** Minimum spacing between status pushes, so a dead link cannot flood. */
        private const val STATUS_MIN_INTERVAL_MS = 1000L

        /**
         * Max events queued toward the socket. A peer that stops reading leaves
         * the socket CONNECTED while the write blocks on RFCOMM flow control, so
         * an isConnected check alone does not bound anything. Beyond this depth
         * the newest events are dropped: delivering a stale burst later is worse
         * than dropping now, which is the same reason input is never queued.
         */
        private const val MAX_PENDING_EVENTS = 8

        /**
         * How long "Waking glasses..." may be claimed before it is treated as failed.
         *
         * A wake is a BLE notify plus an RFCOMM page; on a healthy link that is a
         * couple of seconds, and the periodic self-heal retries every 5 s (30 s while
         * the desktop audio relay holds the radio). 45 s therefore spans several full
         * retry cycles, so this cannot fire on a wake that is merely slow -- only on
         * one that is not happening.
         */
        const val WAKE_CLAIM_TIMEOUT_MS = 45_000L
    }

    private val worker = HandlerThread("watch-input-bridge").apply { start() }
    private val handler = Handler(worker.looper)

    /**
     * Reorder guard state: the last (sid, seq) actually forwarded.
     *
     * The sid is part of the key, not decoration. `seq` restarts at 1 for every new
     * session while this bridge outlives all of them -- it is constructed once by
     * ListenerService -- so a guard on `seq` alone judges every new session's first
     * frames against the PREVIOUS session's high-water mark and drops the entire
     * session. That is a liveness bug, not a security one: the phone holds no HMAC
     * key and forwards unverified, so the authoritative replay defence is the
     * glasses' persisted monotonic-sid store. This guard exists only to stop a
     * later frame from overtaking an earlier one WITHIN a session.
     *
     * Worker-thread confined: written only in [forward], which runs on [handler].
     */
    private var lastForwarded = 0
    private var lastForwardedSid = 0
    private var haveForwarded = false

    /** Depth of the worker queue, so a blocked write cannot grow it unboundedly. */
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile private var glassesSinkAttached = false
    @Volatile private var lastSendDropped = false
    @Volatile private var wakingGlasses = false

    /** elapsedRealtime when the current wake claim started; 0 when not waking. */
    @Volatile private var wakeStartedMs = 0L
    @Volatile private var lastStatusPushMs = 0L

    init {
        inputClient.onLinkStateChanged = { up ->
            if (up) {
                wakingGlasses = false
                wakeStartedMs = 0L
                lastSendDropped = false
            }
            pushStatus(force = true)
        }
    }

    /**
     * Accepts a decoded event from the watch. Runs off the Binder thread.
     *
     * The tag is NOT verified here: the phone has no key by design. Structural
     * validation already happened in the decoder, and the glasses perform the
     * authoritative authentication.
     */
    fun onEvent(event: RemoteInputEvent, tagHex: String) {
        if (pending.get() >= MAX_PENDING_EVENTS) {
            lastSendDropped = true
            Log.w(TAG, "queue full (${pending.get()}); dropping ${event.type}")
            return
        }
        pending.incrementAndGet()
        val posted = handler.post {
            try {
                forward(event, tagHex)
            } finally {
                pending.decrementAndGet()
            }
        }
        // post() returns false once the looper has quit; without this the counter
        // would leak upward and permanently wedge the queue at "full".
        if (!posted) pending.decrementAndGet()
    }

    private fun forward(event: RemoteInputEvent, tagHex: String) {
        // Reorder guard, keyed on (sid, seq). MessageClient is reliable but NOT
        // order-guaranteed, so a SELECT can overtake the SCROLL that preceded it.
        // Forwarding the newer one first would make the glasses drop the older as a
        // duplicate, losing it permanently.
        //
        // OPEN is exempt. It is what establishes the session on the glasses, and the
        // glasses adopt no session implicitly. If a SCROLL overtook its OPEN, a
        // seq-only guard would forward the SCROLL, raise the water mark past the
        // OPEN, and then discard the OPEN -- after which every action of that
        // session is rejected for an unknown sid, permanently. A duplicate OPEN
        // costs nothing: the glasses treat an OPEN for the session already in
        // progress as liveness and preserve their own sequence floor.
        if (haveForwarded && event.type != RemoteInputProtocol.EventType.OPEN) {
            // Wrap-safe on both axes: sid and seq are uint32 in an Int, and a plain
            // comparison locks the source out forever past 2^31.
            val sidDiff = RemoteInputProtocol.seqDifference(event.sid, lastForwardedSid)
            if (sidDiff < 0) {
                Log.d(TAG, "dropping old session sid=${event.sidUnsigned} last=${lastForwardedSid.toUInt()}")
                return
            }
            // sidDiff > 0 is a NEW session: its seq legitimately restarts, so the
            // previous session's water mark says nothing about it and is discarded.
            // Only within one session (sidDiff == 0) does seq ordering mean anything.
            if (sidDiff == 0 &&
                RemoteInputProtocol.seqDifference(event.seq, lastForwarded) <= 0
            ) {
                Log.d(TAG, "dropping stale seq=${event.seqUnsigned} last=${lastForwarded.toUInt()}")
                return
            }
        }

        if (!inputClient.isConnected) {
            // Never enqueue. A queued input frame is delivered stale and would sit
            // in a shared bounded queue evicting other features' traffic.
            //
            // "Waking" is a CLAIM WITH A DEADLINE, not a latch. It previously cleared
            // only on a link-up edge, so if the wake never completed the watch showed
            // "Waking glasses..." forever -- indistinguishable from progress, and the
            // same shape as a status bit that could never clear. Past the deadline the
            // wake has demonstrably failed, and the honest report is that the glasses
            // are offline, which is what the watch already has copy for.
            val now = SystemClock.elapsedRealtime()
            if (!wakingGlasses) {
                wakingGlasses = true
                wakeStartedMs = now
                Log.i(TAG, "link down; claiming wake for up to ${WAKE_CLAIM_TIMEOUT_MS}ms")
                pushStatus(force = true)
            } else {
                val wasWaking = wakingGlasses
                expireStaleWakeClaim()
                if (wasWaking && !wakingGlasses) pushStatus(force = true)
            }
            Log.d(TAG, "link down; dropping ${event.type}")
            return
        }

        val args = RemoteInputProtocol.toRfcommArgs(event, tagHex)
        val ok = inputClient.send(BtProtocol.CH_REMOTE_INPUT, *args)
        if (!ok) {
            // The socket can die between the isConnected check and the write.
            lastSendDropped = true
            wakingGlasses = true
            pushStatus(force = true)
            return
        }

        // Never let an out-of-order OPEN rewind the water mark of a session already
        // in flight; it is exempt from the guard, not licensed to reset it.
        if (!haveForwarded ||
            RemoteInputProtocol.seqDifference(event.sid, lastForwardedSid) > 0
        ) {
            lastForwardedSid = event.sid
            lastForwarded = event.seq
        } else if (event.sid == lastForwardedSid &&
            RemoteInputProtocol.seqDifference(event.seq, lastForwarded) > 0
        ) {
            lastForwarded = event.seq
        }
        haveForwarded = true
        lastSendDropped = false
        // Log the successful forward too. Only logging failures makes a healthy
        // path silent, which is what made the earlier delivery question
        // undiagnosable from logs alone.
        Log.i(TAG, "FWD type=${event.type} seq=${event.seqUnsigned} steps=${event.steps}")
    }

    /** Called when the glasses report whether their input sink is attached. */
    fun setGlassesSinkAttached(attached: Boolean) {
        if (glassesSinkAttached == attached) return
        glassesSinkAttached = attached
        pushStatus(force = true)
    }

    /**
     * Replies to a watch PING, echoing its seq so the watch can distinguish a
     * genuine reply from an unsolicited push and time only the former.
     *
     * The PING is also what re-evaluates a stale wake claim: it arrives every 10 s
     * regardless of user input, so a wake that never completes is reported as a
     * failure within one timeout even if the user has stopped touching the bezel.
     * Relying on the next input event instead would mean the lie persists exactly
     * when the user gives up and stops generating events.
     */
    fun onPing(pingSeq: Int) {
        expireStaleWakeClaim()
        pushStatus(force = true, replyToSeq = pingSeq)
    }

    /**
     * Drops a wake claim that has outlived [WAKE_CLAIM_TIMEOUT_MS].
     *
     * Separate from the send path so it runs on time-based signals too, not only
     * when the user happens to produce input.
     */
    private fun expireStaleWakeClaim() {
        if (!wakingGlasses) return
        if (inputClient.isConnected) return
        val started = wakeStartedMs
        if (started == 0L) return
        if (SystemClock.elapsedRealtime() - started <= WAKE_CLAIM_TIMEOUT_MS) return
        wakingGlasses = false
        wakeStartedMs = 0L
        Log.w(TAG, "wake did not complete in ${WAKE_CLAIM_TIMEOUT_MS}ms; reporting glasses offline")
    }

    private fun pushStatus(force: Boolean, replyToSeq: Int? = null) {
        // Always evaluate on the worker: pushStatus is also called from the RFCOMM
        // connect thread via the link-state callback, which would otherwise race
        // lastStatusPushMs and the flag reads.
        if (Thread.currentThread() != worker) {
            handler.post { pushStatus(force, replyToSeq) }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastStatusPushMs < STATUS_MIN_INTERVAL_MS) return
        lastStatusPushMs = now
        val bits = StatusFlags.encode(
            glassesLinkUp = inputClient.isConnected,
            // Always true here by construction: this bridge only exists while
            // ListenerService is alive. The false case is reported directly by
            // WatchMessageListenerService when it finds no bridge at all.
            phoneServiceAlive = true,
            lastSendDropped = lastSendDropped,
            glassesSinkAttached = glassesSinkAttached,
            wakingGlasses = wakingGlasses,
        )
        val frame = if (replyToSeq != null) {
            StatusFlags.encodeWithReplyTo(bits, replyToSeq)
        } else {
            bits
        }
        try {
            statusSender(frame)
        } catch (e: Exception) {
            Log.w(TAG, "status push failed: ${e.message}")
        }
    }

    fun shutdown() {
        // Clear first: the client outlives the bridge, and the lambda captures
        // this bridge (and through it the destroyed service).
        inputClient.onLinkStateChanged = null
        handler.removeCallbacksAndMessages(null)
        worker.quitSafely()
    }
}
