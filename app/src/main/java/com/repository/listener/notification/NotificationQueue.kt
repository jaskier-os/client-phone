package com.repository.listener.notification

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Sequential notification queue. Processes one notification at a time:
 * enqueue -> request TTS -> send to glasses -> wait for glasses "done" -> next.
 * Per-sender cap of 100 (evicts oldest queued from same sender).
 *
 * TTS sender prefix logic:
 * - Tracks the last announced sender. Same sender = no "X wrote:" prefix.
 * - Different sender = include "X wrote:" prefix (context switch).
 * - After 5s of no new notifications, resets so next message has no prefix.
 *
 * Processing is always deferred via handler.post to allow multiple enqueue() calls
 * to accumulate before the first processNext() runs.
 */
class NotificationQueue {

    data class QueuedNotification(
        val notifId: String,
        val sender: String,
        var text: String,
        val chat: String,
        val repliable: Boolean = false,
        val sbnKey: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        // Each merged same-sender message is kept as a (messageTime, text) segment.
        // The rendered body (`text`) is always combinedText() so reordering on
        // out-of-order delivery is just a re-sort, not an append.
        val segments: MutableList<Pair<Long, String>> = mutableListOf(timestamp to text)

        /** Combined body, segments sorted chronologically by message time. */
        fun combinedText(): String = segments.sortedBy { it.first }.joinToString("\n") { it.second }
    }

    data class QueueSnapshot(
        val queueSize: Int,
        val isProcessing: Boolean,
        val currentNotifId: String?,
        val currentSender: String?,
        val queued: List<Triple<String, String, String>> // notifId, sender, text preview
    )

    interface Callback {
        /** @param ttsSender sender for TTS; empty = omit "X wrote:" prefix */
        fun onRequestTts(notifId: String, ttsSender: String, text: String, chat: String)
        fun onSendToGlasses(notifId: String, sender: String, text: String, chat: String, repliable: Boolean, sbnKey: String?, audioBase64: String?)
        /** Replace an already-sent notification's overlay text with the full recomputed body and restart its timer. */
        fun onSetGlassesText(notifId: String, fullText: String)
        /**
         * Speak a same-sender message that was MERGED into an on-screen notification.
         * The merge is UI-only (one overlay), but each absorbed message must still be
         * heard -- as a continuation (no "X wrote:" prefix). Plays out-of-band on the
         * glasses without disturbing the queue's in-flight state machine for notifId.
         */
        fun onRequestAppendTts(notifId: String, text: String, chat: String)
        fun log(message: String)
    }

    var callback: Callback? = null

    private val queue = ConcurrentLinkedDeque<QueuedNotification>()
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var currentNotif: QueuedNotification? = null
    // True once currentNotif's CH_NOTIFICATION frame has actually been pushed to the
    // glasses (i.e. an overlay exists there). Distinguishes "picked by processNext but
    // not yet sent" from "on-screen, awaiting DONE" for same-sender merge routing.
    @Volatile private var currentSentToGlasses = false
    @Volatile private var isProcessing = false
    @Volatile private var ttsReceived = false

    private val ttsAudioBuffer = StringBuilder()

    // Sender prefix control: reset after 5s of inactivity
    private var lastAnnouncedSender: String? = null
    private val senderResetRunnable = Runnable {
        callback?.log("[NotifQ] Sender prefix reset (5s inactivity)")
        lastAnnouncedSender = null
    }

    private companion object {
        const val PER_SENDER_CAP = 100
        const val TTS_TIMEOUT_MS = 30_000L
        const val GLASSES_DONE_TIMEOUT_MS = 20_000L
        const val SENDER_RESET_MS = 10_000L
    }

    private val glassesDoneTimeout = Runnable {
        val notif = currentNotif ?: return@Runnable
        callback?.log("[NotifQ] Glasses done timeout for ${notif.notifId}, advancing queue")
        advanceQueue()
    }

    private var ttsRetryCount = 0
    private val MAX_TTS_RETRIES = 1

    private fun handleTtsTimeout() {
        val notif = currentNotif ?: return
        if (!ttsReceived && ttsRetryCount < MAX_TTS_RETRIES) {
            ttsRetryCount++
            callback?.log("[NotifQ] TTS timeout for ${notif.notifId}, retrying (attempt ${ttsRetryCount})")
            callback?.onRequestTts(notif.notifId, "", notif.text, notif.chat)
            handler.postDelayed(ttsTimeout, TTS_TIMEOUT_MS)
        } else if (!ttsReceived) {
            callback?.log("[NotifQ] TTS timeout for ${notif.notifId}, sending without audio")
            sendCurrentToGlasses(null)
        }
    }

