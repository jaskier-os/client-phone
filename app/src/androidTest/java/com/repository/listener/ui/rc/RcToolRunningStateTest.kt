package com.repository.listener.ui.rc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for "a long-running tool renders as completed".
 *
 * The bug: the phone suppressed non-agent `calling` events on the theory that
 * the permission card already represented the in-flight tool -- but that card
 * painted itself GREEN (the complete treatment) the moment `approved` became
 * true. Approving a tool therefore looked exactly like the tool finishing, and
 * a `TaskOutput` blocking for four minutes looked done after 200ms.
 *
 * What is asserted here, in order of importance:
 *
 *   1. NEGATIVE (the actual regression): while the tool is provably still in
 *      flight, NO row shows the completed treatment for that tool.
 *   2. An elapsed counter is rendered for the in-flight row and its value
 *      INCREASES across samples taken ~3.5s apart (proves
 *      RcDetailAdapter.tickInFlightToolRows() re-binds at 1Hz).
 *   3. After the tool really finishes the row flips to complete EXACTLY ONCE
 *      and the label stops advancing.
 *
 * Determinism: realism is deliberately traded away. Instead of hoping the model
 * picks a slow tool, the prompt asks for one specific `Bash` command that sleeps
 * for a known duration, so "is it still running?" is answerable from the clock
 * rather than inferred from the UI we are trying to test.
 *
 * How rows are identified WITHOUT coordinate taps or screenshot reading:
 * chat bubbles carry contentDescription "rcUserText" / "rcAssistantText"
 * (RcDetailAdapter.createBubbleHolder), tool/permission card rows carry none.
 * So `contentDescription == null` isolates card rows, and the primary label
 * shape does the rest:
 *
 *   pending   : "Bash"                       (friendlyToolName, no status icon)
 *   running   : "[*] sleep 40 && echo ... - 0m 12s"
 *   complete  : "[+] sleep 40 && echo ..."
 *   error     : "[!] ..."
 *
 * Requires a live orchestrator + pc-agent, as every other RC E2E here does.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RcToolRunningStateTest {

    companion object {
        private const val WORK_DIR = "/tmp/rc-test-slow-tool"

        /** Seconds the tool is held in flight. Long enough for two elapsed
         *  samples plus scheduling slack, short enough not to bore a recording. */
        // Long enough that the whole sample sequence (first sample, 3s hold,
        // 3.5s gap, second sample) fits well inside it even when the model takes
        // several seconds to decide to call Bash.
        private const val SLEEP_SECONDS = 150

        /** Unique-enough token so the tool row can be told apart from unrelated
         *  Bash rows in the same session. Kept short so it survives the 60-char
         *  truncation in buildToolPrimaryTextBase(). */
        private val MARKER = "RCSLOW${System.currentTimeMillis() % 100000}"

        private const val TOOL_APPEAR_TIMEOUT_MS = 120_000L
        private const val TOOL_FINISH_TIMEOUT_MS = 180_000L
        private const val SAMPLE_GAP_MS = 3_500L
        private const val HOLD_MS = 3_000L
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
        // pressHome/pressBack only -- never `am force-stop` on the app package,
        // instrumentation lives inside that process and would kill its runner.
        try { harness.endSessionAndReturn() } catch (_: Throwable) {}
        try { device.pressHome() } catch (_: Throwable) {}
        try { harness.stopRemoteSession(sessionId) } catch (_: Throwable) {}
        try { Thread.sleep(2_000) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------
    // Row scraping helpers (resource-id / contentDescription / text only)
    // ------------------------------------------------------------------

    /** Text of every card row currently rendered (bubbles excluded). */
    private fun cardRowTexts(): List<String> {
        val objs: List<UiObject2> =
            device.findObjects(By.textContains(MARKER)) +
                device.findObjects(By.textStartsWith("[*] ")) +
                device.findObjects(By.textStartsWith("[+] ")) +
                device.findObjects(By.textStartsWith("[!] "))
        return objs
            .filter { runCatching { it.contentDescription }.getOrNull() == null }
            .mapNotNull { runCatching { it.text }.getOrNull()?.trim() }
            .filter { it.isNotEmpty() && !it.contains('\n') }
            .distinct()
    }

    /** Rows showing the in-flight treatment ("[*] " icon, optional elapsed). */
    private fun runningRows(): List<String> = cardRowTexts().filter { it.startsWith("[*] ") }

    /**
     * Rows showing the COMPLETED treatment for OUR tool. The completed Bash
     * label is rebuilt from the tool args, so it always carries the command --
     * and therefore the marker.
     */
    private fun completedMarkerRows(): List<String> =
        cardRowTexts().filter { it.startsWith("[+] ") && it.contains(MARKER) }

    /** Parses the " - 1m 12s" suffix appended by buildToolPrimaryText. */
    private fun parseElapsedSeconds(rowText: String): Long? {
        val m = Regex(" - (\\d+)m (\\d+)s$").find(rowText) ?: return null
        val mins = m.groupValues[1].toLongOrNull() ?: return null
        val secs = m.groupValues[2].toLongOrNull() ?: return null
        return mins * 60 + secs
    }

    /** The in-flight row that currently carries an elapsed counter, if any. */
    private fun runningRowWithElapsed(): Pair<String, Long>? =
        runningRows().mapNotNull { r -> parseElapsedSeconds(r)?.let { r to it } }
            .maxByOrNull { it.second }

    /** Everything on screen, for failure messages that say what DID render. */
    private fun renderedDump(): String =
        "cardRows=${cardRowTexts()} user=${harness.userTexts()} assistant=${harness.assistantTexts()}"

    /**
     * The negative assertion, callable at any point. Fails the instant the UI
     * shows the completed treatment for a tool we know is still sleeping.
     */
    private fun assertNotShownAsCompleted(phase: String) {
        val done = completedMarkerRows()
        assertTrue(
            "REGRESSION ($phase): tool row rendered the COMPLETED treatment while the " +
                "Bash sleep was still in flight. completed rows=$done; ${renderedDump()}",
            done.isEmpty()
        )
    }

    // ------------------------------------------------------------------

    @Test
    fun longRunningToolDoesNotRenderAsCompletedWhileStillRunning() {
        val command = "sleep $SLEEP_SECONDS && echo ${MARKER}_DONE"

        harness.sendMessage(
            "Use the Bash tool to run exactly this one command and nothing else, " +
                "then reply with a single word:\n$command"
        )
        ScreenshotHelper.take("01_slow_tool_prompt_sent")

        // ------------------------------------------------------------------
        // 1. Wait for the tool to be in flight WITH an elapsed counter.
        //    buildToolPrimaryText suppresses the counter below 2s, so this also
        //    proves the counter appeared at all.
        // ------------------------------------------------------------------
        // The in-flight row appears BEFORE its elapsed counter does (the counter
        // is suppressed under 2s). Wait for the row first, and only then for the
        // counter: polling for both at once let the whole 40s window pass
        // unnoticed, after which the row is legitimately complete and the
        // "still in flight" assertions were being made about a finished tool.
        val appearDeadline = System.currentTimeMillis() + TOOL_APPEAR_TIMEOUT_MS
        var sawInFlight = false
        while (System.currentTimeMillis() < appearDeadline) {
            if (runningRows().any { it.contains(MARKER) }) { sawInFlight = true; break }
            // Only meaningful before the row exists: a completed row appearing
            // without any in-flight row ever showing IS the regression.
            assertNotShownAsCompleted("waiting for in-flight row")
            Thread.sleep(200)
        }
        assertTrue(
            "No in-flight ([*]) row for our command ever appeared within " +
                "${TOOL_APPEAR_TIMEOUT_MS}ms. In-flight tools are being suppressed or " +
                "rendered as finished. ${renderedDump()}",
            sawInFlight
        )

        var first: Pair<String, Long>? = null
        while (System.currentTimeMillis() < appearDeadline) {
            first = runningRowWithElapsed()
            if (first != null) break
            Thread.sleep(200)
        }
        val (firstText, firstElapsed) = first ?: throw AssertionError(
            "No in-flight tool row with an elapsed counter appeared within " +
                "${TOOL_APPEAR_TIMEOUT_MS}ms. Either the session never ran the Bash " +
                "command, or in-flight tools are still being suppressed/rendered as " +
                "finished. ${renderedDump()}"
        )
        val sampledAtMs = System.currentTimeMillis()
        ScreenshotHelper.take("02_tool_in_flight_first_sample")

        // Assert BEFORE holding: the hold plus the sample gap is ~7s, which on a
        // slow first sample can carry us past the end of the sleep, at which
        // point a completed row is correct and asserting otherwise is wrong.
        assertNotShownAsCompleted("first sample, elapsed=${firstElapsed}s")

        // The counter is the authority on how much sleep is left. Polling can
        // land late (the row has to exist AND carry a counter before we see it),
        // and continuing with less than the sample sequence's own duration
        // remaining would assert "still running" about a tool that finished --
        // failing the test for the app behaving correctly.
        val neededSeconds = (HOLD_MS + SAMPLE_GAP_MS) / 1000 + 5
        assertTrue(
            "Sampling started ${firstElapsed}s into a ${SLEEP_SECONDS}s sleep, leaving " +
                "less than ${neededSeconds}s -- not enough to sample twice inside the " +
                "in-flight window. This is a harness timing problem, not an app bug: " +
                "raise SLEEP_SECONDS. ${renderedDump()}",
            SLEEP_SECONDS - firstElapsed >= neededSeconds
        )

        // Hold the in-flight state visible so a screen recording captures it.
        Thread.sleep(HOLD_MS)

        // ------------------------------------------------------------------
        // 2. The counter must ADVANCE. A frozen label would mean
        //    tickInFlightToolRows() is not re-binding the row.
        // ------------------------------------------------------------------
        Thread.sleep(SAMPLE_GAP_MS)
        val second = runningRowWithElapsed() ?: throw AssertionError(
            "In-flight row disappeared or lost its elapsed counter after " +
                "${SAMPLE_GAP_MS}ms while the ${SLEEP_SECONDS}s sleep was still " +
                "running. first='$firstText'; ${renderedDump()}"
        )
        ScreenshotHelper.take("03_tool_in_flight_second_sample")
        Thread.sleep(HOLD_MS)

        assertTrue(
            "Elapsed counter did not advance across ${SAMPLE_GAP_MS}ms: " +
                "'$firstText' (${firstElapsed}s) -> '${second.first}' (${second.second}s). " +
                "The 1Hz in-flight ticker is not re-binding the row.",
            second.second > firstElapsed
        )

        // The tool is provably still running (we are well inside the sleep), so
        // this is the load-bearing negative assertion of the whole test.
        val insideSleep = System.currentTimeMillis() - sampledAtMs < (SLEEP_SECONDS - 10) * 1000L
        assertTrue(
            "Test scheduling drifted past the sleep window; cannot prove the tool was " +
                "still in flight. Increase SLEEP_SECONDS.",
            insideSleep
        )
        assertNotShownAsCompleted("second sample, elapsed=${second.second}s")

        // ------------------------------------------------------------------
        // 3. After the sleep really ends the row must flip to complete, once.
        // ------------------------------------------------------------------
        val finishDeadline = System.currentTimeMillis() + TOOL_FINISH_TIMEOUT_MS
        var done: List<String> = emptyList()
        while (System.currentTimeMillis() < finishDeadline) {
            done = completedMarkerRows()
            if (done.isNotEmpty()) break
            Thread.sleep(500)
        }
        assertTrue(
            "Tool never flipped to the completed treatment within " +
                "${TOOL_FINISH_TIMEOUT_MS}ms after the ${SLEEP_SECONDS}s sleep. " +
                "${renderedDump()}",
            done.isNotEmpty()
        )
        ScreenshotHelper.take("04_tool_completed")
        Thread.sleep(HOLD_MS)

        assertEquals(
            "The tool should render as completed exactly once, not duplicated across " +
                "rows. rows=$done",
            1, done.size
        )

        // ------------------------------------------------------------------
        // 4. Completion must FREEZE the label: no elapsed suffix growth, and no
        //    row still claiming to be in flight for this tool.
        // ------------------------------------------------------------------
        val frozenBefore = done.first()
        Thread.sleep(SAMPLE_GAP_MS)
        val frozenAfter = completedMarkerRows().firstOrNull() ?: throw AssertionError(
            "Completed tool row vanished after completion. ${renderedDump()}"
        )
        assertEquals(
            "Completed tool label kept changing -- the elapsed counter is still " +
                "ticking after the tool finished (a late 'running' heartbeat likely " +
                "overwrote the 'complete' state).",
            frozenBefore, frozenAfter
        )
        assertTrue(
            "Completed tool row must not carry a live elapsed counter: '$frozenAfter'",
            parseElapsedSeconds(frozenAfter) == null
        )
        assertTrue(
            "A row for this tool is still rendered as in-flight after completion: " +
                "${runningRows().filter { it.contains(MARKER) }}",
            runningRows().none { it.contains(MARKER) }
        )
        ScreenshotHelper.take("05_tool_completed_frozen")
        Thread.sleep(HOLD_MS)
    }
}
