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
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val data = JSONObject().apply {
            put("toolName", toolName)
            put("toolArgs", toolArgs)
            put("requestId", requestId)
            put("description", "Permission to use $toolName tool")
        }
        val intent = Intent(ListenerService.ACTION_RC_PERMISSION_REQUEST).apply {
            setPackage(ctx.packageName)
            putExtra(ListenerService.EXTRA_RC_SESSION_ID, TEST_SESSION_ID)
            putExtra(ListenerService.EXTRA_RC_DATA, data.toString())
        }
        ctx.sendBroadcast(intent)
        Thread.sleep(500)
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

        assertTextVisible("Permission Required")
        assertTextVisible("Edit")

        ScreenshotHelper.take("permission_dialog_appears")
    }

    @Test
    fun testPermissionDialogNonCancellable() {
        sendPermissionRequest(requestId = "perm-nc-001", toolName = "Edit")

        assertTextVisible("Permission Required")

        // Press back -- dialog should remain because setCancelable(false)
        device.pressBack()
        Thread.sleep(300)

        // Dialog should still be showing
        assertTextVisible("Permission Required")

        ScreenshotHelper.take("permission_dialog_non_cancellable")
    }

    @Test
    fun testPermissionApprove() {
        sendPermissionRequest(requestId = "perm-approve-001", toolName = "Edit")

        assertTextVisible("Permission Required")

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

        assertTextVisible("Permission Required")

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
        assertTextVisible("Permission Required")
        assertTextVisible("Edit")

        ScreenshotHelper.take("permission_queue_first")

        // Approve the first dialog
        device.findObject(By.text("Approve")).click()
        Thread.sleep(500)

        // Next dialog should appear automatically (Read)
        assertTextVisible("Permission Required")
        assertTextVisible("Read")

        ScreenshotHelper.take("permission_queue_second")

        // Approve the second dialog
        device.findObject(By.text("Approve")).click()
        Thread.sleep(500)

        // Third dialog should appear (Write)
        assertTextVisible("Permission Required")
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

        assertTextVisible("Permission Required")

        // Click the "More options..." text link inside the dialog
        val moreOptions = device.findObject(By.text("More options..."))
        assertNotNull("More options link should be visible", moreOptions)
        moreOptions.click()
        Thread.sleep(300)

        // Click the popup menu item
        val acceptEdits = device.findObject(By.text("Approve & Accept Edits"))
        assertNotNull("Accept Edits option should be visible", acceptEdits)
        acceptEdits.click()

        Thread.sleep(300)
        ScreenshotHelper.take("permission_overflow_accept_edits")
    }

    @Test
    fun testPermissionOverflowBypassAll() {
        sendPermissionRequest(requestId = "perm-ba-001", toolName = "Edit")

        assertTextVisible("Permission Required")

        // Click the "More options..." text link inside the dialog
        val moreOptions = device.findObject(By.text("More options..."))
        assertNotNull("More options link should be visible", moreOptions)
        moreOptions.click()
        Thread.sleep(300)

        // Click the popup menu item
        val bypassAll = device.findObject(By.text("Approve & Bypass All"))
        assertNotNull("Bypass All option should be visible", bypassAll)
        bypassAll.click()

        Thread.sleep(300)
        ScreenshotHelper.take("permission_overflow_bypass_all")
    }
}
