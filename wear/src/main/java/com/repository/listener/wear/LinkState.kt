package com.repository.listener.wear

import com.repository.listener.protocol.RemoteInputProtocol.StatusFlags

/**
 * What the watch shows the user about the link.
 *
 * Feedback is deliberately connection status only, plus a haptic tick per detent.
 * There is no echo of focused content back from the glasses.
 *
 * Ordering matters: [fromStatus] resolves the most specific actionable state
 * first, so the user is never told "phone unreachable" when the real problem is
 * that the glasses screen is not active.
 */
enum class LinkState(val label: String, val inputEnabled: Boolean) {

    /** First run: no phone node has ever been seen. */
    SETUP("Open the phone app to pair", false),

    /** No phone node advertising the input-sink capability. */
    UNPAIRED("Phone app not found", false),

    /**
     * The phone replied but its listener service is dead. Distinct from
     * UNREACHABLE so the copy is actionable: a MIUI kill is not a range problem.
     */
    PHONE_STOPPED("Phone app stopped - open it", false),

    /** Bluetooth is off on the watch itself, which the watch can see directly. */
    BT_OFF("Bluetooth is off", false),

    /** RFCOMM to the glasses is down and a wake is in flight. */
    WAKING("Waking glasses...", true),

    /** Phone reachable, glasses link down. */
    PHONE_ONLY("Glasses offline", false),

    /**
     * The link is up and the phone is healthy, but the glasses UI is not
     * foreground so events would be dropped. Without this the watch would show
     * READY while nothing happened.
     */
    GLASSES_BUSY("Glasses screen not active", false),

    /** Sends are being dropped. Warn, but keep sending. */
    DEGRADED("Dropping input", true),

    /** Normal operation. */
    READY("Connected", true),

    /** No status frame within the timeout. */
    UNREACHABLE("Phone unreachable", false);

    companion object {

        /**
         * Derives the state from the latest status bitfield.
         *
         * [bluetoothOn] and [phoneNodeKnown] are watch-local observations, which
         * take precedence over anything a status frame claims: the status path is
         * unauthenticated, so a forged frame must never be able to present a
         * healthier picture than the watch can see for itself.
         */
        fun fromStatus(
            bits: Int,
            bluetoothOn: Boolean,
            phoneNodeKnown: Boolean,
            everSawPhoneNode: Boolean,
            statusFresh: Boolean,
        ): LinkState {
            if (!bluetoothOn) return BT_OFF
            if (!everSawPhoneNode) return SETUP
            if (!phoneNodeKnown) return UNPAIRED
            if (!statusFresh) return UNREACHABLE

            if (!StatusFlags.isSet(bits, StatusFlags.PHONE_SERVICE_ALIVE)) return PHONE_STOPPED
            if (StatusFlags.isSet(bits, StatusFlags.WAKING_GLASSES)) return WAKING
            if (!StatusFlags.isSet(bits, StatusFlags.GLASSES_LINK_UP)) return PHONE_ONLY
            if (!StatusFlags.isSet(bits, StatusFlags.GLASSES_SINK_ATTACHED)) return GLASSES_BUSY
            if (StatusFlags.isSet(bits, StatusFlags.LAST_SEND_DROPPED)) return DEGRADED
            return READY
        }
    }
}
