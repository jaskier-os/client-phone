package com.repository.listener.ui.rc

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
}
