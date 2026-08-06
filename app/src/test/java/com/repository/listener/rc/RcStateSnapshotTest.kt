package com.repository.listener.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class RcStateSnapshotTest {

    private fun session(
        id: String,
        name: String = "session $id",
        folder: String = "/work/$id",
        ended: Boolean = false,
        turning: Boolean = false,
        debouncePending: Boolean = false,
        unread: Boolean = false,
        lastSeq: Long = 0L,
        lastActivityMs: Long = 0L
    ) = RcSessionState(id, name, folder, ended, turning, debouncePending, unread, lastSeq, lastActivityMs)

    @Test
    fun wsReflectsTheArgument() {
        val sessions = listOf(session("a"))
        assertTrue(JSONObject(RcStateSnapshot.build(true, sessions)).getBoolean("ws"))
        assertTrue(!JSONObject(RcStateSnapshot.build(false, sessions)).getBoolean("ws"))
    }

    @Test
    fun theSameInputBuildsByteIdenticalJson() {
        val sessions = listOf(
            session("a", turning = true, unread = true, lastSeq = 4L, lastActivityMs = 20L),
            session("b", ended = true, lastActivityMs = 10L)
        )
        assertEquals(RcStateSnapshot.build(true, sessions), RcStateSnapshot.build(true, sessions))
    }

    @Test
    fun turningIsTrueWhileATurnFinishDebounceIsStillPending() {
        // Raw turning flips false on every inter-tool isFinal; the debounce is the real turn edge.
        assertTrue(turningOf(session("a", turning = true, debouncePending = false)))
        assertTrue(turningOf(session("a", turning = false, debouncePending = true)))
        assertTrue(turningOf(session("a", turning = true, debouncePending = true)))
        assertTrue(!turningOf(session("a", turning = false, debouncePending = false)))
    }

    @Test
    fun anEndedSessionCarriesNeitherSpinnerNorUnread() {
        val json = JSONObject(
            RcStateSnapshot.build(
                true,
                listOf(session("a", ended = true, turning = true, debouncePending = true, unread = true))
            )
        )
        val s = json.getJSONArray("s").getJSONObject(0)
        assertEquals("ended", s.getString("st"))
        assertEquals(false, s.getBoolean("t"))
        assertEquals(false, s.getBoolean("u"))
    }

    @Test
    fun anOpenSessionSerialisesEveryField() {
        val json = JSONObject(
            RcStateSnapshot.build(
                true,
                listOf(session("a", name = "fix the bug", folder = "/w/p", turning = true,
                    unread = true, lastSeq = 12L))
            )
        )
        val s = json.getJSONArray("s").getJSONObject(0)
        assertEquals("a", s.getString("id"))
        assertEquals("fix the bug", s.getString("n"))
        assertEquals("/w/p", s.getString("w"))
        assertEquals("open", s.getString("st"))
        assertEquals(true, s.getBoolean("t"))
        assertEquals(true, s.getBoolean("u"))
        assertEquals(12L, s.getLong("q"))
    }

    @Test
    fun theNameIsTruncatedToFortyChars() {
        val json = JSONObject(RcStateSnapshot.build(true, listOf(session("a", name = "z".repeat(80)))))
        assertEquals(40, json.getJSONArray("s").getJSONObject(0).getString("n").length)
    }

    @Test
    fun moreThanEightSessionsTruncatesToTheEightMostRecentlyActive() {
        val sessions = (0 until 12).map { session("s$it", lastActivityMs = it.toLong()) }
        val json = JSONObject(RcStateSnapshot.build(true, sessions))
        val arr = json.getJSONArray("s")
        assertEquals(8, arr.length())
        assertEquals("s11", arr.getJSONObject(0).getString("id"))
        assertEquals("s4", arr.getJSONObject(7).getString("id"))
    }

    @Test
    fun theFrameStaysUnderTheSingleChunkCap() {
        val sessions = (0 until 12).map {
            session("session-id-$it", name = "n".repeat(200), folder = "/very/long/folder/path/$it",
                lastActivityMs = it.toLong())
        }
        val json = RcStateSnapshot.build(true, sessions)
        assertTrue("frame was ${json.length} chars", json.length < RcStateSnapshot.MAX_FRAME_CHARS)
    }

    @Test
    fun keyOrderIsDeterministic() {
        val json = RcStateSnapshot.build(true, listOf(session("a", unread = true, lastSeq = 3L)))
        assertEquals(
            """{"ws":true,"s":[{"id":"a","n":"session a","w":"/work/a","st":"open","t":false,"u":true,"q":3}]}""",
            json
        )
    }

    @Test
    fun anEmptySessionListStillProducesAValidFrame() {
        assertEquals("""{"ws":false,"s":[]}""", RcStateSnapshot.build(false, emptyList()))
    }

    private fun turningOf(s: RcSessionState): Boolean =
        JSONObject(RcStateSnapshot.build(true, listOf(s))).getJSONArray("s")
            .getJSONObject(0).getBoolean("t")
}
