package com.repository.listener.notification

import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.repository.listener.service.ListenerService
import com.repository.listener.util.LogCollector

/**
 * Listens for Telegram notifications and broadcasts them to ListenerService
 * for relay to glasses via CXR-M global toast API.
 *
 * Requires user to manually enable notification access in:
 * Settings > Notifications > Notification access
 */
class TelegramNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "TelegramNotif"

        private val TELEGRAM_PACKAGES = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta"
        )

        private const val DEDUP_COOLDOWN_MS = 3000L
        private const val PRUNE_INTERVAL_MS = 60_000L
        // On (re)connect we re-scan active (unswiped) notifications to catch any
        // that arrived during a brief listener gap. Only notifications posted within
        // this window are replayed; older ones are stale history. Without this, a
        // cold process restart (which wipes the in-memory seenKeys dedup) would
        // replay every unread Telegram notification -- hours of backlog -- at once.
        private const val RESCAN_FRESHNESS_MS = 60_000L
        private const val REBIND_INITIAL_DELAY_MS = 5_000L
        private const val REBIND_MAX_DELAY_MS = 60_000L

        @Volatile var isConnected = false
            private set

        @Volatile private var instance: TelegramNotificationListener? = null

        /**
         * Liveness probe: tries activeNotifications on the live instance.
         * Returns true if the NLS is genuinely alive, false if dead/silently killed.
         */
        fun probeAlive(): Boolean {
            if (!isConnected) return false
            val inst = instance ?: return false
            return try {
                inst.activeNotifications  // throws if NLS is unbound
                true
            } catch (_: Exception) {
                isConnected = false
                instance = null
                false
            }
        }

        // Survive NLS instance recreation on rebind so already-relayed
        // notifications are not replayed when onListenerConnected re-scans
        private val seenKeys = LinkedHashMap<String, Int>(64, 0.75f, false)
        private val recentRelays = LinkedHashMap<String, Long>(32, 0.75f, false)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var rebindDelay = REBIND_INITIAL_DELAY_MS
    private val pruneRunnable = object : Runnable {
        override fun run() {
            pruneOldEntries()
            handler.postDelayed(this, PRUNE_INTERVAL_MS)
        }
    }
    private val rebindRunnable = Runnable {
        LogCollector.i(TAG, "Attempting requestRebind after disconnect")
        try {
            requestRebind(ComponentName("com.repository.listener", TelegramNotificationListener::class.java.name))
        } catch (e: Exception) {
            LogCollector.i(TAG, "requestRebind failed: ${e.message}")
        }
    }

    override fun onListenerConnected() {
        isConnected = true
        instance = this
        rebindDelay = REBIND_INITIAL_DELAY_MS
        handler.removeCallbacks(rebindRunnable)
        LogCollector.i(TAG, "Notification listener connected")
        handler.postDelayed(pruneRunnable, PRUNE_INTERVAL_MS)
        // Re-scan unswiped Telegram notifications to catch any that arrived during a
        // brief listener gap -- but only RECENT ones. On a cold process restart the
        // in-memory seenKeys dedup is empty, so without a freshness filter every
        // unread notification Telegram still holds (hours of backlog) would be
        // replayed and TTS'd at once. onNotificationPosted also drops everything when
        // the glasses are not connected.
        try {
            val active = activeNotifications ?: return
            val now = System.currentTimeMillis()
            val telegramNotifs = active.filter { it.packageName in TELEGRAM_PACKAGES }
            val fresh = telegramNotifs.filter { now - it.postTime <= RESCAN_FRESHNESS_MS }
            LogCollector.i(TAG, "Active Telegram notifications: ${telegramNotifs.size}, fresh: ${fresh.size}")
            for (sbn in fresh) {
                onNotificationPosted(sbn)
            }
        } catch (e: Exception) {
            LogCollector.i(TAG, "Failed to scan active notifications: ${e.message}")
        }

    }

    override fun onListenerDisconnected() {
        isConnected = false
        instance = null
        LogCollector.i(TAG, "Notification listener DISCONNECTED, scheduling rebind in ${rebindDelay}ms")
        handler.removeCallbacks(pruneRunnable)
        handler.postDelayed(rebindRunnable, rebindDelay)
        rebindDelay = (rebindDelay * 2).coerceAtMost(REBIND_MAX_DELAY_MS)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName !in TELEGRAM_PACKAGES) return

        // Glasses are the only consumer of Telegram notifications. If they are not
        // connected, ignore the notification completely -- do not dedup, broadcast,
        // enqueue, request TTS, or wake the RFCOMM link. Gating here (before the
        // seenKeys bookkeeping) means a notification that arrives while the glasses
        // are absent is simply not delivered; Telegram re-posts it on update, so a
        // genuine later message is still picked up once the glasses are back.
        if (!ListenerService.glassesConnected) {
            LogCollector.i(TAG, "Dropped: glasses not connected")
            return
        }

        // Skip group summary notifications ("5 new messages from 4 chats")
        if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) {
            LogCollector.i(TAG, "Dropped: group summary notification")
            return
        }

        val extras = sbn.notification.extras ?: return
        val sender = extras.getCharSequence("android.title")?.toString() ?: return
        val text = extras.getCharSequence("android.text")?.toString() ?: return

        LogCollector.i(TAG, "Received: sender=$sender text=${text.take(30)}")

        if (text.isBlank() || sender.isBlank()) {
            LogCollector.i(TAG, "Dropped: blank sender or text")
            return
        }

        val textHash = text.hashCode()
        val key = sbn.key

        // Check if same sbn.key with unchanged text (Telegram re-posting same notification)
        val previousHash = seenKeys[key]
        if (previousHash == textHash) {
            LogCollector.i(TAG, "Dropped: unchanged sbn.key=$key")
            return
        }
        seenKeys[key] = textHash

        // Dedup rapid duplicates by content
        val dedupKey = "$sender:$textHash"
        val now = System.currentTimeMillis()
        val lastRelay = recentRelays[dedupKey]
        if (lastRelay != null && now - lastRelay < DEDUP_COOLDOWN_MS) {
            LogCollector.i(TAG, "Dropped: dedup cooldown for $sender")
            return
        }
        recentRelays[dedupKey] = now

        // Determine chat name for groups
        val isGroup = extras.getBoolean("android.isGroupConversation", false)
        val chat = if (isGroup) {
            extras.getCharSequence("android.subText")?.toString()
                ?: extras.getCharSequence("android.summaryText")?.toString()
                ?: ""
        } else ""

        // Locate the native reply action: first action carrying a RemoteInput.
        val replyAction = sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        val remoteInputs = replyAction?.remoteInputs
        val repliable = replyAction != null && !remoteInputs.isNullOrEmpty() && replyAction.actionIntent != null
        if (repliable) {
            NotificationReplyStore.put(
                key,
                NotificationReplyStore.ReplyAction(
                    actionIntent = replyAction!!.actionIntent,
                    remoteInputs = remoteInputs!!,
                    resultKey = remoteInputs[0].resultKey
                )
            )
            LogCollector.i(TAG, "Reply action stored for key=$key (resultKey=${remoteInputs[0].resultKey})")
        }

        // Chronological message time embedded by Telegram (when), falling back to
        // the notification post time, then receive-now. Used to order merged
        // same-sender segments regardless of out-of-order delivery. `now` (receive
        // time) is kept only for the dedup-cooldown logic above.
        val msgTime = sbn.notification.`when`.takeIf { it > 0L } ?: sbn.postTime.takeIf { it > 0L } ?: now

        LogCollector.i(TAG, "Telegram notification: sender=$sender chat=$chat text=${text.take(40)} repliable=$repliable msgTime=$msgTime")

        sendBroadcast(Intent(ListenerService.ACTION_TELEGRAM_NOTIF).apply {
            setPackage(packageName)
            putExtra(ListenerService.EXTRA_TG_SENDER, sender)
            putExtra(ListenerService.EXTRA_TG_TEXT, text)
            putExtra(ListenerService.EXTRA_TG_CHAT, chat)
            putExtra(ListenerService.EXTRA_TG_TIMESTAMP, msgTime)
            putExtra(ListenerService.EXTRA_TG_REPLIABLE, repliable)
            putExtra(ListenerService.EXTRA_TG_SBN_KEY, key)
        })
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Intentionally do NOT evict the stored reply action here. Telegram removes
        // the notification the moment the user reads the chat -- often while they are
        // still composing a voice reply -- but the RemoteInput PendingIntent stays
        // valid. Evicting on removal silently broke those in-flight replies (the send
        // found no stored action and failed). NotificationReplyStore now self-bounds
        // via TTL + cap instead.
    }

    private fun pruneOldEntries() {
        val now = System.currentTimeMillis()
        val cutoff = now - 30_000L
        recentRelays.entries.removeAll { it.value < cutoff }
        // Cap seenKeys to prevent unbounded growth
        while (seenKeys.size > 200) {
            seenKeys.remove(seenKeys.keys.first())
        }
    }
}
