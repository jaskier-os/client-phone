package com.repository.listener.wear

import android.os.SystemClock
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import kotlin.math.min

/**
 * Receives one raw rotary delta.
 *
 * A `fun interface` taking a primitive Float rather than a `(Float) -> Unit`:
 * Kotlin's function types are generic over their parameters, so a lambda would
 * box a `java.lang.Float` on every invocation. That is one allocation per detent,
 * up to ~31 a second, on the one path in this app that must stay free of garbage.
 */
fun interface RotaryDeltaSink {
    fun onDelta(delta: Float)
}

/**
 * The remote screen.
 *
 * ## Structure
 *
 * The screen is a dial, not a page. The rim is the instrument: it carries the
 * link state as a quiet track and the user's own scrolling as a lit arc that
 * mirrors the bezel 1:1. The centre carries only what has to be read, in two
 * lines, because the user glances at this for a fraction of a second while
 * wearing AR glasses.
 *
 * Nothing is drawn in the corners, and no content sits outside the circular safe
 * area, because on a round display the corners do not exist.
 *
 * ## Why the feedback is drawn, not composed
 *
 * All of the motion lives in [drawBehind], reading a plain non-snapshot
 * [FeedbackEngine] and gated on a single frame counter. That keeps recomposition,
 * measure and layout entirely off the input path: a bezel spin at ~31 detents a
 * second does no Compose work beyond one draw invalidation per frame, which the
 * display was going to produce anyway.
 */
