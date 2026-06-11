package com.repository.listener.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class OverlayWidget(private val context: Context) {

    companion object {
        private const val TAG = "OverlayWidget"

        // Gruvbox Dark Medium palette
        const val GBX_BG = 0xF5282828.toInt()       // nearly opaque
        const val GBX_BG1 = 0xFF3c3836.toInt()
        const val GBX_BG2 = 0xFF504945.toInt()
        const val GBX_FG = 0xFFebdbb2.toInt()
        const val GBX_FG4 = 0xFFa89984.toInt()
        const val GBX_ORANGE = 0xFFfe8019.toInt()
        const val GBX_YELLOW = 0xFFd79921.toInt()
        const val GBX_RED = 0xFFfb4934.toInt()

        // Layout constants
        const val NUM_BARS = 28
        const val BAR_WIDTH_DP = 3f
        const val BAR_GAP_DP = 2f
        const val BAR_MAX_HEIGHT_DP = 36f
        const val PILL_HEIGHT_DP = 48f
        const val PILL_RADIUS_DP = 24f
        const val MIC_SIZE_DP = 24f

        // Window sizing -- tight around the pill to minimize touch-dead area
        const val PILL_FULL_WIDTH_DP = NUM_BARS * (BAR_WIDTH_DP + BAR_GAP_DP) - BAR_GAP_DP + MIC_SIZE_DP + 56f // ~218dp
        const val WINDOW_PAD_H_DP = 16f  // horizontal glow padding
        const val WINDOW_PAD_V_DP = 16f  // vertical glow padding

        // Drawing glow margin (within the view)
        const val GLOW_MARGIN_DP = 16f

        // Spectrogram interpolation
        const val BAR_ATTACK = 0.6f    // how fast bars rise (0-1, higher = faster)
        const val BAR_DECAY = 0.12f    // how fast bars fall (0-1, lower = smoother decay)
    }

    enum class State { HIDDEN, LOADING, EXPANDING, LISTENING, CONTRACTING, FINISHING, RESPONDING, FADING_OUT }

    var onCancelClicked: (() -> Unit)? = null

    @Volatile
    var state = State.HIDDEN
        private set

    // Floating message banner (for permission prompts etc.)
    @Volatile
    private var messageText: String? = null

    // Cancel button state
    private var cancelVisible = false
    private var cancelOpacity = 0f
    private val cancelHideRunnable = Runnable {
        cancelVisible = false
        cancelOpacity = 0f
        overlayView?.invalidate()
    }
    private val cancelFadeRunnable = object : Runnable {
        override fun run() {
            if (cancelOpacity < 1f) {
                cancelOpacity = (cancelOpacity + 0.1f).coerceAtMost(1f)
                overlayView?.invalidate()
                handler.postDelayed(this, 16)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: OverlayView? = null
    private var isAttached = false

    // Animation state
    private var pillWidthRatio = 0f
    private var widgetOpacity = 0f
    private var micOpacity = 0f
    private var specOpacity = 0f
    private var loadingAngle = 0f
    private var shadowPhase = 0f
    private var micGlow = 0f
    private var micGrowing = true

    // Audio bars: target values from audio processing, display values interpolated smoothly
    private val targetBars = FloatArray(NUM_BARS)
    private val displayBars = FloatArray(NUM_BARS)

    // TTS responding animation
    private var ttsLevel = 0f
    private var ttsTarget = 0f
    private var ttsPhase = 0f

    // Periodic update runnable (60fps render loop)
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (state == State.HIDDEN) return

            // Spin loading/finishing
            if (state == State.LOADING || state == State.FINISHING) {
                loadingAngle = (loadingAngle + 5f) % 360f
            }

            // Shadow pulse
            shadowPhase += 0.04f
            if (shadowPhase > 2f * PI.toFloat()) shadowPhase -= 2f * PI.toFloat()

            // Mic pulse
            if (state == State.LISTENING || state == State.EXPANDING) {
                if (micGrowing) {
                    micGlow += 0.04f
                    if (micGlow >= 1f) { micGlow = 1f; micGrowing = false }
                } else {
                    micGlow -= 0.04f
                    if (micGlow <= 0f) { micGlow = 0f; micGrowing = true }
                }
            }

            // Interpolate display bars toward target bars each frame
            for (i in 0 until NUM_BARS) {
                val target = targetBars[i]
                val current = displayBars[i]
                displayBars[i] = if (target > current) {
                    // Rising: fast attack
                    current + (target - current) * BAR_ATTACK
                } else {
                    // Falling: slow decay for smooth tail-off
                    current + (target - current) * BAR_DECAY
                }
            }

            // TTS responding animation: smooth level toward target, advance phase
            if (state == State.RESPONDING) {
                ttsLevel += (ttsTarget - ttsLevel) * 0.25f
                ttsPhase += 0.08f
                if (ttsPhase > 2f * PI.toFloat()) ttsPhase -= 2f * PI.toFloat()
            }

            overlayView?.invalidate()
            handler.postDelayed(this, 16)
        }
    }

    fun show() {
        handler.post {
            if (state != State.HIDDEN) return@post

            pillWidthRatio = 0f
            widgetOpacity = 0f
            micOpacity = 0f
            specOpacity = 0f
            loadingAngle = 0f
            micGlow = 0f
            targetBars.fill(0f)
            displayBars.fill(0f)

            ensureAttached()
            state = State.LOADING

            // Fade in
            animateValue(0f, 1f, 350) { widgetOpacity = it }

            // After 800ms, expand to pill
            handler.postDelayed({
                if (state == State.LOADING) {
                    state = State.EXPANDING
                    animateValue(0f, 1f, 400) { pillWidthRatio = it }
                    animateValue(0f, 1f, 300, startDelay = 100) { micOpacity = it }
                    animateValue(0f, 1f, 300, startDelay = 200) {
                        specOpacity = it
                        if (it >= 1f) {
                            state = State.LISTENING
                        }
                    }
                }
            }, 800)

            handler.post(tickRunnable)
            Log.i(TAG, "Overlay shown")
        }
    }

    fun startFinishing() {
        handler.post {
            if (state != State.LISTENING && state != State.EXPANDING) return@post

            state = State.CONTRACTING

            // Contract pill to circle
            animateValue(specOpacity, 0f, 150) { specOpacity = it }
            animateValue(micOpacity, 0f, 200) { micOpacity = it }
            animateValue(pillWidthRatio, 0f, 300) {
                pillWidthRatio = it
                if (it <= 0f) {
                    state = State.FINISHING
                }
            }
            Log.i(TAG, "Overlay finishing")
        }
    }

    fun hide() {
        handler.post {
            if (state == State.HIDDEN) return@post

            hideCancelButton()
            state = State.FADING_OUT
            animateValue(widgetOpacity, 0f, 350) {
                widgetOpacity = it
                if (it <= 0f) {
                    state = State.HIDDEN
                    handler.removeCallbacks(tickRunnable)
                    detach()
                    Log.i(TAG, "Overlay hidden")
                }
            }
        }
    }

    private fun showCancelButton() {
        cancelVisible = true
        cancelOpacity = 0f
        handler.removeCallbacks(cancelHideRunnable)
        handler.removeCallbacks(cancelFadeRunnable)
        handler.post(cancelFadeRunnable)
        handler.postDelayed(cancelHideRunnable, 3000)
    }

    private fun hideCancelButton() {
        cancelVisible = false
        cancelOpacity = 0f
        handler.removeCallbacks(cancelHideRunnable)
        handler.removeCallbacks(cancelFadeRunnable)
    }

    fun updateBars(newBars: FloatArray) {
        // Set target values; the render loop interpolates display bars toward these
        val count = min(newBars.size, NUM_BARS)
        for (i in 0 until count) {
            targetBars[i] = newBars[i].coerceIn(0f, 1f)
        }
    }

    fun startResponding() {
        handler.post {
            if (state != State.FINISHING && state != State.CONTRACTING) return@post

            state = State.RESPONDING
            ttsLevel = 0f
            ttsTarget = 0f
            ttsPhase = 0f
            Log.i(TAG, "Overlay responding")
        }
    }

    fun updateTtsLevel(level: Float) {
        ttsTarget = level.coerceIn(0f, 1f)
    }

    private fun animateValue(from: Float, to: Float, durationMs: Long, startDelay: Long = 0, onUpdate: (Float) -> Unit) {
        val animator = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            this.startDelay = startDelay
            interpolator = DecelerateInterpolator()
            addUpdateListener { onUpdate(it.animatedValue as Float) }
        }
        animator.start()
    }

    private fun ensureAttached() {
        if (isAttached) return

        val view = OverlayView(context)
        overlayView = view

        val params = WindowManager.LayoutParams(
            dpToPx(PILL_FULL_WIDTH_DP + WINDOW_PAD_H_DP * 2),
            dpToPx(PILL_HEIGHT_DP + WINDOW_PAD_V_DP * 2),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpToPx(20f)
        }

        windowManager.addView(view, params)
        isAttached = true
    }

    private fun detach() {
        if (!isAttached) return
        overlayView?.let {
            windowManager.removeView(it)
        }
        overlayView = null
        isAttached = false
    }

    /**
     * Show a floating message banner (e.g. permission prompt).
     * Displayed independently of the recording/response overlay states.
     */
    fun showMessage(text: String) {
        handler.post {
            messageText = text
            if (!isMessageAttached) {
                attachMessageView()
            }
            messageView?.invalidate()
        }
    }

    fun hideMessage() {
        handler.post {
            messageText = null
            detachMessageView()
        }
    }

    fun release() {
        handler.removeCallbacks(tickRunnable)
        state = State.HIDDEN
        detach()
        detachMessageView()
    }

    // Message banner overlay
    private var messageView: MessageBannerView? = null
    private var isMessageAttached = false

    private fun attachMessageView() {
        if (isMessageAttached) return
        val view = MessageBannerView(context)
        messageView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dpToPx(60f),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(40f)
        }

        windowManager.addView(view, params)
        isMessageAttached = true
    }

    private fun detachMessageView() {
        if (!isMessageAttached) return
        messageView?.let { windowManager.removeView(it) }
        messageView = null
        isMessageAttached = false
    }

    inner class MessageBannerView(context: Context) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GBX_BG
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GBX_ORANGE
            style = Paint.Style.STROKE
            strokeWidth = dpF(1.5f)
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GBX_FG
            textSize = dpF(13f)
            textAlign = Paint.Align.CENTER
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GBX_ORANGE
            textSize = dpF(16f)
            textAlign = Paint.Align.CENTER
        }

        private fun dpF(dp: Float): Float = dp * resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            val msg = messageText ?: return
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = dpF(16f)
            val r = dpF(12f)

            val rect = RectF(pad, dpF(4f), w - pad, h - dpF(4f))
            canvas.drawRoundRect(rect, r, r, bgPaint)
            canvas.drawRoundRect(rect, r, r, borderPaint)

            val cx = w / 2f
            val cy = h / 2f
            canvas.drawText("!", cx, cy - dpF(8f), iconPaint)
            canvas.drawText(msg, cx, cy + dpF(12f), textPaint)
        }
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    // Inner View class that does all the drawing
    inner class OverlayView(context: Context) : View(context) {

        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(1f)
            color = (GBX_BG2 and 0x00FFFFFF) or 0xA0000000.toInt()
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val spinnerTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(2f)
            strokeCap = Paint.Cap.ROUND
            color = (GBX_BG2 and 0x00FFFFFF) or 0x64000000.toInt()
        }
        private val spinnerArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(2.5f)
            strokeCap = Paint.Cap.ROUND
            color = GBX_FG
        }
        private val micOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val micFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val micDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ttsBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val xBtnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val xBtnLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        private val pillPath = Path()
        private val pillRect = RectF()
        private val spinnerRect = RectF()

        private fun dpF(dp: Float): Float = dp * resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            if (state == State.HIDDEN) return

            canvas.save()
            alpha = widgetOpacity.coerceIn(0f, 1f)

            val w = width.toFloat()
            val pillH = dpF(PILL_HEIGHT_DP)
            val pillR = dpF(PILL_RADIUS_DP)
            val glowM = dpF(GLOW_MARGIN_DP)

            val pillCy = glowM + pillH / 2f
            val fullPillW = dpF(NUM_BARS * (BAR_WIDTH_DP + BAR_GAP_DP) - BAR_GAP_DP + MIC_SIZE_DP + 40f)
            val circleW = pillH

            val currentW = circleW + (fullPillW - circleW) * pillWidthRatio
            val cx = w / 2f

            pillRect.set(cx - currentW / 2f, pillCy - pillH / 2f, cx + currentW / 2f, pillCy + pillH / 2f)

            // Pulsating glow
            val pulse = 0.5f + 0.5f * sin(shadowPhase)
            val baseR = pillH * 1.4f
            val glowR = baseR * (1f + 0.15f * pulse)
            val coreAlpha = (70 + 50 * pulse).toInt()
            glowPaint.shader = android.graphics.RadialGradient(
                cx, pillCy, glowR,
                intArrayOf(
                    Color.argb(coreAlpha, 80, 73, 69),
                    Color.argb((35 + 30 * pulse).toInt(), 60, 56, 54),
                    Color.argb(0, 40, 40, 40)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, pillCy, glowR, glowPaint)

            // Pill fill
            pillPath.reset()
            pillPath.addRoundRect(pillRect, pillR, pillR, Path.Direction.CW)
            pillPaint.color = GBX_BG
            pillPaint.style = Paint.Style.FILL
            canvas.drawPath(pillPath, pillPaint)

            // Pill border
            canvas.drawPath(pillPath, borderPaint)

            // Loading/finishing spinner
            if (state == State.LOADING || state == State.FINISHING) {
                val spinR = (pillH - dpF(18f)) / 2f
                spinnerRect.set(cx - spinR, pillCy - spinR, cx + spinR, pillCy + spinR)
                canvas.drawOval(spinnerRect, spinnerTrackPaint)
                canvas.drawArc(spinnerRect, loadingAngle, 100f, false, spinnerArcPaint)
            }
            // TTS radial circle spectrogram
            else if (state == State.RESPONDING) {
                val numRadialBars = 24
                val innerR = dpF(8f)
                val maxBarH = dpF(13f)

                for (i in 0 until numRadialBars) {
                    val angle = (2.0 * PI * i / numRadialBars).toFloat()
                    val v1 = 0.3f + 0.7f * abs(sin(angle * 2f + ttsPhase * 1.2f))
                    val v2 = 0.5f + 0.5f * abs(sin(angle * 5f + ttsPhase * 0.7f))
                    val variation = v1 * v2
                    val level = 0.2f + 0.8f * ttsLevel
                    var h = maxBarH * level * variation
                    h = max(dpF(1.5f), h)

                    val cosA = cos(angle)
                    val sinA = sin(angle)
                    val x1 = cx + innerR * cosA
                    val y1 = pillCy + innerR * sinA
                    val x2 = cx + (innerR + h) * cosA
                    val y2 = pillCy + (innerR + h) * sinA

                    val barAlpha = (0.3f + 0.7f * min(1f, h / maxBarH))
                    val alphaInt = (barAlpha * 255).toInt().coerceIn(0, 255)

                    ttsBarPaint.color = Color.argb(alphaInt, 254, 128, 25)
                    ttsBarPaint.strokeWidth = dpF(2.5f)
                    canvas.drawLine(x1, y1, x2, y2, ttsBarPaint)
                }
            }

            // Mic indicator
            if (micOpacity > 0.01f) {
                val micR = dpF(MIC_SIZE_DP / 2f)
                val micCx = pillRect.left + dpF(18f) + micR
                val micCy = pillCy

                // Outer glow ring
                micOuterPaint.style = Paint.Style.STROKE
                micOuterPaint.strokeWidth = dpF(2f)
                val outerAlpha = ((0.25f + 0.35f * micGlow) * micOpacity * 255).toInt().coerceIn(0, 255)
                micOuterPaint.color = Color.argb(outerAlpha, 235, 219, 178)
                canvas.drawCircle(micCx, micCy, micR * 0.83f, micOuterPaint)

                // Inner filled circle
                val innerR = micR * 0.5f
                val fillAlpha = ((0.7f + 0.3f * micGlow) * micOpacity * 255).toInt().coerceIn(0, 255)
                micFillPaint.color = Color.argb(fillAlpha, 254, 128, 25)
                micFillPaint.style = Paint.Style.FILL
                canvas.drawCircle(micCx, micCy, innerR, micFillPaint)

                // Bright center dot
                val dotAlpha = ((0.63f + 0.37f * micGlow) * micOpacity * 255).toInt().coerceIn(0, 255)
                micDotPaint.color = Color.argb(dotAlpha, 255, 255, 255)
                micDotPaint.style = Paint.Style.FILL
                canvas.drawCircle(micCx, micCy, dpF(2f), micDotPaint)
            }

            // Spectrogram bars (uses interpolated displayBars for smooth animation)
            if (specOpacity > 0.01f) {
                val barW = dpF(BAR_WIDTH_DP)
                val barGap = dpF(BAR_GAP_DP)
                val maxH = dpF(BAR_MAX_HEIGHT_DP) / 2f
                val mid = pillCy
                val micAreaW = dpF(MIC_SIZE_DP + 28f)
                val barsStartX = cx - dpF(NUM_BARS * (BAR_WIDTH_DP + BAR_GAP_DP) / 2f) + micAreaW / 2f

                for (i in 0 until NUM_BARS) {
                    val v = displayBars[i]
                    val halfH = max(dpF(1f), v * maxH)
                    val x = barsStartX + i * (barW + barGap)

                    val barAlpha = (specOpacity * 255).toInt().coerceIn(0, 255)

                    // Top bar (upward from center)
                    barPaint.shader = LinearGradient(
                        x, mid, x, mid - halfH,
                        Color.argb(barAlpha, 80, 73, 69),
                        Color.argb(barAlpha, 254, 128, 25),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRoundRect(x, mid - halfH, x + barW, mid, dpF(1.5f), dpF(1.5f), barPaint)

                    // Bottom bar (downward from center, mirrored)
                    barPaint.shader = LinearGradient(
                        x, mid, x, mid + halfH,
                        Color.argb(barAlpha, 80, 73, 69),
                        Color.argb(barAlpha, 254, 128, 25),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRoundRect(x, mid, x + barW, mid + halfH, dpF(1.5f), dpF(1.5f), barPaint)
                }
            }

            // Cancel X button overlay
            val xBtnR = dpF(12f)
            if ((state == State.LISTENING || state == State.FINISHING || state == State.RESPONDING) && cancelVisible) {
                drawXButton(canvas, cx, pillCy, xBtnR, cancelOpacity)
            }

            canvas.restore()
        }

        private fun drawXButton(canvas: Canvas, btnCx: Float, btnCy: Float, radius: Float, opacity: Float) {
            if (opacity < 0.01f) return
            val alphaInt = (opacity * 255).toInt().coerceIn(0, 255)
            xBtnBgPaint.color = GBX_BG2
            xBtnBgPaint.alpha = alphaInt
            xBtnLinePaint.color = GBX_FG
            xBtnLinePaint.strokeWidth = dpF(2f)
            xBtnLinePaint.alpha = alphaInt
            canvas.drawCircle(btnCx, btnCy, radius, xBtnBgPaint)
            val cross = radius * 0.45f
            canvas.drawLine(btnCx - cross, btnCy - cross, btnCx + cross, btnCy + cross, xBtnLinePaint)
            canvas.drawLine(btnCx + cross, btnCy - cross, btnCx - cross, btnCy + cross, xBtnLinePaint)
        }

        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_DOWN) return false

            val tx = event.x
            val ty = event.y
            val pillH = dpF(PILL_HEIGHT_DP)
            val glowM = dpF(GLOW_MARGIN_DP)
            val pillCy = glowM + pillH / 2f
            val cx = width / 2f
            val xBtnR = dpF(12f)
            val hitSlop = dpF(4f)

            when (state) {
                State.LISTENING, State.FINISHING, State.RESPONDING -> {
                    if (cancelVisible) {
                        val dist = kotlin.math.sqrt((tx - cx) * (tx - cx) + (ty - pillCy) * (ty - pillCy))
                        if (dist <= xBtnR + hitSlop) {
                            hideCancelButton()
                            onCancelClicked?.invoke()
                            return true
                        } else {
                            hideCancelButton()
                            return true
                        }
                    } else {
                        val hitR = if (state == State.LISTENING) {
                            val fullPillW = dpF(NUM_BARS * (BAR_WIDTH_DP + BAR_GAP_DP) - BAR_GAP_DP + MIC_SIZE_DP + 40f)
                            fullPillW / 2f
                        } else {
                            pillH / 2f
                        }
                        val dist = kotlin.math.sqrt((tx - cx) * (tx - cx) + (ty - pillCy) * (ty - pillCy))
                        if (dist <= hitR + hitSlop) {
                            showCancelButton()
                            return true
                        }
                    }
                }
                else -> {}
            }
            return false
        }

    }
}
