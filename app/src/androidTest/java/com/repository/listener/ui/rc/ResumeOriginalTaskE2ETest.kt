package com.repository.listener.ui.rc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end test that drives an EXISTING RC session (the user's currently-bugged
 * conversation) back to life by sending a continuation prompt and waiting for the
 * AI to finish the original task.
 *
 * Targets session id 30c0ec8e-1e03-486e-8be2-7e5ad0b2ab25 in workDir
 * /workspace/project (the chat where the user reported
 * "stop button missing on re-entry" and which subsequently lost context due to
 * pc-agent's broken --session-id respawn). After the orchestrator-side fixes
 * (--resume + jsonl chain repair + structuredPatch + ripgrep) this test
 * verifies the AI now reattaches to the prior context AND completes the work
 * end to end.
 *
 * The test is intentionally long-lived (up to ~25 min). It does NOT clean up
 * the session afterwards: the user's original task should remain merged on
 * disk in the workDir. The pass criterion is that the assistant produces at
 * least one new substantive reply that does NOT claim it has lost context.
 *
 * To run only this test:
 *   bash AI/clients/phone/app/gradlew -p AI/clients/phone \
 *       :app:connectedAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=\
 *       com.repository.listener.ui.rc.ResumeOriginalTaskE2ETest
 *
 * Or after `assembleAndroidTest`:
 *   adb shell am instrument -w -r \
 *     -e class com.repository.listener.ui.rc.ResumeOriginalTaskE2ETest \
 *     com.repository.listener.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ResumeOriginalTaskE2ETest {

    companion object {
        private const val TARGET_SESSION_ID = "30c0ec8e-1e03-486e-8be2-7e5ad0b2ab25"
        private const val TARGET_WORK_DIR = "/workspace/project"

        // The continuation prompt deliberately quotes the original task so the
        // model has unambiguous direction even if its loaded transcript is
        // partially summarised. Keep it short — the model should already have
        // chain-A loaded from the repaired jsonl.
        private const val CONTINUE_PROMPT =
            "Please continue working on the original task you were given (the " +
            "phone-app RC chat 'stop button doesn't appear on re-entry' bug). " +
            "Take it from where you left off, ship the fix, deploy the phone " +
            "app via Recon/scripts/deploy-to-phone.sh, and report what you did."

        // Phrases that indicate the AI lost context and is asking for re-clarification.
        // If the first reply matches any of these, the test fails fast.
        private val NO_CONTEXT_MARKERS = listOf(
            "I don't have context",
            "no prior context",
            "lost context",
            "history was likely compressed",
            "could you tell me what you were working on",
            "I don't see any prior",
            "no previous conversation",
        )

        // Hard cap. Real Bash builds + Gradle on this machine take a couple
        // minutes; allowing 25 min gives Claude plenty of room to iterate.
        private const val ASSISTANT_TIMEOUT_MS = 25L * 60L * 1000L
    }

    @Test
    fun resumeAndDriveToCompletion() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val harness = RcChatHarness(device)

        // 1. Reattach to the existing session (does NOT hit /startSession --
        //    the orchestrator already has this session in its store).
        harness.reenterRcSession(TARGET_SESSION_ID, TARGET_WORK_DIR)

        // 2. Snapshot existing assistant texts so awaitAssistantReply only
        //    returns NEW replies generated for our CONTINUE_PROMPT.
        val seenBefore = harness.assistantTexts().toSet()

        // 3. Send the continuation prompt. pc-agent's respawn path now uses
        //    --resume, so loadConversationForResume on the now-single-rooted
        //    jsonl will replay chain A as in-context history.
        harness.sendMessage(CONTINUE_PROMPT)

        // 4. Wait for the FIRST new assistant reply. Generous timeout because
        //    pc-agent may need to spawn a fresh CLI process and CC needs to
        //    load ~35 prior turns before generating a token.
        val firstReply = harness.awaitAssistantReply(
            matchSubstring = null,
            ignoreTexts = seenBefore,
            timeoutMs = 90_000L
        )
        assertNotNull("First reply should not be null", firstReply)

        // 5. If the model still claims to have no context, surface that as a
        //    test failure with the actual reply attached for debugging.
        for (marker in NO_CONTEXT_MARKERS) {
            assertFalse(
                "AI claimed to have lost context after --resume / jsonl repair. " +
                    "Reply was: $firstReply",
                firstReply.contains(marker, ignoreCase = true)
            )
        }

        // 6. Drive the conversation: poll for additional assistant turns until
        //    no new reply has appeared for `quietPeriodMs`. That indicates the
        //    AI either hit a final result event or is genuinely idle.
        val quietPeriodMs = 90_000L
        val deadline = System.currentTimeMillis() + ASSISTANT_TIMEOUT_MS
        var lastSeen = mutableSetOf<String>().apply {
            addAll(seenBefore)
            add(firstReply)
        }
        var lastChangeAt = System.currentTimeMillis()

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000L)
            val nowSet = harness.assistantTexts().toSet()
            val newOnes = nowSet - lastSeen
            if (newOnes.isNotEmpty()) {
                lastSeen.addAll(newOnes)
                lastChangeAt = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastChangeAt > quietPeriodMs) {
                // Conversation has been silent for `quietPeriodMs`; assume the
                // AI finished its turn (or stalled — either way, exit cleanly
                // and let the assertion below report what we got).
                break
            }
        }

        // 7. Final state: log everything we saw so the test report is useful
        //    on failure. Print to stdout (visible via `adb logcat -s
        //    TestRunner` and in the gradle test output).
        val finalReplies = harness.assistantTexts() - seenBefore
        println("[ResumeOriginalTaskE2E] new assistant replies: ${finalReplies.size}")
        finalReplies.forEachIndexed { i, t ->
            println("[ResumeOriginalTaskE2E] reply[$i]: ${t.take(400)}")
        }

        // The pass bar: at least one substantive new reply that didn't claim
        // missing context. We already asserted the first; the loop just gave
        // the AI room to actually finish the work. We do NOT assert "build
        // succeeded" textually — the AI's prose is too varied for that and
        // the deploy-script side-effect is the real signal.
        assert(finalReplies.isNotEmpty()) {
            "No new assistant replies were produced; resume/respawn likely still broken."
        }
    }
}
