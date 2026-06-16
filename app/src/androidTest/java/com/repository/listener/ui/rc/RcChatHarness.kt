package com.repository.listener.ui.rc

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.repository.listener.config.AppConfig
import com.repository.listener.network.RemoteSessionClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reusable harness for RC chat UI E2E tests.
 *
 * Wraps the existing UiAutomator-based approach (same constraints as
 * RemoteControlE2ETest / RemoteControlUITest -- Espresso/ActivityScenario
 * hang because ListenerService keeps the main looper busy) into a
 * higher-level, intention-revealing API:
 *
 *   - launchRcSession(sessionId, workDir): launches RemoteControlActivity
 *   - openChatsTab(): from MainActivity, taps the chats tab
 *   - sendMessage(text): types into rcInput and clicks rcSendButton
 *   - awaitStatus(predicate): polls rcThinkingView TextView text
 *   - currentStatus(): reads rcThinkingView TextView text immediately
 *   - currentContextPct(): reads rcContextPct as Int (or null)
 *   - tapStop(): clicks rcStopButton
 *   - assertToolStatusContains(text, timeoutMs): waits for tool row text
 *   - endSessionAndReturn(): pressBack to exit RC
 *
 * Direct UI driving only -- no orchestrator round-trip required for the
 * status / context / stop tests; the tests that need real backend traffic
 * still rely on a live orchestrator at the configured URL.
 */
class RcChatHarness(val device: UiDevice) {

    companion object {
        const val PKG = "com.repository.listener"
        const val DEFAULT_TIMEOUT = 5_000L
        const val LONG_TIMEOUT = 30_000L
    }

    val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Bring MainActivity to foreground (MIUI requires foreground task before
     *  context.startActivity from instrumentation can launch RC). */
    fun launchApp() {
        device.executeShellCommand(
            "am start -n $PKG/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
        )
        device.wait(Until.hasObject(By.pkg(PKG).depth(0)), DEFAULT_TIMEOUT)
        Thread.sleep(500)
    }

    /** Force-stop and re-launch the app (clean state for each test). */
    fun forceStopAndLaunch() {
        device.executeShellCommand("am force-stop $PKG")
        Thread.sleep(400)
        launchApp()
    }

    /**
     * Start a REAL pc-agent-backed RC session via the orchestrator HTTP API and
     * launch RemoteControlActivity with the returned sessionId/workDir.
     *
     * This emulates what ChatsListFragment.startRemoteSession() does when the
     * user taps fabRemoteSession + picks a workdir. The previous version of this
     * harness hardcoded a sessionId that pc-agent never spawned, so messages
     * sat in the orchestrator pending queue and tests timed out.
     *
     * @param workDir absolute directory on the pc-agent host (must exist there)
     * @param permissionMode orchestrator permission mode (default "bypassAll")
     * @return the real sessionId returned by the orchestrator
     */
    fun launchRcSession(workDir: String, permissionMode: String = "bypassAll"): String {
        launchApp()

        val wsUrl = AppConfig.getOrchestratorUrl(ctx)
        val apiKey = AppConfig.getApiKey(ctx)
        val deviceId = AppConfig.getDeviceId(ctx)
        val client = RemoteSessionClient(wsUrl, apiKey, deviceId)

        val latch = CountDownLatch(1)
        var sessionId: String? = null
        var realWorkDir: String? = null
        var error: Throwable? = null

        client.startSession(workDir, permissionMode) { result ->
            result.onSuccess {
                sessionId = it.sessionId
                realWorkDir = it.workDir
            }
            result.onFailure { err -> error = err }
            latch.countDown()
        }

        check(latch.await(30, TimeUnit.SECONDS)) {
            "Timed out waiting for orchestrator startSession response (workDir=$workDir)"
        }
        error?.let { throw IllegalStateException("startSession failed: ${it.message}", it) }
        val sid = sessionId ?: error("startSession returned null sessionId")
        val wd = realWorkDir ?: workDir

        // Give pc-agent a moment to spawn the CLI process and the orchestrator a
        // moment to be ready to forward messages for this sessionId.
        Thread.sleep(1500)

        val intent = Intent(ctx, RemoteControlActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(RemoteControlActivity.EXTRA_SESSION_ID, sid)
            putExtra(RemoteControlActivity.EXTRA_WORK_DIR, wd)
        }
        ctx.startActivity(intent)
        val basename = wd.trimEnd('/').substringAfterLast('/')
        val launched = device.wait(Until.hasObject(By.textContains(basename)), DEFAULT_TIMEOUT)
        check(launched) { "RemoteControlActivity did not launch (workDir basename '$basename' not visible)" }
        Thread.sleep(500)
        return sid
    }

