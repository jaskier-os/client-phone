package com.repository.listener.protocol

import kotlin.math.abs
import kotlin.math.sign

/**
 * Turns a raw scroll signal into discrete detents.
 *
 * Deliberately free of any Android dependency so it runs as a plain JVM unit test
 * and so BOTH input surfaces share it: the rotary bezel (raw AXIS_SCROLL units)
 * and the left-edge vertical drag fallback (pixels) feed the same maths with a
 * different [threshold]. The emitted detent stream is therefore identical
 * whichever surface the device turns out to support, which is what keeps the
 * glasses side unaffected by that choice.
 *
 * Conservation rule, which the whole design depends on: every stage that cannot
 * emit a step right now CARRIES it. Nothing is ever discarded. An earlier design
 * silently lost roughly half of a fast spin across three such stages.
 *
 * Not thread-safe by construction. It is confined to the link service's single
 * worker thread; the input surface posts raw deltas to that thread rather than
 * touching this directly.
 */
class DetentAccumulator(
    /** Raw units per detent. Calibrated per surface. */
    private val threshold: Float,
    /** Max detents emitted from one input event; the rest is carried. */
    private val maxStepsPerEvent: Int = MAX_STEPS_PER_INPUT_EVENT,
    /** Sustained outbound ceiling, in steps per second. Surplus is carried. */
    private val maxStepsPerSecond: Int = MAX_STEPS_PER_SECOND,
) {
    companion object {
        /**
         * Idle gap after which the accumulator resets. Mirrors AOSP's
         * LowResFlingTimeframe (100 ms): a fractional remainder from a gesture the
         * user finished a second ago must not combine with a new one and produce a
         * phantom detent in whichever direction happens to win.
         */
        const val IDLE_RESET_MS = 100L

        const val MAX_STEPS_PER_INPUT_EVENT = 4
        const val MAX_STEPS_PER_SECOND = 20

        private const val MS_PER_SECOND = 1000L
    }

    /** Sub-detent remainder, carried across events. */
    private var accumulated: Float = 0f

    /** Steps the rate limiter could not pass yet. Carried, never dropped. */
    private var carriedSurplus: Int = 0

    /**
     * Detents in the NEW direction, held while the old direction's backlog drains.
     * Kept separate so the two directions can never be summed against each other,
     * which would cancel real user motion instead of delivering it.
     */
    private var pendingReversal: Int = 0

    private var lastEventTimeMs: Long = Long.MIN_VALUE

    /** Rate-limiter window start and the count already spent inside it. */
    private var windowStartMs: Long = Long.MIN_VALUE
    private var stepsInWindow: Int = 0

    /**
     * Steps still owed to the caller because the limiter deferred them, in the
     * currently draining direction. Excludes any held reversal; use
     * [hasUndeliveredDetents] to ask whether anything at all is still owed.
     */
    val pendingSurplus: Int get() = carriedSurplus

    /** True while any detent in either direction has not yet been delivered. */
    val hasUndeliveredDetents: Boolean get() = carriedSurplus != 0 || pendingReversal != 0

    /**
     * Feeds one raw input delta.
     *
     * @param delta raw units; sign is direction (+ forward/down, - back/up).
     * @param eventTimeMs monotonic event time (SystemClock.uptimeMillis on Android).
     * @return signed detent count to emit now; 0 when below threshold or rate-limited.
     */
    fun onDelta(delta: Float, eventTimeMs: Long): Int {
        // Reject non-finite samples at the door. AXIS_SCROLL arrives as a Float
        // from the platform, and adding NaN to the running sum makes every later
        // addition NaN too: the bezel would go dead for the rest of the session
        // with nothing logged anywhere. An infinity is equally unrecoverable --
        // it saturates the sum and no finite input can bring it back. Dropping a
        // single bad sample costs nothing a user can perceive.
        if (!delta.isFinite()) return 0

        if (lastEventTimeMs != Long.MIN_VALUE && eventTimeMs - lastEventTimeMs > IDLE_RESET_MS) {
            // New gesture. Drop the sub-detent remainder (it is not a step and never
            // was), but KEEP carriedSurplus -- those are real detents the user
            // already produced and the limiter merely deferred.
            accumulated = 0f
        }
        lastEventTimeMs = eventTimeMs

        accumulated += delta

        val whole = (accumulated / threshold).toInt()
        if (whole != 0) {
            accumulated -= whole * threshold

            // A reversal is only real once a COMPLETED detent goes the other way.
            // Deciding this on the sub-detent remainder instead would let a single
            // noise sample -- a finger tremor that never completes a detent --
            // discard an entire carried backlog of genuine user motion.
            if (carriedSurplus != 0 && whole.sign != carriedSurplus.sign) {
                // Deliver the old direction's undelivered backlog BEFORE reversing,
                // so it is delivered rather than discarded, and hold the new
                // direction's detents until that backlog has drained.
                //
                // The backlog leaves through the normal clamped drain path below.
                // Returning it raw would exceed maxStepsPerEvent and overflow the
                // int8 wire field -- the clamp invariant belongs here, not in
                // whichever caller happens to chunk afterwards.
                pendingReversal += whole
            } else {
                carriedSurplus += whole
            }
        }

        return drain(eventTimeMs)
    }

    /**
     * Emits whatever the limiter now permits from the carried surplus, without
     * new input. The service calls this on a timer so a spin's tail still lands
     * after the user's finger stops, and on detach so nothing is stranded.
     */
    fun drain(nowMs: Long): Int {
        // Once the old direction has fully drained, the held reversal becomes the
        // live direction. Promoting here (rather than at reversal time) is what
        // keeps every emission single-directional and within the clamp.
        if (carriedSurplus == 0 && pendingReversal != 0) {
            carriedSurplus = pendingReversal
            pendingReversal = 0
        }
        if (carriedSurplus == 0) return 0

        if (windowStartMs == Long.MIN_VALUE || nowMs - windowStartMs >= MS_PER_SECOND) {
            windowStartMs = nowMs
            stepsInWindow = 0
        }

        val budget = maxStepsPerSecond - stepsInWindow
        if (budget <= 0) return 0

        val direction = carriedSurplus.sign
        val available = abs(carriedSurplus)
        val allowed = minOf(available, budget, maxStepsPerEvent)
        if (allowed <= 0) return 0

        stepsInWindow += allowed
        carriedSurplus -= direction * allowed
        return direction * allowed
    }

    /**
     * Emits carried surplus ignoring the rate limit, in chunks no larger than
     * [maxStepsPerEvent] so every result stays inside the int8 wire range. Call
     * repeatedly until it returns 0. For teardown, where the remaining backlog
     * must land before the session ends rather than be stranded.
     */
    fun flushChunk(): Int {
        if (carriedSurplus == 0 && pendingReversal != 0) {
            carriedSurplus = pendingReversal
            pendingReversal = 0
        }
        if (carriedSurplus == 0) {
            accumulated = 0f
            return 0
        }
        val direction = carriedSurplus.sign
        val chunk = minOf(abs(carriedSurplus), maxStepsPerEvent)
        carriedSurplus -= direction * chunk
        return direction * chunk
    }

    fun reset() {
        accumulated = 0f
        carriedSurplus = 0
        pendingReversal = 0
        lastEventTimeMs = Long.MIN_VALUE
        windowStartMs = Long.MIN_VALUE
        stepsInWindow = 0
    }
}
