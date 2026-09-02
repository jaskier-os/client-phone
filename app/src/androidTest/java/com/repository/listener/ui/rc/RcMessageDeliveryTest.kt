package com.repository.listener.ui.rc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every message the user sends must actually reach the CLI.
 *
 * The failure this guards against: a half-open socket accepts writes silently,
 * so the app reports "sent" while the orchestrator never receives the frame.
 * The retry layer then re-sends until its budget is exhausted and the message
 * is dropped with nothing the user ever sees.
 *
 * Each message carries a unique marker and asks for it to be echoed back. A
 * reply containing the marker is proof the message travelled the whole path --
 * phone, orchestrator, CLI -- and came back, which no amount of local queueing
 * can fake.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RcMessageDeliveryTest {

    companion object {
        private const val WORK_DIR = "/tmp/rc-test-msgloss"
        private const val MESSAGE_COUNT = 5
        /** Generous: each turn is a real model round trip. */
        private const val REPLY_TIMEOUT_MS = 180_000L
        private const val HOLD_MS = 1_500L
    }

    private lateinit var device: UiDevice
    private lateinit var harness: RcChatHarness
    private lateinit var sessionId: String
    private val runId = "MSGOK${System.currentTimeMillis() % 100000}"

    @Before
    fun setup() {
        ScreenshotHelper.resetCounter()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        harness = RcChatHarness(device)
        sessionId = harness.launchRcSession(WORK_DIR)
    }

    @After
    fun teardown() {
        try { harness.endSessionAndReturn() } catch (_: Throwable) {}
        try { harness.stopRemoteSession(sessionId) } catch (_: Throwable) {}
    }

    @Test
    fun everyMessageSentReachesTheSessionAndIsAnswered() {
        val delivered = mutableListOf<String>()
        val lost = mutableListOf<String>()

        for (i in 1..MESSAGE_COUNT) {
            val marker = "${runId}_$i"
            harness.sendMessage("Reply with exactly this word and nothing else: $marker")

            // The reply is the proof. A message that was queued locally but
            // never reached the CLI cannot produce one.
            val deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS
            var seen = false
            while (System.currentTimeMillis() < deadline) {
                if (harness.assistantTexts().any { it.contains(marker) }) { seen = true; break }
                Thread.sleep(1000)
            }

            if (seen) delivered.add(marker) else lost.add(marker)
            ScreenshotHelper.take("msg_${i}_${if (seen) "delivered" else "LOST"}")
            Thread.sleep(HOLD_MS)
        }

        assertTrue(
            "Message loss: ${lost.size}/$MESSAGE_COUNT never came back. " +
                "lost=$lost delivered=$delivered. A message that is sent but never " +
                "answered means the frame was written into a socket the orchestrator " +
                "was not reading. assistant=${harness.assistantTexts().takeLast(3)}",
            lost.isEmpty()
        )
    }
}
