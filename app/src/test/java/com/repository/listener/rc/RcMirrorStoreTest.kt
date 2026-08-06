package com.repository.listener.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RcMirrorStoreTest {

    @Test
    fun appendUserMintsStrictlyIncreasingSeqStartingAtZero() {
        val store = RcMirrorStore()
        val first = store.appendUser("s1", "hello")
        val second = store.appendUser("s1", "again")
        assertEquals(0L, first.seq)
        assertEquals(1L, second.seq)
        assertEquals("user", first.role)
        assertEquals("hello", first.text)
    }

    @Test
    fun cumulativeAssistantTextCommitsOneRowWithTheLastText() {
        val store = RcMirrorStore()
        store.noteAssistantText("s1", "He")
        store.noteAssistantText("s1", "Hello")
        store.noteAssistantText("s1", "Hello world")
        val rows = store.commitTurn("s1")
        assertEquals(1, rows.size)
        assertEquals("assistant", rows[0].role)
        assertEquals("Hello world", rows[0].text)
    }

    @Test
    fun consecutiveToolsCollapseIntoOneRowWithDistinctNamesAndTotalCount() {
        val store = RcMirrorStore()
        store.noteTool("s1", "Read")
        store.noteTool("s1", "Grep")
        store.noteTool("s1", "Read")
        val rows = store.commitTurn("s1")
        assertEquals(1, rows.size)
        assertEquals("tools", rows[0].role)
        assertEquals("Read, Grep", rows[0].text)
        assertEquals(3, rows[0].toolCount)
    }

    @Test
    fun toolsRowIsCommittedBeforeTheAssistantRow() {
        val store = RcMirrorStore()
        store.noteTool("s1", "Read")
        store.noteAssistantText("s1", "done")
        val rows = store.commitTurn("s1")
        assertEquals(listOf("tools", "assistant"), rows.map { it.role })
        assertTrue(rows[0].seq < rows[1].seq)
    }

    @Test
    fun emptyTurnCommitsNoRowAndMintsNoSeq() {
        val store = RcMirrorStore()
        store.appendUser("s1", "hello")
        val rows = store.commitTurn("s1")
        assertEquals(emptyList<RcRow>(), rows)
        assertEquals(0L, store.lastSeq("s1"))
    }

    @Test
    fun rowsAreCappedAtFortyDroppingOldest() {
        val store = RcMirrorStore()
        repeat(45) { store.appendUser("s1", "m$it") }
        val (rows, moreAbove) = store.tail("s1", n = 100)
        assertEquals(40, rows.size)
        assertEquals("m5", rows.first().text)
        assertEquals("m44", rows.last().text)
        assertTrue(moreAbove)
    }

    @Test
    fun tailReturnsTheNewestNRowsAndReportsMoreAbove() {
        val store = RcMirrorStore()
        repeat(40) { store.appendUser("s1", "m$it") }
        val (rows, moreAbove) = store.tail("s1", n = 20)
        assertEquals(20, rows.size)
        assertEquals("m20", rows.first().text)
        assertEquals("m39", rows.last().text)
        assertTrue(moreAbove)

        val small = RcMirrorStore()
        repeat(5) { small.appendUser("s2", "m$it") }
        val (smallRows, smallMore) = small.tail("s2", n = 20)
        assertEquals(5, smallRows.size)
        assertFalse(smallMore)
    }

    @Test
    fun textLongerThanThreeHundredCharsIsTruncatedWithAnEllipsis() {
        val store = RcMirrorStore()
        val row = store.appendUser("s1", "x".repeat(400))
        assertEquals(300, row.text.length)
        assertTrue(row.text.endsWith("..."))
        assertEquals("x".repeat(297) + "...", row.text)
    }

    @Test
    fun leastRecentlyAccessedSessionIsEvictedWithoutAnyCallToClear() {
        val store = RcMirrorStore()
        repeat(8) { store.appendUser("s$it", "hello") }
        // Re-touch s0 so s1 becomes the least recently accessed entry.
        store.lastSeq("s0")
        store.appendUser("s8", "hello")

        assertEquals(-1L, store.lastSeq("s1"))
        for (id in listOf("s0", "s2", "s3", "s4", "s5", "s6", "s7", "s8")) {
            assertEquals("session $id must survive", 0L, store.lastSeq(id))
        }
    }

    @Test
    fun clearRemovesOnlyThatSessionsRows() {
        val store = RcMirrorStore()
        store.appendUser("s1", "a")
        store.appendUser("s2", "b")
        store.clear("s1")
        assertEquals(-1L, store.lastSeq("s1"))
        assertEquals(emptyList<RcRow>(), store.tail("s1").first)
        assertEquals(0L, store.lastSeq("s2"))
    }

    @Test
    fun appendPromptProducesAPromptRowCarryingItsOptions() {
        val store = RcMirrorStore()
        val row = store.appendPrompt("s1", "Allow Bash?", listOf("Yes", "No", "Always"))
        assertEquals("prompt", row.role)
        assertEquals("Allow Bash?", row.text)
        assertEquals(listOf("Yes", "No", "Always"), row.options)
    }

    @Test
    fun lastSeqReturnsTheHighestMintedSeqAndMinusOneForAnUnknownSession() {
        val store = RcMirrorStore()
        assertEquals(-1L, store.lastSeq("nope"))
        store.appendUser("s1", "a")
        store.appendUser("s1", "b")
        assertEquals(1L, store.lastSeq("s1"))
    }
}
