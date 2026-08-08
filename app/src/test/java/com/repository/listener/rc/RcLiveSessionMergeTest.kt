package com.repository.listener.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge is the whole feature: the orchestrator REST session list is the only source that knows
 * about a session the user opened in the phone UI, but it is also the POOREST source -- it carries
 * no turning, unread or lastSeq. Every test here exists to pin one half of that trade: a discovered
 * session must appear, and it must never overwrite what the WebSocket path already knows.
 */
class RcLiveSessionMergeTest {

    private fun ws(
        id: String = "ws",
        workDir: String = "/work/$id",
        status: String = "active",
        turning: Boolean = false,
        unread: Boolean = false,
        sessionName: String? = null
    ) = id to RcDumpEntry(workDir, status, turning, unread, sessionName, discovered = false)

    private fun discovered(
        id: String = "d",
        workDir: String = "/work/$id",
        status: String = "active",
        turning: Boolean = false,
        unread: Boolean = false,
        sessionName: String? = null
    ) = id to RcDumpEntry(workDir, status, turning, unread, sessionName, discovered = true)

    private fun live(
        id: String,
        workDir: String = "/work/$id",
        alive: Boolean = true,
        title: String? = null
    ) = RcLiveSession(id, workDir, alive, title)

    // --- discovery ---

    @Test
    fun aLiveSessionTheServiceNeverSawIsAdded() {
        val r = RcLiveSessionMerge.merge(emptyMap(), listOf(live("s1", workDir = "/home/me/proj")))
        assertEquals(setOf("s1"), r.entries.keys)
        val e = r.entries.getValue("s1")
        assertEquals("/home/me/proj", e.workDir)
        assertEquals("active", e.status)
        assertTrue("a REST-discovered session must be marked as such", e.discovered)
        assertEquals(listOf("s1"), r.added)
    }

    @Test
    fun aDiscoveredSessionDefaultsToNoTurningAndNoUnread() {
        // The REST list cannot know these. Defaulting either to true would paint a permanent
        // spinner or a permanent unread dot on the glasses that nothing could ever clear.
        val e = RcLiveSessionMerge.merge(emptyMap(), listOf(live("s1"))).entries.getValue("s1")
        assertEquals(false, e.turning)
        assertEquals(false, e.unread)
    }

    @Test
    fun theRestTitleBecomesTheSessionName() {
        val e = RcLiveSessionMerge.merge(emptyMap(), listOf(live("s1", title = "fix the parser")))
            .entries.getValue("s1")
        assertEquals("fix the parser", e.sessionName)
    }

    @Test
    fun aBlankRestTitleLeavesTheNameUnsetRatherThanEmpty() {
        // pushRcState falls back to the workDir basename when sessionName is null; an empty string
        // is not null and would render a nameless row.
        val e = RcLiveSessionMerge.merge(emptyMap(), listOf(live("s1", title = "  ")))
            .entries.getValue("s1")
        assertNull(e.sessionName)
    }

    @Test
    fun aDeadSessionIsNeverAdded() {
        val r = RcLiveSessionMerge.merge(emptyMap(), listOf(live("s1", alive = false)))
        assertTrue(r.entries.isEmpty())
        assertTrue(r.added.isEmpty())
    }

    @Test
    fun aSessionWithNoIdIsIgnored() {
        // An id-less row can never be opened, marked read or dictated into.
        val r = RcLiveSessionMerge.merge(emptyMap(), listOf(live("")))
        assertTrue(r.entries.isEmpty())
    }

    // --- the merge must not clobber ---

    @Test
    fun liveWebsocketStateSurvivesRediscovery() {
        val existing = mapOf(ws("s1", turning = true, unread = true, sessionName = "real name"))
        val r = RcLiveSessionMerge.merge(existing, listOf(live("s1", title = "rest title")))
        val e = r.entries.getValue("s1")
        assertEquals(true, e.turning)
        assertEquals(true, e.unread)
        assertEquals("real name", e.sessionName)
        assertEquals(false, e.discovered)
        assertTrue("rediscovery is not an addition", r.added.isEmpty())
    }

    @Test
    fun theRestListNeverOverwritesAWebsocketOwnedWorkDir() {
        val existing = mapOf(ws("s1", workDir = "/truth"))
        val r = RcLiveSessionMerge.merge(existing, listOf(live("s1", workDir = "/stale")))
        assertEquals("/truth", r.entries.getValue("s1").workDir)
    }

    @Test
    fun aWebsocketEventPromotesADiscoveredEntryOutOfRestOwnership() {
        // Provenance is what decides removal, so it has to be able to flip. This documents the
        // contract ListenerService relies on: writing a non-discovered entry ends REST ownership.
        val existing = mapOf(discovered("s1"))
        val promoted = existing["s1"]!!.copy(discovered = false, turning = true)
        val r = RcLiveSessionMerge.merge(mapOf("s1" to promoted), emptyList())
        assertEquals("a promoted entry is no longer the REST list's to remove", setOf("s1"), r.entries.keys)
    }

