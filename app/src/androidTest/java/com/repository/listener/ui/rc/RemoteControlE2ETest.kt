package com.repository.listener.ui.rc

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.repository.listener.service.ListenerService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * End-to-end tests for the Remote Control activity.
 *
 * Launches RemoteControlActivity directly from the instrumentation context
 * (same UID as the app under test) using FLAG_ACTIVITY_NEW_TASK.
 * Does NOT use ActivityScenarioRule because ListenerService keeps the main
 * looper busy, causing ActivityScenario to hang indefinitely.
 *
 * All assertions use UiAutomator (not Espresso) to avoid main looper idle hangs.
 * Data injection uses am broadcast via shell to bypass RECEIVER_NOT_EXPORTED.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RemoteControlE2ETest {

    companion object {
        private const val TEST_SESSION_ID = "e2e-session-001"
        private const val TEST_WORK_DIR = "/home/user/project"
        private const val ORCHESTRATOR_URL = "https://127.0.0.1:8443"
        private const val FIND_TIMEOUT = 5000L
        private const val PKG = "com.repository.listener"
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

    // -- Helpers --

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

    // ---------------------------------------------------------------
    // 1. Full conversation flow
    // ---------------------------------------------------------------

    @Test
    fun testFullConversationFlow() {
        // Step 1: Session start via transcript
        val transcriptJson = """[{"type":"text","role":"system","text":"Session started"}]"""
        sendBroadcast(ListenerService.ACTION_RC_TRANSCRIPT, transcriptJson)
        assertTextVisible("Session started")
        ScreenshotHelper.take("e2e_conv_01_session_start")

        // Step 2: Assistant greeting
        val greetingJson = """{"text":"Hello, how can I help?","isFinal":true,"requestId":"req-conv-001"}"""
        sendBroadcast(ListenerService.ACTION_RC_MESSAGE, greetingJson)
        assertTextVisible("Hello, how can I help?")
        ScreenshotHelper.take("e2e_conv_02_assistant_greeting")

        // Step 3: User types a message
        val inputField = device.findObject(By.res(PKG, "rcInput"))
        assertNotNull("Input field should exist", inputField)
        inputField.click()
        Thread.sleep(200)
        inputField.text = "Read the file main.kt"
        Thread.sleep(500)

        val sendButton = device.findObject(By.res(PKG, "rcSendButton"))
        assertNotNull("Send button should exist", sendButton)
        sendButton.click()
        Thread.sleep(500)

        assertTextVisible("Read the file main.kt")
        ScreenshotHelper.take("e2e_conv_03_user_message")

        // Step 4: Tool status -- Read calling
        val toolCallingJson = """{"toolName":"Read","status":"calling"}"""
        sendBroadcast(ListenerService.ACTION_RC_TOOL_STATUS, toolCallingJson)
        assertTextVisible("Read: calling")
        ScreenshotHelper.take("e2e_conv_04_tool_calling")

        // Step 5: Permission request for Read tool
        val permJson = """{"toolName":"Read","toolArgs":"file: /src/main.kt","requestId":"perm-conv-001","description":"Read file contents"}"""
        sendBroadcast(ListenerService.ACTION_RC_PERMISSION_REQUEST, permJson)
        Thread.sleep(500)
        ScreenshotHelper.take("e2e_conv_05_permission_dialog")

        // Step 6: Approve the permission
        val approveButton = device.findObject(By.text("Approve"))
        assertNotNull("Approve button should be visible", approveButton)
        approveButton.click()
        Thread.sleep(500)
        ScreenshotHelper.take("e2e_conv_06_permission_approved")

        // Step 7: Tool status -- Read complete
        val toolCompleteJson = """{"toolName":"Read","status":"complete"}"""
        sendBroadcast(ListenerService.ACTION_RC_TOOL_STATUS, toolCompleteJson)
        assertTextVisible("Read: complete")
        ScreenshotHelper.take("e2e_conv_07_tool_complete")

        // Step 8: Assistant response with file content
        val responseJson = """{"text":"Here is the file content...","isFinal":true,"requestId":"req-conv-002"}"""
        sendBroadcast(ListenerService.ACTION_RC_MESSAGE, responseJson)
        assertTextVisible("Here is the file content...")
        ScreenshotHelper.take("e2e_conv_08_assistant_response")

        // Step 9: Session end
        sendBroadcast(ListenerService.ACTION_RC_SESSION_END)
        assertTextVisible("Session ended")

        val banner = device.findObject(By.res(PKG, "rcEndedBanner"))
        assertNotNull("Ended banner should be visible", banner)

        val input = device.findObject(By.res(PKG, "rcInput"))
        assertNotNull("Input field should exist", input)
        assertTrue("Input field should be disabled", !input.isEnabled)
        ScreenshotHelper.take("e2e_conv_09_session_ended")

        // Final verification: key messages all visible
        assertTextVisible("Hello, how can I help?")
        assertTextVisible("Read the file main.kt")
        assertTextVisible("Here is the file content...")
    }

    // ---------------------------------------------------------------
    // 2. Plan mode flow
    // ---------------------------------------------------------------

    @Test
    fun testPlanModeFlow() {
        // Step 1: Plan update entering
        val planJson = """{"entering":true,"planContent":"Step 1: Read source files\nStep 2: Implement changes\nStep 3: Run tests"}"""
        sendBroadcast(ListenerService.ACTION_RC_PLAN_UPDATE, planJson)
        assertTextVisible("Plan")
        assertTextVisible("Step 1")
        ScreenshotHelper.take("e2e_plan_01_entering")

        // Step 2: Thinking block
        sendBroadcast(
            ListenerService.ACTION_RC_THINKING,
            "Analyzing project structure and dependencies..."
        )
        assertTextVisible("Analyzing project structure")
        ScreenshotHelper.take("e2e_plan_02_thinking")

        // Step 3: Agent status depth=0 (Explore agent spawned)
        val agent0Json = """{"agentId":"agent-explore-001","name":"Explore","status":"started","depth":0}"""
        sendBroadcast(ListenerService.ACTION_RC_AGENT_STATUS, agent0Json)
        assertTextVisible("Explore: started")
        ScreenshotHelper.take("e2e_plan_03_agent_depth0")

        // Step 4: Sub-agent at depth=1
        val agent1Json = """{"agentId":"agent-read-002","name":"FileReader","status":"started","depth":1}"""
        sendBroadcast(ListenerService.ACTION_RC_AGENT_STATUS, agent1Json)
        assertTextVisible("FileReader: started")
        ScreenshotHelper.take("e2e_plan_04_agent_depth1")

        // Step 5: Agent depth=0 complete
        val agentDoneJson = """{"agentId":"agent-explore-001","name":"Explore","status":"complete","depth":0}"""
        sendBroadcast(ListenerService.ACTION_RC_AGENT_STATUS, agentDoneJson)
        assertTextVisible("Explore: complete")
        ScreenshotHelper.take("e2e_plan_05_agent_complete")

        // Step 6: Plan update exiting
        val planExitJson = """{"entering":false}"""
        sendBroadcast(ListenerService.ACTION_RC_PLAN_UPDATE, planExitJson)
        ScreenshotHelper.take("e2e_plan_06_exiting")
    }

    // ---------------------------------------------------------------
    // 3. Streaming text flow
    // ---------------------------------------------------------------

    @Test
    fun testStreamingTextFlow() {
        val requestId = "req-stream-001"

        // Streaming chunk 1
        val chunk1 = """{"text":"Hello","isFinal":false,"requestId":"$requestId"}"""
        sendBroadcast(ListenerService.ACTION_RC_MESSAGE, chunk1)
        ScreenshotHelper.take("e2e_stream_01_chunk1")

        // Streaming chunk 2
        val chunk2 = """{"text":"Hello, I am","isFinal":false,"requestId":"$requestId"}"""
        sendBroadcast(ListenerService.ACTION_RC_MESSAGE, chunk2)
        ScreenshotHelper.take("e2e_stream_02_chunk2")

        // Streaming chunk 3
        val chunk3 = """{"text":"Hello, I am Claude","isFinal":false,"requestId":"$requestId"}"""
        sendBroadcast(ListenerService.ACTION_RC_MESSAGE, chunk3)
        ScreenshotHelper.take("e2e_stream_03_chunk3")

        // Final chunk
        val finalChunk = """{"text":"Hello, I am Claude. How can I help?","isFinal":true,"requestId":"$requestId"}"""
        sendBroadcast(ListenerService.ACTION_RC_MESSAGE, finalChunk)

        // Verify final text
        assertTextVisible("Hello, I am Claude. How can I help?")
        ScreenshotHelper.take("e2e_stream_04_final")
    }

    // ---------------------------------------------------------------
    // 4. Permission mode escalation
    // ---------------------------------------------------------------

    @Test
    fun testPermissionModeEscalation() {
        // Verify initial mode is "Ask"
        assertTextVisible("Ask", "Mode chip should show 'Ask'")
        ScreenshotHelper.take("e2e_mode_01_initial")

        // Send permission request
        val permJson = """{"toolName":"Edit","toolArgs":"file: /src/config.kt","requestId":"perm-mode-001","description":"Edit configuration file"}"""
        sendBroadcast(ListenerService.ACTION_RC_PERMISSION_REQUEST, permJson)
        Thread.sleep(500)
        ScreenshotHelper.take("e2e_mode_02_permission_dialog")

        // Use overflow menu: "Approve & Accept Edits"
        val moreOptions = device.findObject(By.text("More options..."))
        assertNotNull("More options link should be visible", moreOptions)
        moreOptions.click()
        Thread.sleep(500)
        ScreenshotHelper.take("e2e_mode_03_overflow_menu")

        val acceptEditsOption = device.findObject(By.text("Approve & Accept Edits"))
        assertNotNull("Accept Edits option should be visible", acceptEditsOption)
        acceptEditsOption.click()
        Thread.sleep(500)
        ScreenshotHelper.take("e2e_mode_04_after_escalation")

        // Simulate the orchestrator confirming the mode change
        sendBroadcast(ListenerService.ACTION_RC_MODE_CHANGE, "acceptEdits")

        // Verify mode chip updated
        assertTextVisible("Accept", "Mode chip should show 'Accept'")

        // Verify mode change message in the list
        assertTextVisible("Mode: Accept all edits")
        ScreenshotHelper.take("e2e_mode_05_mode_updated")
    }

    // ---------------------------------------------------------------
    // 5. User input flow
    // ---------------------------------------------------------------

    @Test
    fun testUserInputFlow() {
        // Send user input request
        val inputJson = """{"prompt":"What directory should I work in?","requestId":"input-e2e-001"}"""
        sendBroadcast(ListenerService.ACTION_RC_USER_INPUT, inputJson)

        // Verify input request card appears
        assertTextVisible("Input requested")
        assertTextVisible("What directory should I work in?")
        ScreenshotHelper.take("e2e_input_01_request")

        // Type user response
        val inputField = device.findObject(By.res(PKG, "rcInput"))
        assertNotNull("Input field should exist", inputField)
        inputField.click()
        Thread.sleep(200)
        inputField.text = "/home/user/project"
        Thread.sleep(500)

        val sendButton = device.findObject(By.res(PKG, "rcSendButton"))
        assertNotNull("Send button should exist", sendButton)
        sendButton.click()
        Thread.sleep(500)

        // Verify user response message appears
        assertTextVisible("/home/user/project")
        ScreenshotHelper.take("e2e_input_02_response_sent")

        // Verify input bar returns to normal (still enabled)
        val inputAfter = device.findObject(By.res(PKG, "rcInput"))
        assertNotNull("Input field should exist after response", inputAfter)
        assertTrue("Input field should be enabled", inputAfter.isEnabled)
        ScreenshotHelper.take("e2e_input_03_bar_reset")
    }

    // ---------------------------------------------------------------
    // 6. Reconnect transcript catch-up
    // ---------------------------------------------------------------

    @Test
    fun testReconnectTranscript() {
        val transcript = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("role", "system")
                put("text", "Session started")
            })
            put(JSONObject().apply {
                put("type", "text")
                put("role", "assistant")
                put("text", "Hello, how can I help?")
            })
            put(JSONObject().apply {
                put("type", "tool")
                put("toolName", "Read")
                put("status", "complete")
            })
            put(JSONObject().apply {
                put("type", "text")
                put("role", "user")
                put("text", "Thanks, now edit config.kt")
            })
            put(JSONObject().apply {
                put("type", "text")
                put("role", "assistant")
                put("text", "Done, I have updated the config.")
            })
        }

        sendBroadcast(ListenerService.ACTION_RC_TRANSCRIPT, transcript.toString())
        ScreenshotHelper.take("e2e_transcript_01_loaded")

        // Verify all 5 messages appear
        assertTextVisible("Session started")
        assertTextVisible("Hello, how can I help?")
        assertTextVisible("Read: complete")
        assertTextVisible("Thanks, now edit config.kt")
        assertTextVisible("Done, I have updated the config.")
        ScreenshotHelper.take("e2e_transcript_02_verified")
    }

    // ---------------------------------------------------------------
    // 7. Smoke test -- real orchestrator (skipped in CI)
    // ---------------------------------------------------------------

    /**
     * Smoke test that attempts a real HTTP connection to the remote orchestrator.
     * Requires a running orchestrator at ORCHESTRATOR_URL (127.0.0.1:8443).
     * Uses Assume so the test is skipped (not failed) if the orchestrator is unreachable.
     */
    @Test
    fun testSmokeRealOrchestrator() {
        val apiKey = com.repository.listener.config.AppConfig.getApiKey(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        var createdSessionId: String? = null

        try {
            // Step 1: Health check
            val healthUrl = URL("$ORCHESTRATOR_URL/api/v1/health")
            val conn = healthUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"

            val responseCode = conn.responseCode
            conn.disconnect()

            Assume.assumeTrue(
                "Orchestrator health check returned $responseCode (expected 200)",
                responseCode == 200
            )

            ScreenshotHelper.take("e2e_smoke_01_orchestrator_healthy")

            // Step 2: Create RC session via correct endpoint
            val sessionUrl = URL("$ORCHESTRATOR_URL/api/v1/remote-control/sessions")
            val sessionConn = sessionUrl.openConnection() as HttpURLConnection
            sessionConn.connectTimeout = 5000
            sessionConn.readTimeout = 10000
            sessionConn.requestMethod = "POST"
            sessionConn.setRequestProperty("Content-Type", "application/json")
            sessionConn.setRequestProperty("Authorization", "Bearer $apiKey")
            sessionConn.doOutput = true

            val body = JSONObject().apply {
                put("workDir", "/test")
            }
            sessionConn.outputStream.bufferedWriter().use { it.write(body.toString()) }

            val sessionResponseCode = sessionConn.responseCode
            assertTrue(
                "RC session creation returned $sessionResponseCode (expected 200 or 201)",
                sessionResponseCode == 200 || sessionResponseCode == 201
            )

            val responseBody = sessionConn.inputStream.bufferedReader().use { it.readText() }
            sessionConn.disconnect()
            val responseJson = JSONObject(responseBody)
            val sessionId = responseJson.optString("sessionId", "")
            assertTrue("Session ID should be non-empty", sessionId.isNotEmpty())
            createdSessionId = sessionId

            ScreenshotHelper.take("e2e_smoke_02_session_created")

            Thread.sleep(2000)
            ScreenshotHelper.take("e2e_smoke_03_after_wait")
        } catch (e: Exception) {
            Assume.assumeTrue(
                "Orchestrator not reachable: ${e.message}",
                false
            )
        } finally {
            // Step 3: Clean up -- delete the session if it was created
            if (createdSessionId != null) {
                try {
                    val deleteUrl = URL("$ORCHESTRATOR_URL/api/v1/remote-control/sessions/$createdSessionId")
                    val deleteConn = deleteUrl.openConnection() as HttpURLConnection
                    deleteConn.connectTimeout = 5000
                    deleteConn.readTimeout = 5000
                    deleteConn.requestMethod = "DELETE"
                    deleteConn.setRequestProperty("Authorization", "Bearer $apiKey")
                    deleteConn.responseCode // execute the request
                    deleteConn.disconnect()
                } catch (_: Exception) {
                    // Best-effort cleanup, ignore errors
                }
            }
        }
    }
}
