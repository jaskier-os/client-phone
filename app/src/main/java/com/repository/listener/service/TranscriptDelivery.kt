package com.repository.listener.service

/**
 * The glasses audio session state machine.
 *
 * Extracted from ListenerService so the transcript-delivery logic that drives it
 * can be tested on the JVM. The values and their meaning are unchanged.
 *
 * CONFIRMING only ever occurs on the REMOTE path: it is the 2 s window the phone
 * opens after ITS OWN VAD detects end-of-speech, during which the user may
 * cancel. In local mode end-of-speech is detected on the glasses and the text is
 * already transcribed by the time the phone hears about it, so local runs
 * LISTENING -> SENDING -> RESPONDING with no CONFIRMING state.
 */
enum class GlassesAudioState { IDLE, LISTENING, CONFIRMING, SENDING, RESPONDING }

/** Which transcriber produced the text. */
enum class TranscriptSource { REMOTE, LOCAL }

/**
 * Outcome of handing a request to the orchestrator. The three cases are kept
 * DISTINCT on purpose: collapsing "sent but no requestId" into "failed" hid a
 * real hang, because the original code did nothing at all in that case and left
 * the glasses in SENDING forever.
 */
sealed class OrchestratorSend {
    /** Accepted, and we have the requestId that correlates the reply. */
    data class Sent(val requestId: String) : OrchestratorSend()

    /** Accepted, but no requestId came back -- we cannot correlate the reply. */
    object SentWithoutId : OrchestratorSend()

    /** Not accepted (WS down). The caller arms a reconnect retry. */
    object Failed : OrchestratorSend()
}

/**
 * The text-consuming tails of the two glasses finalizers, lifted out of
 * ListenerService so that the local (on-glasses STT) and remote (WebSocket
 * transcriber) paths are STRUCTURALLY the same code rather than two
 * implementations that are merely meant to agree.
 *
 * This class is the ONLY implementation: ListenerService's private deliver*
 * methods are thin wrappers that call it through a Port built over phoneBtHost /
 * orchestratorClient. If it were a parallel copy, its tests would prove nothing
 * about shipped behaviour.
 *
 * Two defects this exists to prevent, both verified in the original source:
 *
 * F3 -- the blank-is-cancel emission did NOT live in the tail. It lived in
 * finishTelegramVoiceRecording's earlier "no speech detected" early-return, which
 * sent sendGlassesUserText("tg_voice", "") and cleared notifReplyId. The tail
 * only logged for blank text. Routing a local empty final through a naive
 * extraction would send NOTHING and never clear notifReplyId, hanging the
 * notification reply in SENDING forever.
 *
 * F4 -- the assistant tail read `streamText.isBlank()` and
 * `glassesAudioState == SENDING`, neither of which the local path sets: local
 * mode never enters CONFIRMING or SENDING, so RESPONDING never fired and the
 * glasses hung in LISTENING. Here the preview decision is an explicit parameter
 * and the state transition is DRIVEN rather than observed.
 */
class TranscriptDelivery(private val port: Port) {

    /** Everything the tails touch in ListenerService, injected for testability. */
    interface Port {
        fun sendGlassesUserText(requestId: String, text: String)

        /**
         * Clear the notification-reply marker, but ONLY if it still holds the id
         * this delivery was started for. Delivery can run many seconds after the
         * session ended (batch transcription, the 15 s photo fetch), by which time
         * a NEW notification reply may already own the field -- clearing it
         * unconditionally would wedge that new session.
         */
        fun clearNotifReplyIdIfStill(expected: String?)

        fun currentGlassesState(): GlassesAudioState
        fun setGlassesState(s: GlassesAudioState, reason: String)

        /** Suspends: the real send performs a photo fetch and a WS round trip. */
        suspend fun sendToOrchestrator(text: String): OrchestratorSend

        /** Remember the requestId so replies route back to the glasses. */
        fun registerRequestId(requestId: String)

        /** Arm the reconnect retry after a failed send. */
        fun armRetry(text: String)

        fun stripWakeWords(text: String): String
        fun dismissSession()
        fun log(msg: String)
    }

