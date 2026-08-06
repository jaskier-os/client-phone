package com.repository.listener.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seeding is projected from the same transcript entry shape the phone RC UI already parses
 * (RemoteControlActivity.parseAndLoadTranscript): `{type:"rc_message", data:{text}}` entries plus
 * `rc_permission_request` / `rc_permission_resolved` / `user_message`.
 */
class RcProjectionTest {

    /** Two streaming partials of one turn, then a second, unrelated turn. */
    private val transcript = """
        [
          {"type":"user_message","data":{"text":"list the files"}},
          {"type":"rc_message","data":{"text":"Read"}},
          {"type":"rc_message","data":{"text":"Reading the **files** now"}},
          {"type":"user_message","data":{"text":"thanks"}},
          {"type":"rc_message","data":{"text":"Done, see `main.kt`"}}
        ]
    """.trimIndent()

    @Test
    fun aSupersededStreamingPartialIsDroppedAndOnlyTheFullTurnSurvives() {
        val store = RcMirrorStore()
        store.seedFromTranscript("s1", transcript)
        val rows = store.tail("s1", n = 100).first
        assertEquals(
            listOf("user", "assistant", "user", "assistant"),
            rows.map { it.role }
        )
        assertEquals("list the files", rows[0].text)
        assertEquals("Reading the files now", rows[1].text)
        assertEquals("thanks", rows[2].text)
        assertEquals("Done, see main.kt", rows[3].text)
    }

    @Test
    fun aLongerNonPrefixTurnDoesNotSupersedeAShorterEarlierTurn() {
        // A length-only comparison would drop the first turn entirely -- the "old transcript" bug.
        val store = RcMirrorStore()
        store.seedFromTranscript(
            "s1",
            """[{"type":"rc_message","data":{"text":"ok"}},
                {"type":"rc_message","data":{"text":"a completely different, longer turn"}}]"""
        )
        val rows = store.tail("s1", n = 100).first
        assertEquals(listOf("ok", "a completely different, longer turn"), rows.map { it.text })
    }

    @Test
    fun onlyTheImmediatelyFollowingPartialDecidesSupersession() {
        // Partial 1 is a prefix of partial 3 but NOT of partial 2, so entry 2 is a NEW turn and
        // entry 1 must survive. Scanning all later entries would wrongly drop it.
        val store = RcMirrorStore()
        store.seedFromTranscript(
            "s1",
            """[{"type":"rc_message","data":{"text":"Hel"}},
                {"type":"rc_message","data":{"text":"zzz"}},
                {"type":"rc_message","data":{"text":"Hello there"}}]"""
        )
        assertEquals(
            listOf("Hel", "zzz", "Hello there"),
            store.tail("s1", n = 100).first.map { it.text }
        )
    }

    @Test
    fun consecutivePartialsOfOneTurnCollapseToTheLast() {
        val store = RcMirrorStore()
        store.seedFromTranscript(
            "s1",
            """[{"type":"rc_message","data":{"text":"He"}},
                {"type":"rc_message","data":{"text":"Hell"}},
                {"type":"rc_message","data":{"text":"Hello world"}}]"""
        )
        assertEquals(listOf("Hello world"), store.tail("s1", n = 100).first.map { it.text })
    }

    @Test
    fun aLegacyFlatTextEntryWithoutATypeIsSeeded() {
        val store = RcMirrorStore()
        store.seedFromTranscript(
            "s1",
            """[{"role":"user","text":"hi"},{"role":"assistant","text":"hello"}]"""
        )
        val rows = store.tail("s1", n = 100).first
        assertEquals(listOf("user", "assistant"), rows.map { it.role })
        assertEquals(listOf("hi", "hello"), rows.map { it.text })
    }

    @Test
    fun aSeededTurnIsNotReCommittedAsADuplicateByTheLivePath() {
        val store = RcMirrorStore()
        store.seedFromTranscript("s1", """[{"type":"rc_message","data":{"text":"Hello world"}}]""")
        // The live stream re-delivers the same cumulative text for the turn already in history.
        store.noteAssistantText("s1", "Hello world")
        assertEquals(emptyList<RcRow>(), store.commitTurn("s1"))
        assertEquals(1, store.tail("s1", n = 100).first.size)
    }

    @Test
    fun seedingIsSkippedOnceLiveRowsExistSoSeqStaysMonotonic() {
        val store = RcMirrorStore()
        store.appendUser("s1", "live")
        store.seedFromTranscript("s1", transcript)
        assertEquals(listOf("live"), store.tail("s1", n = 100).first.map { it.text })
    }

    @Test
    fun markdownIsStrippedWhileSeeding() {
        val store = RcMirrorStore()
        store.seedFromTranscript(
            "s1",
            """[{"type":"rc_message","data":{"text":"see [the docs](http://x) and ```kotlin\nval a = 1\n``` end"}}]"""
        )
        val rows = store.tail("s1", n = 100).first
        assertEquals(1, rows.size)
        assertEquals("see the docs and [code] end", rows[0].text)
    }

    @Test
    fun seedingTwiceIsIdempotent() {
        val store = RcMirrorStore()
        store.seedFromTranscript("s1", transcript)
        val first = store.tail("s1", n = 100).first
        val firstLastSeq = store.lastSeq("s1")
        store.seedFromTranscript("s1", transcript)
        val second = store.tail("s1", n = 100).first
        assertEquals(first, second)
        assertEquals(firstLastSeq, store.lastSeq("s1"))
    }

    @Test
    fun aPromptEntryIsSeededWithItsOptions() {
        val store = RcMirrorStore()
        store.seedFromTranscript(
            "s1",
            """[{"type":"rc_permission_request","data":{"toolName":"Bash","description":"Allow Bash?","options":["Yes","No"]}}]"""
        )
        val rows = store.tail("s1", n = 100).first
        assertEquals(1, rows.size)
        assertEquals("prompt", rows[0].role)
        assertEquals("Allow Bash?", rows[0].text)
        assertEquals(listOf("Yes", "No"), rows[0].options)
    }

    @Test
    fun aPromptWithoutADescriptionFallsBackToTheToolName() {
        val store = RcMirrorStore()
        store.seedFromTranscript(
            "s1",
            """[{"type":"rc_permission_request","data":{"toolName":"Bash"}}]"""
        )
        assertEquals("Bash", store.tail("s1", n = 100).first[0].text)
    }

    @Test
    fun aMalformedOrEmptyTranscriptSeedsNothingAndDoesNotThrow() {
        val store = RcMirrorStore()
        store.seedFromTranscript("s1", "")
        store.seedFromTranscript("s1", "not json at all")
        store.seedFromTranscript("s1", "[")
        store.seedFromTranscript("s1", "[]")
        store.seedFromTranscript("s1", """[{"type":"rc_message"}]""")
        store.seedFromTranscript("s1", """[1,2,3]""")
        assertEquals(emptyList<RcRow>(), store.tail("s1", n = 100).first)
        assertEquals(-1L, store.lastSeq("s1"))
    }

    @Test
    fun seedingRespectsTheRowCapAndReportsMoreAbove() {
        val entries = (0 until 60).joinToString(",") { """{"type":"user_message","data":{"text":"m$it"}}""" }
        val store = RcMirrorStore()
        store.seedFromTranscript("s1", "[$entries]")
        val (rows, moreAbove) = store.tail("s1", n = 100)
        assertEquals(RcMirrorStore.MAX_ROWS, rows.size)
        assertTrue(moreAbove)
        assertEquals("m59", rows.last().text)
    }
}
