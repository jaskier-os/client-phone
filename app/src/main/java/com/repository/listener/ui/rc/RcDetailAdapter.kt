package com.repository.listener.ui.rc

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.repository.listener.R
import com.repository.listener.ui.MarkwonFactory
import org.json.JSONObject

class RcDetailAdapter : RecyclerView.Adapter<RcDetailAdapter.RcViewHolder>() {

    companion object {
        private const val TYPE_TEXT_USER = 0
        private const val TYPE_TEXT_ASSISTANT = 1
        private const val TYPE_TOOL_STATUS = 2
        private const val TYPE_PERMISSION_REQUEST = 3
        private const val TYPE_PLAN_UPDATE = 4
        private const val TYPE_AGENT_STATUS = 5
        private const val TYPE_THINKING = 6
        private const val TYPE_USER_INPUT = 7
        private const val TYPE_SESSION_EVENT = 8
        private const val TYPE_MODE_CHANGE = 9
        private const val TYPE_ERROR = 10

        private val TOOL_DISPLAY_NAMES = mapOf(
            "ExitPlanMode" to "Plan",
            "EnterPlanMode" to "Plan",
            "Bash" to "Run command",
            "Read" to "Read file",
            "Write" to "Write file",
            "Edit" to "Edit file",
            "Glob" to "Search files",
            "Grep" to "Search content",
            "Agent" to "Agent",
            "WebSearch" to "Web search",
            "WebFetch" to "Fetch URL",
            "NotebookEdit" to "Edit notebook",
            "AskUserQuestion" to "Question"
        )

        fun friendlyToolName(raw: String): String = TOOL_DISPLAY_NAMES[raw] ?: raw

        private const val MAX_PREVIEW_LINES = 10
        private const val COLOR_DIFF_RED = 0xFFCC241D.toInt()
        private const val COLOR_DIFF_GREEN = 0xFF98971A.toInt()
        private const val COLOR_DIFF_GRAY = 0xFFA89984.toInt()

        // Payload marker for partial agent-row rebinds (label-only refresh).
        // Using a payload skips RecyclerView's default change animation, which
        // otherwise causes a 1-Hz blink on the elapsed-counter ticker.
        const val PAYLOAD_AGENT_TICK = "agent_tick"
    }

    private val messages = mutableListOf<RcMessage>()

    class RcViewHolder(
        val container: LinearLayout,
        val primaryText: TextView,
        val secondaryText: TextView?,
        val leftBorder: View?
    ) : RecyclerView.ViewHolder(container)

