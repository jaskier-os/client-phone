package com.repository.listener.ui

import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R
import com.repository.listener.ui.MarkwonFactory

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private var lastAnimatedPosition = -1

    class MessageViewHolder(
        val container: FrameLayout,
        val bubbleWrap: LinearLayout,
        val textView: TextView,
        val timestampView: TextView
    ) : RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val ctx = parent.context

        val textView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(color(ctx, R.color.gbx_fg))
        }

        val bubbleWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(textView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val timestampView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(color(ctx, R.color.gbx_gray))
            visibility = View.GONE
        }

        val outerWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(bubbleWrap)
            addView(timestampView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(ctx, 4) })
        }

        val container = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(ctx, 16), 0, dpToPx(ctx, 16), 0)
            addView(outerWrap, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        return MessageViewHolder(container, bubbleWrap, textView, timestampView)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val msg = messages[position]
        val ctx = holder.itemView.context
        val outerWrap = holder.bubbleWrap.parent as LinearLayout
        val outerLp = outerWrap.layoutParams as FrameLayout.LayoutParams
        val maxWidthUser = (holder.itemView.resources.displayMetrics.widthPixels * 0.80f).toInt()
        val maxWidthAssistant = (holder.itemView.resources.displayMetrics.widthPixels * 0.85f).toInt()

        // Margin: 4dp same role, 12dp role switch
        val prevRole = if (position > 0) messages[position - 1].role else null
        val topMargin = if (prevRole != null && prevRole != msg.role) dpToPx(ctx, 12) else dpToPx(ctx, 4)
        (holder.container.layoutParams as? RecyclerView.LayoutParams)?.topMargin = topMargin

        holder.timestampView.visibility = View.GONE

        when (msg.role) {
            ChatMessage.Role.USER -> {
                holder.textView.text = msg.text
                holder.textView.setTextColor(color(ctx, R.color.gbx_fg))
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                holder.bubbleWrap.setBackgroundResource(R.drawable.bg_bubble_user)
                holder.bubbleWrap.setPadding(dpToPx(ctx, 12), dpToPx(ctx, 8), dpToPx(ctx, 12), dpToPx(ctx, 8))
                holder.textView.maxWidth = maxWidthUser - dpToPx(ctx, 24)
                outerLp.gravity = Gravity.END
                holder.timestampView.gravity = Gravity.END
            }
            ChatMessage.Role.ASSISTANT -> {
                MarkwonFactory.get(ctx).setMarkdown(holder.textView, msg.text)
                holder.textView.movementMethod = LinkMovementMethod.getInstance()
                holder.textView.setTextColor(color(ctx, R.color.gbx_fg))
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                holder.textView.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                holder.bubbleWrap.setBackgroundResource(R.drawable.bg_bubble_assistant)
                holder.bubbleWrap.setPadding(dpToPx(ctx, 12), dpToPx(ctx, 8), dpToPx(ctx, 12), dpToPx(ctx, 8))
                holder.textView.maxWidth = maxWidthAssistant - dpToPx(ctx, 24)
                outerLp.gravity = Gravity.START
                holder.timestampView.gravity = Gravity.START
            }
            ChatMessage.Role.TOOL -> {
                holder.textView.text = msg.text
                holder.textView.setTextColor(color(ctx, R.color.gbx_aqua))
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                holder.bubbleWrap.setBackgroundResource(R.drawable.bg_bubble_tool)
                holder.bubbleWrap.setPadding(dpToPx(ctx, 12), dpToPx(ctx, 4), dpToPx(ctx, 12), dpToPx(ctx, 4))
                holder.textView.maxWidth = Int.MAX_VALUE
                outerLp.gravity = Gravity.CENTER_HORIZONTAL
            }
            ChatMessage.Role.SYSTEM -> {
                holder.textView.text = msg.text
                holder.textView.setTextColor(color(ctx, R.color.gbx_gray))
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                holder.bubbleWrap.background = null
                holder.bubbleWrap.setPadding(0, dpToPx(ctx, 4), 0, dpToPx(ctx, 4))
                holder.textView.maxWidth = Int.MAX_VALUE
                outerLp.gravity = Gravity.CENTER_HORIZONTAL
            }
            ChatMessage.Role.METADATA -> {
                holder.textView.text = msg.text
                holder.textView.setTextColor(color(ctx, R.color.gbx_gray))
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                holder.bubbleWrap.background = null
                holder.bubbleWrap.setPadding(0, dpToPx(ctx, 2), 0, dpToPx(ctx, 2))
                holder.textView.maxWidth = Int.MAX_VALUE
                outerLp.gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        outerWrap.layoutParams = outerLp

        // Animate new items
        if (position > lastAnimatedPosition) {
            val anim = AnimationUtils.loadAnimation(ctx, R.anim.slide_up_fade_in)
            holder.container.startAnimation(anim)
            lastAnimatedPosition = position
        }
    }

    override fun onViewRecycled(holder: MessageViewHolder) {
        super.onViewRecycled(holder)
        holder.container.clearAnimation()
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(text: String) {
        if (messages.isNotEmpty()) {
            messages.last().text = text
            notifyItemChanged(messages.size - 1)
        }
    }

    fun removeLoadingMessages() {
        for (i in messages.indices.reversed()) {
            if (messages[i].role == ChatMessage.Role.SYSTEM && messages[i].requestId == "loading") {
                messages.removeAt(i)
                notifyItemRemoved(i)
            }
        }
    }

    fun getMessages(): List<ChatMessage> = messages.toList()

    fun clear() {
        val size = messages.size
        messages.clear()
        lastAnimatedPosition = -1
        notifyItemRangeRemoved(0, size)
    }

    private fun dpToPx(ctx: android.content.Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), ctx.resources.displayMetrics
        ).toInt()
    }

    private fun color(ctx: android.content.Context, resId: Int): Int =
        ContextCompat.getColor(ctx, resId)
}