    /**
     * Telegram voice / notification reply / RC voice. All three share the
     * "tg_voice" requestId; the glasses disambiguate purely by focusState.
     *
     * @param text the final transcript. "" is MEANINGFUL: it is an explicit empty
     *   final, i.e. a CANCEL, not "nothing happened".
     * @param notifReplyId the notification id this delivery belongs to, or null
     *   when this was a plain Telegram chat voice message.
     */
    fun deliverTelegramVoiceTranscript(text: String, notifReplyId: String?) {
        val isNotifReply = notifReplyId != null
        if (text.isBlank()) {
            // Signal the glasses so a notification reply does not hang forever in
            // SENDING / NOTIFICATION_REPLY. Blank user text is the glasses' cue to
            // cancel (it treats blank as "nothing captured").
            if (isNotifReply) {
                port.log("[NREPLY] empty transcript -> signalling glasses to cancel notif reply")
                port.sendGlassesUserText(TAG_TG_VOICE, "")
            } else {
                port.log("Telegram voice: empty transcription result")
            }
            port.clearNotifReplyIdIfStill(notifReplyId)
            return
        }
        port.log("Telegram voice final text: ${text.take(80)}")
        port.sendGlassesUserText(TAG_TG_VOICE, text)
        port.clearNotifReplyIdIfStill(notifReplyId)
    }

    /**
     * AI assistant and the wake-word follow-on utterance.
     *
     * @param hadStreamPreview whether a live partial has ALREADY been shown on the
     *   glasses. Remote passes streamText.isNotBlank(); local is always false
     *   because local mode is finals-only, so the glasses have shown no text yet
     *   and the "pending" preview must be sent.
     * @param source which transcriber produced this. LOCAL must drive the state
     *   machine itself (see F4 above).
     */
    suspend fun deliverGlassesTranscript(
        rawText: String,
        hadStreamPreview: Boolean,
        source: TranscriptSource,
    ) {
        val text = port.stripWakeWords(rawText)
        if (text.isBlank()) {
            // Includes the case where the transcript was ONLY a wake word: the
            // local model transcribes it too, and an empty request must not reach
            // the orchestrator.
            port.log("Glasses transcription empty after wake word filter (raw='$rawText')")
            port.log("[STT] delivery path=$source DISMISSED: blank after wake-word strip (raw chars=${rawText.length})")
            port.setGlassesState(GlassesAudioState.IDLE, "empty transcription")
            port.dismissSession()
            return
        }

        port.log("Glasses transcription ($source): $text")
        // Which delivery path this text took. The local path is structurally
        // different (it never passes through CONFIRMING/SENDING) and that
        // difference is what once hung the glasses in LISTENING, so name it.
        port.log("[STT] delivery path=$source chars=${text.length} hadPreview=$hadStreamPreview -> assistant")
        if (!hadStreamPreview) port.sendGlassesUserText(TAG_PENDING, text)

        // The remote path already entered SENDING in finishGlassesRecording. The
        // local path never passes through CONFIRMING/SENDING, so it must enter
        // SENDING here -- otherwise the guard below is false and the glasses hang
        // in LISTENING.
        if (source == TranscriptSource.LOCAL) {
            port.setGlassesState(GlassesAudioState.SENDING, "local transcript")
        }

        when (val result = port.sendToOrchestrator(text)) {
            is OrchestratorSend.Sent -> {
                port.registerRequestId(result.requestId)
                port.log("[STT] sent to orchestrator requestId=${result.requestId} path=$source")
                port.sendGlassesUserText(result.requestId, text)
                // Only transition if the session was not cancelled during the
                // send. Evaluated against the same state machine on both paths.
                if (port.currentGlassesState() == GlassesAudioState.SENDING) {
                    port.setGlassesState(GlassesAudioState.RESPONDING, "request sent")
                }
            }

            OrchestratorSend.SentWithoutId -> {
                // The reply can never be correlated back to this session, so
                // waiting for it would strand the glasses in SENDING forever.
                // Fail closed instead of hanging.
                port.log("Glasses send returned no requestId; cannot correlate reply, dismissing")
                port.setGlassesState(GlassesAudioState.IDLE, "no request id")
                port.dismissSession()
            }

            OrchestratorSend.Failed -> {
                // WS dead at this moment. Arm a retry for the next reconnect. Do
                // NOT claim RESPONDING.
                port.armRetry(text)
                port.log("Glasses send failed (WS down); armed retry on next reconnect")
            }
        }
    }

    companion object {
        const val TAG_TG_VOICE = "tg_voice"
        const val TAG_PENDING = "pending"
    }
}
