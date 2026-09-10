package com.repository.listener.ui.rc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported bug: every 2s tool heartbeat scrolled the chat to the bottom,
 * so anything above a running tool could not be read.
 */
class RcAutoScrollTest {

    @Test
    fun heartbeatOnExistingRowNeverScrolls() {
        // Even when the user IS at the bottom, an in-place update is not a
        // reason to move: nothing new appeared.
        assertFalse(RcAutoScroll.shouldScrollToBottom(appended = false, pixelsFromBottom = 0))
        assertFalse(RcAutoScroll.shouldScrollToBottom(appended = false, pixelsFromBottom = 5000))
    }

    @Test
    fun newRowScrollsOnlyWhenUserIsAtTheBottom() {
        assertTrue(RcAutoScroll.shouldScrollToBottom(appended = true, pixelsFromBottom = 0))
        assertTrue(RcAutoScroll.shouldScrollToBottom(appended = true, pixelsFromBottom = RcAutoScroll.NEAR_BOTTOM_PX))
        // Scrolled up to read: a new row must not yank the view away.
        assertFalse(RcAutoScroll.shouldScrollToBottom(appended = true, pixelsFromBottom = RcAutoScroll.NEAR_BOTTOM_PX + 1))
        assertFalse(RcAutoScroll.shouldScrollToBottom(appended = true, pixelsFromBottom = 3000))
    }

    @Test
    fun aLastItemThatIsNotLaidOutMeansFarFromBottom() {
        // pixelsFromBottom() reports Int.MAX_VALUE when the last row is not on
        // screen at all. That is the case that broke three times: the scrollbar
        // ESTIMATE the old code used said "one short row to go" while a whole
        // tall tool row sat below the viewport. Whatever the measurement, an
        // unlaid last item must never read as near.
        assertFalse(RcAutoScroll.isNearBottom(Int.MAX_VALUE))
        assertFalse(RcAutoScroll.shouldScrollToBottom(appended = true, pixelsFromBottom = Int.MAX_VALUE))
    }

    @Test
    fun nearBottomIsPixelsNotItems() {
        // The old item-count proxy called the user "near bottom" whenever the
        // last three items were visible -- true for a tall running-tool row
        // even with a whole screen of answer scrolled past above it.
        assertTrue(RcAutoScroll.isNearBottom(0))
        assertTrue(RcAutoScroll.isNearBottom(RcAutoScroll.NEAR_BOTTOM_PX))
        assertFalse(RcAutoScroll.isNearBottom(RcAutoScroll.NEAR_BOTTOM_PX + 1))
    }
}
