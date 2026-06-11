package com.repository.listener.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import com.repository.listener.network.CopilotCard
import com.repository.listener.network.CopilotChatDetail
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

/**
 * Renders a Copilot session into a clean, shareable A4 PDF: dark-on-white with
 * the orange/green/aqua brand accents preserved, monospace conversational body,
 * sans-serif bold headers, "Them"/"You" labelled lines, reply/note callout
 * blocks with left rules, *highlight* spans bolded, and full pagination with
 * page numbers and a generation-time footer.
 */
object CopilotPdfExporter {

    // A4 at 72dpi.
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f

    // Brand accents.
    private const val ORANGE = "#FE8019"
    private const val GREEN = "#98971A"
    private const val AQUA = "#689D6A"
    private const val INK = "#1D2021"        // near-black body text
    private const val MUTED = "#7C6F64"      // secondary/footer text
    private const val RULE = "#D5C4A1"       // hairline rules
    private const val CARD_BG = "#F4ECD8"    // subtle inset for callout blocks

    fun export(context: Context, detail: CopilotChatDetail): File {
        val pdf = PdfDocument()
        val ctx = RenderCtx(pdf)

        startPage(ctx)
        drawHeader(ctx, detail)

        val sorted = detail.turns.sortedBy { parseTs(it.ts) }
        for (turn in sorted) {
            val block = measureTurn(ctx, turn)
            if (ctx.y + block > PAGE_H - MARGIN - FOOTER_RESERVE && hasBodyContent(turn)) {
                newPage(ctx)
            }
            drawTurn(ctx, turn)
        }

        finishPage(ctx)

        val dir = File(context.cacheDir, "pdfs").apply { mkdirs() }
        val out = File(dir, "copilot-${safeId(detail.id)}-${System.currentTimeMillis()}.pdf")
        FileOutputStream(out).use { pdf.writeTo(it) }
        pdf.close()
        return out
    }

    // --- rendering context ---

    private const val FOOTER_RESERVE = 28f

    private class RenderCtx(val pdf: PdfDocument) {
        var page: PdfDocument.Page? = null
        var canvas: android.graphics.Canvas? = null
        var y = MARGIN
        var pageNo = 0
    }

