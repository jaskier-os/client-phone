package com.repository.listener.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R
import com.repository.listener.network.CopilotCard
import com.repository.listener.network.CopilotTurn
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

/**
 * Read-only RecyclerView adapter rendering Copilot session turns.
 *
 * Each turn is a single item: a group head timestamp, the interlocutor line as an
 * assistant bubble (START), the wearer line as a user bubble (END), then any cards
 * as left-accent callout rows (reply = green quote-to-say, note = aqua info-to-know).
 * Blank wearer/interlocutor lines are skipped. *single-asterisk* highlight markers in
 * card notes become bold spans (asterisks stripped).
 */
class CopilotChatAdapter : RecyclerView.Adapter<CopilotChatAdapter.TurnViewHolder>() {

    private val turns = mutableListOf<CopilotTurn>()

    fun submit(newTurns: List<CopilotTurn>) {
        turns.clear()
        turns.addAll(newTurns)
        notifyDataSetChanged()
    }

    class TurnViewHolder(val group: LinearLayout) : RecyclerView.ViewHolder(group)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TurnViewHolder {
        val ctx = parent.context
        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
        }
        return TurnViewHolder(group)
    }

    override fun onBindViewHolder(holder: TurnViewHolder, position: Int) {
        val ctx = holder.group.context
        val turn = turns[position]
        holder.group.removeAllViews()

        // Group head timestamp
        val ts = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(color(ctx, R.color.gbx_gray))
            text = formatTime(turn.ts)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        holder.group.addView(ts, lp().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(ctx, 6)
        })

        // Interlocutor line -> assistant bubble (START)
        if (turn.interlocutorText.isNotBlank()) {
            holder.group.addView(
                bubble(ctx, turn.interlocutorText, isWearer = false),
                lp().apply { topMargin = dp(ctx, 4) }
            )
        }

        // Wearer line -> user bubble (END)
        if (turn.wearerText.isNotBlank()) {
            holder.group.addView(
                bubble(ctx, turn.wearerText, isWearer = true),
                lp().apply { topMargin = dp(ctx, 4) }
            )
        }

        // Cards
        for (card in turn.cards) {
            holder.group.addView(cardRow(ctx, card), lp().apply { topMargin = dp(ctx, 8) })
        }
    }

    override fun getItemCount(): Int = turns.size

    private fun bubble(ctx: Context, text: String, isWearer: Boolean): View {
        val tv = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(color(ctx, R.color.gbx_fg))
            this.text = text
        }
        val screenW = ctx.resources.displayMetrics.widthPixels
        val maxW = if (isWearer) (screenW * 0.80f).toInt() else (screenW * 0.85f).toInt()
        tv.maxWidth = maxW - dp(ctx, 24)

        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(if (isWearer) R.drawable.bg_bubble_user else R.drawable.bg_bubble_assistant)
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
            addView(tv)
        }

        val outer = FrameLayout(ctx)
        val flp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = if (isWearer) Gravity.END else Gravity.START }
        outer.addView(wrap, flp)
        return outer
    }

    private fun cardRow(ctx: Context, card: CopilotCard): View {
        val isReply = card.kind == "reply"
        val accentColor = color(ctx, if (isReply) R.color.gbx_green else R.color.gbx_aqua)

        val accent = View(ctx).apply { setBackgroundColor(accentColor) }
        val accentLp = LinearLayout.LayoutParams(dp(ctx, 4), LinearLayout.LayoutParams.MATCH_PARENT)

        val body = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isReply) 14f else 13f)
            setTextColor(color(ctx, R.color.gbx_fg))
            // Strip any quotes the model already embedded so reply lines get
            // exactly one pair of quotes (the renderer owns the quoting).
            val parsed = parseHighlights(stripWrappingQuotes(card.note))
            text = if (isReply) {
                SpannableStringBuilder("\u201C").append(parsed).append("\u201D")
            } else {
                parsed
            }
        }

        // The note plus its tiny rationale stack vertically inside the weighted
        // column; the accent rule runs the full height beside them.
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(body)
        }

        // Tiny dim rationale beneath the note. No quotes, no highlight parsing.
        // Skip entirely when blank so there is no empty gap.
        if (card.why.isNotBlank()) {
            val why = TextView(ctx).apply {
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(color(ctx, R.color.gbx_gray))
                text = card.why.trim()
            }
            column.addView(why, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 4) })
        }

        val columnLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(ctx, 12)
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(color(ctx, R.color.gbx_bg1))
                cornerRadius = dp(ctx, 12).toFloat()
            }
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            addView(accent, accentLp)
            addView(column, columnLp)
        }
        return row
    }

    private fun formatTime(iso: String): String {
        return try {
            val instant = Instant.parse(iso)
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(instant.toEpochMilli()))
        } catch (e: Exception) {
            iso
        }
    }

    private fun lp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(ctx: Context, v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    private fun color(ctx: Context, resId: Int): Int = ContextCompat.getColor(ctx, resId)

    companion object {
        /**
         * Remove a single pair of quotes that the model embedded around a reply
         * line so the renderer can add exactly one pair. Handles two shapes:
         *   1. The whole string is wrapped: "..." or "...".
         *   2. A leading quote opens a sentence that closes before a trailing
         *      attribution/continuation, e.g.  "What does X cost?" --
         * In case 2 we strip the leading quote and its matching closing quote
         * (the last quote of the same family), preserving any trailing text.
         * Straight and curly quotes are both recognised.
         */
        fun stripWrappingQuotes(raw: String): String {
            var s = raw.trim()
            if (s.length < 2) return s

            // Case 1: fully wrapped.
            run {
                val first = s.first()
                val last = s.last()
                val fullPair = (first == '"' && last == '"') ||
                    (first == '\u201C' && last == '\u201D')
                if (fullPair) return s.substring(1, s.length - 1).trim()
            }

            // Case 2: leading quote + matching closing quote before a trailer.
            val first = s.first()
            val closer = when (first) {
                '"' -> '"'
                '\u201C' -> '\u201D'
                else -> null
            }
            if (closer != null) {
                val closeIdx = s.lastIndexOf(closer)
                if (closeIdx > 0) {
                    val inner = s.substring(1, closeIdx)
                    val trailer = s.substring(closeIdx + 1)
                    s = (inner + trailer).trim()
                }
            }
            return s
        }

        /**
         * Parse *single-asterisk* highlight spans into bold, stripping the asterisks.
         * Unmatched asterisks are left intact.
         */
        fun parseHighlights(raw: String): CharSequence {
            val sb = SpannableStringBuilder()
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '*') {
                    val close = raw.indexOf('*', i + 1)
                    if (close > i + 1) {
                        val inner = raw.substring(i + 1, close)
                        val start = sb.length
                        sb.append(inner)
                        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = close + 1
                        continue
                    }
                }
                sb.append(c)
                i++
            }
            return sb
        }
    }
}
