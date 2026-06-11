package com.repository.listener.network

import android.os.Handler
import android.os.Looper
import com.repository.listener.util.LogCollector
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Android's [JSONObject.optString] with a null default returns the literal string "null"
 *  when the key is absent. This extension returns a real null instead. */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

class OrchestratorClient(
    private val url: String,
    private val deviceId: String,
    private val apiKey: String
) {

    companion object {
        private const val TAG = "OrchestratorClient"

        /** Strip null bytes and lone surrogates that crash JNI Modified UTF-8. */
        fun sanitizeUtf8(input: String): String {
            var needsSanitize = false
            for (i in input.indices) {
                val c = input[i]
                if (c == '\u0000' || c.isSurrogate()) { needsSanitize = true; break }
            }
            if (!needsSanitize) return input
            val sb = StringBuilder(input.length)
            var i = 0
            while (i < input.length) {
                val c = input[i]
                when {
                    c == '\u0000' -> {}
                    c.isHighSurrogate() -> {
                        if (i + 1 < input.length && input[i + 1].isLowSurrogate()) {
                            sb.append(c); sb.append(input[i + 1]); i++
                        }
                    }
                    c.isLowSurrogate() -> {}
                    else -> sb.append(c)
                }
                i++
            }
            return sb.toString()
        }
    }

    interface TranscribeStreamListener {
        fun onTranscription(segId: Int, text: String, translation: String, partial: Boolean)
        fun onFinal(segId: Int, text: String, translation: String)
        fun onStreamError(message: String)
    }

    interface MessageListener {
        fun onDeviceCommand(requestId: String, command: JSONObject)
        fun onResponse(requestId: String, text: String, status: String, data: JSONObject?, totalTokens: Int)
        /**
         * The orchestrator parsed and accepted a chat request; the AI hasn't
         * answered yet. Used to clear pendingGlassesRetry so a heartbeat-driven
         * reconnect can't redrive a request that already landed -- which used
         * to produce duplicate AI answers + duplicate TTS for the same prompt.
         */
        fun onRequestAck(requestId: String) {}
        fun onStreamingText(requestId: String, partialText: String, isFinal: Boolean)
        /**
         * Assistant fact-check result. cards = new overlay cards to draw,
         * dismiss = card IDs to remove because the wearer used their info.
         */
        fun onAssistantResult(requestId: String, cards: org.json.JSONArray, dismiss: org.json.JSONArray) {}
        fun onTtsAudio(requestId: String, audioBase64: String, sentenceIndex: Int, totalSentences: Int, text: String, isFinal: Boolean)
        fun onToolStatus(requestId: String, toolName: String, status: String, toolArgs: JSONObject?, toolCallId: String)
        fun onServerError(message: String)
        fun onConnected()
        fun onDisconnected()
        fun onBinaryFrame(data: ByteArray) {}
        fun onStreamAck(streamId: Int, width: Int, height: Int, fps: Int, monitorCount: Int) {}
        fun onStreamEnded(streamId: Int) {}
        fun onAudioRelayAck(sampleRate: Int, channels: Int, bitrate: Int, frameSize: Int, frameDurationMs: Int) {}
        fun onAudioRelayError(reason: String) {}
        fun onStreamError(reason: String, streamId: Int) {}
        fun onStreamConnect(streamId: Int, streamType: String, endpoint: String, token: String) {}
        fun onTodoResult(data: String) {}
        fun onJobResult(data: String) {}
        fun onJobNotification(jobId: String, jobName: String, status: String, result: String) {}
        fun onTelegramSavedResult(data: String) {}
        fun onTelegramSavedError(error: String) {}
        fun onTelegramChatListResult(data: String, error: String?) {}
        fun onTelegramMessagesResult(chatId: String, data: String, error: String?) {}
        fun onTelegramSendResult(data: String, error: String?) {}
        fun onTelegramNewMessage(messageJson: String) {}
        fun onTelegramTopicsResult(chatId: String, data: String, error: String?) {}
        fun onTelegramUserStatus(userId: String, isOnline: Boolean, lastSeen: String?) {}
        fun onNotificationTtsAudio(notifId: String, audioBase64: String, isFinal: Boolean) {}
        fun onReidMerge(sourcePersonId: String, targetPersonId: String, targetDisplayName: String) {}
        fun onWebRTCOffer(streamId: Int, sdp: String) {}
        fun onWebRTCIce(streamId: Int, candidate: String, sdpMid: String, sdpMLineIndex: Int) {}

        // Remote Control callbacks
        fun onRcSessionStart(sessionId: String, workDir: String) {}
        fun onRcSessionEnd(sessionId: String) {}
        fun onRcMessage(sessionId: String, text: String, isFinal: Boolean, requestId: String?, contextPct: Int = -1, costUsd: Double = -1.0) {}
        fun onRcPermissionRequest(sessionId: String, toolName: String, toolArgs: String, requestId: String, description: String?) {}
        fun onRcToolStatus(sessionId: String, toolName: String, status: String, result: String?, toolArgs: String?, toolCallId: String? = null, contextPct: Int = -1, isAgent: Boolean = false, agentName: String? = null, agentTask: String? = null, agentToolCount: Int? = null, agentTokens: Long? = null, agentElapsedMs: Long? = null) {}
        fun onRcThinkingEnd(sessionId: String, elapsedMs: Long) {}
        fun onRcPlanUpdate(sessionId: String, entering: Boolean, planContent: String?) {}
        fun onRcAgentStatus(sessionId: String, agentId: String, name: String, status: String, depth: Int) {}
        fun onRcThinking(sessionId: String, text: String, startedAt: Long) {}
        fun onRcModeChange(sessionId: String, newMode: String) {}
        fun onRcUserInput(sessionId: String, prompt: String, requestId: String) {}
        fun onRcTranscript(sessionId: String, transcript: String) {}
        fun onRcError(sessionId: String, errorText: String, source: String) {}

        /**
         * Status update for a phone-originated rc_user_message. The phone-side
         * queue retries on disconnect/send-failure with capped exponential
         * backoff; the UI shows the current attempt + countdown to the next
         * one. Status values:
         *   "sending"   -- first attempt in flight, no failure yet
         *   "queued"    -- queued for later (WS not OPEN, waiting reconnect)
         *   "retrying"  -- previous attempt failed, retrying after backoff
         *   "delivered" -- ack received from orchestrator, no more retries
         *   "failed"    -- exceeded max attempts; permanently dropped
         * `nextRetryAtMs` is wall-clock epoch-ms of the next attempt (0 if
         * status is delivered or failed).
         */
        fun onRcUserMessageStatus(sessionId: String, requestId: String, status: String, attempt: Int, nextRetryAtMs: Long) {}
    }

    var lastRequestId: String? = null
        private set

    @Volatile
    var isConnected = false
        private set

    private var ws: WebSocket? = null
    private var client: OkHttpClient? = null
    private var listener: MessageListener? = null
    @Volatile
    private var reconnectAttempts = 0
    @Volatile
    private var identified = false
    @Volatile
    private var draining: Boolean = false
    private val pendingSends: java.util.ArrayDeque<String> = java.util.ArrayDeque()
    private val streamConnections = mutableMapOf<Pair<Int, String>, StreamWebSocket>()
    private val baseWsUrl: String = url.removeSuffix("/ws/device")
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var connectWatchdogRunnable: Runnable? = null
    private val CONNECT_WATCHDOG_MS = 15_000L
    @Volatile
    private var isShutdown = false
    @Volatile
    private var reconnectScheduled = false

    // Separate heartbeat stream -- independent of data WebSocket so TTS floods can't block it
    private var heartbeatExecutor: java.util.concurrent.ScheduledExecutorService? = null
    private val heartbeatHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()
    private val healthUrl: String = baseWsUrl
        .replace("ws://", "http://")
        .replace("wss://", "https://") + "/api/v1/health"
    @Volatile
    private var consecutiveHeartbeatFailures = 0
    private val MAX_HEARTBEAT_FAILURES = 3
    @Volatile
    private var lastWsFrameAt = 0L
    private val WS_STALE_THRESHOLD_MS = 5 * 60 * 1000L

    // ---- rc_user_message retry queue ----------------------------------------
    // Each phone-originated user message gets a client UUID. We hold it here
    // until the orchestrator acks it (rc_user_message_ack with the same
    // requestId). On send-failure or WS-not-open we reschedule with capped
    // exponential backoff. On WS reconnect we redrain. The UI is told about
    // every state change via onRcUserMessageStatus so it can render a
    // "Sending - retry N in Ks" countdown.
    private data class PendingRcMsg(
        val requestId: String,
        val sessionId: String,
        val text: String,
        var attempt: Int,
        var nextRetryAtMs: Long,
        var retryRunnable: Runnable? = null,
    )
    private val pendingRcMsgs: java.util.LinkedHashMap<String, PendingRcMsg> = java.util.LinkedHashMap()
    private val RC_RETRY_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 20_000L)
    private val RC_RETRY_MAX_BACKOFF_MS = 20_000L
    private val RC_MAX_ATTEMPTS = 12  // ~ first 6 ramps then 6 * 20s = ~150s total

    private fun rcBackoffForAttempt(attempt: Int): Long {
        // attempt is the 1-based index of the attempt we just FINISHED. Schedule
        // the next one at backoff[attempt] (so after attempt 1 we wait 1s,
        // after attempt 2 we wait 2s, etc.)
        val idx = (attempt).coerceAtLeast(1) - 1
        return if (idx < RC_RETRY_BACKOFF_MS.size) RC_RETRY_BACKOFF_MS[idx] else RC_RETRY_MAX_BACKOFF_MS
    }

    @Volatile
    private var transcribeWs: WebSocket? = null
    private var transcribeClient: OkHttpClient? = null
    private var transcribeListener: TranscribeStreamListener? = null
    // Audio batching: accumulate small chunks into ~100ms frames to avoid flooding WS
    private val transcribeAudioBuf = java.io.ByteArrayOutputStream()
    private val TRANSCRIBE_BATCH_BYTES = 3200  // 100ms at 16kHz 16-bit mono
    val isTranscribeConnected: Boolean get() = transcribeWs != null

    fun setListener(listener: MessageListener) {
        this.listener = listener
    }

    fun connect() {
        if (isShutdown) return

        reconnectScheduled = false

        // Cancel any previous watchdog
        connectWatchdogRunnable?.let { handler.removeCallbacks(it) }

        client = OkHttpClient.Builder()
            .pingInterval(0, TimeUnit.SECONDS)  // Disabled: health check is separate HTTP stream
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .build()

        ws = client!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                LogCollector.i(TAG, "WebSocket connected to $url")
                connectWatchdogRunnable?.let { handler.removeCallbacks(it) }
                reconnectAttempts = 0
                isConnected = true
                lastWsFrameAt = System.currentTimeMillis()
                val identifyMsg = Protocol.createIdentifyMessage(deviceId)
                webSocket.send(identifyMsg.toString())
                LogCollector.i(TAG, "Sent identify message for device $deviceId")
                identified = true
                // Drain any messages enqueued while disconnected.
                // Snapshot under lock with draining=true so concurrent sendOrQueue
                // calls go to the queue and preserve order.
                val snapshot: ArrayList<String>
                synchronized(pendingSends) {
                    draining = true
                    snapshot = ArrayList(pendingSends)
                    pendingSends.clear()
                }
                var drained = 0
                var failedAt = -1
                for (i in snapshot.indices) {
                    val msg = snapshot[i]
                    val ok = try { webSocket.send(msg) } catch (_: Exception) { false }
                    if (ok) drained++ else { failedAt = i; break }
                }
                if (failedAt >= 0) {
                    // Prepend remaining items back to preserve original order.
                    synchronized(pendingSends) {
                        val rest = snapshot.subList(failedAt, snapshot.size)
                        val newDeque = java.util.ArrayDeque<String>(rest.size + pendingSends.size)
                        newDeque.addAll(rest)
                        newDeque.addAll(pendingSends)
                        pendingSends.clear()
                        pendingSends.addAll(newDeque)
                    }
                }
                synchronized(pendingSends) { draining = false }
                if (drained > 0) LogCollector.i(TAG, "Drained $drained pending messages on reconnect")
                synchronized(this@OrchestratorClient) {
                    reconnectScheduled = false
                }
                startHeartbeat()
                // Re-fire any rc_user_message that was queued or in-flight when
                // the previous WS died. Each goes through the normal retry
                // path so the UI sees a status update.
                drainRcUserMessages()
                listener?.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                lastWsFrameAt = System.currentTimeMillis()
                // Binary frame: notification TTS audio [notifId 36 bytes] + [audio data]
                if (bytes.size > 36) {
                    val notifId = bytes.substring(0, 36).utf8().trimEnd('\u0000')
                    val audioBytes = bytes.substring(36)
                    val audioBase64 = audioBytes.base64()
                    LogCollector.i(TAG, "Binary TTS audio: $notifId (${audioBytes.size} bytes)")
                    listener?.onNotificationTtsAudio(notifId, audioBase64, true)
                } else {
                    LogCollector.w(TAG, "Unexpected binary frame on main WS (${bytes.size} bytes)")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastWsFrameAt = System.currentTimeMillis()
                // Sanitize: strip null bytes and lone surrogates that crash JNI Modified UTF-8
                val safeText = sanitizeUtf8(text)
                try {
                    val envelope = JSONObject(safeText)
                    val type = envelope.optString("type", "")
                    if (type != "health") {
                        LogCollector.d(TAG, "WS frame: type=$type (${text.length} chars)")
                    }

                    when (type) {
                        Protocol.TYPE_DEVICE_COMMAND -> {
                            val requestId = envelope.optString("requestId", "")
                            val command = envelope.optJSONObject("command") ?: JSONObject()
                            LogCollector.i(TAG, "Device command received: requestId=$requestId")
                            listener?.onDeviceCommand(requestId, command)
                        }
                        Protocol.TYPE_REQUEST_ACK -> {
                            val requestId = envelope.optString("requestId", "")
                            if (requestId.isNotEmpty()) listener?.onRequestAck(requestId)
                        }
                        Protocol.TYPE_RESPONSE -> {
                            val requestId = envelope.optString("requestId", "")
                            val responseText = envelope.optString("text", "")
                            val responseStatus = envelope.optString("status", "")
                            val responseData = envelope.optJSONObject("data")
                            val usage = envelope.optJSONObject("usage")
                            val totalTokens = usage?.optInt("total_tokens", 0) ?: 0
                            LogCollector.i(TAG, "Response received: requestId=$requestId status=$responseStatus tokens=$totalTokens")
                            listener?.onResponse(requestId, responseText, responseStatus, responseData, totalTokens)
                        }
                        Protocol.TYPE_STREAMING_TEXT -> {
                            val requestId = envelope.optString("requestId", "")
                            val partialText = envelope.optString("text", "")
                            val isFinal = envelope.optBoolean("isFinal", false)
                            listener?.onStreamingText(requestId, partialText, isFinal)
                        }
                        Protocol.TYPE_ASSISTANT_RESULT -> {
                            val requestId = envelope.optString("requestId", "")
                            val cards = envelope.optJSONArray("cards") ?: org.json.JSONArray()
                            val dismiss = envelope.optJSONArray("dismiss") ?: org.json.JSONArray()
                            listener?.onAssistantResult(requestId, cards, dismiss)
                        }
                        Protocol.TYPE_TOOL_STATUS -> {
                            val requestId = envelope.optString("requestId", "")
                            val toolName = envelope.optString("toolName", "")
                            val status = envelope.optString("status", "")
                            val toolArgs = envelope.optJSONObject("toolArgs")
                            val toolCallId = envelope.optString("toolCallId", "")
                            listener?.onToolStatus(requestId, toolName, status, toolArgs, toolCallId)
                        }
                        Protocol.TYPE_TTS_AUDIO -> {
                            val requestId = envelope.optString("requestId", "")
                            val audioBase64 = envelope.optString("audioBase64", "")
                            val sentenceIndex = envelope.optInt("sentenceIndex", 0)
                            val totalSentences = envelope.optInt("totalSentences", 0)
                            val text = envelope.optString("text", "")
                            val isFinal = envelope.optBoolean("isFinal", false)
                            LogCollector.i(TAG, "TTS audio received: requestId=$requestId sentence=${sentenceIndex}/${totalSentences}")
                            listener?.onTtsAudio(requestId, audioBase64, sentenceIndex, totalSentences, text, isFinal)
                        }
                        Protocol.TYPE_NOTIFICATION_TTS_AUDIO -> {
                            val notifId = envelope.optString("notifId", "")
                            val audioBase64 = envelope.optString("audioBase64", "")
                            val isFinal = envelope.optBoolean("isFinal", true)
                            val error = envelope.optString("error", "")
                            if (error.isNotEmpty()) {
                                LogCollector.i(TAG, "Notification TTS failed for $notifId: $error")
                            } else {
                                LogCollector.i(TAG, "Notification TTS audio: $notifId (${audioBase64.length} chars, final=$isFinal)")
                            }
                            listener?.onNotificationTtsAudio(notifId, audioBase64, isFinal)
                        }
                        Protocol.TYPE_STREAM_ACK -> {
                            val streamId = envelope.optInt("streamId", 0)
                            val width = envelope.optInt("width", 1280)
                            val height = envelope.optInt("height", 720)
                            val fps = envelope.optInt("fps", 24)
                            val monitorCount = envelope.optInt("monitorCount", 1)
                            LogCollector.i(TAG, "Stream ACK: streamId=$streamId ${width}x${height} @${fps}fps monitors=$monitorCount")
                            listener?.onStreamAck(streamId, width, height, fps, monitorCount)
                        }
                        Protocol.TYPE_STREAM_ENDED -> {
                            val streamId = envelope.optInt("streamId", 0)
                            LogCollector.i(TAG, "Stream ended: streamId=$streamId")
                            listener?.onStreamEnded(streamId)
                        }
                        Protocol.TYPE_AUDIO_RELAY_ACK -> {
                            val sampleRate = envelope.optInt("sampleRate", 48000)
                            val channels = envelope.optInt("channels", 2)
                            val bitrate = envelope.optInt("bitrate", 64000)
                            val frameSize = envelope.optInt("frameSize", 2880)
                            val frameDurationMs = envelope.optInt("frameDurationMs", 60)
                            LogCollector.i(TAG, "Audio relay ACK: ${sampleRate}Hz ${channels}ch ${bitrate}bps frame=${frameDurationMs}ms")
                            listener?.onAudioRelayAck(sampleRate, channels, bitrate, frameSize, frameDurationMs)
                        }
                        Protocol.TYPE_AUDIO_RELAY_ERROR -> {
                            val reason = envelope.optString("reason", "unknown")
                            LogCollector.e(TAG, "Audio relay error: $reason")
                            listener?.onAudioRelayError(reason)
                        }
                        Protocol.TYPE_STREAM_ERROR -> {
                            val reason = envelope.optString("reason", "unknown")
                            val streamId = envelope.optInt("streamId", 0)
                            LogCollector.e(TAG, "Stream error: $reason (streamId=$streamId)")
                            listener?.onStreamError(reason, streamId)
                        }
                        Protocol.TYPE_STREAM_CONNECT -> {
                            val streamId = envelope.optInt("streamId", 0)
                            val streamType = envelope.optString("streamType", "")
                            val endpoint = envelope.optString("endpoint", "")
                            val token = envelope.optString("token", "")
                            LogCollector.i(TAG, "Stream connect: $streamType streamId=$streamId")
                            listener?.onStreamConnect(streamId, streamType, endpoint, token)
                        }
                        "todo_result" -> {
                            val data = envelope.optJSONArray("todos")?.toString()
                                ?: envelope.optJSONObject("todo")?.let { "[$it]" }
                                ?: "[]"
                            LogCollector.i(TAG, "Todo result received")
                            listener?.onTodoResult(data)
                        }
                        "job_result" -> {
                            val jobsArr = envelope.optJSONArray("jobs")
                            if (jobsArr != null) {
                                LogCollector.i(TAG, "Job result received: ${jobsArr.length()} jobs")
                                listener?.onJobResult(jobsArr.toString())
                            }
                        }
                        "job_notification" -> {
                            val jobId = envelope.optString("jobId", "")
                            val jobName = envelope.optString("jobName", "")
                            val status = envelope.optString("status", "")
                            val result = if (envelope.isNull("result")) "" else envelope.optString("result", "")
                            LogCollector.i(TAG, "Job notification: jobId=$jobId status=$status")
                            listener?.onJobNotification(jobId, jobName, status, result)
                        }
                        "telegram_saved_result" -> {
                            val error = envelope.optString("error", null)
                            val data = envelope.optJSONArray("messages")?.toString() ?: "[]"
                            if (error != null) {
                                LogCollector.e(TAG, "Telegram saved error: $error")
                                listener?.onTelegramSavedError(error)
                            } else {
                                LogCollector.i(TAG, "Telegram saved result received")
                                listener?.onTelegramSavedResult(data)
                            }
                        }
                        Protocol.TYPE_TG_CHAT_LIST_RESULT -> {
                            val error = envelope.optString("error", null)
                            val data = envelope.optJSONArray("chats")?.toString() ?: "[]"
                            LogCollector.i(TAG, "Telegram chat list result: ${data.length} chars")
                            listener?.onTelegramChatListResult(data, error)
                        }
                        Protocol.TYPE_TG_MESSAGES_RESULT -> {
                            val chatId = envelope.optString("chatId", "")
                            val error = envelope.optString("error", null)
                            val data = envelope.optJSONArray("messages")?.toString() ?: "[]"
                            LogCollector.i(TAG, "Telegram messages result: chatId=$chatId ${data.length} chars")
                            listener?.onTelegramMessagesResult(chatId, data, error)
                        }
                        Protocol.TYPE_TG_TOPICS_RESULT -> {
                            val chatId = envelope.optString("chatId", "")
                            val error = envelope.optString("error", null)
                            val data = envelope.optJSONArray("topics")?.toString() ?: "[]"
                            LogCollector.i(TAG, "Telegram topics result: chatId=$chatId ${data.length} chars")
                            listener?.onTelegramTopicsResult(chatId, data, error)
                        }
                        Protocol.TYPE_TG_SEND_RESULT -> {
                            val error = envelope.optString("error", null)
                            val data = envelope.toString()
                            LogCollector.i(TAG, "Telegram send result received")
                            listener?.onTelegramSendResult(data, error)
                        }
                        Protocol.TYPE_TG_NEW_MESSAGE -> {
                            val message = envelope.optJSONObject("message")?.toString() ?: "{}"
                            LogCollector.i(TAG, "Telegram new message received")
                            listener?.onTelegramNewMessage(message)
                        }
                        "telegram_user_status" -> {
                            val userId = envelope.optString("userId", "")
                            val isOnline = envelope.optBoolean("isOnline", false)
                            val lastSeen = if (envelope.has("lastSeen") && !envelope.isNull("lastSeen")) envelope.optString("lastSeen") else null
                            listener?.onTelegramUserStatus(userId, isOnline, lastSeen)
                        }
                        "reid_merge" -> {
                            val sourcePersonId = envelope.optString("source_person_id", "")
                            val targetPersonId = envelope.optString("target_person_id", "")
                            val targetDisplayName = envelope.optString("target_display_name", "")
                            LogCollector.i(TAG, "Reid merge: $sourcePersonId -> $targetPersonId")
                            listener?.onReidMerge(sourcePersonId, targetPersonId, targetDisplayName)
                        }
                        Protocol.TYPE_WEBRTC_OFFER -> {
                            val streamId = envelope.optInt("streamId", 0)
                            val sdp = envelope.optString("sdp", "")
                            if (sdp.isNotEmpty()) {
                                listener?.onWebRTCOffer(streamId, sdp)
                            }
                        }
                        Protocol.TYPE_WEBRTC_ICE -> {
                            val streamId = envelope.optInt("streamId", 0)
                            val candidate = envelope.optString("candidate", "")
                            val sdpMid = envelope.optString("sdpMid", "")
                            val sdpMLineIndex = envelope.optInt("sdpMLineIndex", 0)
                            if (candidate.isNotEmpty()) {
                                listener?.onWebRTCIce(streamId, candidate, sdpMid, sdpMLineIndex)
                            }
                        }
                        // Remote Control
                        Protocol.TYPE_RC_SESSION_START -> {
                            val sessionId = envelope.optString("sessionId", "")
                            // optString returns the literal "null" when the JSON value is
                            // JSONObject.NULL -- normalize so downstream never sees it as
                            // a folder name in the chat-list chip bar.
                            val workDirRaw = envelope.optStringOrNull("workDir") ?: ""
                            val workDir = if (workDirRaw.equals("null", ignoreCase = true)) "" else workDirRaw
                            listener?.onRcSessionStart(sessionId, workDir)
                        }
                        Protocol.TYPE_RC_SESSION_END -> {
                            val sessionId = envelope.optString("sessionId", "")
                            listener?.onRcSessionEnd(sessionId)
                        }
                        Protocol.TYPE_RC_MESSAGE -> {
                            val sessionId = envelope.optString("sessionId", "")
                            val rcText = envelope.optString("text", "")
                            val isFinal = envelope.optBoolean("isFinal", false)
                            val requestId = envelope.optStringOrNull("requestId")
                            val contextPct = if (envelope.has("contextPct")) envelope.optInt("contextPct", -1) else -1
                            val costUsd = if (envelope.has("costUsd")) envelope.optDouble("costUsd", -1.0) else -1.0
                            listener?.onRcMessage(sessionId, rcText, isFinal, requestId, contextPct, costUsd)
                        }
                        Protocol.TYPE_RC_PERMISSION_REQUEST -> {
                            val sessionId = envelope.optString("sessionId", "")
                            val toolName = envelope.optString("toolName", "")
                            val toolArgs = envelope.optString("toolArgs", "{}")
                            val requestId = envelope.optString("requestId", "")
                            val description = envelope.optStringOrNull("description")
                            listener?.onRcPermissionRequest(sessionId, toolName, toolArgs, requestId, description)
                        }
                        Protocol.TYPE_RC_TOOL_STATUS -> {
                            val sessionId = envelope.optString("sessionId", "")
                            val toolName = envelope.optString("toolName", "")
                            val rcStatus = envelope.optString("status", "")
                            val result = envelope.optStringOrNull("result")
                            val toolArgs = envelope.optStringOrNull("toolArgs")
                            val toolCallId = envelope.optStringOrNull("toolCallId")
                            val ctxPct = if (envelope.has("contextPct")) envelope.optInt("contextPct", -1) else -1
                            val isAgent = envelope.optBoolean("isAgent", false)
                            val agentName = envelope.optStringOrNull("agentName")
                            val agentTask = envelope.optStringOrNull("agentTask")
                            val agentToolCount = if (envelope.has("agentToolCount")) envelope.optInt("agentToolCount", -1).takeIf { it >= 0 } else null
                            val agentTokens = if (envelope.has("agentTokens")) envelope.optLong("agentTokens", -1L).takeIf { it >= 0 } else null
                            val agentElapsedMs = if (envelope.has("agentElapsedMs")) envelope.optLong("agentElapsedMs", -1L).takeIf { it >= 0 } else null
                            listener?.onRcToolStatus(sessionId, toolName, rcStatus, result, toolArgs, toolCallId, ctxPct, isAgent, agentName, agentTask, agentToolCount, agentTokens, agentElapsedMs)
                        }
                        Protocol.TYPE_RC_PLAN_UPDATE -> {
                            val sessionId = envelope.optString("sessionId", "")
                            val entering = envelope.optBoolean("entering", false)
                            val planContent = envelope.optStringOrNull("planContent")
                            listener?.onRcPlanUpdate(sessionId, entering, planContent)
                        }
                        Protocol.TYPE_RC_AGENT_STATUS -> {
                            val sessionId = envelope.optString("sessionId", "")
                            val agentId = envelope.optString("agentId", "")
                            val name = envelope.optString("name", "")
                            val agentStatus = envelope.optString("status", "")
                            val depth = envelope.optInt("depth", 0)
                            listener?.onRcAgentStatus(sessionId, agentId, name, agentStatus, depth)
                        }
                        Protocol.TYPE_RC_THINKING -> {
                            val sessionId = envelope.optString("sessionId", "")
                            val rcText = envelope.optString("text", "")
                            val startedAt = envelope.optLong("startedAt", 0L)
                            listener?.onRcThinking(sessionId, rcText, startedAt)
                        }
                        Protocol.TYPE_RC_THINKING_END -> {
                            val sessionId = envelope.optString("sessionId", "")
                            val elapsedMs = envelope.optLong("elapsedMs", 0L)
                            listener?.onRcThinkingEnd(sessionId, elapsedMs)
                        }
                        Protocol.TYPE_RC_MODE_CHANGE -> {
                            val sessionId = envelope.optString("sessionId", "")
                            val newMode = envelope.optString("mode", "")
                            listener?.onRcModeChange(sessionId, newMode)
                        }
                        Protocol.TYPE_RC_USER_INPUT -> {
                            val sessionId = envelope.optString("sessionId", "")
                            val prompt = envelope.optString("prompt", "")
                            val requestId = envelope.optString("requestId", "")
                            listener?.onRcUserInput(sessionId, prompt, requestId)
                        }
                        Protocol.TYPE_RC_TRANSCRIPT -> {
                            val sessionId = envelope.optString("sessionId", "")
                            val transcript = envelope.optJSONArray("messages")?.toString() ?: "[]"
                            listener?.onRcTranscript(sessionId, transcript)
                        }
                        Protocol.TYPE_RC_ERROR -> {
                            val sessionId = envelope.optString("sessionId", "")
                            val errorText = envelope.optString("error", "")
                            val source = envelope.optString("source", "system")
                            listener?.onRcError(sessionId, errorText, source)
                        }
                        Protocol.TYPE_RC_USER_MESSAGE_ACK -> {
                            val sessionId = envelope.optString("sessionId", "")
                            val requestId = envelope.optString("requestId", "")
                            if (requestId.isNotEmpty()) handleRcUserMessageAck(sessionId, requestId)
                        }
                        Protocol.TYPE_HEALTH -> {
                            val status = envelope.optString("status", "")
                            if (status == "ping") {
                                val pong = Protocol.createHealthPong()
                                webSocket.send(pong.toString())
                            }
                        }
                        "ws_size_test_result" -> {
                            val size = envelope.optInt("size", 0)
                            LogCollector.w(TAG, "SIZE TEST: received ${text.length} chars (target=$size)")
                        }
                        Protocol.TYPE_ERROR -> {
                            val msg = envelope.optString("message", "unknown error")
                            LogCollector.e(TAG, "Server error: $msg")
                            listener?.onServerError(msg)
                        }
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Failed to parse message: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                LogCollector.i(TAG, "WebSocket closed: code=$code reason=$reason")
                stopHeartbeat()
                synchronized(this@OrchestratorClient) {
                    isConnected = false
                    identified = false
                }
                listener?.onDisconnected()
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                LogCollector.e(TAG, "WebSocket failure: ${t.message}")
                connectWatchdogRunnable?.let { handler.removeCallbacks(it) }
                stopHeartbeat()
                synchronized(this@OrchestratorClient) {
                    isConnected = false
                    identified = false
                }
                listener?.onDisconnected()
                scheduleReconnect()
            }
        })

        // Watchdog: if onOpen hasn't fired within CONNECT_WATCHDOG_MS, force-close and retry.
        // Prevents hanging forever when TCP connects but TLS/upgrade stalls (readTimeout=0).
        connectWatchdogRunnable = Runnable {
            if (!isConnected && !isShutdown) {
                LogCollector.e(TAG, "Connect watchdog: no onOpen after ${CONNECT_WATCHDOG_MS}ms, forcing reconnect")
                try { ws?.cancel() } catch (_: Exception) {}
                ws = null
                scheduleReconnect()
            }
        }
        handler.postDelayed(connectWatchdogRunnable!!, CONNECT_WATCHDOG_MS)
    }

    /**
     * Send request with retry logic. Blocks the calling thread.
     * Retries up to 30 times with 10s intervals (~5min total).
     * Returns true if sent successfully, false if all retries exhausted.
     */
    /** Public map: requestId -> wall-clock ms when the chat request was first
     *  enqueued for send. Read by ListenerService to compute RTT to the
     *  server's request_ack and the AI's first streaming/TTS frame. Cleaned
     *  up by ListenerService on response or expiry. */
    val requestSentAtMs: java.util.concurrent.ConcurrentHashMap<String, Long> =
        java.util.concurrent.ConcurrentHashMap()

    fun sendRequest(text: String, imageBase64: String? = null, model: String? = null, deviceType: String? = null, userSystemPrompt: String? = null): Boolean {
        val requestId = java.util.UUID.randomUUID().toString()
        lastRequestId = requestId
        requestSentAtMs[requestId] = System.currentTimeMillis()
        val msg = Protocol.createRequestMessage(text, imageBase64, model, deviceType, userSystemPrompt).apply {
            put("requestId", requestId)
        }
        val sent = sendOrQueue(msg.toString())
        if (sent) {
            LogCollector.i(TAG, "Request sent (requestId=$requestId)")
        } else {
            LogCollector.i(TAG, "Request queued for replay on reconnect (requestId=$requestId)")
        }
        // Return true: the message has either been sent or reliably queued.
        return true
    }

    fun sendDeviceResponse(
        requestId: String,
        commandType: String,
        imageBase64: String? = null,
        screenBase64: String? = null,
        text: String? = null,
        data: JSONObject? = null
    ) {
        val msg = Protocol.createDeviceResponse(requestId, commandType, imageBase64, screenBase64, text, data)
        val sent = ws?.send(msg.toString()) ?: false
        if (!sent) {
            LogCollector.e(TAG, "Failed to send device response (WebSocket not connected)")
        }
    }

    fun sendTtsInterrupt(requestId: String) {
        val msg = Protocol.createTtsInterruptMessage(requestId)
        val sent = ws?.send(msg.toString()) ?: false
        if (!sent) {
            LogCollector.e(TAG, "Failed to send TTS interrupt (WebSocket not connected)")
        }
    }

    fun sendAbort(requestId: String) {
        val msg = Protocol.createAbortMessage(requestId)
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent abort for $requestId")
        } else {
            LogCollector.e(TAG, "Failed to send abort (WebSocket not connected)")
        }
    }

    fun sendNewChat() {
        val msg = JSONObject().apply { put("type", "new_chat") }
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent new_chat to orchestrator")
        } else {
            LogCollector.e(TAG, "Failed to send new_chat (WebSocket not connected)")
        }
    }

    /**
     * Send an Assistant fact-check batch. The caller supplies the requestId so it
     * can record the in-flight marker synchronously before this send runs, closing
     * the race where a result could arrive before the marker was set.
     */
    fun sendAssistantBatch(
        requestId: String,
        wearerText: String,
        interlocutorText: String,
        activeCards: List<Triple<String, String, String>>,
        model: String
    ) {
        val msg = Protocol.createAssistantMessage(requestId, wearerText, interlocutorText, activeCards, model)
        val sent = sendOrQueue(msg.toString())
        if (sent) {
            LogCollector.i(TAG, "Assistant batch sent (requestId=$requestId)")
        } else {
            LogCollector.i(TAG, "Assistant batch queued (requestId=$requestId)")
        }
    }

    fun sendAssistantNew() {
        val msg = Protocol.createAssistantNewMessage()
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent assistant_new to orchestrator")
        } else {
            LogCollector.e(TAG, "Failed to send assistant_new (WebSocket not connected)")
        }
    }

    fun sendStreamRequest(
        targetDeviceId: String,
        resolution: String = "720p",
        monitor: Int = 0,
        fps: Int = 24,
        preset: String = "ultrafast",
        profile: String = "baseline",
        keyframeInterval: Int = 2
    ): Boolean {
        val msg = Protocol.createStreamRequest(targetDeviceId, resolution, monitor, fps, preset, profile, keyframeInterval)
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent stream request to $targetDeviceId ($resolution ${fps}fps $preset $profile kf=${keyframeInterval}s)")
        } else {
            LogCollector.e(TAG, "Failed to send stream request (WebSocket not connected)")
        }
        return sent
    }

    fun sendStreamSwitchMonitor(streamId: Int, monitor: Int): Boolean {
        val msg = Protocol.createStreamSwitchMonitor(streamId, monitor)
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent stream switch monitor=$monitor for streamId=$streamId")
        } else {
            LogCollector.e(TAG, "Failed to send stream switch monitor (WebSocket not connected)")
        }
        return sent
    }

    fun sendStreamStop(streamId: Int): Boolean {
        val msg = Protocol.createStreamStop(streamId)
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent stream stop for streamId=$streamId")
        } else {
            LogCollector.e(TAG, "Failed to send stream stop (WebSocket not connected)")
        }
        return sent
    }

    fun sendAudioRelayStart(targetDeviceId: String, bitrate: Int): Boolean {
        val msg = Protocol.createAudioRelayStart(targetDeviceId, bitrate)
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent audio relay start to $targetDeviceId (${bitrate}bps)")
        } else {
            LogCollector.e(TAG, "Failed to send audio relay start (WebSocket not connected)")
        }
        return sent
    }

    fun sendAudioRelayStop(targetDeviceId: String): Boolean {
        val msg = Protocol.createAudioRelayStop(targetDeviceId)
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent audio relay stop to $targetDeviceId")
        } else {
            LogCollector.e(TAG, "Failed to send audio relay stop (WebSocket not connected)")
        }
        return sent
    }

    fun sendBinary(data: ByteArray) {
        ws?.send(ByteString.of(*data))
    }

    fun sendTodoList(): Boolean {
        val msg = JSONObject().apply {
            put("type", "todo_list")
        }
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent todo_list request")
        } else {
            LogCollector.e(TAG, "Failed to send todo_list (WebSocket not connected)")
        }
        return sent
    }

    fun sendTodoCreate(text: String): Boolean {
        val msg = JSONObject().apply {
            put("type", "todo_create")
            put("text", text)
        }
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent todo_create: ${text.take(40)}")
        } else {
            LogCollector.e(TAG, "Failed to send todo_create (WebSocket not connected)")
        }
        return sent
    }

    fun sendTodoUpdate(id: String, completed: Boolean): Boolean {
        val msg = JSONObject().apply {
            put("type", "todo_update")
            put("id", id)
            put("completed", completed)
        }
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent todo_update: id=$id completed=$completed")
        } else {
            LogCollector.e(TAG, "Failed to send todo_update (WebSocket not connected)")
        }
        return sent
    }

    fun sendTodoMove(id: String, position: Int): Boolean {
        val msg = JSONObject().apply {
            put("type", "todo_move")
            put("id", id)
            put("position", position)
        }
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent todo_move: id=$id position=$position")
        } else {
            LogCollector.e(TAG, "Failed to send todo_move (WebSocket not connected)")
        }
        return sent
    }

    fun sendTodoDelete(id: String): Boolean {
        val msg = JSONObject().apply {
            put("type", "todo_delete")
            put("id", id)
        }
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent todo_delete: id=$id")
        } else {
            LogCollector.e(TAG, "Failed to send todo_delete (WebSocket not connected)")
        }
        return sent
    }

    fun sendJobList(): Boolean {
        val msg = JSONObject().apply {
            put("type", "job_list")
        }
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent job_list request")
        } else {
            LogCollector.e(TAG, "Failed to send job_list (WebSocket not connected)")
        }
        return sent
    }

    fun sendJobCreate(name: String, prompt: String, scheduledAt: String): Boolean {
        val msg = JSONObject().apply {
            put("type", "job_create")
            put("name", name)
            put("prompt", prompt)
            put("scheduledAt", scheduledAt)
        }
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent job_create: ${name.take(40)}")
        } else {
            LogCollector.e(TAG, "Failed to send job_create (WebSocket not connected)")
        }
        return sent
    }

    fun sendJobUpdate(id: String, name: String?, prompt: String?, scheduledAt: String?): Boolean {
        val msg = JSONObject().apply {
            put("type", "job_update")
            put("id", id)
            if (name != null) put("name", name)
            if (prompt != null) put("prompt", prompt)
            if (scheduledAt != null) put("scheduledAt", scheduledAt)
        }
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent job_update: id=$id")
        } else {
            LogCollector.e(TAG, "Failed to send job_update (WebSocket not connected)")
        }
        return sent
    }

    fun sendJobDelete(id: String): Boolean {
        val msg = JSONObject().apply {
            put("type", "job_delete")
            put("id", id)
        }
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent job_delete: id=$id")
        } else {
            LogCollector.e(TAG, "Failed to send job_delete (WebSocket not connected)")
        }
        return sent
    }

    fun sendTelegramSaved(limit: Int = 20): Boolean {
        val msg = JSONObject().apply {
            put("type", "telegram_saved")
            put("limit", limit)
        }
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent telegram_saved request (limit=$limit)")
        } else {
            LogCollector.e(TAG, "Failed to send telegram_saved (WebSocket not connected)")
        }
        return sent
    }

    fun sendTelegramChatList(limit: Int = 20): Boolean {
        val msg = JSONObject().apply {
            put("type", Protocol.TYPE_TG_CHAT_LIST)
            put("limit", limit)
        }
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent telegram_chat_list (limit=$limit)")
        } else {
            LogCollector.e(TAG, "Failed to send telegram_chat_list (WebSocket not connected)")
        }
        return sent
    }

    /**
     * Fetch a Telegram avatar via HTTP (not WS) to keep WS payload small.
     * Returns JPEG bytes or null if not available.
     */
    fun fetchTelegramAvatar(chatId: String): ByteArray? {
        val httpUrl = url
            .replace("wss://", "https://")
            .replace("ws://", "http://")
            .replace("/ws/device", "/api/v1/telegram/avatar/${java.net.URLEncoder.encode(chatId, "UTF-8")}")
        try {
            val request = Request.Builder()
                .url(httpUrl)
                .addHeader("x-api-key", apiKey)
                .get()
                .build()
            val response = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                LogCollector.i(TAG, "Avatar fetched for $chatId: ${bytes?.size ?: 0} bytes")
                return bytes
            }
            LogCollector.w(TAG, "Avatar fetch failed for $chatId: ${response.code}")
        } catch (e: Exception) {
            LogCollector.w(TAG, "Avatar fetch error for $chatId: ${e.message}")
        }
        return null
    }

    fun sendTelegramMessages(chatId: String, limit: Int = 20, topicId: Int = 0, offsetId: Int = 0): Boolean {
        val msg = JSONObject().apply {
            put("type", Protocol.TYPE_TG_MESSAGES)
            put("chatId", chatId)
            put("limit", limit)
            if (topicId > 0) put("topicId", topicId)
            if (offsetId > 0) put("offsetId", offsetId)
        }
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent telegram_messages: chatId=$chatId limit=$limit topicId=$topicId")
        } else {
            LogCollector.e(TAG, "Failed to send telegram_messages (WebSocket not connected)")
        }
        return sent
    }

    fun sendTelegramTopics(chatId: String): Boolean {
        val msg = JSONObject().apply {
            put("type", Protocol.TYPE_TG_TOPICS)
            put("chatId", chatId)
        }
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) LogCollector.i(TAG, "Sent telegram_topics: chatId=$chatId")
        return sent
    }

    fun sendTelegramSendMessage(chatId: String, text: String, topicId: Int = 0): Boolean {
        val msg = JSONObject().apply {
            put("type", Protocol.TYPE_TG_SEND)
            put("chatId", chatId)
            put("text", text)
            if (topicId > 0) put("topicId", topicId)
        }
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent telegram_send: chatId=$chatId text=${text.take(40)}")
        } else {
            LogCollector.e(TAG, "Failed to send telegram_send (WebSocket not connected)")
        }
        return sent
    }

    fun sendTelegramSubscribe(): Boolean {
        val msg = JSONObject().apply {
            put("type", Protocol.TYPE_TG_SUBSCRIBE)
        }
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent telegram_subscribe")
        } else {
            LogCollector.e(TAG, "Failed to send telegram_subscribe (WebSocket not connected)")
        }
        return sent
    }

    fun sendTelegramUnsubscribe(): Boolean {
        val msg = JSONObject().apply {
            put("type", Protocol.TYPE_TG_UNSUBSCRIBE)
        }
        val sent = ws?.send(msg.toString()) ?: false
        if (sent) {
            LogCollector.i(TAG, "Sent telegram_unsubscribe")
        } else {
            LogCollector.e(TAG, "Failed to send telegram_unsubscribe (WebSocket not connected)")
        }
        return sent
    }

    fun sendNotificationTts(notifId: String, sender: String, text: String, chat: String) {
        val msg = Protocol.createNotificationTtsMessage(notifId, sender, text, chat)
        val msgStr = msg.toString()
        val wsRef = ws
        LogCollector.i(TAG, "Sending notification_tts: $notifId (${msgStr.length} chars, ws=${wsRef != null})")
        val sent = wsRef?.send(msgStr) ?: false
        if (!sent) {
            LogCollector.e(TAG, "Failed to send notification TTS (ws=${wsRef != null}, sent=false)")
        } else {
            LogCollector.i(TAG, "Notification TTS sent OK: $notifId")
        }
    }

    fun sendRaw(json: String): Boolean {
        val sent = ws?.send(json) ?: false
        if (!sent) {
            LogCollector.e(TAG, "Failed to send raw message (WebSocket not connected)")
        }
        return sent
    }

    fun sendWebRTCAnswer(streamId: Int, sdp: String): Boolean {
        val msg = Protocol.createWebRTCAnswer(streamId, sdp)
        return ws?.send(msg.toString()) ?: false
    }

    fun sendWebRTCIce(streamId: Int, candidate: String, sdpMid: String, sdpMLineIndex: Int): Boolean {
        val msg = Protocol.createWebRTCIce(streamId, candidate, sdpMid, sdpMLineIndex)
        return ws?.send(msg.toString()) ?: false
    }

    fun sendRcPermissionResponse(sessionId: String, requestId: String, approved: Boolean, modeChange: String? = null, reason: String? = null): Boolean {
        val msg = Protocol.createRcPermissionResponse(sessionId, requestId, approved, modeChange, reason)
        return ws?.send(msg.toString()) ?: false
    }

    fun sendRcUserResponse(sessionId: String, requestId: String, text: String): Boolean {
        val msg = Protocol.createRcUserResponse(sessionId, requestId, text)
        return ws?.send(msg.toString()) ?: false
    }

    /**
     * Enqueue an rc_user_message for delivery to the orchestrator. Returns
     * the client-generated requestId so the caller can correlate UI state
     * with onRcUserMessageStatus callbacks. Always succeeds locally; actual
     * delivery happens on the next WS attempt and may retry up to
     * RC_MAX_ATTEMPTS times before giving up.
     */
    fun sendRcUserMessage(sessionId: String, text: String): String {
        val requestId = java.util.UUID.randomUUID().toString()
        val pending = PendingRcMsg(
            requestId = requestId,
            sessionId = sessionId,
            text = text,
            attempt = 0,
            nextRetryAtMs = System.currentTimeMillis(),
        )
        synchronized(pendingRcMsgs) { pendingRcMsgs[requestId] = pending }
        attemptRcSend(pending)
        return requestId
    }

    /** Try to send a pending rc_user_message right now. Schedules the next
     *  retry on failure; emits the appropriate status callback either way. */
    private fun attemptRcSend(p: PendingRcMsg) {
        // Defensive: if it was acked between scheduling and execution, drop it.
        val stillPending = synchronized(pendingRcMsgs) { pendingRcMsgs.containsKey(p.requestId) }
        if (!stillPending) return
        p.attempt += 1
        val webSocket = ws
        val canSend = isConnected && identified && webSocket != null
        val sent = if (canSend) {
            try {
                webSocket.send(Protocol.createRcUserMessage(p.sessionId, p.text, p.requestId).toString())
            } catch (e: Exception) {
                LogCollector.w(TAG, "rc_user_message send threw on attempt ${p.attempt}: ${e.message}")
                false
            }
        } else false

        if (sent) {
            // Optimistic: mark as in-flight 'sending' (first attempt) or
            // 'retrying' (subsequent). The terminal 'delivered' transition
            // happens when the ack actually arrives; until then we keep the
            // entry in pendingRcMsgs and arm a fallback retry timer in case
            // the ack never comes.
            val backoff = rcBackoffForAttempt(p.attempt)
            p.nextRetryAtMs = System.currentTimeMillis() + backoff
            scheduleRcRetry(p, backoff)
            val status = if (p.attempt == 1) "sending" else "retrying"
            listener?.onRcUserMessageStatus(p.sessionId, p.requestId, status, p.attempt, p.nextRetryAtMs)
            LogCollector.i(TAG, "rc_user_message attempt=${p.attempt} sent (req=${p.requestId.take(8)})")
        } else {
            // WS not open or send threw. If we've blown the attempt budget,
            // mark as failed and drop. Otherwise back off and try again.
            if (p.attempt >= RC_MAX_ATTEMPTS) {
                synchronized(pendingRcMsgs) { pendingRcMsgs.remove(p.requestId) }
                p.retryRunnable?.let { handler.removeCallbacks(it) }
                p.retryRunnable = null
                listener?.onRcUserMessageStatus(p.sessionId, p.requestId, "failed", p.attempt, 0L)
                LogCollector.w(TAG, "rc_user_message gave up after ${p.attempt} attempts (req=${p.requestId.take(8)})")
                return
            }
            val backoff = rcBackoffForAttempt(p.attempt)
            p.nextRetryAtMs = System.currentTimeMillis() + backoff
            scheduleRcRetry(p, backoff)
            val status = if (p.attempt == 1) "queued" else "retrying"
            listener?.onRcUserMessageStatus(p.sessionId, p.requestId, status, p.attempt, p.nextRetryAtMs)
            LogCollector.w(TAG, "rc_user_message attempt=${p.attempt} not sent (ws=${webSocket != null} connected=$isConnected); retry in ${backoff}ms (req=${p.requestId.take(8)})")
        }
    }

    private fun scheduleRcRetry(p: PendingRcMsg, delayMs: Long) {
        p.retryRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            // Re-check membership at fire time -- ack may have removed it.
            if (synchronized(pendingRcMsgs) { pendingRcMsgs.containsKey(p.requestId) }) {
                attemptRcSend(p)
            }
        }
        p.retryRunnable = r
        handler.postDelayed(r, delayMs)
    }

    /** Drain the rc_user_message queue immediately. Called on WS reconnect. */
    private fun drainRcUserMessages() {
        val snapshot = synchronized(pendingRcMsgs) { ArrayList(pendingRcMsgs.values) }
        if (snapshot.isEmpty()) return
        LogCollector.i(TAG, "Draining ${snapshot.size} pending rc_user_messages on reconnect")
        for (p in snapshot) {
            p.retryRunnable?.let { handler.removeCallbacks(it) }
            p.retryRunnable = null
            attemptRcSend(p)
        }
    }

    /** Called from the dispatcher when the orchestrator acks one of our
     *  rc_user_messages by requestId. */
    private fun handleRcUserMessageAck(sessionId: String, requestId: String) {
        val p = synchronized(pendingRcMsgs) { pendingRcMsgs.remove(requestId) }
        if (p == null) {
            // Already acked or never tracked -- ignore.
            return
        }
        p.retryRunnable?.let { handler.removeCallbacks(it) }
        p.retryRunnable = null
        listener?.onRcUserMessageStatus(sessionId, requestId, "delivered", p.attempt, 0L)
        LogCollector.i(TAG, "rc_user_message acked (req=${requestId.take(8)}) after ${p.attempt} attempt(s)")
    }

    fun sendRcModeChange(sessionId: String, mode: String): Boolean {
        val msg = Protocol.createRcModeChangeRequest(sessionId, mode)
        return ws?.send(msg.toString()) ?: false
    }

    fun requestRcTranscript(sessionId: String): Boolean {
        val msg = Protocol.createRcTranscriptRequest(sessionId)
        return ws?.send(msg.toString()) ?: false
    }

    fun sendRcInterrupt(sessionId: String): Boolean {
        val msg = Protocol.createRcInterrupt(sessionId)
        return ws?.send(msg.toString()) ?: false
    }

    fun sendRcRevive(sessionId: String, workDir: String): Boolean {
        val msg = Protocol.createRcRevive(sessionId, workDir)
        return ws?.send(msg.toString()) ?: false
    }

    fun sendRcSettingChange(sessionId: String, setting: String, value: String): Boolean {
        val msg = Protocol.createRcSettingChange(sessionId, setting, value)
        return ws?.send(msg.toString()) ?: false
    }

    fun openStreamConnection(
        streamId: Int,
        streamType: String,
        endpoint: String,
        token: String,
        onBinaryReceived: ((ByteArray) -> Unit)? = null,
        onDisconnected: (() -> Unit)? = null
    ): StreamWebSocket {
        val fullUrl = "$baseWsUrl$endpoint?token=$token"
        val conn = StreamWebSocket(fullUrl, streamId, streamType, onBinaryReceived, onDisconnected = onDisconnected, apiKey = apiKey)
        streamConnections[Pair(streamId, streamType)] = conn
        conn.connect()
        return conn
    }

    fun closeStreamConnections(streamId: Int? = null) {
        val toRemove = streamConnections.filter { (key, _) ->
            streamId == null || key.first == streamId
        }
        toRemove.forEach { (key, conn) ->
            conn.disconnect()
            streamConnections.remove(key)
        }
    }

    fun setTranscribeStreamListener(listener: TranscribeStreamListener?) {
        this.transcribeListener = listener
    }

    fun connectTranscribeStream(
        sourceLang: String,
        targetLang: String,
        sourceNllb: String,
        targetNllb: String,
        sampleRate: Int = 16000,
        translate: Boolean = false
    ) {
        val wsUrl = url.replace("/ws/device", "/ws/transcribe")

        transcribeClient = OkHttpClient.Builder()
            .pingInterval(0, TimeUnit.SECONDS)  // Disabled: health check is separate HTTP stream
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url(wsUrl).addHeader("x-api-key", apiKey).build()

        transcribeClient!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                LogCollector.i(TAG, "Transcribe stream connected")
                val startMsg = JSONObject().apply {
                    put("type", "start")
                    put("sampleRate", sampleRate)
                    put("sourceLang", sourceLang)
                    put("targetLang", targetLang)
                    put("sourceNllb", sourceNllb)
                    put("targetNllb", targetNllb)
                    put("translate", translate)
                }
                webSocket.send(startMsg.toString())
                // Send speech_start immediately -- phone streams all audio continuously,
                // server-side whisper vad_filter handles silence detection.
                val speechStartMsg = JSONObject().apply { put("type", "speech_start") }
                webSocket.send(speechStartMsg.toString())
                // Set transcribeWs AFTER start + speech_start are sent so audio frames
                // cannot be enqueued before the start message.
                transcribeWs = webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "transcription" -> {
                            val segId = json.optInt("segId")
                            val transcribedText = json.optString("text", "")
                            val translation = json.optString("translation", "")
                            val partial = json.optBoolean("partial", true)
                            transcribeListener?.onTranscription(segId, transcribedText, translation, partial)
                        }
                        "final" -> {
                            val segId = json.optInt("segId")
                            val finalText = json.optString("text", "")
                            val translation = json.optString("translation", "")
                            transcribeListener?.onFinal(segId, finalText, translation)
                        }
                        "error" -> {
                            val msg = json.optString("message", "unknown error")
                            LogCollector.e(TAG, "Transcribe stream error: $msg")
                            transcribeListener?.onStreamError(msg)
                        }
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Failed to parse transcribe stream message: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                LogCollector.i(TAG, "Transcribe stream closed: code=$code")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                LogCollector.e(TAG, "Transcribe stream failure: ${t.message}")
                transcribeListener?.onStreamError(t.message ?: "connection failed")
            }
        })
    }

    fun sendTranscribeAudioFrame(pcmBytes: ByteArray) {
        synchronized(transcribeAudioBuf) {
            transcribeAudioBuf.write(pcmBytes)
            if (transcribeAudioBuf.size() >= TRANSCRIBE_BATCH_BYTES) {
                val ws = transcribeWs
                if (ws != null) {
                    ws.send(transcribeAudioBuf.toByteArray().toByteString())
                    transcribeAudioBuf.reset()
                }
                // If ws is null, keep buffering -- don't drop audio
            }
        }
    }

    private fun flushTranscribeAudioBuf() {
        synchronized(transcribeAudioBuf) {
            if (transcribeAudioBuf.size() > 0) {
                val ws = transcribeWs
                if (ws != null) {
                    ws.send(transcribeAudioBuf.toByteArray().toByteString())
                }
                transcribeAudioBuf.reset()
            }
        }
    }

    fun sendTranscribeTextMessage(msg: JSONObject) {
        transcribeWs?.send(msg.toString())
    }

    fun disconnectTranscribeStream() {
        flushTranscribeAudioBuf()
        val stopMsg = JSONObject().apply { put("type", "stop") }
        transcribeWs?.send(stopMsg.toString())
        transcribeWs?.close(1000, "Stream ended")
        transcribeWs = null
        transcribeClient?.dispatcher?.executorService?.shutdown()
        transcribeClient = null
        transcribeListener = null
        LogCollector.i(TAG, "Transcribe stream disconnected")
    }

    fun disconnect() {
        isShutdown = true
        isConnected = false
        stopHeartbeat()
        closeStreamConnections()
        disconnectTranscribeStream()
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
        ws?.close(1000, "Client disconnect")
        ws = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        LogCollector.i(TAG, "Disconnected")
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        consecutiveHeartbeatFailures = 0
        heartbeatExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "orchestrator-heartbeat").apply { isDaemon = true }
        }
        heartbeatExecutor?.scheduleAtFixedRate({
            try {
                // Check HTTP health (orchestrator process is alive)
                val request = Request.Builder().url(healthUrl).addHeader("x-api-key", apiKey).build()
                heartbeatHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onHeartbeatFailure("HTTP ${response.code}")
                        return@use
                    }

                    val wsRef = ws
                    if (wsRef == null) {
                        onHeartbeatFailure("WS is null")
                        return@use
                    }

                    // Check if we've received any WS frame recently --
                    // ws.send() returns true even on dead sockets (buffered), so
                    // the only reliable signal is whether the server is actually sending us data
                    val staleMs = System.currentTimeMillis() - lastWsFrameAt
                    if (lastWsFrameAt > 0 && staleMs > WS_STALE_THRESHOLD_MS) {
                        onHeartbeatFailure("WS stale (no frame in ${staleMs}ms)")
                        return@use
                    }

                    // Send health ping (for orchestrator-side monitoring)
                    wsRef.send("""{"type":"health","status":"ping"}""")
                    consecutiveHeartbeatFailures = 0
                }
            } catch (e: Exception) {
                onHeartbeatFailure(e.message ?: "unknown")
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    private fun onHeartbeatFailure(reason: String) {
        consecutiveHeartbeatFailures++
        if (consecutiveHeartbeatFailures >= MAX_HEARTBEAT_FAILURES) {
            LogCollector.e(TAG, "Heartbeat failed $consecutiveHeartbeatFailures times ($reason), reconnecting")
            stopHeartbeat()
            handler.post {
                isConnected = false
                ws?.close(1000, "Heartbeat timeout")
                ws = null
                listener?.onDisconnected()
                scheduleReconnect()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatExecutor?.shutdownNow()
        heartbeatExecutor = null
    }

    private fun scheduleReconnect() {
        val delay: Long
        synchronized(this) {
            if (isShutdown || reconnectScheduled) return
            reconnectScheduled = true
            reconnectAttempts++
            // Exponential backoff: 1s, 2s, 4s, 8s, capped at 10s
            val capped = reconnectAttempts.coerceAtMost(4)
            delay = min(1000L * (1L shl capped), 10_000L)
            reconnectRunnable = Runnable { connect() }
            handler.postDelayed(reconnectRunnable!!, delay)
        }
        LogCollector.i(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempts)")
    }

    /**
     * Enqueue a JSON message for sending, or send immediately if connected
     * and identified. Returns true if sent, false if queued.
     */
    private fun sendOrQueue(json: String): Boolean {
        synchronized(pendingSends) {
            val wsRef = ws
            // While drain is in progress, always enqueue to preserve order.
            if (!draining && identified && isConnected && wsRef != null) {
                val sent = try { wsRef.send(json) } catch (_: Exception) { false }
                if (sent) return true
            }
            pendingSends.addLast(json)
            LogCollector.w(TAG, "WS not ready, queued message (queue=${pendingSends.size})")
            return false
        }
    }
}
