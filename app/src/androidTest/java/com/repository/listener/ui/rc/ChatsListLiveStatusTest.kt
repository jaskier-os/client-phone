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
import org.junit.Assume.assumeTrue
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

    @org.junit.After
    fun teardown() {
        // Kill only a CLI this test started; never touch the user's own.
        fixturePid?.let { runCatching { PcTuiDriver().killTui("SIGTERM") } }
        fixturePid = null
    }

    /** workDir per live session id, filled by [liveSessionIds]. */
    private var liveWorkDirById: Map<String, String> = emptyMap()

    /** A CLI this test started on the PC, torn down in [teardown]. */
    private var fixturePid: Int? = null

    /**
     * Start a real interactive CLI on the PC that the orchestrator has never
     * seen, so it has no store row -- the exact state a user creates by running
     * the CLI in their own terminal.
     *
     * Created as a fixture rather than assumed from ambient state: opening such
     * a session ADOPTS it and gives it a store row, so the condition destroys
     * itself and an assumption-based test would silently skip on every run after
     * the first, reporting green while asserting nothing.
     *
     * Returns the sessionId, or null when the PC driver is unreachable.
     */
    private fun startStoreLessCli(): String? {
        val pc = PcTuiDriver()
        return try {
            pc.requireReachable()
            // The driver holds one TUI at a time and rejects a second start, so
            // clear any session an earlier test left behind. The teardown of that
            // session is not instant -- the CLI has to exit and release its
            // attach socket -- so retry rather than failing the first attempt.
            var tui: PcTuiDriver.TuiSession? = null
            var lastErr: Throwable? = null
            repeat(3) { attempt ->
                if (tui == null) {
                    runCatching { pc.reset() }
                    Thread.sleep(3_000L * (attempt + 1))
                    runCatching {
                        pc.startTui(
                            workDir = "/media/varingait/Lobotomite/.cache/rc-live-attach-test",
                            permissionMode = "bypassPermissions"
                        )
                    }.onSuccess { tui = it }.onFailure { lastErr = it }
                }
            }
            if (tui == null) throw (lastErr ?: IllegalStateException("startTui failed"))
            val tuiSession = tui!!
            fixturePid = tuiSession.pid
            // Let pc-agent publish it into the live-session registry.
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                if (liveSessionIds().contains(tuiSession.sessionId)) return tuiSession.sessionId
                Thread.sleep(2_000)
            }
            tuiSession.sessionId
        } catch (e: Throwable) {
            fixtureError = "${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }

    /** Why [startStoreLessCli] failed, so the assertion can say so. */
    private var fixtureError: String? = null

    /**
     * Live CLIs on the PC, retrying briefly: pc-agent reconnects to the
     * orchestrator from time to time, and a poll landing in that window returns
     * an empty list, which would fail a precondition for no real reason.
     */
    private fun liveSessionIdsStable(expect: String? = null): Set<String> {
        var ids = liveSessionIds()
        var tries = 0
        // Retry while the list is empty, or while a session we expect to see is
        // absent -- both are symptoms of a reconnect rather than a dead CLI.
        while ((ids.isEmpty() || (expect != null && !ids.contains(expect))) && tries < 5) {
            Thread.sleep(4_000)
            ids = liveSessionIds()
            tries++
        }
        return ids
    }

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
        // Select the tab by its content description, not by dividing the
        // TabLayout's bounds: those bounds can report the whole screen, which
        // put the tap in the middle of the content and silently left the app on
        // whatever tab it was already showing.
        val chats = device.wait(Until.findObject(By.desc("Chats")), UI_TIMEOUT)
            ?: error("Chats tab not found")
        chats.click()
        Thread.sleep(1_500)
        // A previous test may have left the list scrolled; every test here reads
        // rows by text, so start from the top or the reads silently see nothing.
        val rv = device.findObject(By.res(PKG, "chatsRecycler"))
        if (rv != null) {
            val b = rv.visibleBounds
            repeat(10) {
                device.swipe(b.centerX(), b.top + 100, b.centerX(), b.bottom - 100, 8)
            }
            Thread.sleep(800)
        }
    }

    @Test
    fun aLiveCliIsNeverShownAsEndedInTheChatList() {
        val live = liveSessionIdsStable()
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
        // Search for each folder rather than scrolling the list: findObjects
        // only sees rendered rows, and in a long list an active session sits
        // below the fold, which a scroll-and-collect loop reports as missing.
        // Search narrows to the row directly and is deterministic.
        val search = device.wait(Until.findObject(By.res(PKG, "searchInput")), UI_TIMEOUT)
            ?: error("searchInput not found")
        val activeFolders = mutableSetOf<String>()
        for (folder in expectedFolders) {
            search.text = folder
            Thread.sleep(3_500)
            if (device.findObject(By.text("$folder - active")) != null) {
                activeFolders.add(folder)
            }
        }
        search.text = ""
        Thread.sleep(1_500)

        val missing = expectedFolders - activeFolders
        assertTrue(
            "Every folder with a live CLI must show an active row. " +
                "missing=$missing expected=$expectedFolders rendered=$activeFolders " +
                "(promotion candidates=$mismatched)",
            missing.isEmpty()
        )
        // Specifically: each session the STORE calls ended, but whose CLI is
        // live, must still render active. Counting rows cannot express this --
        // two live sessions can share a workDir and produce identical text --
        // so assert per-folder, which is what the store alone could never do.
        val promotedFolders = mismatched.mapNotNull { liveWorkDirById[it] }.toSet()
        val notPromoted = promotedFolders - activeFolders
        assertTrue(
            "Sessions the store calls ended, whose CLI is live, must render " +
                "active. notPromoted=$notPromoted rendered=$activeFolders",
            notPromoted.isEmpty()
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
    fun swipingARunningSessionRequiresConfirmation() {
        val live = liveSessionIdsStable()
        val stored = storedStatuses()
        val promoted = live.filter { stored[it] != null && stored[it] != "active" }
        assertTrue(
            "Precondition: need a live CLI whose store row is not active. live=$live",
            promoted.isNotEmpty()
        )
        val target = promoted.first()

        navigateToChatTab()
        device.wait(Until.hasObject(By.res(PKG, "chatsRecycler")), UI_TIMEOUT)
        Thread.sleep(3_000)

        // Search for the row rather than expecting it on the first screen: the
        // list is long and the target often sits below the fold, where
        // findObject simply does not see it.
        val workDir = liveWorkDirById[target]!!
        val search = device.wait(Until.findObject(By.res(PKG, "searchInput")), UI_TIMEOUT)
            ?: error("searchInput not found")
        search.text = workDir
        Thread.sleep(4_000)

        // Match the RC row's own subtitle ("<workDir> - active"), not any text
        // containing the folder name: only that identifies the swipeable row.
        val row = device.wait(Until.findObject(By.text("$workDir - active")), UI_TIMEOUT)
        assertTrue("Promoted row for '$workDir' must render as active", row != null)

        // Swipe across the full list width at the row's height, so the gesture
        // lands on the row container rather than on a narrow text node.
        val b = row!!.visibleBounds
        val recycler = device.findObject(By.res(PKG, "chatsRecycler"))!!.visibleBounds
        device.swipe(recycler.right - 20, b.centerY(), recycler.left + 20, b.centerY(), 25)
        Thread.sleep(2_000)

        // The swipe must never act on its own: a confirmation naming the
        // directory has to appear first. This is the guard that stops a stray
        // gesture from killing a terminal-started CLI.
        val confirm = device.wait(Until.findObject(By.textContains("End session in")), UI_TIMEOUT)
        assertTrue(
            "Swiping a running session must ask for confirmation before ending it",
            confirm != null
        )
        Thread.sleep(2_000) // hold the dialog so a recording shows it

        // Decline, and the CLI must still be alive on the PC.
        device.findObject(By.text("Cancel"))?.click()
        Thread.sleep(3_000)
        // Retry: pc-agent reconnects periodically and a poll landing in that
        // window returns an empty list, which would read as "the session died"
        // when it is simply not being reported for a moment.
        assertTrue(
            "Cancelling must leave the user's own terminal CLI running " +
                "($target should still be live)",
            liveSessionIdsStable(expect = target).contains(target)
        )
        search.text = ""
        Thread.sleep(1_500)
    }

    /**
     * A live CLI with no store row exists only as a live-session row, and the
     * search screen used to omit those rows entirely -- so a running session was
     * visible in the list but impossible to find by searching for it.
     */
    @Test
    fun aStoreLessLiveSessionIsFindableBySearch() {
        val live = liveSessionIdsStable()
        val stored = storedStatuses()
        // Start our own store-less CLI rather than hoping one exists: opening a
        // session adopts it, so an ambient candidate is consumed by the first
        // test that runs and every later run would silently skip.
        val target = live.firstOrNull { !stored.containsKey(it) } ?: startStoreLessCli()
        assertTrue(
            "Could not obtain a live CLI without a store row. driverError=$fixtureError",
            target != null
        )
        liveSessionIds() // refresh liveWorkDirById for a freshly started fixture
        val dirName = liveWorkDirById[target]!!
            .trimEnd('/').substringAfterLast('/')

        navigateToChatTab()
        val search = device.wait(Until.findObject(By.res(PKG, "searchInput")), UI_TIMEOUT)
        assertTrue("Search field must be present", search != null)
        search!!.text = dirName
        Thread.sleep(4_000)

        // Scope the match to the RESULT LIST. By.textContains(dirName) alone
        // also matches the search field we just typed into, so it would pass
        // with zero results.
        val results = device.wait(Until.findObject(By.res(PKG, "chatsRecycler")), UI_TIMEOUT)
        assertTrue("Results list must be present", results != null)
        val hit = results!!.findObject(By.textContains(dirName))
        assertTrue(
            "A live store-less session must be findable by search (no matching " +
                "row inside chatsRecycler for '$dirName')",
            hit != null
        )
        Thread.sleep(2_000)

        // Leave the search box clean for the next test.
        search.text = ""
        Thread.sleep(1_000)
    }

    /**
     * A live CLI the orchestrator has no store row for surfaces only as a
     * live-session row. That row used to have its click listener explicitly set
     * to null, so the user could see the session running but had no way to open
     * it -- and opening is what adopts it. It must now be clickable.
     */
    @Test
    fun aStoreLessLiveSessionRowCanBeOpened() {
        val live = liveSessionIdsStable()
        val stored = storedStatuses()

        // Same self-destroying condition as the search test, so start a fixture
        // rather than assuming one is lying around.
        val target = live.firstOrNull { !stored.containsKey(it) } ?: startStoreLessCli()
        assertTrue(
            "Could not obtain a live CLI without a store row. driverError=$fixtureError",
            target != null
        )
        liveSessionIds() // refresh liveWorkDirById for a freshly started fixture
        val dirName = liveWorkDirById[target]!!.trimEnd('/').substringAfterLast('/')

        navigateToChatTab()
        device.wait(Until.hasObject(By.res(PKG, "chatsRecycler")), UI_TIMEOUT)
        Thread.sleep(3_000)

        // The live-only row titles as "> <dirName>". It may be below the fold in
        // a long list, so scroll the list looking for it before failing.
        var row = device.findObject(By.textContains(dirName))
        var scrolls = 0
        while (row == null && scrolls < 8) {
            val rv = device.findObject(By.res(PKG, "chatsRecycler")) ?: break
            val rb = rv.visibleBounds
            device.swipe(rb.centerX(), rb.bottom - 100, rb.centerX(), rb.top + 100, 12)
            Thread.sleep(800)
            row = device.findObject(By.textContains(dirName))
            scrolls++
        }
        assertTrue("Live session row for '$dirName' must be present", row != null)

        // Walk up: the click listener lives on the row container, not on the
        // TextView the text matched, and the container may be several levels up.
        var node: androidx.test.uiautomator.UiObject2? = row
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
        // Assert on the RC screen only. The old OR fell back to "the folder name
        // is on screen", which is still true from the list row when the click
        // does nothing -- so it passed whether or not the row opened.
        val opened = device.wait(Until.hasObject(By.res(PKG, "rcLoadingOverlay")), UI_TIMEOUT) ||
            device.wait(Until.hasObject(By.res(PKG, "rcInput")), UI_TIMEOUT)
        assertTrue("Tapping a live session row must open the RC chat", opened)
        Thread.sleep(2_000)
        device.pressBack()
    }
}
