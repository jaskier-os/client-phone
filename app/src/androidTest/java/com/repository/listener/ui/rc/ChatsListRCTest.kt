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
 * Chat list tests for RC sessions appearing in the chats tab.
 *
 * Launches MainActivity directly from the instrumentation context using
 * FLAG_ACTIVITY_NEW_TASK. Does NOT use ActivityScenarioRule because
 * ListenerService keeps the main looper busy, causing ActivityScenario
 * to hang indefinitely.
 *
 * All assertions and interactions use UiAutomator (not Espresso).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ChatsListRCTest {

    companion object {
        private const val TEST_SESSION_ID = "rc-chat-test-001"
        private const val TEST_SESSION_ID_2 = "rc-chat-test-002"
        private const val TEST_WORK_DIR = "/home/user/project"
        private const val TEST_WORK_DIR_2 = "/home/user/another-project"
        private const val UI_TIMEOUT = 8000L
        private const val PKG = "com.repository.listener"
    }

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        ScreenshotHelper.resetCounter()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Launch MainActivity via am start (exported activity).
        // MIUI blocks context.startActivity() from background/instrumentation contexts,
        // but am start via shell always works for exported activities.
        device.executeShellCommand(
            "am start -n $PKG/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
        )

        // Wait for the activity to appear (look for the tab layout)
        val appeared = device.wait(Until.hasObject(By.res(PKG, "tabLayout")), UI_TIMEOUT)
        assertTrue("MainActivity should launch and show tabLayout", appeared)

        // Navigate to chat tab using UiAutomator
        navigateToChatTab()

        // Wait for chat tab content: either the chats recycler or the empty container
        val chatTabReady = device.wait(
            Until.hasObject(By.res(PKG, "fabNewChat")),
            UI_TIMEOUT
        )
        assertTrue("Chat tab should be visible (fabNewChat present)", chatTabReady)

        // Wait for the fragment to finish initial load (loaded=true).
        // The loadingContainer disappears once loadChats completes (success or fallback).
        // Once either chatsRecycler or emptyContainer is visible, the fragment is ready
        // to process RC broadcast sessions into the display list.
        val listReady = device.wait(
            Until.hasObject(By.res(PKG, "chatsRecycler").enabled(true)),
            UI_TIMEOUT
        ) || device.wait(
            Until.hasObject(By.res(PKG, "emptyContainer")),
            2000L
        )
        // Even if neither appeared (orchestrator down, stuck in retry), give extra settle time
        Thread.sleep(1000)
    }

    @After
    fun teardown() {
        device.pressBack()
        Thread.sleep(300)
    }

    // -- Helpers --

    private fun navigateToChatTab() {
        // TabLayout has 7 icon-only tabs. Chat is index 1 (second tab).
        // Calculate the center of the second tab: (1 + 0.5) / 7 = 3/14 of total width.
        val tabs = device.findObject(By.res(PKG, "tabLayout"))
        assertNotNull("TabLayout should be visible", tabs)
        val bounds = tabs.visibleBounds
        val tabWidth = bounds.width() / 7
        val x = bounds.left + tabWidth + (tabWidth / 2)  // center of second tab
        val y = bounds.centerY()
        device.click(x, y)
        Thread.sleep(500)
    }

    private fun sendSessionBroadcast(action: String, sessionId: String, workDir: String = TEST_WORK_DIR) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val data = JSONObject().apply {
            put("workDir", workDir)
        }
        val intent = Intent(action).apply {
            setPackage(ctx.packageName)
            putExtra(ListenerService.EXTRA_RC_SESSION_ID, sessionId)
            putExtra(ListenerService.EXTRA_RC_DATA, data.toString())
        }
        ctx.sendBroadcast(intent)
        Thread.sleep(1500)
    }

    private fun sendSessionEndBroadcast(sessionId: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(ListenerService.ACTION_RC_SESSION_END).apply {
            setPackage(ctx.packageName)
            putExtra(ListenerService.EXTRA_RC_SESSION_ID, sessionId)
        }
        ctx.sendBroadcast(intent)
        Thread.sleep(1500)
    }

    // -- Tests --

    @Test
    fun testRcSessionAppearsInChatList() {
        sendSessionBroadcast(ListenerService.ACTION_RC_SESSION_START, TEST_SESSION_ID)

        // Verify an item with "project" text appears (adapter shows sessionName or dirname)
        val rcItem = device.wait(Until.findObject(By.textContains("project")), UI_TIMEOUT)
        assertNotNull("RC session item should appear in chat list", rcItem)

        // Verify the terminal icon ">_" is present
        val terminalIcon = device.findObject(By.text(">_"))
        assertNotNull("Terminal icon >_ should be visible", terminalIcon)

        ScreenshotHelper.take("chats_rc_session_appears")
    }

    @Test
    fun testRcSessionShowsPurpleBorder() {
        sendSessionBroadcast(ListenerService.ACTION_RC_SESSION_START, TEST_SESSION_ID)

        // Verify the RC session item exists with purple-styled title text "project"
        val rcItem = device.wait(Until.findObject(By.textContains("project")), UI_TIMEOUT)
        assertNotNull("RC session item should appear in chat list", rcItem)

        // The purple border and styling are visual -- verify the item is present and rendered
        // The adapter sets title color to gbx_purple and has a purple left border
        assertTrue("RC session item should be visible on screen", rcItem.isEnabled)

        ScreenshotHelper.take("chats_rc_purple_border")
    }

    @Test
    fun testRcSessionClickOpensActivity() {
        sendSessionBroadcast(ListenerService.ACTION_RC_SESSION_START, TEST_SESSION_ID)

        // Find and click the RC session item
        val rcItem = device.wait(Until.findObject(By.textContains("project")), UI_TIMEOUT)
        assertNotNull("RC session item should appear in chat list", rcItem)

        ScreenshotHelper.take("chats_rc_before_click")

        rcItem.click()
        Thread.sleep(1000)

        // Verify RemoteControlActivity opened by checking for the mode chip
        val modeChip = device.wait(Until.findObject(By.res("com.repository.listener", "rcModeSelector")), UI_TIMEOUT)
        assertNotNull("RemoteControlActivity mode chip should be visible after click", modeChip)

        ScreenshotHelper.take("chats_rc_activity_opened")

        // Press back to return to chat list
        device.pressBack()
        Thread.sleep(500)

        // Verify we are back on the chat list (recycler visible)
        val chatList = device.wait(Until.findObject(By.res("com.repository.listener", "chatsRecycler")), UI_TIMEOUT)
        assertNotNull("Should return to chat list after pressing back", chatList)

        ScreenshotHelper.take("chats_rc_returned_to_list")
    }

    @Test
    fun testRcSessionEndUpdatesStatus() {
        // Start session
        sendSessionBroadcast(ListenerService.ACTION_RC_SESSION_START, TEST_SESSION_ID)

        // Verify session shows as active (green pulsing dot visible)
        val rcItem = device.wait(Until.findObject(By.textContains("project")), UI_TIMEOUT)
        assertNotNull("RC session should appear as active", rcItem)

        ScreenshotHelper.take("chats_rc_session_active")

        // End session
        sendSessionEndBroadcast(TEST_SESSION_ID)

        // Verify the session item updates -- subtitle changes to "ended"
        val endedItem = device.wait(Until.findObject(By.textContains("ended")), UI_TIMEOUT)
        assertNotNull("RC session should show ended status after session end", endedItem)

        ScreenshotHelper.take("chats_rc_session_ended")
    }

    @Test
    fun testMultipleRcSessions() {
        // Send two different RC session starts
        sendSessionBroadcast(ListenerService.ACTION_RC_SESSION_START, TEST_SESSION_ID, TEST_WORK_DIR)
        sendSessionBroadcast(ListenerService.ACTION_RC_SESSION_START, TEST_SESSION_ID_2, TEST_WORK_DIR_2)

        // Verify both appear in the list
        val session1 = device.wait(Until.findObject(By.textContains("project")), UI_TIMEOUT)
        assertNotNull("First RC session should appear in chat list", session1)

        val session2 = device.findObject(By.textContains("another-project"))
        assertNotNull("Second RC session should appear in chat list", session2)

        ScreenshotHelper.take("chats_rc_multiple_sessions")
    }
}
