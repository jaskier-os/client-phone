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
    private val inputClient: InputRfcommClient,
    private val statusSender: (ByteArray) -> Unit,
) {

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
    }

    private val worker = HandlerThread("watch-input-bridge").apply { start() }
    private val handler = Handler(worker.looper)

    /** Last sequence number actually forwarded, for the reorder guard. */
    private var lastForwarded = 0
    private var haveForwarded = false

    /** Depth of the worker queue, so a blocked write cannot grow it unboundedly. */
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile private var glassesSinkAttached = false
    @Volatile private var lastSendDropped = false
    @Volatile private var wakingGlasses = false
    @Volatile private var lastStatusPushMs = 0L

    init {
        inputClient.onLinkStateChanged = { up ->
            if (up) {
                wakingGlasses = false
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
        // Reorder guard. MessageClient is reliable but NOT order-guaranteed, so a
        // SELECT can overtake the SCROLL that preceded it. Forwarding the newer
        // one first would make the glasses drop the older as a duplicate, losing
        // it permanently. Anything at or behind what we already forwarded is a
        // duplicate or a late arrival and must not rewind the stream.
        if (haveForwarded && RemoteInputProtocol.seqDifference(event.seq, lastForwarded) <= 0) {
            Log.d(TAG, "dropping stale seq=${event.seqUnsigned} last=${lastForwarded.toUInt()}")
            return
        }

        if (!inputClient.isConnected) {
            // Never enqueue. A queued input frame is delivered stale and would sit
            // in a shared bounded queue evicting other features' traffic.
            if (!wakingGlasses) {
                wakingGlasses = true
                pushStatus(force = true)
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

        lastForwarded = event.seq
        haveForwarded = true
        lastSendDropped = false
    }

    /** Called when the glasses report whether their input sink is attached. */
    fun setGlassesSinkAttached(attached: Boolean) {
        if (glassesSinkAttached == attached) return
        glassesSinkAttached = attached
        pushStatus(force = true)
    }

    /** Replies to a watch PING with the current status. */
    fun onPing() {
        pushStatus(force = true)
    }

    private fun pushStatus(force: Boolean) {
        // Always evaluate on the worker: pushStatus is also called from the RFCOMM
        // connect thread via the link-state callback, which would otherwise race
        // lastStatusPushMs and the flag reads.
        if (Thread.currentThread() != worker) {
            handler.post { pushStatus(force) }
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
        try {
            statusSender(bits)
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
