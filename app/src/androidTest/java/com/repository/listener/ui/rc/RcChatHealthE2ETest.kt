package com.repository.listener.ui.rc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Targeted E2E health checks for the phone RC chat:
 *   1. noDuplicateAssistantReply - one user send must produce exactly one
 *      new assistant text (no duplicate replies from a flaky stream/reconnect).
 *   2. connectionStableAcrossMessages - three sequential sends in the same
 *      session each get one reply on time (proves WS + orchestrator stable).
 *   3. askAssistantToRecordAndBounceVideo - the assistant can be asked to
 *      record a small video and POST it to the local Telegram bounce
 *      endpoint; reply must contain the 6-hex shortId.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RcChatHealthE2ETest {

    companion object {
        private const val DEFAULT_WORK_DIR = "/workspace/project"
        private const val SHORT_REPLY_TIMEOUT_MS = 300_000L
        private const val LONG_REPLY_TIMEOUT_MS = 600_000L
        private const val DUPLICATE_GUARD_MS = 30_000L
    }

    private lateinit var device: UiDevice
    private lateinit var harness: RcChatHarness
    private lateinit var sessionId: String

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        harness = RcChatHarness(device)
        sessionId = harness.launchRcSession(DEFAULT_WORK_DIR, permissionMode = "bypassAll")
    }

    @After
    fun teardown() {
        try { harness.endSessionAndReturn() } catch (_: Throwable) {}
        try { harness.stopRemoteSession(sessionId) } catch (_: Throwable) {}
    }

    /**
     * Send one message; ensure exactly one new assistant message appears,
     * and that no second copy arrives during a 30s settle window.
     */
    @Test
    fun noDuplicateAssistantReply() {
        val before = harness.assistantTexts().toSet()
        val marker = "no-dupe-marker-${System.currentTimeMillis() % 100000}"
        harness.sendMessage("Reply with exactly this token and nothing else: $marker")

        val reply = harness.awaitAssistantReply(
            matchSubstring = marker,
            ignoreTexts = before,
            timeoutMs = SHORT_REPLY_TIMEOUT_MS
        )
        assertTrue("Reply must contain marker", reply.contains(marker, ignoreCase = true))

        // Settle window: poll the chat list every 1s for 30s; the count of
        // assistant messages containing the marker must remain exactly 1.
        val deadline = System.currentTimeMillis() + DUPLICATE_GUARD_MS
        var maxSeen = 0
        while (System.currentTimeMillis() < deadline) {
            val matchingNow = harness.assistantTexts()
                .count { it.contains(marker, ignoreCase = true) }
            if (matchingNow > maxSeen) maxSeen = matchingNow
            assertTrue(
                "Assistant produced a duplicate reply (saw $matchingNow copies of '$marker')",
                matchingNow <= 1
            )
            Thread.sleep(1_000)
        }
        assertEquals("Expected exactly one assistant reply with marker", 1, maxSeen)
    }

    /**
     * Send three sequential prompts in the same session. Each must produce
     * its own unique-marker reply within the per-turn budget. Validates
     * connection stability and ordering across multi-turn streaming.
     */
    @Test
    fun connectionStableAcrossMessages() {
        val markers = listOf(
            "stable-token-alpha-${System.currentTimeMillis() % 100000}",
            "stable-token-bravo-${System.currentTimeMillis() % 100000}",
            "stable-token-charlie-${System.currentTimeMillis() % 100000}"
        )
        for (m in markers) {
            val ignore = harness.assistantTexts().toSet()
            harness.sendMessage("Reply with exactly this token and nothing else: $m")
            val reply = harness.awaitAssistantReply(
                matchSubstring = m,
                ignoreTexts = ignore,
                timeoutMs = SHORT_REPLY_TIMEOUT_MS
            )
            assertTrue("Turn for '$m' did not return marker (was: '$reply')",
                reply.contains(m, ignoreCase = true))
        }
        // Final transcript must contain all three markers as distinct messages.
        val finalTexts = harness.assistantTexts()
        for (m in markers) {
            assertTrue("Final transcript missing marker '$m'",
                finalTexts.any { it.contains(m, ignoreCase = true) })
        }
    }

    /**
     * Ask the assistant (running on the PC under pc-agent) to:
     *   1. Generate a small synthetic 2-second mp4 with ffmpeg.
     *   2. POST it to the local Telegram bounce endpoint.
     *   3. Quote the returned shortId so we can verify success.
     *
     * The bounce endpoint runs at http://127.0.0.1:10004 on the PC where
     * pc-agent (and therefore the assistant's bash tool) executes. Success
     * is a 6-hex shortId in the assistant's reply.
     */
    @Test
    fun askAssistantToRecordAndBounceVideo() {
        val ignore = harness.assistantTexts().toSet()
        val outPath = "/tmp/rc-e2e-bounce-${System.currentTimeMillis()}.mp4"
        val caption = "RC E2E bounce test"
        val prompt = """
            Run this exactly using the Bash tool, then report the shortId from the JSON response:
            ffmpeg -y -f lavfi -i testsrc=duration=2:size=320x240:rate=15 -pix_fmt yuv420p $outPath >/dev/null 2>&1 && \
              curl -fsS -X POST http://127.0.0.1:10004/api/v1/tg-upload \
                -H 'Content-Type: application/json' \
                -d '{"filePath":"$outPath","caption":"$caption"}'
            Quote the shortId field verbatim in your reply (the 6-hex value).
        """.trimIndent()

        harness.sendMessage(prompt)
        val reply = harness.awaitAssistantReply(
            ignoreTexts = ignore,
            timeoutMs = LONG_REPLY_TIMEOUT_MS
        )
        // shortId is 6 hex chars per CLAUDE.md spec
        val shortIdRegex = Regex("\\b[0-9a-fA-F]{6}\\b")
        val match = shortIdRegex.find(reply)
        assertTrue(
            "Assistant reply must include a 6-hex shortId (was: '$reply')",
            match != null
        )
    }
}