    override fun getItemViewType(position: Int): Int {
        return when (val msg = messages[position]) {
            is RcMessage.TextMessage -> when (msg.role) {
                RcMessage.Role.USER -> TYPE_TEXT_USER
                RcMessage.Role.ASSISTANT -> TYPE_TEXT_ASSISTANT
                RcMessage.Role.SYSTEM -> TYPE_SESSION_EVENT
            }
            is RcMessage.ToolStatus -> TYPE_TOOL_STATUS
            is RcMessage.PermissionRequest -> TYPE_PERMISSION_REQUEST
            is RcMessage.PlanUpdate -> TYPE_PLAN_UPDATE
            is RcMessage.AgentStatus -> TYPE_AGENT_STATUS
            is RcMessage.ThinkingBlock -> TYPE_THINKING
            is RcMessage.UserInputRequest -> TYPE_USER_INPUT
            is RcMessage.SessionEvent -> TYPE_SESSION_EVENT
            is RcMessage.ModeChange -> TYPE_MODE_CHANGE
            is RcMessage.ErrorMessage -> TYPE_ERROR
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RcViewHolder {
        val ctx = parent.context
        val dp = { value: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics
            ).toInt()
        }

        return when (viewType) {
            TYPE_TEXT_USER -> createBubbleHolder(parent, dp, Gravity.END, color(ctx, R.color.gbx_green), isUser = true)
            TYPE_TEXT_ASSISTANT -> createBubbleHolder(parent, dp, Gravity.START, color(ctx, R.color.gbx_bg1), isUser = false)
            TYPE_TOOL_STATUS -> createCardHolder(parent, dp, color(ctx, R.color.gbx_aqua))
            TYPE_PERMISSION_REQUEST -> createCardHolder(parent, dp, color(ctx, R.color.gbx_orange))
            TYPE_PLAN_UPDATE -> createPlanHolder(parent, dp, color(ctx, R.color.gbx_blue))
            TYPE_AGENT_STATUS -> createAgentHolder(parent, dp)
            TYPE_THINKING -> createThinkingHolder(parent, dp)
            TYPE_USER_INPUT -> createCardHolder(parent, dp, color(ctx, R.color.gbx_yellow))
            TYPE_SESSION_EVENT -> createCenterHolder(parent, dp, color(ctx, R.color.gbx_gray))
            TYPE_MODE_CHANGE -> createCenterHolder(parent, dp, color(ctx, R.color.gbx_gray))
            TYPE_ERROR -> createCardHolder(parent, dp, color(ctx, R.color.gbx_red))
            else -> createCenterHolder(parent, dp, color(ctx, R.color.gbx_gray))
        }
    }

    override fun onBindViewHolder(holder: RcViewHolder, position: Int, payloads: MutableList<Any>) {
        // Partial rebind for the agent-row elapsed ticker: only update the
        // primary text without touching layout/animation/borders. This avoids
        // the RecyclerView change-animation blink seen on full rebinds.
        if (payloads.isNotEmpty() && payloads.contains(PAYLOAD_AGENT_TICK)) {
            val msg = messages[position]
            if (msg is RcMessage.ToolStatus && msg.isAgent) {
                holder.primaryText.text = buildAgentPrimaryLabel(msg)
                return
            }
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RcViewHolder, position: Int) {
        val ctx = holder.itemView.context
        val msg = messages[position]

        when (msg) {
            is RcMessage.TextMessage -> {
                if (msg.role == RcMessage.Role.ASSISTANT) {
                    MarkwonFactory.get(ctx).setMarkdown(holder.primaryText, msg.text.trim())
                    holder.primaryText.setTextIsSelectable(true)
                    holder.primaryText.movementMethod = LinkMovementMethod.getInstance()
                    holder.primaryText.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                } else {
                    holder.primaryText.setTextIsSelectable(true)
                    holder.primaryText.text = msg.text.trim()
                    holder.primaryText.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                }
            }
            is RcMessage.ToolStatus -> {
                holder.primaryText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)

                // Build primary text with tool-specific info (agent-prefixed if applicable)
                val primaryLabel = if (msg.isAgent) {
                    buildAgentPrimaryLabel(msg)
                } else {
                    buildToolPrimaryText(msg)
                }
                holder.primaryText.text = primaryLabel

                // Status drives row color (aqua=calling/running, green=complete, red=error)
                // for both regular tools and agents. Agents additionally keep a purple LEFT
                // border as the agent indicator.
                val statusColor = when (msg.status) {
                    "complete" -> color(ctx, R.color.gbx_green)
                    "error" -> color(ctx, R.color.gbx_red)
                    else -> color(ctx, R.color.gbx_aqua)
                }
                if (msg.isAgent) {
                    // Border follows status (aqua=calling, green=complete, red=error).
                    // Text stays in the default foreground color so only the border
                    // is the status indicator -- consistent with regular tool rows.
                    holder.leftBorder?.setBackgroundColor(statusColor)
                    holder.primaryText.setTextColor(color(ctx, R.color.gbx_fg))
                } else {
                    holder.primaryText.setTextColor(color(ctx, R.color.gbx_fg))
                    holder.itemView.findViewWithTag<View>("card_border")?.setBackgroundColor(statusColor)
                }

                // Show tool output inline (colored for diffs)
                val output = buildToolOutput(msg)
                if (output != null && output.isNotEmpty()) {
                    holder.secondaryText?.text = output
                    holder.secondaryText?.visibility = View.VISIBLE
                } else {
                    holder.secondaryText?.visibility = View.GONE
                }

                if (msg.status == "calling" || msg.status == "running") {
                    val pulse = AlphaAnimation(1f, 0.3f).apply {
                        duration = 800
                        repeatCount = Animation.INFINITE
                        repeatMode = Animation.REVERSE
                    }
                    holder.itemView.startAnimation(pulse)
                } else {
                    holder.itemView.clearAnimation()
                }
                // Click to preview full Write/Edit content
                holder.itemView.setOnClickListener(null)
                if (msg.status == "complete" && !msg.toolArgs.isNullOrEmpty()) {
                    if (msg.toolName == "Write") {
                        holder.itemView.setOnClickListener { showWritePreview(holder.itemView.context, msg.toolArgs) }
                    } else if (msg.toolName == "Edit") {
                        holder.itemView.setOnClickListener { showEditDiff(holder.itemView.context, msg.toolArgs) }
                    }
                }
            }
            is RcMessage.PermissionRequest -> {
                holder.primaryText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)

                if (msg.toolName == "AskUserQuestion") {
                    // AskUserQuestion: show question text and selected answer
                    holder.primaryText.text = friendlyToolName(msg.toolName)
                    if (msg.approved && msg.result != null) {
                        holder.secondaryText?.text = "${msg.description}\n-- ${msg.result}"
                    } else if (msg.approved) {
                        holder.secondaryText?.text = msg.description.ifEmpty { "Answered" }
                    } else {
                        holder.secondaryText?.text = msg.description.ifEmpty { "" }
                    }
                } else if (msg.result != null && msg.approved) {
                    // Completed tool: show tool-specific output
                    val toolStatus = RcMessage.ToolStatus(
                        id = msg.id, timestamp = msg.timestamp,
                        toolName = msg.toolName, status = "complete",
                        result = msg.result, toolArgs = msg.toolArgs
                    )
                    holder.primaryText.text = buildToolPrimaryText(toolStatus)
                    val output = buildToolOutput(toolStatus)
                    if (output != null && output.isNotEmpty()) {
                        holder.secondaryText?.text = output
                    } else {
                        val text = PermissionDialog.formatToolArgs(msg.toolName, msg.toolArgs)
                        setSecondaryText(holder.secondaryText, text, isPlanTool(msg.toolName), ctx)
                    }
                } else {
                    holder.primaryText.text = friendlyToolName(msg.toolName)
                    val argsText = buildString {
                        append(PermissionDialog.formatToolArgs(msg.toolName, msg.toolArgs))
                        if (!msg.description.isNullOrEmpty()) {
                            append("\n")
                            append(msg.description)
                        }
                    }
                    setSecondaryText(holder.secondaryText, argsText, isPlanTool(msg.toolName), ctx)
                }
                holder.secondaryText?.visibility = View.VISIBLE

                // Dynamic border color: orange=pending, green=approved, red=rejected
                val borderColor = when {
                    msg.pending -> color(holder.itemView.context, R.color.gbx_orange)
                    msg.approved -> color(holder.itemView.context, R.color.gbx_green)
                    else -> color(holder.itemView.context, R.color.gbx_red)
                }
                holder.itemView.findViewWithTag<View>("card_border")?.setBackgroundColor(borderColor)

                // Click to preview Write/Edit content when resolved
                holder.itemView.setOnClickListener(null)
                if (!msg.pending && msg.toolArgs.isNotEmpty()) {
                    if (msg.toolName == "Write") {
                        holder.itemView.setOnClickListener { showWritePreview(holder.itemView.context, msg.toolArgs) }
                    } else if (msg.toolName == "Edit") {
                        holder.itemView.setOnClickListener { showEditDiff(holder.itemView.context, msg.toolArgs) }
                    }
                }
            }
            is RcMessage.PlanUpdate -> {
                val chevron = holder.itemView.findViewWithTag<TextView>("plan_chevron")
                if (msg.entering) {
                    holder.primaryText.text = "Plan"
                    holder.primaryText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    val content = msg.planContent ?: ""
                    if (content.isNotEmpty()) {
                        val tv = holder.secondaryText!!
                        MarkwonFactory.get(ctx).setMarkdown(tv, content)
                        tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                        tv.movementMethod = LinkMovementMethod.getInstance()
                        tv.visibility = View.VISIBLE
                    } else {
                        holder.secondaryText?.visibility = View.GONE
                    }
                    chevron?.text = "v"
                    chevron?.visibility = if (msg.planContent != null) View.VISIBLE else View.GONE
                    holder.itemView.setOnClickListener {
                        val content = holder.secondaryText ?: return@setOnClickListener
                        val isVisible = content.visibility == View.VISIBLE
                        content.visibility = if (isVisible) View.GONE else View.VISIBLE
                        chevron?.text = if (isVisible) ">" else "v"
                    }
                } else {
                    holder.primaryText.text = "Plan complete"
                    holder.primaryText.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                    holder.secondaryText?.visibility = View.GONE
                    chevron?.visibility = View.GONE
                    holder.itemView.setOnClickListener(null)
                }
            }
            is RcMessage.AgentStatus -> {
                val dp = { value: Int ->
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics
                    ).toInt()
                }
                val indent = msg.depth * dp(16)
                (holder.container.layoutParams as? RecyclerView.LayoutParams)?.let {
                    it.marginStart = dp(12) + indent
                }

                val borderColor = when (msg.status) {
                    "started" -> color(ctx, R.color.gbx_green)
                    "completed" -> color(ctx, R.color.gbx_blue)
                    "error" -> color(ctx, R.color.gbx_red)
                    else -> color(ctx, R.color.gbx_purple)
                }
                holder.leftBorder?.setBackgroundColor(borderColor)

                val statusIcon = when (msg.status) {
                    "started" -> "[>] "
                    "completed" -> "[+] "
                    "error" -> "[!] "
                    else -> ""
                }
                holder.primaryText.text = "${statusIcon}Agent: ${msg.name}"
                holder.primaryText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)

                holder.secondaryText?.text = msg.status
                holder.secondaryText?.visibility = View.VISIBLE

                if (msg.status == "started") {
                    val pulse = AlphaAnimation(1f, 0.3f).apply {
                        duration = 800
                        repeatCount = Animation.INFINITE
                        repeatMode = Animation.REVERSE
                    }
                    holder.itemView.startAnimation(pulse)
                } else {
                    holder.itemView.clearAnimation()
                }
            }
            is RcMessage.ThinkingBlock -> {
                holder.primaryText.text = if (msg.text.isNotEmpty()) msg.text else "Thinking..."
            }
            is RcMessage.UserInputRequest -> {
                holder.primaryText.text = "Input requested"
                holder.primaryText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                holder.secondaryText?.text = msg.prompt
                holder.secondaryText?.visibility = View.VISIBLE
                if (msg.answered) {
                    holder.secondaryText?.text = "${msg.prompt}\n-- answered --"
                }
            }
            is RcMessage.SessionEvent -> {
                holder.primaryText.text = msg.event
            }
            is RcMessage.ModeChange -> {
                val modeLabel = when (msg.newMode) {
                    "plan" -> "Mode: Ask on risky ops"
                    "acceptEdits" -> "Mode: Accept all edits"
                    "bypassAll" -> "Mode: Bypass all permissions"
                    else -> "Mode: ${msg.newMode}"
                }
                holder.primaryText.text = modeLabel
            }
            is RcMessage.ErrorMessage -> {
                holder.primaryText.text = "[${msg.source}]"
                holder.primaryText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                holder.primaryText.setTextColor(color(ctx, R.color.gbx_red))
                holder.secondaryText?.text = msg.errorText
                holder.secondaryText?.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                holder.secondaryText?.visibility = View.VISIBLE
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    override fun onViewRecycled(holder: RcViewHolder) {
        super.onViewRecycled(holder)
        holder.itemView.clearAnimation()
    }

    fun findPositionById(id: String): Int = messages.indexOfFirst { it.id == id }

    fun getMessages(): List<RcMessage> = messages.toList()

    fun addMessage(msg: RcMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun submitMessages(newMessages: List<RcMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun updateStreamingText(requestId: String, text: String) {
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            if (msg is RcMessage.TextMessage && msg.role == RcMessage.Role.ASSISTANT && msg.requestId == requestId) {
                messages[i] = msg.copy(text = text)
                notifyItemChanged(i)
                return
            }
        }
    }

    fun upsertToolStatus(toolName: String, status: String, result: String?, toolArgs: String?, toolCallId: String? = null, isAgent: Boolean = false, agentName: String? = null, agentTask: String? = null, agentToolCount: Int? = null, agentTokens: Long? = null, agentElapsedMs: Long? = null) {
        // Suppress standalone cards for tools that don't need visible output
        if (toolName == "AskUserQuestion" || toolName == "EnterPlanMode") {
            // Just update existing PermissionRequest if found, don't create new card
            for (i in messages.indices.reversed()) {
                val msg = messages[i]
                if (msg is RcMessage.PermissionRequest && msg.toolName == toolName && !msg.pending) {
                    msg.result = result
                    notifyItemChanged(i)
                    return
                }
            }
            return // No card needed
        }
        // Try to find existing card by toolCallId first (unique per invocation),
        // then fall back to toolName matching for PermissionRequest cards.
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            // Match PermissionRequest by toolCallId or toolName (skip for agents)
            if (!isAgent && msg is RcMessage.PermissionRequest && msg.toolName == toolName && !msg.pending) {
                msg.result = result
                notifyItemChanged(i)
                return
            }
            // Match existing ToolStatus by toolCallId (precise) or toolName (legacy fallback)
            if (msg is RcMessage.ToolStatus) {
                val matches = if (!toolCallId.isNullOrEmpty() && !msg.toolCallId.isNullOrEmpty()) {
                    msg.toolCallId == toolCallId
                } else if (toolCallId.isNullOrEmpty() && msg.toolCallId.isNullOrEmpty()) {
                    msg.toolName == toolName
                } else {
                    false
                }
                if (matches) {
                    messages[i] = msg.copy(
                        status = status,
                        result = result ?: msg.result,
                        toolArgs = toolArgs ?: msg.toolArgs,
                        isAgent = isAgent || msg.isAgent,
                        agentName = agentName ?: msg.agentName,
                        agentTask = agentTask ?: msg.agentTask,
                        // Preserve dispatch timestamp from the original Calling row so
                        // the elapsed ticker has a stable origin until Complete arrives.
                        agentDispatchedAt = msg.agentDispatchedAt
                            ?: (if (isAgent || msg.isAgent) System.currentTimeMillis() else null),
                        agentToolCount = agentToolCount ?: msg.agentToolCount,
                        agentTokens = agentTokens ?: msg.agentTokens,
                        agentElapsedMs = agentElapsedMs ?: msg.agentElapsedMs
                    )
                    notifyItemChanged(i)
                    return
                }
            }
        }
        // No matching card found, add new ToolStatus
        val msg = RcMessage.ToolStatus(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            toolName = toolName,
            status = status,
            result = result,
            toolArgs = toolArgs,
            toolCallId = toolCallId,
            isAgent = isAgent,
            agentName = agentName,
            agentTask = agentTask,
            agentDispatchedAt = if (isAgent) System.currentTimeMillis() else null,
            agentToolCount = agentToolCount,
            agentTokens = agentTokens,
            agentElapsedMs = agentElapsedMs
        )
        addMessage(msg)
    }

    /**
     * Force a re-bind of any in-flight agent rows so the elapsed counter ticks.
     * Returns true if any agent row is still in calling/running state.
     */
    fun tickInFlightAgentRows(): Boolean {
        var anyInFlight = false
        for (i in messages.indices) {
            val m = messages[i]
            if (m is RcMessage.ToolStatus && m.isAgent && (m.status == "calling" || m.status == "running")) {
                anyInFlight = true
                // Payload-scoped rebind -- ItemAnimator skips the change
                // animation when payloads are present, killing the blink.
                notifyItemChanged(i, PAYLOAD_AGENT_TICK)
            }
        }
        return anyInFlight
    }

    fun updateThinking(text: String) {
        // Update last thinking block or add new one
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            if (msg is RcMessage.ThinkingBlock) {
                messages[i] = msg.copy(text = text)
                notifyItemChanged(i)
                return
            }
        }
        addMessage(RcMessage.ThinkingBlock(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            text = text
        ))
    }

