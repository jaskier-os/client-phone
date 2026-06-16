package com.repository.listener.ui

import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.repository.listener.R
import com.repository.listener.network.ConversationInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Picker that lists resumable Claude Code conversations for a directory and lets
 * the user resume one or start a fresh conversation. Used by the launcher flow
 * (after a folder is chosen) and the in-session /resume command.
 */
class ConversationPickerBottomSheet : BottomSheetDialogFragment() {

    private fun color(resId: Int) = ContextCompat.getColor(requireContext(), resId)
    private val COLOR_BG get() = color(R.color.gbx_bg)
    private val COLOR_BG1 get() = color(R.color.gbx_bg1)
    private val COLOR_FG get() = color(R.color.gbx_fg)
    private val COLOR_GRAY get() = color(R.color.gbx_gray)
    private val COLOR_GREEN get() = color(R.color.gbx_green)
    private val COLOR_AQUA get() = color(R.color.gbx_aqua)

    var workDir: String = ""
    var conversations: List<ConversationInfo> = emptyList()
    var isLoading: Boolean = true
    var errorMessage: String? = null

    var onConversationSelected: ((ConversationInfo) -> Unit)? = null
    var onNewConversation: (() -> Unit)? = null

    private var container: LinearLayout? = null
    private val dateFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        this.container = root
        buildContent(root)
        return root
    }

    private fun buildContent(root: LinearLayout) {
        root.removeAllViews()

        root.addView(TextView(requireContext()).apply {
            text = "Resume Conversation"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(COLOR_FG)
            setPadding(48, 16, 48, 4)
        })
        if (workDir.isNotEmpty()) {
            root.addView(TextView(requireContext()).apply {
                text = workDir
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(COLOR_GRAY)
                setPadding(48, 0, 48, 16)
            })
        }

        // "New conversation" is always available, even while loading/on error.
        root.addView(createNewConversationItem())

        if (isLoading) {
            root.addView(ProgressBar(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER_HORIZONTAL }
                indeterminateTintList = android.content.res.ColorStateList.valueOf(COLOR_GRAY)
                setPadding(0, 24, 0, 24)
            })
            return
        }

        if (errorMessage != null) {
            root.addView(TextView(requireContext()).apply {
                text = errorMessage
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFFfb4934.toInt())
                setPadding(48, 8, 48, 24)
            })
            return
        }

        if (conversations.isEmpty()) {
            root.addView(TextView(requireContext()).apply {
                text = "No previous conversations in this folder"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(COLOR_GRAY)
                setPadding(48, 8, 48, 16)
            })
            return
        }

        // Scroll the (potentially long) conversation list.
        val listColumn = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        for (conv in conversations) {
            listColumn.addView(createConversationItem(conv))
        }
        root.addView(ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resources.displayMetrics.heightPixels / 2
            )
            addView(listColumn)
        })
    }

    private fun createNewConversationItem(): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 20)
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(COLOR_BG1),
                ColorDrawable(COLOR_BG),
                null
            )
            setOnClickListener {
                onNewConversation?.invoke()
                dismiss()
            }
            addView(TextView(context).apply {
                text = "+ New conversation"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(COLOR_GREEN)
            })
        }
    }

    private fun createConversationItem(conv: ConversationInfo): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 20)
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(COLOR_BG1),
                ColorDrawable(COLOR_BG),
                null
            )
            setOnClickListener {
                onConversationSelected?.invoke(conv)
                dismiss()
            }
            addView(TextView(context).apply {
                text = conv.label
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(COLOR_AQUA)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val ts = if (conv.lastModified > 0) dateFmt.format(Date(conv.lastModified)) else ""
            val branch = conv.gitBranch?.takeIf { it != "HEAD" }?.let { "  $it" } ?: ""
            addView(TextView(context).apply {
                text = "$ts$branch"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(COLOR_GRAY)
            })
        }
    }

    fun showConversations(list: List<ConversationInfo>) {
        conversations = list
        isLoading = false
        errorMessage = null
        container?.let { buildContent(it) }
    }

    fun showError(msg: String) {
        errorMessage = msg
        isLoading = false
        container?.let { buildContent(it) }
    }
}
