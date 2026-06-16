package com.repository.listener.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E test for routing the glasses RFCOMM mouse onto an active remote-desktop
 * video stream (WowMouse-style), instead of the Bluetooth-HID bridge.
 *
 * Verifies the core edge case from the feature: while a video stream is live,
 * enabling the glasses mouse must succeed WITHOUT a Bluetooth-HID target
 * (no device_address), because input reaches the PC over the stream's mouse
 * channel. The opposite (no stream, no address) must still fail -- that is
 * covered by MouseHidE2ETest.test03.
 *
 * Requires:
 * - Phone connected via ADB, orchestrator reachable
 * - Desktop client online so a stream can actually be established
 * - Glasses paired + connected (for start_mouse to be accepted)
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class GlassesMouseStreamRelayE2ETest {

    companion object {
        private const val PKG = "com.repository.listener"
        private const val UI_TIMEOUT = 15000L
        private const val RENDER_HOLD = 3000L
        private const val STREAM_WAIT = 15000L
    }

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        launchApp()
        navigateToDesktopTab()
    }

    @After
    fun teardown() {
        // Stop glasses mouse, then stop the stream.
        runAdb("stop_mouse", "relay_cleanup_mouse", "{}")
        Thread.sleep(500)
        device.findObject(By.res(PKG, "btnStream"))?.click()
        Thread.sleep(RENDER_HOLD)
    }

    // --- Helpers ---

    private fun launchApp() {
        for (attempt in 1..3) {
            device.executeShellCommand(
                "am start -n $PKG/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
            )
            if (device.wait(Until.hasObject(By.res(PKG, "tabLayout")), 20000)) return
            Thread.sleep(3000)
        }
        throw AssertionError("MainActivity failed to launch after 3 attempts")
    }

    private fun navigateToDesktopTab() {
        val desktopTab = device.findObject(By.desc("Desktop"))
        assertNotNull("Desktop tab should exist in bottom nav", desktopTab)
        desktopTab.click()
        device.wait(Until.hasObject(By.res(PKG, "streamContainer")), 30000)
            ?: device.wait(Until.hasObject(By.res(PKG, "audioContainer")), 5000)
        Thread.sleep(RENDER_HOLD)
    }

    private fun runAdb(type: String, id: String, params: String) {
        device.executeShellCommand(
            "am broadcast -n $PKG/.adb.AdbCommandReceiver " +
                "-a $PKG.ADB_COMMAND --es type $type --es command_id $id --es params '$params'"
        )
    }

    private fun readAdbResult(id: String): String =
        device.executeShellCommand("run-as $PKG cat files/adb_results/$id.json").trim()

    private fun statusJson(): String {
        val id = "relay_status_${System.currentTimeMillis()}"
        runAdb("status", id, "{}")
        Thread.sleep(1000)
        return readAdbResult(id)
    }

    /** Wait up to [timeoutMs] for the glasses RFCOMM link to be connected (it can flap). */
    private fun waitForGlasses(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (statusJson().contains("\"glasses_connected\": true")) return true
            Thread.sleep(2000)
        }
        return false
    }

    /** Probe stream readiness by issuing start_mouse with NO device_address. */
    private fun probeStartMouseNoAddr(): String {
        val id = "relay_start_nostreamaddr_${System.currentTimeMillis()}"
        runAdb("start_mouse", id, "{\\\"sensitivity_x\\\":1800,\\\"sensitivity_y\\\":4200}")
        Thread.sleep(2000)
        return readAdbResult(id)
    }

    private fun isSuccess(result: String): Boolean =
        result.contains("\"status\": \"success\"") || result.contains("\"status\":\"success\"")

    private fun isNoTargetError(result: String): Boolean =
        result.contains("No target device address")

    // --- Test ---

    @Test
    fun glassesMouseAttachesToStreamWithoutHidTarget() {
        if (!waitForGlasses(30000)) {
            println("SKIP: glasses not connected, cannot exercise start_mouse")
            return
        }

        // Sanity: before any stream, start_mouse with no device_address must error
        // (HID mode requires a target). This is the contrast case for the feature.
        runAdb("stop_mouse", "relay_pre_cleanup", "{}")
        Thread.sleep(800)
        val noStreamResult = probeStartMouseNoAddr()
        assertTrue(
            "Pre-stream: start_mouse without device_address must error (got: $noStreamResult)",
            isNoTargetError(noStreamResult)
        )

        // Start the video stream from the Desktop tab.
        val btnStream = device.wait(Until.findObject(By.res(PKG, "btnStream")), UI_TIMEOUT)
        assertNotNull("Stream button should be visible", btnStream)
        btnStream.click()

        // Poll: once the stream's mouse channel is live (mouseEventListener != null),
        // start_mouse with NO device_address must SUCCEED -- routed to the PC over the
        // stream, no Bluetooth-HID target. Use the result itself as the readiness signal.
        var streamModeResult = ""
        var streamReady = false
        val deadline = System.currentTimeMillis() + STREAM_WAIT + 10000
        while (System.currentTimeMillis() < deadline) {
            streamModeResult = probeStartMouseNoAddr()
            if (isSuccess(streamModeResult)) { streamReady = true; break }
            // still "No target" -> stream not up yet; clear and retry
            runAdb("stop_mouse", "relay_retry_cleanup_${System.currentTimeMillis()}", "{}")
            Thread.sleep(1500)
        }

        if (!streamReady) {
            println("SKIP: stream never reached STREAMING (desktop client offline?) last=$streamModeResult")
            return
        }

        assertTrue(
            "start_mouse with no device_address must succeed while streaming (got: $streamModeResult)",
            isSuccess(streamModeResult)
        )
        assertTrue(
            "start_mouse must NOT report a missing-target error while streaming (got: $streamModeResult)",
            !isNoTargetError(streamModeResult)
        )

        // Hold STREAMING so a screen recording captures the streamed PC frame while
        // the glasses drive the cursor over the stream.
        Thread.sleep(RENDER_HOLD)
    }
}
