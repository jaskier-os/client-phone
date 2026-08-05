package com.repository.listener.protocol

import com.repository.listener.protocol.RemoteInputProtocol.COALESCE_WINDOW_MS
import com.repository.listener.protocol.RemoteInputProtocol.EventType
import com.repository.listener.protocol.RemoteInputProtocol.MAX_STEPS_PER_EVENT
import kotlin.math.abs
import kotlin.math.sign

/**
 * Leading-edge coalescer for scroll detents.
 *
 * Runs on the WATCH, not the phone. The parent plan placed it on the phone, but
 * the authentication tag covers `seq` and `steps`, and coalescing rewrites both --
 * so a phone-side merge produces an event the watch never signed and the glasses
 * can never verify. Coalescing therefore happens before the tag is computed. The
 * wire contract is byte-identical either way, so the glasses side is unaffected.
 *
 * Rules (frozen):
 *  1. A detent arriving with no window open is emitted IMMEDIATELY and opens a
 *     60 ms window. This is the cheap latency win: a trailing-edge window charged
 *     0-60 ms to every scroll including a lone slow detent, and ~55-65 ms is the
 *     just-noticeable-difference for added latency on discrete input.
 *  2. Further same-direction detents inside the window accumulate.
 *  3. Window closes: emit if steps != 0, then idle.
 *  4. steps reaching the cap flushes immediately and starts a new window.
 *  5. A direction change flushes the pending event, then applies rule 1.
 *  6. SELECT/BACK/OPEN/CLOSE/PING flush any pending SCROLL first, then emit.
 *     They are never coalesced.
 *
 * Deliberately Android-free and single-threaded: it is confined to the link
 * service's worker thread, and time is passed in rather than read, so the whole
 * rule set is exercised deterministically by JVM unit tests.
 *
 * The caller drives it with [onDetents], [onDiscreteEvent] and [onTimeout], and
 * receives emissions through [sink]. Emissions are the ONLY place a sequence
 * number is minted, which is what makes seq assignment and emission order the
 * same order by construction.
 */
class ScrollCoalescer(
    private val windowMs: Long = COALESCE_WINDOW_MS,
    private val maxSteps: Int = MAX_STEPS_PER_EVENT,
    private val sink: Sink,
) {
    /** Receives coalesced emissions, in order. */
    fun interface Sink {
        /**
         * @param type the event type to emit.
         * @param steps signed detent count, 0 for non-SCROLL.
         * @param timeMs the time to stamp the event with (the time of the FIRST
         *        detent merged into it, so age reflects when the user acted).
         */
        fun emit(type: EventType, steps: Int, timeMs: Long)
    }

    /** Pending same-direction detents not yet emitted. 0 when no window is open. */
    private var pendingSteps: Int = 0

    /** Time the currently open window opened, or MIN_VALUE when none is open. */
    private var windowOpenedAtMs: Long = Long.MIN_VALUE

    /** Stamp for the pending event: when the first merged detent occurred. */
    private var pendingTimeMs: Long = 0

    val hasPendingWindow: Boolean get() = windowOpenedAtMs != Long.MIN_VALUE

    /** Deadline at which the caller should invoke [onTimeout], or null if idle. */
    fun nextTimeoutAtMs(): Long? =
        if (windowOpenedAtMs == Long.MIN_VALUE) null else windowOpenedAtMs + windowMs

    /**
     * Feeds a signed detent count produced by [DetentAccumulator].
     * Emits immediately when no window is open (rule 1), on a direction change
     * (rule 5), or on reaching the cap (rule 4).
     */
    fun onDetents(steps: Int, nowMs: Long) {
        if (steps == 0) return

        if (windowOpenedAtMs == Long.MIN_VALUE) {
            // Rule 1: leading edge. Emit up to the cap now, carry any excess into
            // the freshly opened window rather than dropping it.
            val direction = steps.sign
            val immediate = minOf(abs(steps), maxSteps)
            sink.emit(EventType.SCROLL, direction * immediate, nowMs)
            val carried = abs(steps) - immediate
            windowOpenedAtMs = nowMs
            pendingTimeMs = nowMs
            pendingSteps = direction * carried
            // Emit whole cap-sized chunks. Flushing outright here would emit a
            // single event above the cap, which overflows the int8 steps field
            // and flips the scroll direction on the wire.
            emitWholeCapChunks(nowMs)
            return
        }

        if (pendingSteps != 0 && steps.sign != pendingSteps.sign) {
            // Rule 5: direction change. Flush what is pending, then treat the new
            // direction as a fresh leading edge.
            flush(nowMs)
            onDetents(steps, nowMs)
            return
        }

        if (pendingSteps == 0) pendingTimeMs = nowMs
        pendingSteps += steps

        // Rule 4: cap reached. Emit full events and open a new window so the
        // surplus above the cap is carried, not discarded.
        emitWholeCapChunks(nowMs)
    }

    /**
     * Emits pending detents in cap-sized chunks, leaving any sub-cap remainder in
     * the open window. Every emission is therefore within the int8 wire range.
     */
    private fun emitWholeCapChunks(nowMs: Long) {
        while (abs(pendingSteps) >= maxSteps) {
            val direction = pendingSteps.sign
            sink.emit(EventType.SCROLL, direction * maxSteps, pendingTimeMs)
            pendingSteps -= direction * maxSteps
            windowOpenedAtMs = nowMs
            pendingTimeMs = nowMs
        }
    }

    /** Rule 6: a discrete event flushes any pending SCROLL first, in order. */
    fun onDiscreteEvent(type: EventType, nowMs: Long) {
        require(type != EventType.SCROLL) { "use onDetents for SCROLL" }
        flush(nowMs)
        sink.emit(type, 0, nowMs)
    }

    /** Rule 3: the caller's window timer fired. */
    fun onTimeout(nowMs: Long) {
        if (windowOpenedAtMs == Long.MIN_VALUE) return
        if (nowMs - windowOpenedAtMs < windowMs) return
        flush(nowMs)
    }

    /** Emits any pending detents and closes the window. Safe when already idle. */
    fun flush(nowMs: Long) {
        if (pendingSteps != 0) {
            sink.emit(EventType.SCROLL, pendingSteps, pendingTimeMs)
            pendingSteps = 0
        }
        windowOpenedAtMs = Long.MIN_VALUE
    }

    fun reset() {
        pendingSteps = 0
        windowOpenedAtMs = Long.MIN_VALUE
        pendingTimeMs = 0
    }
}
