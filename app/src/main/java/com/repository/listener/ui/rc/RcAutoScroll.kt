package com.repository.listener.ui.rc

/**
 * When an incoming update may move the chat to the bottom.
 *
 * Two rules, kept out of the activity so they can be tested without a screen:
 *
 * 1. Only a NEWLY appended row may auto-scroll. A heartbeat that merely
 *    advances the elapsed counter on a row already on screen changes nothing
 *    worth moving for -- yet it used to scroll on every 2s tick, which yanked
 *    the list to the bottom the whole time a slow tool ran and made anything
 *    above it unreadable.
 *
 * 2. "Near the bottom" is measured in pixels, not items. The old check was
 *    `lastVisibleItem >= itemCount - 3`, and the running tool row is usually
 *    the last item, so with that row still partly on screen the user was
 *    treated as "at the bottom" no matter how far up they had read.
 */
object RcAutoScroll {

    /** Anything closer to the end than this is "at the bottom". */
    const val NEAR_BOTTOM_PX = 160

    /**
     * @param appended true when the update ADDED a row; false when it updated
     *   an existing one (heartbeat, status change, completion).
     * @param pixelsFromBottom how far the viewport's bottom edge is from the
     *   end of the content, in pixels.
     */
    fun shouldScrollToBottom(appended: Boolean, pixelsFromBottom: Int): Boolean =
        appended && pixelsFromBottom <= NEAR_BOTTOM_PX

    /** True when the user is close enough to the end to be considered "at the bottom". */
    fun isNearBottom(pixelsFromBottom: Int): Boolean = pixelsFromBottom <= NEAR_BOTTOM_PX
}
