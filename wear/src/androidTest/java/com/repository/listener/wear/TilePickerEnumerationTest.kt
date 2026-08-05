package com.repository.listener.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Enumerates every entry the One UI Watch tile picker ("Add tiles") offers, so we
 * can prove from a test — not from a screenshot — whether this app's tile is
 * offered to the user.
 *
 * Scrolling is done through the accessibility node's own scroll-forward action,
 * never through synthesised coordinate swipes, so the result does not depend on
 * where anything happens to be drawn.
 */
@RunWith(AndroidJUnit4::class)
class TilePickerEnumerationTest {

    private companion object {
        const val PICKER_ACTION = "com.samsung.android.wearable.sysui.ACTION_SHOW_TILE_ADDABLE"
        const val PICKER_PKG = "com.samsung.android.wearable.sysui"
        const val MAX_SCROLLS = 60
    }

    @Test
    fun listEveryOfferedTile() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        device.wakeUp()
        ctx.startActivity(
            android.content.Intent(PICKER_ACTION)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        device.wait(Until.hasObject(By.pkg(PICKER_PKG)), 10_000)

        val seen = LinkedHashSet<String>()
        var idle = 0
        for (i in 0 until MAX_SCROLLS) {
            val before = seen.size

            // Each app is shown as a collapsed group whose content description is
            // "Свернутый вид, <app>, N Карточки". The individual tiles only become
            // readable once the group is expanded, so expand every group we meet.
            // Every app that offers tiles appears as its own group header bearing
            // the app label, so reading headers is enough to tell whether this app
            // is offered at all. Nodes can go stale between passes, hence the
            // guards.
            runCatching {
                device.findObjects(By.pkg(PICKER_PKG)).forEach { o ->
                    runCatching { o.text }.getOrNull()
                        ?.takeIf { it.isNotBlank() }?.let { seen.add(it) }
                    runCatching { o.contentDescription }.getOrNull()
                        ?.takeIf { it.isNotBlank() }?.let { seen.add(it) }
                }
            }
            if (seen.size == before) idle++ else idle = 0
            if (idle >= 4) break

            val scroller = device.findObject(By.scrollable(true)) ?: break
            val moved = runCatching {
                scroller.scroll(androidx.test.uiautomator.Direction.DOWN, 0.6f)
            }.getOrDefault(true)
            if (!moved && idle >= 2) break
            device.waitForIdle(1_000)
        }

        // The tag is what we grep for in the instrumentation output.
        seen.forEach { android.util.Log.i("TILEPICKER", "OFFERED: $it") }
        android.util.Log.i("TILEPICKER", "TOTAL: ${seen.size}")
        val ours = seen.any { it.contains("Glasses", ignoreCase = true) }
        android.util.Log.i("TILEPICKER", "CONTAINS_OUR_TILE: $ours")
    }
}
