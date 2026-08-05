package com.repository.listener.wear

/**
 * The text a watch-face complication slot shows for each [LinkState].
 *
 * Deliberately pure Kotlin with no Android types so the whole mapping is unit
 * testable on the JVM. The complication service is a thin shell over this.
 *
 * Complication slots are tiny -- a SHORT_TEXT field on this watch renders roughly
 * seven glyphs before the platform ellipsises it, and an ellipsis in a status
 * readout is worse than a terser word. So the short copy is authored to fit
 * rather than truncated at runtime; [SHORT_TEXT_MAX] and [LONG_TEXT_MAX] are
 * enforced by test, not by a substring call that would silently produce
 * unreadable output.
 */
object ComplicationCopy {

    /** Hard budget for a SHORT_TEXT field. */
    const val SHORT_TEXT_MAX = 7

    /** Hard budget for a LONG_TEXT field on a round 1.5" display. */
    const val LONG_TEXT_MAX = 20

    /** Title shown alongside the short text where the slot renders one. */
    const val TITLE = "AR"

    /** Highest value of [healthRank]; the RANGED_VALUE upper bound. */
    const val HEALTH_MAX = 3f

    /**
     * Copy for "the link service is not running at all".
     *
     * A null [LinkState] is NOT the same as [LinkState.SETUP]. SETUP means the app
     * is running and waiting to be paired; null means nothing is running, so the
     * status shown is not merely stale, it is absent. Collapsing the two would
     * leave a dead service rendering "Pair" forever with no way for the user to
     * tell that tapping is the fix.
     */
    const val INACTIVE_SHORT = "Off"
    const val INACTIVE_LONG = "Remote not running"
    const val INACTIVE_DESCRIPTION = "Glasses Remote: not running, tap to start"

    /**
     * The at-a-glance word for a SHORT_TEXT slot. Never longer than
     * [SHORT_TEXT_MAX], never empty.
     */
    fun shortText(state: LinkState): String = when (state) {
        LinkState.SETUP -> "Pair"
        LinkState.UNPAIRED -> "No app"
        LinkState.PHONE_STOPPED -> "Stopped"
        LinkState.BT_OFF -> "BT off"
        LinkState.WAKING -> "Waking"
        LinkState.PHONE_ONLY -> "No AR"
        LinkState.GLASSES_BUSY -> "AR idle"
        LinkState.DEGRADED -> "Drops"
        LinkState.READY -> "Ready"
        LinkState.UNREACHABLE -> "No link"
    }

    /**
     * The fuller phrasing for a LONG_TEXT slot. Never longer than
     * [LONG_TEXT_MAX], never empty.
     *
     * [LinkState.label] is authored for the full-screen activity and overruns a
     * complication slot, so the long copy is a separate, shorter authoring of the
     * same meaning rather than a truncation of it.
     */
    fun longText(state: LinkState): String = when (state) {
        LinkState.SETUP -> "Open the phone app"
        LinkState.UNPAIRED -> "Phone app not found"
        LinkState.PHONE_STOPPED -> "Phone app stopped"
        LinkState.BT_OFF -> "Bluetooth is off"
        LinkState.WAKING -> "Waking glasses"
        LinkState.PHONE_ONLY -> "Glasses offline"
        LinkState.GLASSES_BUSY -> "Glasses not active"
        LinkState.DEGRADED -> "Dropping input"
        LinkState.READY -> "Glasses connected"
        LinkState.UNREACHABLE -> "Phone unreachable"
    }

    /**
     * Accessibility text. Spoken by TalkBack, so it is the one field allowed to be
     * a full sentence: it has no layout budget.
     */
    fun contentDescription(state: LinkState): String =
        "Glasses Remote: ${state.label}"

    /**
     * Link health as a 0..[HEALTH_MAX] rank for a RANGED_VALUE slot.
     *
     * Ranked by how much of the chain is up rather than by enum order, so the arc
     * grows monotonically as the link comes together: nothing, phone, glasses
     * reachable, glasses accepting input.
     */
    fun healthRank(state: LinkState): Float = when (state) {
        LinkState.BT_OFF,
        LinkState.SETUP,
        LinkState.UNPAIRED,
        LinkState.UNREACHABLE,
        -> 0f

        LinkState.PHONE_STOPPED,
        LinkState.PHONE_ONLY,
        -> 1f

        LinkState.WAKING,
        LinkState.GLASSES_BUSY,
        LinkState.DEGRADED,
        -> 2f

        LinkState.READY -> 3f
    }
}
