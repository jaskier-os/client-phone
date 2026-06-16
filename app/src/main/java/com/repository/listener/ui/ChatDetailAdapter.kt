package com.repository.listener.ui

import android.graphics.Color
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R
import com.repository.listener.ui.MarkwonFactory
import java.util.UUID

class ChatDetailAdapter(
    private val onMessageClicked: (Int, ChatMessage) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<ChatDetailAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private var lastAnimatedPosition = -1
    var isSelectionMode = false
        private set
    val selectedPositions = mutableSetOf<Int>()

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

        val prevRole = if (position > 0) messages[position - 1].role else null
        val topMargin = if (prevRole != null && prevRole != msg.role) dpToPx(ctx, 12) else dpToPx(ctx, 4)
        (holder.container.layoutParams as? RecyclerView.LayoutParams)?.topMargin = topMargin

        holder.timestampView.visibility = View.GONE

        // Clean up wrench icon from recycled TOOL ViewHolders
        holder.bubbleWrap.findViewWithTag<ImageView>("wrench")?.let {
            it.clearAnimation()
            holder.bubbleWrap.removeView(it)
        }
        holder.bubbleWrap.orientation = LinearLayout.VERTICAL
        holder.bubbleWrap.gravity = Gravity.NO_GRAVITY

        when (msg.role) {
            ChatMessage.Role.USER -> {
                holder.textView.setTextIsSelectable(false)
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
                holder.textView.setTextIsSelectable(true)
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
                holder.textView.setTextIsSelectable(false)
                holder.textView.text = msg.text
                holder.textView.setTextColor(color(ctx, R.color.gbx_aqua))
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                holder.bubbleWrap.setBackgroundResource(R.drawable.bg_bubble_tool)
                holder.bubbleWrap.setPadding(dpToPx(ctx, 12), dpToPx(ctx, 4), dpToPx(ctx, 12), dpToPx(ctx, 4))
                holder.textView.maxWidth = Int.MAX_VALUE
                outerLp.gravity = Gravity.CENTER_HORIZONTAL

                // Wrench icon with rotation animation
                holder.bubbleWrap.orientation = LinearLayout.HORIZONTAL
                holder.bubbleWrap.gravity = Gravity.CENTER_VERTICAL
                val wrench = ImageView(ctx).apply {
                    tag = "wrench"
                    setImageResource(R.drawable.ic_wrench)
                    setColorFilter(color(ctx, R.color.gbx_aqua))
                    layoutParams = LinearLayout.LayoutParams(dpToPx(ctx, 14), dpToPx(ctx, 14)).apply {
                        marginEnd = dpToPx(ctx, 6)
                    }
                }
                holder.bubbleWrap.addView(wrench, 0)
                if (msg.isAnimating) {
                    wrench.startAnimation(AnimationUtils.loadAnimation(ctx, R.anim.rotate_continuous))
                } else {
                    wrench.clearAnimation()
                }
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

        // Selection highlight
        if (selectedPositions.contains(position)) {
            holder.container.setBackgroundColor(color(ctx, R.color.gbx_bg2))
        } else {
            holder.container.setBackgroundColor(Color.TRANSPARENT)
        }

        holder.container.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener

            if (isSelectionMode) {
                toggleSelection(pos)
            } else {
                onMessageClicked(pos, messages[pos])
            }
        }

        holder.container.setOnLongClickListener {
            val pos = holder.adapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false

            if (!isSelectionMode) {
                isSelectionMode = true
                selectedPositions.add(pos)
                notifyItemChanged(pos)
                onSelectionChanged(selectedPositions.size)
            }
            true
        }

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

    fun submitMessages(newMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        lastAnimatedPosition = newMessages.size - 1
        notifyDataSetChanged()
    }

    fun getSelectedMessages(): List<ChatMessage> {
        return selectedPositions.sorted().mapNotNull { pos ->
            messages.getOrNull(pos)
        }
    }

    fun clearSelection() {
        val changed = selectedPositions.toList()
        selectedPositions.clear()
        isSelectionMode = false
        changed.forEach { notifyItemChanged(it) }
        onSelectionChanged(0)
    }

    fun updateLastMessage(text: String) {
        if (messages.isNotEmpty()) {
            messages.last().text = text
            notifyItemChanged(messages.size - 1)
        }
    }

    /**
     * Update or create a TOOL message.
     * @param upsertId Unique key for dedup (toolCallId for agent tools, requestId for device tools)
     * @param parentRequestId The parent request ID (used by stopToolAnimation to match)
     * @param toolName Display text (e.g. "web_search: quantum computing")
     */
    fun upsertToolMessage(upsertId: String, parentRequestId: String, toolName: String): Int {
        val existing = messages.indexOfLast { it.role == ChatMessage.Role.TOOL && it.id == upsertId }
        if (existing >= 0) {
            messages[existing].text = toolName
            notifyItemChanged(existing)
            return existing
        }
        val msg = ChatMessage(
            id = upsertId,
            role = ChatMessage.Role.TOOL,
            text = toolName,
            requestId = parentRequestId,
            isAnimating = true
        )
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
        return messages.size - 1
    }

    /**
     * Update the existing ASSISTANT message for a requestId, or append one if
     * none exists yet. Prevents a duplicate assistant bubble when the final
     * response broadcast arrives for a turn that streaming (or a history reload)
     * already rendered. requestId must be non-empty to match; an empty id always
     * appends. Returns the affected position.
     */
    fun upsertAssistantMessage(requestId: String, text: String): Int {
        if (requestId.isNotEmpty()) {
            val existing = messages.indexOfLast {
                it.role == ChatMessage.Role.ASSISTANT && it.requestId == requestId
            }
            if (existing >= 0) {
                messages[existing].text = text
                notifyItemChanged(existing)
                return existing
            }
        }
        val msg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatMessage.Role.ASSISTANT,
            text = text,
            requestId = requestId
        )
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
        return messages.size - 1
    }

    /** Stop animation on all TOOL messages for a given requestId. */
    fun stopToolAnimation(requestId: String) {
        for (i in messages.indices) {
            if (messages[i].role == ChatMessage.Role.TOOL && messages[i].requestId == requestId && messages[i].isAnimating) {
                messages[i].isAnimating = false
                notifyItemChanged(i)
            }
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

    private fun toggleSelection(position: Int) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
        notifyItemChanged(position)

        if (selectedPositions.isEmpty()) {
            isSelectionMode = false
        }
        onSelectionChanged(selectedPositions.size)
    }

    private fun dpToPx(ctx: android.content.Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), ctx.resources.displayMetrics
        ).toInt()
    }

    private fun color(ctx: android.content.Context, resId: Int): Int =
        ContextCompat.getColor(ctx, resId)
}
