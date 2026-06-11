package com.repository.navigation.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Wraps MapView to prevent ViewPager2 from intercepting touch events.
 * ViewPager2's internal RecyclerView resets requestDisallowInterceptTouchEvent
 * on ACTION_DOWN, so we must re-assert it on every dispatch.
 */
class MapTouchWrapper @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(ev)
    }
}
