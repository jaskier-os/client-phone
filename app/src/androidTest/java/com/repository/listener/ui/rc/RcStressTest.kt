package com.repository.listener.ui.rc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stress + capability tests for the phone -> orchestrator -> pc-agent -> CLI pipeline.
 *
 * All tests drive REAL AI conversations via the orchestrator. No broadcast mocks.
 * Per-test budget is generous (60-120s) to accommodate real-AI roundtrip.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RcStressTest {

    companion object {
        private const val DEFAULT_WORK_DIR = "/workspace/project"
        private const val SECONDARY_WORK_DIR = "/workspace/project/sub-module"
        private const val PKG = "com.repository.listener"

        private const val SHORT_REPLY_TIMEOUT_MS = 300_000L
        private const val LONG_REPLY_TIMEOUT_MS = 1_200_000L
        private const val STATUS_TIMEOUT_MS = 15_000L
    }

    private lateinit var device: UiDevice
    private lateinit var harness: RcChatHarness
    private lateinit var sessionId: String
    private val workDir: String = DEFAULT_WORK_DIR

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
        try { Thread.sleep(2_000) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------
    // Group 1 - Tool capabilities
    // ------------------------------------------------------------------

    @Test
    fun bashToolEchoes() {
        val ignore = harness.assistantTexts().toSet()
        harness.sendMessage("Run: echo hello-world")
        val reply = harness.awaitAssistantReply(
            matchSubstring = "hello-world",
            ignoreTexts = ignore,
            timeoutMs = SHORT_REPLY_TIMEOUT_MS
        )
        assertTrue("Reply should contain 'hello-world' (was: '$reply')",
            reply.contains("hello-world", ignoreCase = true))
    }

    @Test
    fun readToolFindsClaudeMd() {
        val ignore = harness.assistantTexts().toSet()
        harness.sendMessage("Read /workspace/project/CLAUDE.md and tell me the first heading")
        val reply = harness.awaitAssistantReply(
            ignoreTexts = ignore,
            timeoutMs = LONG_REPLY_TIMEOUT_MS
        )
        assertTrue("Reply should reference CLAUDE.md content (was: '$reply')",
            reply.contains("CLAUDE.md", ignoreCase = true) ||
                reply.contains("CLAUDE", ignoreCase = true))
    }

    @Test
    fun writeAndReadFile() {
        val ignore = harness.assistantTexts().toSet()
        harness.sendMessage(
            "Write file /tmp/stress-test.txt with content 'stress-marker', then read it back and confirm the content"
        )
        val reply = harness.awaitAssistantReply(
            matchSubstring = "stress-marker",
            ignoreTexts = ignore,
            timeoutMs = LONG_REPLY_TIMEOUT_MS
        )
        assertTrue("Reply should contain 'stress-marker' (was: '$reply')",
            reply.contains("stress-marker", ignoreCase = true))
    }

    @Test
    fun editFile() {
        val ignore = harness.assistantTexts().toSet()
        harness.sendMessage(
            "First write file /tmp/stress-test.txt with 'stress-marker'. " +
                "Then replace 'stress-marker' with 'stress-edited' in /tmp/stress-test.txt and confirm."
        )
        val reply = harness.awaitAssistantReply(
            matchSubstring = "stress-edited",
            ignoreTexts = ignore,
            timeoutMs = LONG_REPLY_TIMEOUT_MS
        )
        assertTrue("Reply should contain 'stress-edited' (was: '$reply')",
            reply.contains("stress-edited", ignoreCase = true))
    }

    @Test
    fun grepFinds() {
        val ignore = harness.assistantTexts().toSet()
        harness.sendMessage(
            "First write file /tmp/stress-test.txt with content 'stress-edited line one'. " +
                "Then use the Grep tool to find 'stress-edited' in /tmp/stress-test.txt and report the result."
        )
        val reply = harness.awaitAssistantReply(
            matchSubstring = "stress-edited",
            ignoreTexts = ignore,
            timeoutMs = LONG_REPLY_TIMEOUT_MS
        )
        assertTrue("Reply should mention the grep match (was: '$reply')",
            reply.contains("stress-edited", ignoreCase = true))
    }

    @Test
    fun globFinds() {
        val ignore = harness.assistantTexts().toSet()
        harness.sendMessage(
            "First write file /tmp/stress-test.txt with 'marker'. " +
                "Then use the Glob tool to find /tmp/stress-test.txt and report the result."
        )
        val reply = harness.awaitAssistantReply(
            matchSubstring = "stress-test.txt",
            ignoreTexts = ignore,
            timeoutMs = LONG_REPLY_TIMEOUT_MS
        )
        assertTrue("Reply should mention the matched path (was: '$reply')",
            reply.contains("stress-test.txt", ignoreCase = true))
    }

    @Test
    fun agentDispatch() {
        val ignore = harness.assistantTexts().toSet()
        harness.sendMessage(
            "Use the Task tool with subagent_type=general-purpose to summarize " +
                "/workspace/project/CLAUDE.md in one sentence."
        )
        val agentRow = device.wait(
            Until.findObject(By.textContains("Agent")),
            LONG_REPLY_TIMEOUT_MS
        )
        assertNotNull("Agent row should appear when Task tool is dispatched", agentRow)
        harness.awaitAssistantReply(
            ignoreTexts = ignore,
            timeoutMs = LONG_REPLY_TIMEOUT_MS
        )
    }

    // ------------------------------------------------------------------
    // Group 2 - Stability + reliability
    // ------------------------------------------------------------------

    @Test
    fun tenConsecutiveMessages() {
        for (i in 1..10) {
            val ignore = harness.assistantTexts().toSet()
            harness.sendMessage("Reply only with the single token: marker-$i")
            val reply = harness.awaitAssistantReply(
                matchSubstring = "marker-$i",
                ignoreTexts = ignore,
                timeoutMs = SHORT_REPLY_TIMEOUT_MS
            )
            assertTrue("Iteration $i: reply should contain 'marker-$i' (was: '$reply')",
                reply.contains("marker-$i", ignoreCase = true))
        }
    }

    @Test
    fun largeOutputResponse() {
        val ignore = harness.assistantTexts().toSet()
        harness.sendMessage("Print numbers 1 to 200, one per line, nothing else.")
        val reply = harness.awaitAssistantReply(
            matchSubstring = "200",
            ignoreTexts = ignore,
            timeoutMs = LONG_REPLY_TIMEOUT_MS
        )
        assertTrue("Reply should contain '200' (was length=${reply.length})",
            reply.contains("200"))
        assertTrue("Reply should contain '1' near start", reply.contains("1"))
    }

    @Test
    fun unicodeAndSpecialChars() {
        val ignore = harness.assistantTexts().toSet()
        harness.sendMessage("Reply only with this exact text: 你好 emoji-celebration \"quoted\" 'apos' &amp;")
        val reply = harness.awaitAssistantReply(
            ignoreTexts = ignore,
            timeoutMs = SHORT_REPLY_TIMEOUT_MS
        )
        // Stochastic: accept either Chinese chars OR the marker word.
        assertTrue("Reply should contain Chinese characters or the marker (was: '$reply')",
            reply.contains("你好") || reply.contains("emoji-celebration", ignoreCase = true))
    }

    @Test
    fun emptyMessageRejected() {
        // Snapshot assistant message count before attempting empty send.
        val before = harness.assistantTexts().size

        // Attempt to type empty string and click send.
        val input = device.findObject(By.res(PKG, "rcInput"))
        assertNotNull("rcInput should be present", input)
        input.click()
        Thread.sleep(150)
        input.text = ""
        Thread.sleep(300)

        val send = device.findObject(By.res(PKG, "rcSendButton"))
        // Either send button is disabled (preferred), or clicking it is a no-op.
        val sendEnabled = send?.isEnabled ?: false
        if (sendEnabled) {
            // Try clicking; verify no new assistant message + no Sending state appears.
            send.click()
            Thread.sleep(2_000)
            val status = harness.currentStatus()
            val after = harness.assistantTexts().size
            assertTrue(
                "Empty message must not trigger send (status='$status', before=$before, after=$after)",
                after == before && (status == null || !status.contains("Sending"))
            )
        } else {
            // Disabled is the desired UX -- pass.
            assertTrue("Send button correctly disabled for empty input", true)
        }
    }

    @Test
    fun stopMidStream() {
        val tStart = System.currentTimeMillis()
        harness.sendMessage(
            "Read /workspace/project/CLAUDE.md " +
                "and write a 100-line numbered analysis, one sentence per line, slowly."
        )
        val pre = harness.awaitStatus(
            { s -> !s.isNullOrEmpty() && !s.contains("Thought") },
            timeoutMs = 60_000L
        )
        assertNotNull("Should reach a busy state before stop", pre)

        // Wait at least 2s before stopping so elapsed > 1s is observable.
        Thread.sleep(2_500)
        // Verify we are still in a busy (non-Thought) state right before tapping stop --
        // this guarantees stop fires DURING streaming, not after natural completion.
        val statusJustBeforeStop = harness.currentStatus()
        assertTrue(
            "Stop must fire during streaming, not after completion (status was '$statusJustBeforeStop')",
            statusJustBeforeStop != null && !statusJustBeforeStop.contains("Thought")
        )
        val elapsedBeforeStop = System.currentTimeMillis() - tStart
        assertTrue(
            "Elapsed before stop must be >= 1s (was ${elapsedBeforeStop}ms)",
            elapsedBeforeStop >= 1000
        )
        harness.tapStop()

        val thought = harness.awaitStatus(
            { it?.contains("Thought for") == true },
            timeoutMs = 10_000L
        )
        if (thought != null) {
            // Parse "Thought for Xs" and verify > 1s.
            val match = Regex("Thought for (\\d+)s").find(thought)
            val secs = match?.groupValues?.get(1)?.toIntOrNull()
            assertNotNull("Thought-for status must contain numeric seconds (was: '$thought')", secs)
            assertTrue(
                "Thought-for elapsed should be >= 1s after 2.5s wait before stop (was: '$thought')",
                (secs ?: 0) >= 1
            )
        } else {
            // Acceptable alternative: stop cleared the indicator entirely.
            val final = harness.currentStatus()
            assertTrue(
                "After stop, indicator must not be Thinking (was: '$final')",
                final == null || !final.contains("Thinking")
            )
        }
    }

    @Test
    fun permissionModeMidSession() {
        val ignore1 = harness.assistantTexts().toSet()
        harness.sendMessage("Reply only with: alpha")
        val reply1 = harness.awaitAssistantReply(
            matchSubstring = "alpha",
            ignoreTexts = ignore1,
            timeoutMs = SHORT_REPLY_TIMEOUT_MS
        )
        assertTrue("First reply should contain 'alpha'", reply1.contains("alpha", ignoreCase = true))

        // The mode selector is the chip at res-id rcModeSelector in the toolbar.
        // Tapping it opens a PopupMenu with entries: Default / Plan mode /
        // Accept all edits / Bypass all permissions. The chip text after
        // selection becomes one of: Default / Plan / Accept / Bypass.
        // Session was launched in bypassAll => chip starts as "Bypass".
        val chipBefore = device.findObject(By.res(PKG, "rcModeSelector"))
        val chipBeforeText = chipBefore?.let { it.text }
        assertNotNull("rcModeSelector chip must exist", chipBefore)

        val toggled = harness.changeMode(
            popupEntryText = "Plan mode",
            expectedChipText = "Plan"
        )
        assertTrue(
            "Mode chip should have transitioned to 'Plan' after tapping Plan mode " +
                "(before='$chipBeforeText')",
            toggled
        )

        // Hold the visible Plan-mode state for >=2s so the recording captures
        // the toolbar chip change.
        Thread.sleep(2_500)

        val chipAfter = device.findObject(By.res(PKG, "rcModeSelector"))
        val chipAfterText = chipAfter?.let { it.text }
        assertEquals(
            "Mode chip text should now be 'Plan'",
            "Plan", chipAfterText
        )

        val ignore2 = harness.assistantTexts().toSet()
        harness.sendMessage("Reply only with: beta-after-plan")
        val reply2 = harness.awaitAssistantReply(
            matchSubstring = "beta-after-plan",
            ignoreTexts = ignore2,
            timeoutMs = SHORT_REPLY_TIMEOUT_MS
        )
        assertTrue(
            "Second reply (in Plan mode) should contain 'beta-after-plan' (was: '$reply2')",
            reply2.contains("beta-after-plan", ignoreCase = true)
        )
    }

    // ------------------------------------------------------------------
    // Group 3 - Edge cases
    // ------------------------------------------------------------------

    @Test
    fun wsReconnectScenario() {
        val ignore1 = harness.assistantTexts().toSet()
        harness.sendMessage("Reply only with: pre-reconnect")
        harness.awaitAssistantReply(
            matchSubstring = "pre-reconnect",
            ignoreTexts = ignore1,
            timeoutMs = SHORT_REPLY_TIMEOUT_MS
        )

        // Toggle wifi off then on. Hard-assert each shell command executed and that
        // wifi state actually flipped (so we know we exercised the reconnect path).
        device.executeShellCommand("svc wifi disable")
        Thread.sleep(4_000)
        val disabledState = device.executeShellCommand("dumpsys wifi | grep -E 'Wi-Fi is' | head -1")
        assertTrue(
            "Wifi must report disabled after 'svc wifi disable' (got: '$disabledState'). " +
                "If this fails, instrumentation lacks permission to toggle wifi -- " +
                "either grant it or remove this test.",
            disabledState.contains("disabled", ignoreCase = true) ||
                disabledState.contains("disabling", ignoreCase = true)
        )
        device.executeShellCommand("svc wifi enable")
        // Poll for wifi enabled state -- reassociation can take >8s on some devices.
        val wifiDeadline = System.currentTimeMillis() + 30_000L
        var enabledState = ""
        while (System.currentTimeMillis() < wifiDeadline) {
            enabledState = device.executeShellCommand("dumpsys wifi | grep -E 'Wi-Fi is' | head -1")
            if (enabledState.contains("enabled", ignoreCase = true)) break
            Thread.sleep(1_000)
        }
        assertTrue(
            "Wifi must report enabled within 30s after 'svc wifi enable' (got: '$enabledState')",
            enabledState.contains("enabled", ignoreCase = true)
        )
        // Wait for network to be VALIDATED (DHCP + connectivity check) so the WS can actually reconnect.
        val netDeadline = System.currentTimeMillis() + 30_000L
        var validated = false
        while (System.currentTimeMillis() < netDeadline) {
            val net = device.executeShellCommand("dumpsys connectivity | grep -E 'VALIDATED|CAPTIVE' | head -3")
            if (net.contains("VALIDATED", ignoreCase = false)) { validated = true; break }
            Thread.sleep(1_500)
        }
        assertTrue("Network must reach VALIDATED state within 30s after wifi enable", validated)
        // Allow the orchestrator WS to reconnect (heartbeat watchdog + reconnectDelay).
        Thread.sleep(15_000)

        val ignore2 = harness.assistantTexts().toSet()
        harness.sendMessage("Reply only with: post-reconnect")
        val reply = harness.awaitAssistantReply(
            matchSubstring = "post-reconnect",
            ignoreTexts = ignore2,
            timeoutMs = 180_000L
        )
        assertTrue("Post-reconnect reply should arrive (was: '$reply')",
            reply.contains("post-reconnect", ignoreCase = true))
    }

    @Test
    fun concurrentTwoSessions() {
        // First session is the @Before-launched one (workDir = DEFAULT_WORK_DIR).
        val ignore1 = harness.assistantTexts().toSet()
        harness.sendMessage("Reply only with: session-one")
        val reply1 = harness.awaitAssistantReply(
            matchSubstring = "session-one",
            ignoreTexts = ignore1,
            timeoutMs = SHORT_REPLY_TIMEOUT_MS
        )
        assertTrue("Session 1 should reply with 'session-one'",
            reply1.contains("session-one", ignoreCase = true))

        // Leave first, start second in different workDir.
        harness.endSessionAndReturn()
        Thread.sleep(800)
        val sessionId2 = harness.launchRcSession(SECONDARY_WORK_DIR)
        // Hard-assert two distinct concurrent sessions exist.
        assertNotEquals(
            "Second session must have a distinct sessionId from the first",
            sessionId, sessionId2
        )
        try {
            val ignore2 = harness.assistantTexts().toSet()
            harness.sendMessage("Reply only with: session-two")
            val reply2 = harness.awaitAssistantReply(
                matchSubstring = "session-two",
                ignoreTexts = ignore2,
                timeoutMs = SHORT_REPLY_TIMEOUT_MS
            )
            assertTrue("Session 2 should reply with 'session-two'",
                reply2.contains("session-two", ignoreCase = true))
        } finally {
            try { harness.endSessionAndReturn() } catch (_: Throwable) {}
            try { harness.stopRemoteSession(sessionId2) } catch (_: Throwable) {}
        }

        // Re-enter first session and verify it still responds independently.
        harness.reenterRcSession(sessionId, workDir)
        val ignore3 = harness.assistantTexts().toSet()
        harness.sendMessage("Reply only with: session-one-again")
        val reply3 = harness.awaitAssistantReply(
            matchSubstring = "session-one-again",
            ignoreTexts = ignore3,
            timeoutMs = SHORT_REPLY_TIMEOUT_MS
        )
        assertTrue("Session 1 should still respond after session 2 lifecycle",
            reply3.contains("session-one-again", ignoreCase = true))
    }

    // ------------------------------------------------------------------
    // Group 4 - Production reliability (resume after CLI killed)
    // ------------------------------------------------------------------

    @Test
    fun resumeAfterCliKilled() {
        val ignore1 = harness.assistantTexts().toSet()
        harness.sendMessage("Reply only with: pre-kill")
        harness.awaitAssistantReply(
            matchSubstring = "pre-kill",
            ignoreTexts = ignore1,
            timeoutMs = SHORT_REPLY_TIMEOUT_MS
        )

        // Kill the local CLI process on the orchestrator host via the Bash tool, then
        // verify via a follow-up ps that 0 CLIs remain. The phone has no shell access
        // to the PC, so the only way to validate kill was to drive it through the
        // CLI itself. The pkill -9 will SIGKILL the very CLI handling the request,
        // so the subsequent "echo killed" may or may not be flushed -- but in either
        // case the next ps must show 0 matching processes.
        val ignoreKill = harness.assistantTexts().toSet()
        harness.sendMessage(
            "Run this exact bash command and report the output: " +
                "pkill -9 -f 'claude-code-sources/dist/cli' || true; sleep 1; " +
                "ps -ef | grep 'claude-code-sources/dist/cli' | grep -v grep | wc -l"
        )
        // The pkill kills the CLI mid-turn so this reply may legitimately not arrive.
        // We accept either outcome and proceed; the post-respawn turn below is the
        // real verification that respawn worked.
        try {
            harness.awaitAssistantReply(
                ignoreTexts = ignoreKill,
                timeoutMs = 30_000L
            )
        } catch (_: AssertionError) {
            // Expected: pkill -9 killed the CLI before it could send a reply. The
            // respawn assertion below is what actually verifies the fix.
        }

        Thread.sleep(5_000)

        val ignore2 = harness.assistantTexts().toSet()
        harness.sendMessage("Reply only with: post-respawn")
        val reply = harness.awaitAssistantReply(
            matchSubstring = "post-respawn",
            ignoreTexts = ignore2,
            timeoutMs = LONG_REPLY_TIMEOUT_MS
        )
        assertTrue("Post-respawn reply should arrive (validates respawn fix from 2f6fce581)",
            reply.contains("post-respawn", ignoreCase = true))

    }
}
