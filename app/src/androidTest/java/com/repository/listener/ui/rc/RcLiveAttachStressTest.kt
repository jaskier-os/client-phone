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
 * Stress / concurrency suite for live attach (plan section 8.5).
 *
 * Same REAL system as [RcLiveAttachE2ETest]: a real interactive CLI on the PC,
 * the real pc-agent, the real deployed orchestrator. Nothing is faked. What is
 * different here is duration and repetition -- these tests look for loss,
 * duplication, ordering violations, resource leaks and attach flap that a
 * single happy-path run cannot see.
 *
 * Message counts are lower than the plan's 50+50 by design: those numbers were
 * written for the local fake-orchestrator harness (section 8.3), where a turn is
 * instant. Here every message is a real AI turn against the deployed
 * orchestrator, so 50 each would take hours and time out the instrumentation.
 * The properties under test (no loss, no duplication, per-source ordering) are
 * violated by the FIRST bad interleave, not by the fiftieth, so a shorter run
 * with strictly checked invariants tests the same thing. The full 50+50 belongs
 * in the fake-orchestrator harness.
 *
 * UiAutomator2 only; no coordinate taps; run via `adb shell am instrument`,
 * never connectedAndroidTest.
 *
 * Preconditions (PC side):
 *   node AI/clients/phone/test/rc-live-attach/tui-driver.mjs &
 *   adb reverse tcp:8792 tcp:8792
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RcLiveAttachStressTest {

    companion object {
        // Must be trusted and realpath-stable -- see RcLiveAttachE2ETest.
        private const val WORK_DIR = "/media/varingait/Lobotomite/.cache/rc-live-attach-stress"

        private const val REPLY_TIMEOUT_MS = 300_000L
        private const val TUI_TIMEOUT_MS = 300_000L
        private const val HOLD_MS = 3_000L

        /** Turns per source in the interleave test. See the class comment. */
        private const val INTERLEAVE_ROUNDS = 6

        /** Attach/detach cycles. Enough for a per-cycle leak to accumulate
         *  visibly; 100 (the plan's number) is a fake-orchestrator budget. */
        private const val ATTACH_CYCLES = 12
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
        // Never force-stop this package: instrumentation runs inside it, so that
        // kills the test runner itself (see RcLiveAttachE2ETest.teardown).
        try { device.pressHome() } catch (_: Throwable) {}
        Thread.sleep(1_000)
    }

    // ------------------------------------------------------------------

    private fun startAttachedSession(seedMarker: String): PcTuiDriver.TuiSession {
        val tui = pc.startTui(workDir = WORK_DIR, model = "claude-sonnet-4-6", permissionMode = "default")
        pc.type("Reply with exactly this token and nothing else: $seedMarker")
        pc.awaitScreenContains(seedMarker, TUI_TIMEOUT_MS)

        val (sid, _) = harness.launchRcSession(
            workDir = WORK_DIR,
            permissionMode = "bypassAll",
            resumeSessionId = tui.sessionId
        )
        sessionId = sid
        assertEquals(tui.sessionId, sid)
        pc.awaitAttached(expected = true, timeoutMs = 60_000L)
        return tui
    }

    // ==================================================================
    // 25. Interleaved PC-typed and phone-sent turns.
    // ==================================================================

    /**
     * Alternate PC-typed and phone-sent turns on one attached session and
     * verify, for BOTH sources: every turn lands, none lands twice, and
     * per-source order is preserved.
     *
     * Each token embeds its source and sequence number, so ordering is checked
     * against the rendered transcript rather than assumed. Duplication is
     * checked by exact count, which is the assertion that catches the classic
     * attach failure -- a second desktop replaying the same history, or a
     * reconnect re-emitting frames after replay.
     */
    @Test
    fun interleavedPcAndPhoneTurnsAreNotLostDuplicatedOrReordered() {
        val tui = startAttachedSession("INTERLEAVE-SEED-${System.currentTimeMillis()}")
        ScreenshotHelper.take("01_attached")

        val run = System.currentTimeMillis()
        val phoneTokens = mutableListOf<String>()
        val pcTokens = mutableListOf<String>()

        for (i in 1..INTERLEAVE_ROUNDS) {
            if (i % 2 == 1) {
                val token = "IL-PHONE-$run-$i"
                phoneTokens += token
                val ignore = harness.assistantTexts().toSet()
                harness.sendMessage("Reply with exactly this token and nothing else: $token")
                harness.awaitAssistantReply(
                    matchSubstring = token,
                    ignoreTexts = ignore,
                    timeoutMs = REPLY_TIMEOUT_MS
                )
            } else {
                val token = "IL-PC-$run-$i"
                pcTokens += token
                val mark = pc.screenLength()
                val ignore = harness.assistantTexts().toSet()
                pc.type("Reply with exactly this token and nothing else: $token")
                pc.awaitScreenContains(token, TUI_TIMEOUT_MS, since = mark)
                // The PC turn's answer must reach the phone live.
                harness.awaitAssistantReply(
                    matchSubstring = token,
                    ignoreTexts = ignore,
                    timeoutMs = REPLY_TIMEOUT_MS
                )
            }
            // Serialized on purpose: two turns in flight at once is a different
            // (unsupported) scenario, and would make loss vs. queueing
            // indistinguishable.
            assertTrue(
                "Attachment must survive turn $i",
                pc.probe().attached
            )
        }
        ScreenshotHelper.take("02_interleave_done")
        Thread.sleep(HOLD_MS)

        // --- No loss. ---------------------------------------------------
        val rendered = harness.userTexts() + harness.assistantTexts()
        for (token in phoneTokens + pcTokens) {
            assertTrue(
                "Token $token was lost from the phone transcript; rendered=${rendered.size} rows",
                rendered.any { it.contains(token) }
            )
        }

        // --- No duplication. --------------------------------------------
        // A given token must appear in exactly one assistant bubble. More than
        // one means a frame was replayed or two desktops answered.
        for (token in phoneTokens + pcTokens) {
            val hits = harness.assistantTexts().count { it.contains(token) }
            assertEquals("Token $token was rendered $hits times in assistant bubbles", 1, hits)
        }

        // --- Per-source ordering. ---------------------------------------
        assertOrdered("phone", phoneTokens, rendered)
        assertOrdered("pc", pcTokens, rendered)

        // --- Still exactly one attachment on the original pid. ----------
        val probe = pc.probe()
        assertTrue(probe.attached)
        assertEquals("No takeover may have happened during the run", tui.pid, probe.pid)
        ScreenshotHelper.take("03_invariants_hold")
        Thread.sleep(HOLD_MS)
    }

    /** Assert [tokens] appear in the rendered transcript in the order sent. */
    private fun assertOrdered(source: String, tokens: List<String>, rendered: List<String>) {
        val positions = tokens.map { token ->
            rendered.indexOfFirst { it.contains(token) }
        }
        assertFalse("$source: a token is missing from the transcript ($positions)", positions.any { it < 0 })
        assertEquals(
            "$source ordering violated: tokens=$tokens landed at rows $positions",
            positions.sorted(), positions
        )
    }

    // ==================================================================
    // 26. Attach/detach churn -- no leak, no lockout, no spawn drift.
    // ==================================================================

    /**
     * Repeatedly open and leave the same live conversation.
     *
     * Three invariants per cycle, all of which a leak would break:
     *  - every cycle attaches (a leaked control socket, a stuck attachInFlight
     *    flag or a stale attemptToken would make some later cycle refuse);
     *  - every cycle detaches cleanly;
     *  - the headless spawn count never moves (any failed attach silently
     *    degrades to a spawn, so drift here is the sensitive detector for
     *    "attach broke after N cycles").
     */
    @Test
    fun repeatedAttachDetachCyclesDoNotLeakOrDegradeToSpawn() {
        val tui = startAttachedSession("CHURN-SEED-${System.currentTimeMillis()}")
        val baselineHeadless = pc.headlessCount()
        ScreenshotHelper.take("01_baseline")

        for (cycle in 1..ATTACH_CYCLES) {
            harness.endSessionAndReturn()
            harness.stopRemoteSession(sessionId)
            sessionId = ""
            pc.awaitAttached(expected = false, timeoutMs = 60_000L)

            val (sid, _) = harness.launchRcSession(
                workDir = WORK_DIR,
                permissionMode = "bypassAll",
                resumeSessionId = tui.sessionId
            )
            sessionId = sid

            val probe = pc.awaitAttached(expected = true, timeoutMs = 60_000L)
            assertEquals("cycle $cycle attached to the wrong pid", tui.pid, probe.pid)
            assertEquals("cycle $cycle attached to the wrong conversation", tui.sessionId, probe.sessionId)
            assertEquals(
                "cycle $cycle spawned a headless CLI, so its attach must have failed; " +
                    "argv=${pc.headlessArgvs()}",
                baselineHeadless, pc.headlessCount()
            )
            assertFalse("cycle $cycle killed the PC TUI", pc.hasExited())
        }
        ScreenshotHelper.take("02_churn_survived")
        Thread.sleep(HOLD_MS)

        // Still functional after the churn -- "attached" alone is not enough.
        val marker = "POST-CHURN-${System.currentTimeMillis()}"
        val mark = pc.screenLength()
        harness.sendMessage("Reply with exactly this token and nothing else: $marker")
        pc.awaitScreenContains(marker, TUI_TIMEOUT_MS, since = mark)
        ScreenshotHelper.take("03_still_functional")
        Thread.sleep(HOLD_MS)
    }

    // ==================================================================
    // 27. Rapid reopen churn: no duplicate frames, no competing desktop.
    // ==================================================================

    /**
     * Reopen the conversation repeatedly without waiting for a clean detach in
     * between, which is what a flapping network or an impatient user produces.
     *
     * The hazard this targets is the asynchronous WebSocket close outliving its
     * attach attempt: a stale close tearing down a NEWER attachment would show
     * up as attach_ok immediately followed by detached, and the next phone
     * message would spawn a competing desktop. Both symptoms are asserted.
     */
    @Test
    fun rapidReopenDoesNotProduceACompetingDesktopOrDuplicateFrames() {
        val tui = startAttachedSession("FLAP-SEED-${System.currentTimeMillis()}")
        val baselineHeadless = pc.headlessCount()
        ScreenshotHelper.take("01_attached")

        repeat(5) {
            harness.endSessionAndReturn()
            harness.stopRemoteSession(sessionId)
            // Deliberately do NOT await the detach: reopening mid-teardown is
            // the race under test.
            Thread.sleep(700)
            val (sid, _) = harness.launchRcSession(
                workDir = WORK_DIR,
                permissionMode = "bypassAll",
                resumeSessionId = tui.sessionId
            )
            sessionId = sid
        }

        // The end state must be a single healthy attachment on the original
        // pid, reached and HELD -- not one that flaps.
        val settled = pc.awaitAttached(expected = true, timeoutMs = 90_000L)
        assertEquals(tui.pid, settled.pid)
        Thread.sleep(15_000)
        val held = pc.probe()
        assertTrue("Attachment must be stable, not flapping", held.attached)
        assertEquals(tui.pid, held.pid)
        assertEquals(
            "Flap must not leave a competing spawned desktop; argv=${pc.headlessArgvs()}",
            baselineHeadless, pc.headlessCount()
        )
        ScreenshotHelper.take("02_settled")

        // One turn, one answer: a duplicated desktop would answer twice.
        val marker = "FLAP-PROBE-${System.currentTimeMillis()}"
        val ignore = harness.assistantTexts().toSet()
        harness.sendMessage("Reply with exactly this token and nothing else: $marker")
        harness.awaitAssistantReply(
            matchSubstring = marker,
            ignoreTexts = ignore,
            timeoutMs = REPLY_TIMEOUT_MS
        )
        Thread.sleep(5_000)
        assertEquals(
            "Exactly one answer must render for one question; a second means a " +
                "competing desktop or a replayed frame",
            1, harness.assistantTexts().count { it.contains(marker) }
        )
        ScreenshotHelper.take("03_single_answer")
        Thread.sleep(HOLD_MS)
    }

    // ==================================================================
    // 28. The existing spawned-session stress, run against an ATTACHED one.
    // ==================================================================

    /**
     * Attached variant of the tool-capability checks [RcStressTest] runs
     * against spawned sessions. Same prompts, different transport: an attached
     * session must be no less capable than a spawned one, and its tools run in
     * the PC user's live cwd.
     */
    @Test
    fun attachedSessionRunsToolsLikeASpawnedOne() {
        startAttachedSession("TOOLS-SEED-${System.currentTimeMillis()}")
        ScreenshotHelper.take("01_attached")

        val run = System.currentTimeMillis()

        // Bash.
        val echoToken = "attached-echo-$run"
        var ignore = harness.assistantTexts().toSet()
        harness.sendMessage("Run: echo $echoToken")
        val echoReply = harness.awaitAssistantReply(
            matchSubstring = echoToken, ignoreTexts = ignore, timeoutMs = REPLY_TIMEOUT_MS
        )
        assertTrue("Bash must work on an attached session", echoReply.contains(echoToken))
        ScreenshotHelper.take("02_bash")
        Thread.sleep(HOLD_MS)

        // Write + Read, in the live cwd. The relative path is the point: it
        // proves the tools run in the PC TUI's directory, not in some
        // directory a headless spawn was pinned to.
        val fileToken = "attached-file-$run"
        ignore = harness.assistantTexts().toSet()
        harness.sendMessage(
            "Write a file named attached-probe.txt in the current directory with the " +
                "exact content $fileToken, then read it back and reply with its content only."
        )
        val fileReply = harness.awaitAssistantReply(
            matchSubstring = fileToken, ignoreTexts = ignore, timeoutMs = REPLY_TIMEOUT_MS
        )
        assertTrue("Write+Read must work on an attached session", fileReply.contains(fileToken))
        ScreenshotHelper.take("03_write_read")
        Thread.sleep(HOLD_MS)

        // The file really is in the live session's cwd -- verified from the PC
        // side, independent of anything the model claimed.
        val mark = pc.screenLength()
        pc.type("Run: cat $WORK_DIR/attached-probe.txt")
        pc.awaitScreenContains(fileToken, TUI_TIMEOUT_MS, since = mark)
        ScreenshotHelper.take("04_verified_on_pc")
        Thread.sleep(HOLD_MS)
    }
}
