package com.repository.listener.notification

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Thread-safe ring buffer storing recent Telegram notifications.
 * Used to build AI context -- the AI sees recent messages so it can
 * understand references like "Tell Nikolay I'll be late" when Nikolay
 * just messaged.
 */
class NotificationHistory(
    private val maxEntries: Int = 50
) {
    data class Entry(
        val sender: String,
        val text: String,
        val chat: String,
        val timestamp: Long
    )

    private val entries = ConcurrentLinkedDeque<Entry>()

    fun add(sender: String, text: String, chat: String, timestamp: Long) {
        entries.addLast(Entry(sender, text, chat, timestamp))
        while (entries.size > maxEntries) {
            entries.pollFirst()
        }
    }

    /**
     * Returns entries within the given time window (default 3 minutes).
     */
    fun getRecent(windowMs: Long = 180_000L): List<Entry> {
        val cutoff = System.currentTimeMillis() - windowMs
        return entries.filter { it.timestamp >= cutoff }
    }

    /**
     * Formats recent notifications for AI context injection.
     * Returns null if no recent notifications exist.
     */
    fun formatForContext(): String? {
        val recent = getRecent()
        if (recent.isEmpty()) return null

        val now = System.currentTimeMillis()
        val lines = recent.map { entry ->
            val agoMs = now - entry.timestamp
            val agoText = when {
                agoMs < 60_000 -> "just now"
                agoMs < 120_000 -> "1 min ago"
                else -> "${agoMs / 60_000} min ago"
            }
            val chatSuffix = if (entry.chat.isNotBlank()) " [${entry.chat}]" else ""
            "- ${entry.sender}$chatSuffix: ${entry.text} ($agoText)"
        }

        return lines.joinToString("\n")
    }
}
