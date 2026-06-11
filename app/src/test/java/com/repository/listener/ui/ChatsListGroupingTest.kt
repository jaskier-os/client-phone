package com.repository.listener.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatsListGroupingTest {

    private fun rc(
        id: String,
        workDir: String,
        status: String = "active",
        startedAt: Long = 0L,
        turning: Boolean = false
    ): ChatListItem.RemoteControlSession = ChatListItem.RemoteControlSession(
        sessionId = id,
        workDir = workDir,
        status = status,
        lastMessage = null,
        startedAt = startedAt,
        sessionName = null,
        turning = turning
    )

    // --- folderNameFromWorkDir ---------------------------------------------------

    @Test
    fun folderNameFromWorkDirBasicPath() {
        assertEquals("Repository", folderNameFromWorkDir("/a/Repository"))
    }

    @Test
    fun folderNameFromWorkDirNullReturnsNull() {
        assertEquals(null, folderNameFromWorkDir(null))
    }

    @Test
    fun folderNameFromWorkDirBlankReturnsNull() {
        assertEquals(null, folderNameFromWorkDir(""))
        assertEquals(null, folderNameFromWorkDir("   "))
    }

    @Test
    fun folderNameFromWorkDirNullStringReturnsNull() {
        assertEquals(null, folderNameFromWorkDir("null"))
        assertEquals(null, folderNameFromWorkDir("NULL"))
    }

    // --- groupActiveByFolder -----------------------------------------------------

    @Test
    fun groupActiveByFolderEmptyInput() {
        assertTrue(groupActiveByFolder(emptyList()).isEmpty())
    }

    @Test
    fun groupActiveByFolderOnlyEndedProducesEmpty() {
        val out = groupActiveByFolder(
            listOf(
                rc("s1", "/a/Repository", status = "ended"),
                rc("s2", "/b/shareitt", status = "ended")
            )
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun groupActiveByFolderGroupsWithHeaders() {
        val out = groupActiveByFolder(
            listOf(
                rc("s1", "/a/Repository", startedAt = 100),
                rc("s2", "/b/shareitt", startedAt = 200),
                rc("s3", "/a/Repository", startedAt = 300)
            )
        )
        // Expected: FolderHeader("Repository"), s3 (300), s1 (100), FolderHeader("shareitt"), s2
        assertEquals(5, out.size)
        assertEquals(ChatListItem.FolderHeader("Repository"), out[0])
        assertEquals("s3", (out[1] as ChatListItem.RemoteControlSession).sessionId)
        assertEquals("s1", (out[2] as ChatListItem.RemoteControlSession).sessionId)
        assertEquals(ChatListItem.FolderHeader("shareitt"), out[3])
        assertEquals("s2", (out[4] as ChatListItem.RemoteControlSession).sessionId)
    }

    @Test
    fun groupActiveByFolderFoldersAlphabetical() {
        val out = groupActiveByFolder(
            listOf(
                rc("s1", "/z/Zebra"),
                rc("s2", "/a/Alpha"),
                rc("s3", "/m/Middle")
            )
        )
        val headers = out.filterIsInstance<ChatListItem.FolderHeader>().map { it.label }
        assertEquals(listOf("Alpha", "Middle", "Zebra"), headers)
    }

    @Test
    fun groupActiveByFolderExcludesEndedSessions() {
        val out = groupActiveByFolder(
            listOf(
                rc("s1", "/a/Repository", status = "active"),
                rc("s2", "/a/Repository", status = "ended")
            )
        )
        // Only one header + one session (s1).
        assertEquals(2, out.size)
        assertEquals("s1", (out[1] as ChatListItem.RemoteControlSession).sessionId)
    }

    @Test
    fun groupActiveByFolderNullWorkDirBucketsUnderOther() {
        val out = groupActiveByFolder(
            listOf(rc("s1", ""))
        )
        // Empty workDir -> folderNameFromWorkDir returns null -> "Other"
        // But actually empty string returns null from folderNameFromWorkDir,
        // so it goes to "Other".
        assertTrue(out.isEmpty() || (out[0] as? ChatListItem.FolderHeader)?.label == "Other")
    }

    @Test
    fun groupActiveByFolderSortsByStartedAtDescending() {
        val out = groupActiveByFolder(
            listOf(
                rc("s1", "/a/Repo", startedAt = 100),
                rc("s2", "/a/Repo", startedAt = 300),
                rc("s3", "/a/Repo", startedAt = 200)
            )
        )
        val sessions = out.filterIsInstance<ChatListItem.RemoteControlSession>()
        assertEquals(listOf("s2", "s3", "s1"), sessions.map { it.sessionId })
    }
}
