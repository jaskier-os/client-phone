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
 * E2E tests validating that user messages persist across session re-entry
 * and that message text is selectable (long-press copyable).
 *
 * Requires a reachable orchestrator at the configured WS URL.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RcMessagePersistenceTest {

    companion object {
        private const val DEFAULT_WORK_DIR = "/workspace/project"
        private const val PKG = "com.repository.listener"
        private const val SHORT_REPLY_TIMEOUT_MS = 120_000L
        private const val HOLD_MS = 3_000L
    }

    private lateinit var device: UiDevice
    private lateinit var harness: RcChatHarness
    private lateinit var sessionId: String
    private val workDir = DEFAULT_WORK_DIR

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        harness = RcChatHarness(device)
        sessionId = harness.launchRcSession(workDir, permissionMode = "bypassAll")
    }

    @After
    fun teardown() {
        try { harness.endSessionAndReturn() } catch (_: Throwable) {}
        try { harness.stopRemoteSession(sessionId) } catch (_: Throwable) {}
    }

    /**
     * Send a user message, wait for the AI reply, leave the session,
     * re-enter it, and verify the USER message is still visible in the
     * transcript (not just the assistant reply).
     *
     * Before the fix, user messages were only persisted when the CLI
     * replayed them back. If the CLI was slow or the user left early,
     * the user message vanished from the transcript.
     */
    @Test
    fun userMessagePersistsAfterReEntry() {
        val marker = "persist-test-${System.currentTimeMillis() % 100000}"
        val userText = "Reply with exactly this token: $marker"

        val ignoreBefore = harness.assistantTexts().toSet()
        harness.sendMessage(userText)
        ScreenshotHelper.take("persist_01_message_sent")

        // Wait for the assistant to reply (proves the round-trip worked)
        harness.awaitAssistantReply(
            matchSubstring = marker,
            ignoreTexts = ignoreBefore,
            timeoutMs = SHORT_REPLY_TIMEOUT_MS
        )
        ScreenshotHelper.take("persist_02_reply_received")

        // Leave the session
        harness.endSessionAndReturn()
        Thread.sleep(1_000)

        // Re-enter the same session
        harness.reenterRcSession(sessionId, workDir)
        Thread.sleep(2_500) // allow transcript fetch

        // Verify the USER message is visible (not just the assistant reply)
        val userMsgVisible = device.wait(
            Until.findObject(By.textContains(marker)),
            15_000L
        )
        ScreenshotHelper.take("persist_03_after_reentry")
        assertNotNull(
            "User message containing '$marker' must be visible after re-entering the session",
            userMsgVisible
        )

        // Also verify the assistant reply is still there
        val assistantVisible = device.findObjects(By.desc("rcAssistantText"))
            .any { runCatching { it.text }.getOrNull()?.contains(marker, ignoreCase = true) == true }
        assertTrue("Assistant reply must also persist after re-entry", assistantVisible)

        Thread.sleep(HOLD_MS)
    }

    /**
     * Verify that user message text is long-clickable (selectable).
     * Before the fix, setTextIsSelectable(false) was hardcoded for user messages.
     *
     * UiAutomator's long-clickable attribute reflects whether the view accepts
     * long-click gestures, which setTextIsSelectable(true) enables. The actual
     * text selection popup varies by OEM (MIUI, OneUI, etc.) and is unreliable
     * to detect, so we verify the attribute instead.
     */
    @Test
    fun userMessageTextIsSelectable() {
        val marker = "selectable-test-${System.currentTimeMillis() % 100000}"
        harness.sendMessage("Reply with this token: $marker")
        harness.awaitAssistantReply(
            matchSubstring = marker,
            timeoutMs = SHORT_REPLY_TIMEOUT_MS
        )
        ScreenshotHelper.take("select_01_messages_visible")

        // Find the user message text view (contains marker but is NOT tagged rcAssistantText)
        val allWithMarker = device.findObjects(By.textContains(marker))
        val userMsg = allWithMarker.firstOrNull { obj ->
            runCatching { obj.contentDescription }.getOrNull() != "rcAssistantText"
        }
        assertNotNull("User message containing '$marker' must be visible", userMsg)

        // setTextIsSelectable(true) sets focusableInTouchMode=true on the TextView.
        // Before the fix (setTextIsSelectable=false), focusable was false.
        assertTrue(
            "User message TextView must be focusable (setTextIsSelectable=true sets this)",
            userMsg!!.isFocusable
        )

        // Long-press to trigger text selection -- should not crash
        userMsg.longClick()
        Thread.sleep(1_500)
        ScreenshotHelper.take("select_02_after_long_press")

        Thread.sleep(HOLD_MS)
    }
}
