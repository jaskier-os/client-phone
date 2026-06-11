package com.repository.listener.ui.rc

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.repository.listener.R
import org.json.JSONObject

object PermissionDialog {

    // Map technical tool names to user-friendly display names
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
        "NotebookEdit" to "Edit notebook"
    )

    private fun friendlyToolName(raw: String): String =
        TOOL_DISPLAY_NAMES[raw] ?: raw

    /**
     * Parse tool args JSON string into a human-readable representation.
     * For plan-related tools, extract and format the plan text.
     * For file tools, show the file path.
     * For command tools, show the command.
     */
    fun formatToolArgs(toolName: String, argsJson: String): String {
        if (argsJson.isEmpty() || argsJson == "{}") return ""
        return try {
            val obj = JSONObject(argsJson)

            // Plan tools: extract plan content
            if (toolName == "ExitPlanMode" || toolName == "EnterPlanMode") {
                val plan = obj.optString("plan", "")
                if (plan.isNotEmpty()) return plan
                val planFilePath = obj.optString("planFilePath", "")
                if (planFilePath.isNotEmpty()) return "Plan file: $planFilePath"
            }

            // File tools: show path
            val filePath = obj.optString("file_path", obj.optString("path", ""))
            if (filePath.isNotEmpty()) {
                val parts = mutableListOf("File: $filePath")
                val command = obj.optString("command", "")
                if (command.isNotEmpty()) parts.add("Command: $command")
                return parts.joinToString("\n")
            }

            // Bash: show command directly
            val command = obj.optString("command", "")
            if (command.isNotEmpty()) return command

            // Fallback: show key=value pairs, skip very long values
            val lines = mutableListOf<String>()
            for (key in obj.keys()) {
                val value = obj.opt(key)?.toString() ?: ""
                if (value.length > 200) {
                    lines.add("$key: ${value.take(200)}...")
                } else {
                    lines.add("$key: $value")
                }
            }
            lines.joinToString("\n")
        } catch (_: Exception) {
            argsJson.take(500)
        }
    }

    fun show(
        context: Context,
        request: RcMessage.PermissionRequest,
        onResult: (approved: Boolean, modeChange: String?) -> Unit
    ): AlertDialog {
        val dp = { value: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
            ).toInt()
        }

        val displayName = friendlyToolName(request.toolName)
        val formattedArgs = formatToolArgs(request.toolName, request.toolArgs)

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        // Tool name (bold, user-friendly)
        val toolNameView = TextView(context).apply {
            text = displayName
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ContextCompat.getColor(context, R.color.gbx_fg))
        }
        contentLayout.addView(toolNameView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        // Formatted tool args / plan content
        if (formattedArgs.isNotEmpty()) {
            val argsView = TextView(context).apply {
                text = formattedArgs
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(context, R.color.gbx_gray))
                maxLines = 30
            }
            contentLayout.addView(argsView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }

        // Description
        if (!request.description.isNullOrEmpty()) {
            val descView = TextView(context).apply {
                text = request.description
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(context, R.color.gbx_fg))
            }
            contentLayout.addView(descView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }

        // Overflow link for bulk approve options
        val overflowLink = TextView(context).apply {
            text = "More options..."
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(context, R.color.gbx_aqua))
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
        }
        contentLayout.addView(overflowLink, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Permission Required")
            .setView(contentLayout)
            .setCancelable(false)
            .setNegativeButton("Reject") { _, _ ->
                onResult(false, null)
            }
            .setPositiveButton("Approve") { _, _ ->
                onResult(true, null)
            }
            .create()

        overflowLink.setOnClickListener { anchor ->
            val popup = PopupMenu(context, anchor)
            popup.menu.add(0, 1, 0, "Approve & Accept Edits")
            popup.menu.add(0, 2, 1, "Approve & Bypass All")
            popup.setOnMenuItemClickListener { item ->
                dialog.dismiss()
                when (item.itemId) {
                    1 -> onResult(true, "acceptEdits")
                    2 -> onResult(true, "bypassAll")
                }
                true
            }
            popup.show()
        }

        dialog.show()
        return dialog
    }
}
