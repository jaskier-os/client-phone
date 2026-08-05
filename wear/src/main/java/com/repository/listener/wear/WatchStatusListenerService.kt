package com.repository.listener.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.repository.listener.protocol.RemoteInputProtocol

/**
 * Receives the phone's status backchannel.
 *
 * The status frame is advisory and unauthenticated by design: the HMAC key lives
 * on the watch and the glasses, deliberately not on the phone, so the phone cannot
 * sign anything. Containment is that [WatchLinkService.onStatus] folds it in
 * through applyAdvisory, which only ever lets a frame make the watch more
 * pessimistic -- it can never assert health over a locally observed failure, nor
 * cause the watch to send more.
 */
class WatchStatusListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WatchStatus"
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != RemoteInputProtocol.PATH_STATUS) return
        try {
            val bits = RemoteInputProtocol.StatusFlags.decode(event.data)
            WatchLinkService.current()?.onStatus(bits)
        } catch (e: Exception) {
            // onMessageReceived runs on a Binder thread; an uncaught throw here
            // would take down the service on a single malformed frame.
            Log.w(TAG, "bad status frame: ${e.message}")
        }
    }
}