    private fun startPage(ctx: RenderCtx) {
        ctx.pageNo += 1
        val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, ctx.pageNo).create()
        val page = ctx.pdf.startPage(info)
        ctx.page = page
        ctx.canvas = page.canvas
        ctx.canvas!!.drawColor(Color.WHITE)
        ctx.y = MARGIN
    }

    private fun finishPage(ctx: RenderCtx) {
        val page = ctx.page ?: return
        drawFooter(ctx)
        ctx.pdf.finishPage(page)
        ctx.page = null
        ctx.canvas = null
    }

    private fun newPage(ctx: RenderCtx) {
        finishPage(ctx)
        startPage(ctx)
    }

    // --- paints ---

    private fun textPaint(sizeSp: Float, colorHex: String, mono: Boolean, bold: Boolean): TextPaint {
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizeSp
            color = Color.parseColor(colorHex)
            // A shared PDF is read on screen/print, not a terminal -- use a clean,
            // highly readable proportional font, NOT monospace. Conversational
            // body uses a readable serif (Noto Serif); headers/labels use sans.
            // The `mono` flag now selects body-serif vs header-sans.
            typeface = if (mono) {
                Typeface.create(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            } else {
                Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }

    // --- header / footer ---

    private fun drawHeader(ctx: RenderCtx, detail: CopilotChatDetail) {
        val c = ctx.canvas!!
        val brand = textPaint(20f, ORANGE, mono = false, bold = true)
        c.drawText("Copilot", MARGIN, ctx.y + 16f, brand)
        ctx.y += 26f

        val titlePaint = textPaint(15f, INK, mono = false, bold = true)
        val title = detail.title.ifBlank { "Untitled session" }
        for (line in wrap(title, titlePaint, contentWidth())) {
            c.drawText(line, MARGIN, ctx.y + 12f, titlePaint)
            ctx.y += 18f
        }

        val metaPaint = textPaint(10f, MUTED, mono = false, bold = false)
        val started = formatFull(detail.startedAt)
        val meta = "$started  -  ${detail.turns.size} turns"
        c.drawText(meta, MARGIN, ctx.y + 10f, metaPaint)
        ctx.y += 18f

        // Thin rule beneath.
        val rule = Paint().apply { color = Color.parseColor(RULE); strokeWidth = 1f }
        c.drawLine(MARGIN, ctx.y, PAGE_W - MARGIN, ctx.y, rule)
        ctx.y += 16f
    }

    private fun drawFooter(ctx: RenderCtx) {
        val c = ctx.canvas ?: return
        val footPaint = textPaint(8f, MUTED, mono = false, bold = false)
        val gen = "Generated " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        c.drawText(gen, MARGIN, PAGE_H - 16f, footPaint)
        val pageLabel = "Page ${ctx.pageNo}"
        val w = footPaint.measureText(pageLabel)
        c.drawText(pageLabel, PAGE_W - MARGIN - w, PAGE_H - 16f, footPaint)
    }

    // --- turn rendering ---

    private fun hasBodyContent(turn: com.repository.listener.network.CopilotTurn): Boolean {
        return turn.interlocutorText.isNotBlank() || turn.wearerText.isNotBlank() || turn.cards.isNotEmpty()
    }

    private fun drawTurn(ctx: RenderCtx, turn: com.repository.listener.network.CopilotTurn) {
        val tsLabel = formatTime(turn.ts)
        if (tsLabel.isNotEmpty()) {
            val tsPaint = textPaint(8f, MUTED, mono = true, bold = false)
            ctx.canvas!!.drawText(tsLabel, MARGIN, ctx.y + 8f, tsPaint)
            ctx.y += 14f
        }

        if (turn.interlocutorText.isNotBlank()) {
            drawLabeledLine(ctx, "Them", AQUA, turn.interlocutorText.trim())
        }
        if (turn.wearerText.isNotBlank()) {
            drawLabeledLine(ctx, "You", GREEN, turn.wearerText.trim())
        }
        for (card in turn.cards) {
            drawCard(ctx, card)
        }
        ctx.y += 10f
    }

    private fun drawLabeledLine(ctx: RenderCtx, label: String, labelHex: String, text: String) {
        val labelPaint = textPaint(10f, labelHex, mono = false, bold = true)
        val bodyPaint = textPaint(11f, INK, mono = true, bold = false)

        ensureSpace(ctx, 14f)
        ctx.canvas!!.drawText(label, MARGIN, ctx.y + 10f, labelPaint)
        ctx.y += 14f

        val indent = MARGIN + 12f
        val avail = PAGE_W - MARGIN - indent
        for (line in wrap(text, bodyPaint, avail)) {
            ensureSpace(ctx, 14f)
            ctx.canvas!!.drawText(line, indent, ctx.y + 10f, bodyPaint)
            ctx.y += 14f
        }
        ctx.y += 4f
    }

    private fun drawCard(ctx: RenderCtx, card: CopilotCard) {
        val isReply = card.kind == "reply"
        val accentHex = if (isReply) GREEN else AQUA
        val bodyPaint = textPaint(if (isReply) 11f else 10f, INK, mono = true, bold = false)

        val raw = CopilotChatAdapter.stripWrappingQuotes(card.note)
        val display = if (isReply) "\u201C$raw\u201D" else raw
        val indent = MARGIN + 14f
        val avail = PAGE_W - MARGIN - indent - 6f
        val lines = wrapHighlighted(display, bodyPaint, avail)

        val lineH = 14f
        val padV = 8f
        val boldPaint = textPaint(bodyPaint.textSize, INK, mono = true, bold = true)
        val bg = Paint().apply { color = Color.parseColor(CARD_BG) }
        val accent = Paint().apply { color = Color.parseColor(accentHex) }

        // Draw the callout in page-sized chunks so a very long note paginates
        // cleanly across pages instead of overflowing off the bottom edge.
        var idx = 0
        while (idx < lines.size) {
            val limit = PAGE_H - MARGIN - FOOTER_RESERVE
            // How many lines fit in the remaining space on the current page.
            var remaining = ((limit - ctx.y - padV * 2) / lineH).toInt()
            if (remaining < 1) {
                newPage(ctx)
                remaining = ((limit - ctx.y - padV * 2) / lineH).toInt().coerceAtLeast(1)
            }
            val end = minOf(idx + remaining, lines.size)
            val chunk = lines.subList(idx, end)
            val chunkH = chunk.size * lineH + padV * 2

            val c = ctx.canvas!!
            val top = ctx.y
            c.drawRect(MARGIN, top, PAGE_W - MARGIN, top + chunkH, bg)
            c.drawRect(MARGIN, top, MARGIN + 4f, top + chunkH, accent)

            var ly = top + padV + 10f
            for (seg in chunk) {
                drawHighlightedLine(c, seg, indent, ly, bodyPaint, boldPaint)
                ly += lineH
            }
            ctx.y = top + chunkH
            idx = end
        }

        // Tiny dim rationale beneath the callout: smaller, lighter gray, italic.
        // No quotes, no highlight parsing. Skip entirely when blank.
        if (card.why.isNotBlank()) {
            val whyPaint = textPaint(9f, MUTED, mono = true, bold = false).apply {
                typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            }
            val whyLineH = 12f
            val whyIndent = MARGIN + 14f
            val whyAvail = PAGE_W - MARGIN - whyIndent - 6f
            ctx.y += 2f
            for (line in wrap(card.why.trim(), whyPaint, whyAvail)) {
                ensureSpace(ctx, whyLineH)
                ctx.canvas!!.drawText(line, whyIndent, ctx.y + 9f, whyPaint)
                ctx.y += whyLineH
            }
        }
        ctx.y += 6f
    }

    // --- highlight-aware drawing ---

    /** A wrapped line as a list of (text, bold) runs. */
    private data class Run(val text: String, val bold: Boolean)

    private fun drawHighlightedLine(
        c: android.graphics.Canvas,
        runs: List<Run>,
        x: Float,
        y: Float,
        normal: TextPaint,
        bold: TextPaint
    ) {
        var cx = x
        for (run in runs) {
            val p = if (run.bold) bold else normal
            c.drawText(run.text, cx, y, p)
            cx += p.measureText(run.text)
        }
    }

    /** Tokenize *highlight* markers into (text, bold) runs, stripping asterisks. */
    private fun tokenizeHighlights(input: String): List<Run> {
        val out = mutableListOf<Run>()
        var i = 0
        while (i < input.length) {
            val open = input.indexOf('*', i)
            if (open < 0) {
                if (i < input.length) out.add(Run(input.substring(i), false))
                break
            }
            val close = input.indexOf('*', open + 1)
            if (close < 0) {
                out.add(Run(input.substring(i), false))
                break
            }
            if (open > i) out.add(Run(input.substring(i, open), false))
            out.add(Run(input.substring(open + 1, close), true))
            i = close + 1
        }
        return out
    }

    /** Wrap text that may contain highlight markers; returns wrapped lines of runs. */
    private fun wrapHighlighted(text: String, paint: TextPaint, maxWidth: Float): List<List<Run>> {
        val runs = tokenizeHighlights(text)
        val lines = mutableListOf<MutableList<Run>>()
        var current = mutableListOf<Run>()
        var lineWidth = 0f
        lines.add(current)

        for (run in runs) {
            val words = run.text.split(Regex("(?<= )|(?= )"))
            for (word in words) {
                if (word.isEmpty()) continue
                val w = paint.measureText(word)
                if (lineWidth + w > maxWidth && lineWidth > 0f) {
                    current = mutableListOf()
                    lines.add(current)
                    lineWidth = 0f
                    if (word == " ") continue
                }
                current.add(Run(word, run.bold))
                lineWidth += w
            }
        }
        return lines
    }

    // --- measurement ---

    private fun measureTurn(ctx: RenderCtx, turn: com.repository.listener.network.CopilotTurn): Float {
        var h = 0f
        if (formatTime(turn.ts).isNotEmpty()) h += 14f
        val bodyPaint = textPaint(11f, INK, mono = true, bold = false)
        val indent = MARGIN + 12f
        val avail = PAGE_W - MARGIN - indent
        if (turn.interlocutorText.isNotBlank()) {
            h += 14f + wrap(turn.interlocutorText.trim(), bodyPaint, avail).size * 14f + 4f
        }
        if (turn.wearerText.isNotBlank()) {
            h += 14f + wrap(turn.wearerText.trim(), bodyPaint, avail).size * 14f + 4f
        }
        for (card in turn.cards) {
            val isReply = card.kind == "reply"
            val cp = textPaint(if (isReply) 11f else 10f, INK, mono = true, bold = false)
            val cAvail = PAGE_W - MARGIN - (MARGIN + 14f) - 6f
            val raw = CopilotChatAdapter.stripWrappingQuotes(card.note)
            val disp = if (isReply) "\u201C$raw\u201D" else raw
            h += wrapHighlighted(disp, cp, cAvail).size * 14f + 16f + 6f
            if (card.why.isNotBlank()) {
                val whyPaint = textPaint(9f, MUTED, mono = true, bold = false).apply {
                    typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                }
                val whyAvail = PAGE_W - MARGIN - (MARGIN + 14f) - 6f
                h += 2f + wrap(card.why.trim(), whyPaint, whyAvail).size * 12f
            }
        }
        return h + 10f
    }

    // --- helpers ---

    private fun ensureSpace(ctx: RenderCtx, needed: Float) {
        if (ctx.y + needed > PAGE_H - MARGIN - FOOTER_RESERVE) newPage(ctx)
    }

    private fun contentWidth(): Float = PAGE_W - MARGIN * 2

    /** Greedy word-wrap honoring explicit newlines; strips highlight markers for plain wrap. */
    private fun wrap(textRaw: String, paint: TextPaint, maxWidth: Float): List<String> {
        val text = textRaw.replace("*", "")
        val result = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) {
                result.add("")
                continue
            }
            var line = StringBuilder()
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                    result.add(line.toString())
                    line = StringBuilder(word)
                } else {
                    line = StringBuilder(candidate)
                }
            }
            if (line.isNotEmpty()) result.add(line.toString())
        }
        return result
    }

    private fun formatFull(ts: String): String {
        val millis = parseTs(ts)
        if (millis == 0L) return ts
        return SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.US).format(Date(millis))
    }

    /** Parse an ISO-8601 timestamp to epoch millis, mirroring the adapter's Instant.parse. */
    private fun parseTs(ts: String): Long = try {
        Instant.parse(ts).toEpochMilli()
    } catch (e: Exception) {
        0L
    }

    /** Short local time label (HH:mm), empty when unparseable. */
    private fun formatTime(ts: String): String {
        val millis = parseTs(ts)
        if (millis == 0L) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    }

    private fun safeId(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
}
