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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the Config > Server tab map-provider dropdown to select "Google".
 *
 * Selecting "Google" calls AppConfig.setMapProvider(ctx,"google") and broadcasts
 * ACTION_SETTINGS_CHANGED, which triggers the idle-only provider switch in
 * ListenerService. Verifies the persisted pref flips to "google".
 *
 * UI is driven entirely via UiAutomator selectors (resource-id / text) -- never
 * coordinate taps.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SetMapProviderUiTest {

    companion object {
        const val PKG = "com.repository.listener"
        const val FIND_TIMEOUT = 8000L
        const val SHORT_WAIT = 1200L
        const val DROPDOWN_ID = "$PKG:id/dropdownMapProvider"
    }

    private lateinit var device: UiDevice

    @Test
    fun selectGoogleProvider() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Launch the app.
        device.executeShellCommand(
            "am start -n $PKG/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
        )
        device.wait(Until.hasObject(By.pkg(PKG).depth(0)), FIND_TIMEOUT)
        Thread.sleep(SHORT_WAIT)

        // Open the Config tab (uses content-desc "Config").
        val configTab = device.wait(Until.findObject(By.res("$PKG:id/tab_config")), FIND_TIMEOUT)
            ?: device.wait(Until.findObject(By.desc("Config")), FIND_TIMEOUT)
        assertNotNull("Config tab should be visible", configTab)
        configTab!!.click()
        Thread.sleep(SHORT_WAIT)

        // The inner "Server" sub-tab is position 0 and selected by default, but click
        // it to be safe in case the user left another sub-tab open.
        device.wait(Until.findObject(By.text("Server")), FIND_TIMEOUT)?.let {
            it.click()
            Thread.sleep(SHORT_WAIT)
        }

        // The dropdown lives inside a ScrollView -- scroll it into view.
        try {
            val scroller = UiScrollable(UiSelector().scrollable(true))
            scroller.scrollIntoView(UiSelector().resourceId(DROPDOWN_ID))
        } catch (_: Exception) {
            // No scroll needed / not scrollable; continue.
        }

        val dropdown = device.wait(Until.findObject(By.res(DROPDOWN_ID)), FIND_TIMEOUT)
        assertNotNull("Map provider dropdown should be visible", dropdown)
        dropdown!!.click()
        Thread.sleep(SHORT_WAIT)

        // The exposed-dropdown popup lists "Yandex" and "Google".
        val googleItem = device.wait(Until.findObject(By.text("Google")), FIND_TIMEOUT)
        assertNotNull("'Google' item should appear in the dropdown popup", googleItem)
        googleItem!!.click()
        Thread.sleep(SHORT_WAIT)

        // Let the SETTINGS_CHANGED broadcast / pref write settle, then hold the
        // rendered state so a screen recording captures it.
        Thread.sleep(2500)

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            "Map provider pref should now be google",
            AppConfig.MAP_PROVIDER_GOOGLE,
            AppConfig.getMapProvider(ctx)
        )
    }
}
