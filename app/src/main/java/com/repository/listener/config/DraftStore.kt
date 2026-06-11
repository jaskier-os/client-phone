package com.repository.listener.config

import android.content.Context

/**
 * Persists unfinished message drafts per chat, keyed by conversation/session ID.
 * Saves are cheap (SharedPreferences.apply() is async).
 */
object DraftStore {
    private const val PREFS_NAME = "chat_drafts"
    const val KEY_NEW_CHAT = "__new_chat__"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(context: Context, chatId: String): String =
        prefs(context).getString(chatId, "") ?: ""

    fun set(context: Context, chatId: String, text: String) {
        prefs(context).edit().putString(chatId, text).apply()
    }

    fun clear(context: Context, chatId: String) {
        prefs(context).edit().remove(chatId).apply()
    }
}
