package com.repository.listener.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Mic-driven amplitude bars. Caller pushes raw 16-bit PCM samples via
 * [pushSamples]; the view computes per-band peak amplitudes over the chunk
 * and animates them with a decay envelope. No FFT -- just block-peak across
 * time-domain bins -- enough to "look like" a spectrogram for UI feedback.
 *
 * Used by TwoWayTranslationActivity to show that the mic is actively being
 * sampled while the wearer/other party speaks.
 */
class MicLevelBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BAR_COUNT = 32
        private const val DECAY = 0.82f
        // Ceiling for normalization. 16-bit PCM peak is 32767 but speech rarely
        // exceeds half-scale; clamp lower so quiet voice still draws meaningful bars.
        private const val PEAK_CEILING = 12000f
    }

    private val barLevels = FloatArray(BAR_COUNT)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF9800.toInt()
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    fun pushSamples(samples: ShortArray) {
        if (samples.isEmpty()) return
        val perBand = max(1, samples.size / BAR_COUNT)
        for (b in 0 until BAR_COUNT) {
            val from = b * perBand
            val to = min(samples.size, from + perBand)
            if (from >= to) continue
            var peak = 0
            for (i in from until to) {
                val a = abs(samples[i].toInt())
                if (a > peak) peak = a
            }
            val v = (peak / PEAK_CEILING).coerceIn(0f, 1f)
            barLevels[b] = max(barLevels[b] * DECAY, v)
        }
        postInvalidateOnAnimation()
    }

    /** Decay-only tick for when audio dries up; keeps bars from looking frozen. */
    fun decay() {
        var any = false
        for (b in 0 until BAR_COUNT) {
            barLevels[b] *= DECAY
            if (barLevels[b] > 0.005f) any = true else barLevels[b] = 0f
        }
        if (any) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val gap = w / BAR_COUNT * 0.25f
        val barW = (w / BAR_COUNT) - gap
        for (b in 0 until BAR_COUNT) {
            val level = barLevels[b]
            val barH = max(2f, h * level)
            val x = b * (barW + gap) + gap * 0.5f
            rect.set(x, h - barH, x + barW, h)
            canvas.drawRoundRect(rect, barW * 0.4f, barW * 0.4f, barPaint)
        }
    }
}
