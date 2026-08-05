package com.repository.listener.protocol

import com.repository.listener.protocol.SessionIdentity.OpenDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests exist to prove one security property: a captured OPEN cannot be
 * replayed to rewind a receiver's sequence state and re-enable replay of a whole
 * captured session.
 */
class SessionIdentityTest {

    // ---- Minting ----

    @Test
    fun mintProducesStrictlyIncreasingSids() {
        var sid = 0
        var previous = 0
        repeat(100) {
            sid = SessionIdentity.mintNextSid(sid)
            assertTrue("sid must increase: $previous -> $sid", sid > previous)
            previous = sid
        }
    }

    @Test
    fun firstMintIsTheReservedFirstSid() {
        assertEquals(SessionIdentity.FIRST_SID, SessionIdentity.mintNextSid(0))
    }

    @Test
    fun mintNeverReturnsTheReservedZeroOnWrap() {
        // -1 is 0xFFFFFFFF, the last uint32 value; the next increment wraps to 0.
        assertNotEquals(0, SessionIdentity.mintNextSid(-1))
        assertEquals(SessionIdentity.FIRST_SID, SessionIdentity.mintNextSid(-1))
    }

    // ---- OPEN decisions ----

    @Test
    fun firstEverOpenStartsANewSession() {
        assertEquals(
            OpenDecision.ACCEPT_NEW_SESSION,
            SessionIdentity.decideOpen(incomingSid = 1, storedSid = 0),
        )
    }

    @Test
    fun newerSidStartsANewSessionAndResetsSeq() {
        assertEquals(
            OpenDecision.ACCEPT_NEW_SESSION,
            SessionIdentity.decideOpen(incomingSid = 7, storedSid = 6),
        )
    }

    /**
     * The security-critical case. A replayed OPEN carries the SAME sid as the
     * session already in progress. It must not reset lastSeq, or every captured
     * event of that session becomes replayable with a valid tag.
     */
    @Test
    fun replayedOpenForTheCurrentSessionPreservesSeq() {
        assertEquals(
            OpenDecision.ACCEPT_PRESERVE_SEQ,
            SessionIdentity.decideOpen(incomingSid = 7, storedSid = 7),
        )
    }

    /** A replayed OPEN from an older captured session is rejected outright. */
    @Test
    fun replayedOpenFromAnOlderSessionIsRejected() {
        assertEquals(
            OpenDecision.REJECT_REPLAY,
            SessionIdentity.decideOpen(incomingSid = 3, storedSid = 7),
        )
    }

    @Test
    fun sidZeroIsNeverAcceptedAsASession() {
        assertEquals(
            OpenDecision.REJECT_REPLAY,
            SessionIdentity.decideOpen(incomingSid = 0, storedSid = 0),
        )
        assertEquals(
            OpenDecision.REJECT_REPLAY,
            SessionIdentity.decideOpen(incomingSid = 0, storedSid = 5),
        )
    }

    /**
     * Full attack narrative, end to end: capture a session, then replay its OPEN
     * and its events after the user has legitimately moved on to a new session.
     */
    @Test
    fun capturedSessionCannotBeReplayedAfterALegitimateNewSession() {
        val capturedSid = 41
        // The user later starts a genuine new session.
        val currentSid = SessionIdentity.mintNextSid(capturedSid)
        assertEquals(42, currentSid)

        // The attacker replays the captured OPEN.
        assertEquals(
            "a captured OPEN must not rewind the receiver",
            OpenDecision.REJECT_REPLAY,
            SessionIdentity.decideOpen(capturedSid, currentSid),
        )
        // And the captured events are refused for the same reason.
        assertFalse(SessionIdentity.isAcceptableEventSid(capturedSid, currentSid))
    }

    @Test
    fun replayWithinTheLiveSessionCannotRewindSequenceState() {
        val sid = 9
        // The live session has already applied up to seq 500.
        val lastSeq = 500
        // A replayed OPEN arrives for the SAME sid.
        assertEquals(OpenDecision.ACCEPT_PRESERVE_SEQ, SessionIdentity.decideOpen(sid, sid))
        // Because lastSeq is preserved, every captured event is still a duplicate.
        for (capturedSeq in listOf(1, 250, 499, 500)) {
            assertTrue(
                "captured seq $capturedSeq must still be dropped as a duplicate",
                RemoteInputProtocol.seqDifference(capturedSeq, lastSeq) <= 0,
            )
        }
    }

