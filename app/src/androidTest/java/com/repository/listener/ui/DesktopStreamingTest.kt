package com.repository.listener.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E test for Desktop streaming UI flow.
 * Navigates to Desktop tab, tests audio relay connect/disconnect + video stream.
 * Designed to be screen-recorded -- each state is held visible for 3s.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DesktopStreamingTest {

    companion object {
        private const val PKG = "com.repository.listener"
        private const val UI_TIMEOUT = 15000L
        private const val RENDER_HOLD = 3000L
    }

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        launchApp()
        navigateToDesktopTab()
    }

    private fun launchApp() {
        // Launch the app -- may crash due to maps SDK on POCO, retry up to 3 times
        for (attempt in 1..3) {
            device.executeShellCommand(
                "am start -n $PKG/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
            )
            val appeared = device.wait(Until.hasObject(By.res(PKG, "tabLayout")), 20000)
            if (appeared) return
            // App may have crashed, wait and retry
            Thread.sleep(3000)
        }
        fail("MainActivity failed to launch after 3 attempts")
    }

    private fun navigateToDesktopTab() {
        val desktopTab = device.findObject(By.desc("Desktop"))
        assertNotNull("Desktop tab should exist in bottom nav", desktopTab)
        desktopTab.click()
        // Wait for audioContainer (visible in IDLE state when orchestrator connected)
        val connected = device.wait(Until.hasObject(By.res(PKG, "audioContainer")), 30000)
        if (!connected) {
            // Maybe orchestrator not connected -- check if at least btnStream is there (DISCONNECTED state)
            val hasStream = device.wait(Until.hasObject(By.res(PKG, "streamContainer")), 5000)
            if (!hasStream) fail("Desktop fragment did not load (no audioContainer or streamContainer)")
        }
        Thread.sleep(RENDER_HOLD)
    }

    @Test
    fun fullStreamingDemo() {
        // 1. Show idle state
        Thread.sleep(RENDER_HOLD)

        // 2. Tap audio button -- shows spinner replacing icon
        val btnAudio = device.wait(Until.findObject(By.res(PKG, "btnAudio")), UI_TIMEOUT)
        assertNotNull("Audio button should be visible (orchestrator connected?)", btnAudio)
        btnAudio.click()
        Thread.sleep(RENDER_HOLD) // Show spinner in place of audio icon

        // 3. Wait for WebRTC connection
        Thread.sleep(8000)

        // 4. Tap audio button to stop (icon reappears after connect)
        val btnAudioActive = device.wait(Until.findObject(By.res(PKG, "btnAudio")), UI_TIMEOUT)
        btnAudioActive?.click()
        Thread.sleep(RENDER_HOLD)

        // 5. Tap stream play button -- shows spinner replacing play icon
        val btnStream = device.wait(Until.findObject(By.res(PKG, "btnStream")), UI_TIMEOUT)
        assertNotNull("Stream button should be visible", btnStream)
        btnStream.click()
        Thread.sleep(RENDER_HOLD) // Show spinner in place of play button + "Connecting..."

        // 6. Wait for stream or timeout
        Thread.sleep(12000)

        // 7. Stop stream if connected
        device.findObject(By.res(PKG, "btnStream"))?.click()
        Thread.sleep(RENDER_HOLD)
    }
}
