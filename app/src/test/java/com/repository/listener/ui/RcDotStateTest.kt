package com.repository.listener.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a chat row's status dot says.
 *
 * The regression this pins: `turning` and `unread` are only ever set by live
 * WebSocket events, so a session the phone has not been watching has both
 * false. That fell through to IDLE (red) even while its CLI was running on the
 * PC, which reads as "stopped" for a session that is very much alive.
 */
class RcDotStateTest {

    @Test
    fun runningSessionThePhoneHasNotWatchedIsNotIdle() {
        // The exact reported case: alive on the PC, no WS events seen.
        assertEquals(
            RcDotState.RUNNING,
            rcDotState(status = "active", turning = false, unread = false, isLive = true)
        )
    }

    @Test
    fun idleSessionWithNoLiveCliIsIdle() {
        assertEquals(
            RcDotState.IDLE,
            rcDotState(status = "active", turning = false, unread = false, isLive = false)
        )
    }

    @Test
    fun endedSessionIsEndedEvenIfSomehowReportedLive() {
        // status wins over liveness: a row the store calls ended must not claim
        // to be running just because a stale live entry mentions it.
        assertEquals(
            RcDotState.ENDED,
            rcDotState(status = "ended", turning = false, unread = false, isLive = true)
        )
    }

    @Test
    fun turningOutranksEverythingElse() {
        assertEquals(
            RcDotState.TURNING,
            rcDotState(status = "active", turning = true, unread = true, isLive = true)
        )
    }

    @Test
    fun unreadOutranksPlainLiveness() {
        // Both render green today, but they are different states and the
        // ordering must stay explicit.
        assertEquals(
            RcDotState.UNREAD,
            rcDotState(status = "active", turning = false, unread = true, isLive = true)
        )
    }

    @Test
    fun unreadStillShowsWithoutALiveCli() {
        assertEquals(
            RcDotState.UNREAD,
            rcDotState(status = "active", turning = false, unread = true, isLive = false)
        )
    }

    @Test
    fun everyCombinationIsDecided() {
        // No input combination may fall through undecided.
        for (status in listOf("active", "ended")) {
            for (turning in listOf(true, false)) {
                for (unread in listOf(true, false)) {
                    for (isLive in listOf(true, false)) {
                        val state = rcDotState(status, turning, unread, isLive)
                        if (status != "active") {
                            assertEquals(
                                "a non-active session is always ENDED",
                                RcDotState.ENDED, state
                            )
                        } else if (!turning && !unread && isLive) {
                            assertEquals(
                                "an idle-but-live session must never read as IDLE",
                                RcDotState.RUNNING, state
                            )
                        }
                    }
                }
            }
        }
    }
}
