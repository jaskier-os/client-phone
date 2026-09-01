package com.repository.listener.ui.rc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for "thinking text never displayed".
 *
 * The bug: the orchestrator read `block.text` on thinking blocks, but Anthropic
 * puts the reasoning in `block.thinking`. Only the empty spinner-stamp
 * `rc_thinking` was ever forwarded, so `RcMessage.ThinkingBlock` was dead code
 * -- the phone had a whole row type that could never render anything.
 *
 * The only assertion that would have caught that is: a ThinkingBlock row exists
 * AND carries real reasoning text. Everything weaker (a spinner appeared, the
 * status bar said "Thinking... 4s") passed throughout the entire bug, because
 * the status bar is driven by the empty stamp, not by block content.
 *
 * So this test asserts exactly:
 *   1. A ThinkingBlock row renders during the turn.
 *   2. Its text is non-empty and is NOT the "Thinking..." placeholder that
 *      RcDetailAdapter substitutes for an empty block (that placeholder is
 *      literally the bug's symptom).
 *   3. The text is substantive prose, not a one-token stamp.
 *   4. It is not simply a copy of the assistant's visible reply.
 *
 * ----------------------------------------------------------------------------
 * REQUIRED PRODUCTION HOOK
 *
 * ThinkingBlock rows are built by RcDetailAdapter.createThinkingHolder(), whose
 * TextView carries NO contentDescription -- unlike chat bubbles, which are
 * tagged "rcUserText" / "rcAssistantText" precisely so instrumentation can find
 * them. Without an equivalent tag a test can only identify the row by guessing
 * at italic gray styling or by elimination against every other row type, which
 * is exactly the kind of brittle heuristic that lets a broken pipeline look
 * green.
 *
 * This test therefore selects on `contentDescription = "rcThinkingText"`, to be
 * set on the TextView in createThinkingHolder(). Until that one line lands the
 * test fails loudly with an actionable message rather than passing vacuously.
 * ----------------------------------------------------------------------------
 *
 * Determinism caveat: whether a given turn emits extended-thinking blocks is the
 * model's decision, not the harness's. There is no phone-side or orchestrator
 * switch that forces one. The prompt below asks for multi-step reasoning, which
 * is the strongest lever available; if no block arrives within the deadline the
 * test FAILS (no assumeTrue, no silent skip), because on this pipeline a turn
 * with reasoning enabled and none delivered is indistinguishable from the bug
 * this test exists to catch.
 *
 * Requires a live orchestrator + pc-agent.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RcThinkingBlockTest {

    companion object {
        private const val WORK_DIR = "/tmp/rc-test-thinking"

        private const val THINKING_TIMEOUT_MS = 180_000L
        private const val REPLY_TIMEOUT_MS = 240_000L
        private const val HOLD_MS = 3_000L

        /** The placeholder RcDetailAdapter shows for an EMPTY ThinkingBlock. Its
         *  presence is the bug's signature, not evidence of a fix. */
        private const val EMPTY_PLACEHOLDER = "Thinking..."

        /** Below this a "thinking block" is a stamp, not reasoning. */
        private const val MIN_THINKING_CHARS = 40
    }

    private lateinit var device: UiDevice
    private lateinit var harness: RcChatHarness
    private lateinit var sessionId: String

    @Before
    fun setup() {
        ScreenshotHelper.resetCounter()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        harness = RcChatHarness(device)
        sessionId = harness.launchRcSession(WORK_DIR)
    }

    @After
    fun teardown() {
        // pressHome/pressBack only -- `am force-stop` on the app package would
        // kill the instrumentation runner living inside that process.
        try { harness.endSessionAndReturn() } catch (_: Throwable) {}
        try { device.pressHome() } catch (_: Throwable) {}
        try { harness.stopRemoteSession(sessionId) } catch (_: Throwable) {}
        try { Thread.sleep(2_000) } catch (_: Throwable) {}
    }

    /** All currently rendered ThinkingBlock texts, by contentDescription. */
    private fun thinkingTexts(): List<String> =
        device.findObjects(By.desc("rcThinkingText"))
            .mapNotNull { runCatching { it.text }.getOrNull()?.trim() }

    private fun renderedDump(): String =
        "thinking=${thinkingTexts()} assistant=${harness.assistantTexts()} " +
            "status='${harness.currentStatus()}'"

    @Test
    fun thinkingBlockRendersNonEmptyReasoningText() {
        val ignore = harness.assistantTexts().toSet()

        // A prompt that cannot be answered by pattern-matching: it needs several
        // dependent reasoning steps, which is the strongest available lever for
        // eliciting extended thinking.
        harness.sendMessage(
            "Think hard and reason step by step before answering, do not use any tools. " +
                "A train leaves at 09:20 travelling 84 km/h. A second train leaves the " +
                "same station 35 minutes later travelling 112 km/h on the same track. " +
                "At what clock time does the second train catch the first, and how far " +
                "from the station? Show your reasoning, then give the final answer."
        )
        ScreenshotHelper.take("01_thinking_prompt_sent")

        // ------------------------------------------------------------------
        // 1. A ThinkingBlock row must appear, with REAL text.
        // ------------------------------------------------------------------
        val deadline = System.currentTimeMillis() + THINKING_TIMEOUT_MS
        var best: String? = null
        var sawPlaceholderOnly = false
        while (System.currentTimeMillis() < deadline) {
            val texts = thinkingTexts()
            if (texts.isNotEmpty() && texts.all { it == EMPTY_PLACEHOLDER }) {
                sawPlaceholderOnly = true
            }
            val real = texts
                .filter { it.isNotEmpty() && it != EMPTY_PLACEHOLDER }
                .maxByOrNull { it.length }
            if (real != null && real.length >= MIN_THINKING_CHARS) {
                best = real
                break
            }
            if (real != null) best = real
            Thread.sleep(500)
        }

        val thinking = best ?: throw AssertionError(
            if (sawPlaceholderOnly) {
                "A ThinkingBlock row rendered but only ever showed the empty " +
                    "'$EMPTY_PLACEHOLDER' placeholder within ${THINKING_TIMEOUT_MS}ms -- " +
                    "the orchestrator is sending thinking blocks with no text " +
                    "(the block.thinking vs block.text bug). ${renderedDump()}"
            } else {
                "No ThinkingBlock row rendered within ${THINKING_TIMEOUT_MS}ms. Either " +
                    "no thinking block reached the phone (the regression), or the " +
                    "TextView in RcDetailAdapter.createThinkingHolder() is still missing " +
                    "contentDescription = \"rcThinkingText\", which this test selects on. " +
                    "${renderedDump()}"
            }
        )
        ScreenshotHelper.take("02_thinking_block_visible")
        // Hold the rendered thinking block so a screen recording captures it.
        Thread.sleep(HOLD_MS)

        // ------------------------------------------------------------------
        // 2/3. Non-empty, not the placeholder, and substantive.
        // ------------------------------------------------------------------
        assertTrue(
            "ThinkingBlock rendered the empty placeholder instead of reasoning text.",
            thinking != EMPTY_PLACEHOLDER
        )
        assertTrue(
            "ThinkingBlock text is too short to be real reasoning " +
                "(${thinking.length} chars, need >= $MIN_THINKING_CHARS): '$thinking'",
            thinking.length >= MIN_THINKING_CHARS
        )
        assertTrue(
            "ThinkingBlock text contains no word characters: '$thinking'",
            Regex("[A-Za-z]{3,}").containsMatchIn(thinking)
        )

        // ------------------------------------------------------------------
        // 4. The turn still completes, and the thinking text is its own content
        //    -- not the assistant reply echoed into the thinking row.
        // ------------------------------------------------------------------
        val reply = harness.awaitAssistantReply(
            ignoreTexts = ignore,
            timeoutMs = REPLY_TIMEOUT_MS
        )
        ScreenshotHelper.take("03_reply_after_thinking")
        Thread.sleep(HOLD_MS)

        assertTrue(
            "ThinkingBlock text is identical to the assistant reply -- the thinking " +
                "row is echoing the answer rather than rendering reasoning.",
            thinking != reply.trim()
        )

        // The block must survive the turn: it is transcript content, not a spinner.
        val after = thinkingTexts().filter { it.isNotEmpty() && it != EMPTY_PLACEHOLDER }
        assertTrue(
            "The ThinkingBlock row disappeared once the reply arrived; thinking text " +
                "must persist in the transcript. ${renderedDump()}",
            after.isNotEmpty()
        )
        ScreenshotHelper.take("04_thinking_persists")
        Thread.sleep(HOLD_MS)
    }
}
