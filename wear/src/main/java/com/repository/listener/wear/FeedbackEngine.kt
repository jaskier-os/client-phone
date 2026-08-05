package com.repository.listener.wear

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The visual state behind the rim and the tap wave.
 *
 * DELIBERATELY NOT COMPOSE SNAPSHOT STATE. Every field here is written from the
 * rotary callback, which fires up to ~31 times a second during a fast bezel spin.
 * A snapshot write there would invalidate the composition on nearly every frame
 * and put measure and layout on the input path; the input path must stay as close
 * to free as possible, because it is shared with the send. Instead the callback
 * mutates these plain fields and the draw layer reads them once per frame, driven
 * by its own frame clock. Draw only, never recompose.
 *
 * For the same reason nothing here allocates: the class is created once and every
 * update is arithmetic on existing primitives.
 *
 * ## Why a charge, not a pulse per detent
 *
 * The obvious design -- one expanding ripple per detent -- fails at speed. At 31
 * detents per second with any decay long enough to be seen, a dozen ripples
 * overlap and the display modulates its luminance at roughly 31 Hz. That is
 * squarely in the range WCAG 2.3.1 exists to prevent (no more than three general
 * flashes per second over a significant area), on a screen worn against the body
 * and viewed at 30 cm. It is also simply illegible: a fast spin reads as noise.
 *
 * So the scroll feedback is a single continuous quantity. Each detent TOPS UP a
 * charge which decays exponentially, and advances a head angle. The result is one
 * arc that brightens smoothly as the user spins, sweeps around the rim, and fades
 * when they stop. One detent is a visible nudge; thirty are continuous motion.
 * There is no flash at any speed, because there is no discrete visual event at
 * all.
 *
 * ## Why taps are the opposite -- a pool of coexisting waves
 *
 * Taps get the treatment scroll cannot have, because the two are not comparable
 * events. A tap is deliberate, human-rate and countable; the user knows exactly
 * how many they made and wants to see each one. So every tap starts its OWN wave
 * in a fixed pool, with its own origin, birth time and phase, and waves overlap
 * freely. A new wave never cancels, restarts or steals from one already running.
 *
 * That is safe here for a reason that does not apply to the scroll ripple: a wave
 * is a thin ring, not a filled disc, so even [MAX_WAVES] of them at maximum
 * overlap modulate under 2% of the display's luminance -- far below the flash
 * threshold that ruled out per-detent scroll pulses. The rate limit that remains
 * bounds only how often a new wave is BORN, never how long an existing one lives.
 */
class FeedbackEngine {

    companion object {

        /**
         * Charge added per detent. Three to four detents saturate, so a slow
         * deliberate click is clearly visible while a spin cannot exceed full.
         */
        const val CHARGE_PER_DETENT = 0.30f

        /**
         * Exponential decay time constant for the scroll charge, milliseconds.
         *
         * This value is what makes a fast spin safe to look at, so it is derived
         * rather than picked. During a spin the charge is pinned at its ceiling
         * and dips only by whatever decays in the gap between two detents, so the
         * residual brightness ripple is exactly `1 - exp(-gap / tau)`. At the
         * fastest measured gap of 32 ms this is 10.1% of charge, which the alpha
         * mapping in the draw layer (`0.15 + 0.45 * charge`) compresses to 7.5%
         * of rendered luminance -- below the 10% relative-luminance change that
         * WCAG 2.3.1 counts as a flash at all, before even reaching its
         * requirement that a flash cover a quarter of the field, which a 4 dp rim
         * stroke on a 432 px disc comes nowhere near. A materially shorter tau
         * would push the ripple over that line; a longer one would leave the arc
         * hanging after the user stops.
         */
        const val CHARGE_TAU_MS = 300f

        /**
         * Degrees of rim travel per detent. The bezel is 60 detents per
         * revolution, so 6 degrees makes the arc a true 1:1 mirror of the user's
         * finger: a full turn of the bezel is exactly one lap of the rim.
         */
        const val DEGREES_PER_DETENT = 6f

        /** Below this the arc is invisible and the frame loop may stop. */
        const val CHARGE_FLOOR = 0.01f

        /** Tap wave lifetime, milliseconds. */
        const val TAP_WAVE_MS = 420f

        /**
         * Refusal wave lifetime. Shorter and it reads as a rebound rather than a
         * release.
         */
        const val REFUSE_WAVE_MS = 260f

        /**
         * How many waves may be in flight at once.
         *
         * Waves COEXIST; a new one never cancels or restarts an existing one.
         * Tapping twice quickly must produce two rings at their own independent
         * phases, because that is what a ripple is and what the eye expects.
         *
         * Four is derived from the geometry, not chosen for feel. A wave is a
         * 2.5 dp ring (5.3 px at this 340 dpi panel), so even at its widest it
         * covers about 1.5% of the 432 px disc. Integrating the alpha envelope
         * over four maximally-overlapped waves gives a peak mean-field luminance
         * modulation of 1.47%, against the 10% that WCAG 2.3.1 treats as a flash
         * -- and that guidance further requires a flash to cover more than 25% of
         * the field, which a set of thin rings never approaches. Even five waves
         * only reach 1.74%. Four therefore sits far inside the safety margin
         * while being more than a human can physically produce; the cap exists to
         * bound memory and draw cost, not because the fifth wave would be unsafe.
         *
         * The pool is pre-allocated and reused, so a tap allocates nothing.
         */
        const val MAX_WAVES = 4

        /**
         * Minimum gap between the CREATION of two waves.
         *
         * This limits how often a NEW wave may be born. It must never be
         * implemented by cancelling or restarting one already in flight -- that
         * is exactly what made the feedback look like a single wave that reset
         * instead of a set of ripples.
         *
         * 120 ms, down from an earlier 340 ms. The old value was longer than the
         * 150-250 ms gap of a natural double tap, so the second tap of a genuine
         * double tap -- the app's own BACK gesture, the single most important
         * thing a user does here -- was silently drawn as nothing. A gesture that
         * the glasses act on must be visible on the watch.
         *
         * Still non-zero, so a stuck or bouncing touch cannot spawn waves without
         * bound. It does NOT gate the tap itself: the send and the haptic always
         * happen, and only the birth of an extra ring is skipped.
         */
        const val WAVE_MIN_INTERVAL_MS = 120L

        /** Progress value marking a free wave slot. */
        const val NO_WAVE = -1f
    }

