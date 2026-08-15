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

    /**
     * A live video surface NEVER lets the window go idle.
     *
     * Every UiAutomator query first calls waitForIdle, and on this screen the TextureView posts a
     * frame ~30 times a second forever, so that wait always burns its full budget and the query
     * then runs against a stale/empty tree -- which is exactly the "button not in the enabled
     * state" failure this test used to hit after ~87s of healthy streaming. The controls were on
     * screen the whole time. Dropping the idle budget makes queries answer from the live tree.
     */
    @org.junit.Before
    fun disableIdleWaiting() {
        androidx.test.uiautomator.Configurator.getInstance().apply {
            waitForIdleTimeout = 0L
            waitForSelectorTimeout = 0L
        }
    }

    @Test
    fun arStreamTileOpensSessionAndTogglesBothMics() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // Launch the app fresh.
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ctx.startActivity(launch)
        device.wait(Until.hasObject(By.pkg(ctx.packageName).depth(0)), LAUNCH_TIMEOUT_MS)

        // The app restores whatever tab was last open, so navigate explicitly. Selected by
        // resource-id / content-desc, never by coordinates.
        val glassesTab = device.wait(Until.findObject(By.res(ctx.packageName, "tab_glasses")), FIND_TIMEOUT_MS)
        assertTrue("Glasses tab not found in the bottom tab bar", glassesTab != null)
        glassesTab!!.click()

        // Glasses > Apps is the first sub-tab; select it by label so a reordering cannot silently
        // point this test at the wrong sub-tab.
        device.wait(Until.findObject(By.desc(APPS_SUBTAB)), FIND_TIMEOUT_MS)?.click()

        // Reach the AR Stream tile by its label, not by position. It may need scrolling into view
        // since it sits below the fold in the 2-column grid.
        var tile = device.wait(Until.findObject(By.text(TILE_LABEL)), FIND_TIMEOUT_MS)
        if (tile == null) {
            val scrollable = device.findObject(By.scrollable(true))
            var tries = 0
            while (tile == null && scrollable != null && tries < MAX_SCROLLS) {
                scrollable.scroll(androidx.test.uiautomator.Direction.DOWN, 0.6f)
                tile = device.wait(Until.findObject(By.text(TILE_LABEL)), SHORT_TIMEOUT_MS)
                tries++
            }
        }
        assertTrue("AR Stream tile not found in the glasses Apps tab", tile != null)
        tile!!.click()

        // The mic controls are part of the Activity's chrome, so their presence proves it opened
        // regardless of whether the stream itself has connected yet.
        val opened = device.wait(Until.hasObject(By.desc(PHONE_MIC_ON)), SESSION_TIMEOUT_MS)
        assertTrue("ArStreamActivity did not open (no mic controls on screen)", opened)

        // Hold the live session open long enough for video and both audio directions to actually
        // flow. A brief open/close proves the Activity launches, not that the stream works.
        Thread.sleep(STREAM_SETTLE_MS)

        // Record the whole exchange below. Started BEFORE the mute toggles so the produced file
        // contains a stretch with both directions live and a stretch with each side muted --
        // which is what makes the two audio contributions separable in the saved MP4.
        val recordBtn = waitForDesc(RECORD_START, "record button")
        recordBtn.click()
        assertTrue(
            "record button did not switch to the recording state",
            device.wait(Until.hasObject(By.desc(RECORD_STOP)), FIND_TIMEOUT_MS)
        )
        assertTrue(
            "no 'Recording started' snackbar",
            device.wait(Until.hasObject(By.text(SNACK_REC_STARTED)), FIND_TIMEOUT_MS)
        )
        Thread.sleep(RECORD_BOTH_LIVE_MS)

        // Phone mic: on -> muted.
        //
        // findObject() is a single instantaneous query against the accessibility tree; after a
        // long sleep on a busy streaming screen it regularly returns null for a node that IS
        // there (the window is mid-update). Waiting for the selector -- as every other assertion
        // in this test already does -- is the fix, not a different selector.
        waitForDesc(PHONE_MIC_ON, "phone mic (enabled)").click()
        assertTrue(
            "phone mic did not switch to the muted icon",
            device.wait(Until.hasObject(By.desc(PHONE_MIC_OFF)), FIND_TIMEOUT_MS)
        )
        assertTrue(
            "no phone-mic-disabled snackbar",
            device.wait(Until.hasObject(By.text(SNACK_PHONE_OFF)), FIND_TIMEOUT_MS)
        )
        // Long enough that the glasses-only stretch is measurable in the saved audio track.
        Thread.sleep(RECORD_ONE_SIDE_MS)

        // Glasses mic: on -> muted.
        waitForDesc(GLASSES_MIC_ON, "glasses mic (enabled)").click()
        assertTrue(
            "glasses mic did not switch to the muted icon",
            device.wait(Until.hasObject(By.desc(GLASSES_MIC_OFF)), FIND_TIMEOUT_MS)
        )
        assertTrue(
            "no glasses-audio-disabled snackbar",
            device.wait(Until.hasObject(By.text(SNACK_GLASSES_OFF)), FIND_TIMEOUT_MS)
        )
        Thread.sleep(HOLD_MS)

        // Unmute both again, so the recording shows the full round trip.
        waitForDesc(PHONE_MIC_OFF, "phone mic (muted)").click()
        assertTrue(
            "phone mic did not return to the enabled icon",
            device.wait(Until.hasObject(By.desc(PHONE_MIC_ON)), FIND_TIMEOUT_MS)
        )
        waitForDesc(GLASSES_MIC_OFF, "glasses mic (muted)").click()
        assertTrue(
            "glasses mic did not return to the enabled icon",
            device.wait(Until.hasObject(By.desc(GLASSES_MIC_ON)), FIND_TIMEOUT_MS)
        )
        Thread.sleep(RECORD_BOTH_LIVE_MS)

        // Stop the recording and assert the save outcome, not just the button state: a file that
        // never reached MediaStore is the exact failure this test exists to catch.
        waitForDesc(RECORD_STOP, "record button (recording)").click()
        assertTrue(
            "record button did not return to the idle state",
            device.wait(Until.hasObject(By.desc(RECORD_START)), FIND_TIMEOUT_MS)
        )
        assertTrue(
            "no 'Recording saved to gallery' snackbar (recording did not reach the gallery)",
            device.wait(Until.hasObject(By.text(SNACK_REC_SAVED)), SAVE_TIMEOUT_MS)
        )
        Thread.sleep(HOLD_MS)

        // Leaving the screen must tear the session down (Activity lifecycle owns it).
        device.pressBack()
    }

    /**
     * Wait for a content-description and return the node, failing with a useful message.
     *
     * A bare findObject() is a one-shot query and returns null whenever the window happens to be
     * mid-update -- which on a live 30fps stream is often.
     */
    private fun waitForDesc(desc: String, what: String): androidx.test.uiautomator.UiObject2 {
        val o = device.wait(Until.findObject(By.desc(desc)), FIND_TIMEOUT_MS)
        assertTrue("$what not found (content-desc '$desc')", o != null)
        return o!!
    }

    private companion object {
        const val TILE_LABEL = "AR Stream"
        const val APPS_SUBTAB = "Apps"
        const val MAX_SCROLLS = 6
        const val SHORT_TIMEOUT_MS = 2_000L
        const val PHONE_MIC_ON = "Phone mic on"
        const val PHONE_MIC_OFF = "Phone mic muted"
        const val GLASSES_MIC_ON = "Glasses mic on"
        const val GLASSES_MIC_OFF = "Glasses mic muted"
        const val RECORD_START = "Start recording"
        const val RECORD_STOP = "Stop recording"

        // Must match res/values/strings.xml exactly.
        const val SNACK_REC_STARTED = "Recording started"
        const val SNACK_REC_SAVED = "Recording saved to gallery"
        const val SNACK_PHONE_OFF = "Phone outgoing audio disabled"
        const val SNACK_GLASSES_OFF = "Glasses incoming audio disabled"

        /** Both directions live -- the stretch that must show the loudest mixed audio. */
        const val RECORD_BOTH_LIVE_MS = 6_000L
        /** One direction muted, long enough to be measurable in the saved track. */
        const val RECORD_ONE_SIDE_MS = 5_000L
        /** Mux + MediaStore copy of a ~20s file. */
        const val SAVE_TIMEOUT_MS = 30_000L

        const val LAUNCH_TIMEOUT_MS = 10_000L
        const val FIND_TIMEOUT_MS = 10_000L
        const val SESSION_TIMEOUT_MS = 60_000L

        /** Long enough for a screen recording to show each state. */
        const val HOLD_MS = 2_500L

        /**
         * WiFi-Direct join + TCP connect + a long stretch of real streaming. Long deliberately:
         * the failure this guards against (a single bad write ending the whole session) only
         * shows up over time, so a short hold would pass against a broken build.
         */
        const val STREAM_SETTLE_MS = 80_000L
    }
}
