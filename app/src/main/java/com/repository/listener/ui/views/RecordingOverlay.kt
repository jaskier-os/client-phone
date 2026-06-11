package com.repository.listener.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.repository.listener.util.LogCollector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RecordingOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "RecordingOverlay"

        // Colors (Gruvbox)
        private const val COLOR_BG = 0xFF1D2021.toInt()        // gbx_bg0_hard
        private const val COLOR_RED = 0xFFCC241D.toInt()        // gbx_red
        private const val COLOR_GREEN = 0xFF98971A.toInt()      // gbx_green
        private const val COLOR_FG = 0xFFEBDBB2.toInt()         // gbx_fg
        private const val COLOR_GRAY = 0xFFA89984.toInt()       // gbx_gray

        // Circle
        private const val CIRCLE_BASE_RADIUS_DP = 41f
        private const val CIRCLE_AMP_RADIUS_DP = 30f
        private const val WAVE_INNER_ALPHA = 0x80
        private const val WAVE_OUTER_ALPHA = 0x40

        // Entry animation phases
        private const val ENTRY_DURATION_MS = 300L

        // Timer
        private const val DOT_RADIUS_DP = 4f
        private const val DOT_BLINK_MS = 500L

        // Slide text
        private const val ARROW_OSCILLATE_DP = 6f
        private const val ARROW_STEP_MS = 250L

        // Lock
        private const val LOCK_SIZE_DP = 36f
    }

    enum class State { IDLE, RECORDING, LOCKED, CANCELLED, COMPLETED }

    interface Listener {
        fun onRecordEnd()
        fun onRecordCancel()
    }

    var listener: Listener? = null
    var state = State.IDLE
        private set

    // Circle state
    private var amplitude = 0f
    private var circleScale = 0f
    private var slideToCancelProgress = 1f
    private var lockProgress = 0f
    private var circleColorProgress = 0f  // 0=red, 1=green

    // Positioning
    private var circleCenterX = 0f
    private var circleCenterY = 0f

    // Timing
    private var entryStartTime = 0L
    private var recordingStartTime = 0L
    private var lastFrameTime = 0L

    // Arrow oscillation
    private var arrowOffset = 0f
    private var arrowDirection = 1

    // Dot blink
    private var dotVisible = true
    private var lastDotToggle = 0L

    // Timer
    private var lastTimerText = ""

    // Paints
    private val bgPaint = Paint().apply { color = COLOR_BG; style = Paint.Style.FILL }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val waveInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val waveOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = dp(1.5f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val timerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_FG; textSize = sp(12f); typeface = Typeface.MONOSPACE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_RED; style = Paint.Style.FILL
    }
    private val slideTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GRAY; textSize = sp(13f); typeface = Typeface.DEFAULT
    }
    private val cancelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_RED; textSize = sp(13f); typeface = Typeface.DEFAULT
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GRAY; style = Paint.Style.STROKE
        strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_FG; style = Paint.Style.STROKE
        strokeWidth = dp(1.5f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val cancelXPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GRAY; style = Paint.Style.STROKE
        strokeWidth = dp(1.5f); strokeCap = Paint.Cap.ROUND
    }

    // Paths
    private val micBodyPath = Path()
    private val micDetailPath = Path()
    private val sendPath = Path()
    private val arrowPath = Path()
    private val lockPath = Path()

    // Choreographer for 60fps rendering
    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (state == State.IDLE) return
            val now = System.currentTimeMillis()
            updateAnimations(now)
            invalidate()
            choreographer.postFrameCallback(this)
        }
    }

    init {
        buildMicPaths()
        buildSendPath()
    }

    // --- Public API ---

    fun show(anchorRight: Int, anchorCenterY: Int) {
        if (state != State.IDLE) return
        state = State.RECORDING
        // Convert window coordinates to local view coordinates
        val myLoc = IntArray(2)
        getLocationInWindow(myLoc)
        circleCenterX = anchorRight.toFloat() - myLoc[0] - dp(20f)
        circleCenterY = height / 2f  // center vertically in overlay
        entryStartTime = System.currentTimeMillis()
        recordingStartTime = entryStartTime
        lastDotToggle = entryStartTime
        circleScale = 0f
        slideToCancelProgress = 1f
        lockProgress = 0f
        circleColorProgress = 0f
        amplitude = 0f
        arrowOffset = 0f
        arrowDirection = 1
        dotVisible = true
        lastTimerText = "00:00"
        visibility = VISIBLE
        LogCollector.i(TAG, "Recording overlay shown")
        choreographer.postFrameCallback(frameCallback)
    }

    fun hide() {
        state = State.IDLE
        visibility = GONE
        choreographer.removeFrameCallback(frameCallback)
        LogCollector.i(TAG, "Recording overlay hidden")
    }

    fun setAmplitude(amp: Float) {
        amplitude = amp.coerceIn(0f, 1f)
    }

    fun setSlideToCancelProgress(progress: Float) {
        slideToCancelProgress = progress
    }

    fun setLockProgress(progress: Float) {
        lockProgress = progress.coerceIn(0f, 1f)
    }

    fun setLocked() {
        if (state != State.RECORDING) return
        state = State.LOCKED
        LogCollector.i(TAG, "Recording locked (hands-free)")
    }

    fun isLocked(): Boolean = state == State.LOCKED

    fun reset() {
        state = State.IDLE
        visibility = GONE
        choreographer.removeFrameCallback(frameCallback)
    }

    // --- Touch handling for lock mode ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (state != State.LOCKED) return false
        if (event.action == MotionEvent.ACTION_UP) {
            val dx = event.x - circleCenterX
            val dy = event.y - circleCenterY
            val circleRadius = dp(CIRCLE_BASE_RADIUS_DP)
            if (dx * dx + dy * dy <= circleRadius * circleRadius * 1.5f) {
                // Tap on circle = send
                listener?.onRecordEnd()
                return true
            }
            // Tap on timer area (left side) = cancel
            if (event.x < width * 0.3f) {
                listener?.onRecordCancel()
                return true
            }
        }
        return true
    }

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (state == State.IDLE) return

        val w = width.toFloat()
        val h = height.toFloat()

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Calculate entry progress
        val entryElapsed = System.currentTimeMillis() - entryStartTime
        val entryProgress = min(1f, entryElapsed.toFloat() / ENTRY_DURATION_MS)

        // 3-phase scale: 0-0.5 grow to 1.0, 0.5-0.75 shrink to 0.9, 0.75-1.0 grow to 1.0
        val targetScale = when {
            entryProgress < 0.5f -> entryProgress / 0.5f
            entryProgress < 0.75f -> 1f - 0.1f * ((entryProgress - 0.5f) / 0.25f)
            else -> 0.9f + 0.1f * ((entryProgress - 0.75f) / 0.25f)
        }
        circleScale = targetScale

        // Apply slide-to-cancel scale
        val slideScale = 0.7f + slideToCancelProgress.coerceIn(0f, 1f) * 0.3f
        val finalScale = circleScale * slideScale

        // Circle alpha (fades when cancelling)
        var circleAlpha = 1f
        if (slideToCancelProgress < 0.7f) {
            circleAlpha = max(0f, slideToCancelProgress / 0.7f)
        }

        // Draw waveform rings
        val baseRadius = dp(CIRCLE_BASE_RADIUS_DP)
        val ampRadius = dp(CIRCLE_AMP_RADIUS_DP) * amplitude
        val mainRadius = (baseRadius + ampRadius) * finalScale

        val circleColor = lerpColor(COLOR_RED, COLOR_GREEN, circleColorProgress)

        // Outer wave
        waveOuterPaint.color = withAlpha(circleColor, (WAVE_OUTER_ALPHA * circleAlpha).toInt())
        val outerWaveRadius = mainRadius + dp(14f) * amplitude * finalScale
        canvas.drawCircle(circleCenterX, circleCenterY, outerWaveRadius, waveOuterPaint)

        // Inner wave
        waveInnerPaint.color = withAlpha(circleColor, (WAVE_INNER_ALPHA * circleAlpha).toInt())
        val innerWaveRadius = mainRadius + dp(7f) * amplitude * finalScale
        canvas.drawCircle(circleCenterX, circleCenterY, innerWaveRadius, waveInnerPaint)

        // Main circle
        circlePaint.color = withAlpha(circleColor, (255 * circleAlpha).toInt())
        canvas.drawCircle(circleCenterX, circleCenterY, mainRadius, circlePaint)

        // Center icon (mic or send)
        val iconScale = finalScale * 1.8f
        val iconAlpha = (255 * circleAlpha).toInt()
        canvas.save()
        canvas.translate(circleCenterX, circleCenterY)
        canvas.scale(iconScale, iconScale)
        if (state == State.LOCKED) {
            iconStrokePaint.alpha = iconAlpha
            iconStrokePaint.strokeWidth = dp(2f) / iconScale
            canvas.drawPath(sendPath, iconStrokePaint)
        } else {
            iconFillPaint.alpha = iconAlpha
            canvas.drawPath(micBodyPath, iconFillPaint)
            iconStrokePaint.alpha = iconAlpha
            iconStrokePaint.strokeWidth = dp(1.5f) / iconScale
            canvas.drawPath(micDetailPath, iconStrokePaint)
        }
        canvas.restore()

        // Timer (left side)
        drawTimer(canvas, h)

        // Slide-to-cancel text (center)
        if (state == State.RECORDING) {
            drawSlideText(canvas, w, h)
        }

        // Lock icon (above circle, only during recording)
        if (state == State.RECORDING && lockProgress > 0f) {
            drawLockIcon(canvas)
        }

        // Cancel X in locked mode
        if (state == State.LOCKED) {
            drawCancelX(canvas, h)
        }
    }

    private fun drawTimer(canvas: Canvas, h: Float) {
        val elapsed = System.currentTimeMillis() - recordingStartTime
        val totalSec = (elapsed / 1000).toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        lastTimerText = String.format("%02d:%02d", min, sec)

        val textX = dp(16f)
        val textY = h / 2f + timerPaint.textSize / 3f

        // Blinking red dot
        val dotX = textX + dp(DOT_RADIUS_DP)
        val dotY = h / 2f
        if (dotVisible) {
            canvas.drawCircle(dotX, dotY, dp(DOT_RADIUS_DP), dotPaint)
        }

        // Timer text
        canvas.drawText(lastTimerText, dotX + dp(DOT_RADIUS_DP) + dp(8f), textY, timerPaint)
    }

    private fun drawSlideText(canvas: Canvas, w: Float, h: Float) {
        val text = if (slideToCancelProgress < 0.6f) "Cancel" else "Slide to cancel"
        val paint = if (slideToCancelProgress < 0.6f) cancelTextPaint else slideTextPaint

        val textWidth = paint.measureText(text)
        val centerX = w / 2f
        val textX = centerX - textWidth / 2f
        val textY = h / 2f + paint.textSize / 3f

        // Alpha based on entry + cancel progress
        val alpha = min(1f, slideToCancelProgress.coerceIn(0f, 1f))
        paint.alpha = (255 * alpha).toInt()
        canvas.drawText(text, textX, textY, paint)

        // Arrow (left of text)
        if (slideToCancelProgress >= 0.6f) {
            val arrowX = textX - dp(20f) + arrowOffset
            val arrowY = h / 2f
            arrowPath.reset()
            arrowPath.moveTo(arrowX + dp(8f), arrowY - dp(8f))
            arrowPath.lineTo(arrowX, arrowY)
            arrowPath.lineTo(arrowX + dp(8f), arrowY + dp(8f))
            arrowPaint.alpha = (255 * alpha).toInt()
            canvas.drawPath(arrowPath, arrowPaint)
        }
    }

    private fun drawLockIcon(canvas: Canvas) {
        val lockSize = dp(LOCK_SIZE_DP)
        val lockCenterX = circleCenterX
        val lockBottom = circleCenterY - dp(CIRCLE_BASE_RADIUS_DP) - dp(8f)
        val lockTop = lockBottom - lockSize * lockProgress

        if (lockProgress <= 0f) return

        lockPaint.alpha = (255 * lockProgress).toInt()

        canvas.save()
        canvas.translate(lockCenterX, lockTop + lockSize / 2f)

        val bodyW = dp(12f)
        val bodyH = dp(10f)
        val shackleR = dp(5f)

        // Shackle (semicircle)
        lockPath.reset()
        lockPath.moveTo(-shackleR, 0f)
        lockPath.arcTo(-shackleR, -shackleR * 2, shackleR, 0f, 180f, -180f, false)
        canvas.drawPath(lockPath, lockPaint)

        // Body (rect)
        canvas.drawRect(-bodyW / 2f, 0f, bodyW / 2f, bodyH, lockPaint)

        canvas.restore()
    }

    private fun drawCancelX(canvas: Canvas, h: Float) {
        // Small "X" to the right of the timer
        val timerEndX = dp(16f) + dp(DOT_RADIUS_DP) * 2 + dp(8f) + timerPaint.measureText(lastTimerText)
        val xCenterX = timerEndX + dp(20f)
        val xCenterY = h / 2f
        val xSize = dp(6f)
        canvas.drawLine(xCenterX - xSize, xCenterY - xSize, xCenterX + xSize, xCenterY + xSize, cancelXPaint)
        canvas.drawLine(xCenterX - xSize, xCenterY + xSize, xCenterX + xSize, xCenterY - xSize, cancelXPaint)
    }

    // --- Animation updates ---

    private fun updateAnimations(now: Long) {
        // Dot blink
        if (now - lastDotToggle >= DOT_BLINK_MS) {
            dotVisible = !dotVisible
            lastDotToggle = now
        }

        // Arrow oscillation
        if (state == State.RECORDING) {
            val arrowStep = dp(3f)
            val maxOffset = dp(ARROW_OSCILLATE_DP)
            arrowOffset += arrowStep * arrowDirection * 0.06f
            if (abs(arrowOffset) >= maxOffset) {
                arrowDirection *= -1
                arrowOffset = maxOffset * arrowDirection.toFloat().coerceIn(-1f, 1f)
            }
        }

        // Color transition for lock
        if (state == State.LOCKED && circleColorProgress < 1f) {
            circleColorProgress = min(1f, circleColorProgress + 0.067f)  // ~150ms at 60fps
        }

        // Smooth amplitude (dampen sudden changes)
        // amplitude is set directly from outside, but we could smooth here if needed
    }

    // --- Path builders ---

    private fun buildMicPaths() {
        val s = dp(1f)
        // Body (filled rounded rect)
        micBodyPath.reset()
        micBodyPath.addRoundRect(-3f * s, -8f * s, 3f * s, 3f * s, 3f * s, 3f * s, Path.Direction.CW)
        // Details (stroked: arc, stand, base)
        micDetailPath.reset()
        micDetailPath.moveTo(-5.3f * s, 0f)
        micDetailPath.cubicTo(-5.3f * s, 3.5f * s, -3f * s, 5.1f * s, 0f, 5.1f * s)
        micDetailPath.cubicTo(3f * s, 5.1f * s, 5.3f * s, 3.5f * s, 5.3f * s, 0f)
        micDetailPath.moveTo(0f, 5.1f * s)
        micDetailPath.lineTo(0f, 8f * s)
        micDetailPath.moveTo(-3f * s, 8f * s)
        micDetailPath.lineTo(3f * s, 8f * s)
    }

    private fun buildSendPath() {
        // Send arrow centered at origin, ~24dp viewport
        val s = dp(1f)
        sendPath.reset()
        sendPath.moveTo(-10f * s, 0f)
        sendPath.lineTo(10f * s, 0f)
        sendPath.moveTo(4f * s, -6f * s)
        sendPath.lineTo(10f * s, 0f)
        sendPath.lineTo(4f * s, 6f * s)
    }

    // --- Utility ---

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val ta = t.coerceIn(0f, 1f)
        val ra = Color.red(a) + ((Color.red(b) - Color.red(a)) * ta).toInt()
        val ga = Color.green(a) + ((Color.green(b) - Color.green(a)) * ta).toInt()
        val ba2 = Color.blue(a) + ((Color.blue(b) - Color.blue(a)) * ta).toInt()
        return Color.argb(255, ra, ga, ba2)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }
}
