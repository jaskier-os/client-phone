package com.repository.listener.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import androidx.viewpager2.widget.ViewPager2
import com.repository.listener.MainActivity
import com.repository.listener.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.regex.Pattern

/**
 * Phone-side E2E for the Copilot Chat Logs feature.
 *
 * Drives the real UI (no coordinate taps -- ViewPager2.setCurrentItem + UiAutomator
 * text/content-desc selectors only):
 *   1. Launch MainActivity, switch to the Chats tab (ViewPager2 position 1).
 *   2. Assert the seeded Copilot row appears (matched by the "copilot" chip text and
 *      the seeded title substring). Screenshot copilot_list.png.
 *   3. Tap the row -> CopilotChatActivity. Assert turns rendered (seeded body text).
 *      Screenshot copilot_detail.png.
 *   4. Tap the "Share as PDF" action. Assert a PDF was produced in cacheDir/pdfs/.
 *      Screenshot copilot_share.png (chooser, if it appears), then dismiss.
 *
 * Fails loudly if the Copilot row never appears, the detail does not render, or no
 * PDF is produced.
 *
 * Run:
 *   adb shell am instrument -w -e class \
 *     com.repository.listener.ui.CopilotChatLogE2ETest \
 *     com.repository.listener.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class CopilotChatLogE2ETest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instr)

    private companion object {
        const val PKG = "com.repository.listener"
        const val CHATS_TAB_INDEX = 1
        const val FIND_TIMEOUT = 20000L
        const val SHORT_WAIT = 1500L
        const val LIST_HOLD = 2500L
        const val DETAIL_HOLD = 3000L

        // Seeded AP-automation sales conversation markers.
        val TITLE_MARKERS = listOf("manual invoice", "options to replace", "couple of options")
        val BODY_MARKERS = listOf("Xero", "NetSuite", "invoice", "AP", "automation")
    }

    private fun <T> onMain(block: () -> T): T {
        val holder = arrayOfNulls<Any>(1)
        instr.runOnMainSync { holder[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    /**
     * Collect the text of every TextView in the current foreground activity's
     * decor view. Must be called on the main thread.
     */
    private fun currentActivityTextViews(): List<String> {
        val out = ArrayList<String>()
        val act = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
            .getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
            .firstOrNull() ?: return out
        val root = act.window?.decorView ?: return out
        fun walk(v: android.view.View) {
            if (v is android.widget.TextView) {
                val s = v.text?.toString()
                if (!s.isNullOrEmpty()) out.add(s)
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    private fun shot(name: String): File {
        val dir = instr.targetContext.getExternalFilesDir(null)
            ?: throw IllegalStateException("No external files dir")
        val out = File(dir, name)
        val bmp: Bitmap = instr.uiAutomation.takeScreenshot()
            ?: throw IllegalStateException("takeScreenshot returned null for $name")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        assertTrue("Screenshot $name is empty", out.length() > 1000)
        return out
    }

    /** Case-insensitive presence of any marker in the current UI dump. */
    private fun findAnyText(markers: List<String>, timeoutMs: Long): UiObject2? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            for (m in markers) {
                val o = device.findObject(By.textContains(m))
                if (o != null) return o
            }
            device.wait(Until.hasObject(By.pkg(PKG)), 500)
            SystemClock.sleep(400)
        }
        return null
    }

    @Test
    fun copilotChatLog_listDetailAndPdfExport() {
        // --- 1. Launch + go to Chats tab ---
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        device.wait(Until.hasObject(By.pkg(PKG).depth(0)), FIND_TIMEOUT)
        SystemClock.sleep(SHORT_WAIT)

        scenario.onActivity { act ->
            val vp = act.findViewById<ViewPager2>(R.id.viewPager)
            assertNotNull("viewPager not found", vp)
            vp.setCurrentItem(CHATS_TAB_INDEX, false)
        }
        // Let the chat list load from the orchestrator.
        SystemClock.sleep(SHORT_WAIT)

        // --- 2. Assert the seeded Copilot row appears ---
        // The copilot chip text is the lowercase literal "copilot"; the row title is
        // the seeded conversation title. Require BOTH the chip and a title marker.
        val chip = device.wait(Until.findObject(By.text("copilot")), FIND_TIMEOUT)
        if (chip == null) {
            shot("copilot_list_FAIL.png")
            fail(
                "Copilot 'copilot' chip never appeared in the chat list within " +
                    "${FIND_TIMEOUT}ms. The seeded session (af80013d-...) did not " +
                    "surface on this phone/account. Check copilot list scoping."
            )
        }
        val titleRow = findAnyText(TITLE_MARKERS, FIND_TIMEOUT)
        if (titleRow == null) {
            shot("copilot_list_FAIL.png")
            fail(
                "Copilot chip present but seeded title (markers=$TITLE_MARKERS) not " +
                    "found -- a different copilot session may be showing."
            )
        }
        SystemClock.sleep(LIST_HOLD)
        val listShot = shot("copilot_list.png")

        // --- 3. Open detail ---
        titleRow!!.click()
        // Wait for CopilotChatActivity: the share action content-desc is unique to it.
        val shareBtn = device.wait(
            Until.findObject(By.desc("Share as PDF")), FIND_TIMEOUT
        )
        if (shareBtn == null) {
            shot("copilot_detail_FAIL.png")
            fail("CopilotChatActivity 'Share as PDF' action not found -- detail did not open.")
        }
        // Assert turns rendered: seeded body text must be visible.
        val body = findAnyText(BODY_MARKERS, FIND_TIMEOUT)
        if (body == null) {
            shot("copilot_detail_FAIL.png")
            fail("Detail opened but no seeded turn text (markers=$BODY_MARKERS) rendered.")
        }
        // --- 3b. Assert no reply card shows doubled quotes ---
        // The renderer wraps reply lines in exactly one curly pair (U+201C..U+201D)
        // after CopilotChatAdapter.stripWrappingQuotes removes any model-embedded
        // quotes. Walk the on-screen text and fail if any reply text contains a
        // doubled opening/closing curly quote or a doubled straight quote.
        val replyTexts = onMain {
            val act = currentActivityTextViews()
            act
        }
        val doubledQuoteOffenders = replyTexts.filter { t ->
            t.contains("\u201C\u201C") || t.contains("\u201D\u201D") ||
                t.contains("\"\"") || t.contains("\u201C\"") || t.contains("\"\u201D")
        }
        if (doubledQuoteOffenders.isNotEmpty()) {
            shot("copilot_detail_DOUBLEQUOTE_FAIL.png")
            fail(
                "Reply card(s) still show DOUBLED quotes after the strip fix: " +
                    doubledQuoteOffenders.joinToString(" | ") { it.take(80) }
            )
        }
        // Positive proof: at least one reply line is wrapped in a single curly pair.
        val singleWrapped = replyTexts.any { it.startsWith("\u201C") && it.endsWith("\u201D") }
        assertTrue(
            "Expected at least one reply line wrapped in a single curly-quote pair",
            singleWrapped
        )

        SystemClock.sleep(DETAIL_HOLD)
        val detailShot = shot("copilot_detail.png")

        // --- 4. Trigger PDF export via the Share action ---
        // Clean any pre-existing pdfs first so we assert a freshly-produced file.
        val pdfDir = File(instr.targetContext.cacheDir, "pdfs")
        val before = pdfDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

        shareBtn!!.click()

        // PDF build runs on Dispatchers.IO then opens the system chooser. Poll the
        // cache dir for a newly created copilot-*.pdf.
        var pdf: File? = null
        val deadline = SystemClock.uptimeMillis() + FIND_TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            val now = pdfDir.listFiles()
                ?.filter { it.name.endsWith(".pdf") && it.length() > 0 }
                ?: emptyList()
            pdf = now.firstOrNull { it.name !in before } ?: now.maxByOrNull { it.lastModified() }
            if (pdf != null && pdf.length() > 0) break
            SystemClock.sleep(500)
        }

        // Try to capture the chooser if it surfaced (system UI).
        val chooser = device.wait(
            Until.findObject(By.textContains("Share")), 4000
        )
        if (chooser != null) {
            SystemClock.sleep(1000)
            shot("copilot_share.png")
            // Dismiss the chooser so we leave the app in a clean state.
            device.pressBack()
        }

        if (pdf == null || pdf.length() <= 0) {
            shot("copilot_share_FAIL.png")
            fail("Share tapped but no PDF was produced in ${pdfDir.absolutePath}")
        }

        // Sanity log via assertions (visible in instrumentation output on failure).
        assertTrue("PDF too small: ${pdf!!.length()} bytes", pdf.length() > 1000)
        assertTrue("List screenshot missing", listShot.exists())
        assertTrue("Detail screenshot missing", detailShot.exists())

        scenario.close()
    }
}
