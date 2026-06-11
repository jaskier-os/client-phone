package com.repository.listener.ui.util

import android.view.MotionEvent
import android.view.View

/**
 * Adds a press-pulse animation: scales the view to 1.15x on touch down,
 * back to 1.0x on touch up/cancel. Does not consume the event, so the
 * existing OnClickListener still fires.
 */
fun View.applyPressPulse() {
    setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                v.animate()
                    .scaleX(1.15f)
                    .scaleY(1.15f)
                    .setDuration(100)
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(100)
                    .start()
            }
        }
        false
    }
}
