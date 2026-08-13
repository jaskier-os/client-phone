package com.repository.listener.wear

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The haptic vocabulary of the remote.
 *
 * The user is wearing AR glasses and is mostly NOT looking at the watch, so touch
 * is the primary output channel, not a garnish on a visual one. That only works
 * if the vocabulary is small and the entries are genuinely distinguishable
 * without looking:
 *
 * | Event            | Constant                | Feels like            | Asserts        |
 * |------------------|-------------------------|-----------------------|----------------|
 * | Scroll detent    | SEGMENT_TICK            | a click of a dial     | movement       |
 * | Tap accepted     | CONFIRM                 | a definite landing    | it will land   |
 * | Tap dispatched   | KEYBOARD_TAP            | a light key press     | sent, no more  |
 * | Tap refused      | REJECT                  | a blunt double bump   | it did not     |
 * | Link lost        | TOGGLE_OFF              | something switched off| input is dead  |
 * | Link restored    | TOGGLE_ON               | something switched on | input is live  |
 *
 * The refusal is the load-bearing one. It is the only way the user learns that a
 * tap did nothing WITHOUT raising their wrist and reading, which is the whole
 * point of putting the control on a watch.
 *
 * ## Why "accepted" and "dispatched" are two different things
 *
 * The rightmost column is the whole design. A haptic is consumed with no context
 * and total trust, so it must never assert more than the watch actually knows.
 *
 * For most of this feature's life the watch knew only that it had SENT a tap. The
 * glasses refuse input for their own reasons -- wrong focus state, folded, a
 * locked slider -- and none of that came back over the wire, so firing CONFIRM
 * off link state would have delivered a confident success buzz for events that
 * were being thrown away. During that period the glasses were in fact refusing
 * input almost constantly while the link was perfectly healthy, so such a buzz
 * would have been wrong on nearly every tap. Only [dispatched] was honest.
 *
 * The glasses now report refusals (GLASSES_REFUSING_INPUT plus a reason), so the
 * watch can finally distinguish "the link is up" from "input is landing", and
 * [accepted] is earned. It is driven by [LinkState.inputConfirmed] -- which is
 * true ONLY for READY, where the chain is healthy AND no refusal is being
 * reported -- and never by link state alone. States that send without proof of
 * arrival (WAKING, DEGRADED) keep the weaker [dispatched] tick.
 *
 * The two are deliberately far apart in strength, so a user who learned the light
 * tick during the honest-but-uncertain period experiences CONFIRM as a promotion
 * of a gesture they already know rather than as a new alphabet.
 *
 * Two things are deliberately not done. There is no haptic on entering every
 * state -- only on crossing the boundary between "input works" and "input does
 * not" -- because a buzz for a transition the user cannot act on is pure noise
 * and pure battery. And nothing here branches on the return value of
 * performHapticFeedback: the platform documents that return as reporting whether
 * feedback was performed at all (it is false when the user has haptics disabled),
 * not whether a specific constant was recognised, and an unrecognised constant
 * falls back internally. Branching on it would silently double-buzz on the very
 * devices where the first call worked.
 *
 * All entry points are main-thread only, which is what makes the unsynchronised
 * rate-limit fields below safe.
 */
@androidx.annotation.MainThread
object Haptics {

    /**
     * Minimum interval between detent ticks, milliseconds.
     *
     * performHapticFeedback is a synchronous binder call into the vibrator
     * service and each SEGMENT_TICK primitive occupies the actuator for a
     * comparable span, so past roughly this rate the service is already dropping
     * effects non-deterministically -- the user feels a ragged, thinning buzz
     * rather than more ticks. Limiting deterministically at 25 Hz keeps one tick
     * per detent for every deliberate movement (measured detent gaps run 32-110
     * ms) and degrades predictably instead of randomly only in the top of a fast
     * flick, where individual ticks are not perceptually resolvable anyway.
     */
    private const val DETENT_MIN_INTERVAL_MS = 40L

    /**
     * Minimum interval between refusals. A refusal is a heavy effect and a user
     * scrolling against a dead link would otherwise generate a continuous buzz.
     */
    private const val REFUSAL_MIN_INTERVAL_MS = 800L

    private var lastDetentMs = 0L
    private var lastRefusalMs = 0L

    /** One click of the dial. Rate limited; see [DETENT_MIN_INTERVAL_MS]. */
    fun detent(view: View) {
        val now = SystemClock.uptimeMillis()
        if (now - lastDetentMs < DETENT_MIN_INTERVAL_MS) return
        lastDetentMs = now
        view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
    }

    /**
     * The tap left the watch. Asserts delivery, NOT success.
     *
     * Light on purpose: it has to be clearly weaker than [accepted] so that when
     * a real acknowledgement signal arrives and taps start confirming properly,
     * the user feels the difference as a promotion rather than as a new alphabet.
     */
    fun dispatched(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /**
     * The tap will land: the chain is healthy and the glasses are not refusing.
     *
     * Gated on [LinkState.inputConfirmed], never on link state alone.
     */
    fun accepted(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    /**
     * The input cannot land in the current state. Rate limited so holding a
     * gesture against a dead link buzzes once, not continuously.
     *
     * @return true if the refusal was actually delivered, so the caller can keep
     *         the visual and the tactile feedback in step rather than showing a
     *         refusal wave with no accompanying sensation.
     */
    fun refused(view: View): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastRefusalMs < REFUSAL_MIN_INTERVAL_MS) return false
        lastRefusalMs = now
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        return true
    }

    /**
     * The surface registered a finger. Fired on press-DOWN, before the gesture is known.
     *
     * Says NOTHING about delivery or acceptance -- at this point nothing has been sent
     * and the gesture is not yet resolved. It exists purely so contact feels immediate;
     * every press therefore produces this tick and then, once resolved, one of the
     * sensations below. Deliberately the lightest effect available, so the second buzz
     * is the one that carries the information.
     */
    fun contact(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /**
     * A press reached the hold threshold and a HOLD was emitted.
     *
     * Heavier than [contact] and distinct from [accepted], because a hold commits the
     * user to something a tap does not (on the glasses it starts dictation) and they
     * must be able to tell from the wrist which gesture the watch decided on.
     */
    fun held(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** Input just became impossible. Fired on the transition only. */
    fun linkLost(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.TOGGLE_OFF)
    }

    /** Input just became possible. Fired on the transition only. */
    fun linkRestored(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.TOGGLE_ON)
    }
}
