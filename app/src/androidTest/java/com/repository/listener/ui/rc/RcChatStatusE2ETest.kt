package com.repository.listener.ui.rc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Iter 8 E2E tests for the RC chat UI status/context machinery.
 *
 * All 8 tests now drive REAL AI conversations via the orchestrator:
 *   - sendMessage(...) types into rcInput and clicks send (POST -> orchestrator -> CLI).
 *   - awaitAssistantReply(...) polls for the actual assistant TextMessage row.
 *   - No broadcast injection. No mocked state.
 *
 * Per-test budgets are widened to 60-90s to accommodate live AI latency.
 *
 * Requires a reachable orchestrator at the configured WS URL and a working
 * RC session backend.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RcChatStatusE2ETest {

    companion object {
        // Default workDir for tests that don't care about the project (always exists).
        private const val DEFAULT_WORK_DIR = "/workspace/project"
        // shareitt workDir for the shareitt-specific test.
        private const val SHAREITT_WORK_DIR = "/workspace/other-project"
        private const val PKG = "com.repository.listener"

        // Generous live-AI deadlines.
        private const val SHORT_REPLY_TIMEOUT_MS = 60_000L
        private const val LONG_REPLY_TIMEOUT_MS = 90_000L
        private const val STATUS_TIMEOUT_MS = 15_000L
        // How long to hold the final UI state visible for screen recording.
        private const val HOLD_MS = 2_000L
    }

    private lateinit var device: UiDevice
    private lateinit var harness: RcChatHarness
    private lateinit var sessionId: String
    private var workDir: String = DEFAULT_WORK_DIR

    @Before
    fun setup() {
        ScreenshotHelper.resetCounter()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        harness = RcChatHarness(device)
        // Tests that need shareitt override workDir before super-setup by setting
        // it directly in their @Test body would be racy; instead, the shareitt
        // test does its own session start. All other tests use DEFAULT_WORK_DIR.
        sessionId = harness.launchRcSession(workDir)
    }

    @After
    fun teardown() {
        try { harness.endSessionAndReturn() } catch (_: Throwable) {}
        // Stop the orchestrator-side pc-agent CLI process so subsequent tests
        // don't compete with stale bun processes for orchestrator capacity.
        try { harness.stopRemoteSession(sessionId) } catch (_: Throwable) {}
        // Brief settling window so the next @Before's startSession lands cleanly.
        try { Thread.sleep(2_000) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------
    // 1. Real backend round-trip: shareitt session responds and does not hang
    // ------------------------------------------------------------------

    @Test
    fun shareittSessionRespondsAndDoesNotHang() {
        // Replace the default-workdir session set up in @Before with a real
        // shareitt-workdir session, so this test exercises the actual project.
        try { harness.endSessionAndReturn() } catch (_: Throwable) {}
        sessionId = harness.launchRcSession(SHAREITT_WORK_DIR)
        val ignore = harness.assistantTexts().toSet()
        harness.sendMessage("hi, just say the single word hello back")
        // Real AI: prefer "hello" but accept any non-empty assistant text as a sign of life.
        val reply = harness.awaitAssistantReply(
            matchSubstring = "hello",
            ignoreTexts = ignore,
            timeoutMs = SHORT_REPLY_TIMEOUT_MS
        )
        assertNotNull("Should receive non-empty assistant reply", reply)
        assertTrue("Reply should mention 'hello'", reply.contains("hello", ignoreCase = true))
        ScreenshotHelper.take("iter8_01_shareitt_responds")
        Thread.sleep(HOLD_MS)
    }

    // ------------------------------------------------------------------
    // 2. Status state machine: Sending -> Thinking -> Thought (live AI)
    // ------------------------------------------------------------------

    @Test
    fun statusTransitionsSendingThinkingThought() {
        val ignore = harness.assistantTexts().toSet()
        // Force a tool call: Read introduces multi-second Thinking before any text streams.
        harness.sendMessage("Read /workspace/project/CLAUDE.md and summarize in one paragraph")

        // The view transitions from GONE to VISIBLE on send. On some devices
        // the accessibility tree rebuild takes longer than SENDING_MIN_VISIBLE_MS
        // (800ms), so the first UiAutomator-visible state may already be
        // "Thinking" rather than "Sending". Accept either as proof the state
        // machine activated.
        val busy = harness.awaitStatus(
            { it?.contains("Sending") == true || it?.contains("Thinking") == true },
            timeoutMs = STATUS_TIMEOUT_MS
        )
        assertNotNull("Should reach Sending or Thinking state", busy)
        ScreenshotHelper.take("iter8_02a_busy")

        // Thinking appears once orchestrator has accepted and is producing.
        val thinking = harness.awaitStatus({ it?.contains("Thinking") == true }, timeoutMs = 30_000L)
        assertNotNull("Should reach Thinking state", thinking)
        ScreenshotHelper.take("iter8_02b_thinking")

        // Thought appears on completion.
        val thought = harness.awaitStatus({ it?.contains("Thought for") == true }, timeoutMs = LONG_REPLY_TIMEOUT_MS)
        assertNotNull("Should reach Thought state after final chunk", thought)

        // And a real assistant reply must be present.
        val reply = harness.awaitAssistantReply(
            ignoreTexts = ignore,
            timeoutMs = SHORT_REPLY_TIMEOUT_MS
        )
        assertNotNull("Assistant reply should be present after Thought", reply)
        ScreenshotHelper.take("iter8_02c_thought")
        Thread.sleep(HOLD_MS)
    }

    // ------------------------------------------------------------------
    // 3. Thinking timer ticks while real AI is generating
    // ------------------------------------------------------------------

    @Test
    fun thinkingTimerTicks() {
        val ignore = harness.assistantTexts().toSet()
        // Tool call prompt: forces multi-second Thinking before any text streams,
        // and the long output keeps Thinking visible while sub-tasks run.
        harness.sendMessage("Read /workspace/project/CLAUDE.md and write a 30-line summary, one sentence per line.")

        val first = harness.awaitStatus({ it?.contains("Thinking") == true }, timeoutMs = 60_000L)
        assertNotNull("Thinking should appear", first)
        ScreenshotHelper.take("iter8_03a_tick0")

        Thread.sleep(3_000)
        val second = harness.currentStatus()
        ScreenshotHelper.take("iter8_03b_tick1")

        Thread.sleep(3_000)
        val third = harness.currentStatus()
        ScreenshotHelper.take("iter8_03c_tick2")

        // Require at least 2 distinct samples among the 3 collected (timer ticking),
        // OR the run finished with a real reply (also valid: timer did tick to completion).
        val samples = listOf(first, second, third).filterNotNull()
        val distinctCount = samples.toSet().size
        // Hard assertion: timer must visibly tick. Three samples taken at 3s intervals
        // while a tool-call prompt is generating MUST produce at least 2 distinct values.
        assertTrue(
            "Thinking elapsed should produce at least 2 distinct samples (first='$first' second='$second' third='$third')",
            distinctCount >= 2
        )
        // And we must still receive a real assistant reply for the turn we initiated.
        val reply = harness.awaitAssistantReply(ignoreTexts = ignore, timeoutMs = LONG_REPLY_TIMEOUT_MS)
        assertNotNull("Assistant reply must arrive after thinking timer ticked", reply)
        Thread.sleep(HOLD_MS)
    }

    // ------------------------------------------------------------------
    // 4. contextPct rendering against a real turn
    // ------------------------------------------------------------------

    @Test
    fun contextPercentageUpdatesDuringTurn() {
        val ignore = harness.assistantTexts().toSet()
        val before = harness.currentContextPct()

        harness.sendMessage("Reply only with the word ready")
        harness.awaitAssistantReply(ignoreTexts = ignore, timeoutMs = SHORT_REPLY_TIMEOUT_MS)
        val after = harness.currentContextPct()

        // The pct field must render after a real turn (non-null). If both observed
        // values are non-null we further assert they make sense (>= 0).
        assertNotNull("contextPct should render after a real turn", after)
        assertTrue("contextPct should be non-negative (was $after)", (after ?: -1) >= 0)
        // If we had a baseline, the value typically grows; allow equality but not regression.
        if (before != null && after != null) {
            assertTrue(
                "contextPct should not regress (before=$before after=$after)",
                after >= before
            )
        }
        ScreenshotHelper.take("iter8_04_context_pct")
        Thread.sleep(HOLD_MS)
    }

    // ------------------------------------------------------------------
    // 5. Agent tool renders distinctly from regular tool rows (real Task call)
    // ------------------------------------------------------------------

    @Test
    fun agentToolRendersDistinctly() {
        val ignore = harness.assistantTexts().toSet()
        // Ask the CLI to dispatch a sub-agent. This produces an Agent: row in the chat.
        harness.sendMessage(
            "Use the Task tool with subagent_type=general-purpose to look up what the file Repository/CLAUDE.md describes, then summarize in one sentence."
        )

        // Agent rows are prefixed "Agent" by RcDetailAdapter when isAgent=true.
        // Format: "Agent (Calling): <task>" -> "Agent (Complete): <task>".
        val agentRow = device.wait(
            Until.findObject(By.textContains("Agent")),
            LONG_REPLY_TIMEOUT_MS
        )
        assertNotNull("Agent tool row should render with 'Agent' label", agentRow)
        // The agent row must surface the agent's purpose -- subagent_type or task
        // description -- not just a status word.
        val agentRowText = agentRow.text ?: ""
        assertTrue(
            "Agent row should expose agent identity/task, not just status (was: '$agentRowText')",
            agentRowText.contains("general-purpose", ignoreCase = true) ||
                agentRowText.contains("CLAUDE", ignoreCase = true) ||
                agentRowText.contains("summari", ignoreCase = true) ||
                agentRowText.contains("look", ignoreCase = true)
        )
        assertTrue(
            "Agent row must not collapse into a bare status word",
            !agentRowText.contains("Agent: complete", ignoreCase = true) &&
                !agentRowText.equals("Agent (Complete):", ignoreCase = true)
        )
        ScreenshotHelper.take("iter8_05a_agent_row")

        // Tier A live-counts assertion: poll the agent row text every ~1s
        // while the row is still in Calling/running state, capturing the
        // highest tool count and token count we observe. With orchestrator
        // sub-agent progress forwarding, both values must increment above 0
        // BEFORE the row reaches Complete -- not just appear at Complete.
        var maxLiveTools = 0
        var maxLiveTokens = 0.0
        val callingDeadline = System.currentTimeMillis() + LONG_REPLY_TIMEOUT_MS
        while (System.currentTimeMillis() < callingDeadline) {
            val row = device.findObject(By.textContains("Agent"))
            val txt = row?.text ?: ""
            if (txt.contains("Complete", ignoreCase = true)) break
            // Parse "<N> tools" segment.
            Regex("(\\d+)\\s*tools").find(txt)?.let { m ->
                val n = m.groupValues[1].toIntOrNull() ?: 0
                if (n > maxLiveTools) maxLiveTools = n
            }
            // Parse "<X.Y>k tokens" or "<X.Y>M tokens" segment.
            Regex("(\\d+(?:\\.\\d+)?)([kM])\\s*tokens").find(txt)?.let { m ->
                val v = m.groupValues[1].toDoubleOrNull() ?: 0.0
                val mult = if (m.groupValues[2] == "M") 1_000_000.0 else 1_000.0
                val tokens = v * mult
                if (tokens > maxLiveTokens) maxLiveTokens = tokens
            }
            Thread.sleep(1000L)
        }
        android.util.Log.i(
            "RcChatStatusE2ETest",
            "Live counts captured during Calling: tools=$maxLiveTools tokens=$maxLiveTokens"
        )

        // The agent row must visibly transition to the complete state
        // before the test finishes -- this catches the gap where orchestrator emits
        // tool_result for the Task but the phone fails to update the existing row.
        val completedRow = device.wait(
            Until.findObject(By.textContains("Agent (Complete)")),
            LONG_REPLY_TIMEOUT_MS
        )
        assertNotNull("Agent row must visibly reach Agent (Complete) state", completedRow)
        // Stats segments: tools count + tokens (k/M) + elapsed (Xm Ys).
        // The orchestrator parses Claude Code's <usage> block from the Task
        // tool_result and forwards them; the adapter must render them on the
        // Complete row as " - <N> tools - <X.Yk|M> tokens - <Xm Ys>".
        // Elapsed is always synthesized client-side. tokens/tools depend on
        // the subagent emitting a <usage> trailer in its tool_result body --
        // when the parent stream-json doesn't forward subagent usage (current
        // CLI behavior on intermediate streams), tokens may legitimately be
        // absent. Treat tokens as a soft assertion: require the literal
        // "tokens" word, but only require the numeric k/M form when present.
        val completedText = completedRow.text ?: ""
        assertTrue(
            "Complete row should show elapsed time in 'Xm Ys' form (was: '$completedText')",
            Regex("\\d+m \\d+s").containsMatchIn(completedText)
        )
        // HARD assertion: subagent <usage> trailer must be forwarded by orchestrator
        // and rendered as numeric "<X.Y>k tokens" or "<X.Y>M tokens" on the Complete
        // row. If this fails it surfaces a real regression in the orchestrator/CLI
        // <usage> propagation path -- not a soft warning.
        assertTrue(
            "Complete row must show numeric '<N>k|M tokens' from subagent <usage> trailer (was: '$completedText')",
            Regex("\\d+(?:\\.\\d+)?[kM] tokens").containsMatchIn(completedText)
        )
        // HARD assertion: orchestrator must forward sub-agent progress events with
        // tool/token counts during Calling state, not only at completion. If this
        // fails, the debounce/forwarding pipeline is dropping intermediate progress.
        assertTrue(
            "Live tool/token counts must be observed during Calling state " +
                "(maxTools=$maxLiveTools maxTokens=$maxLiveTokens, completedText='$completedText')",
            maxLiveTools > 0 || maxLiveTokens > 0.0
        )
        ScreenshotHelper.take("iter8_05a_agent_row_complete")

        // Regression guard: subagent progress emits MUST update the parent
        // agent row, not create a second one. Assert exactly ONE on-screen
        // row whose text begins with "Agent (" -- multiple rows means the
        // sub-agent progress messages used a different toolUseId/toolName
        // and were rendered as a separate ToolStatus card.
        val agentRows = device.findObjects(By.textStartsWith("Agent ("))
        assertEquals(
            "Expected exactly ONE Agent row on screen, found ${agentRows?.size ?: 0}: " +
                (agentRows?.joinToString(" | ") { it.text ?: "" } ?: ""),
            1,
            agentRows?.size ?: 0
        )

        // And we should still get a final assistant reply.
        harness.awaitAssistantReply(ignoreTexts = ignore, timeoutMs = LONG_REPLY_TIMEOUT_MS)
        ScreenshotHelper.take("iter8_05b_agent_reply")
        Thread.sleep(HOLD_MS)
    }

    // ------------------------------------------------------------------
    // 6. Stop button clears Thinking immediately on a long-running prompt
    // ------------------------------------------------------------------

    @Test
    fun stopButtonClearsThinkingImmediately() {
        // Tool-call prompt: guarantees Thinking is visible long enough to tap stop.
        harness.sendMessage("Read /workspace/project/CLAUDE.md and then write a 50-line numbered summary slowly.")
        // Accept any non-final busy state (Sending or Thinking). Cold-start can keep
        // Sending visible long enough that Thinking arrives only after 30s; the test's
        // intent is "tap stop while AI is busy", which doesn't require Thinking specifically.
        val pre = harness.awaitStatus(
            { s -> !s.isNullOrEmpty() && !s.contains("Thought") },
            timeoutMs = 60_000L
        )
        assertNotNull("Should reach a busy state before stop (was: '${harness.currentStatus()}')", pre)
        ScreenshotHelper.take("iter8_06a_thinking")

        harness.tapStop()

        // After stop, indicator must NOT remain in Thinking. Thought / Idle / null all valid.
        val after = harness.awaitStatus(
            { it == null || !it.contains("Thinking") },
            timeoutMs = 5_000
        )
        assertTrue(
            "After stop, indicator must not stay in Thinking (was: '${harness.currentStatus()}')",
            after == null || !after.contains("Thinking")
        )
        ScreenshotHelper.take("iter8_06b_stopped")
        Thread.sleep(HOLD_MS)
    }

    // ------------------------------------------------------------------
    // 7. Re-entering the session preserves real messages and contextPct
    // ------------------------------------------------------------------

    @Test
    fun reEnteringSessionPreservesContextPct() {
        val ignore = harness.assistantTexts().toSet()
        harness.sendMessage("Reply with one short fact about the moon.")
        val reply = harness.awaitAssistantReply(ignoreTexts = ignore, timeoutMs = SHORT_REPLY_TIMEOUT_MS)
        val pctBefore = harness.currentContextPct()
        assertNotNull("contextPct should render after first turn", pctBefore)
        ScreenshotHelper.take("iter8_07a_before_reenter")

        // Leave RC and re-enter same session.
        harness.endSessionAndReturn()
        Thread.sleep(800)
        harness.reenterRcSession(sessionId, workDir)
        // Allow transcript catch-up.
        Thread.sleep(2_000)

        // The earlier real assistant reply must still be visible.
        val rePresent = device.wait(
            Until.findObject(By.textContains(reply.take(20))),
            LONG_REPLY_TIMEOUT_MS
        )
        assertNotNull("Earlier assistant reply should re-render after re-entry", rePresent)

        val pctAfter = harness.currentContextPct()
        assertNotNull("contextPct should be present after re-entering session", pctAfter)
        ScreenshotHelper.take("iter8_07b_after_reenter")
        Thread.sleep(HOLD_MS + 500)
    }

    // ------------------------------------------------------------------
    // 8. Rapid consecutive real messages are both handled by the state machine
    // ------------------------------------------------------------------

    @Test
    fun rapidConsecutiveMessages() {
        val ignore = harness.assistantTexts().toSet()

        harness.sendMessage("Reply only with: one")
        // Wait for first turn to fully settle so the second send isn't merged.
        val first = harness.awaitAssistantReply(
            matchSubstring = "one",
            ignoreTexts = ignore,
            timeoutMs = SHORT_REPLY_TIMEOUT_MS
        )
        assertNotNull("First reply should arrive", first)

        val ignoreAfterFirst = harness.assistantTexts().toSet()
        harness.sendMessage("Reply only with: two")
        val second = harness.awaitAssistantReply(
            matchSubstring = "two",
            ignoreTexts = ignoreAfterFirst,
            timeoutMs = SHORT_REPLY_TIMEOUT_MS
        )
        assertNotNull("Second reply should arrive", second)
        assertNotEquals("Both replies should be distinct messages", first, second)

        // Verify input is still functional (state machine returned to idle).
        val input = device.findObject(By.res(PKG, "rcInput"))
        assertNotNull("rcInput should still exist and be reachable", input)
        ScreenshotHelper.take("iter8_08_rapid")
        Thread.sleep(HOLD_MS)
    }
}
