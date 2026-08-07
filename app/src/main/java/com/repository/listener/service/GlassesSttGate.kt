package com.repository.listener.service

/**
 * What the phone does about a glasses audio session, given who is recognising
 * the speech.
 *
 * A single value with named questions rather than a bare boolean checked in five
 * places, because the five answers are NOT all the same. In particular the PCM
 * buffer keeps running in local mode: that buffer is what the failure fallback
 * batch-transcribes, so switching it off would turn a recoverable local failure
 * into a lost utterance.
 *
 * Defaults to remote. An older glasses build, or a reconnect that lands before
 * the mode announcement, must behave exactly as it does today -- failing the
 * other way would leave nobody transcribing at all.
 */
data class GlassesSttGate(val localMode: Boolean) {

    /** The transcriber WebSocket. Not opened locally: no round trip, no audio off-device. */
    fun shouldOpenTranscriberStream(): Boolean = !localMode

    /**
     * The phone's Silero VAD. In local mode endpointing happens on the glasses,
     * and running both would give two opinions about when the wearer stopped --
     * the phone's would finalize a session the glasses still own.
     */
    fun shouldFeedPhoneVad(): Boolean = !localMode

    /**
     * The no-speech watchdog fires when the phone's VAD has heard nothing. In
     * local mode it hears nothing by design, so arming it would cancel every
     * healthy local session.
     */
    fun shouldArmNoSpeechWatchdog(): Boolean = !localMode

    /**
     * ALWAYS true. This buffer is the fallback: on a local failure the phone
     * batch-transcribes it. Gating it on remote mode is the one change here that
     * would silently lose the wearer's words.
     */
    fun shouldBufferPcm(): Boolean = true

    /** Local is finals-only in v1; partials are full re-decodes and would jump. */
    fun shouldForwardPartials(): Boolean = !localMode

    companion object {
        fun default(): GlassesSttGate = GlassesSttGate(localMode = false)
    }
}
