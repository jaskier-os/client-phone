package com.repository.listener.wear

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens for the remote screen.
 *
 * The product is a signal link to an instrument the user is already looking
 * through, so the palette is taken from lit instrumentation rather than from a
 * generic app: an unlit panel, a live carrier trace, a working lamp, a fault
 * lamp. Everything is one hue family per role and differs only in lightness, so
 * nothing on a 1.5" round OLED competes for attention.
 *
 * Pure black is not a stylistic default here: on this AMOLED an unlit pixel draws
 * no current, and the screen is held awake during a scroll.
 */
object WatchTokens {

    /** Unlit panel. Every pixel not carrying meaning is this. */
    val Panel = Color(0xFF000000)

    /** The unlit rim track. Present so the lit arc reads as travel along a dial. */
    val RimTrack = Color(0xFF232A2E)

    /** Live carrier. The link is up and input is being accepted. */
    val Carrier = Color(0xFF4DD9E6)

    /** Working. Transient, self-resolving, input may or may not land yet. */
    val Working = Color(0xFFE8A33D)

    /** Fault. Something is blocking input and the user must act. */
    val Fault = Color(0xFFE06C75)

    /**
     * Text hierarchy: the glance line, the actionable line, and metadata. Three
     * levels, all neutral so that colour is never doing the work of meaning.
     */
    val InkPrimary = Color(0xFFF2F5F6)
    val InkSecondary = Color(0xFF9AA5AA)
    val InkMuted = Color(0xFF5C666B)

    /** Rim geometry. The track sits just inside the physical bezel. */
    val RimInset = 5.dp
    val RimStroke = 4.dp

    /** Tap/refusal wave ring weight. Lighter than the rim so it reads as air. */
    val WaveStroke = 2.5.dp

    /** Radial inset for centre content, keeping it clear of the circular edge. */
    val ContentInset = 30.dp
}

/**
 * How a [LinkState] should be coloured and, more importantly, what it means.
 *
 * Colour never carries the meaning alone -- every severity also has distinct copy
 * in [LinkState.title]/[LinkState.hint] and a distinct rim behaviour, so the
 * screen is readable with no colour perception at all.
 */
enum class LinkSeverity(val tint: Color) {

    /** Input is accepted right now. */
    LIVE(WatchTokens.Carrier),

    /** The system is doing something about it; waiting is a valid response. */
    WORKING(WatchTokens.Working),

    /** Input cannot land until the user does something. */
    BLOCKED(WatchTokens.Fault),
}
