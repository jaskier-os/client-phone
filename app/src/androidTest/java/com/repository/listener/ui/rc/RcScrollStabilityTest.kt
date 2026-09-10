package com.repository.listener.ui.rc

import android.content.Intent
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A running tool's heartbeats must not move a chat the user has scrolled up.
 *
 * Reported three times. The scroll CALLS were gated correctly, yet the list
 * still snapped to the bottom on every tick. This test does not reason about
 * why: it fills the chat, scrolls up, drives real heartbeats through the same
 * callback the WebSocket uses, and asserts the RecyclerView's own scroll
 * offset did not change. Anything that moves the list, by whatever mechanism,
 * fails it.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RcScrollStabilityTest {

    companion object {
        private const val PKG = "com.repository.listener"
        private const val SESSION = "test-scroll-session"
        private const val UI_TIMEOUT = 10_000L
    }

    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand(
            "am start -n $PKG/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
        )
        device.wait(Until.hasObject(By.pkg(PKG).depth(0)), UI_TIMEOUT)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.startActivity(Intent(ctx, RemoteControlActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(RemoteControlActivity.EXTRA_SESSION_ID, SESSION)
            putExtra(RemoteControlActivity.EXTRA_WORK_DIR, "/home/user/scroll")
        })
        assertTrue("RC chat must open", device.wait(Until.hasObject(By.res(PKG, "rcMessageList")), UI_TIMEOUT))
        Thread.sleep(800)
    }

    private fun inject(params: JSONObject) {
        params.put("sessionId", SESSION)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.sendBroadcast(Intent("$PKG.ADB_COMMAND").apply {
            setClassName(PKG, "$PKG.adb.AdbCommandReceiver")
            putExtra("type", "rc_inject_event")
            putExtra("command_id", "scroll-${System.nanoTime()}")
            putExtra("params", params.toString())
        })
    }

    /** A finished assistant message, long enough that many of them overflow the screen. */
    private fun message(i: Int) = inject(JSONObject().apply {
        put("action", "message")
        put("isFinal", true)
        put("text", "Message $i. " + "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(6))
    })

    private fun heartbeat(seq: Int) = inject(JSONObject().apply {
        put("action", "tool_status")
        put("toolName", "Bash")
        put("status", "running")
        put("toolCallId", "tu-scroll-1")
        put("elapsedMs", seq * 2000L)
        put("seq", seq)
    })

    /** The list's own scroll offset, read on the main thread. */
    private fun scrollOffset(): Int {
        val inst = InstrumentationRegistry.getInstrumentation()
        var offset = -1
        inst.runOnMainSync {
            val activity = currentActivity() ?: return@runOnMainSync
            val rv = activity.findViewById<RecyclerView>(
                activity.resources.getIdentifier("rcMessageList", "id", PKG)
            )
            offset = rv.computeVerticalScrollOffset()
        }
        return offset
    }

    private fun currentActivity(): android.app.Activity? {
        val cls = Class.forName("android.app.ActivityThread")
        val thread = cls.getMethod("currentActivityThread").invoke(null)
        val field = cls.getDeclaredField("mActivities").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val records = field.get(thread) as Map<Any, Any>
        for (record in records.values) {
            val rc = record.javaClass
            val paused = rc.getDeclaredField("paused").apply { isAccessible = true }.getBoolean(record)
            if (!paused) {
                return rc.getDeclaredField("activity").apply { isAccessible = true }.get(record) as android.app.Activity
            }
        }
        return null
    }

    @Test
    fun heartbeatsDoNotMoveAListTheUserScrolledUp() {
        // 1. Enough finished messages that the list is far taller than the screen.
        repeat(25) { message(it) }
        Thread.sleep(1_500)
        // 2. A tool starts and stays in flight (its row is the LAST item).
        heartbeat(1)
        Thread.sleep(800)

        // 3. The user scrolls well up to read.
        val list = device.findObject(By.res(PKG, "rcMessageList"))
        repeat(4) {
            list.scroll(androidx.test.uiautomator.Direction.UP, 0.8f)
            Thread.sleep(200)
        }
        Thread.sleep(600)
        val before = scrollOffset()
        assertTrue("Precondition: must be scrolled away from the bottom (offset=$before)", before >= 0)

        // 4. Heartbeats arrive, exactly as they do every 2s for a slow tool.
        for (seq in 2..6) {
            heartbeat(seq)
            Thread.sleep(700)
        }
        Thread.sleep(600)
        val after = scrollOffset()

        // 5. The list must not have moved. Any drift of more than a few px of
        //    layout rounding means a tick dragged the viewport.
        val drift = after - before
        assertTrue(
            "Tool heartbeats moved the chat by ${drift}px (offset $before -> $after). " +
                "A running tool must never scroll a list the user has scrolled up.",
            kotlin.math.abs(drift) <= 8
        )
    }
}
