package com.repository.listener.ui.rc

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File

/**
 * Saves screenshots during instrumented tests via the shell-level `screencap` command.
 *
 * UiDevice.takeScreenshot(File) writes using the app UID, which SELinux blocks from
 * writing to /data/local/tmp even with 777 permissions. Using `screencap -p` via
 * executeShellCommand runs as the shell user and bypasses this restriction.
 *
 * Screenshots are saved to /data/local/tmp/rc-screenshots/ and can be pulled with:
 *   adb pull /data/local/tmp/rc-screenshots/ ./screenshots/
 */
object ScreenshotHelper {
    private const val TAG = "ScreenshotHelper"
    private var counter = 0
    private const val DIR = "/data/local/tmp/rc-screenshots"
    private var dirReady = false

    fun take(name: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        if (!dirReady) {
            device.executeShellCommand("mkdir -p $DIR")
            dirReady = true
        }
        val path = "$DIR/${String.format("%03d", counter++)}_$name.png"
        val output = device.executeShellCommand("screencap -p $path").trim()
        if (output.isEmpty()) {
            Log.d(TAG, "Screenshot saved: $path")
        } else {
            Log.e(TAG, "screencap failed for $path: $output")
        }
    }

    fun resetCounter() { counter = 0 }
}