    // ---- Event sid acceptance ----

    @Test
    fun eventsForTheCurrentSessionAreAccepted() {
        assertTrue(SessionIdentity.isAcceptableEventSid(7, 7))
    }

    /** OPEN can be lost, so events for a newer sid must not be stranded. */
    @Test
    fun eventsForANewerSessionAreAcceptedEvenIfOpenWasLost() {
        assertTrue(SessionIdentity.isAcceptableEventSid(8, 7))
    }

    @Test
    fun eventsForAnOlderSessionAreRejected() {
        assertFalse(SessionIdentity.isAcceptableEventSid(6, 7))
    }

    @Test
    fun eventsWithTheReservedZeroSidAreRejected() {
        assertFalse(SessionIdentity.isAcceptableEventSid(0, 7))
    }

    // ---- Reboot / reinstall / wrap ----

    /**
     * A watch reboot must NOT regress the sid. This is why it is persisted rather
     * than derived from elapsedRealtime, which resets to 0 on every boot.
     */
    @Test
    fun rebootDoesNotRegressTheSidWhenPersisted() {
        val beforeReboot = SessionIdentity.mintNextSid(SessionIdentity.mintNextSid(0))
        // After reboot the watch reads the persisted value and mints from it.
        val afterReboot = SessionIdentity.mintNextSid(beforeReboot)
        assertTrue(afterReboot > beforeReboot)
        assertEquals(
            OpenDecision.ACCEPT_NEW_SESSION,
            SessionIdentity.decideOpen(afterReboot, beforeReboot),
        )
    }

    /**
     * Honest limitation, asserted so it is not forgotten: a factory reset or a
     * data clear wipes the persisted counter, so the watch mints from 1 again
     * while the glasses still hold a high sid. The receiver then rejects the
     * legitimate new session as a replay. That is the SAFE failure direction, but
     * it needs an operator-visible recovery (clear the glasses' stored sid).
     */
    @Test
    fun factoryResetCausesASidRegressionThatFailsClosed() {
        val storedOnGlasses = 500
        val afterFactoryReset = SessionIdentity.mintNextSid(0)
        assertEquals(
            "a sid regression must fail closed, never silently accept",
            OpenDecision.REJECT_REPLAY,
            SessionIdentity.decideOpen(afterFactoryReset, storedOnGlasses),
        )
    }

    /**
     * Wrap-safe comparison: crossing the uint32 boundary still reads as "newer",
     * which is the entire point of using a signed difference rather than `<=`.
     * A plain comparison would permanently lock the source out at wrap.
     *
     * Wrap needs 2^32 app-session starts (one increment each), so it is
     * unreachable in practice; this pins the behaviour rather than leaving it
     * undefined.
     */
    @Test
    fun comparisonSurvivesUnsignedWraparound() {
        assertEquals(
            OpenDecision.ACCEPT_NEW_SESSION,
            SessionIdentity.decideOpen(Int.MIN_VALUE, Int.MAX_VALUE),
        )
        // 0xFFFFFFFF -> 1 is a forward step of 2 under wrap-safe arithmetic, not a
        // 4-billion regression, so the session is accepted and the source survives.
        val nearMax = -1 // 0xFFFFFFFF
        val wrapped = SessionIdentity.mintNextSid(nearMax)
        assertEquals(SessionIdentity.FIRST_SID, wrapped)
        assertEquals(
            OpenDecision.ACCEPT_NEW_SESSION,
            SessionIdentity.decideOpen(wrapped, nearMax),
        )
    }

    @Test
    fun concurrentOpensForTheSameSidAreIdempotent() {
        val sid = 12
        repeat(5) {
            assertEquals(OpenDecision.ACCEPT_PRESERVE_SEQ, SessionIdentity.decideOpen(sid, sid))
        }
    }
}