@Composable
fun RemoteScreen(
    state: State<LinkState>,
    elapsedLabel: State<String>,
    engine: FeedbackEngine,
    onRotaryDelta: RotaryDeltaSink,
    onTap: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val view = LocalView.current
    val current by state

    // The tap lambda below lives inside a pointerInput keyed on Unit, so it is
    // created once and never recreated. Capturing `current` directly would pin it
    // to the state the screen opened in for the rest of the session; the State
    // object is read through instead, so the tap always sees the truth.
    val liveState = state

    // The ONLY snapshot state the animation touches. Two counters, both written
    // exclusively OUTSIDE the steady-state input path:
    //  - `frame` is bumped once per rendered frame by the loop below, and read in
    //    drawBehind, so a frame tick repaints without recomposing anything.
    //  - `wake` is bumped by an input callback ONLY when the animation is idle
    //    and must be restarted. During a spin the loop is already running, so a
    //    detent writes no snapshot state at all.
    val frame = remember { mutableIntStateOf(0) }
    val wake = remember { mutableIntStateOf(0) }

    // onRotaryScrollEvent delivers nothing unless the composable is focusable AND
    // actually holds focus, so focus is claimed here -- and RE-claimed whenever
    // it is lost. Requesting once on first composition is not enough: the
    // requester can still be unattached that early, and anything that takes focus
    // later (a system dialog, returning from wrist-down) would otherwise leave
    // the bezel silently dead for the rest of the session with no visible cause.
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (!focused) {
            // Yield first so the modifier chain is attached before the request;
            // an unattached requester throws rather than deferring.
            withFrameMillis { }
            runCatching { focusRequester.requestFocus() }
        }
    }

    // One frame loop for every animation on the screen. It pumps only while the
    // engine reports something visible, then RETURNS -- it does not spin, does
    // not poll and does not hold a timer. An untouched screen therefore requests
    // zero frames and burns zero CPU, which matters because the activity holds
    // the screen on while the user is scrolling. Reading `wake` at the top is
    // what re-launches it on the next input after an idle period.
    LaunchedEffect(wake.intValue) {
        try {
            do {
                engine.markRunning(true)
                var alive = true
                while (alive) {
                    withFrameMillis { nowMs ->
                        alive = engine.advance(nowMs)
                        frame.intValue++
                    }
                }
                // Clearing `running` and re-checking must happen together, before
                // the loop can be left. withFrameMillis resumes on a dispatch, so
                // between the last frame and this point there is a main-loop gap
                // in which a detent can land; if that detent saw running == true
                // it wrote no wake, and exiting here would strand a lit arc with
                // nothing pumping it. Re-testing after clearing the flag closes
                // the window: either the detent already requested a wake, or we
                // observe its energy right here and keep going.
                engine.markRunning(false)
            } while (engine.needsWake())
        } finally {
            engine.markRunning(false)
        }
    }

    val tint = current.severity.tint

    // Stroke is a real object, so building one per arc per frame would be three
    // allocations every frame -- ~270 a second at 90 Hz, on a screen that is held
    // awake precisely while the user is scrolling. They depend only on density,
    // so they are built once.
    val density = LocalDensity.current
    val rimStroke = remember(density) {
        Stroke(width = with(density) { WatchTokens.RimStroke.toPx() })
    }
    val waveStroke = remember(density) {
        Stroke(width = with(density) { WatchTokens.WaveStroke.toPx() })
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WatchTokens.Panel)
            // EXACTLY ONE dispatch path is hooked. Rotary events reach all four of
            // Activity.dispatchGenericMotionEvent, Activity.onGenericMotionEvent,
            // View.OnGenericMotionListener and View.onGenericMotionEvent --
            // hooking more than one counts every detent multiple times.
            .onRotaryScrollEvent { event ->
                // ORDER IS LOAD BEARING. The send goes first, unconditionally and
                // with nothing before it that can block: no logging, no snapshot
                // write, no allocation. Feedback is rendered optimistically
                // afterwards and can never delay or drop an input event.
                val raw = rawAxisScroll(view, event.verticalScrollPixels)
                onRotaryDelta.onDelta(raw)

                val now = SystemClock.uptimeMillis()
                // A detent keeps its plain tick even while the glasses are
                // refusing: the tick reports MOVEMENT of the dial, which is true
                // regardless, and swapping 31 ticks a second for 31 rejections
                // would bury the one refusal buzz that carries the information.
                // The refusal is surfaced on the tap and on the screen instead.
                if (liveState.value.inputEnabled) {
                    Haptics.detent(view)
                } else if (Haptics.refused(view)) {
                    // A bezel refusal has no touch point, so it collapses from
                    // the rim toward the middle of the screen.
                    engine.onWaveAtCenter(now, refusal = true)
                }
                engine.onDetent(raw, now)
                // Only pays the cost of a snapshot write when the animation was
                // idle. Mid-spin this is false on every detent but the first.
                if (engine.needsWake()) wake.intValue++
                true
            }
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            // One raw physical tap, handed to the local gesture recogniser. Single
            // vs double is resolved here, on the watch, against a 400 ms threshold,
            // and leaves as a semantic SELECT or BACK. Recognising locally costs no
            // network time: deferring on the receiver instead would make a single
            // tap wait out the threshold plus the link RTT before anything happens.
            // The phone relays the action verbatim; the glasses interpret it.
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { position ->
                        // The tap is ALWAYS sent, in every state. Whether the
                        // glasses can act on it is their call, not the watch's;
                        // suppressing it here would make the watch lie about a
                        // state that may already have changed.
                        onTap()

                        val now = SystemClock.uptimeMillis()
                        // liveState, not `current`: pointerInput is keyed on Unit
                        // so this lambda is created once and never recreated, and
                        // reading the captured `current` would pin the feedback to
                        // whatever state the screen opened in -- forever.
                        // Three outcomes, three distinct sensations. The glasses
                        // now report refusals, so the watch can finally tell
                        // "this will land" from "this was declined" from "sent,
                        // arrival unknown" -- and says exactly which, no more.
                        val s = liveState.value
                        when {
                            // Checked first: a refusal is the one thing the user
                            // most needs to feel, and it outranks the fact that
                            // the watch is still willing to send.
                            s.isRefusal -> if (Haptics.refused(view)) {
                                engine.onWave(now, position.x, position.y, refusal = true)
                            }

                            s.inputConfirmed -> {
                                Haptics.accepted(view)
                                engine.onWave(now, position.x, position.y, refusal = false)
                            }

                            // Sending, but with no proof of arrival (WAKING,
                            // DEGRADED). The weaker tick claims only delivery.
                            s.inputEnabled -> {
                                Haptics.dispatched(view)
                                engine.onWave(now, position.x, position.y, refusal = false)
                            }

                            else -> if (Haptics.refused(view)) {
                                engine.onWave(now, position.x, position.y, refusal = true)
                            }
                        }
                        if (engine.needsWake()) wake.intValue++
                    },
                )
            }
            .drawBehind {
                // Subscribing the draw to the frame counter. Reading it here is
                // what makes a frame tick repaint WITHOUT recomposing anything --
                // draw is snapshot-observed independently of composition. The
                // comparison is here so the read is an ordinary use of the value
                // and cannot be mistaken for a discardable expression.
                if (frame.intValue < 0) return@drawBehind
                drawRimTrack(rimStroke)
                drawScrollArc(engine, tint, rimStroke)
                drawWaves(engine, tint, waveStroke)
            }
            .semantics {
                contentDescription = ComplicationCopy.contentDescription(current)
            },
        contentAlignment = Alignment.Center,
    ) {
        StatusBlock(current, elapsedLabel)
    }
}

/**
 * The two readable lines.
 *
 * Type does the hierarchy, not colour: the title is heavier and larger than the
 * hint by enough to be told apart at a glance and in monochrome. Colour is only
 * ever a second, redundant signal -- the words alone say which of the three
 * severities this is.
 */
