package com.repository.listener.ui.views

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import android.view.ViewGroup
import com.repository.listener.R
import com.repository.listener.audio.VoiceSegmentData

class ZoomBubblePopup(private val context: Context) {

    companion object {
        private const val ZOOM_WINDOW_MS = 30_000L // 30 seconds
    }

    private var popup: PopupWindow? = null
    var onPositionSelected: ((Long) -> Unit)? = null
    var onVoiceSegmentTapped: ((VoiceSegmentData) -> Unit)? = null

    fun show(
        anchor: View,
        screenX: Int,
        screenY: Int,
        centerMs: Long,
        amplitudeData: ShortArray,
        voiceSegments: List<VoiceSegmentData>,
        totalDurationMs: Long
    ) {
        dismiss()

        val view = LayoutInflater.from(context).inflate(R.layout.popup_zoom_bubble, null)
        val spectrogramView = view.findViewById<SpectrogramView>(R.id.zoomSpectrogramView)
        val txtStart = view.findViewById<TextView>(R.id.txtZoomStart)
        val txtEnd = view.findViewById<TextView>(R.id.txtZoomEnd)

        // Set up zoomed spectrogram (tap-only, no scroll/zoom in popup)
        spectrogramView.scrollEnabled = false
        spectrogramView.zoomEnabled = false
        spectrogramView.setData(amplitudeData, voiceSegments, totalDurationMs)

        // Calculate zoom level to show ZOOM_WINDOW_MS in the popup width
        val popupWidthDp = 280
        val popupWidthPx = (popupWidthDp * context.resources.displayMetrics.density).toInt()
        val msPerPx = ZOOM_WINDOW_MS.toFloat() / popupWidthPx
        spectrogramView.setZoomLevel(msPerPx)

        // Center on tapped position
        val startMs = maxOf(0, centerMs - ZOOM_WINDOW_MS / 2)
        spectrogramView.setScrollPosition(startMs.toFloat())

        // Time labels
        txtStart?.text = formatTime(startMs)
        txtEnd?.text = formatTime(minOf(startMs + ZOOM_WINDOW_MS, totalDurationMs))

        spectrogramView.onTap = { ms, _, _ ->
            val segment = spectrogramView.findVoiceSegmentAt(ms)
            if (segment != null) {
                onVoiceSegmentTapped?.invoke(segment)
            } else {
                onPositionSelected?.invoke(ms)
            }
            dismiss()
        }

        // Create popup with background
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#3C3836")) // bg1
            cornerRadius = dpToPx(16f)
            setStroke(dpToPx(1f).toInt(), Color.parseColor("#504945")) // bg2 border
        }
        view.background = bg
        view.clipToOutline = true

        popup = PopupWindow(view, popupWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            elevation = dpToPx(4f)
            isOutsideTouchable = true

            // Position above the tap point, centered horizontally
            val screenWidth = anchor.resources.displayMetrics.widthPixels
            val popupHeight = dpToPx(120f).toInt()
            val x = (screenX - popupWidthPx / 2).coerceIn(dpToPx(8f).toInt(), screenWidth - popupWidthPx - dpToPx(8f).toInt())
            val y = screenY - popupHeight - dpToPx(16f).toInt()
            showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, x, y.coerceAtLeast(0))
        }
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    private fun dpToPx(dp: Float): Float = dp * context.resources.displayMetrics.density
}
