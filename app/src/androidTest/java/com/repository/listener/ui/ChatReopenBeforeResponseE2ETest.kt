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

/**
 * Phone-side regression E2E for: "I ask the AI something in a regular chat and
 * reopen the chat before it answers -- I don't see my original prompt."
 *
 * Root cause (fixed): the orchestrator only persisted a chat turn AFTER the AI
 * responded, so a conversation reopened mid-flight returned no turns and the
 * optimistic in-memory user bubble was lost when ChatDetailActivity rebuilt from
 * the server. The fix persists the user half of the turn immediately (beginTurn)
 * so a mid-flight reload returns the prompt as a pending turn.
 *
 * This test drives the REAL UI (no coordinate taps -- ViewPager2.setCurrentItem,
 * resource-id lookups, and UiAutomator text selectors only):
 *   1. Launch MainActivity, switch to the Chats tab (ViewPager2 position 1).
 *   2. Tap the "new chat" FAB -> ChatDetailActivity (fresh, no conversationId).
 *   3. Type a unique prompt and tap send.
 *   4. IMMEDIATELY press back (leave the chat) -- well within the seconds-long
 *      LLM round-trip, i.e. before any response can arrive.
 *   5. Back on the chat list, tap the row whose title is our prompt to REOPEN
 *      the conversation.
 *   6. Assert the original prompt text is rendered in the reopened chat. Before
 *      the fix this row/text would be absent (empty conversation).
 *
 * Per-step screenshots are written as artifacts. Holds rendered states ~2-3s so
 * a screen recording of the run shows the observable behavior.
 *
 * Run (install ONLY the .test APK; deploy the app via deploy-to-phone.sh):
 *   adb shell am instrument -w -e class \
 *     com.repository.listener.ui.ChatReopenBeforeResponseE2ETest \
 *     com.repository.listener.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ChatReopenBeforeResponseE2ETest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instr)

    private companion object {
        const val PKG = "com.repository.listener"
        const val CHATS_TAB_INDEX = 1
        const val FIND_TIMEOUT = 20000L
        const val SHORT_WAIT = 1500L
        const val HOLD = 2500L
    }

    // Unique marker so the chat row + bubble are unambiguous across reruns.
    private val prompt = "E2E reopen probe ${System.currentTimeMillis() % 1_000_000} " +
        "please describe the history of cartography in detail"

    private fun shot(name: String): File {
        val dir = instr.targetContext.getExternalFilesDir(null)
            ?: throw IllegalStateException("No external files dir")
        val out = File(dir, name)
        val bmp: Bitmap = instr.uiAutomation.takeScreenshot()
            ?: throw IllegalStateException("takeScreenshot returned null for $name")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return out
    }

    /** Case-insensitive presence of a substring in the current UI dump. */
    private fun findText(needle: String, timeoutMs: Long): UiObject2? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val o = device.findObject(By.textContains(needle))
            if (o != null) return o
            device.wait(Until.hasObject(By.pkg(PKG)), 400)
            SystemClock.sleep(300)
        }
        return null
    }

    @Test
    fun prompt_survives_reopen_before_ai_responds() {
        // --- 1. Launch + go to Chats tab ---
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        device.wait(Until.hasObject(By.pkg(PKG).depth(0)), FIND_TIMEOUT)
        SystemClock.sleep(SHORT_WAIT)

        scenario.onActivity { act ->
            val vp = act.findViewById<ViewPager2>(R.id.viewPager)
            assertNotNull("viewPager not found", vp)
            vp.setCurrentItem(CHATS_TAB_INDEX, false)
        }
        SystemClock.sleep(SHORT_WAIT)
        shot("reopen_01_chatlist.png")

        // --- 2. New chat via FAB ---
        val fab = device.wait(Until.findObject(By.res(PKG, "fabNewChat")), FIND_TIMEOUT)
        if (fab == null) {
            shot("reopen_FAIL_no_fab.png")
            fail("New-chat FAB (fabNewChat) not found on the Chats tab.")
        }
        fab!!.click()

        // ChatDetailActivity is up once the message input is present.
        val input = device.wait(Until.findObject(By.res(PKG, "messageInput")), FIND_TIMEOUT)
        if (input == null) {
            shot("reopen_FAIL_no_input.png")
            fail("ChatDetailActivity message input not found after tapping new-chat FAB.")
        }
        SystemClock.sleep(SHORT_WAIT)

        // --- 3. Type the prompt and send ---
        input!!.text = prompt
        SystemClock.sleep(800)
        shot("reopen_02_typed.png")

        val send = device.wait(Until.findObject(By.res(PKG, "btnSend")), FIND_TIMEOUT)
        if (send == null) {
            shot("reopen_FAIL_no_send.png")
            fail("Send button (btnSend) not found / not visible after typing.")
        }
        send!!.click()

        // The optimistic user bubble should be visible right after send.
        val sentBubble = findText(prompt.take(24), FIND_TIMEOUT)
        if (sentBubble == null) {
            shot("reopen_FAIL_no_optimistic_bubble.png")
            fail("Prompt did not appear in the chat immediately after send.")
        }
        shot("reopen_03_sent.png")

        // --- 4. Leave the chat BEFORE the AI answers ---
        // Pressing back now (a few hundred ms after send) is well within the
        // multi-second LLM round-trip, so we exit mid-flight.
        device.pressBack()
        // Back on the chat list. Give listChats a moment to refresh and surface
        // the just-created conversation (its title == our prompt, derived from
        // the persisted pending user turn -- the crux of the fix).
        SystemClock.sleep(SHORT_WAIT)

        val row = findText(prompt.take(24), FIND_TIMEOUT)
        if (row == null) {
            shot("reopen_FAIL_no_row.png")
            fail(
                "After leaving mid-flight, the new conversation did NOT appear in " +
                    "the chat list. The pending user turn was not persisted server-side " +
                    "(this is the original bug)."
            )
        }
        shot("reopen_04_list_has_row.png")
        SystemClock.sleep(HOLD)

        // --- 5. Reopen the conversation ---
        row!!.click()
        // Wait for ChatDetailActivity again.
        val reopenedInput = device.wait(Until.findObject(By.res(PKG, "messageInput")), FIND_TIMEOUT)
        if (reopenedInput == null) {
            shot("reopen_FAIL_reopen_no_input.png")
            fail("Reopened ChatDetailActivity did not show (no message input).")
        }

        // --- 6. Assert the prompt is still there ---
        val reopenedBubble = findText(prompt.take(24), FIND_TIMEOUT)
        if (reopenedBubble == null) {
            shot("reopen_05_FAIL_prompt_lost.png")
            fail(
                "REGRESSION: after reopening the chat before the AI answered, the " +
                    "original prompt is NOT visible. The pending turn was dropped."
            )
        }
        SystemClock.sleep(HOLD)
        shot("reopen_05_PASS_prompt_present.png")

        assertTrue("Prompt present on reopen", true)
        scenario.close()
    }
}