@Composable
private fun StatusBlock(current: LinkState, elapsedLabel: State<String>) {
    val elapsed by elapsedLabel
    Column(
        modifier = Modifier.padding(horizontal = WatchTokens.ContentInset),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = current.title,
            textAlign = TextAlign.Center,
            fontSize = 19.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
            color = WatchTokens.InkPrimary,
        )
        if (current.hint.isNotEmpty()) {
            Text(
                text = current.hint,
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                color = WatchTokens.InkSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        // Only present for states that can hang. A transient with no visible
        // duration is indistinguishable from a stuck one, which is exactly the
        // failure that made "Waking glasses..." untrustworthy.
        if (current.showsElapsed && elapsed.isNotEmpty()) {
            Text(
                text = elapsed,
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = WatchTokens.InkMuted,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

/** The unlit dial track, so the lit arc reads as travel rather than as a blob. */
private fun DrawScope.drawRimTrack(stroke: Stroke) {
    val inset = WatchTokens.RimInset.toPx() + WatchTokens.RimStroke.toPx() / 2f
    val d = min(size.width, size.height) - inset * 2f
    drawArc(
        color = WatchTokens.RimTrack,
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = Size(d, d),
        style = stroke,
    )
}

/**
 * The scroll arc: one continuous head that sweeps the rim as the bezel turns.
 *
 * Direction is legible from the motion itself, not from a decoration -- the head
 * travels the way the finger travels, at exactly 6 degrees per detent, so a
 * clockwise and a counter-clockwise detent are never confusable. The arc is
 * additionally drawn AHEAD of the head in the direction of travel, giving it a
 * comet shape whose blunt end points where the user came from.
 *
 * Peak alpha is capped well below full: this sits on an OLED against pure black
 * a few centimetres from the eye, and full-luminance white would be unpleasant
 * rather than informative.
 */
private fun DrawScope.drawScrollArc(engine: FeedbackEngine, tint: Color, stroke: Stroke) {
    val charge = engine.charge
    if (charge <= 0f) return

    val inset = WatchTokens.RimInset.toPx() + WatchTokens.RimStroke.toPx() / 2f
    val d = min(size.width, size.height) - inset * 2f

    // Sweep grows with charge, so a single detent is a short mark and a spin is a
    // long confident streak.
    val sweep = (18f + 40f * charge) * if (engine.direction < 0) -1f else 1f
    // -90 puts zero at the top of the dial, where the eye lands first.
    val start = -90f + engine.headDegrees

    drawArc(
        color = tint.copy(alpha = (0.15f + 0.45f * charge).coerceAtMost(0.60f)),
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = Size(d, d),
        style = stroke,
    )
}

/**
 * The tap waves: rings expanding from the points actually touched.
 *
 * EVERY live wave in the pool is drawn in the same frame, each at its own phase,
 * so two quick taps read as two independent ripples rather than as one that
 * restarted. Nothing here couples the waves to one another.
 *
 * Expansion eases out and alpha falls faster than the radius grows, so a wave
 * reads as a release of energy rather than as a growing shape. A refusal wave
 * instead CONTRACTS -- it starts wide and collapses inward, the opposite gesture,
 * so a dispatched and a refused tap are told apart by motion alone with no colour
 * and no text.
 */
private fun DrawScope.drawWaves(engine: FeedbackEngine, tint: Color, stroke: Stroke) {
    val maxRadius = min(size.width, size.height) / 2f - WatchTokens.RimInset.toPx()
    val cx = size.width / 2f
    val cy = size.height / 2f

    // Indexed loop over the pool: no iterator and no boxing, so drawing a full
    // pool allocates nothing per frame on a screen held awake while in use.
    for (i in 0 until FeedbackEngine.MAX_WAVES) {
        val p = engine.waveProgressAt(i)
        if (p < 0f) continue

        val eased = 1f - (1f - p) * (1f - p)
        val radius: Float
        val color: Color
        if (engine.waveIsRefusalAt(i)) {
            radius = maxRadius * (1f - 0.55f * eased)
            color = WatchTokens.Fault
        } else {
            radius = maxRadius * (0.10f + 0.90f * eased)
            color = tint
        }

        drawCircle(
            color = color.copy(alpha = 0.55f * (1f - p) * (1f - p)),
            radius = radius,
            center = Offset(
                engine.waveOriginXAt(i, cx),
                engine.waveOriginYAt(i, cy),
            ),
            style = stroke,
        )
    }
}

/**
 * Recovers the RAW `AXIS_SCROLL` value from Compose's rotary event.
 *
 * Compose does not hand through the raw axis. `RotaryScrollEvent` is built as
 * `-getAxisValue(AXIS_SCROLL) * ViewConfiguration.scaledVerticalScrollFactor`, so
 * the value is (a) negated and (b) multiplied by a device-dependent pixel factor
 * that is roughly a list-item height -- on this watch (340 dpi) around two orders
 * of magnitude. Feeding that straight into an accumulator calibrated at 1.0 units
 * per detent would turn one detent into hundreds of steps: a single flick would
 * scroll the glasses for many seconds, and in the wrong direction.
 *
 * Dividing the factor back out restores the normalized -1..+1 per detent that the
 * hardware actually reports (verified via `dumpsys input`: SCROLL min=-1 max=+1),
 * and re-negating restores the sign convention where positive means forward/down.
 *
 * The factor is queried per event rather than cached because it is
 * configuration-dependent and this is a single cheap field read. This function is
 * allocation free and does no logging: it runs on the input path at up to ~31 Hz
 * and sits between the physical detent and the send.
 */
internal fun rawAxisScroll(view: View, verticalScrollPixels: Float): Float {
    val factor = ViewConfiguration.get(view.context).scaledVerticalScrollFactor
    return if (factor <= 0f) -verticalScrollPixels else -verticalScrollPixels / factor.toFloat()
}
