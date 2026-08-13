package com.repository.listener.arstream

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mute + keyframe state for one live AR session.
 *
 * Deliberately free of Android types so it is unit testable; the Activity and the socket threads
 * both read it, hence the atomics.
 */
class ArStreamSessionState {
    private val phoneMicMuted = AtomicBoolean(false)
    private val glassesMicMuted = AtomicBoolean(false)
    private val awaitingKeyframe = AtomicBoolean(true)

    fun setPhoneMicMuted(muted: Boolean) = phoneMicMuted.set(muted)
    fun setGlassesMicMuted(muted: Boolean) = glassesMicMuted.set(muted)

    fun isPhoneMicMuted(): Boolean = phoneMicMuted.get()
    fun isGlassesMicMuted(): Boolean = glassesMicMuted.get()

    /**
     * Both mutes suppress at the SOURCE rather than downstream: a muted phone mic transmits
     * nothing at all, so the glasses speaker cannot reproduce it and the glasses mics cannot
     * pick it back up. Muting is therefore also an echo-path cut.
     */
    fun shouldSendPhoneMic(): Boolean = !phoneMicMuted.get()

    fun shouldAcceptGlassesAudio(): Boolean = !glassesMicMuted.get()

    fun onDecoderStarted() = awaitingKeyframe.set(false)

    /**
     * Rotation/backgrounding destroys the TextureView Surface. ScreenStreamDecoder caches no
     * SPS/PPS and cannot re-attach to a new Surface, so the rebuilt decoder needs a fresh
     * config + IDR -- without one the surface stays black forever with no error anywhere.
     */
    fun onSurfaceDestroyed() = awaitingKeyframe.set(true)

    fun needsKeyframe(): Boolean = awaitingKeyframe.get()
}
