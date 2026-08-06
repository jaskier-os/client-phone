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
        LinkState.PHONE_STOPPED -> "No svc"
        LinkState.BT_OFF -> "BT off"
        LinkState.WAKING -> "Waking"
        LinkState.PHONE_ONLY -> "No AR"
        LinkState.GLASSES_BUSY -> "AR busy"
        LinkState.DEGRADED -> "Losing"
        // Each refusal keeps its own word even at a seven-glyph budget. Collapsing
        // them to a shared "Refused" was tried and is wrong: the complication is
        // the surface the user reads WITHOUT opening the app, so it is the one
        // place the reason matters most, and several states rendering identically
        // is the same indistinguishability the refusal signal exists to remove.
        LinkState.REFUSED_FOLDED -> "Folded"
        LinkState.REFUSED_LOCKED -> "In use"
        LinkState.REFUSED -> "Refused"
        LinkState.READY -> "Ready"
        LinkState.UNREACHABLE -> "No link"
    }

    /**
     * The fuller phrasing for a LONG_TEXT slot. Never longer than
     * [LONG_TEXT_MAX], never empty.
     *
     * [LinkState.title] is the on-screen glance line and some titles still overrun
     * a complication slot, so the long copy is a separate authoring of the same
     * meaning rather than a truncation of it. It must never CONTRADICT the title:
     * the complication and the app describing one state as two different things
     * is worse than either wording alone.
     */
    fun longText(state: LinkState): String = when (state) {
        LinkState.SETUP -> "Not paired yet"
        LinkState.UNPAIRED -> "No phone app"
        LinkState.PHONE_STOPPED -> "Phone service down"
        LinkState.BT_OFF -> "Bluetooth is off"
        LinkState.WAKING -> "Waking glasses"
        LinkState.PHONE_ONLY -> "Glasses offline"
        LinkState.GLASSES_BUSY -> "Glasses busy"
        LinkState.DEGRADED -> "Losing input"
        LinkState.REFUSED_FOLDED -> "Glasses folded"
        // Not "Glasses busy": GLASSES_BUSY already owns that phrasing and the two
        // are different problems -- that one is a display that is not listening,
        // this one is a display doing something the user must finish.
        LinkState.REFUSED_LOCKED -> "Glasses in use"
        LinkState.REFUSED -> "Input declined"
        LinkState.READY -> "Glasses connected"
        LinkState.UNREACHABLE -> "Phone unreachable"
    }

    /**
     * Accessibility text. Spoken by TalkBack, so it is the one field allowed to be
     * a full sentence: it has no layout budget.
     */
    fun contentDescription(state: LinkState): String =
        "Glasses Remote: ${state.title}. ${state.hint}"

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
        // Rank 2, not 3. The whole chain IS up for a refusal, so by pure link
        // health these would sit alongside READY -- but the arc is read as "can I
        // use this right now", and a full arc while every tap is being declined
        // is exactly the false reassurance the refusal signal exists to end.
        LinkState.REFUSED_FOLDED,
        LinkState.REFUSED_LOCKED,
        LinkState.REFUSED,
        -> 2f

        LinkState.READY -> 3f
    }
}
