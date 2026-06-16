package com.repository.listener.ui.rc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the /resume conversation picker.
 *
 * Flow:
 *  1. Start a session, send a memorable message so a transcript exists.
 *  2. Confirm the conversation is enumerable via listConversations (the same
 *     path the picker uses: orchestrator -> pc-agent -> remote-session
 *     list-sessions).
 *  3. Invoke the in-session /resume command, assert the picker lists the
 *     conversation, pick it, and assert the activity rebinds + backfills the
 *     prior message into the view.
 *
 * Requires live orchestrator + pc-agent.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RcResumeE2ETest {

    companion object {
        private const val WORK_DIR = "/tmp/rc-resume-test"
        private const val PKG = "com.repository.listener"
        private const val REPLY_TIMEOUT_MS = 300_000L
        private const val HOLD_MS = 3_000L
        // A distinctive marker so we can recognize this conversation later.
        private const val MARKER = "PINEAPPLE-RESUME-MARKER"
    }

    private lateinit var device: UiDevice
    private lateinit var harness: RcChatHarness
    private var sessionId: String = ""

    @Before
    fun setup() {
        ScreenshotHelper.resetCounter()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        harness = RcChatHarness(device)
        // NOTE: WORK_DIR must already exist on the pc-agent host (the PC), not
        // the phone. The test runner creates it on the PC before invoking.
        sessionId = harness.launchRcSession(WORK_DIR, permissionMode = "bypassAll")
    }

    @After
    fun teardown() {
        try { harness.endSessionAndReturn() } catch (_: Throwable) {}
        try { harness.stopRemoteSession(sessionId) } catch (_: Throwable) {}
    }

    @Test
    fun resumePickerListsAndReopensConversation() {
        // ============================================================
        // TURN 1: seed the conversation with a recognizable exchange.
        // ============================================================
        harness.sendMessage("Reply with exactly this token and nothing else: $MARKER")
        ScreenshotHelper.take("01_seed_sent")
        val reply = harness.awaitAssistantReply(matchSubstring = MARKER, timeoutMs = REPLY_TIMEOUT_MS)
        assertNotNull("Seed reply should contain the marker", reply)
        ScreenshotHelper.take("02_seed_reply")
        Thread.sleep(HOLD_MS)

        // ============================================================
        // Verify enumeration: the just-created conversation must show up in
        // the resume list for this folder, keyed by its sessionId.
        // ============================================================
        val convos = harness.listConversations(WORK_DIR)
        val match = convos.firstOrNull { it.sessionId == sessionId }
        assertNotNull(
            "listConversations should include the active session $sessionId; got ${convos.map { it.sessionId }}",
            match
        )

        // ============================================================
        // End the session, then start a DIFFERENT fresh session in the same
        // folder so /resume has something to switch away from.
        // ============================================================
        harness.endSessionAndReturn()
        harness.stopRemoteSession(sessionId)
        Thread.sleep(2_000)

        val freshSessionId = harness.launchRcSession(WORK_DIR, permissionMode = "bypassAll")
        assertTrue("Fresh session id should differ", freshSessionId != sessionId)
        ScreenshotHelper.take("03_fresh_session")
        Thread.sleep(1_000)

        // ============================================================
        // Open the in-session /resume picker.
        // ============================================================
        harness.sendSlashCommand("/resume")
        // Picker is a bottom sheet titled "Resume Conversation".
        val picker = device.wait(Until.hasObject(By.textContains("Resume Conversation")), 15_000L)
        assertTrue("Resume picker should appear", picker)
        ScreenshotHelper.take("04_resume_picker")
        Thread.sleep(HOLD_MS)

        // The seeded conversation should be listed (by its label = first prompt).
        val convoRow = device.wait(
            Until.findObject(By.textContains("token and nothing else")),
            10_000L
        ) ?: device.wait(Until.findObject(By.textContains(MARKER)), 5_000L)
        assertNotNull("Seeded conversation should be listed in the picker", convoRow)
        ScreenshotHelper.take("05_conversation_listed")

        // ============================================================
        // Pick it: activity relaunches on the resumed id and backfills the
        // transcript. The marker reply from TURN 1 must reappear.
        // ============================================================
        convoRow!!.click()
        Thread.sleep(2_000)
        ScreenshotHelper.take("06_resumed")

        // The transcript backfill renders the prior marker reply.
        val restored = device.wait(Until.findObject(By.textContains(MARKER)), 30_000L)
        assertNotNull("Resumed view should backfill the prior marker message", restored)
        ScreenshotHelper.take("07_transcript_restored")
        Thread.sleep(HOLD_MS)
    }
}
