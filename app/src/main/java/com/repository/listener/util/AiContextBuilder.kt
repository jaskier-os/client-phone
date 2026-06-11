package com.repository.listener.util

import com.repository.listener.notification.NotificationHistory
import org.json.JSONArray

/**
 * Builds a context string injected into the AI system prompt.
 * Combines recent Telegram notifications, todo tasks, and the user's
 * custom system prompt into a single string with explicit AI instructions.
 */
object AiContextBuilder {

    /**
     * Build the full context string for AI requests.
     * Returns null if all sections are empty.
     */
    fun build(
        userSystemPrompt: String?,
        notificationHistory: NotificationHistory,
        cachedTodosJson: String?
    ): String? {
        val notifSection = buildNotificationSection(notificationHistory)
        val todoSection = buildTodoSection(cachedTodosJson)

        // If no dynamic context, just return user prompt as-is
        if (notifSection == null && todoSection == null) return userSystemPrompt

        val sb = StringBuilder()
        sb.append("LIVE CONTEXT FROM USER'S PHONE:\n\n")
        sb.append("The following are real-time notifications and tasks from the user's phone. ")
        sb.append("The user may refer to people, messages, or tasks mentioned here without repeating the details. ")
        sb.append("Use this context to understand their intent.")

        if (notifSection != null) {
            sb.append("\n\n")
            sb.append("RECENT TELEGRAM MESSAGES (last few minutes):\n")
            sb.append("When the user mentions a person or conversation from this list, they are referring to these messages. ")
            sb.append("If they ask to reply, send a message, or communicate with someone listed here, delegate to the pc-agent which has Telegram access.\n")
            sb.append(notifSection)
        }

        if (todoSection != null) {
            sb.append("\n\n")
            sb.append("USER'S TASK LIST:\n")
            sb.append("These are the user's current tasks. When the user says they did something that matches a task, use update_task to mark it completed. ")
            sb.append("When they ask to add a task, use add_task.\n")
            sb.append(todoSection)
        }

        if (!userSystemPrompt.isNullOrBlank()) {
            sb.append("\n\n")
            sb.append(userSystemPrompt)
        }

        return sb.toString()
    }

    private fun buildNotificationSection(notificationHistory: NotificationHistory): String? {
        return notificationHistory.formatForContext()
    }

    private fun buildTodoSection(cachedTodosJson: String?): String? {
        if (cachedTodosJson.isNullOrBlank()) return null

        return try {
            val arr = JSONArray(cachedTodosJson)
            if (arr.length() == 0) return null

            val active = mutableListOf<org.json.JSONObject>()
            val done = mutableListOf<org.json.JSONObject>()
            for (i in 0 until arr.length()) {
                val todo = arr.getJSONObject(i)
                if (todo.optBoolean("completed", false)) done.add(todo) else active.add(todo)
            }

            // Keep only 5 most recent done items (by createdAt descending)
            val recentDone = done
                .sortedByDescending { it.optLong("createdAt", 0L) }
                .take(5)

            val filtered = active + recentDone
            if (filtered.isEmpty()) return null

            val lines = StringBuilder()
            for ((idx, todo) in filtered.withIndex()) {
                val text = todo.optString("text", "")
                val completed = todo.optBoolean("completed", false)
                val checkbox = if (completed) "[x]" else "[ ]"
                lines.append("${idx + 1}. $checkbox $text\n")
            }
            lines.toString().trimEnd()
        } catch (_: Exception) {
            null
        }
    }
}
