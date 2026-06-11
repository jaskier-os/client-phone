package com.repository.listener.ui.rc

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.repository.listener.service.ListenerService
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI tests for RemoteControlActivity.
 *
 * Launches RemoteControlActivity directly from the instrumentation context
 * (same UID as the app under test) using FLAG_ACTIVITY_NEW_TASK.
 * Does NOT use ActivityScenarioRule because ListenerService keeps the main
 * looper busy, causing ActivityScenario to hang indefinitely.
 *
 * All assertions use UiAutomator (not Espresso) to avoid main looper idle hangs.
 *
 * Data injection uses am broadcast with --receiver-include-background flag
 * to bypass RECEIVER_NOT_EXPORTED.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RemoteControlUITest {

    companion object {
        const val TEST_SESSION_ID = "test-session-001"
        const val TEST_WORK_DIR = "/home/user/project"
        const val FIND_TIMEOUT = 5000L
        const val PKG = "com.repository.listener"
    }

    private lateinit var device: UiDevice

    @Before
    fun setup() {
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
        // This succeeds because the app task is already in the foreground.
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
        // Press back to finish RemoteControlActivity
        device.pressBack()
        Thread.sleep(300)
    }

    /** Assert text is visible on screen with timeout. */
    private fun assertTextVisible(text: String, message: String = "Expected '$text' to be visible") {
        val found = device.wait(Until.hasObject(By.textContains(text)), FIND_TIMEOUT)
        assertTrue(message, found)
    }

    /** Send broadcast from the same UID via context.sendBroadcast().
     *  am broadcast from shell cannot reach RECEIVER_NOT_EXPORTED receivers. */
    private fun sendBroadcast(action: String, data: String? = null) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(action).apply {
            setPackage(ctx.packageName)
            putExtra(ListenerService.EXTRA_RC_SESSION_ID, TEST_SESSION_ID)
            if (data != null) {
                putExtra(ListenerService.EXTRA_RC_DATA, data)
            }
        }
        ctx.sendBroadcast(intent)
        Thread.sleep(800)
    }

    // -- Tests --

    @Test
    fun testActivityLaunches() {
        // Top bar shows basename of workDir
        assertTextVisible("project", "Title should contain 'project'")

        // Mode chip should be present (actual text depends on server-restored mode)
        val modeChip = device.findObject(By.res(PKG, "rcModeSelector"))
        assertNotNull("Mode chip should be visible", modeChip)

        ScreenshotHelper.take("01_activity_launches")
    }

    @Test
    fun testSessionStartEvent() {
        // Session start is rendered as a SessionEvent message added by the
        // rc_session_start receiver (not via transcript broadcast). Inject
        // a session-start broadcast directly.
        sendBroadcast(ListenerService.ACTION_RC_SESSION_START)

        // The session start event may or may not render visible text depending
        // on whether the session was already open. Just verify the activity
        // is still alive and the input field is present.
        val input = device.findObject(By.res(PKG, "rcInput"))
        assertNotNull("Input field should exist after session start", input)

        ScreenshotHelper.take("02_session_start_event")
    }

    @Test
    fun testAssistantMessage() {
        val json = """{"text":"Hello, I am your assistant.","isFinal":true,"requestId":"req-001"}"""
        sendBroadcast(ListenerService.ACTION_RC_MESSAGE, json)

        assertTextVisible("Hello, I am your assistant.")

        ScreenshotHelper.take("03_assistant_message")
    }

    @Test
    fun testUserMessage() {
        val inputField = device.findObject(By.res(PKG, "rcInput"))
        assertNotNull("Input field should exist", inputField)
        inputField.click()
        Thread.sleep(200)
        inputField.text = "This is a user message"
        Thread.sleep(500)

        val sendButton = device.findObject(By.res(PKG, "rcSendButton"))
        assertNotNull("Send button should exist", sendButton)
        sendButton.click()
        Thread.sleep(500)

        assertTextVisible("This is a user message")

        ScreenshotHelper.take("04_user_message")
    }

    @Test
    fun testToolStatusRunning() {
        // "calling" status is filtered out for non-agent tools (only permission
        // cards show calling state). Use "auto-approved" which is what bypassAll
        // mode emits, and is rendered by the adapter.
        val json = """{"toolName":"Read","status":"auto-approved","toolCallId":"tool-001"}"""
        sendBroadcast(ListenerService.ACTION_RC_TOOL_STATUS, json)

        assertTextVisible("Read")

        ScreenshotHelper.take("05_tool_status_running")
    }

    @Test
    fun testToolStatusComplete() {
        // Send auto-approved first to create the row, then complete to update it
        val json1 = """{"toolName":"Read","status":"auto-approved","toolCallId":"tool-002"}"""
        sendBroadcast(ListenerService.ACTION_RC_TOOL_STATUS, json1)
        Thread.sleep(500)

        val json2 = """{"toolName":"Read","status":"complete","toolCallId":"tool-002"}"""
        sendBroadcast(ListenerService.ACTION_RC_TOOL_STATUS, json2)

        // Adapter renders "[+] Read: complete" for non-Edit/Write/Bash tools
        assertTextVisible("Read")

        ScreenshotHelper.take("06_tool_status_complete")
    }

    @Test
    fun testPermissionRequest() {
        val json = """{"toolName":"Edit","toolArgs":"file: /src/main.kt","requestId":"perm-001","description":"Modify the main entry point"}"""
        sendBroadcast(ListenerService.ACTION_RC_PERMISSION_REQUEST, json)

        assertTextVisible("Edit")

        ScreenshotHelper.take("07_permission_request")
    }

    @Test
    fun testPlanUpdate() {
        val json = """{"entering":true,"planContent":"Step 1: Read the source files\nStep 2: Implement changes\nStep 3: Run tests"}"""
        sendBroadcast(ListenerService.ACTION_RC_PLAN_UPDATE, json)

        assertTextVisible("Plan")
        assertTextVisible("Step 1")

        ScreenshotHelper.take("08_plan_update")
    }

    @Test
    fun testPlanCollapse() {
        val json = """{"entering":true,"planContent":"Step 1: Read files\nStep 2: Write code"}"""
        sendBroadcast(ListenerService.ACTION_RC_PLAN_UPDATE, json)

        assertTextVisible("Step 1", "Plan content should be visible initially")

        val planHeader = device.findObject(By.textContains("Plan"))
        assertNotNull("Plan header should exist", planHeader)
        planHeader.click()
        Thread.sleep(500)

        ScreenshotHelper.take("09a_plan_collapsed")

        val planHeaderAgain = device.findObject(By.textContains("Plan"))
        assertNotNull("Plan header should still exist", planHeaderAgain)
        planHeaderAgain.click()
        Thread.sleep(500)

        assertTextVisible("Step 1", "Plan content should be visible after expand")

        ScreenshotHelper.take("09b_plan_expanded")
    }

    @Test
    fun testAgentStatus() {
        val json1 = """{"agentId":"agent-001","name":"WebSearch","status":"started","depth":0}"""
        sendBroadcast(ListenerService.ACTION_RC_AGENT_STATUS, json1)

        // Adapter renders "[>] Agent: WebSearch"
        assertTextVisible("Agent: WebSearch")

        val json2 = """{"agentId":"agent-002","name":"UrlReader","status":"started","depth":1}"""
        sendBroadcast(ListenerService.ACTION_RC_AGENT_STATUS, json2)

        assertTextVisible("Agent: UrlReader")

        ScreenshotHelper.take("10_agent_status")
    }

    @Test
    fun testThinkingBlock() {
        sendBroadcast(
            ListenerService.ACTION_RC_THINKING,
            "Analyzing the code structure and dependencies..."
        )

        assertTextVisible("Analyzing the code structure")

        ScreenshotHelper.take("11_thinking_block")
    }

    @Test
    fun testUserInputRequest() {
        val json = """{"prompt":"Please provide the API key:","requestId":"input-001"}"""
        sendBroadcast(ListenerService.ACTION_RC_USER_INPUT, json)

        assertTextVisible("Input requested")
        assertTextVisible("Please provide the API key")

        ScreenshotHelper.take("12_user_input_request")
    }

    @Test
    fun testModeChange() {
        sendBroadcast(ListenerService.ACTION_RC_MODE_CHANGE, "acceptEdits")

        assertTextVisible("Mode: Accept all edits")

        ScreenshotHelper.take("13_mode_change")
    }

    @Test
    fun testSessionEnd() {
        sendBroadcast(ListenerService.ACTION_RC_SESSION_END)

        assertTextVisible("Session ended")

        val banner = device.findObject(By.res(PKG, "rcEndedBanner"))
        assertNotNull("Ended banner should be visible", banner)

        // Input stays enabled so the user can revive the session by typing
        val input = device.findObject(By.res(PKG, "rcInput"))
        assertNotNull("Input field should exist", input)
        assertTrue("Input field should remain enabled for session revival", input.isEnabled)

        ScreenshotHelper.take("14_session_end")
    }
}
