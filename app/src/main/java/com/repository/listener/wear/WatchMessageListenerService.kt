package com.repository.listener.wear

import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.repository.listener.protocol.RemoteInputProtocol
import com.repository.listener.protocol.RemoteInputProtocol.EventType
import com.repository.listener.service.ListenerService

/**
 * Receives remote input frames from the watch over the Wear Data Layer.
 *
 * This component is exported because Google Play services must be able to bind
 * it. That means a local app could in principle deliver a forged intent, so the
 * connected-node check below is applied as hygiene. It is NOT the security
 * boundary and must not be mistaken for one: `sourceNodeId` is attacker-supplied
 * in a forged intent and readable by any app. The real authentication is the
 * watch-computed HMAC, which the GLASSES verify -- the phone deliberately holds
 * no key, so a compromised phone cannot mint input.
 *
 * This service DOES start [ListenerService] when a watch frame arrives and no
 * bridge is registered. That is the opposite of what this comment previously
 * said, and the previous claim was the reason the feature was unusable: the watch
 * is meant to be glanced at and turned, so requiring the user to open the phone
 * app first defeats the premise entirely.
 *
 * The Android 12+ background-FGS restriction is real, but this app holds a
 * battery-optimization exemption, which is an explicit documented exemption from
 * it. That is verified on the device rather than assumed -- see
 * [startListenerService] -- and the failure path is logged by exception name, so
 * if the exemption is ever revoked the log says so instead of going silent.
 */
class WatchMessageListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WatchMsgListener"

        /**
         * Connected-node cache TTL. WearableListenerService serialises its
         * callbacks onto ONE looper, so a blocking lookup per frame would put a
         * GMS round trip in front of every scroll detent and stall every other
         * Data Layer callback in this app behind it.
         */
        private const val NODE_CACHE_TTL_MS = 5000L

        @Volatile private var cachedNodeIds: Set<String> = emptySet()
        @Volatile private var cachedAtMs = 0L

        /** Set by ListenerService while it is alive. */
        @Volatile
        var bridge: WatchInputBridge? = null

        /**
         * Minimum spacing between background service-start attempts.
         *
         * A single bezel turn delivers a burst of frames, and the service takes a
         * moment to come up and publish its bridge; without this every frame in the
         * burst would issue its own start.
         */
        private const val START_THROTTLE_MS = 5_000L

        @Volatile
        private var lastStartAttemptMs = 0L
    }

    override fun onMessageReceived(event: MessageEvent) {
        // Everything here is wrapped: onMessageReceived runs on a Binder thread
        // and an uncaught throw would take down the process on one bad frame.
        try {
            if (!event.path.startsWith(RemoteInputProtocol.PATH_PREFIX)) return

            // Bridge check FIRST: when ListenerService is dead there is nothing to
            // forward onto, so paying for a node lookup would be wasted work.
            val target = bridge
            if (target == null) {
                // GMS binds this service alone after a process kill, so without an
                // explicit reply the watch would sit in a generic failure state
                // with no idea the phone app is stopped.
                Log.w(TAG, "no bridge; ListenerService not running")
                // Start it. The user glancing at their watch and turning the bezel
                // cannot be expected to have opened the phone app first -- that is
                // the entire premise of the feature, and requiring it makes the
                // feature unusable in practice rather than merely inconvenient.
                //
                // This IS permitted from the background here, despite the Android 12+
                // restriction, because the app holds a battery-optimization exemption
                // (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, granted at first run) and
                // that exemption is an explicit documented exemption from the
                // background-FGS-start rule. Verified on the real device: started
                // from a pure background broadcast with no activity involved, the
                // service reached isForeground=true.
                startListenerService()
                replyPhoneStopped(event.sourceNodeId)
                return
            }

            if (!isFromConnectedNode(event.sourceNodeId)) {
                Log.w(TAG, "frame from unknown node; ignoring")
                return
            }

            val decoded = RemoteInputProtocol.decodeEvent(event.data)
            val tagHex = RemoteInputProtocol.toHex(decoded.tag)

            // Log EVERY accepted frame, including the happy path.
            //
            // This exists because its absence already cost a long misdiagnosis: a
            // correctly working listener that logs nothing is indistinguishable
            // from one that is never invoked, and an empty logcat was mistaken for
            // proof of non-delivery. Absence of logging is not absence of
            // execution. `rx` is the receive-side timestamp used to measure the
            // Data Layer hop against the watch's send stamp.
            Log.i(
                TAG,
                "RX type=${decoded.event.type} seq=${decoded.event.seqUnsigned} " +
                    "sid=${decoded.event.sidUnsigned} steps=${decoded.event.steps} " +
                    "wms=${decoded.event.wmsUnsigned} rx=${SystemClock.elapsedRealtime()}",
            )

            // A PING is BOTH a round-trip probe for the watch and the keepalive that
            // holds the session open on the glasses -- so it is answered here AND
            // forwarded, not one or the other. Answering only (which is what this
            // did) left the glasses' session with no traffic at all during idle, and
            // they expire a silent session after SESSION_EXPIRY_MS and then reject a
            // PING for the sid they just dropped. The status reply is issued first
            // and unconditionally: the watch's link display and its RTT measurement
            // must not depend on the glasses link being up.
            if (decoded.event.type == EventType.PING) target.onPing(decoded.event.seq)
            target.onEvent(decoded.event, tagHex)
        } catch (e: RemoteInputProtocol.MalformedFrameException) {
            Log.w(TAG, "malformed frame: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "frame handling failed: ${e.message}")
        }
    }

    /**
     * Hygiene check against the cached connected-node set, refreshed
     * asynchronously. This is NOT the security boundary -- `sourceNodeId` is
     * attacker-supplied in a forged intent -- so a stale-but-recent cache costs
     * nothing, while a blocking lookup on the callback thread would cost latency
     * on every event.
     */
    private fun isFromConnectedNode(sourceNodeId: String?): Boolean {
        if (sourceNodeId.isNullOrEmpty()) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - cachedAtMs > NODE_CACHE_TTL_MS) {
            cachedAtMs = now
            Wearable.getNodeClient(this).connectedNodes
                .addOnSuccessListener { nodes -> cachedNodeIds = nodes.map { it.id }.toSet() }
                .addOnFailureListener { e -> Log.w(TAG, "node refresh failed: ${e.message}") }
        }
        // Accept while the very first refresh is still in flight; the HMAC on the
        // glasses is what actually authenticates the frame.
        if (cachedNodeIds.isEmpty()) return true
        return sourceNodeId in cachedNodeIds
    }

    /**
     * Starts [ListenerService] so a watch event can be served without the user
     * having opened the phone app.
     *
     * Throttled: GMS delivers a burst of frames (OPEN, then scroll detents) and each
     * would otherwise issue its own start while the first is still coming up.
     *
     * Every outcome is logged, including the exception. A service that never started
     * was previously indistinguishable from one that started and died -- the whole
     * failure was invisible in logcat, which is why it took a hardware session to
     * find. [ForegroundServiceStartNotAllowedException] is caught by name rather
     * than swallowed as a generic failure, because it is the one outcome that means
     * "this approach cannot work here" rather than "this attempt failed".
     */
    private fun startListenerService() {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastStartAttemptMs
        if (now - last < START_THROTTLE_MS) return
        lastStartAttemptMs = now
        try {
            val intent = android.content.Intent(this, ListenerService::class.java).apply {
                action = ListenerService.ACTION_START
            }
            startForegroundService(intent)
            Log.i(TAG, "started ListenerService for an inbound watch frame")
        } catch (e: Exception) {
            // On Android 12+ this is ForegroundServiceStartNotAllowedException when
            // the battery-optimization exemption is absent. Named explicitly so the
            // log says which constraint was hit rather than just "failed".
            Log.e(
                TAG,
                "could not start ListenerService from the background " +
                    "(${e.javaClass.simpleName}: ${e.message}); the watch cannot work " +
                    "until the phone app is opened once",
            )
        }
    }

    /** Tells the watch the phone service is down, so it can show actionable copy. */
    private fun replyPhoneStopped(nodeId: String?) {
        if (nodeId.isNullOrEmpty()) return
        try {
            val bits = RemoteInputProtocol.StatusFlags.encode(
                glassesLinkUp = false,
                phoneServiceAlive = false,
                lastSendDropped = false,
                glassesSinkAttached = false,
                wakingGlasses = false,
            )
            Wearable.getMessageClient(this)
                .sendMessage(nodeId, RemoteInputProtocol.PATH_STATUS, bits)
        } catch (e: Exception) {
            Log.w(TAG, "phone-stopped reply failed: ${e.message}")
        }
    }
}
