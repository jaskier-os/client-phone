package com.repository.listener.wear

import android.os.SystemClock
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import com.repository.listener.protocol.RemoteInputProtocol
import kotlinx.coroutines.withTimeoutOrNull
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
 * Fraction of the display radius that accepts taps and holds.
 *
 * The remaining outer ring belongs to the rotary bezel. Turning the bezel presses the
 * glass, so without a dead zone every scroll also opened a press that matured into a
 * HOLD -- scrolling and holding were the same gesture and only one of them could win.
 *
 * 0.7 leaves a ring roughly a finger-width wide on this 1.5-inch display while keeping
 * the central target comfortably larger than a fingertip.
 */
private const val TAP_ZONE_FRACTION = 0.7f

/** Centre of a capture button in pixels, for both drawing and hit testing. */
internal fun captureButtonCentre(
    angleDeg: Float,
    width: Float,
    height: Float,
): Pair<Float, Float> {
    val cx = width / 2f
    val cy = height / 2f
    val orbit = kotlin.math.min(width, height) / 2f * WatchTokens.CaptureOrbit
    val rad = Math.toRadians(angleDeg.toDouble())
    // Zero at 12 o'clock, growing clockwise, so the constants read like a clock face.
    return (cx + orbit * kotlin.math.sin(rad)).toFloat() to
        (cy - orbit * kotlin.math.cos(rad)).toFloat()
}

/**
 * Which capture button a touch landed on, or null for anywhere else.
 *
 * The hit radius is deliberately larger than the drawn one: a 13dp circle is smaller than
 * a fingertip, and the surrounding rim is space the user does not scroll on anyway, so
 * spending it on a forgiving target costs nothing and prevents missed presses.
 */
