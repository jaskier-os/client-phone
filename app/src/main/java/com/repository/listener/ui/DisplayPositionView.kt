package com.repository.listener.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.repository.listener.R

/**
 * Custom view for adjusting display content position on glasses.
 * Renders a frame (glasses display area) with a draggable content rectangle inside.
 * The content rectangle can only move vertically within the frame bounds.
 */
class DisplayPositionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        // Container (full display area) aspect ratio from Hi Rokid reference
        private const val CONTAINER_W = 313f
        private const val CONTAINER_H = 356f

        // Content area dimensions relative to container width
        private const val CONTENT_WIDTH_RATIO = 0.92f  // 92% of container width
        private const val CONTENT_ASPECT_W = 285f
        private const val CONTENT_ASPECT_H = 240f

        private const val BORDER_WIDTH_DP = 1f
    }

    /** Normalized Y position: 0.0 = top, 1.0 = bottom, 0.5 = center */
    var normalizedY: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Called continuously during drag with the current normalized Y value */
    var onPositionChanged: ((Float) -> Unit)? = null

    private val borderPx = BORDER_WIDTH_DP * resources.displayMetrics.density

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderPx
        color = context.getColor(R.color.gbx_bg2)
    }

    private val contentFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = (0x66 shl 24) or (context.getColor(R.color.gbx_orange) and 0x00FFFFFF)
    }

    private val contentBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderPx
        color = context.getColor(R.color.gbx_orange)
    }

    private val disabledOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x40000000
    }

    private val frameRect = RectF()
    private val contentRect = RectF()
    private var contentWidth = 0f
    private var contentHeight = 0f
    private var maxContentY = 0f

    private var isDragging = false
    private var dragTouchOffsetY = 0f
    private var lastCallbackTime = 0L
    private val callbackIntervalMs = 100L

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width * CONTAINER_H / CONTAINER_W).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = borderPx / 2f
        frameRect.set(inset, inset, w - inset, h - inset)

        val frameW = frameRect.width()
        contentWidth = frameW * CONTENT_WIDTH_RATIO
        contentHeight = contentWidth * CONTENT_ASPECT_H / CONTENT_ASPECT_W
        maxContentY = frameRect.height() - contentHeight
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw frame border
        canvas.drawRect(frameRect, framePaint)

        // Calculate content rect position
        val contentLeft = frameRect.left + (frameRect.width() - contentWidth) / 2f
        val contentTop = frameRect.top + normalizedY * maxContentY
        contentRect.set(
            contentLeft,
            contentTop,
            contentLeft + contentWidth,
            contentTop + contentHeight
        )

        // Draw content rectangle
        canvas.drawRect(contentRect, contentFillPaint)
        canvas.drawRect(contentRect, contentBorderPaint)

        // Disabled overlay
        if (!isEnabled) {
            canvas.drawRect(frameRect, disabledOverlayPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (contentRect.contains(event.x, event.y)) {
                    isDragging = true
                    dragTouchOffsetY = event.y - contentRect.top
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                val newTop = (event.y - dragTouchOffsetY - frameRect.top).coerceIn(0f, maxContentY)
                normalizedY = if (maxContentY > 0f) newTop / maxContentY else 0f
                val now = System.currentTimeMillis()
                if (now - lastCallbackTime >= callbackIntervalMs) {
                    lastCallbackTime = now
                    onPositionChanged?.invoke(normalizedY)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onPositionChanged?.invoke(normalizedY)
                    return true
                }
                return false
            }
        }
        return super.onTouchEvent(event)
    }
}
