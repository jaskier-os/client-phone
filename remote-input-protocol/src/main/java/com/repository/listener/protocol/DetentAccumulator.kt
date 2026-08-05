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

    private var lastEventTimeMs: Long = Long.MIN_VALUE

    /** Rate-limiter window start and the count already spent inside it. */
    private var windowStartMs: Long = Long.MIN_VALUE
    private var stepsInWindow: Int = 0

    /** Steps still owed to the caller because the limiter deferred them. */
    val pendingSurplus: Int get() = carriedSurplus

    /**
     * Feeds one raw input delta.
     *
     * @param delta raw units; sign is direction (+ forward/down, - back/up).
     * @param eventTimeMs monotonic event time (SystemClock.uptimeMillis on Android).
     * @return signed detent count to emit now; 0 when below threshold or rate-limited.
     */
    fun onDelta(delta: Float, eventTimeMs: Long): Int {
        if (lastEventTimeMs != Long.MIN_VALUE && eventTimeMs - lastEventTimeMs > IDLE_RESET_MS) {
            // New gesture. Drop the sub-detent remainder (it is not a step and never
            // was), but KEEP carriedSurplus -- those are real detents the user
            // already produced and the limiter merely deferred.
            accumulated = 0f
        }
        lastEventTimeMs = eventTimeMs

        accumulated += delta

        // A direction reversal invalidates the opposite-signed remainder, and the
        // carried surplus of the old direction can no longer be delivered as-is.
        if (carriedSurplus != 0 && accumulated.sign != 0f &&
            carriedSurplus.sign.toFloat() != accumulated.sign
        ) {
            carriedSurplus = 0
        }

        val whole = (accumulated / threshold).toInt()
        if (whole != 0) {
            accumulated -= whole * threshold
            carriedSurplus += whole
        }

        return drain(eventTimeMs)
    }

    /**
     * Emits whatever the limiter now permits from the carried surplus, without
     * new input. The service calls this on a timer so a spin's tail still lands
     * after the user's finger stops, and on detach so nothing is stranded.
     */
    fun drain(nowMs: Long): Int {
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

    /** Emits all carried surplus ignoring the rate limit. For teardown only. */
    fun flushAll(): Int {
        val out = carriedSurplus
        carriedSurplus = 0
        accumulated = 0f
        return out
    }

    fun reset() {
        accumulated = 0f
        carriedSurplus = 0
        lastEventTimeMs = Long.MIN_VALUE
        windowStartMs = Long.MIN_VALUE
        stepsInWindow = 0
    }
}
