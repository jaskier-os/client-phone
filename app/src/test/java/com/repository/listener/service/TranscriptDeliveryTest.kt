package com.repository.listener.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Plan task 3.4 -- the two extracted transcript-delivery tails, covering the two
 * blocking defects the audit found (F3 and F4).
 *
 * F3: the blank-is-cancel emission lives in finishTelegramVoiceRecording's EARLY
 * "no speech detected" return (sendGlassesUserText("tg_voice","") plus
 * notifReplyId = null), ABOVE the point the plan proposed splitting at. A naive
 * extraction sends NOTHING for blank text and never clears notifReplyId, so a
 * notification reply hangs in SENDING forever.
 *
 * F4: finishGlassesRecording's tail reads streamText.isBlank() and
 * glassesAudioState == SENDING. In local mode the phone never enters
 * CONFIRMING/SENDING (no phone VAD, no transcriber stream), so RESPONDING never
 * fires and the glasses hang in LISTENING.
 *
 * ListenerService is a 10k-line Android Service and cannot be instantiated on the
 * JVM, so the decision logic lives in TranscriptDelivery behind a port. These
 * tests drive the REAL logic; the service just supplies a port implementation.
 */
class TranscriptDeliveryTest {

    private class Recorder : TranscriptDelivery.Port {
        val logs = ArrayList<String>()
        val registeredIds = ArrayList<String>()
        var armedRetry: String? = null
        var sendResult: OrchestratorSend = OrchestratorSend.Sent("orch-req-42")
        /** onSend runs inside sendToOrchestrator, to simulate mid-send races. */
        var onSend: (() -> Unit)? = null
        val userTexts = ArrayList<Pair<String, String>>()
        val states = ArrayList<GlassesAudioState>()
        val sentToOrchestrator = ArrayList<String>()
        var notifReplyId: String? = "notif-1"
        var glassesAudioState: GlassesAudioState = GlassesAudioState.LISTENING
        var orchestratorRequestId: String? = "orch-req-42"
        var sendSucceeds = true
        var dismissed = false
        var strippedWith = ArrayList<String>()

        override fun sendGlassesUserText(requestId: String, text: String) {
            userTexts += requestId to text
        }

        override fun clearNotifReplyIdIfStill(expected: String?) {
            if (notifReplyId == expected) notifReplyId = null
        }

        override fun currentGlassesState(): GlassesAudioState = glassesAudioState

        override fun setGlassesState(s: GlassesAudioState, reason: String) {
            glassesAudioState = s
            states += s
        }

        override suspend fun sendToOrchestrator(text: String): OrchestratorSend {
            sentToOrchestrator += text
            onSend?.invoke()
            return if (!sendSucceeds) OrchestratorSend.Failed else sendResult
        }

        override fun registerRequestId(requestId: String) { registeredIds += requestId }

        override fun armRetry(text: String) { armedRetry = text }

        override fun log(msg: String) { logs += msg }

        override fun stripWakeWords(text: String): String {
            strippedWith += text
            return text.replace(Regex("сиренев\\S*", RegexOption.IGNORE_CASE), "").trim()
        }

        override fun dismissSession() { dismissed = true }
    }

    // ---------------- F3: the blank-is-cancel contract ----------------

    @Test
    fun blankTelegramTranscriptOnANotifReplyEmitsTheCancelCueAndClearsTheId() {
        val p = Recorder()
        TranscriptDelivery(p).deliverTelegramVoiceTranscript("", notifReplyId = "notif-1")
        assertEquals(
            "a blank final MUST send the empty user text -- it is the glasses' cancel cue",
            listOf("tg_voice" to ""), p.userTexts
        )
        assertNull("notifReplyId must be cleared or the reply hangs in SENDING", p.notifReplyId)
    }

    @Test
    fun blankTelegramTranscriptWithoutNotifReplySendsNoStrayTextAndLeavesOtherSessionsAlone() {
        val p = Recorder()
        // A plain Telegram chat voice message (no notif reply). It must not emit
        // the cancel cue, and -- because it does not own notifReplyId -- it must
        // NOT clear a reply session that belongs to someone else.
        p.notifReplyId = "notif-owned-by-another-session"
        TranscriptDelivery(p).deliverTelegramVoiceTranscript("", notifReplyId = null)
        assertTrue("no user text should be sent outside a notif reply", p.userTexts.isEmpty())
        assertEquals(
            "a non-notif-reply delivery must not clear another session's id",
            "notif-owned-by-another-session", p.notifReplyId
        )
    }

