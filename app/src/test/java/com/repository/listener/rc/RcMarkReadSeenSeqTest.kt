package com.repository.listener.rc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unread bar may only be cleared for content the glasses have actually rendered. Clearing
 * unconditionally loses a turn that finishes between the glasses' render and its request.
 */
class RcMarkReadSeenSeqTest {

    @Test
    fun everythingSeenClearsUnread() {
        assertTrue(RcReadPolicy.shouldClearUnread(lastSeq = 12L, seenSeq = 12L))
        assertTrue(RcReadPolicy.shouldClearUnread(lastSeq = 5L, seenSeq = 12L))
    }

    @Test
    fun aTurnThatFinishedInFlightDoesNotClearUnread() {
        assertFalse(RcReadPolicy.shouldClearUnread(lastSeq = 13L, seenSeq = 12L))
    }

    @Test
    fun theLegacyPhoneUiPathAlwaysClears() {
        assertTrue(RcReadPolicy.shouldClearUnread(lastSeq = 9_999L, seenSeq = Long.MAX_VALUE))
        assertTrue(RcReadPolicy.shouldClearUnread(lastSeq = -1L, seenSeq = Long.MAX_VALUE))
    }

    @Test
    fun anEmptySessionSeenAsEmptyClears() {
        assertTrue(RcReadPolicy.shouldClearUnread(lastSeq = -1L, seenSeq = -1L))
    }

    @Test
    fun anUnseenSessionWithRowsDoesNotClear() {
        assertFalse(RcReadPolicy.shouldClearUnread(lastSeq = 0L, seenSeq = -1L))
    }
}