    /**
     * Re-enter an EXISTING RC session (does not call startSession). Use after
     * endSessionAndReturn() to verify transcript/state persistence.
     */
    fun reenterRcSession(sessionId: String, workDir: String) {
        launchApp()
        val intent = Intent(ctx, RemoteControlActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(RemoteControlActivity.EXTRA_SESSION_ID, sessionId)
            putExtra(RemoteControlActivity.EXTRA_WORK_DIR, workDir)
        }
        ctx.startActivity(intent)
        val basename = workDir.trimEnd('/').substringAfterLast('/')
        val launched = device.wait(Until.hasObject(By.textContains(basename)), DEFAULT_TIMEOUT)
        check(launched) { "RemoteControlActivity did not re-launch (workDir basename '$basename' not visible)" }
        Thread.sleep(500)
    }

    /**
     * Fetch resumable conversations for a workDir via the orchestrator
     * (-> pc-agent -> remote-session list-sessions). Mirrors what the resume
     * picker calls. Returns the parsed list; throws on transport failure.
     */
    fun listConversations(workDir: String): List<com.repository.listener.network.ConversationInfo> {
        val wsUrl = AppConfig.getOrchestratorUrl(ctx)
        val apiKey = AppConfig.getApiKey(ctx)
        val deviceId = AppConfig.getDeviceId(ctx)
        val client = RemoteSessionClient(wsUrl, apiKey, deviceId)

        val latch = CountDownLatch(1)
        var out: List<com.repository.listener.network.ConversationInfo>? = null
        var error: Throwable? = null
        client.listConversations(workDir) { result ->
            result.onSuccess { out = it }
            result.onFailure { err -> error = err }
            latch.countDown()
        }
        check(latch.await(30, TimeUnit.SECONDS)) {
            "Timed out waiting for listConversations (workDir=$workDir)"
        }
        error?.let { throw IllegalStateException("listConversations failed: ${it.message}", it) }
        return out ?: error("listConversations returned null")
    }

    /** Navigate from MainActivity to the chats tab (index 1 of 7 tabs). */
    fun openChatsTab() {
        val tabs = device.wait(Until.findObject(By.res(PKG, "tabLayout")), DEFAULT_TIMEOUT)
            ?: error("tabLayout not found")
        val bounds = tabs.visibleBounds
        val tabWidth = bounds.width() / 7
        val x = bounds.left + tabWidth + (tabWidth / 2)
        val y = bounds.centerY()
        device.click(x, y)
        Thread.sleep(500)
        device.wait(Until.hasObject(By.res(PKG, "fabNewChat")), DEFAULT_TIMEOUT)
    }

    // ------------------------------------------------------------------
    // Messaging
    // ------------------------------------------------------------------

    /**
     * Type a slash command into rcInput and submit it. Slash-prefixed text is
     * intercepted by the SlashCommandRegistry on send (not forwarded as a chat
     * message), so this is just sendMessage with an intention-revealing name.
     */
    fun sendSlashCommand(command: String) = sendMessage(command)

    /** Type into rcInput and click rcSendButton. */
    fun sendMessage(text: String) {
        val input = device.findObject(By.res(PKG, "rcInput"))
            ?: error("rcInput not found")
        input.click()
        Thread.sleep(150)
        input.text = text
        Thread.sleep(300)
        val send = device.findObject(By.res(PKG, "rcSendButton"))
            ?: error("rcSendButton not found")
        send.click()
        Thread.sleep(300)
    }

