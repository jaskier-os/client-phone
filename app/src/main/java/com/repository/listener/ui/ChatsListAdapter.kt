package com.repository.listener.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R
import com.repository.listener.network.ChatSummary
import com.repository.listener.network.CopilotSummary
import com.repository.listener.network.LiveSession
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

sealed class ChatListItem {
    data class Conversation(val chat: ChatSummary) : ChatListItem()
    data class RemoteSession(val session: LiveSession) : ChatListItem()
    data class RemoteControlSession(
        val sessionId: String,
        val workDir: String,
        val status: String,
        val lastMessage: String?,
        val startedAt: Long,
        val sessionName: String? = null,
        val turning: Boolean = false,
        // True when the AI just finished a turn and the user hasn't yet opened
        // the row. Drives the green dot color and folder-chip badge count.
        val unread: Boolean = false
    ) : ChatListItem()
    data class DateHeader(val label: String) : ChatListItem()
    data class FolderHeader(val label: String) : ChatListItem()
    data class CopilotConversation(val summary: CopilotSummary) : ChatListItem()
}

class ChatsListAdapter(
    private val onChatClick: (ChatSummary) -> Unit,
    private val onChatLongClick: (ChatSummary) -> Unit,
    private val onSessionLongClick: (LiveSession) -> Unit,
    private val onRcSessionClick: ((ChatListItem.RemoteControlSession) -> Unit)? = null,
    private val onCopilotClick: ((CopilotSummary) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_CONVERSATION = 0
        private const val TYPE_REMOTE_SESSION = 1
        private const val TYPE_RC_SESSION = 2
        private const val TYPE_DATE_HEADER = 3
        private const val TYPE_FOLDER_HEADER = 4
        private const val TYPE_COPILOT = 5
    }

    private val items = mutableListOf<ChatListItem>()
    var pinnedIds: Set<String> = emptySet()
    var respondingChatIds: Set<String> = emptySet()
    var thinkingRcSessionIds: Set<String> = emptySet()

    class ChatViewHolder(
        val container: LinearLayout,
        val aliveIndicator: View,
        val pinIcon: ImageView,
        val titleView: TextView,
        val deviceIcon: ImageView,
        val clockIcon: ImageView,
        val timeText: TextView,
        val thinkingChip: TextView
    ) : RecyclerView.ViewHolder(container)

    class SessionViewHolder(
        val container: LinearLayout,
        val aliveIndicator: View,
        val titleView: TextView,
        val subtitleView: TextView,
        var pulseAnimator: ObjectAnimator? = null
    ) : RecyclerView.ViewHolder(container)

    class RcSessionViewHolder(
        val container: LinearLayout,
        val statusDot: View,
        val titleView: TextView,
        val subtitleView: TextView,
        val thinkingChip: TextView,
        var pulseAnimator: ObjectAnimator? = null
    ) : RecyclerView.ViewHolder(container)

    class CopilotViewHolder(
        val container: LinearLayout,
        val titleView: TextView,
        val copilotChip: TextView,
        val timeText: TextView
    ) : RecyclerView.ViewHolder(container)

    class DateHeaderViewHolder(
        val container: LinearLayout,
        val textView: TextView
    ) : RecyclerView.ViewHolder(container)

    class FolderHeaderViewHolder(
        val container: LinearLayout,
        val textView: TextView
    ) : RecyclerView.ViewHolder(container)

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ChatListItem.Conversation -> TYPE_CONVERSATION
            is ChatListItem.RemoteSession -> TYPE_REMOTE_SESSION
            is ChatListItem.RemoteControlSession -> TYPE_RC_SESSION
            is ChatListItem.DateHeader -> TYPE_DATE_HEADER
            is ChatListItem.FolderHeader -> TYPE_FOLDER_HEADER
            is ChatListItem.CopilotConversation -> TYPE_COPILOT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_REMOTE_SESSION -> createSessionHolder(parent)
            TYPE_RC_SESSION -> createRcSessionHolder(parent)
            TYPE_DATE_HEADER -> createDateHeaderHolder(parent)
            TYPE_FOLDER_HEADER -> createFolderHeaderHolder(parent)
            TYPE_COPILOT -> createCopilotHolder(parent)
            else -> createChatHolder(parent)
        }
    }

    private fun createDateHeaderHolder(parent: ViewGroup): DateHeaderViewHolder {
        val ctx = parent.context
        val dp = { value: Int -> dpToPx(ctx, value) }
        val text = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
            isAllCaps = true
            setTextColor(color(ctx, R.color.gbx_gray))
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(16), dp(14), dp(16), dp(6))
            isClickable = false
            isFocusable = false
            addView(text)
        }
        return DateHeaderViewHolder(container, text)
    }

    private fun createFolderHeaderHolder(parent: ViewGroup): FolderHeaderViewHolder {
        val ctx = parent.context
        val dp = { value: Int -> dpToPx(ctx, value) }
        val text = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(color(ctx, R.color.gbx_purple))
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(16), dp(18), dp(16), dp(4))
            isClickable = false
            isFocusable = false
            addView(text)
        }
        return FolderHeaderViewHolder(container, text)
    }

    private fun createChatHolder(parent: ViewGroup): ChatViewHolder {
        val ctx = parent.context
        val dp = { value: Int -> dpToPx(ctx, value) }

        val aliveIndicator = View(ctx).apply {
            val size = dp(8)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(8)
                gravity = Gravity.CENTER_VERTICAL
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(ctx, R.color.gbx_green))
            }
            visibility = View.GONE
        }

        val pinIcon = ImageView(ctx).apply {
            val size = dp(14)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(4)
                gravity = Gravity.CENTER_VERTICAL
            }
            setImageResource(R.drawable.ic_pin_filled)
            setColorFilter(color(ctx, R.color.gbx_orange))
            visibility = View.GONE
        }

        val titleView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(color(ctx, R.color.gbx_fg))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val thinkingChip = createThinkingChip(ctx)

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(aliveIndicator)
            addView(pinIcon)
            addView(titleView)
            addView(thinkingChip)
        }

        // Subtitle row with device icon + clock icon + time text
        val deviceIcon = ImageView(ctx).apply {
            val size = dp(14)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(6)
                gravity = Gravity.CENTER_VERTICAL
            }
            setColorFilter(color(ctx, R.color.gbx_gray))
        }

        val clockIcon = ImageView(ctx).apply {
            val size = dp(12)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(4)
                gravity = Gravity.CENTER_VERTICAL
            }
            setImageResource(R.drawable.ic_clock)
            setColorFilter(color(ctx, R.color.gbx_gray))
        }

        val timeText = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(color(ctx, R.color.gbx_gray))
            maxLines = 1
        }

        val subtitleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
            addView(deviceIcon)
            addView(clockIcon)
            addView(timeText)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            isFocusable = true
            isLongClickable = true
            setBackgroundResource(R.drawable.bg_chat_card)
            addView(titleRow)
            addView(subtitleRow)
        }

        return ChatViewHolder(container, aliveIndicator, pinIcon, titleView, deviceIcon, clockIcon, timeText, thinkingChip)
    }

    private fun createCopilotHolder(parent: ViewGroup): CopilotViewHolder {
        val ctx = parent.context
        val dp = { value: Int -> dpToPx(ctx, value) }

        val titleView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(color(ctx, R.color.gbx_fg))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val copilotChip = createCopilotChip(ctx)

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(titleView)
            addView(copilotChip)
        }

        val clockIcon = ImageView(ctx).apply {
            val size = dp(12)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(4)
                gravity = Gravity.CENTER_VERTICAL
            }
            setImageResource(R.drawable.ic_clock)
            setColorFilter(color(ctx, R.color.gbx_gray))
        }

        val timeText = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(color(ctx, R.color.gbx_gray))
            maxLines = 1
        }

        val subtitleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
            addView(clockIcon)
            addView(timeText)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_chat_card)
            addView(titleRow)
            addView(subtitleRow)
        }

        return CopilotViewHolder(container, titleView, copilotChip, timeText)
    }

    private fun createSessionHolder(parent: ViewGroup): SessionViewHolder {
        val ctx = parent.context
        val dp = { value: Int -> dpToPx(ctx, value) }

        // Aqua left border
        val leftBorder = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(color(ctx, R.color.gbx_aqua))
        }

        // Alive indicator (pulsing green dot)
        val aliveIndicator = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(8)
                gravity = Gravity.CENTER_VERTICAL
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(ctx, R.color.gbx_green))
            }
        }

        val titleView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(color(ctx, R.color.gbx_aqua))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(aliveIndicator)
            addView(titleView)
        }

        val subtitleView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(color(ctx, R.color.gbx_gray))
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }

        val textContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(12), dp(16), dp(16), dp(16))
            addView(titleRow)
            addView(subtitleView)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
            isClickable = true
            isFocusable = true
            isLongClickable = true
            setBackgroundResource(R.drawable.bg_chat_card)
            clipToOutline = true
            addView(leftBorder)
            addView(textContent)
        }

        return SessionViewHolder(container, aliveIndicator, titleView, subtitleView)
    }

    private fun createRcSessionHolder(parent: ViewGroup): RcSessionViewHolder {
        val ctx = parent.context
        val dp = { value: Int -> dpToPx(ctx, value) }

        // Purple left border (distinct from aqua used for remote sessions)
        val leftBorder = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(color(ctx, R.color.gbx_purple))
        }

        // Status dot (pulsing green when active)
        val statusDot = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(8)
                gravity = Gravity.CENTER_VERTICAL
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(ctx, R.color.gbx_green))
            }
        }

        val titleView = TextView(ctx).apply {
            id = R.id.rc_session_title
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(color(ctx, R.color.gbx_purple))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val terminalIcon = TextView(ctx).apply {
            text = ">_"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(color(ctx, R.color.gbx_purple))
            setPadding(0, 0, dpToPx(ctx, 8), 0)
        }

        val rcThinkingChip = createThinkingChip(ctx)

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(statusDot)
            addView(terminalIcon)
            addView(titleView)
            addView(rcThinkingChip)
        }

        val subtitleView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(color(ctx, R.color.gbx_gray))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }

        val textContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(12), dp(16), dp(16), dp(16))
            addView(titleRow)
            addView(subtitleView)
        }

        val container = LinearLayout(ctx).apply {
            id = R.id.rc_session_root
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_chat_card)
            clipToOutline = true
            addView(leftBorder)
            addView(textContent)
        }

        return RcSessionViewHolder(container, statusDot, titleView, subtitleView, rcThinkingChip)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ChatListItem.Conversation -> {
                val h = holder as ChatViewHolder
                h.titleView.text = item.chat.firstUserMessage ?: "(no messages)"
                h.aliveIndicator.visibility = if (!item.chat.closed) View.VISIBLE else View.GONE

                // Pin indicator
                h.pinIcon.visibility = if (pinnedIds.contains(item.chat.id)) View.VISIBLE else View.GONE

                // Thinking chip
                h.thinkingChip.visibility = if (respondingChatIds.contains(item.chat.id)) View.VISIBLE else View.GONE

                // Device type icon
                val deviceRes = when (item.chat.deviceType.lowercase()) {
                    "desktop", "pc" -> R.drawable.ic_device_desktop
                    "glasses" -> R.drawable.ic_device_glasses
                    else -> R.drawable.ic_device_phone
                }
                h.deviceIcon.setImageResource(deviceRes)

                // Time text
                h.timeText.text = formatRelativeTime(item.chat.lastActivityAt)

                h.container.setOnClickListener { onChatClick(item.chat) }
                h.container.setOnLongClickListener {
                    onChatLongClick(item.chat)
                    true
                }
            }
            is ChatListItem.RemoteSession -> {
                val h = holder as SessionViewHolder
                val dirName = item.session.workDir.substringAfterLast('/')
                    .ifEmpty { item.session.workDir.substringAfterLast('\\') }
                h.titleView.text = item.session.title ?: "> $dirName"
                h.subtitleView.text = if (item.session.title != null) {
                    "$dirName - ${formatRelativeTime(item.session.startedAt)}"
                } else {
                    formatRelativeTime(item.session.startedAt)
                }

                // Pulsing alive indicator
                if (item.session.alive) {
                    h.aliveIndicator.visibility = View.VISIBLE
                    if (h.pulseAnimator == null) {
                        h.pulseAnimator = ObjectAnimator.ofFloat(h.aliveIndicator, "alpha", 1f, 0.4f).apply {
                            duration = 750
                            repeatCount = ValueAnimator.INFINITE
                            repeatMode = ValueAnimator.REVERSE
                        }
                    }
                    h.pulseAnimator?.start()
                } else {
                    h.aliveIndicator.visibility = View.GONE
                    h.pulseAnimator?.cancel()
                }

                h.container.setOnClickListener(null)
                h.container.setOnLongClickListener {
                    onSessionLongClick(item.session)
                    true
                }
            }
            is ChatListItem.RemoteControlSession -> {
                val h = holder as RcSessionViewHolder
                val dirName = item.workDir.substringAfterLast('/')
                    .ifEmpty { item.workDir.substringAfterLast('\\') }
                // Prefer an explicit session title; otherwise show the last
                // observed message text so we never end up labelling a chat
                // with the bare folder name (e.g. "user" for /home/user).
                h.titleView.text = item.sessionName
                    ?: item.lastMessage?.takeIf { it.isNotBlank() }?.take(60)
                    ?: dirName
                h.subtitleView.text = "${item.workDir} - ${item.status}"

                // Thinking chip for RC sessions
                h.thinkingChip.visibility = if (thinkingRcSessionIds.contains(item.sessionId)) View.VISIBLE else View.GONE

                val ctx = h.container.context
                h.statusDot.visibility = View.VISIBLE
                h.titleView.setTextColor(color(ctx, R.color.gbx_purple))

                if (item.status != "active") {
                    // Ended sessions: gray, no pulse.
                    h.pulseAnimator?.cancel()
                    h.pulseAnimator = null
                    h.statusDot.alpha = 1f
                    (h.statusDot.background as? GradientDrawable)?.setColor(color(ctx, R.color.gbx_gray))
                    h.titleView.setTextColor(color(ctx, R.color.gbx_gray))
                } else if (item.turning) {
                    // AI is still producing output -- orange + pulsing.
                    (h.statusDot.background as? GradientDrawable)?.setColor(color(ctx, R.color.gbx_orange))
                    if (h.pulseAnimator == null) {
                        h.pulseAnimator = ObjectAnimator.ofFloat(h.statusDot, "alpha", 1f, 0.4f).apply {
                            duration = 750
                            repeatCount = ValueAnimator.INFINITE
                            repeatMode = ValueAnimator.REVERSE
                        }
                    }
                    h.pulseAnimator?.start()
                } else if (item.unread) {
                    // Finished turn, user hasn't opened it yet -- green solid.
                    h.pulseAnimator?.cancel()
                    h.pulseAnimator = null
                    h.statusDot.alpha = 1f
                    (h.statusDot.background as? GradientDrawable)?.setColor(color(ctx, R.color.gbx_green))
                } else {
                    // Finished + already read -- red solid.
                    h.pulseAnimator?.cancel()
                    h.pulseAnimator = null
                    h.statusDot.alpha = 1f
                    (h.statusDot.background as? GradientDrawable)?.setColor(color(ctx, R.color.gbx_red))
                }

                h.container.setOnClickListener {
                    onRcSessionClick?.invoke(item)
                }
            }
            is ChatListItem.CopilotConversation -> {
                val h = holder as CopilotViewHolder
                h.titleView.text = item.summary.title
                val turns = item.summary.turnCount
                val turnLabel = if (turns == 1) "1 turn" else "$turns turns"
                h.timeText.text = "${formatRelativeTime(item.summary.lastActivityAt)} - $turnLabel"
                h.container.setOnClickListener { onCopilotClick?.invoke(item.summary) }
            }
            is ChatListItem.DateHeader -> {
                val h = holder as DateHeaderViewHolder
                h.textView.text = item.label
            }
            is ChatListItem.FolderHeader -> {
                val h = holder as FolderHeaderViewHolder
                h.textView.text = item.label
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is SessionViewHolder) {
            holder.pulseAnimator?.cancel()
        }
        if (holder is RcSessionViewHolder) {
            holder.pulseAnimator?.cancel()
        }
    }

    override fun getItemCount(): Int = items.size

    fun getItemAt(position: Int): ChatListItem? =
        if (position in items.indices) items[position] else null

    fun removeItemAt(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun submitList(newItems: List<ChatListItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    private fun formatRelativeTime(isoTimestamp: String): String {
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = fmt.parse(isoTimestamp) ?: return isoTimestamp
            val diffMs = System.currentTimeMillis() - date.time
            val diffMin = diffMs / 60_000
            val diffHour = diffMs / 3_600_000
            val diffDay = diffMs / 86_400_000

            when {
                diffMin < 1 -> "just now"
                diffMin < 60 -> "${diffMin}m ago"
                diffHour < 24 -> "${diffHour}h ago"
                diffDay < 7 -> "${diffDay}d ago"
                else -> SimpleDateFormat("MMM d", Locale.US).format(date)
            }
        } catch (e: Exception) {
            isoTimestamp
        }
    }

    private fun createThinkingChip(ctx: android.content.Context): TextView {
        val dp = { value: Int -> dpToPx(ctx, value) }
        return TextView(ctx).apply {
            text = "thinking"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(color(ctx, R.color.gbx_bg))
            background = GradientDrawable().apply {
                setColor(color(ctx, R.color.gbx_orange))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
                gravity = Gravity.CENTER_VERTICAL
            }
            visibility = View.GONE
        }
    }

    private fun createCopilotChip(ctx: android.content.Context): TextView {
        val dp = { value: Int -> dpToPx(ctx, value) }
        return TextView(ctx).apply {
            text = "copilot"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(color(ctx, R.color.gbx_bg))
            background = GradientDrawable().apply {
                setColor(color(ctx, R.color.gbx_aqua))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
                gravity = Gravity.CENTER_VERTICAL
            }
        }
    }

    private fun dpToPx(ctx: android.content.Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), ctx.resources.displayMetrics
        ).toInt()
    }

    private fun color(ctx: android.content.Context, resId: Int): Int =
        ContextCompat.getColor(ctx, resId)
}