    @Test
    fun blankTelegramTranscriptClearsItsOwnNotifReplyId() {
        val p = Recorder()
        p.notifReplyId = "notif-1"
        TranscriptDelivery(p).deliverTelegramVoiceTranscript("", notifReplyId = "notif-1")
        assertNull("its own id must be cleared or the next session is wedged", p.notifReplyId)
    }

    @Test
    fun whitespaceOnlyTranscriptIsTreatedAsBlank() {
        val p = Recorder()
        TranscriptDelivery(p).deliverTelegramVoiceTranscript("   ", notifReplyId = "notif-1")
        assertEquals(listOf("tg_voice" to ""), p.userTexts)
        assertNull(p.notifReplyId)
    }

    @Test
    fun nonBlankTelegramTranscriptIsSentAndClearsTheNotifReplyId() {
        val p = Recorder()
        TranscriptDelivery(p).deliverTelegramVoiceTranscript("привет", notifReplyId = "notif-1")
        assertEquals(listOf("tg_voice" to "привет"), p.userTexts)
        assertNull(p.notifReplyId)
    }

    @Test
    fun nonBlankTelegramTranscriptWithoutNotifReplyIsStillDelivered() {
        val p = Recorder()
        TranscriptDelivery(p).deliverTelegramVoiceTranscript("x", notifReplyId = null)
        assertEquals(listOf("tg_voice" to "x"), p.userTexts)
    }

    // ---------------- F4: the assistant state machine ----------------

    @Test
    fun localTranscriptStartingFromListeningReachesResponding() {
        val p = Recorder()
        p.glassesAudioState = GlassesAudioState.LISTENING
        runBlocking { TranscriptDelivery(p).deliverGlassesTranscript(
            "привет", hadStreamPreview = false, source = TranscriptSource.LOCAL
        ) }
        assertEquals(
            "local mode must drive SENDING then RESPONDING; otherwise the glasses hang in LISTENING",
            GlassesAudioState.RESPONDING, p.glassesAudioState
        )
        assertTrue(p.states.contains(GlassesAudioState.SENDING))
    }

    @Test
    fun remoteTranscriptAlreadyInSendingReachesResponding() {
        val p = Recorder()
        // finishGlassesRecording already set SENDING before calling the tail.
        p.glassesAudioState = GlassesAudioState.SENDING
        runBlocking { TranscriptDelivery(p).deliverGlassesTranscript(
            "привет", hadStreamPreview = true, source = TranscriptSource.REMOTE
        ) }
        assertEquals(GlassesAudioState.RESPONDING, p.glassesAudioState)
    }

    @Test
    fun cancelDuringSendSuppressesTheRespondingTransition() {
        val p = Recorder()
        p.glassesAudioState = GlassesAudioState.LISTENING
        // The user cancels while the request is in flight: the orchestrator send
        // moves the state off SENDING, so RESPONDING must NOT fire.
        p.sendResult = OrchestratorSend.Sent("orch-1")
        p.onSend = { p.glassesAudioState = GlassesAudioState.IDLE }  // cancelled mid-send
        runBlocking {
            TranscriptDelivery(p).deliverGlassesTranscript(
                "привет", hadStreamPreview = false, source = TranscriptSource.LOCAL
            )
        }
        assertEquals(
            "a cancel during send must suppress RESPONDING",
            GlassesAudioState.IDLE, p.glassesAudioState
        )
    }

    // ---- the two failure modes the audit found were unmodelled ----

    @Test
    fun sentWithoutARequestIdFailsClosedInsteadOfHangingInSending() {
        val p = Recorder()
        p.glassesAudioState = GlassesAudioState.LISTENING
        p.sendResult = OrchestratorSend.SentWithoutId
        runBlocking {
            TranscriptDelivery(p).deliverGlassesTranscript(
                "привет", hadStreamPreview = false, source = TranscriptSource.LOCAL
            )
        }
        assertEquals(
            "no requestId means the reply can never be correlated; must not sit in SENDING",
            GlassesAudioState.IDLE, p.glassesAudioState
        )
        assertTrue(p.dismissed)
    }

    @Test
    fun aFailedSendArmsTheReconnectRetryWithTheStrippedText() {
        val p = Recorder()
        p.glassesAudioState = GlassesAudioState.LISTENING
        p.sendSucceeds = false
        runBlocking {
            TranscriptDelivery(p).deliverGlassesTranscript(
                "сиреневый привет", hadStreamPreview = false, source = TranscriptSource.LOCAL
            )
        }
        assertEquals("привет", p.armedRetry)
        assertTrue(p.glassesAudioState != GlassesAudioState.RESPONDING)
    }

