package com.repository.listener.ui.views

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView
import com.repository.listener.R
import com.repository.listener.util.LogCollector
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

class VoiceRecordButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "VoiceRecordButton"
        private const val HOLD_DELAY_MS = 150L
        private const val CANCEL_THRESHOLD = 0.45f
    }

    interface VoiceRecordListener {
        fun onRecordStart()
        fun onRecordEnd()
        fun onRecordCancel()
    }

    var listener: VoiceRecordListener? = null
    var overlay: RecordingOverlay? = null

    private val handler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val screenWidth = resources.displayMetrics.widthPixels.toFloat()

    private val moveSlopPx = 10f * density
    private val cancelDistPx = min(screenWidth * 0.35f, 140f * density)
    private val lockThresholdPx = 57f * density

    // Touch state
    private var startX = 0f
    private var startY = 0f
    private var isRecording = false
    private var isLocked = false
    private var startedDragging = false
    private var startedDraggingX = 0f
    private var pendingStart = false

    private val startRecordRunnable = Runnable {
        isRecording = true
        LogCollector.i(TAG, "Recording started (hold threshold reached)")

        // Show overlay at button position
        val loc = IntArray(2)
        getLocationInWindow(loc)
        overlay?.show(loc[0] + width, loc[1] + height / 2)

        listener?.onRecordStart()
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                isLocked = false
                startedDragging = false
                pendingStart = false

                // Start recording immediately on press (like Telegram audio mode)
                startRecordRunnable.run()
                animate().scaleX(1.05f).scaleY(1.05f).setDuration(100).start()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isRecording) {
                    return true
                } else {
                    // Horizontal cancel (swipe left)
                    val dx = startX - event.rawX  // positive = leftward
                    if (!startedDragging && dx > 0) {
                        startedDragging = true
                        startedDraggingX = event.rawX
                    }
                    if (startedDragging) {
                        val dist = startedDraggingX - event.rawX
                        val progress = 1f - dist / cancelDistPx
                        overlay?.setSlideToCancelProgress(progress)
                        if (progress < CANCEL_THRESHOLD) {
                            // Auto-cancel
                            LogCollector.i(TAG, "Recording cancelled by swipe")
                            isRecording = false
                            overlay?.hide()
                            resetVisuals()
                            listener?.onRecordCancel()
                            return true
                        }
                    }

                    // Vertical lock (swipe up)
                    val dy = startY - event.rawY  // positive = upward
                    if (dy > 0 && !isLocked) {
                        val lockProg = (dy / lockThresholdPx).coerceIn(0f, 1f)
                        overlay?.setLockProgress(lockProg)
                        if (dy >= lockThresholdPx) {
                            isLocked = true
                            overlay?.setLocked()
                            LogCollector.i(TAG, "Recording locked")
                            resetVisuals()
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(startRecordRunnable)
                pendingStart = false
                parent?.requestDisallowInterceptTouchEvent(false)

                if (isRecording && !isLocked) {
                    // Finish recording
                    isRecording = false
                    overlay?.hide()
                    resetVisuals()
                    listener?.onRecordEnd()
                } else if (!isRecording) {
                    // Was just a tap
                    resetVisuals()
                    performClick()
                }
                // If locked: do nothing, overlay handles taps
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(startRecordRunnable)
                pendingStart = false
                parent?.requestDisallowInterceptTouchEvent(false)

                if (isRecording) {
                    isRecording = false
                    overlay?.hide()
                    listener?.onRecordCancel()
                }
                resetVisuals()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun resetVisuals() {
        animate().cancel()
        animate().scaleX(1f).scaleY(1f).setDuration(150).start()
    }

    fun cancelRecording() {
        handler.removeCallbacks(startRecordRunnable)
        pendingStart = false
        if (isRecording) {
            isRecording = false
            isLocked = false
            overlay?.hide()
            resetVisuals()
        }
    }
}
