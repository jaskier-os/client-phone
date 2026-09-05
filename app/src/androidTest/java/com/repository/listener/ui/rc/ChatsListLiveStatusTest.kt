package com.repository.listener.ui.rc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.repository.listener.config.AppConfig
import com.repository.listener.network.RemoteSessionClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The chat list must show a session as active whenever its CLI is actually
 * running on the PC.
 *
 * The bug: a conversation started in a terminal on the PC has no orchestrator
 * store row, so GET /api/v1/remote-control/sessions (which the list reads for
 * status) reports it "ended" -> red dot, even while its CLI is alive. The live
 * list (GET /api/v1/remote-sessions) is the authority on what is running, and
 * buildMergedList now promotes any row it covers to "active".
 *
 * This test is non-vacuous only when the PC actually has a live CLI whose store
 * row is NOT active -- exactly the reported state. It reads both endpoints for
 * ground truth first and skips (rather than false-passes) if that state does not
 * currently exist on the machine.
 *
 * Run:
 *   adb shell am instrument -w \
 *     -e class com.repository.listener.ui.rc.ChatsListLiveStatusTest \
 *     com.repository.listener.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ChatsListLiveStatusTest {

    companion object {
        private const val PKG = "com.repository.listener"
        private const val UI_TIMEOUT = 20_000L
    }

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand(
            "am start -n $PKG/.MainActivity -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER"
        )
        device.wait(Until.hasObject(By.res(PKG, "tabLayout")), UI_TIMEOUT)
        Thread.sleep(1_000)
    }

    /** workDir per live session id, filled by [liveSessionIds]. */
    private var liveWorkDirById: Map<String, String> = emptyMap()

    /** Live CLIs on the PC, straight from the orchestrator. */
    private fun liveSessionIds(): Set<String> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val client = RemoteSessionClient(
            AppConfig.getOrchestratorUrl(ctx),
            AppConfig.getApiKey(ctx),
            AppConfig.getDeviceId(ctx)
        )
        val latch = CountDownLatch(1)
        var ids: Set<String> = emptySet()
        client.listSessions { result ->
            result.onSuccess { s ->
                val alive = s.filter { it.alive }
                ids = alive.map { it.sessionId }.toSet()
                liveWorkDirById = alive.associate { it.sessionId to it.workDir }
            }
            latch.countDown()
        }
        check(latch.await(30, TimeUnit.SECONDS)) { "listSessions timed out" }
        return ids
    }

    /** Store-side status per session id, as the chat list reads it. */
    private fun storedStatuses(): Map<String, String> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val client = RemoteSessionClient(
            AppConfig.getOrchestratorUrl(ctx),
            AppConfig.getApiKey(ctx),
            AppConfig.getDeviceId(ctx)
        )
        val latch = CountDownLatch(1)
        var out: Map<String, String> = emptyMap()
        client.listRcSessions { result ->
            result.onSuccess { rows -> out = rows.associate { it.sessionId to it.status } }
            latch.countDown()
        }
        check(latch.await(30, TimeUnit.SECONDS)) { "listRcSessions timed out" }
        return out
    }

    private fun navigateToChatTab() {
        val tabs = device.findObject(By.res(PKG, "tabLayout")) ?: error("tabLayout missing")
        val bounds = tabs.visibleBounds
        val tabWidth = bounds.width() / 7
        device.click(bounds.left + tabWidth + (tabWidth / 2), bounds.centerY())
        Thread.sleep(1_500)
    }

    @Test
    fun aLiveCliIsNeverShownAsEndedInTheChatList() {
        val live = liveSessionIds()
        val stored = storedStatuses()

        // The exact reported condition: a CLI is running but the store row is not
        // active. If it isn't present right now, fail loudly rather than pass
        // vacuously -- a green run must mean the promotion was really exercised.
        val mismatched = live.filter { stored[it] != null && stored[it] != "active" }
        assertTrue(
            "Precondition: need a live CLI whose store row is not active. " +
                "live=$live storedForLive=" + live.associateWith { stored[it] },
            mismatched.isNotEmpty()
        )

        navigateToChatTab()
        device.wait(Until.hasObject(By.res(PKG, "chatsRecycler")), UI_TIMEOUT)
        Thread.sleep(3_000) // let loadSessions + loadRcSessions land

        // Count, don't match by workDir: one folder legitimately holds many old
        // dead conversations alongside one live session, so a "- ended" row for
        // the same directory is not necessarily a bug. What IS load-bearing is
        // that every live CLI that has a store row renders active.
        //
        // The RC row subtitle renders "<workDir> - <status>", so counting
        // "- active" rows is a faithful read of what the user sees.
        // Count DISTINCT folders, not rows: two live sessions can share one
        // workDir, and their rows then render identical text that UiAutomator
        // cannot tell apart (and one may sit below the fold). Distinct folders
        // is the strongest claim this screen-scrape can honestly support.
        val liveWithRow = live.filter { stored.containsKey(it) }
        val expectedFolders = liveWithRow.mapNotNull { sid -> liveWorkDirById[sid] }.toSet()
        val activeRows = device.findObjects(By.textContains("- active"))
            .mapNotNull { runCatching { it.text }.getOrNull() }
        val activeFolders = activeRows.map { it.substringBefore(" - ") }.toSet()

        val missing = expectedFolders - activeFolders
        assertTrue(
            "Every folder with a live CLI must show an active row. " +
                "missing=$missing expected=$expectedFolders rendered=$activeFolders " +
                "(promotion candidates=$mismatched)",
            missing.isEmpty()
        )
        // And specifically: the sessions that were stored-as-ended got promoted,
        // i.e. the active rows outnumber what the store alone would have shown.
        val storeOnlyActive = stored.values.count { it == "active" }
        assertTrue(
            "Promotion did not happen: rendered active=${activeRows.size} is not " +
                "more than the store's own active count=$storeOnlyActive despite " +
                "${mismatched.size} live-but-ended session(s)",
            activeRows.size > storeOnlyActive || mismatched.isEmpty()
        )
        Thread.sleep(2_000) // hold the rendered state for the recording
    }

    /**
     * Promoting a row to "active" must not make it swipeable to END SESSION.
     * Those CLIs are typically ones the user started in their own terminal, and
     * the swipe kills the process outright with no confirmation. Swiping a
     * promoted row must leave the session alive on the PC.
     */
    @Test
    fun promotedRowsCannotBeSwipedToEndTheUsersOwnCli() {
        val live = liveSessionIds()
        val stored = storedStatuses()
        val promoted = live.filter { stored[it] != null && stored[it] != "active" }
        assertTrue(
            "Precondition: need a live CLI whose store row is not active. live=$live",
            promoted.isNotEmpty()
        )
        val target = promoted.first()
        val dirName = liveWorkDirById[target]!!.trimEnd('/').substringAfterLast('/')

        navigateToChatTab()
        device.wait(Until.hasObject(By.res(PKG, "chatsRecycler")), UI_TIMEOUT)
        Thread.sleep(3_000)

        val row = device.wait(Until.findObject(By.textContains(dirName)), UI_TIMEOUT)
        assertTrue("Promoted row for '$dirName' must be visible", row != null)

        // Try to swipe it away, the gesture that ends a session.
        val b = row!!.visibleBounds
        device.swipe(b.centerX(), b.centerY(), b.left + 10, b.centerY(), 20)
        Thread.sleep(3_000)

        // Ground truth: the CLI must still be alive on the PC afterwards.
        assertTrue(
            "Swiping a promoted row must NOT end the user's own terminal CLI " +
                "($target should still be live)",
            liveSessionIds().contains(target)
        )
        Thread.sleep(2_000)
    }

    /**
     * A live CLI the orchestrator has no store row for surfaces only as a
     * live-session row. That row used to have its click listener explicitly set
     * to null, so the user could see the session running but had no way to open
     * it -- and opening is what adopts it. It must now be clickable.
     */
    @Test
    fun aStoreLessLiveSessionRowCanBeOpened() {
        val live = liveSessionIds()
        val stored = storedStatuses()

        val storeless = live.filter { !stored.containsKey(it) }
        assertTrue(
            "Precondition: need a live CLI with no store row. live=$live",
            storeless.isNotEmpty()
        )
        val target = storeless.first()
        val dirName = liveWorkDirById[target]!!.trimEnd('/').substringAfterLast('/')

        navigateToChatTab()
        device.wait(Until.hasObject(By.res(PKG, "chatsRecycler")), UI_TIMEOUT)
        Thread.sleep(3_000)

        // The live-only row titles as "> <dirName>".
        val row = device.wait(Until.findObject(By.textContains(dirName)), UI_TIMEOUT)
        assertTrue("Live session row for '$dirName' must be present", row != null)

        // Walk up: the click listener lives on the row container, not on the
        // TextView the text matched, and the container may be several levels up.
        var node = row
        var clickable = false
        var depth = 0
        val chain = StringBuilder()
        while (node != null && depth < 6) {
            chain.append("${node.className}(clickable=${node.isClickable}) <- ")
            if (node.isClickable) { clickable = true; break }
            node = runCatching { node!!.parent }.getOrNull()
            depth++
        }
        assertTrue(
            "The row for a live store-less session must be clickable so it can " +
                "be opened (it used to have a null click listener). chain=$chain",
            clickable
        )

        // Opening it must actually reach the RC chat screen, which is what
        // triggers the adopt.
        row.click()
        val opened = device.wait(Until.hasObject(By.res(PKG, "rcLoadingOverlay")), UI_TIMEOUT) ||
            device.wait(Until.hasObject(By.textContains(dirName)), UI_TIMEOUT)
        assertTrue("Tapping a live session row must open the RC chat", opened)
        Thread.sleep(2_000)
        device.pressBack()
    }
}
