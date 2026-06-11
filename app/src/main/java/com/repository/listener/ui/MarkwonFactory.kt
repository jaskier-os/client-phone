package com.repository.listener.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import androidx.core.content.ContextCompat
import com.repository.listener.R
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock

object MarkwonFactory {

    @Volatile
    private var instance: Markwon? = null

    fun get(context: Context): Markwon {
        return instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(ctx: Context): Markwon {
        val bgHard = ContextCompat.getColor(ctx, R.color.gbx_bg0_hard)
        val bg1 = ContextCompat.getColor(ctx, R.color.gbx_bg1)
        val fg = ContextCompat.getColor(ctx, R.color.gbx_fg)
        val orange = ContextCompat.getColor(ctx, R.color.gbx_orange)
        val blue = ContextCompat.getColor(ctx, R.color.gbx_blue)
        val gray = ContextCompat.getColor(ctx, R.color.gbx_gray)

        return Markwon.builder(ctx)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create { builder ->
                builder
                    .tableBorderColor(bg1)
                    .tableHeaderRowBackgroundColor(bg1)
                    .tableCellPadding(dpToPx(ctx, 4))
                    .tableOddRowBackgroundColor(bgHard)
                    .tableEvenRowBackgroundColor(0x00000000)
            })
            .usePlugin(HtmlPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .codeTextColor(fg)
                        .codeBackgroundColor(bg1)
                        .codeBlockTextColor(fg)
                        .codeBlockBackgroundColor(bgHard)
                        .codeTypeface(Typeface.MONOSPACE)
                        .codeBlockTypeface(Typeface.MONOSPACE)
                        .codeTextSize(dpToPx(ctx, 13))
                        .codeBlockTextSize(dpToPx(ctx, 13))
                        .codeBlockMargin(dpToPx(ctx, 8))
                        .headingTextSizeMultipliers(floatArrayOf(1.4f, 1.25f, 1.15f, 1.05f, 1f, 1f))
                        .headingBreakHeight(0)
                        .blockQuoteColor(gray)
                        .blockQuoteWidth(dpToPx(ctx, 3))
                        .blockMargin(dpToPx(ctx, 12))
                        .linkColor(blue)
                        .isLinkUnderlined(true)
                }

                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    // Fenced code blocks: rounded background
                    val codeBlockFactory = builder.getFactory(FencedCodeBlock::class.java)
                    builder.setFactory(FencedCodeBlock::class.java) { config, props ->
                        val original = codeBlockFactory?.getSpans(config, props)
                        val spans = mutableListOf<Any>()
                        if (original != null) {
                            if (original is Array<*>) spans.addAll(original.filterNotNull())
                            else spans.add(original)
                        }
                        spans.add(CodeBlockBackgroundSpan(bgHard, dpToPx(ctx, 6).toFloat(), dpToPx(ctx, 8)))
                        spans.toTypedArray()
                    }

                    // Indented code blocks: same treatment
                    val indentedFactory = builder.getFactory(IndentedCodeBlock::class.java)
                    builder.setFactory(IndentedCodeBlock::class.java) { config, props ->
                        val original = indentedFactory?.getSpans(config, props)
                        val spans = mutableListOf<Any>()
                        if (original != null) {
                            if (original is Array<*>) spans.addAll(original.filterNotNull())
                            else spans.add(original)
                        }
                        spans.add(CodeBlockBackgroundSpan(bgHard, dpToPx(ctx, 6).toFloat(), dpToPx(ctx, 8)))
                        spans.toTypedArray()
                    }

                    // Headings: orange color
                    val headingFactory = builder.getFactory(Heading::class.java)
                    builder.setFactory(Heading::class.java) { config, props ->
                        val original = headingFactory?.getSpans(config, props)
                        val spans = mutableListOf<Any>()
                        if (original != null) {
                            if (original is Array<*>) spans.addAll(original.filterNotNull())
                            else spans.add(original)
                        }
                        spans.add(android.text.style.ForegroundColorSpan(orange))
                        spans.toTypedArray()
                    }
                }
            })
            .build()
    }

    private fun dpToPx(ctx: Context, dp: Int): Int {
        return android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            ctx.resources.displayMetrics
        ).toInt()
    }

    /**
     * Draws a rounded-rect background behind fenced/indented code blocks.
     * Implements both LineBackgroundSpan (for the background fill) and
     * LeadingMarginSpan (for inner padding).
     */
    private class CodeBlockBackgroundSpan(
        private val bgColor: Int,
        private val cornerRadius: Float,
        private val padding: Int
    ) : LineBackgroundSpan, LeadingMarginSpan {

        private val rect = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }

        override fun drawBackground(
            canvas: Canvas,
            paint: Paint,
            left: Int,
            right: Int,
            top: Int,
            baseline: Int,
            bottom: Int,
            text: CharSequence,
            start: Int,
            end: Int,
            lineNumber: Int
        ) {
            rect.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, this.paint)
        }

        override fun getLeadingMargin(first: Boolean): Int = padding

        override fun drawLeadingMargin(
            c: Canvas, p: Paint, x: Int, dir: Int,
            top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int,
            first: Boolean, layout: Layout
        ) {
            // no-op, margin is handled by getLeadingMargin
        }
    }
}
