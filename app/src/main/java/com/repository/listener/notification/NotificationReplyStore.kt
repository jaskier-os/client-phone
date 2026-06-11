package com.repository.listener.notification

import android.app.PendingIntent
import android.app.RemoteInput
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide store of native reply actions captured from repliable
 * notifications, keyed by StatusBarNotification.key.
 *
 * TelegramNotificationListener populates this on onNotificationPosted when a
 * notification carries a reply action (an action with non-empty RemoteInputs).
 * ListenerService looks the entry up by sbn.key when a confirmed voice reply
 * (CH_NOTIF_REPLY_SEND) arrives and fires the stored PendingIntent with
 * RemoteInput results attached.
 *
 * Lifetime: entries are NOT evicted when the notification is dismissed/removed.
 * The RemoteInput PendingIntent stays valid after Telegram dismisses the
 * notification, and the user is frequently still mid-reply when that happens
 * (e.g. they read the chat, which removes the notification). Evicting on
 * onNotificationRemoved silently broke in-flight replies. Instead the store is
 * bounded by a TTL and a hard cap so it cannot grow unbounded.
 */
object NotificationReplyStore {

    data class ReplyAction(
        val actionIntent: PendingIntent,
        // The full RemoteInput array; RemoteInput.addResultsToIntent needs all of them.
        val remoteInputs: Array<RemoteInput>,
        // The chosen RemoteInput whose resultKey carries the typed text.
        val resultKey: String,
        val storedAt: Long = System.currentTimeMillis()
    )

    private const val TTL_MS = 10 * 60 * 1000L // 10 minutes: long enough to compose a reply
    private const val MAX_ENTRIES = 100

    private val store = ConcurrentHashMap<String, ReplyAction>()

    fun put(sbnKey: String, action: ReplyAction) {
        prune()
        store[sbnKey] = action
    }

    fun get(sbnKey: String): ReplyAction? {
        val entry = store[sbnKey] ?: return null
        if (System.currentTimeMillis() - entry.storedAt > TTL_MS) {
            store.remove(sbnKey)
            return null
        }
        return entry
    }

    fun remove(sbnKey: String) {
        store.remove(sbnKey)
    }

    /** Drop expired entries and, if still over the cap, the oldest ones. */
    private fun prune() {
        val now = System.currentTimeMillis()
        store.entries.removeAll { now - it.value.storedAt > TTL_MS }
        if (store.size >= MAX_ENTRIES) {
            store.entries
                .sortedBy { it.value.storedAt }
                .take(store.size - MAX_ENTRIES + 1)
                .forEach { store.remove(it.key) }
        }
    }
}
