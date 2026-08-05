package com.repository.listener.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.repository.listener.protocol.RemoteInputProtocol
import com.repository.listener.protocol.RemoteInputProtocol.EventType

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
 * This service must NOT call startForegroundService: Android 12+ forbids starting
 * a foreground service from the background, and GMS binding us grants no
 * exemption.
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
                replyPhoneStopped(event.sourceNodeId)
                return
            }

            if (!isFromConnectedNode(event.sourceNodeId)) {
                Log.w(TAG, "frame from unknown node; ignoring")
                return
            }

            val decoded = RemoteInputProtocol.decodeEvent(event.data)
            val tagHex = RemoteInputProtocol.toHex(decoded.tag)

            when (decoded.event.type) {
                EventType.PING -> target.onPing()
                else -> target.onEvent(decoded.event, tagHex)
            }
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
