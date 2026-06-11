package com.repository.listener.ui.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.repository.listener.audio.VoiceSegmentData

class SpectrogramView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val MS_PER_AMPLITUDE_SAMPLE = 100L
        private const val MIN_MS_PER_PX = 5L      // max zoom in
        private const val MAX_MS_PER_PX = 500L     // max zoom out
        private const val TIME_AXIS_HEIGHT_DP = 24f
        private const val WAVEFORM_PADDING_DP = 8f
    }

    // Data
    var amplitudeData: ShortArray = ShortArray(0)
        private set
    private var voiceSegments: List<VoiceSegmentData> = emptyList()
    private var totalDurationMs: Long = 0

    // View state
    private var msPerPx: Float = 50f  // default zoom: 50ms per pixel = 20px per second
    private var scrollOffsetMs: Float = 0f
    private var playbackPositionMs: Long = 0

    // Selection state
    var selectionStartMs: Long = -1
        private set
    var selectionEndMs: Long = -1
        private set
    var selectionMode = false
    private var isSelecting = false

    // Colors
    private val bgColor = Color.parseColor("#282828")
    private val waveformColor = Color.parseColor("#66FE8019") // orange at 40%
    private val voiceBandColor = Color.parseColor("#33D79921") // yellow at 20%
    private val voiceMarkerColor = Color.parseColor("#80D79921") // yellow at 50%
    private val playbackLineColor = Color.parseColor("#EBDBB2") // fg
    private val timeTextColor = Color.parseColor("#A89984") // gray
    private val timeAxisBgColor = Color.parseColor("#1D2021") // bg0_hard

    // Paints
    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = waveformColor
        style = Paint.Style.FILL
    }
    private val voiceBandPaint = Paint().apply {
        color = voiceBandColor
        style = Paint.Style.FILL
    }
    private val voiceBandTranscribingPaint = Paint().apply {
        color = Color.parseColor("#33CC241D") // red at 20%
        style = Paint.Style.FILL
    }
    private val voiceMarkerPaint = Paint().apply {
        color = voiceMarkerColor
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1f)
    }
    private val playbackPaint = Paint().apply {
        color = playbackLineColor
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
    }
    private val timeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = timeTextColor
        textSize = spToPx(12f)
        typeface = Typeface.MONOSPACE
    }
    private val timeAxisPaint = Paint().apply {
        color = timeAxisBgColor
        style = Paint.Style.FILL
    }
    private val bgPaint = Paint().apply {
        color = bgColor
        style = Paint.Style.FILL
    }
    private val selectionPaint = Paint().apply {
        color = Color.parseColor("#26EBDBB2") // white/fg at 15%
        style = Paint.Style.FILL
    }
    private val selectionBorderPaint = Paint().apply {
        color = Color.parseColor("#CCEBDBB2") // white/fg at 80%
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
    }
    private val selectionHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EBDBB2") // fg
        style = Paint.Style.FILL
    }
    private val HANDLE_WIDTH_DP = 12f
    private val HANDLE_TOUCH_DP = 32f // touch target wider than visual
    private var draggingHandle: Int = 0 // 0=none, 1=left, 2=right

    // Gesture handling
    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector

    // Interaction control
    var scrollEnabled = true
    var zoomEnabled = true

    // Callbacks (screenX/screenY are absolute screen coordinates for popup positioning)
    var onTap: ((positionMs: Long, screenX: Int, screenY: Int) -> Unit)? = null
    var onSeek: ((positionMs: Long) -> Unit)? = null

    private val timeAxisHeight = dpToPx(TIME_AXIS_HEIGHT_DP)
    private val waveformPadding = dpToPx(WAVEFORM_PADDING_DP)

    private var isScrubbing = false

    init {
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                if (selectionMode && selectionStartMs >= 0) {
                    // Check if touching a handle
                    val touchTarget = dpToPx(HANDLE_TOUCH_DP)
                    val leftX = msToX(selectionStartMs)
                    val rightX = msToX(selectionEndMs)
                    draggingHandle = when {
                        kotlin.math.abs(e.x - leftX) < touchTarget / 2 -> 1
                        kotlin.math.abs(e.x - rightX) < touchTarget / 2 -> 2
                        else -> 0
                    }
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (selectionMode && draggingHandle > 0) {
                    val ms = xToMs(e2.x).coerceIn(0, totalDurationMs)
                    if (draggingHandle == 1) {
                        selectionStartMs = minOf(ms, selectionEndMs - 500)
                    } else {
                        selectionEndMs = maxOf(ms, selectionStartMs + 500)
                    }
                    invalidate()
                    return true
                }
                // Drag-to-scrub: move caret to finger position
                val ms = xToMs(e2.x)
                if (ms in 0..totalDurationMs) {
                    isScrubbing = true
                    playbackPositionMs = ms
                    onSeek?.invoke(ms)
                    invalidate()
                }
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val ms = xToMs(e.x)
                if (ms in 0..totalDurationMs) {
                    val loc = IntArray(2)
                    getLocationOnScreen(loc)
                    onTap?.invoke(ms, loc[0] + e.x.toInt(), loc[1] + e.y.toInt())
                }
                return true
            }
        })

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!zoomEnabled) return false
                val focusMs = xToMs(detector.focusX)
                msPerPx /= detector.scaleFactor
                msPerPx = msPerPx.coerceIn(MIN_MS_PER_PX.toFloat(), MAX_MS_PER_PX.toFloat())
                // Adjust scroll to keep focus point stable
                scrollOffsetMs = focusMs - detector.focusX * msPerPx
                scrollOffsetMs = scrollOffsetMs.coerceIn(0f, maxOf(0f, totalDurationMs - visibleDurationMs()))
                invalidate()
                return true
            }
        })
    }

    fun setData(amplitude: ShortArray, segments: List<VoiceSegmentData>, durationMs: Long) {
        val isFirstLoad = amplitudeData.isEmpty() || totalDurationMs == 0L
        amplitudeData = amplitude
        voiceSegments = segments
        totalDurationMs = durationMs
        // Only reset zoom on first load (not on live refresh updates)
        if (isFirstLoad && width > 0 && durationMs > 0) {
            msPerPx = (durationMs.toFloat() / width).coerceAtLeast(MIN_MS_PER_PX.toFloat())
            scrollOffsetMs = 0f
        }
        invalidate()
    }

    fun resetZoom() {
        if (width > 0 && totalDurationMs > 0) {
            msPerPx = (totalDurationMs.toFloat() / width).coerceAtLeast(MIN_MS_PER_PX.toFloat())
            scrollOffsetMs = 0f
            invalidate()
        }
    }

    fun setPlaybackPosition(positionMs: Long) {
        playbackPositionMs = positionMs
        // Auto-scroll if playback position is off-screen
        val visibleStart = scrollOffsetMs.toLong()
        val visibleEnd = visibleStart + visibleDurationMs().toLong()
        if (positionMs < visibleStart || positionMs > visibleEnd) {
            scrollOffsetMs = (positionMs - visibleDurationMs() / 4).coerceIn(0f, maxOf(0f, totalDurationMs - visibleDurationMs()))
        }
        invalidate()
    }

    private var zoomManuallySet = false

    fun setZoomLevel(msPerPixel: Float) {
        msPerPx = msPerPixel.coerceIn(MIN_MS_PER_PX.toFloat(), MAX_MS_PER_PX.toFloat())
        zoomManuallySet = true
        invalidate()
    }

    fun setScrollPosition(offsetMs: Float) {
        scrollOffsetMs = offsetMs.coerceIn(0f, maxOf(0f, totalDurationMs - visibleDurationMs()))
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && totalDurationMs > 0 && !zoomManuallySet) {
            msPerPx = (totalDurationMs.toFloat() / w).coerceAtLeast(MIN_MS_PER_PX.toFloat())
        }
    }

    fun getVisibleRange(): Pair<Long, Long> {
        val start = scrollOffsetMs.toLong()
        val end = start + visibleDurationMs().toLong()
        return start to minOf(end, totalDurationMs)
    }

    fun findVoiceSegmentAt(ms: Long): VoiceSegmentData? {
        return voiceSegments.find { ms in it.startMs..it.endMs }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val waveformHeight = h - timeAxisHeight

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (amplitudeData.isEmpty() || totalDurationMs == 0L) return

        // Draw voice segment bands (red = transcribing, green = done)
        for (seg in voiceSegments) {
            val x1 = msToX(seg.startMs)
            val x2 = msToX(seg.endMs)
            if (x2 >= 0 && x1 <= w) {
                val paint = if (seg.transcription == "[transcribing...]") voiceBandTranscribingPaint else voiceBandPaint
                canvas.drawRect(
                    x1.coerceAtLeast(0f), 0f,
                    x2.coerceAtMost(w), h,
                    paint
                )
                // Voice start/end markers (full height)
                if (x1 in 0f..w) canvas.drawLine(x1, 0f, x1, h, voiceMarkerPaint)
                if (x2 in 0f..w) canvas.drawLine(x2, 0f, x2, h, voiceMarkerPaint)
            }
        }

        // Draw waveform
        val maxAmp = amplitudeData.maxOrNull()?.toFloat()?.coerceAtLeast(1f) ?: 1f
        val centerY = waveformHeight / 2
        val halfHeight = (waveformHeight / 2) - waveformPadding

        val path = Path()
        var pathStarted = false
        val startSample = (scrollOffsetMs / MS_PER_AMPLITUDE_SAMPLE).toInt().coerceAtLeast(0)
        val endSample = ((scrollOffsetMs + visibleDurationMs()) / MS_PER_AMPLITUDE_SAMPLE).toInt()
            .coerceAtMost(amplitudeData.size - 1)

        // Draw mirrored waveform (top half)
        for (i in startSample..endSample) {
            val ms = i * MS_PER_AMPLITUDE_SAMPLE
            val x = msToX(ms)
            val amp = amplitudeData[i].toFloat() / maxAmp
            val y = centerY - amp * halfHeight

            if (!pathStarted) {
                path.moveTo(x, centerY)
                path.lineTo(x, y)
                pathStarted = true
            } else {
                path.lineTo(x, y)
            }
        }
        // Mirror back (bottom half)
        for (i in endSample downTo startSample) {
            val ms = i * MS_PER_AMPLITUDE_SAMPLE
            val x = msToX(ms)
            val amp = amplitudeData[i].toFloat() / maxAmp
            val y = centerY + amp * halfHeight
            path.lineTo(x, y)
        }
        if (pathStarted) {
            path.close()
            canvas.drawPath(path, waveformPaint)
        }

        // Draw selection range with drag handles
        if (selectionStartMs >= 0 && selectionEndMs > selectionStartMs) {
            val sx1 = msToX(selectionStartMs)
            val sx2 = msToX(selectionEndMs)
            if (sx2 >= 0 && sx1 <= w) {
                // Selection fill
                canvas.drawRect(sx1.coerceAtLeast(0f), 0f, sx2.coerceAtMost(w), waveformHeight, selectionPaint)
                // Border lines
                if (sx1 in 0f..w) canvas.drawLine(sx1, 0f, sx1, waveformHeight, selectionBorderPaint)
                if (sx2 in 0f..w) canvas.drawLine(sx2, 0f, sx2, waveformHeight, selectionBorderPaint)
                // Drag handles (rounded rectangles at edges)
                val hw = dpToPx(HANDLE_WIDTH_DP) / 2
                val handleH = waveformHeight * 0.4f
                val handleTop = (waveformHeight - handleH) / 2
                if (sx1 in 0f..w) {
                    canvas.drawRoundRect(sx1 - hw, handleTop, sx1 + hw, handleTop + handleH, hw, hw, selectionHandlePaint)
                    // Arrow indicator (small triangle pointing left)
                    val arrowSize = dpToPx(4f)
                    val cy = waveformHeight / 2
                    val path = android.graphics.Path().apply {
                        moveTo(sx1 - arrowSize, cy)
                        lineTo(sx1, cy - arrowSize)
                        lineTo(sx1, cy + arrowSize)
                        close()
                    }
                    canvas.drawPath(path, bgPaint)
                }
                if (sx2 in 0f..w) {
                    canvas.drawRoundRect(sx2 - hw, handleTop, sx2 + hw, handleTop + handleH, hw, hw, selectionHandlePaint)
                    // Arrow indicator (small triangle pointing right)
                    val arrowSize = dpToPx(4f)
                    val cy = waveformHeight / 2
                    val path = android.graphics.Path().apply {
                        moveTo(sx2 + arrowSize, cy)
                        lineTo(sx2, cy - arrowSize)
                        lineTo(sx2, cy + arrowSize)
                        close()
                    }
                    canvas.drawPath(path, bgPaint)
                }
            }
        }

        // Draw playback position line
        val playbackX = msToX(playbackPositionMs)
        if (playbackX in 0f..w) {
            canvas.drawLine(playbackX, 0f, playbackX, waveformHeight, playbackPaint)
        }

        // Draw time axis
        canvas.drawRect(0f, waveformHeight, w, h, timeAxisPaint)
        drawTimeLabels(canvas, waveformHeight, w)
    }

    private fun drawTimeLabels(canvas: Canvas, top: Float, width: Float) {
        // Choose interval based on zoom level
        val intervalMs = when {
            msPerPx < 10 -> 5_000L    // 5s
            msPerPx < 30 -> 15_000L   // 15s
            msPerPx < 60 -> 30_000L   // 30s
            msPerPx < 120 -> 60_000L  // 1m
            msPerPx < 300 -> 300_000L // 5m
            else -> 600_000L           // 10m
        }

        val startMs = (scrollOffsetMs / intervalMs).toLong() * intervalMs
        val endMs = scrollOffsetMs.toLong() + visibleDurationMs().toLong()

        var ms = startMs
        while (ms <= endMs) {
            val x = msToX(ms)
            if (x in 0f..width) {
                val label = formatTime(ms)
                val textWidth = timeTextPaint.measureText(label)
                canvas.drawText(label, x - textWidth / 2, top + timeAxisHeight - dpToPx(6f), timeTextPaint)
            }
            ms += intervalMs
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    fun setSelectionAroundPosition(centerMs: Long, radiusMs: Long = 5000) {
        selectionStartMs = maxOf(0, centerMs - radiusMs)
        selectionEndMs = minOf(totalDurationMs, centerMs + radiusMs)
        invalidate()
    }

    fun clearSelection() {
        selectionStartMs = -1
        selectionEndMs = -1
        draggingHandle = 0
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            isSelecting = false
            draggingHandle = 0
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun msToX(ms: Long): Float = (ms - scrollOffsetMs) / msPerPx
    private fun xToMs(x: Float): Long = (x * msPerPx + scrollOffsetMs).toLong()
    private fun visibleDurationMs(): Float = width * msPerPx

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
    private fun spToPx(sp: Float): Float = sp * resources.displayMetrics.scaledDensity
}