    // --- REST owns what REST created ---

    @Test
    fun aDiscoveredSessionMissingFromTheListIsRemoved() {
        val r = RcLiveSessionMerge.merge(mapOf(discovered("s1"), discovered("s2")), listOf(live("s1")))
        assertEquals(setOf("s1"), r.entries.keys)
        assertEquals(listOf("s2"), r.removed)
    }

    @Test
    fun aDiscoveredSessionReportedDeadIsRemoved() {
        val r = RcLiveSessionMerge.merge(mapOf(discovered("s1")), listOf(live("s1", alive = false)))
        assertTrue(r.entries.isEmpty())
        assertEquals(listOf("s1"), r.removed)
    }

    @Test
    fun aWebsocketOwnedSessionMissingFromTheListIsKept() {
        // onRcSessionEnd deliberately retains the entry as status=ended so it lingers in the All
        // view. The REST list must not be allowed to delete that history.
        val existing = mapOf(ws("s1", status = "ended"))
        val r = RcLiveSessionMerge.merge(existing, emptyList())
        assertEquals(setOf("s1"), r.entries.keys)
        assertEquals("ended", r.entries.getValue("s1").status)
        assertTrue(r.removed.isEmpty())
    }

    @Test
    fun aDiscoveredSessionsWorkDirAndNameTrackTheRestList() {
        // REST owns these entries outright, so a rename or a moved workDir must propagate.
        val existing = mapOf(discovered("s1", workDir = "/old", sessionName = "old"))
        val r = RcLiveSessionMerge.merge(existing, listOf(live("s1", workDir = "/new", title = "new")))
        val e = r.entries.getValue("s1")
        assertEquals("/new", e.workDir)
        assertEquals("new", e.sessionName)
        assertTrue(e.discovered)
    }

    @Test
    fun aDiscoveredSessionKeepsItsNameWhenTheListStopsSendingATitle() {
        val existing = mapOf(discovered("s1", sessionName = "kept"))
        val r = RcLiveSessionMerge.merge(existing, listOf(live("s1", title = null)))
        assertEquals("kept", r.entries.getValue("s1").sessionName)
    }

    // --- revival ---

    @Test
    fun anEndedSessionTheListStillReportsAliveIsRevived() {
        // The WS drops silently. A session marked ended by a lost socket, then found alive by the
        // authoritative list, is open -- the phone UI already does exactly this reconciliation.
        val existing = mapOf(ws("s1", status = "ended", unread = true, sessionName = "keep me"))
        val r = RcLiveSessionMerge.merge(existing, listOf(live("s1")))
        val e = r.entries.getValue("s1")
        assertEquals("active", e.status)
        assertEquals("revival must not discard the unread bit", true, e.unread)
        assertEquals("keep me", e.sessionName)
        assertEquals(listOf("s1"), r.revived)
    }

    @Test
    fun anAlreadyActiveSessionIsNotReportedAsRevived() {
        val r = RcLiveSessionMerge.merge(mapOf(ws("s1")), listOf(live("s1")))
        assertTrue(r.revived.isEmpty())
    }

    // --- stability ---

    @Test
    fun anUnchangedMergeChangesNothing() {
        // pushRcState dedups on byte equality; a merge that churned the map every minute would
        // defeat that and put a BT frame on the wire once per poll forever.
        val existing = mapOf(ws("s1", turning = true), discovered("s2"))
        val listed = listOf(live("s1"), live("s2"))
        val r = RcLiveSessionMerge.merge(existing, listed)
        assertEquals(existing, r.entries)
        assertTrue(r.added.isEmpty() && r.removed.isEmpty() && r.revived.isEmpty())
        assertTrue("no change at all must be reportable as such", !r.changed)
    }

    @Test
    fun anyAdditionRemovalOrRevivalCountsAsChanged() {
        assertTrue(RcLiveSessionMerge.merge(emptyMap(), listOf(live("a"))).changed)
        assertTrue(RcLiveSessionMerge.merge(mapOf(discovered("a")), emptyList()).changed)
        assertTrue(RcLiveSessionMerge.merge(mapOf(ws("a", status = "ended")), listOf(live("a"))).changed)
    }

    @Test
    fun aDiscoveredWorkDirEditCountsAsChanged() {
        val r = RcLiveSessionMerge.merge(mapOf(discovered("a", workDir = "/old")), listOf(live("a", workDir = "/new")))
        assertTrue(r.changed)
    }

    @Test
    fun aDuplicateIdInTheListDoesNotProduceTwoEntries() {
        val r = RcLiveSessionMerge.merge(emptyMap(), listOf(live("s1", workDir = "/a"), live("s1", workDir = "/b")))
        assertEquals(1, r.entries.size)
    }

    @Test
    fun theInputMapIsNotMutated() {
        val existing = HashMap(mapOf(discovered("s1")))
        RcLiveSessionMerge.merge(existing, listOf(live("s2")))
        assertEquals(setOf("s1"), existing.keys)
    }
}
