package com.repository.listener.wear

import com.repository.listener.protocol.RemoteInputProtocol
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
 *
 * ## The rule every entry here obeys
 *
 * A state may never assert more than its status bits actually prove. This is not
 * a style preference: the display is the only explanation the user ever gets for
 * why an input did nothing, and it has already lost their trust once by claiming
 * things that were not true. Concretely, [title] names what is observed, [hint]
 * proposes only actions that could plausibly help, and [severity] LIVE is
 * reserved for states where input genuinely lands.
 *
 * ## Adding a state
 *
 * The set is expected to grow -- states for "the glasses are refusing input" and
 * "the glasses are folded" are known gaps that need bits which do not exist on
 * the wire yet. Nothing downstream enumerates these entries positionally: the
 * screen renders whatever [title]/[hint]/[severity] say, and ComplicationCopy's
 * `when` blocks are exhaustive, so the compiler names every site a new entry
 * must be handled. Adding one means adding its copy and nothing else.
 *
 * Until such a bit arrives, the honest behaviour is the current one: no state
 * claims the glasses are accepting input, because the watch cannot see that.
 */
enum class LinkState(
    /**
     * The glanceable line. Names what IS, in the fewest true words, and never
     * asserts more than the status bits actually prove.
     */
    val title: String,
    /**
     * The line under it. Where the user can do something, this is the thing to
     * do; where they cannot, it says what has to happen instead. Empty only
     * where there is genuinely nothing more honest to add.
     */
    val hint: String,
    val severity: LinkSeverity,
    val inputEnabled: Boolean,
    /**
     * True where the state is a transient the system is actively working
     * through, so the screen shows how long it has been in it. A state that can
     * hang must never be able to look identical at 1 second and at 5 minutes --
     * an unqualified "Waking glasses" that never changes is a lie of omission.
     */
    val showsElapsed: Boolean = false,
    /**
     * True only where the watch has POSITIVE evidence that input is landing.
     *
     * Deliberately distinct from [inputEnabled], which says only that the watch
     * is willing to SEND. [WAKING] and [DEGRADED] both send, but one is queuing
     * behind a wake and the other is watching sends drop, so neither may claim
     * that anything arrived.
     *
     * This is what earns the confirming tap haptic, and it became knowable only
     * once the glasses began reporting refusals. Before that, "the link is up"
     * was the strongest signal available and it was not strong enough: the
     * glasses were refusing input constantly while the link was perfectly
     * healthy, so a link-driven success buzz would have been lying on nearly
     * every tap.
     */
    val inputConfirmed: Boolean = false,
    /**
     * True where the glasses themselves declined input, as opposed to anything
     * being wrong with the link.
     *
     * Kept as its own flag rather than inferred from [severity] or [inputEnabled]
     * so the tap handler can tell a refusal apart from the other reasons a state
     * might not be confirmed -- WAKING and DEGRADED also send without proof of
     * arrival, but nothing has refused anything, and buzzing REJECT at the user
     * for those would be the same category of lie in the opposite direction.
     */
    val isRefusal: Boolean = false,
) {

    /** First run: no phone node has ever been seen. */
    SETUP(
        title = "Not paired",
        hint = "Open the phone app once",
        severity = LinkSeverity.BLOCKED,
        inputEnabled = false,
    ),

    /** No phone node advertising the input-sink capability. */
    /**
     * The hint does NOT say "install the app". The bit that produces this state
     * only proves that no node is currently advertising the capability, which is
     * far more often a cold start or the phone being out of range than a missing
     * install -- and telling a user to install software they already have is the
     * kind of confidently wrong instruction that makes them stop believing the
     * screen. The copy names the two likely causes and asserts neither.
     */
    UNPAIRED(
        title = "No phone app",
        hint = "Out of range, or not started",
        severity = LinkSeverity.BLOCKED,
        inputEnabled = false,
    ),

    /**
     * The phone replied but its listener service is dead. Distinct from
     * UNREACHABLE so the copy is actionable: a MIUI kill is not a range problem.
     *
     * Phrased as what was reported rather than as a verdict on the whole app:
     * the phone answered, so something over there is running, and claiming the
     * app "stopped" when only the service died reads as a lie to a user who can
     * see the app on screen.
     */
    PHONE_STOPPED(
        title = "Phone service down",
        hint = "Reopen the app on your phone",
        severity = LinkSeverity.BLOCKED,
        inputEnabled = false,
    ),

    /** Bluetooth is off on the watch itself, which the watch can see directly. */
    BT_OFF(
        title = "Bluetooth off",
        hint = "Turn Bluetooth on here",
        severity = LinkSeverity.BLOCKED,
        inputEnabled = false,
    ),

    /**
     * RFCOMM to the glasses is down and a wake is in flight. Input is accepted
     * and queued, so the hint says so rather than implying it is being lost.
     */
    WAKING(
        title = "Waking glasses",
        hint = "Input is queued meanwhile",
        severity = LinkSeverity.WORKING,
        inputEnabled = true,
        showsElapsed = true,
    ),

    /** Phone reachable, glasses link down. */
    PHONE_ONLY(
        title = "Glasses offline",
        hint = "Put them on, or check charge",
        severity = LinkSeverity.BLOCKED,
        inputEnabled = false,
    ),

    /**
     * The link is up and the phone is healthy, but the glasses UI is not
     * foreground so events would be dropped. Without this the watch would show
     * READY while nothing happened.
     */
    /**
     * The hint leads with the overwhelmingly common cause. The bit says only
     * "no sink is attached", and while an unusual foreground app can produce
     * that, in practice it is nearly always the glasses display being off. Copy
     * that names the rare cause first sends the user hunting through the glasses
     * UI for a problem that a single wake would have fixed.
     */
    GLASSES_BUSY(
        title = "Not listening",
        hint = "Wake the glasses display",
        severity = LinkSeverity.BLOCKED,
        inputEnabled = false,
    ),

    /**
     * Sends are being dropped. Warn, but keep sending.
     *
     * The hint does NOT say "move closer". This state is raised by a send being
     * dropped, and the most common source of that is a queue overflow on the
     * phone -- a throughput problem to which distance is simply irrelevant.
     * Advice that cannot work is worse than no advice, so the copy states what
     * is happening and what the user can actually do about it: slow down.
     */
    DEGRADED(
        title = "Losing input",
        hint = "Still sending. Turn slower",
        severity = LinkSeverity.WORKING,
        inputEnabled = true,
    ),

    /**
     * The link is healthy and the glasses are DECLINING input anyway.
     *
     * These three are the only states that describe the far end's UI rather than
     * the link, and they exist because the link being perfect explains nothing to
     * a user whose taps are doing nothing. Each carries the reason the glasses
     * gave, because "it is not working" and "you cannot do that HERE" send the
     * user to completely different actions.
     *
     * Input stays ENABLED: the refusal describes the glasses' state a moment ago,
     * the user is very likely about to move somewhere input is accepted, and
     * gating sends on a stale refusal would remove the very gesture -- back --
     * that gets them out. The watch reports the refusal; it does not enforce it.
     */
    REFUSED_HERE(
        title = "Not allowed here",
        hint = "Double tap to go back",
        severity = LinkSeverity.WORKING,
        inputEnabled = true,
        isRefusal = true,
    ),

    /**
     * The glasses are folded, so nothing can be displayed to act on.
     *
     * WORKING rather than BLOCKED, despite reading like a hard stop. BLOCKED is
     * defined as "the watch is not sending", and this state does send -- the user
     * unfolds the glasses and the very next detent must land, so gating input
     * behind a refusal that is already stale would be the bug, not the fix. The
     * severity describes what the WATCH is doing; the copy describes what the
     * glasses need.
     */
    REFUSED_FOLDED(
        title = "Glasses folded",
        hint = "Unfold them to continue",
        severity = LinkSeverity.WORKING,
        inputEnabled = true,
        isRefusal = true,
    ),

    /** A call, recording or reply owns the glasses UI. */
    REFUSED_LOCKED(
        title = "Glasses busy",
        hint = "Finish what is on screen first",
        severity = LinkSeverity.WORKING,
        inputEnabled = true,
        isRefusal = true,
    ),

    /**
     * A refusal whose reason the watch does not recognise.
     *
     * Reached when the glasses send a reason code newer than this build knows.
     * It says only what is certain -- the input was declined -- rather than
     * guessing a cause, and it exists so a future reason degrades to something
     * true instead of silently rendering as READY.
     */
    REFUSED(
        title = "Input declined",
        hint = "The glasses refused that",
        severity = LinkSeverity.WORKING,
        inputEnabled = true,
        isRefusal = true,
    ),

    /**
     * Normal operation, and the ONLY state that positively confirms input lands:
     * the link is healthy AND the glasses are not reporting a refusal.
     */
    READY(
        title = "Connected",
        hint = "Turn to scroll, tap to select",
        severity = LinkSeverity.LIVE,
        inputEnabled = true,
        inputConfirmed = true,
    ),

    /** No status frame within the timeout. */
    UNREACHABLE(
        title = "Phone unreachable",
        hint = "Move closer to your phone",
        severity = LinkSeverity.BLOCKED,
        inputEnabled = false,
    );

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

            // Checked LAST among the problems, and above READY.
            //
            // Ordered here because a refusal describes the far end's UI, not the
            // link, so it is only meaningful once the whole chain is known good:
            // reporting "not allowed here" while the glasses are actually offline
            // would send the user to fix the wrong thing. Above READY because
            // this is precisely the case that used to render as "Connected" while
            // nothing the user did had any effect.
            if (StatusFlags.isSet(bits, StatusFlags.GLASSES_REFUSING_INPUT)) {
                return when (StatusFlags.decodeReason(bits)) {
                    RemoteInputProtocol.RefusalReason.NOT_ALLOWED -> REFUSED_HERE
                    RemoteInputProtocol.RefusalReason.FOLDED -> REFUSED_FOLDED
                    RemoteInputProtocol.RefusalReason.LOCKED -> REFUSED_LOCKED
                    // A reason this build does not know, or none supplied at all.
                    // Degrade to the honest generic rather than guessing.
                    null -> REFUSED
                }
            }
            return READY
        }
    }
}
