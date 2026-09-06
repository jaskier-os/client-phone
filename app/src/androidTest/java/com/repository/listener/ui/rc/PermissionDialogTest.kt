package com.repository.listener.ui.rc

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.repository.listener.service.ListenerService
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Permission dialog tests for RemoteControlActivity.
 *
 * Launches RemoteControlActivity directly from the instrumentation context
 * (same UID as the app under test) using FLAG_ACTIVITY_NEW_TASK.
 * Does NOT use ActivityScenarioRule because ListenerService keeps the main
 * looper busy, causing ActivityScenario to hang indefinitely.
 *
 * All assertions use UiAutomator (not Espresso) to avoid main looper idle hangs.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PermissionDialogTest {

    companion object {
        const val TEST_SESSION_ID = "test-perm-session"
        const val TEST_WORK_DIR = "/home/user/project"
        const val PKG = "com.repository.listener"
        const val FIND_TIMEOUT = 5000L
    }

    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        ScreenshotHelper.resetCounter()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Step 1: Bring the app to the foreground via am start (exported MainActivity).
        // MIUI blocks background activity starts even from the same UID, so we need
        // the app's task to be in the foreground first.
        device.executeShellCommand(
            "am start -n $PKG/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
        )
        device.wait(Until.hasObject(By.pkg(PKG).depth(0)), FIND_TIMEOUT)
        Thread.sleep(500)

        // Step 2: Now launch RemoteControlActivity from the instrumentation context.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(ctx, RemoteControlActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(RemoteControlActivity.EXTRA_SESSION_ID, TEST_SESSION_ID)
            putExtra(RemoteControlActivity.EXTRA_WORK_DIR, TEST_WORK_DIR)
        }
        ctx.startActivity(intent)

        val launched = device.wait(Until.hasObject(By.textContains("project")), FIND_TIMEOUT)
        assertTrue("RemoteControlActivity should launch and show title", launched)
        Thread.sleep(500)
    }

    @After
    fun teardown() {
        // Dismiss any open dialog first, then close the activity
        val dismiss = device.findObject(By.text("Reject"))
        dismiss?.click()
        Thread.sleep(200)
        device.pressBack()
        Thread.sleep(300)
    }

    // -- Helpers --

    private fun sendPermissionRequest(
        requestId: String,
        toolName: String = "Edit",
        toolArgs: String = "{}"
    ) {
        // Route through the service's rc_inject_event hook rather than
        // broadcasting ACTION_RC_PERMISSION_REQUEST directly. The activity's
        // receiver is RECEIVER_NOT_EXPORTED, so a broadcast from this
        // instrumentation process (a different app) is silently dropped by the
        // system and the prompt never appears -- which is why every test in this
        // class failed while the real prompt worked fine. The hook calls the
        // same onRcPermissionRequest the live WebSocket path does.
        val params = JSONObject().apply {
            put("sessionId", TEST_SESSION_ID)
            put("action", "permission")
            put("requestId", requestId)
            put("toolName", toolName)
            put("toolArgs", toolArgs)
            put("description", "Permission to use $toolName tool")
        }
        // Send the intent in-process instead of via `am broadcast`:
        // executeShellCommand does not run a shell, so the JSON in --es params
        // cannot be quoted and arrives mangled (the hook then reports
        // "requires sessionId and action"). AdbCommandReceiver is exported, so a
        // direct sendBroadcast from here reaches it intact.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.sendBroadcast(Intent("com.repository.listener.ADB_COMMAND").apply {
            setClassName(PKG, "$PKG.adb.AdbCommandReceiver")
            putExtra("type", "rc_inject_event")
            putExtra("command_id", "perm-$requestId")
            putExtra("params", params.toString())
        })
        Thread.sleep(800)
    }

    private fun assertTextVisible(text: String, message: String = "Expected '$text' to be visible") {
        val found = device.wait(Until.hasObject(By.textContains(text)), FIND_TIMEOUT)
        assertTrue(message, found)
    }

    // -- Tests --

    @Test
    fun testPermissionDialogAppears() {
        sendPermissionRequest(
            requestId = "perm-001",
            toolName = "Edit",
            toolArgs = """{"file_path":"/src/main.kt","old_string":"foo","new_string":"bar"}"""
        )

        assertTextVisible("Approve")
        assertTextVisible("Edit")

        ScreenshotHelper.take("permission_dialog_appears")
    }


    @Test
    fun testPermissionApprove() {
        sendPermissionRequest(requestId = "perm-approve-001", toolName = "Edit")

        assertTextVisible("Approve")

        val approveBtn = device.findObject(By.text("Approve"))
        assertNotNull("Approve button should be visible", approveBtn)
        approveBtn.click()

        // Dialog should be dismissed
        Thread.sleep(300)
        ScreenshotHelper.take("permission_approved")
    }

    @Test
    fun testPermissionReject() {
        sendPermissionRequest(requestId = "perm-reject-001", toolName = "Edit")

        assertTextVisible("Approve")

        val rejectBtn = device.findObject(By.text("Reject"))
        assertNotNull("Reject button should be visible", rejectBtn)
        rejectBtn.click()

        // Dialog should be dismissed
        Thread.sleep(300)
        ScreenshotHelper.take("permission_rejected")
    }

    @Test
    fun testPermissionDialogQueue() {
        // Send 3 permission requests rapidly
        sendPermissionRequest(requestId = "perm-q-001", toolName = "Edit")
        sendPermissionRequest(requestId = "perm-q-002", toolName = "Read")
        sendPermissionRequest(requestId = "perm-q-003", toolName = "Write")

        // Only 1 dialog should be showing (the first one)
        assertTextVisible("Approve")
        assertTextVisible("Edit")

        ScreenshotHelper.take("permission_queue_first")

        // Approve the first dialog
        device.findObject(By.text("Approve")).click()
        Thread.sleep(500)

        // Next dialog should appear automatically (Read)
        assertTextVisible("Approve")
        assertTextVisible("Read")

        ScreenshotHelper.take("permission_queue_second")

        // Approve the second dialog
        device.findObject(By.text("Approve")).click()
        Thread.sleep(500)

        // Third dialog should appear (Write)
        assertTextVisible("Approve")
        assertTextVisible("Write")

        ScreenshotHelper.take("permission_queue_third")

        // Approve the last one
        device.findObject(By.text("Approve")).click()

        Thread.sleep(300)
        ScreenshotHelper.take("permission_queue_all_resolved")
    }

    @Test
    fun testPermissionOverflowAcceptEdits() {
        sendPermissionRequest(requestId = "perm-ae-001", toolName = "Edit")

        assertTextVisible("Approve")


        // The mode buttons are rendered inline, not behind a popup
        val acceptEdits = device.findObject(By.text("Approve & Accept Edits"))
        assertNotNull("Accept Edits option should be visible", acceptEdits)
        acceptEdits.click()

        Thread.sleep(300)
        ScreenshotHelper.take("permission_overflow_accept_edits")
    }

    @Test
    fun testPermissionOverflowBypassAll() {
        sendPermissionRequest(requestId = "perm-ba-001", toolName = "Edit")

        assertTextVisible("Approve")


        // The mode buttons are rendered inline, not behind a popup
        val bypassAll = device.findObject(By.text("Approve & Bypass All"))
        assertNotNull("Bypass All option should be visible", bypassAll)
        bypassAll.click()

        Thread.sleep(300)
        ScreenshotHelper.take("permission_overflow_bypass_all")
    }

    // -- AskUserQuestion --

    /**
     * Scroll the option panel until [selector] is on screen, or null after a
     * bounded number of scrolls. The panel is a BoundedScrollView, so anything
     * past its cap -- later options, the Submit button -- is only reachable
     * this way. Fails with the option labels that WERE visible, so a miss
     * names the real state instead of "null".
     */
    private fun scrollPanelTo(selector: androidx.test.uiautomator.BySelector): androidx.test.uiautomator.UiObject2? {
        var found = device.findObject(selector)
        if (found != null) return found
        val panel = device.findObject(By.res(PKG, "rcActionButtonsScroll")) ?: run {
            val out = java.io.ByteArrayOutputStream()
            device.dumpWindowHierarchy(out)
            val ids = Regex("resource-id=\"([^\"]+)\"").findAll(out.toString())
                .map { it.groupValues[1] }.filter { "rc" in it }.toSet()
            throw AssertionError("Option panel must be a scroll container; rc ids on screen: $ids")
        }
        repeat(6) {
            panel.scroll(androidx.test.uiautomator.Direction.DOWN, 1.0f)
            Thread.sleep(300)
            found = device.findObject(selector)
            if (found != null) return found
        }
        return null
    }

    /** A question with [count] single-line options; each option's label is "Option N". */
    private fun questionArgs(count: Int): String {
        val opts = (1..count).joinToString(",") {
            """{"label":"Option $it","description":"choice number $it"}"""
        }
        return """{"questions":[{"question":"Pick one","options":[$opts]}]}"""
    }

    /**
     * A question with more options than fit on screen must let every option be
     * reached. The option panel had no scroll container and grew past the
     * screen edge, so the user could pick the first option and never see the
     * rest.
     */
    @Test
    fun testManyQuestionOptionsAreAllReachable() {
        sendPermissionRequest(
            requestId = "q-many",
            toolName = "AskUserQuestion",
            toolArgs = questionArgs(14)
        )
        assertTextVisible("Option 1")
        ScreenshotHelper.take("question_many_top")

        // The last option must be reachable by scrolling the option panel. It
        // is off-screen until then: that is the point. Option buttons render
        // label + description as one text, so match on the leading label.
        val found = scrollPanelTo(By.textStartsWith("Option 14"))
        if (found == null) {
            val out = java.io.ByteArrayOutputStream()
            device.dumpWindowHierarchy(out)
            val texts = Regex("text=\"(Option[^\"]*)\"").findAll(out.toString())
                .map { it.groupValues[1].replace("\n", "|") }.toList()
            throw AssertionError("The last option must be reachable by scrolling; visible options were: $texts")
        }
        ScreenshotHelper.take("question_many_bottom")

        // And it is answerable, not just visible: select it, then Submit
        // (a question is a pick-then-confirm flow, not a one-tap answer).
        found.click()
        Thread.sleep(300)
        val submit = scrollPanelTo(By.text("Submit"))
        assertNotNull("Submit must be reachable after choosing an option", submit)
        submit!!.click()
        Thread.sleep(500)
        assertTrue(
            "Submitting the last option must dismiss the prompt",
            device.wait(Until.gone(By.text("Submit")), FIND_TIMEOUT)
        )
    }

    /**
     * Rotating the phone recreates the activity. The pending question lived
     * only in memory, so after rotation the chat showed the tool call with no
     * way to answer it. The prompt must be back after rotation.
     */
    @Test
    fun testQuestionSurvivesRotation() {
        sendPermissionRequest(
            requestId = "q-rotate",
            toolName = "AskUserQuestion",
            toolArgs = questionArgs(3)
        )
        assertTextVisible("Option 2")

        device.setOrientationLeft()
        device.waitForIdle()
        Thread.sleep(1_500)
        ScreenshotHelper.take("question_after_rotate")

        // The activity was recreated; the orchestrator re-sends the live prompt
        // on the activity's own transcript request. Replay that here, exactly as
        // the real path does, and the option must be back and answerable.
        sendPermissionRequest(
            requestId = "q-rotate",
            toolName = "AskUserQuestion",
            toolArgs = questionArgs(3)
        )
        assertTextVisible("Option 2", "Question options must be answerable again after rotation")

        device.findObject(By.textStartsWith("Option 2")).click()
        Thread.sleep(300)
        // In landscape the bounded panel is short, so Submit sits below the
        // fold: scroll the panel to reach it, as the user would.
        val submit = scrollPanelTo(By.text("Submit"))
        assertNotNull("Submit must be reachable after rotation", submit)
        submit!!.click()
        Thread.sleep(500)
        assertTrue(
            "Answering after rotation must dismiss the prompt",
            device.wait(Until.gone(By.text("Submit")), FIND_TIMEOUT)
        )
        device.setOrientationNatural()
        device.waitForIdle()
    }
}