    // --- Private view creation methods ---

    private fun createBubbleHolder(
        parent: ViewGroup, dp: (Int) -> Int, gravity: Int, bgColor: Int, isUser: Boolean
    ): RcViewHolder {
        val ctx = parent.context
        val bubbleMaxWidth = (ctx.resources.displayMetrics.widthPixels * if (isUser) 0.80f else 0.85f).toInt()

        val textView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(color(ctx, R.color.gbx_fg))
            maxWidth = bubbleMaxWidth - dp(24)
            // Marker for instrumentation harness to find user/assistant bubbles by contentDescription.
            contentDescription = if (isUser) "rcUserText" else "rcAssistantText"
        }

        val bubble = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(textView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2); bottomMargin = dp(2) }
            setPadding(dp(16), 0, dp(16), 0)
            this.gravity = gravity
            addView(bubble, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        return RcViewHolder(container, textView, null, null)
    }

    private fun createCenterHolder(parent: ViewGroup, dp: (Int) -> Int, textColor: Int): RcViewHolder {
        val ctx = parent.context

        val textView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(textColor)
            gravity = Gravity.CENTER
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(4) }
            setPadding(dp(16), dp(4), dp(16), dp(4))
            gravity = Gravity.CENTER_HORIZONTAL
            addView(textView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        return RcViewHolder(container, textView, null, null)
    }

    private fun createCardHolder(parent: ViewGroup, dp: (Int) -> Int, borderColor: Int): RcViewHolder {
        val ctx = parent.context

        val cornerR = dp(8).toFloat()
        val leftBorder = View(ctx).apply {
            tag = "card_border"
            layoutParams = LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(borderColor)
        }

        val primaryText = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(color(ctx, R.color.gbx_fg))
        }

        val secondaryText = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(color(ctx, R.color.gbx_gray))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        val textContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
            setPadding(0, dp(12), dp(12), dp(12))
            addView(primaryText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(secondaryText)
        }

        val innerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(leftBorder)
            addView(textContent)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
            background = GradientDrawable().apply {
                setColor(color(ctx, R.color.gbx_bg1))
                cornerRadius = cornerR
            }
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerR)
                }
            }
            clipToOutline = true
            addView(innerRow)
        }

        return RcViewHolder(container, primaryText, secondaryText, leftBorder)
    }

    private fun createPlanHolder(parent: ViewGroup, dp: (Int) -> Int, borderColor: Int): RcViewHolder {
        val ctx = parent.context

        val cornerR = dp(8).toFloat()
        val leftBorder = View(ctx).apply {
            tag = "card_border"
            layoutParams = LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(borderColor)
        }

        val primaryText = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(color(ctx, R.color.gbx_fg))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val chevron = TextView(ctx).apply {
            tag = "plan_chevron"
            text = "v"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(color(ctx, R.color.gbx_gray))
            setPadding(dp(8), 0, 0, 0)
        }

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(primaryText)
            addView(chevron)
        }

        val secondaryText = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(color(ctx, R.color.gbx_gray))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        val textContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
            setPadding(0, dp(12), dp(12), dp(12))
            addView(headerRow)
            addView(secondaryText)
        }

        val innerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(leftBorder)
            addView(textContent)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
            background = GradientDrawable().apply {
                setColor(color(ctx, R.color.gbx_bg1))
                cornerRadius = cornerR
            }
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerR)
                }
            }
            clipToOutline = true
            isClickable = true
            addView(innerRow)
        }

        return RcViewHolder(container, primaryText, secondaryText, leftBorder)
    }

    private fun createAgentHolder(parent: ViewGroup, dp: (Int) -> Int): RcViewHolder {
        val ctx = parent.context
        val cornerR = dp(8).toFloat()

        val leftBorder = View(ctx).apply {
            tag = "card_border"
            layoutParams = LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(color(ctx, R.color.gbx_purple))
        }

        val primaryText = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(color(ctx, R.color.gbx_fg))
        }

        val secondaryText = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(color(ctx, R.color.gbx_gray))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        val textContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
            setPadding(0, dp(12), dp(12), dp(12))
            addView(primaryText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(secondaryText)
        }

        val innerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(leftBorder)
            addView(textContent)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
            background = GradientDrawable().apply {
                setColor(color(ctx, R.color.gbx_bg1))
                cornerRadius = cornerR
            }
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerR)
                }
            }
            clipToOutline = true
            addView(innerRow)
        }

        return RcViewHolder(container, primaryText, secondaryText, leftBorder)
    }

    private fun createThinkingHolder(parent: ViewGroup, dp: (Int) -> Int): RcViewHolder {
        val ctx = parent.context

        val textView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(color(ctx, R.color.gbx_gray))
            setTypeface(typeface, Typeface.ITALIC)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2); bottomMargin = dp(2) }
            background = GradientDrawable().apply {
                setColor(color(ctx, R.color.gbx_bg1))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(textView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        return RcViewHolder(container, textView, null, null)
    }

    private fun isPlanTool(toolName: String): Boolean =
        toolName == "ExitPlanMode" || toolName == "EnterPlanMode"

    private fun setSecondaryText(tv: TextView?, text: String, markdown: Boolean, ctx: android.content.Context) {
        tv ?: return
        if (markdown && text.isNotEmpty()) {
            MarkwonFactory.get(ctx).setMarkdown(tv, text)
            tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            tv.movementMethod = LinkMovementMethod.getInstance()
        } else {
            tv.text = text
        }
    }

    private fun color(ctx: android.content.Context, resId: Int): Int =
        ContextCompat.getColor(ctx, resId)

    /**
     * Build the primary label for an Agent (Task) row, including live stats:
     *   "Agent (Calling): Find ChatsListFragment.kt - 3 tools - 12.4k tokens - 0m 42s"
     *   "Agent (Complete): Find ChatsListFragment.kt - 7 tools - 81.1k tokens - 2m 23s"
     * Stats segments are appended only when their underlying value is known.
     * Elapsed for in-flight rows is computed from agentDispatchedAt; on Complete
     * the orchestrator-supplied agentElapsedMs takes precedence (frozen value).
     */
    fun buildAgentPrimaryLabel(msg: RcMessage.ToolStatus): String {
        val statusWord = when (msg.status) {
            "calling", "running" -> "(Calling)"
            "complete" -> "(Complete)"
            "error" -> "(Error)"
            else -> ""
        }
        val name = msg.agentName ?: "agent"
        val task = msg.agentTask?.takeIf { it.isNotBlank() }
        val prefix = if (statusWord.isNotEmpty()) "Agent $statusWord" else "Agent"
        val head = "$prefix: ${task ?: name}"
        val parts = mutableListOf(head)
        msg.agentToolCount?.let { parts.add("$it tools") }
        msg.agentTokens?.let { parts.add("${formatTokens(it)} tokens") }
        val elapsedMs: Long? = when {
            msg.agentElapsedMs != null -> msg.agentElapsedMs
            msg.status == "calling" || msg.status == "running" -> {
                msg.agentDispatchedAt?.let { System.currentTimeMillis() - it }
            }
            else -> null
        }
        elapsedMs?.let { parts.add(formatElapsedMmSs(it)) }
        return parts.joinToString(" - ")
    }

    /** Format token count -> "81.1k", "1.2M", or "734" for small values. */
    fun formatTokens(n: Long): String {
        val abs = kotlin.math.abs(n)
        return when {
            abs >= 1_000_000L -> {
                val v = n.toDouble() / 1_000_000.0
                String.format(java.util.Locale.US, "%.1fM", v)
            }
            abs >= 1_000L -> {
                val v = n.toDouble() / 1_000.0
                String.format(java.util.Locale.US, "%.1fk", v)
            }
            else -> n.toString()
        }
    }

    /** Format elapsed millis -> "2m 23s" (or "0m 42s"). */
    fun formatElapsedMmSs(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val m = totalSec / 60L
        val s = totalSec % 60L
        return "${m}m ${s}s"
    }

    private fun buildToolPrimaryText(msg: RcMessage.ToolStatus): String {
        val statusIcon = when (msg.status) {
            "running" -> "[*] "
            "complete" -> "[+] "
            "error" -> "[!] "
            else -> ""
        }
        val args = try { if (!msg.toolArgs.isNullOrEmpty()) JSONObject(msg.toolArgs) else null } catch (_: Exception) { null }
        return when (msg.toolName) {
            "Edit" -> {
                val path = args?.optString("file_path", "")?.substringAfterLast('/') ?: ""
                "${statusIcon}Edit ${path.ifEmpty { "file" }}"
            }
            "Write" -> {
                val path = args?.optString("file_path", "")?.substringAfterLast('/') ?: ""
                val content = args?.optString("content", "") ?: ""
                val lineCount = if (content.isNotEmpty()) content.lines().size else 0
                if (msg.status == "complete" && lineCount > 0)
                    "${statusIcon}Wrote $lineCount lines to ${path.ifEmpty { "file" }}"
                else "${statusIcon}Write ${path.ifEmpty { "file" }}"
            }
            "Bash" -> {
                val cmd = args?.optString("command", "") ?: ""
                val shortCmd = cmd.lines().firstOrNull()?.take(60) ?: ""
                "${statusIcon}${shortCmd.ifEmpty { "Run command" }}"
            }
            else -> "$statusIcon${friendlyToolName(msg.toolName)}: ${msg.status}"
        }
    }

    private fun buildToolOutput(msg: RcMessage.ToolStatus): CharSequence? {
        val args = try { if (!msg.toolArgs.isNullOrEmpty()) JSONObject(msg.toolArgs) else null } catch (_: Exception) { null }
        return when (msg.toolName) {
            "Edit" -> buildEditDiffOutput(args)
            "Write" -> buildWriteOutput(args)
            "Bash" -> buildBashOutput(msg)
            else -> null
        }
    }

    private fun buildEditDiffOutput(args: JSONObject?): CharSequence? {
        args ?: return null
        val oldStr = args.optString("old_string", "")
        val newStr = args.optString("new_string", "")
        if (oldStr.isEmpty() && newStr.isEmpty()) return null

        val oldLines = if (oldStr.isNotEmpty()) oldStr.lines().map { "- $it" } else emptyList()
        val newLines = if (newStr.isNotEmpty()) newStr.lines().map { "+ $it" } else emptyList()
        val allLines = oldLines + newLines
        val truncated = allLines.size > MAX_PREVIEW_LINES
        val displayLines = if (truncated) allLines.take(MAX_PREVIEW_LINES) else allLines

        val sb = SpannableStringBuilder()
        for ((i, line) in displayLines.withIndex()) {
            if (i > 0) sb.append("\n")
            val start = sb.length
            sb.append(line)
            val color = if (line.startsWith("-")) COLOR_DIFF_RED else COLOR_DIFF_GREEN
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (truncated) {
            sb.append("\n")
            val start = sb.length
            sb.append("... +${allLines.size - MAX_PREVIEW_LINES} lines")
            sb.setSpan(ForegroundColorSpan(COLOR_DIFF_GRAY), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun buildWriteOutput(args: JSONObject?): CharSequence? {
        args ?: return null
        val content = args.optString("content", "")
        if (content.isEmpty()) return null

        val lines = content.lines()
        val truncated = lines.size > MAX_PREVIEW_LINES
        val displayLines = if (truncated) lines.take(MAX_PREVIEW_LINES) else lines

        val sb = SpannableStringBuilder()
        sb.append(displayLines.joinToString("\n"))
        if (truncated) {
            sb.append("\n")
            val start = sb.length
            sb.append("... +${lines.size - MAX_PREVIEW_LINES} lines")
            sb.setSpan(ForegroundColorSpan(COLOR_DIFF_GRAY), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun buildBashOutput(msg: RcMessage.ToolStatus): CharSequence? {
        if (msg.status != "complete" || msg.result.isNullOrEmpty()) return null
        val lines = msg.result.lines()
        val truncated = lines.size > MAX_PREVIEW_LINES
        val displayLines = if (truncated) lines.take(MAX_PREVIEW_LINES) else lines

        val sb = SpannableStringBuilder()
        sb.append(displayLines.joinToString("\n"))
        if (truncated) {
            sb.append("\n")
            val start = sb.length
            sb.append("... +${lines.size - MAX_PREVIEW_LINES} lines")
            sb.setSpan(ForegroundColorSpan(COLOR_DIFF_GRAY), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun showWritePreview(context: android.content.Context, toolArgs: String) {
        try {
            val args = JSONObject(toolArgs)
            val content = args.optString("content", "")
            val path = args.optString("file_path", "")
            val textView = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(color(context, R.color.gbx_fg))
                text = content
                setPadding(32, 16, 32, 16)
            }
            val scrollView = ScrollView(context).apply {
                addView(textView)
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(path.substringAfterLast('/').ifEmpty { path })
                .setView(scrollView)
                .setPositiveButton("Close", null)
                .show()
        } catch (_: Exception) {}
    }

    private fun showEditDiff(context: android.content.Context, toolArgs: String) {
        try {
            val args = JSONObject(toolArgs)
            val path = args.optString("file_path", "")
            val oldStr = args.optString("old_string", "")
            val newStr = args.optString("new_string", "")
            val dp = { value: Int ->
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
                ).toInt()
            }

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }

            if (oldStr.isNotEmpty()) {
                val oldLabel = TextView(context).apply {
                    text = "- removed"
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(Color.parseColor("#CC241D"))
                }
                container.addView(oldLabel)
                val oldView = TextView(context).apply {
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(color(context, R.color.gbx_fg))
                    text = oldStr
                    setBackgroundColor(Color.parseColor("#30CC241D"))
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                }
                container.addView(oldView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) })
            }

            if (newStr.isNotEmpty()) {
                val newLabel = TextView(context).apply {
                    text = "+ added"
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(Color.parseColor("#98971A"))
                }
                container.addView(newLabel)
                val newView = TextView(context).apply {
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(color(context, R.color.gbx_fg))
                    text = newStr
                    setBackgroundColor(Color.parseColor("#3098971A"))
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                }
                container.addView(newView)
            }

            val scrollView = ScrollView(context).apply {
                addView(container)
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(path.substringAfterLast('/').ifEmpty { path })
                .setView(scrollView)
                .setPositiveButton("Close", null)
                .show()
        } catch (_: Exception) {}
    }
}