    private val ttsTimeout = Runnable { handleTtsTimeout() }

    fun enqueue(notifId: String, sender: String, text: String, chat: String, repliable: Boolean = false, sbnKey: String? = null, timestamp: Long = System.currentTimeMillis()) {
        // Cancel any pending sender reset (queue is active again)
        handler.removeCallbacks(senderResetRunnable)

        val notif = QueuedNotification(notifId, sender, text, chat, repliable, sbnKey, timestamp)
        var shouldSchedule = false

        synchronized(lock) {
            // Same-sender merge: if there is an in-flight notification (picked by
            // processNext, not yet DONE) from the same sender, absorb this one into it
            // instead of enqueuing a separate item. This is what lets the glasses show
            // chained same-sender messages on a single overlay -- a strictly serialized
            // queue otherwise never has two of them live at once.
            val inFlight = currentNotif
            if (inFlight != null && inFlight.sender == sender) {
                inFlight.segments.add(timestamp to text)
                inFlight.text = inFlight.combinedText()
                if (currentSentToGlasses) {
                    // The overlay already exists on the glasses: replace its text with
                    // the full recomputed (chronologically sorted) body and restart its
                    // dismissal timer there. A SET/replace reorders correctly even when
                    // messages were delivered out of order; an append could not.
                    callback?.onSetGlassesText(inFlight.notifId, inFlight.text)
                    handler.removeCallbacks(glassesDoneTimeout)
                    handler.postDelayed(glassesDoneTimeout, GLASSES_DONE_TIMEOUT_MS)
                    callback?.log("[NotifQ] Merged same-sender into ${inFlight.notifId}, now ${inFlight.segments.size} segments (set full text)")
                    // The merge is UI-only; still SPEAK this absorbed message as a
                    // continuation (no sender prefix -- same sender already announced).
                    callback?.onRequestAppendTts(inFlight.notifId, text, chat)
                } else {
                    // Rare: incoming arrived AFTER processNext already requested TTS for
                    // the first segment (with the old single-segment text) but BEFORE the
                    // send completed. The visual frame will carry the combined text, but
                    // the already-sent TTS request captured only the first segment -- so
                    // this absorbed segment would otherwise be spoken nowhere. Speak it as
                    // a continuation too. (Tiny risk of double-speak only if the merge
                    // landed before processNext's onRequestTts; silent loss is worse.)
                    callback?.onRequestAppendTts(inFlight.notifId, text, chat)
                    callback?.log("[NotifQ] Merged same-sender into not-yet-sent ${inFlight.notifId}, now ${inFlight.segments.size} segments (in-place + continuation tts)")
                }
                return@enqueue
            }

            // Per-sender cap
            val queuedFromSender = queue.count { it.sender == sender }
            val currentFromSender = if (currentNotif?.sender == sender) 1 else 0
            val totalFromSender = queuedFromSender + currentFromSender

            if (totalFromSender >= PER_SENDER_CAP) {
                val oldest = queue.filter { it.sender == sender }.minByOrNull { it.timestamp }
                if (oldest != null) {
                    queue.remove(oldest)
                    callback?.log("[NotifQ] Evicted oldest from $sender: ${oldest.notifId} (cap=$PER_SENDER_CAP)")
                }
            }

            queue.addLast(notif)
            callback?.log("[NotifQ] Enqueued ${notif.notifId} from $sender (queueSize=${queue.size})")

            if (!isProcessing) {
                isProcessing = true
                shouldSchedule = true
            }
        }

        if (shouldSchedule) {
            handler.post { processNext() }
        }
    }

