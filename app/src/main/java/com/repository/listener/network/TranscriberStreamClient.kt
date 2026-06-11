package com.repository.listener.network

import com.repository.listener.util.LogCollector
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Streams audio to the transcriber server via WebSocket for real-time
 * speech-to-text.
 *
 * NOTE: Single instance shared between phone and glasses paths.
 * Starting a new session releases the previous one.
 *
 * Usage:
 *   val client = TranscriberStreamClient(url, apiKey)
 *   client.start(listener)
 *   client.feedAudio(samples)  // call repeatedly with PCM16LE chunks
 *   val text = client.stopAndWaitForFinal(timeoutMs = 3000)
 */
class TranscriberStreamClient(
    private val baseUrl: String,
    private val apiKey: String
) {
    companion object {
        private const val TAG = "TranscriberStream"
        private val sharedHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private var ws: WebSocket? = null
    private var listener: Listener? = null
    private val active = AtomicBoolean(false)
    @Volatile private var lastFinalText = ""
    private var finalLatch: CountDownLatch? = null

    fun isActive(): Boolean = active.get()

    fun start(listener: Listener, sourceLang: String = "ru", provider: String = "local") {
        if (active.get()) {
            LogCollector.w(TAG, "Already active, closing previous session")
            forceClose()
        }

        this.listener = listener
        lastFinalText = ""
        finalLatch = CountDownLatch(1)

        val wsUrl = baseUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://") + "/ws/transcribe"

        LogCollector.i(TAG, "Connecting to $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("x-api-key", apiKey)
            .build()

        ws = sharedHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                LogCollector.i(TAG, "Connected, sending start message")
                val startMsg = JSONObject().apply {
                    put("type", "start")
                    put("sampleRate", 16000)
                    if (sourceLang != "auto") put("sourceLang", sourceLang)
                    put("provider", provider)
                }
                webSocket.send(startMsg.toString())
                active.set(true)  // Only accept feedAudio after start message is queued
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val cb = listener ?: return
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")
                    when (type) {
                        "partial", "transcription" -> {
                            val partialText = json.optString("text", "")
                            if (partialText.isNotBlank()) {
                                cb.onPartial(partialText)
                            }
                        }
                        "final" -> {
                            // Anthropic voice_stream emits one TranscriptEndpoint per natural
                            // pause (endpointing_ms threshold). One spoken sentence with a
                            // mid-sentence breath produces multiple finals representing disjoint
                            // segments of the same utterance. Concatenate them; do NOT latch on
                            // first arrival -- wait for the WS to actually close.
                            val finalText = json.optString("text", "").trim()
                            if (finalText.isNotBlank()) {
                                lastFinalText = when {
                                    lastFinalText.isBlank() -> finalText
                                    // Defensive: if server ever switches to cumulative-across-
                                    // endpoints, the new final will start with the previous
                                    // accumulated text -- replace instead of duplicating.
                                    finalText.startsWith(lastFinalText) -> finalText
                                    else -> "$lastFinalText $finalText"
                                }
                                cb.onFinal(lastFinalText)
                            }
                        }
                        "done" -> {
                            LogCollector.i(TAG, "Server signaled done")
                            finalLatch?.countDown()
                        }
                        "error" -> {
                            val msg = json.optString("message", "unknown error")
                            LogCollector.e(TAG, "Server error: $msg")
                            cb.onError(msg)
                            finalLatch?.countDown()
                        }
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Failed to parse message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                LogCollector.e(TAG, "WebSocket failure: ${t.message}")
                val cb = listener
                if (active.get() && cb != null) {
                    cb.onError(t.message ?: "connection failed")
                }
                active.set(false)
                finalLatch?.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                LogCollector.i(TAG, "WebSocket closed: $code $reason")
                active.set(false)
                finalLatch?.countDown()
            }
        })
    }

    private var feedDropCount = 0L
    private var feedSendCount = 0L

    fun feedAudio(samples: ShortArray) {
        if (!active.get()) {
            if (feedDropCount++ % 200 == 0L) {
                LogCollector.e(TAG, "feedAudio dropped: active=false (drop #$feedDropCount)")
            }
            return
        }
        val socket = ws
        if (socket == null) {
            if (feedDropCount++ % 200 == 0L) {
                LogCollector.e(TAG, "feedAudio dropped: ws=null (drop #$feedDropCount)")
            }
            return
        }

        val byteBuffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            byteBuffer.putShort(s)
        }
        val sent = socket.send(byteBuffer.array().toByteString())
        feedSendCount++
        if (feedSendCount % 200 == 0L) {
            LogCollector.i(TAG, "feedAudio sent #$feedSendCount (${samples.size * 2}B, ok=$sent)")
        }
    }

    /**
     * Send stop signal, wait for server's final transcription (up to timeoutMs),
     * then close the socket and return the final text.
     */
    fun stopAndWaitForFinal(timeoutMs: Long = 10000): String {
        if (!active.getAndSet(false)) return lastFinalText
        LogCollector.i(TAG, "Sending stop, waiting for final (${timeoutMs}ms)")
        try {
            ws?.send(JSONObject().apply { put("type", "stop") }.toString())
            // Wait for the server to send final text back
            finalLatch?.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Error waiting for final: ${e.message}")
        }
        forceClose()
        LogCollector.i(TAG, "Final text: '$lastFinalText'")
        return lastFinalText
    }

    fun stop() {
        stopAndWaitForFinal()
    }

    fun getLastFinalText(): String = lastFinalText

    private fun forceClose() {
        try {
            ws?.close(1000, "done")
        } catch (_: Exception) {}
        ws = null
    }

    fun release() {
        active.set(false)
        forceClose()
        listener = null
        finalLatch?.countDown()
    }
}
