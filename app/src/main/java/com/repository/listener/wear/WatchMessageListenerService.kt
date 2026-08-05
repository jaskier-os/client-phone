package com.repository.listener.wear

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.repository.listener.protocol.RemoteInputProtocol
import com.repository.listener.protocol.RemoteInputProtocol.EventType
import java.util.concurrent.TimeUnit

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
        private const val NODE_LOOKUP_TIMEOUT_MS = 2000L

        /** Set by ListenerService while it is alive. */
        @Volatile
        var bridge: WatchInputBridge? = null
    }

    override fun onMessageReceived(event: MessageEvent) {
        // Everything here is wrapped: onMessageReceived runs on a Binder thread
        // and an uncaught throw would take down the process on one bad frame.
        try {
            if (!event.path.startsWith(RemoteInputProtocol.PATH_PREFIX)) return

            if (!isFromConnectedNode(event.sourceNodeId)) {
                Log.w(TAG, "frame from unknown node; ignoring")
                return
            }

            val target = bridge
            if (target == null) {
                // ListenerService is not running, so there is no RFCOMM client to
                // forward onto. Report it rather than dropping silently, so the
                // watch can show actionable copy instead of a generic failure.
                Log.w(TAG, "no bridge; ListenerService not running")
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

    private fun isFromConnectedNode(sourceNodeId: String?): Boolean {
        if (sourceNodeId.isNullOrEmpty()) return false
        return try {
            val nodes = Tasks.await(
                Wearable.getNodeClient(this).connectedNodes,
                NODE_LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS,
            )
            nodes.any { it.id == sourceNodeId }
        } catch (e: Exception) {
            Log.w(TAG, "node lookup failed: ${e.message}")
            false
        }
    }
}
