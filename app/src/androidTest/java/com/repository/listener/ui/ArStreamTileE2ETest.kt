package com.repository.listener.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the AR Stream tile and its mute toggles.
 *
 * Navigation is programmatic / by selector only -- never coordinate taps (a screenshot-derived
 * coordinate silently hits the wrong row the moment a tile is inserted above it).
 *
 * Each asserted state is held for [HOLD_MS] so an external screen recording captures it:
 * assertions alone prove the code path ran, not that anything rendered.
 *
 * Requires the glasses to be connected -- the tile is gated on a BLE reachability ping, and the
 * session itself needs the glasses to answer start_ar_stream.
 */
@RunWith(AndroidJUnit4::class)
class ArStreamTileE2ETest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun arStreamTileOpensSessionAndTogglesBothMics() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // Launch the app fresh.
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ctx.startActivity(launch)
        device.wait(Until.hasObject(By.pkg(ctx.packageName).depth(0)), LAUNCH_TIMEOUT_MS)

        // Reach the AR Stream tile by its label, not by position.
        val tile = device.wait(Until.findObject(By.text(TILE_LABEL)), FIND_TIMEOUT_MS)
        assertTrue("AR Stream tile not found in the glasses Apps tab", tile != null)
        tile!!.click()

        // The mic controls are part of the Activity's chrome, so their presence proves it opened
        // regardless of whether the stream itself has connected yet.
        val opened = device.wait(Until.hasObject(By.desc(PHONE_MIC_ON)), SESSION_TIMEOUT_MS)
        assertTrue("ArStreamActivity did not open (no mic controls on screen)", opened)
        Thread.sleep(HOLD_MS)

        // Phone mic: on -> muted.
        val phoneMic = device.findObject(By.desc(PHONE_MIC_ON))
        assertTrue("phone mic button not in the enabled state", phoneMic != null)
        phoneMic!!.click()
        assertTrue(
            "phone mic did not switch to the muted icon",
            device.wait(Until.hasObject(By.desc(PHONE_MIC_OFF)), FIND_TIMEOUT_MS)
        )
        Thread.sleep(HOLD_MS)

        // Glasses mic: on -> muted.
        val glassesMic = device.findObject(By.desc(GLASSES_MIC_ON))
        assertTrue("glasses mic button not in the enabled state", glassesMic != null)
        glassesMic!!.click()
        assertTrue(
            "glasses mic did not switch to the muted icon",
            device.wait(Until.hasObject(By.desc(GLASSES_MIC_OFF)), FIND_TIMEOUT_MS)
        )
        Thread.sleep(HOLD_MS)

        // Unmute both again, so the recording shows the full round trip.
        device.findObject(By.desc(PHONE_MIC_OFF))?.click()
        device.findObject(By.desc(GLASSES_MIC_OFF))?.click()
        assertTrue(
            "phone mic did not return to the enabled icon",
            device.wait(Until.hasObject(By.desc(PHONE_MIC_ON)), FIND_TIMEOUT_MS)
        )
        Thread.sleep(HOLD_MS)

        // Leaving the screen must tear the session down (Activity lifecycle owns it).
        device.pressBack()
    }

    private companion object {
        const val TILE_LABEL = "AR Stream"
        const val PHONE_MIC_ON = "Phone mic on"
        const val PHONE_MIC_OFF = "Phone mic muted"
        const val GLASSES_MIC_ON = "Glasses mic on"
        const val GLASSES_MIC_OFF = "Glasses mic muted"

        const val LAUNCH_TIMEOUT_MS = 10_000L
        const val FIND_TIMEOUT_MS = 10_000L
        const val SESSION_TIMEOUT_MS = 60_000L

        /** Long enough for a screen recording to show each state. */
        const val HOLD_MS = 2_500L
    }
}
