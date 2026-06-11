package com.repository.listener.ui.rc

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reproduces the user-reported bug: tapping RC session "chips" (rows with view
 * type TYPE_RC_SESSION in the chats list) does nothing for some of them.
 *
 * Uses whatever RC sessions already exist on the device -- this test does not
 * inject sessions via broadcast (the broadcast pathway has its own issues).
 * It walks every visible RC row (identified by the ">_" terminal icon) and
 * taps each, asserting that RemoteControlActivity opens.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RcChipTapTest {

    companion object {
        private const val PKG = "com.repository.listener"
        private const val UI_TIMEOUT = 8000L
    }

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand(
            "am start -n $PKG/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
        )
        val appeared = device.wait(Until.hasObject(By.res(PKG, "tabLayout")), UI_TIMEOUT)
        assertTrue("MainActivity should launch and show tabLayout", appeared)
        navigateToChatTab()
        device.wait(Until.hasObject(By.res(PKG, "chatsRecycler")), UI_TIMEOUT)
        // RC sessions arrive via orchestrator WS broadcast asynchronously after
        // service reconnect. Allow generous time on a fresh install.
        Thread.sleep(15000)
    }

    private fun navigateToChatTab() {
        val tabs = device.findObject(By.res(PKG, "tabLayout"))
        assertNotNull("TabLayout should be visible", tabs)
        val bounds = tabs.visibleBounds
        val tabWidth = bounds.width() / 7
        val x = bounds.left + tabWidth + (tabWidth / 2)
        val y = bounds.centerY()
        device.click(x, y)
        Thread.sleep(600)
    }

    @Test
    fun tapEveryVisibleRcChipAndReportWhichOnesNavigate() {
        // RC rows are identifiable by the ">_" terminal icon TextView.
        val terminalIcons = device.findObjects(By.text(">_"))
        assertTrue(
            "Expected at least 1 RC chip on screen (have you opened the chats tab with active/ended RC sessions?). Found: ${terminalIcons.size}",
            terminalIcons.isNotEmpty()
        )

        ScreenshotHelper.take("rc_chips_initial")

        val results = mutableListOf<String>()
        // Capture chip bounds upfront -- after we navigate away and back, the
        // UiObject2 references go stale.
        val chipBounds: List<Rect> = terminalIcons.map { it.visibleBounds }

        for ((idx, iconBounds) in chipBounds.withIndex()) {
            // The tappable row container extends well beyond the ">_" icon.
            // Tap the title text right of the icon (where users naturally tap).
            val tapX = iconBounds.right + 80
            val tapY = iconBounds.centerY()
            android.util.Log.d("RcChipTapTest", "Tapping chip #$idx at ($tapX,$tapY) iconBounds=$iconBounds")
            device.click(tapX, tapY)
            Thread.sleep(1500)

            // Did RemoteControlActivity open? Look for its mode chip.
            val modeChip = device.wait(
                Until.findObject(By.res(PKG, "rcModeSelector")),
                3000L
            )
            val opened = modeChip != null
            results.add("chip#$idx tapAt=($tapX,$tapY) opened=$opened")

            ScreenshotHelper.take("rc_chip_${idx}_after_tap_opened_$opened")

            if (opened) {
                device.pressBack()
                Thread.sleep(800)
                // Make sure we're back on the list before tapping the next one.
                device.wait(Until.hasObject(By.res(PKG, "chatsRecycler")), UI_TIMEOUT)
                Thread.sleep(500)
            }
        }

        val report = results.joinToString("\n")
        android.util.Log.d("RcChipTapTest", "RESULT:\n$report")

        val failed = results.filter { it.contains("opened=false") }
        assertTrue(
            "Some RC chips did NOT open RemoteControlActivity:\n$report",
            failed.isEmpty()
        )
    }
}