    /**
     * Tap the rcModeSelector chip and pick a mode entry from the popup menu.
     * `popupEntryText` is the visible PopupMenu item label (e.g. "Plan mode",
     * "Bypass all permissions"). `expectedChipText` is the post-selection chip
     * label ("Plan", "Bypass", etc.) -- waits up to 5s for the chip text to
     * change to it. Returns true if the chip transitioned.
     */
    fun changeMode(popupEntryText: String, expectedChipText: String): Boolean {
        val chip = device.findObject(By.res(PKG, "rcModeSelector"))
            ?: error("rcModeSelector not found")
        val before = chip.text
        chip.click()
        Thread.sleep(900)
        val entry = device.wait(Until.findObject(By.text(popupEntryText)), 4_000L)
            ?: error("Mode popup entry '$popupEntryText' not found (canonical exact-text match required)")
        entry.click()
        Thread.sleep(600)
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val now = device.findObject(By.res(PKG, "rcModeSelector"))
            val txt = now?.text
            if (txt != null && txt.equals(expectedChipText, ignoreCase = true)) return true
            Thread.sleep(200)
        }
        val finalTxt = device.findObject(By.res(PKG, "rcModeSelector"))?.text
        throw AssertionError(
            "changeMode: chip text did not become '$expectedChipText' " +
                "(before='$before', after='$finalTxt')"
        )
    }

    fun tapStop() {
        val stop = device.findObject(By.res(PKG, "rcStopButton"))
            ?: error("rcStopButton not found")
        stop.click()
        Thread.sleep(300)
    }

    // ------------------------------------------------------------------
    // Status reads
    // ------------------------------------------------------------------

    /** Returns current rcThinkingView text, or null if hidden / absent.
     *  Examples: "Sending...", "Thinking... 3s", "Thought for 12s", null. */
    fun currentStatus(): String? {
        val v = device.findObject(By.res(PKG, "rcThinkingView")) ?: return null
        return runCatching { v.text }.getOrNull()
    }

    /** Poll rcThinkingView until predicate(text) is true or timeout. */
    fun awaitStatus(predicate: (String?) -> Boolean, timeoutMs: Long = DEFAULT_TIMEOUT): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: String? = null
        while (System.currentTimeMillis() < deadline) {
            last = currentStatus()
            if (predicate(last)) return last
            Thread.sleep(150)
        }
        return null
    }

    /** Returns current contextPct integer, or null if not visible / not parseable. */
    fun currentContextPct(): Int? {
        val v = device.findObject(By.res(PKG, "rcContextPct")) ?: return null
        val txt = runCatching { v.text }.getOrNull() ?: return null
        return Regex("(\\d+)%").find(txt)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Wait for any chat row containing the given text (tool status, message, etc.). */
    fun assertToolStatusContains(text: String, timeoutMs: Long = DEFAULT_TIMEOUT): UiObject2? {
        return device.wait(Until.findObject(By.textContains(text)), timeoutMs)
    }

    /**
     * Poll the chat list for an assistant reply. Assistant message TextViews are
     * tagged via contentDescription="rcAssistantText" by RcDetailAdapter.
     *
     * @param matchSubstring optional substring (case-insensitive) the reply text must contain
     * @param ignoreTexts assistant texts to treat as "not yet" (e.g. earlier replies in the same session)
     * @param timeoutMs polling deadline
     * @return the matched assistant text
     * @throws AssertionError on timeout
     */
    fun awaitAssistantReply(
        matchSubstring: String? = null,
        ignoreTexts: Set<String> = emptySet(),
        timeoutMs: Long = 75_000L
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSeen: String? = null
        while (System.currentTimeMillis() < deadline) {
            val candidates = device.findObjects(By.desc("rcAssistantText"))
            for (obj in candidates) {
                val txt = runCatching { obj.text }.getOrNull()?.trim() ?: continue
                if (txt.isEmpty()) continue
                if (txt in ignoreTexts) continue
                lastSeen = txt
                if (matchSubstring == null || txt.contains(matchSubstring, ignoreCase = true)) {
                    return txt
                }
            }
            Thread.sleep(400)
        }
        throw AssertionError(
            "Timed out after ${timeoutMs}ms waiting for assistant reply" +
                (matchSubstring?.let { " containing '$it'" } ?: "") +
                "; lastSeen='${lastSeen ?: "<none>"}'"
        )
    }

    /** Snapshot all currently visible assistant message texts. */
    fun assistantTexts(): List<String> {
        return device.findObjects(By.desc("rcAssistantText"))
            .mapNotNull { runCatching { it.text }.getOrNull()?.trim() }
            .filter { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    fun endSessionAndReturn() {
        device.pressBack()
        Thread.sleep(300)
    }

    /**
     * Stop the orchestrator-side pc-agent process for the given sessionId.
     * Looks up pid via listSessions and posts to /api/v1/remote-sessions/stop.
     * Awaits up to 5s. Best-effort: never throws.
     */
    fun stopRemoteSession(sessionId: String) {
        if (sessionId.isEmpty()) return
        try {
            val wsUrl = AppConfig.getOrchestratorUrl(ctx)
            val apiKey = AppConfig.getApiKey(ctx)
            val deviceId = AppConfig.getDeviceId(ctx)
            val client = RemoteSessionClient(wsUrl, apiKey, deviceId)

            val listLatch = CountDownLatch(1)
            var pid: Int? = null
            client.listSessions { result ->
                result.onSuccess { sessions ->
                    pid = sessions.firstOrNull { it.sessionId == sessionId && it.alive }?.pid
                }
                listLatch.countDown()
            }
            if (!listLatch.await(5, TimeUnit.SECONDS)) return
            val foundPid = pid ?: return

            val stopLatch = CountDownLatch(1)
            client.stopSession(foundPid) { _ -> stopLatch.countDown() }
            stopLatch.await(5, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            // best-effort cleanup
        }
    }
}
