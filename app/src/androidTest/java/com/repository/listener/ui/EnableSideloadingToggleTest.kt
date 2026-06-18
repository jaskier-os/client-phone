package com.repository.listener.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.repository.listener.config.AppConfig
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the Glasses > Settings sub-tab "Enable sideloading" checkbox ON.
 *
 * Checking it calls GlassesSettingsFragment.setSideloadingEnabled(true), which
 * (1) persists AppConfig.setSideloadingEnabled(ctx,true), (2) starts the phone
 * LAN sideload HTTP server on :8771, and (3) pushes {"enable_sideloading":true}
 * to the glasses over BT (CH_SETTINGS).
 *
 * UI is driven ENTIRELY via UiAutomator selectors (resource-id / text) -- never
 * coordinate taps, never screenshot-read-image. The state is held visible ~3s at
 * the end so an external screenrecord captures the toggled checkbox.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class EnableSideloadingToggleTest {

    companion object {
        const val PKG = "com.repository.listener"
        const val FIND_TIMEOUT = 8000L
        const val SHORT_WAIT = 1200L
        const val HOLD_FOR_RECORDING = 3000L
        const val CHK_ID = "$PKG:id/chkEnableSideloading"
    }

    private lateinit var device: UiDevice

    @Test
    fun enableSideloading() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Launch the app.
        device.executeShellCommand(
            "am start -n $PKG/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
        )
        device.wait(Until.hasObject(By.pkg(PKG).depth(0)), FIND_TIMEOUT)
        Thread.sleep(SHORT_WAIT)

        // Open the Glasses tab (by resource-id, fall back to content-desc).
        val glassesTab = device.wait(Until.findObject(By.res("$PKG:id/tab_glasses")), FIND_TIMEOUT)
            ?: device.wait(Until.findObject(By.desc("Glasses")), FIND_TIMEOUT)
        assertNotNull("Glasses tab should be visible", glassesTab)
        glassesTab!!.click()
        Thread.sleep(SHORT_WAIT)

        // Open the inner "Settings" sub-tab (GlassesSubTabAdapter position 2).
        val settingsSubTab = device.wait(Until.findObject(By.text("Settings")), FIND_TIMEOUT)
        assertNotNull("Glasses 'Settings' sub-tab should be visible", settingsSubTab)
        settingsSubTab!!.click()
        Thread.sleep(SHORT_WAIT)

        // The checkbox lives inside a ScrollView (Developer card near the bottom).
        try {
            val scroller = UiScrollable(UiSelector().scrollable(true))
            scroller.scrollIntoView(UiSelector().resourceId(CHK_ID))
        } catch (_: Exception) {
            // not scrollable / already visible
        }

        val chk = device.wait(Until.findObject(By.res(CHK_ID)), FIND_TIMEOUT)
            ?: device.wait(Until.findObject(By.text("Enable sideloading")), FIND_TIMEOUT)
        assertNotNull("'Enable sideloading' checkbox should be visible", chk)

        // Only toggle if not already checked, so the test is idempotent.
        if (chk!!.isCheckable && !chk.isChecked) {
            chk.click()
            Thread.sleep(SHORT_WAIT)
        } else if (!chk.isCheckable) {
            // Fall back to clicking the label row if the checkable node is the text.
            device.wait(Until.findObject(By.text("Enable sideloading")), FIND_TIMEOUT)?.click()
            Thread.sleep(SHORT_WAIT)
        }

        // Hold the rendered, checked state so a screenrecord captures it.
        Thread.sleep(HOLD_FOR_RECORDING)

        // Assert the persisted flag flipped on.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "AppConfig.getSideloadingEnabled should be true after toggling the checkbox",
            AppConfig.getSideloadingEnabled(ctx)
        )
    }
}