    /** 0..1 scroll energy. Read by the draw layer, written by the input path. */
    var charge = 0f
        private set

    /** Accumulated rim angle in degrees; sign follows the scroll direction. */
    var headDegrees = 0f
        private set

    /** Sign of the most recent detent: +1 forward, -1 back, 0 if never scrolled. */
    var direction = 0
        private set

    /**
     * The wave pool: [MAX_WAVES] slots of parallel primitive arrays.
     *
     * Parallel arrays rather than a list of objects so that firing a wave is a
     * few array stores and allocates nothing -- the draw layer iterates by index.
     * A slot is free when its progress is [NO_WAVE].
     */
    private val waveProgressPool = FloatArray(MAX_WAVES) { NO_WAVE }
    private val waveStartPool = LongArray(MAX_WAVES)
    private val waveOriginXPool = FloatArray(MAX_WAVES)
    private val waveOriginYPool = FloatArray(MAX_WAVES)
    private val waveRefusalPool = BooleanArray(MAX_WAVES)

    /** Round-robin cursor for choosing a slot when every one is occupied. */
    private var waveCursor = 0

    /** Number of wave slots currently in flight. */
    val activeWaveCount: Int
        get() {
            var n = 0
            for (i in 0 until MAX_WAVES) if (waveProgressPool[i] >= 0f) n++
            return n
        }

    /** 0..1 progress of wave [index], or [NO_WAVE] when that slot is free. */
    fun waveProgressAt(index: Int): Float = waveProgressPool[index]

    /** True when wave [index] is a refusal rather than a dispatch. */
    fun waveIsRefusalAt(index: Int): Boolean = waveRefusalPool[index]

    /** X origin of wave [index], or [centerX] when it had no touch point. */
    fun waveOriginXAt(index: Int, centerX: Float): Float =
        if (waveOriginXPool[index].isNaN()) centerX else waveOriginXPool[index]

    /** Y origin of wave [index], or [centerY] when it had no touch point. */
    fun waveOriginYAt(index: Int, centerY: Float): Float =
        if (waveOriginYPool[index].isNaN()) centerY else waveOriginYPool[index]

    /**
     * True while the frame loop is currently pumping.
     *
     * This is what keeps the input path free of snapshot writes. During a spin
     * the loop is already running, so a detent only mutates plain fields and the
     * loop picks them up on its next frame. Only the FIRST input after an idle
     * period needs to wake anything, and [needsWake] is how the input path asks
     * for that without knowing anything about Compose.
     */
    var running = false
        private set

    /**
     * Sentinel for "no such timestamp yet".
     *
     * NOT zero. Zero is a perfectly legal clock reading, and using it as "never"
     * makes the very first event after the clock's origin compare as though it
     * had just happened -- which silently swallows the first wave and makes the
     * first frame's delta wrong. A separate out-of-band value has no such
     * collision.
     */
    private val unset = Long.MIN_VALUE

    private var lastWaveMs = unset
    private var lastFrameMs = unset

