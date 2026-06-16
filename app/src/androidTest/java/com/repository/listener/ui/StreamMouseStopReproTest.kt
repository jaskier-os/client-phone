package com.repository.listener.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reproduction driver for the "glasses become unreachable after stopping the video stream
 * while glasses mouse is active" bug. Drives the real UI: start stream, select Glasses input
 * mode, then stop the stream. Run with BT logging armed and inspect logs afterward.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class StreamMouseStopReproTest {

    companion object {
        private const val PKG = "com.repository.listener"
        private const val UI_TIMEOUT = 15000L
        private const val STREAM_WAIT = 20000L
    }

    private lateinit var device: UiDevice

    private fun log(msg: String) = println("REPRO_MARK ${System.currentTimeMillis()} $msg")

    @Test
    fun stopStreamWhileGlassesMouseActive() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Launch app (retry for the known maps-SDK launch crash).
        for (attempt in 1..3) {
            device.executeShellCommand(
                "am start -n $PKG/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
            )
            if (device.wait(Until.hasObject(By.res(PKG, "tabLayout")), 20000)) break
            Thread.sleep(3000)
        }

        // Desktop tab.
        device.findObject(By.desc("Desktop"))?.click()
        device.wait(Until.hasObject(By.res(PKG, "streamContainer")), 30000)
        Thread.sleep(2000)

        // Start stream.
        log("CLICK_STREAM_START")
        device.wait(Until.findObject(By.res(PKG, "btnStream")), UI_TIMEOUT)?.click()
        // STREAMING enters immersive (hides tabLayout). Wait for that as the signal instead of
        // btnKeyboard, which can race with decoder/surface readiness.
        device.wait(Until.gone(By.res(PKG, "tabLayout")), STREAM_WAIT)
        Thread.sleep(6000) // let the stream settle and the footer be tappable
        log("STREAM_SETTLED")

        // Tap the screen to reveal the control overlay (auto-hidden during streaming).
        device.click(device.displayWidth / 2, device.displayHeight - 40)
        Thread.sleep(1000)

        // Open the input-mode footer and select Glasses.
        log("OPEN_FOOTER")
        device.wait(Until.findObject(By.res(PKG, "btnMouse")), UI_TIMEOUT)?.click()
        Thread.sleep(1000)
        val glasses = device.wait(Until.findObject(By.res(PKG, "btnModeGlasses")), UI_TIMEOUT)
        if (glasses == null) {
            println("REPRO_NOTE btnModeGlasses not found; selecting via ADB start_mouse instead")
            device.executeShellCommand(
                "am broadcast -n $PKG/.adb.AdbCommandReceiver -a $PKG.ADB_COMMAND " +
                    "--es type start_mouse --es command_id repro_sm " +
                    "--es params '{\"sensitivity_x\":1800,\"sensitivity_y\":4200,\"stream_mode\":true}'"
            )
        } else {
            log("SELECT_GLASSES enabled=${glasses.isEnabled}")
            glasses.click()
        }
        Thread.sleep(4000) // let glasses mouse run

        // Stop the stream (the action that triggers the bug).
        log("CLICK_STREAM_STOP")
        // Reveal overlay again then hit stop.
        device.click(device.displayWidth / 2, device.displayHeight - 40)
        Thread.sleep(500)
        device.findObject(By.res(PKG, "btnStream"))?.click()
        Thread.sleep(10000) // observe BT teardown + reachability

        log("DONE")
    }
}
