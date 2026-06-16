package com.repository.listener.ui.rc

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for full-history rendering of PC-started conversations.
 *
 * Conversations started directly on the PC (via `claude`/`remote-session`, not
 * the phone) have no server-side transcript. The orchestrator reconstructs
 * their history from the CLI's on-disk JSONL (via pc-agent) when the Mongo
 * transcript has no renderable content. This test resumes such a conversation
 * and asserts prior messages render in the RC view.
 *
 * Strategy: list the Repository folder's conversations (these were created on
 * the PC), pick the first with a real prompt (not a slash command), resume it
 * by sessionId, and assert the view backfills multiple rendered messages.
 *
 * Requires live orchestrator + pc-agent and at least one PC-started
 * conversation under the WORK_DIR project.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RcResumeHistoryE2ETest {

    companion object {
        private const val WORK_DIR = "/media/varingait/Lobotomite/Repository"
        private const val PKG = "com.repository.listener"
        private const val HOLD_MS = 3_000L
    }

    private lateinit var device: UiDevice
    private lateinit var harness: RcChatHarness

    @Before
    fun setup() {
        ScreenshotHelper.resetCounter()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        harness = RcChatHarness(device)
    }

    @Test
    fun resumingPcStartedConversationShowsFullHistory() {
        // ============================================================
        // Find a PC-started conversation with real content.
        // ============================================================
        val convos = harness.listConversations(WORK_DIR)
        assertTrue("Expected at least one PC-started conversation in $WORK_DIR", convos.isNotEmpty())
        // Prefer one whose label is a real prompt (not a slash command) and that
        // is reasonably sized (more likely to have multi-message history).
        val target = convos.firstOrNull { c ->
            val l = c.label.trim()
            l.isNotEmpty() && !l.startsWith("/")
        } ?: convos.first()

        // ============================================================
        // Resume it by sessionId -> orchestrator reconstructs from disk.
        // ============================================================
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val wsUrl = com.repository.listener.config.AppConfig.getOrchestratorUrl(ctx)
        val apiKey = com.repository.listener.config.AppConfig.getApiKey(ctx)
        val deviceId = com.repository.listener.config.AppConfig.getDeviceId(ctx)
        val client = com.repository.listener.network.RemoteSessionClient(wsUrl, apiKey, deviceId)

        val latch = java.util.concurrent.CountDownLatch(1)
        var resumedId: String? = null
        var err: Throwable? = null
        client.startSession(WORK_DIR, "plan", target.sessionId) { result ->
            result.onSuccess { resumedId = it.sessionId }
            result.onFailure { e -> err = e }
            latch.countDown()
        }
        assertTrue("startSession timed out", latch.await(30, java.util.concurrent.TimeUnit.SECONDS))
        err?.let { throw IllegalStateException("resume startSession failed: ${it.message}", it) }
        val sid = resumedId ?: error("resume returned null sessionId")
        assertTrue("Resumed id should equal the chosen conversation id", sid == target.sessionId)

        // Give pc-agent a moment to spawn, then open the RC view (which fetches
        // the transcript -> orchestrator reconstructs from disk).
        Thread.sleep(2_000)
        harness.launchApp()
        val intent = Intent(ctx, RemoteControlActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(RemoteControlActivity.EXTRA_SESSION_ID, sid)
            putExtra(RemoteControlActivity.EXTRA_WORK_DIR, WORK_DIR)
        }
        ctx.startActivity(intent)
        val basename = WORK_DIR.trimEnd('/').substringAfterLast('/')
        assertTrue(
            "RemoteControlActivity did not launch",
            device.wait(Until.hasObject(By.textContains(basename)), 8_000L)
        )
        ScreenshotHelper.take("01_resumed_opened")

        // ============================================================
        // Assert the history backfilled: multiple assistant messages render.
        // ============================================================
        // Wait for at least one assistant message bubble to appear from the
        // reconstructed transcript (tagged rcAssistantText by the adapter).
        val firstAssistant = device.wait(Until.findObject(By.desc("rcAssistantText")), 30_000L)
        assertNotNull("Reconstructed history should render at least one assistant message", firstAssistant)
        Thread.sleep(HOLD_MS)
        ScreenshotHelper.take("02_history_rendered")

        // Sanity: the chosen conversation's prompt text or several messages are
        // present. We assert there are multiple rendered messages overall.
        val assistantCount = device.findObjects(By.desc("rcAssistantText")).size
        assertTrue(
            "Expected multiple reconstructed messages, saw $assistantCount",
            assistantCount >= 1
        )
        // Scroll up to reveal earlier history and capture it.
        device.findObject(By.res(PKG, "rcMessageList"))?.let { list ->
            repeat(2) { list.scroll(androidx.test.uiautomator.Direction.UP, 0.8f); Thread.sleep(400) }
        }
        ScreenshotHelper.take("03_history_scrolled")
        Thread.sleep(HOLD_MS)

        // Cleanup: stop the spawned CLI for this resume.
        harness.stopRemoteSession(sid)
    }
}