internal fun captureButtonAt(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    hitRadiusPx: Float,
): RemoteInputProtocol.EventType? {
    fun hits(angle: Float): Boolean {
        val (bx, by) = captureButtonCentre(angle, width, height)
        val dx = x - bx
        val dy = y - by
        return dx * dx + dy * dy <= hitRadiusPx * hitRadiusPx
    }
    return when {
        hits(WatchTokens.CapturePhotoAngle) -> RemoteInputProtocol.EventType.PHOTO
        hits(WatchTokens.CaptureVideoAngle) -> RemoteInputProtocol.EventType.VIDEO
        else -> null
    }
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
    onHold: () -> Unit,
    holdThresholdMs: () -> Int,
    onCapture: (RemoteInputProtocol.EventType) -> Unit,
    /** True while the glasses report a recording in progress. */
    recording: State<Boolean>,
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

    // Which capture button is showing press feedback, and when it was pressed. Plain
    // mutable state read from drawBehind, like the frame counter -- written once per
    // press rather than per frame, so this is not on any hot path.
    val pressedButton = remember { mutableStateOf<RemoteInputProtocol.EventType?>(null) }
    val pressedAtMs = remember { androidx.compose.runtime.mutableLongStateOf(0L) }

    val density = LocalDensity.current
    // Generous enough to catch a fingertip; see captureButtonAt.
    val captureHitRadiusPx = remember(density) {
        with(density) { (WatchTokens.CaptureRadius + 9.dp).toPx() }
    }

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
                        // The loop must also keep running while a button press is still
                        // fading, or the swell would freeze mid-animation whenever the
                        // engine itself has nothing to draw -- which is the common case,
                        // since a capture press produces no scroll and no wave.
                        val pressAlive = pressedButton.value != null &&
                            nowMs - pressedAtMs.longValue < WatchTokens.CapturePressMs
                        if (!pressAlive) pressedButton.value = null
                        alive = engine.advance(nowMs) or pressAlive
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
                // Contact feedback fires on press-DOWN, before the gesture is known.
                //
                // That ordering is the whole point: the tick reports that the surface
                // registered the finger, which is true the instant it lands and is what
                // makes the watch feel responsive. Waiting for the gesture to resolve
                // put the first sensation up to a full double-tap window after the
                // touch, which reads as a laggy screen.
                //
                // It is deliberately a CONTACT signal and nothing more. It makes no
                // claim about delivery or acceptance -- those remain the distinct
                // sensations fired below, once the gesture is known and sent. So a
                // press produces two buzzes: "I felt you", then "here is what it was".
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    // CAPTURE BUTTONS, checked before the dead zone: they deliberately live
                    // out in the rim band the dead zone otherwise discards.
                    //
                    // Fired on press-DOWN with no hold window, because these are buttons,
                    // not gestures -- there is nothing to disambiguate, and making the user
                    // wait out a hold threshold to take a photo would feel broken. The
                    // press is then consumed so the release cannot also read as a tap.
                    val button = captureButtonAt(
                        down.position.x, down.position.y,
                        size.width.toFloat(), size.height.toFloat(),
                        hitRadiusPx = captureHitRadiusPx,
                    )
                    if (button != null) {
                        onCapture(button)
                        val now = SystemClock.uptimeMillis()
                        // Visible confirmation ON THE BUTTON ITSELF: it swells and
                        // fills. A ripple alone was not enough -- the user is looking
                        // at the control they pressed, so that is where the answer
                        // belongs.
                        pressedButton.value = button
                        pressedAtMs.value = now
                        if (liveState.value.inputEnabled) {
                            Haptics.held(view)
                        } else {
                            Haptics.refused(view)
                        }
                        // Wake the frame loop so the press animation is pumped even
                        // when nothing else on screen is moving.
                        wake.intValue++
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }

                    // RIM DEAD ZONE. Turning the bezel means dragging a finger around the
                    // edge of the glass, and that press is a real ACTION_DOWN -- so every
                    // scroll also started a press here and fired a HOLD once it outlived
                    // the threshold, which made scrolling impossible.
                    //
                    // Only the central disc accepts taps and holds. The rim is reserved
                    // for the rotary, and a press that starts there is abandoned outright
                    // (no contact tick either, so touching the rim to scroll stays silent
                    // rather than promising an action that will never be sent).
                    //
                    // Measured from the CENTRE, not from the edges: the display is round,
                    // so a rectangular inset would leave the diagonal corners live while
                    // clipping the top and bottom of the usable area.
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val dx = down.position.x - cx
                    val dy = down.position.y - cy
                    val radius = kotlin.math.sqrt(dx * dx + dy * dy)
                    val liveRadius = (kotlin.math.min(size.width, size.height) / 2f) *
                        TAP_ZONE_FRACTION
                    if (radius > liveRadius) {
                        // Consume the rest of this press so no later stage mistakes the
                        // release for a tap.
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }

                    Haptics.contact(view)

                    // The threshold is read PER PRESS, not captured: the receiver can
                    // revise it at any time on the status channel, and a value captured
                    // when this pointerInput was created would pin the watch to whatever
                    // was current at composition for the rest of the session.
                    val holdMs = holdThresholdMs().toLong()
                    var held = false
                    // Wait out the hold window. A null return means the finger left
                    // first, so this was a tap and the tap path below handles it.
                    val up = withTimeoutOrNull(holdMs) { waitForUpOrCancellation() }
                    if (up == null) {
                        // Still down at the threshold: this is a hold. It is emitted
                        // NOW rather than on release, so the gesture confirms itself
                        // under the finger exactly as the glasses touchpad does.
                        held = true
                        onHold()
                        val now = SystemClock.uptimeMillis()
                        if (liveState.value.inputEnabled) {
                            Haptics.held(view)
                            engine.onWave(now, down.position.x, down.position.y, refusal = false)
                        } else if (Haptics.refused(view)) {
                            engine.onWave(now, down.position.x, down.position.y, refusal = true)
                        }
                        if (engine.needsWake()) wake.intValue++
                        // Consume the rest of the press so the release cannot also read
                        // as a tap. Without this one hold emits HOLD and then SELECT.
                        waitForUpOrCancellation()
                    }
                    if (!held && up != null) {
                        val position = up.position
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
                    }
                }
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
                // Drawn LAST so the buttons interrupt the scroll arc rather than being
                // painted over by it. They are permanent furniture -- always present,
                // in every link state -- so there is no condition around this call.
                drawCaptureButtons(
                    pressed = pressedButton.value,
                    pressAgeMs = SystemClock.uptimeMillis() - pressedAtMs.longValue,
                    recording = recording.value,
                )
            }
            .semantics {
                contentDescription = ComplicationCopy.contentDescription(current)
            },
        contentAlignment = Alignment.Center,
    ) {
        // Nothing at all while the link is healthy -- see LinkState.hidesText.
        if (!current.hidesText) StatusBlock(current, elapsedLabel)
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
    // The track is broken around the buttons, which is what makes them read as PART of
    // the rim rather than as decals on top of it. Two arcs: the long way round from the
    // bottom of the video button back to the top of the photo one, and nothing between.
    val gapStart = WatchTokens.CapturePhotoAngle - WatchTokens.CaptureArcGapHalfSweep
    val gapEnd = WatchTokens.CaptureVideoAngle + WatchTokens.CaptureArcGapHalfSweep
    drawArc(
        color = WatchTokens.RimTrack,
        startAngle = gapEnd - 90f,
        sweepAngle = 360f - (gapEnd - gapStart),
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
 * The two capture buttons: circles in the rim on the right, in the ordinary UI ink.
 *
 * Each is a filled disc with a ring and a small glyph -- a solid dot for the photo
 * shutter, a triangle for video. The glyphs are the smallest marks that still read at
 * this size; anything more detailed becomes a smudge on a 1-inch display.
 *
 * A press swells the button and lights its fill for [WatchTokens.CapturePressMs]. That
 * feedback is on the control itself rather than only in the ripple, because that is where
 * the user is looking when they press it.
 */
private fun DrawScope.drawCaptureButtons(
    pressed: RemoteInputProtocol.EventType?,
    pressAgeMs: Long,
    recording: Boolean,
) {
    val baseRadius = WatchTokens.CaptureRadius.toPx()
    val ringStroke = Stroke(width = WatchTokens.CaptureRingStroke.toPx())

    fun button(angle: Float, type: RemoteInputProtocol.EventType) {
        val (bx, by) = captureButtonCentre(angle, size.width, size.height)
        val centre = Offset(bx, by)
        // The video button becomes a STOP button while recording, and is the one place
        // colour is spent: it is the only state where doing nothing has a consequence.
        val isStop = type == RemoteInputProtocol.EventType.VIDEO && recording

        val p = if (pressed == type) {
            (1f - (pressAgeMs.toFloat() / WatchTokens.CapturePressMs)).coerceIn(0f, 1f)
        } else {
            0f
        }
        val radius = baseRadius * (1f + WatchTokens.CapturePressScale * p)
        val ink = if (isStop) WatchTokens.Fault else WatchTokens.CaptureRing
        val glyphInk = if (isStop) WatchTokens.Fault else WatchTokens.CaptureGlyph

        drawCircle(color = WatchTokens.CaptureFill, radius = radius, center = centre)
        if (isStop) {
            // A dim red wash so the recording state reads at a glance, without lighting
            // the whole disc on an OLED held inches from the eye.
            drawCircle(
                color = WatchTokens.Fault.copy(alpha = 0.22f),
                radius = radius,
                center = centre,
            )
        }
        if (p > 0f) {
            drawCircle(color = ink.copy(alpha = 0.30f * p), radius = radius, center = centre)
        }
        drawCircle(
            color = ink.copy(alpha = 0.55f + 0.45f * p),
            radius = radius,
            center = centre,
            style = ringStroke,
        )

        val g = radius * 0.46f
        when {
            isStop -> drawStopGlyph(centre, g, glyphInk)
            type == RemoteInputProtocol.EventType.PHOTO -> drawPhotoGlyph(centre, g, glyphInk)
            else -> drawVideoGlyph(centre, g, glyphInk)
        }
    }

    button(WatchTokens.CapturePhotoAngle, RemoteInputProtocol.EventType.PHOTO)
    button(WatchTokens.CaptureVideoAngle, RemoteInputProtocol.EventType.VIDEO)
}

/**
 * A stills camera: body, viewfinder bump, lens.
 *
 * Drawn rather than loaded as a vector asset because the whole glyph is ~14px across on
 * this display -- at that size the recognisable silhouette has to be tuned by hand against
 * the pixel grid, and a scaled-down Material icon turns to mush.
 */
private fun DrawScope.drawPhotoGlyph(c: Offset, g: Float, ink: Color) {
    val w = g * 2.0f
    val h = g * 1.45f
    // Viewfinder bump on the top left, which is what makes it read as a camera and not
    // as a plain rounded rectangle.
    drawRoundRect(
        color = ink,
        topLeft = Offset(c.x - w * 0.30f, c.y - h * 0.78f),
        size = Size(w * 0.30f, h * 0.24f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(g * 0.10f),
    )
    drawRoundRect(
        color = ink,
        topLeft = Offset(c.x - w / 2f, c.y - h / 2f + h * 0.10f),
        size = Size(w, h * 0.90f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(g * 0.28f),
    )
    // Lens punched out in the fill colour: a hole reads better than an outline here.
    drawCircle(
        color = WatchTokens.CaptureFill,
        radius = g * 0.42f,
        center = Offset(c.x, c.y + h * 0.06f),
    )
}

/** A video camera: body plus the lens barrel jutting right. */
private fun DrawScope.drawVideoGlyph(c: Offset, g: Float, ink: Color) {
    val w = g * 1.55f
    val h = g * 1.25f
    drawRoundRect(
        color = ink,
        topLeft = Offset(c.x - g * 1.05f, c.y - h / 2f),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(g * 0.24f),
    )
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x + g * 0.58f, c.y)
        lineTo(c.x + g * 1.12f, c.y - h * 0.46f)
        lineTo(c.x + g * 1.12f, c.y + h * 0.46f)
        close()
    }
    drawPath(path, color = ink)
}

/** A stop square. Deliberately the most literal shape available. */
private fun DrawScope.drawStopGlyph(c: Offset, g: Float, ink: Color) {
    val s = g * 1.30f
    drawRoundRect(
        color = ink,
        topLeft = Offset(c.x - s / 2f, c.y - s / 2f),
        size = Size(s, s),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(g * 0.18f),
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
 * hardware actually reports (verified via `dumpsys input`: SCROLL min=-1 max=+1).
 *
 * Compose's negation is left in place rather than undone: turning the bezel
 * clockwise then moves the glasses forward, which is the direction the wearer
 * expects and the one the physical touchpad already gives for a forward swipe.
 *
 * The factor is queried per event rather than cached because it is
 * configuration-dependent and this is a single cheap field read. This function is
 * allocation free and does no logging: it runs on the input path at up to ~31 Hz
 * and sits between the physical detent and the send.
 */
internal fun rawAxisScroll(view: View, verticalScrollPixels: Float): Float {
    val factor = ViewConfiguration.get(view.context).scaledVerticalScrollFactor
    return if (factor <= 0f) verticalScrollPixels else verticalScrollPixels / factor.toFloat()
}