    /** Called when TTS audio chunk arrives from orchestrator. */
    fun onTtsAudio(notifId: String, audioBase64: String, isFinal: Boolean) {
        val notif = currentNotif
        if (notif == null || notif.notifId != notifId) {
            callback?.log("[NotifQ] TTS audio for stale notifId=$notifId, ignoring (current=${currentNotif?.notifId})")
            return
        }

        ttsReceived = true
        handler.removeCallbacks(ttsTimeout)

        if (audioBase64.isNotEmpty()) {
            ttsAudioBuffer.append(audioBase64)
            callback?.log("[NotifQ] TTS chunk: $notifId (+${audioBase64.length} chars, total=${ttsAudioBuffer.length}, final=$isFinal)")
        }

        if (isFinal) {
            val fullAudio = if (ttsAudioBuffer.isNotEmpty()) ttsAudioBuffer.toString() else null
            sendCurrentToGlasses(fullAudio)
        } else {
            // Re-arm timeout for next chunk - if remaining chunks never arrive,
            // send what we have after timeout instead of blocking the queue forever
            handler.postDelayed(ttsTimeout, TTS_TIMEOUT_MS)
        }
    }

    /** Called when glasses signals notification is done (TTS played + overlay dismissed). */
    fun onGlassesDone(notifId: String) {
        val notif = currentNotif
        if (notif == null || notif.notifId != notifId) {
            callback?.log("[NotifQ] Glasses done for stale notifId=$notifId, ignoring (current=${currentNotif?.notifId})")
            return
        }
        callback?.log("[NotifQ] Glasses done: $notifId")
        advanceQueue()
    }

    /** Clear queue and reset state (e.g. on BT disconnect). */
    fun clear(reason: String) {
        handler.removeCallbacks(senderResetRunnable)
        lastAnnouncedSender = null

        val dropped: Int
        synchronized(lock) {
            dropped = queue.size
            queue.clear()
            isProcessing = false
        }
        handler.removeCallbacks(glassesDoneTimeout)
        handler.removeCallbacks(ttsTimeout)
        currentNotif = null
        currentSentToGlasses = false
        ttsReceived = false
        ttsAudioBuffer.clear()
        callback?.log("[NotifQ] Clear: reason=$reason (dropped=$dropped)")
    }

    fun snapshot(): QueueSnapshot {
        synchronized(lock) {
            return QueueSnapshot(
                queueSize = queue.size,
                isProcessing = isProcessing,
                currentNotifId = currentNotif?.notifId,
                currentSender = currentNotif?.sender,
                queued = queue.map { Triple(it.notifId, it.sender, it.text.take(60)) }
            )
        }
    }

    private fun processNext() {
        val notif: QueuedNotification?
        synchronized(lock) {
            notif = queue.pollFirst()
            if (notif == null) {
                isProcessing = false
            }
        }
        if (notif == null) {
            currentNotif = null
            // Start sender reset timer now that queue is idle
            handler.postDelayed(senderResetRunnable, SENDER_RESET_MS)
            callback?.log("[NotifQ] Queue empty, idle (sender reset in ${SENDER_RESET_MS}ms)")
            return
        }

        currentNotif = notif
        currentSentToGlasses = false
        ttsReceived = false
        ttsRetryCount = 0
        ttsAudioBuffer.clear()

        // Include "X wrote:" on first message or when sender changes
        val includeSender = lastAnnouncedSender != notif.sender
        val ttsSender = if (includeSender) notif.sender else ""
        lastAnnouncedSender = notif.sender

        callback?.log("[NotifQ] Processing ${notif.notifId} from ${notif.sender} (remaining=${queue.size}, senderPrefix=${includeSender})")
        callback?.onRequestTts(notif.notifId, ttsSender, notif.text, notif.chat)

        handler.postDelayed(ttsTimeout, TTS_TIMEOUT_MS)
    }

    private fun sendCurrentToGlasses(audioBase64: String?) {
        val notif = currentNotif ?: return
        handler.removeCallbacks(ttsTimeout)
        ttsAudioBuffer.clear()

        callback?.log("[NotifQ] Sending to glasses: ${notif.notifId} (hasAudio=${audioBase64 != null}, audioLen=${audioBase64?.length ?: 0}, repliable=${notif.repliable})")
        callback?.onSendToGlasses(notif.notifId, notif.sender, notif.text, notif.chat, notif.repliable, notif.sbnKey, audioBase64)
        currentSentToGlasses = true

        handler.postDelayed(glassesDoneTimeout, GLASSES_DONE_TIMEOUT_MS)
    }

    private fun advanceQueue() {
        handler.removeCallbacks(glassesDoneTimeout)
        handler.removeCallbacks(ttsTimeout)
        currentNotif = null
        currentSentToGlasses = false
        ttsReceived = false
        ttsAudioBuffer.clear()
        handler.post { processNext() }
    }
}
