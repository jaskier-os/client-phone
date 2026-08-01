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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device e2e for live attach (plan section 8.4).
 *
 * The feature: when the phone opens a conversation that is ALREADY live in an
 * interactive remote-session TUI on the PC, pc-agent ATTACHES to that CLI over
 * a local unix socket instead of spawning a second headless one, and the live
 * CLI opens the orchestrator WebSocket itself. Both views then update.
 *
 * REAL SYSTEM ONLY. No fakes, no mocks, no stubbed transport: a real
 * interactive CLI in a real pty on the PC, the real pc-agent, the real deployed
 * orchestrator, and the phone app talking to it over its normal WebSocket.
 *
 * Why the PC driver exists: from the phone alone an attach and a spawn look
 * identical -- both render an assistant reply. Every "this is really an attach"
 * assertion here reads PC-side ground truth via [PcTuiDriver]: the count of
 * headless CLIs pc-agent spawned (`--sdk-url` in argv) and the live CLI's own
 * `hello_ok` self-report over its attach socket.
 *
 * UI is driven with UiAutomator2 resource-ids / contentDescriptions only. No
 * coordinate taps, no `input tap`, no ADB broadcasts to fake app state.
 *
 * Preconditions on the PC (the deploying agent sets these up):
 *   node AI/clients/phone/test/rc-live-attach/tui-driver.mjs &
 *   adb reverse tcp:8792 tcp:8792
 *   pc-agent running and registered with the orchestrator
 *
 * Run (NEVER connectedAndroidTest -- its teardown uninstalls the app):
 *   adb shell am instrument -w \
 *     -e class com.repository.listener.ui.rc.RcLiveAttachE2ETest \
 *     com.repository.listener.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RcLiveAttachE2ETest {

    companion object {
        // Must sit under an already-trusted directory. The CLI blocks on the
        // workspace-trust dialog for anything else and then never publishes an
        // attach socket, so every test here would die on an opaque timeout.
        // /tmp is not trusted. Must also be realpath-stable, because the CLI
        // reports its resolved cwd and we assert equality against this string --
        // $HOME is a symlink here and would come back rewritten.
        private const val WORK_DIR = "/media/varingait/Lobotomite/.cache/rc-live-attach-test"

        /** Real AI round-trips are slow; these budgets match the sibling rc tests. */
        private const val REPLY_TIMEOUT_MS = 300_000L
        private const val TUI_TIMEOUT_MS = 300_000L

        /** Every rendered state is held this long so screen recordings capture it. */
        private const val HOLD_MS = 3_000L

        /**
         * The attach path must not be stalled by the orchestrator's replay
         * gate. `handleRemoteControlConnection` sets replayInProgress for any
         * session that already has a title, and the only other exit is
         * REPLAY_FLUSH_TIMEOUT_MS = 90s; an attached CLI never replays, so
         * without the synthetic `result` (plan section 3.8) the first phone
         * message hangs for the full 90s. 60s is comfortably below that and
         * comfortably above a healthy attach (~2-5s), so this threshold cannot
         * pass a regressed build nor flake on a slow-but-correct one.
         */
        private const val REPLAY_STALL_THRESHOLD_MS = 60_000L

    }

    private lateinit var device: UiDevice
    private lateinit var harness: RcChatHarness
    private lateinit var pc: PcTuiDriver

    private var sessionId: String = ""
    private var spawnedSessionId: String = ""

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
        // Detach the phone side FIRST. stopRemoteSession on an attached entry
        // detaches without killing the user's CLI (that is the pc-agent
        // contract); the driver then kills the TUI it owns.
        try { harness.endSessionAndReturn() } catch (_: Throwable) {}
        try { if (sessionId.isNotEmpty()) harness.stopRemoteSession(sessionId) } catch (_: Throwable) {}
        try { if (spawnedSessionId.isNotEmpty()) harness.stopRemoteSession(spawnedSessionId) } catch (_: Throwable) {}
        try { pc.reset() } catch (_: Throwable) {}
        sessionId = ""
        spawnedSessionId = ""
        // Leave no RC activity on the stack for the next test in the class.
        // NEVER force-stop the package here: instrumentation runs inside this
        // very process, so `am force-stop` kills the test runner itself and the
        // run dies as "Process crashed." with no stack. Going Home is enough --
        // each test re-launches the activity it needs.
        try { device.pressHome() } catch (_: Throwable) {}
        Thread.sleep(1_000)
    }

    // ==================================================================
    // Shared setup: a live PC TUI with real conversation history.
    // ==================================================================

    /**
     * Start the PC TUI and give it one real exchange so the conversation has
     * on-disk history AND an orchestrator-side title.
     *
     * The title matters: it is what flips `replayInProgress` on for the
     * attaching desktop, so seeding it is what makes the 90s-stall regression
     * reachable at all. A test that skipped this would pass against the
     * regressed build.
     */
    private fun startLiveTuiWithHistory(marker: String): PcTuiDriver.TuiSession {
        val tui = pc.startTui(workDir = WORK_DIR, permissionMode = "default")
        assertEquals(
            "Driver must start an INTERACTIVE session; only kind=interactive is attachable",
            "interactive", tui.kind
        )
        assertTrue(
            "CLI must publish an attach socket, else pc-agent can only spawn",
            tui.attachSocketPath.isNotEmpty()
        )

        pc.type("Reply with exactly this token and nothing else: $marker")
        pc.awaitScreenContains(marker, TUI_TIMEOUT_MS)

        val probe = pc.probe()
        assertEquals("CLI reports the conversation the driver started", tui.sessionId, probe.sessionId)
        assertEquals("CLI cwd must equal the workDir the phone will ask for", WORK_DIR, probe.cwd)
        assertFalse("Nothing is attached before the phone opens the conversation", probe.attached)
        return tui
    }

    /**
     * Open [tui]'s conversation on the phone. Returns how long the orchestrator
     * start call took.
     */
    private fun openOnPhone(tui: PcTuiDriver.TuiSession): Long {
        val (sid, elapsedMs) = harness.launchRcSession(
            workDir = WORK_DIR,
            permissionMode = "bypassAll",
            resumeSessionId = tui.sessionId
        )
        sessionId = sid
        assertEquals(
            "Opening a known conversation must reuse its id, not mint a new one",
            tui.sessionId, sid
        )
        return elapsedMs
    }

    // ==================================================================
    // 1. Attach (not spawn) + existing history is visible on the phone.
    // ==================================================================

    @Test
    fun phoneOpeningLiveConversationAttachesInsteadOfSpawning() {
        val marker = "ATTACH-HISTORY-MARKER-${System.currentTimeMillis()}"
        val tui = startLiveTuiWithHistory(marker)
        ScreenshotHelper.take("01_tui_seeded")

        val headlessBefore = pc.headlessCount()

        openOnPhone(tui)
        ScreenshotHelper.take("02_phone_opened")

        // --- The attach assertion. -------------------------------------
        // Non-vacuous because it reads the CLI's own hello_ok: `attached`
        // becomes true only after attachServer installed a session and its
        // orchestrator WebSocket came up. Nothing the phone or this test does
        // can set it directly.
        val probe = pc.awaitAttached(expected = true, timeoutMs = 60_000L)
        assertEquals("Attached to the right conversation", tui.sessionId, probe.sessionId)
        assertEquals("Attached to the right pid", tui.pid, probe.pid)

        // --- The "not a spawn" assertion. ------------------------------
        // pc-agent's spawn path is the only producer of `--sdk-url` argv on
        // this host. If it had spawned instead of attaching, this count would
        // have gone UP by one and the assertion fails -- so it cannot pass
        // vacuously alongside the attach assertion above.
        val headlessAfter = pc.headlessCount()
        assertEquals(
            "No headless CLI may be spawned for a conversation that is already live. " +
                "before=$headlessBefore after=$headlessAfter argv=${pc.headlessArgvs()}",
            headlessBefore, headlessAfter
        )

        // --- Existing history renders on the phone. --------------------
        // The transcript comes from the orchestrator store, seeded by the PC
        // leg before the phone ever connected, so seeing the marker proves the
        // phone joined an EXISTING conversation rather than a blank one.
        val restored = harness.awaitAnyMessage(marker, timeoutMs = 90_000L)
        assertTrue("Phone must show the pre-existing exchange", restored.contains(marker))
        ScreenshotHelper.take("03_history_visible")
        Thread.sleep(HOLD_MS)
    }

    // ==================================================================
    // 2. Realtime, both directions. This is the actual feature.
    // ==================================================================

    @Test
    fun messagesFlowBothWaysBetweenPhoneAndPcTui() {
        val seed = "BIDIR-SEED-${System.currentTimeMillis()}"
        val tui = startLiveTuiWithHistory(seed)
        openOnPhone(tui)
        pc.awaitAttached(expected = true, timeoutMs = 60_000L)
        ScreenshotHelper.take("01_attached")

        // ---------- Direction A: phone -> PC TUI ----------
        val fromPhone = "PHONE-TO-PC-${System.currentTimeMillis()}"
        val screenMark = pc.screenLength()
        val ignore = harness.assistantTexts().toSet()

        harness.sendMessage("Reply with exactly this token and nothing else: $fromPhone")
        ScreenshotHelper.take("02_phone_sent")

        // The token must appear in the REAL TUI's own transcript. It can only
        // get there by being injected into the live REPL through the attach
        // socket -- a spawned headless CLI has no terminal to print to.
        val tuiScreen = pc.awaitScreenContains(fromPhone, TUI_TIMEOUT_MS, since = screenMark)
        assertTrue("PC TUI must show the phone's message", tuiScreen.contains(fromPhone))

        // ...and the answer to it must come back to the phone.
        val phoneReply = harness.awaitAssistantReply(
            matchSubstring = fromPhone,
            ignoreTexts = ignore,
            timeoutMs = REPLY_TIMEOUT_MS
        )
        assertTrue("Phone must render the reply to its own message", phoneReply.contains(fromPhone))
        ScreenshotHelper.take("03_phone_to_pc_roundtrip")
        Thread.sleep(HOLD_MS)

        // ---------- Direction B: PC TUI -> phone ----------
        val fromPc = "PC-TO-PHONE-${System.currentTimeMillis()}"
        val ignore2 = harness.assistantTexts().toSet()

        pc.type("Reply with exactly this token and nothing else: $fromPc")
        pc.awaitScreenContains(fromPc, TUI_TIMEOUT_MS)

        // The assistant's answer to a prompt typed on the PC is pushed live to
        // the phone (rc_message). Asserting on the ANSWER, not on an echo of
        // the typed line, is deliberate: a desktop `user` frame is persisted to
        // the transcript but not pushed live, so asserting on the echo would
        // test the transcript refetch, not the live mirror. The answer only
        // exists because the attached CLI ran the PC-typed turn and streamed it
        // over the orchestrator WS it owns.
        val liveOnPhone = harness.awaitAssistantReply(
            matchSubstring = fromPc,
            ignoreTexts = ignore2,
            timeoutMs = REPLY_TIMEOUT_MS
        )
        assertTrue("Phone must render the PC turn's reply in realtime", liveOnPhone.contains(fromPc))
        ScreenshotHelper.take("04_pc_to_phone_live")
        Thread.sleep(HOLD_MS)

        // Both legs of the conversation coexist in one transcript on the phone:
        // this is what "one conversation, two views" means, and it fails if the
        // two sides ever ended up on separate sessions.
        val all = harness.userTexts() + harness.assistantTexts()
        assertTrue(
            "Phone transcript must contain BOTH the phone-originated and the " +
                "PC-originated turn; rendered=${all.take(20)}",
            all.any { it.contains(fromPhone) } && all.any { it.contains(fromPc) }
        )
        ScreenshotHelper.take("05_both_turns")
        Thread.sleep(HOLD_MS)

        // Still exactly one attachment on exactly one pid: no competing desktop
        // appeared during the exchange.
        val probe = pc.probe()
        assertTrue("Attachment must survive the exchange", probe.attached)
        assertEquals(tui.pid, probe.pid)
    }

    // ==================================================================
    // 3. Regression: no live CLI -> old spawn path, unchanged.
    // ==================================================================

    @Test
    fun noLiveCliStartsAnInteractiveOneAndAttachesToIt() {
        // Deliberately NO PC TUI for this conversation.
        pc.reset()

        val (sid, _) = harness.launchRcSession(
            workDir = WORK_DIR,
            permissionMode = "bypassAll",
            resumeSessionId = null
        )
        spawnedSessionId = sid
        ScreenshotHelper.take("01_started_session")

        // There is only ONE kind of session: a real interactive TUI the user can
        // see, which pc-agent then attaches to. A headless `--print` process
        // would render nothing on the PC and could not be attached to at all, so
        // its absence here is the assertion -- not a missing fallback.
        assertEquals(
            "Starting a session must never produce a headless CLI. argv=${pc.headlessArgvs()}",
            0, pc.headlessCount()
        )
        val live = pc.awaitLiveSession(sid, timeoutMs = 60_000L)
        assertEquals("pc-agent must start an interactive session", "interactive", live.kind)
        assertTrue(
            "The started session must be attachable, i.e. it published a socket",
            live.attachSocketPath.isNotEmpty()
        )

        // ...and it behaves exactly as today: a real AI round-trip renders.
        val marker = "SPAWN-FALLBACK-${System.currentTimeMillis()}"
        val ignore = harness.assistantTexts().toSet()
        harness.sendMessage("Reply with exactly this token and nothing else: $marker")
        val reply = harness.awaitAssistantReply(
            matchSubstring = marker,
            ignoreTexts = ignore,
            timeoutMs = REPLY_TIMEOUT_MS
        )
        assertTrue("Spawned session must answer as before", reply.contains(marker))
        ScreenshotHelper.take("02_spawn_reply")
        Thread.sleep(HOLD_MS)
    }

    // ==================================================================
    // 4. Regression: the orchestrator replayInProgress 90s stall.
    // ==================================================================

    @Test
    fun attachDoesNotStallOnOrchestratorReplayGate() {
        // The seed exchange gives the conversation a title, which is exactly
        // what makes the orchestrator arm replayInProgress for the attaching
        // desktop. Without it this test could not observe the regression.
        val seed = "REPLAY-GATE-SEED-${System.currentTimeMillis()}"
        val tui = startLiveTuiWithHistory(seed)

        val startElapsed = openOnPhone(tui)
        pc.awaitAttached(expected = true, timeoutMs = 60_000L)
        ScreenshotHelper.take("01_attached_fast")

        // The load-bearing measurement: the FIRST phone message after attach.
        // Under the regression it is buffered until REPLAY_FLUSH_TIMEOUT_MS
        // (90s) expires, so the TUI does not see it until then.
        val probeToken = "REPLAY-GATE-PROBE-${System.currentTimeMillis()}"
        val screenMark = pc.screenLength()
        val sentAt = System.currentTimeMillis()
        harness.sendMessage("Reply with exactly this token and nothing else: $probeToken")

        pc.awaitScreenContains(probeToken, TUI_TIMEOUT_MS, since = screenMark)
        val deliveryMs = System.currentTimeMillis() - sentAt

        assertTrue(
            "First phone message after attach reached the live TUI in ${deliveryMs}ms; " +
                "over ${REPLAY_STALL_THRESHOLD_MS}ms means the synthetic result on connect " +
                "is missing and the orchestrator's 90s replay gate is stalling attached sessions",
            deliveryMs < REPLAY_STALL_THRESHOLD_MS
        )
        assertTrue(
            "startSession itself must not block on the replay gate (took ${startElapsed}ms)",
            startElapsed < REPLAY_STALL_THRESHOLD_MS
        )
        ScreenshotHelper.take("02_first_message_delivered")
        Thread.sleep(HOLD_MS)
    }

    // ==================================================================
    // 5. Regression: close code 1000 is a permanent detach in attached mode.
    // ==================================================================

    @Test
    fun endingTheSessionDetachesPermanentlyWithoutResurrection() {
        val seed = "CLOSE-1000-SEED-${System.currentTimeMillis()}"
        val tui = startLiveTuiWithHistory(seed)
        openOnPhone(tui)
        pc.awaitAttached(expected = true, timeoutMs = 60_000L)
        ScreenshotHelper.take("01_attached")

        val headlessBefore = pc.headlessCount()

        // Ending the RC session makes the orchestrator close the desktop WS
        // with code 1000. In attached mode that must be PERMANENT: the CLI
        // must not reconnect (which would resurrect a desktop nobody asked
        // for), and must not die (it is the user's own live shell).
        harness.endSessionAndReturn()
        harness.stopRemoteSession(sessionId)
        sessionId = ""

        val detached = pc.awaitAttached(expected = false, timeoutMs = 60_000L)
        assertFalse("CLI must report itself detached", detached.attached)
        ScreenshotHelper.take("02_detached")

        // Hold past any plausible reconnect backoff and re-check. A transport
        // that treated 1000 as transient would have come back by now.
        Thread.sleep(20_000)
        val still = pc.probe()
        assertFalse(
            "Attachment must not resurrect after a 1000 close (that would flap " +
                "against any competing spawned desktop)",
            still.attached
        )

        // The PC user's CLI is untouched: same pid, same conversation, alive.
        assertEquals("The live CLI must survive detach", tui.pid, still.pid)
        assertEquals(tui.sessionId, still.sessionId)
        assertFalse("The TUI process must not have exited", pc.hasExited())

        // And no competing headless desktop was spawned in the meantime.
        assertEquals(
            "Detach must not trigger a spawned competitor; argv=${pc.headlessArgvs()}",
            headlessBefore, pc.headlessCount()
        )
        ScreenshotHelper.take("03_no_resurrection")
        Thread.sleep(HOLD_MS)
    }

    // ==================================================================
    // 6. Lifecycle: PC CLI exits while the phone is attached.
    // ==================================================================

    @Test
    fun pcCliExitWhileAttachedLeavesPhoneUsable() {
        val seed = "CLI-EXIT-SEED-${System.currentTimeMillis()}"
        val tui = startLiveTuiWithHistory(seed)
        openOnPhone(tui)
        pc.awaitAttached(expected = true, timeoutMs = 60_000L)
        ScreenshotHelper.take("01_attached")

        val deadPid = tui.pid

        // SIGKILL: the harshest exit, no chance to detach cleanly.
        pc.killTui("SIGKILL")
        Thread.sleep(5_000)
        ScreenshotHelper.take("02_cli_killed")

        // The phone must recover: the orchestrator notices the desktop is gone
        // and asks pc-agent to start the session again, which now means a fresh
        // interactive CLI (resumed onto the same transcript) that it attaches to.
        val marker = "AFTER-CLI-EXIT-${System.currentTimeMillis()}"
        val ignore = harness.assistantTexts().toSet()
        harness.sendMessage("Reply with exactly this token and nothing else: $marker")
        val reply = harness.awaitAssistantReply(
            matchSubstring = marker,
            ignoreTexts = ignore,
            timeoutMs = REPLY_TIMEOUT_MS
        )
        assertTrue("Phone must keep working after the PC CLI died", reply.contains(marker))

        // ...and recovery really was a NEW live CLI, not a phantom attach to the
        // dead pid, and still not a headless one.
        val revived = pc.awaitLiveSession(sessionId, timeoutMs = 60_000L)
        assertNotEquals("Recovery must not reuse the killed pid", deadPid, revived.pid)
        assertEquals("Recovery must start an interactive session", "interactive", revived.kind)
        assertEquals(
            "Recovery must never produce a headless CLI. argv=${pc.headlessArgvs()}",
            0, pc.headlessCount()
        )
        ScreenshotHelper.take("03_recovered")
        Thread.sleep(HOLD_MS)
    }

    // ==================================================================
    // 7. Lifecycle: phone leaves while the PC CLI lives.
    // ==================================================================

    @Test
    fun phoneLeavingDetachesButLeavesPcTuiFullyUsable() {
        val seed = "PHONE-LEAVE-SEED-${System.currentTimeMillis()}"
        val tui = startLiveTuiWithHistory(seed)
        openOnPhone(tui)
        pc.awaitAttached(expected = true, timeoutMs = 60_000L)
        ScreenshotHelper.take("01_attached")

        harness.endSessionAndReturn()
        harness.stopRemoteSession(sessionId)
        sessionId = ""
        pc.awaitAttached(expected = false, timeoutMs = 60_000L)
        ScreenshotHelper.take("02_phone_left")

        // The PC TUI keeps working normally: still the same process, still on
        // the same conversation, still able to run a turn. A detach that had
        // damaged the REPL (or killed it) would fail here.
        val marker = "PC-ALONE-${System.currentTimeMillis()}"
        val screenMark = pc.screenLength()
        pc.type("Reply with exactly this token and nothing else: $marker")
        pc.awaitScreenContains(marker, TUI_TIMEOUT_MS, since = screenMark)
        assertFalse("The TUI must still be running", pc.hasExited())

        val after = pc.probe()
        assertEquals(tui.pid, after.pid)
        assertEquals(tui.sessionId, after.sessionId)
        assertFalse(after.attached)
        ScreenshotHelper.take("03_pc_still_working")
        Thread.sleep(HOLD_MS)
    }

    // ==================================================================
    // 8. Double attach / takeover.
    // ==================================================================

    @Test
    fun secondControlPeerIsRefusedWhileAttachedAndTakesOverAfterRelease() {
        val seed = "DOUBLE-ATTACH-SEED-${System.currentTimeMillis()}"
        val tui = startLiveTuiWithHistory(seed)
        openOnPhone(tui)
        pc.awaitAttached(expected = true, timeoutMs = 60_000L)
        ScreenshotHelper.take("01_attached")

        // A second control peer issuing a real `attach` frame must be refused
        // with `already_attached` -- the machine-readable code pc-agent keys on
        // to report success against the live pid instead of spawning a
        // competing desktop. The refusal happens before any WebSocket is
        // opened, so this probe cannot disturb the live attachment.
        val verdict = pc.attachAttempt()
        assertEquals(
            "A competing attach must be refused, not granted (verdict=$verdict)",
            "attach_error", verdict.type
        )
        assertEquals(
            "Refusal must carry the machine-readable already_attached code " +
                "(verdict=$verdict)",
            "already_attached", verdict.code
        )

        // The original attachment is undisturbed by the refused attempt.
        val stillAttached = pc.probe()
        assertTrue("Refused competitor must not tear down the live attachment", stillAttached.attached)
        assertEquals(tui.pid, stillAttached.pid)
        ScreenshotHelper.take("02_competitor_refused")
        Thread.sleep(HOLD_MS)

        // Release the attachment; a fresh peer must then be able to take over.
        // Without takeover a pc-agent restart would lock itself out forever and
        // degrade to spawning a second desktop.
        harness.endSessionAndReturn()
        harness.stopRemoteSession(sessionId)
        sessionId = ""
        pc.awaitAttached(expected = false, timeoutMs = 60_000L)

        val second = pc.attachAttempt()
        assertTrue(
            "After release, a new peer must NOT be refused with already_attached " +
                "(verdict=$second)",
            second.code != "already_attached"
        )
        ScreenshotHelper.take("03_takeover_allowed")
        Thread.sleep(HOLD_MS)
    }

    // ==================================================================
    // 9. Reopening after detach re-attaches (pc-agent restart / reconnect).
    // ==================================================================

    @Test
    fun reopeningTheConversationReattachesToTheSameLiveCli() {
        val seed = "REATTACH-SEED-${System.currentTimeMillis()}"
        val tui = startLiveTuiWithHistory(seed)
        openOnPhone(tui)
        pc.awaitAttached(expected = true, timeoutMs = 60_000L)

        harness.endSessionAndReturn()
        harness.stopRemoteSession(sessionId)
        sessionId = ""
        pc.awaitAttached(expected = false, timeoutMs = 60_000L)
        ScreenshotHelper.take("01_detached")

        val headlessBefore = pc.headlessCount()

        // Reopen the same conversation. It must attach AGAIN to the same pid --
        // not spawn a competitor, and not be locked out by leftover state from
        // the first attachment.
        openOnPhone(tui)
        val again = pc.awaitAttached(expected = true, timeoutMs = 60_000L)
        assertEquals("Re-attached to the same live CLI", tui.pid, again.pid)
        assertEquals(tui.sessionId, again.sessionId)
        assertEquals(
            "Re-attach must not spawn a competing desktop; argv=${pc.headlessArgvs()}",
            headlessBefore, pc.headlessCount()
        )
        ScreenshotHelper.take("02_reattached")

        // And it is functional, not merely "attached".
        val marker = "REATTACH-PROBE-${System.currentTimeMillis()}"
        val screenMark = pc.screenLength()
        harness.sendMessage("Reply with exactly this token and nothing else: $marker")
        pc.awaitScreenContains(marker, TUI_TIMEOUT_MS, since = screenMark)
        ScreenshotHelper.take("03_reattach_works")
        Thread.sleep(HOLD_MS)
    }

    // ==================================================================
    // 10. Permission mode: the phone may lower, never escalate.
    // ==================================================================

    @Test
    fun phoneCannotEscalatePermissionModeOnAnAttachedSession() {
        val seed = "PERM-SEED-${System.currentTimeMillis()}"
        // Start in `plan`, the LOWEST ranked tier. Any move the phone makes
        // from here is an escalation, so a build that failed open would be
        // caught regardless of which tier the phone happens to request.
        val tui = pc.startTui(workDir = WORK_DIR, permissionMode = "plan")
        pc.type("Reply with exactly this token and nothing else: $seed")
        pc.awaitScreenContains(seed, TUI_TIMEOUT_MS)

        val before = pc.probe()
        assertEquals("TUI must actually be in plan mode", "plan", before.permissionMode)

        // The phone opens with bypassAll, which pc-agent maps to the CLI's
        // bypassPermissions -- the maximum tier. This is the exact escalation
        // the lower-only rule exists to refuse.
        openOnPhone(tui)
        pc.awaitAttached(expected = true, timeoutMs = 60_000L)
        ScreenshotHelper.take("01_attached_in_plan")

        // Give any inbound mode change ample time to be (wrongly) applied.
        Thread.sleep(10_000)

        val after = pc.probe()
        assertEquals(
            "The phone must NOT be able to raise the live session's tier. " +
                "was=${before.permissionMode} now=${after.permissionMode}",
            "plan", after.permissionMode
        )
        assertTrue(
            "bypassPermissions is never reachable from the phone on an attached session",
            after.permissionMode != "bypassPermissions"
        )
        assertTrue(
            "dontAsk is equally an escalation and must also be unreachable " +
                "(this is the unranked-mode hole the audit found)",
            after.permissionMode != "dontAsk"
        )
        ScreenshotHelper.take("02_still_plan")
        Thread.sleep(HOLD_MS)
    }

    /**
     * Auto-approving sessions ARE attachable. The phone starts every session in
     * bypassAll, so refusing these would mean it could never open one at all.
     * The trust boundary is the attach socket (owner-only, peer-cred checked,
     * shared-secret) plus the orchestrator API key -- not the permission tier.
     * Escalation from a lower tier is still refused; see the test above.
     */
    @Test
    fun anAutoApprovingSessionIsAttachable() {
        val seed = "AUTO-APPROVE-SEED-${System.currentTimeMillis()}"
        val tui = pc.startTui(workDir = WORK_DIR, permissionMode = "dontAsk")
        pc.type("Reply with exactly this token and nothing else: $seed")
        pc.awaitScreenContains(seed, TUI_TIMEOUT_MS)
        assertEquals("dontAsk", pc.probe().permissionMode)

        openOnPhone(tui)
        ScreenshotHelper.take("01_phone_opened_auto_approving_session")

        val probe = pc.awaitAttached(expected = true, timeoutMs = 60_000L)
        assertEquals("Attach must target the live CLI, not a new process", tui.pid, probe.pid)
        assertEquals(
            "Attaching must not silently change the session's tier",
            "dontAsk", probe.permissionMode
        )
        assertEquals(
            "Attaching must never produce a headless CLI. argv=${pc.headlessArgvs()}",
            0, pc.headlessCount()
        )
        ScreenshotHelper.take("02_attached")
        Thread.sleep(HOLD_MS)
    }

    // ==================================================================
    // 11. Model surfacing.
    // ==================================================================

    @Test
    fun attachReportsTheSessionsRealModelAndNeverFakesAMessage() {
        val seed = "MODEL-SEED-${System.currentTimeMillis()}"
        val tui = startLiveTuiWithHistory(seed)
        // Ground truth, read from the live CLI. Never hardcode a model here:
        // the TUI restores whichever model the user last used, so a constant
        // would either test a model they never run or break when they switch.
        val liveModel = pc.probe().model
        assertTrue("CLI must report the model it is running", liveModel.isNotEmpty())

        val bubblesBefore = harness.assistantTexts().toSet()
        openOnPhone(tui)
        val attached = pc.awaitAttached(expected = true, timeoutMs = 60_000L)
        ScreenshotHelper.take("01_attached")

        // The model stays readable while attached, and attaching does not
        // silently change it.
        assertEquals(
            "Attaching must not change the model the session runs",
            liveModel, attached.model
        )

        // Attaching must not INVENT chat content. Session metadata used to be
        // pushed as a fabricated `assistant` message (the orchestrator drops
        // `type: 'system'`), putting a status line in the conversation that no
        // model ever wrote -- and which the orchestrator dropped anyway, since
        // assistant text is only persisted on the next `result`.
        //
        // Real history IS expected to appear: the seed turn's reply is exactly
        // what "the phone sees the conversation" means. So assert on the shape
        // of what arrives -- no status/metadata lines -- rather than on nothing
        // arriving at all.
        Thread.sleep(10_000)
        val newBubbles = harness.assistantTexts().toSet() - bubblesBefore
        val fabricated = newBubbles.filter {
            it.contains("attached to live PC session") ||
                (it.contains(liveModel) && it.contains(WORK_DIR))
        }
        assertTrue(
            "Attaching must not fabricate status messages; found=$fabricated",
            fabricated.isEmpty()
        )
        Thread.sleep(HOLD_MS)
        ScreenshotHelper.take("02_no_injected_messages")
    }
}