    @Test
    fun theOrchestratorRequestIdIsRegisteredSoRepliesRouteBackToTheGlasses() {
        val p = Recorder()
        p.sendResult = OrchestratorSend.Sent("orch-req-42")
        runBlocking {
            TranscriptDelivery(p).deliverGlassesTranscript(
                "привет", hadStreamPreview = true, source = TranscriptSource.REMOTE
            )
        }
        assertEquals(listOf("orch-req-42"), p.registeredIds)
    }

    @Test
    fun aLateDeliveryDoesNotClearANewerNotifReplyId() {
        val p = Recorder()
        // This delivery belongs to notif-1, but by the time it completes a NEW
        // reply (notif-2) owns the field. Clearing unconditionally would wedge it.
        p.notifReplyId = "notif-2"
        TranscriptDelivery(p).deliverTelegramVoiceTranscript("привет", notifReplyId = "notif-1")
        assertEquals("a stale delivery must not clear a newer session", "notif-2", p.notifReplyId)
    }

    @Test
    fun requestIdIsTheOrchestratorsNotALiteral() {
        val p = Recorder()
        p.orchestratorRequestId = "orch-req-42"
        runBlocking { TranscriptDelivery(p).deliverGlassesTranscript(
            "привет", hadStreamPreview = true, source = TranscriptSource.REMOTE
        ) }
        assertTrue(
            "the user text must be keyed by the orchestrator requestId",
            p.userTexts.contains("orch-req-42" to "привет")
        )
    }

    @Test
    fun pendingPreviewIsSentOnlyWhenNoStreamPreviewWasShown() {
        val withoutPreview = Recorder()
        runBlocking { TranscriptDelivery(withoutPreview).deliverGlassesTranscript(
            "привет", hadStreamPreview = false, source = TranscriptSource.LOCAL
        ) }
        assertTrue(
            "with no live partial the glasses have shown nothing, so send the pending preview",
            withoutPreview.userTexts.any { it.first == "pending" }
        )

        val withPreview = Recorder()
        withPreview.glassesAudioState = GlassesAudioState.SENDING
        runBlocking { TranscriptDelivery(withPreview).deliverGlassesTranscript(
            "привет", hadStreamPreview = true, source = TranscriptSource.REMOTE
        ) }
        assertTrue(
            "a live partial was already shown, so no duplicate pending preview",
            withPreview.userTexts.none { it.first == "pending" }
        )
    }

    @Test
    fun stripWakeWordsIsAppliedOnTheAssistantPath() {
        val p = Recorder()
        runBlocking { TranscriptDelivery(p).deliverGlassesTranscript(
            "сиреневый привет", hadStreamPreview = false, source = TranscriptSource.LOCAL
        ) }
        assertEquals(listOf("привет"), p.sentToOrchestrator)
    }

    @Test
    fun blankAssistantTranscriptReturnsToIdleAndDismissesRatherThanHanging() {
        val p = Recorder()
        p.glassesAudioState = GlassesAudioState.LISTENING
        runBlocking { TranscriptDelivery(p).deliverGlassesTranscript(
            "", hadStreamPreview = false, source = TranscriptSource.LOCAL
        ) }
        assertEquals(GlassesAudioState.IDLE, p.glassesAudioState)
        assertTrue(p.dismissed)
        assertTrue("nothing should reach the orchestrator", p.sentToOrchestrator.isEmpty())
    }

    @Test
    fun transcriptThatIsOnlyAWakeWordIsTreatedAsBlank() {
        val p = Recorder()
        p.glassesAudioState = GlassesAudioState.LISTENING
        // The local model transcribes the wake word too; after stripping there is
        // nothing left, so this must not become an empty orchestrator request.
        runBlocking { TranscriptDelivery(p).deliverGlassesTranscript(
            "сиреневый", hadStreamPreview = false, source = TranscriptSource.LOCAL
        ) }
        assertTrue(p.sentToOrchestrator.isEmpty())
        assertEquals(GlassesAudioState.IDLE, p.glassesAudioState)
    }

    @Test
    fun aFailedOrchestratorSendDoesNotClaimResponding() {
        val p = Recorder()
        p.glassesAudioState = GlassesAudioState.LISTENING
        p.sendSucceeds = false
        runBlocking { TranscriptDelivery(p).deliverGlassesTranscript(
            "привет", hadStreamPreview = false, source = TranscriptSource.LOCAL
        ) }
        assertTrue(
            "RESPONDING must not fire when the send failed",
            p.glassesAudioState != GlassesAudioState.RESPONDING
        )
    }
}
