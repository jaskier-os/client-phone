package com.repository.listener.ui.rc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device e2e for reflecting MID-TURN state when the phone opens a chat whose
 * CLI is currently running a turn on the PC.
 *
 * Two states that used to be invisible on the phone until the tool finished:
 *   1. A tool executing at the moment of open -> the phone must show a running
 *      row, not "Completed".
 *   2. The CLI paused on a permission/question prompt -> the phone must show the
 *      live prompt (Approve/Reject), and answering it from the phone must
 *      unblock the PC. Before the fix the phone rendered "Question: Completed".
 *
 * The chat is opened with reenterRcSession (NO startSession), so the only thing
 * that attaches is the orchestrator's adopt on rc_transcript_request -- this is
 * the same adopt path, exercised while the PC session is mid-turn.
 *
 * REAL SYSTEM ONLY: real interactive CLI in a pty ([PcTuiDriver]), real
 * pc-agent, real deployed orchestrator, phone over its WebSocket.
 *
 * Preconditions on the PC:
 *   node AI/clients/phone/test/rc-live-attach/tui-driver.mjs &
 *   adb reverse tcp:8792 tcp:8792
 *   pc-agent running; orchestrator deployed; CLI rebuilt (bun run build).
 *
 * Run:
 *   adb shell am instrument -w \
 *     -e class com.repository.listener.ui.rc.RcAdoptMidTurnE2ETest \
 *     com.repository.listener.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RcAdoptMidTurnE2ETest {

    companion object {
        private const val WORK_DIR = "/media/varingait/Lobotomite/.cache/rc-live-attach-test"
        private const val TUI_TIMEOUT_MS = 300_000L
        private const val REPLY_TIMEOUT_MS = 300_000L
        private const val UI_TIMEOUT_MS = 90_000L
        private const val HOLD_MS = 3_000L
        private const val PKG = "com.repository.listener"
    }

    private lateinit var device: UiDevice
    private lateinit var harness: RcChatHarness
    private lateinit var pc: PcTuiDriver
    private var sessionId: String = ""

    @Before
    fun setup() {
        ScreenshotHelper.resetCounter()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        harness = RcChatHarness(device)
        pc = PcTuiDriver()
        pc.requireReachable()
        pc.reset()
    }

    @After
    fun teardown() {
        try { harness.endSessionAndReturn() } catch (_: Throwable) {}
        try { if (sessionId.isNotEmpty()) harness.stopRemoteSession(sessionId) } catch (_: Throwable) {}
        try { pc.reset() } catch (_: Throwable) {}
        sessionId = ""
        try { device.pressHome() } catch (_: Throwable) {}
        Thread.sleep(1_000)
    }

    /**
     * A CLI paused on a permission prompt when the phone opens the chat must show
     * the live prompt on the phone, and approving from the phone must unblock the
     * PC turn -- not render "Question: Completed".
     */
    @Test
    fun openingWhilePausedOnAPromptShowsItAndPhoneCanAnswer() {
        // permissionMode "default" so a tool call pauses on a real permission
        // prompt instead of auto-approving.
        val tui = pc.startTui(workDir = WORK_DIR, permissionMode = "default")
        assertEquals("interactive", tui.kind)
        assertTrue(tui.attachSocketPath.isNotEmpty())
        sessionId = tui.sessionId

        // Drive the CLI to a tool it must ask permission for. A unique marker in
        // the command lets us confirm on the PC screen that the tool actually ran
        // AFTER the phone approves.
        val marker = "ADOPT-PROMPT-${System.currentTimeMillis()}"
        pc.type("Run this exact shell command and nothing else: echo $marker")
        // Wait until the TUI is actually showing a permission prompt.
        pc.awaitScreenContains("Do you want to", TUI_TIMEOUT_MS)
        val probeBefore = pc.probe()
        assertFalse("Not attached before the phone opens", probeBefore.attached)
        ScreenshotHelper.take("01_pc_paused_on_prompt")

        // Open the chat the chat-list way: known id, NO startSession.
        harness.reenterRcSession(tui.sessionId, WORK_DIR)
        pc.awaitAttached(expected = true, timeoutMs = UI_TIMEOUT_MS)
        ScreenshotHelper.take("02_phone_opened")

        // The live prompt must appear on the phone (Approve/Reject buttons). Under
        // the old code the phone showed only a historical "Completed" entry, so
        // these buttons never appeared.
        val approve = device.wait(Until.findObject(By.text("Approve")), UI_TIMEOUT_MS)
        assertNotNull(
            "Phone must show the live permission prompt (Approve button) for a " +
                "CLI paused on a prompt when the chat was opened", approve
        )
        ScreenshotHelper.take("03_prompt_visible_on_phone")
        Thread.sleep(HOLD_MS)

        // Approve from the phone; the PC turn must proceed and actually run the
        // tool (marker echoed in the TUI), proving the phone's answer reached the
        // paused CLI rather than a dead historical entry.
        val screenMark = pc.screenLength()
        approve.click()
        val ran = pc.awaitScreenContains(marker, TUI_TIMEOUT_MS, since = screenMark)
        assertTrue("Approving on the phone must unblock the PC and run the tool", ran.contains(marker))
        ScreenshotHelper.take("04_pc_unblocked_after_phone_approve")
        Thread.sleep(HOLD_MS)
    }

    /**
     * A tool executing when the phone opens the chat must render as a running row
     * on the phone, not appear only after it completes.
     */
    @Test
    fun openingWhileAToolIsRunningShowsARunningRow() {
        // bypassPermissions so the tool runs without a prompt; a sleep keeps it
        // in flight long enough for the phone to open mid-execution.
        val tui = pc.startTui(workDir = WORK_DIR, permissionMode = "bypassPermissions")
        assertEquals("interactive", tui.kind)
        sessionId = tui.sessionId

        val marker = "ADOPT-RUNNING-${System.currentTimeMillis()}"
        pc.type("Run this exact shell command and nothing else: sleep 25 && echo $marker")
        // Wait until the tool has actually started (the CLI shows the running
        // Bash), then open the phone while it is still sleeping.
        pc.awaitScreenContains("sleep 25", TUI_TIMEOUT_MS)
        ScreenshotHelper.take("01_tool_running_on_pc")

        harness.reenterRcSession(tui.sessionId, WORK_DIR)
        pc.awaitAttached(expected = true, timeoutMs = UI_TIMEOUT_MS)

        // The phone must show a running Bash row WHILE the tool is still in
        // flight -- before the marker (which only prints on completion) appears.
        val running = device.wait(Until.findObject(By.textContains("Bash")), UI_TIMEOUT_MS)
        assertNotNull("Phone must show the running tool row while it is in flight", running)
        // Ground truth: the tool has not completed yet on the PC.
        assertFalse(
            "Guard: the running row must be observed BEFORE the tool completes",
            pc.screen().contains(marker)
        )
        ScreenshotHelper.take("02_running_row_on_phone")
        Thread.sleep(HOLD_MS)

        // And it does complete and the result reaches the phone.
        pc.awaitScreenContains(marker, TUI_TIMEOUT_MS)
        val done = harness.awaitAnyMessage(marker, timeoutMs = REPLY_TIMEOUT_MS)
        assertTrue("Completion must reach the phone", done.contains(marker))
        ScreenshotHelper.take("03_tool_completed_on_phone")
        Thread.sleep(HOLD_MS)
    }
}
