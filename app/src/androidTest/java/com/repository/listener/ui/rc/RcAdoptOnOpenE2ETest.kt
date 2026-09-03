package com.repository.listener.ui.rc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device e2e for the adopt-on-open gap.
 *
 * The gap: opening a chat from the phone's chat list, when that conversation
 * already has an interactive CLI running on the PC, did NOT attach and did NOT
 * show the session as running. The user only saw new messages, and nothing
 * "opened" until they sent a message (which triggered the respawn/queue path).
 *
 * The distinction that makes this test about the NEW code and not the old
 * live-attach path: it opens the conversation with [RcChatHarness.reenterRcSession],
 * which launches RemoteControlActivity with a known sessionId/workDir but NEVER
 * calls the orchestrator startSession RPC. So the only thing that can cause an
 * attach here is the orchestrator firing adopt on the rc_transcript_request the
 * activity sends on open (maybeAdoptCli). Under the old code this attaches
 * nothing; under the fix it adopts the live CLI.
 *
 * REAL SYSTEM ONLY: a real interactive CLI in a real pty on the PC (via
 * [PcTuiDriver]), the real pc-agent, the real deployed orchestrator, the phone
 * app over its normal WebSocket. PC-side ground truth (attach socket self-report,
 * headless spawn count) is read through the driver so "this was an adopt, not a
 * spawn" is non-vacuous.
 *
 * Preconditions on the PC (the deploying agent sets these up):
 *   node AI/clients/phone/test/rc-live-attach/tui-driver.mjs &
 *   adb reverse tcp:8792 tcp:8792
 *   pc-agent running and registered; orchestrator deployed with adoptCli=true
 *
 * Run (NEVER connectedAndroidTest -- teardown uninstalls the app):
 *   adb shell am instrument -w \
 *     -e class com.repository.listener.ui.rc.RcAdoptOnOpenE2ETest \
 *     com.repository.listener.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RcAdoptOnOpenE2ETest {

    companion object {
        // Must sit under an already-trusted directory, else the CLI blocks on
        // the workspace-trust dialog and never publishes an attach socket.
        private const val WORK_DIR = "/media/varingait/Lobotomite/.cache/rc-live-attach-test"
        private const val REPLY_TIMEOUT_MS = 300_000L
        private const val TUI_TIMEOUT_MS = 300_000L
        private const val HOLD_MS = 3_000L
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
     * Opening a conversation from the chat list (no startSession call) must adopt
     * the live PC CLI, so the session shows as running and messages flow -- the
     * exact state that used to be missing until the user sent a message.
     */
    @Test
    fun openingAnAlreadyLivePcConversationAdoptsItWithoutSending() {
        // 1. A real live TUI on the PC with on-disk history + a title. The title
        //    is what makes this conversation look "already going" to the phone.
        val marker = "ADOPT-OPEN-MARKER-${System.currentTimeMillis()}"
        val tui = pc.startTui(workDir = WORK_DIR, permissionMode = "default")
        assertEquals("Driver must start an INTERACTIVE session", "interactive", tui.kind)
        assertTrue("CLI must publish an attach socket", tui.attachSocketPath.isNotEmpty())
        pc.type("Reply with exactly this token and nothing else: $marker")
        pc.awaitScreenContains(marker, TUI_TIMEOUT_MS)

        val probeBefore = pc.probe()
        assertFalse("Nothing is attached before the phone opens the chat", probeBefore.attached)
        val headlessBefore = pc.headlessCount()
        sessionId = tui.sessionId
        ScreenshotHelper.take("01_live_tui_seeded")

        // 2. Open the chat the way tapping a chat-list row does: launch the
        //    activity with the known id/workDir. This does NOT call startSession,
        //    so ONLY adopt-on-transcript-request can cause an attach.
        harness.reenterRcSession(tui.sessionId, WORK_DIR)
        ScreenshotHelper.take("02_chat_opened_no_send")

        // 3. The adopt assertion. `attached` flips true only after the CLI's own
        //    attachServer installed a session and its orchestrator WS came up --
        //    nothing the phone or this test does can set it directly. Under the
        //    pre-fix code this stays false and the test fails here.
        val adopted = pc.awaitAttached(expected = true, timeoutMs = 90_000L)
        assertEquals("Adopted the right conversation", tui.sessionId, adopted.sessionId)
        assertEquals("Adopted the live pid, not a new process", tui.pid, adopted.pid)

        // 4. The "not a spawn" assertion: opening a chat must never spawn a
        //    headless CLI. If adopt had fallen through to spawn this would climb.
        val headlessAfter = pc.headlessCount()
        assertEquals(
            "Opening an already-live conversation must not spawn. " +
                "before=$headlessBefore after=$headlessAfter argv=${pc.headlessArgvs()}",
            headlessBefore, headlessAfter
        )

        // 5. The pre-existing exchange is visible: proves the phone joined the
        //    EXISTING conversation, not a blank one.
        val restored = harness.awaitAnyMessage(marker, timeoutMs = 90_000L)
        assertTrue("Phone must show the pre-existing exchange", restored.contains(marker))
        ScreenshotHelper.take("03_adopted_history_visible")
        Thread.sleep(HOLD_MS)

        // 6. It is functional: a message sent now lands in the SAME live CLI, and
        //    its answer comes back to the phone -- one conversation, two views.
        val fromPhone = "ADOPT-PROBE-${System.currentTimeMillis()}"
        val screenMark = pc.screenLength()
        val ignore = harness.assistantTexts().toSet()
        harness.sendMessage("Reply with exactly this token and nothing else: $fromPhone")
        val tuiScreen = pc.awaitScreenContains(fromPhone, TUI_TIMEOUT_MS, since = screenMark)
        assertTrue("PC TUI must show the phone's message on the adopted CLI", tuiScreen.contains(fromPhone))
        val reply = harness.awaitAssistantReply(
            matchSubstring = fromPhone,
            ignoreTexts = ignore,
            timeoutMs = REPLY_TIMEOUT_MS
        )
        assertTrue("Phone must render the reply from the adopted session", reply.contains(fromPhone))

        // Still exactly one attachment on the same pid: no competing desktop.
        val after = pc.probe()
        assertTrue("Attachment survives the exchange", after.attached)
        assertEquals(tui.pid, after.pid)
        ScreenshotHelper.take("04_adopted_bidirectional")
        Thread.sleep(HOLD_MS)
    }
}