    /**
     * Records one detent. Called from the rotary callback AFTER the event has
     * been handed to the link service, so nothing here can delay a send.
     *
     * @param delta the raw axis value, nominally +/-1.0 per detent.
     */
    fun onDetent(delta: Float, nowMs: Long) {
        if (delta == 0f) return
        direction = if (delta > 0f) 1 else -1
        charge = min(1f, charge + CHARGE_PER_DETENT)
        headDegrees += delta * DEGREES_PER_DETENT
        // Keep the angle bounded so a long session cannot drift into float
        // resolution loss, which would make the arc visibly stutter.
        if (headDegrees > 360f || headDegrees < -360f) headDegrees %= 360f
        if (lastFrameMs == unset) lastFrameMs = nowMs
    }

    /**
     * True when something is visible but no frame loop is pumping it, i.e. the
     * caller must wake the loop. False during a spin, which is the hot path: an
     * ongoing scroll never asks for a wake and therefore never writes Compose
     * state.
     */
    fun needsWake(): Boolean = !running && (charge > 0f || activeWaveCount > 0)

    /** Marks the frame loop as pumping. Called by the loop, not by input. */
    fun markRunning(value: Boolean) {
        running = value
        if (!value) lastFrameMs = unset
    }


    /**
     * Records a tap or a refused tap, originating at ([x], [y]).
     *
     * The origin is applied ONLY when a wave actually starts. Storing it
     * unconditionally would let a suppressed second tap teleport a wave that is
     * still mid-flight to a new point, which reads as a glitch rather than as
     * feedback.
     *
     * @return true if a wave was started, false if the flash rate limit
     *         suppressed it.
     */
    fun onWave(nowMs: Long, x: Float, y: Float, refusal: Boolean): Boolean {
        if (lastWaveMs != unset && nowMs - lastWaveMs < WAVE_MIN_INTERVAL_MS) return false
        lastWaveMs = nowMs

        // Prefer a free slot so an existing wave is never disturbed. Only when
        // all MAX_WAVES are genuinely in flight is one recycled, and then the
        // OLDEST is taken -- it is the faintest and closest to retiring, so its
        // loss is the least visible. Round-robin from a cursor would be cheaper
        // but could evict a wave that had only just started.
        var slot = -1
        for (i in 0 until MAX_WAVES) {
            if (waveProgressPool[i] < 0f) {
                slot = i
                break
            }
        }
        if (slot < 0) {
            var oldest = 0
            for (i in 1 until MAX_WAVES) {
                if (waveStartPool[i] < waveStartPool[oldest]) oldest = i
            }
            slot = oldest
        }
        waveCursor = slot

        waveStartPool[slot] = nowMs
        waveRefusalPool[slot] = refusal
        waveProgressPool[slot] = 0f
        waveOriginXPool[slot] = x
        waveOriginYPool[slot] = y
        if (lastFrameMs == unset) lastFrameMs = nowMs
        return true
    }

    /**
     * A wave with no touch point, used for input refused via the bezel. It
     * originates at the middle of the display, which the draw layer supplies.
     */
    fun onWaveAtCenter(nowMs: Long, refusal: Boolean): Boolean =
        onWave(nowMs, Float.NaN, Float.NaN, refusal)

    /**
     * Advances the animation to [nowMs].
     *
     * @return true while anything is still visible. The frame loop stops as soon
     *         as this returns false, so an untouched screen costs nothing at all.
     */
    fun advance(nowMs: Long): Boolean {
        val dt = if (lastFrameMs == unset) 0f else (nowMs - lastFrameMs).toFloat()
        lastFrameMs = nowMs

        if (charge > 0f) {
            // Frame-rate independent decay. A per-frame multiplier would decay at
            // a different rate at 60 and 90 Hz, and this watch does both.
            charge *= exp(-max(0f, dt) / CHARGE_TAU_MS)
            if (charge < CHARGE_FLOOR) charge = 0f
        }

        // Every wave advances on its OWN start time, so each keeps an independent
        // phase and retires by itself. Nothing here couples one wave to another.
        var anyWave = false
        for (i in 0 until MAX_WAVES) {
            if (waveProgressPool[i] < 0f) continue
            val span = if (waveRefusalPool[i]) REFUSE_WAVE_MS else TAP_WAVE_MS
            val p = (nowMs - waveStartPool[i]) / span
            if (p >= 1f) {
                waveProgressPool[i] = NO_WAVE
            } else {
                waveProgressPool[i] = p
                anyWave = true
            }
        }

        val alive = charge > 0f || anyWave
        if (!alive) lastFrameMs = unset
        return alive
    }

    /**
     * Drops all energy. Called when the screen goes away, so returning to it
     * never resumes a fade that belongs to an interaction the user has forgotten.
     */
    fun reset() {
        charge = 0f
        for (i in 0 until MAX_WAVES) waveProgressPool[i] = NO_WAVE
        lastWaveMs = unset
        lastFrameMs = unset
        running = false
        // headDegrees and direction are intentionally kept: they describe where
        // the dial is, not an animation in flight.
    }
}
