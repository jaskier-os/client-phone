package com.repository.listener.ui.rc

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * A ScrollView whose height never exceeds a fraction of the window.
 *
 * Android's ScrollView ignores `android:maxHeight`, and `wrap_content` grows
 * without bound. The question/permission option panel uses this so a prompt
 * with many options scrolls inside a bounded area instead of pushing the
 * message list off the top of the screen, where the overflowed options could
 * never be reached.
 *
 * Measured fresh on every pass, so a short prompt shown after a long one
 * shrinks back to its content; nothing is cached.
 */
class BoundedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {

    /** Fraction of the window height this view may occupy at most. */
    var maxHeightFraction: Float = 0.45f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (resources.displayMetrics.heightPixels * maxHeightFraction).toInt()
        val capped = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, capped)
    }
}
