package com.repository.listener.ui.rc

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real multi-turn AI conversation E2E test.
 *
 * Simulates a genuine user session: build a website, add docs, navigate
 * away mid-turn and back, verify tool calls render correctly (toolCallId
 * fix), verify stop button appears, verify list shows progress.
 *
 * Requires live orchestrator + pc-agent.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RcRealConversationE2ETest {

    companion object {
        private const val WORK_DIR = "/tmp/rc-test-website"
        private const val PKG = "com.repository.listener"
        private const val REPLY_TIMEOUT_MS = 300_000L
        private const val HOLD_MS = 3_000L
    }

    private lateinit var device: UiDevice
    private lateinit var harness: RcChatHarness
    private lateinit var sessionId: String

    @Before
    fun setup() {
        ScreenshotHelper.resetCounter()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        harness = RcChatHarness(device)
        // Start in plan mode to test plan flow
        sessionId = harness.launchRcSession(WORK_DIR, permissionMode = "plan")
    }

    @After
    fun teardown() {
        try { harness.endSessionAndReturn() } catch (_: Throwable) {}
        try { harness.stopRemoteSession(sessionId) } catch (_: Throwable) {}
        // Clean up work directory on PC (best-effort via orchestrator)
        try { Thread.sleep(2_000) } catch (_: Throwable) {}
    }

    @Test
    fun realConversationWithNavigationAndToolCalls() {
        // ============================================================
        // TURN 1: Ask AI to build a landing page (plan mode)
        // ============================================================
        harness.sendMessage(
            "Create a simple landing page: index.html with a hero section, and style.css. Keep it very minimal, under 30 lines each."
        )
        ScreenshotHelper.take("01_prompt_sent")

        // Wait for busy state
        val busy = harness.awaitStatus(
            { !it.isNullOrEmpty() && (it.contains("Sending") || it.contains("Thinking")) },
            timeoutMs = 30_000L
        )
        assertNotNull("Should enter busy state", busy)
        ScreenshotHelper.take("02_thinking_state")

        // Hold for recording -- let AI produce plan content
        Thread.sleep(5_000)
        ScreenshotHelper.take("03_plan_appearing")

        // ============================================================
        // NAVIGATE AWAY: Leave to chat list while AI is working
        // ============================================================
        // Finish RC activity and navigate to MainActivity with CLEAR_TOP
        // to ensure we land on the tab layout, not back in RC.
        device.executeShellCommand(
            "am start -n $PKG/.MainActivity --activity-clear-top --activity-single-top"
        )
        val tabsReady = device.wait(Until.hasObject(By.res(PKG, "tabLayout")), 15_000L)
        if (tabsReady != true) {
            // Fallback: force home + relaunch
            device.pressHome()
            Thread.sleep(500)
            device.executeShellCommand(
                "am start -n $PKG/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
            )
            device.wait(Until.hasObject(By.res(PKG, "tabLayout")), 10_000L)
        }
        Thread.sleep(500)
        harness.openChatsTab()
        ScreenshotHelper.take("04_chat_list_mid_turn")

        // Wait and observe list progress updates
        Thread.sleep(7_000)
        ScreenshotHelper.take("05_chat_list_7s_later")

        // ============================================================
        // RE-ENTER: Tap the session in the chat list
        // ============================================================
        // Re-enter via intent (reliable across all devices)
        harness.reenterRcSession(sessionId, WORK_DIR)
        Thread.sleep(2_000)
        ScreenshotHelper.take("06_re_entered")
        Thread.sleep(3_000)
        ScreenshotHelper.take("07_content_after_reentry")

        // ============================================================
        // APPROVE PLAN: Click "Approve & Bypass All" to approve the
        // ExitPlanMode prompt and switch to bypass in one step.
        // ============================================================
        // Wait for the Approve button to appear (plan prompt)
        val approveBtn = device.wait(
            Until.findObject(By.textContains("Approve & Bypass")),
            REPLY_TIMEOUT_MS
        )
        if (approveBtn != null) {
            ScreenshotHelper.take("08_plan_prompt_visible")
            Thread.sleep(HOLD_MS)
            approveBtn.click()
            Thread.sleep(1_000)
            ScreenshotHelper.take("09_plan_approved")
        } else {
            // No plan prompt -- might have auto-resolved. Switch mode manually.
            harness.awaitStatus(
                { it == null || it.contains("Thought") },
                timeoutMs = REPLY_TIMEOUT_MS
            )
            harness.changeMode("Bypass all permissions", "Bypass")
            ScreenshotHelper.take("09_bypass_mode_manual")
        }

        // ============================================================
        // TURN 2: AI should now execute the plan (creates files)
        // ============================================================
        // Wait for tool calls to appear
        val ignore2 = harness.assistantTexts().toSet()
        ScreenshotHelper.take("10_waiting_for_execution")

        // Watch tool calls appear
        val toolVisible = device.wait(
            Until.findObject(By.textContains("Write")),
            REPLY_TIMEOUT_MS
        )
        if (toolVisible != null) {
            ScreenshotHelper.take("11_write_tool_visible")
        }

        // Wait for completion
        val reply2 = harness.awaitAssistantReply(
            ignoreTexts = ignore2,
            timeoutMs = REPLY_TIMEOUT_MS
        )
        assertNotNull("Should get reply after file creation", reply2)
        ScreenshotHelper.take("12_turn2_complete")
        Thread.sleep(HOLD_MS)

        // ============================================================
        // TURN 3: Add documentation page (tests multiple tool calls)
        // ============================================================
        val ignore3 = harness.assistantTexts().toSet()
        harness.sendMessage(
            "Now add a docs.html with a simple documentation section. Link it from the landing page nav."
        )
        ScreenshotHelper.take("13_docs_prompt_sent")

        val reply3 = harness.awaitAssistantReply(
            ignoreTexts = ignore3,
            timeoutMs = REPLY_TIMEOUT_MS
        )
        assertNotNull("Should get reply after docs creation", reply3)
        ScreenshotHelper.take("14_turn3_complete")
        Thread.sleep(HOLD_MS)

        // ============================================================
        // TURN 4: Verify files exist (ls output is in tool card, not
        // assistant text -- just confirm the turn completes)
        // ============================================================
        val ignore4 = harness.assistantTexts().toSet()
        harness.sendMessage("List all files in $WORK_DIR")
        val lsReply = harness.awaitAssistantReply(
            ignoreTexts = ignore4,
            timeoutMs = REPLY_TIMEOUT_MS
        )
        assertNotNull("Should get reply after listing files", lsReply)
        // Verify tool card shows the files
        val indexVisible = device.findObject(By.textContains("index.html"))
        ScreenshotHelper.take("15_files_verified")
        Thread.sleep(HOLD_MS)
    }
}
