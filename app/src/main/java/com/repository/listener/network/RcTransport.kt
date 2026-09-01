package com.repository.listener.network

import com.repository.listener.util.LogCollector
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * The device message channel, abstracted over how it is carried.
 *
 * Everything above this -- reconnect backoff, the connect watchdog, the health
 * heartbeat, the pending-send queue and the at-least-once rc_user_message retry
 * layer -- is transport-agnostic and lives in OrchestratorClient. This
 * interface is deliberately the smallest thing those need.
 */
interface RcTransport {
    fun connect(listener: Callbacks)
    fun send(payload: String): Boolean
    fun close()

    interface Callbacks {
        fun onOpen()
        fun onText(text: String)
        fun onBinary(bytes: ByteString)
        /**
         * @param upgradeRejected the failure looked like the channel itself was
         *   refused rather than an ordinary drop -- the signal used to fall back
         *   to a transport that carries no WebSocket handshake.
         */
        fun onClosed(reason: String, upgradeRejected: Boolean)
    }
}

/**
 * The original transport: one OkHttp WebSocket.
 *
 * Note this is already plain TCP over TLS -- OkHttp 4.x cannot speak HTTP/3, so
 * despite the common assumption none of this ever rode on UDP. What it does
 * carry is an HTTP `Upgrade` handshake, which is the part some middleboxes
 * single out.
 */
class WsTransport(
    private val url: String,
    private val apiKey: String
) : RcTransport {
    private var client: OkHttpClient? = null
    private var ws: WebSocket? = null

    override fun connect(listener: RcTransport.Callbacks) {
        client = OkHttpClient.Builder()
            .pingInterval(0, TimeUnit.SECONDS) // health check is a separate HTTP stream
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .build()

        ws = client!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onBinary(bytes)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed("closed code=$code reason=$reason", upgradeRejected = false)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // An HTTP response on a WebSocket failure means the server (or
                // something in the path) answered the handshake instead of
                // switching protocols. 4xx especially reads as "this Upgrade is
                // not welcome here" rather than a transient network fault.
                val code = response?.code
                val rejected = code != null && code >= 400
                listener.onClosed("failure ${t.message} httpCode=$code", upgradeRejected = rejected)
            }
        })
    }

    override fun send(payload: String): Boolean =
        try { ws?.send(payload) ?: false } catch (_: Exception) { false }

    /** Binary frames, which only this transport can carry natively. */
    fun sendBinary(bytes: ByteString): Boolean =
        try { ws?.send(bytes) ?: false } catch (_: Exception) { false }

    override fun close() {
        try { ws?.cancel() } catch (_: Exception) {}
        ws = null
        client = null
    }
}

/**
 * The same channel over ordinary HTTPS: a long-lived `text/event-stream` GET
 * for server-to-phone frames, plain POSTs for phone-to-server.
 *
 * There is no `Upgrade` handshake here, which is the one thing this buys over
 * [WsTransport]. It uses the same host, port and certificate, so it does NOT
 * change the TLS fingerprint -- if a middlebox is objecting at that level this
 * will not help either.
 *
 * Resume is by `Last-Event-ID`: the server retains a bounded ring of recent
 * frames and replays what was missed. If the client has fallen further behind
 * than the ring holds, the server sends a `resync` event rather than silently
 * skipping frames.
 */
class SseTransport(
    baseHttpUrl: String,
    private val apiKey: String,
    private val deviceId: String
) : RcTransport {
    companion object {
        private const val TAG = "SseTransport"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val streamUrl = "$baseHttpUrl/api/v1/device/stream?deviceId=$deviceId"
    private val sendUrl = "$baseHttpUrl/api/v1/device/send?deviceId=$deviceId"

    // readTimeout 0: the stream is meant to stay open indefinitely.
    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val postClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var eventSource: EventSource? = null

    /**
     * Single-threaded so POSTs go out in the order they were sent, matching
     * WebSocket frame ordering. Callers must never block on this.
     */
    private val sendQueue = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "sse-send").apply { isDaemon = true }
    }

    /**
     * Last frame id the server sent us. okhttp-sse surfaces the id but does not
     * resend it on reconnect, so we track it and set the header ourselves.
     */
    @Volatile
    private var lastEventId: String? = null

    override fun connect(listener: RcTransport.Callbacks) {
        val builder = Request.Builder()
            .url(streamUrl)
            .addHeader("x-api-key", apiKey)
            .addHeader("Accept", "text/event-stream")
        lastEventId?.let { builder.addHeader("Last-Event-ID", it) }

        eventSource = EventSources.createFactory(streamClient)
            .newEventSource(builder.build(), object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    LogCollector.i(TAG, "SSE stream open (resume=${lastEventId ?: "none"})")
                    listener.onOpen()
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (id != null) lastEventId = id
                    when (type) {
                        "resync" -> {
                            // We fell outside the server's replay buffer, so
                            // frames were genuinely lost. Drop the cursor and
                            // let the reconnect path refetch state rather than
                            // pretending we are current. Cancel this stream
                            // FIRST: leaving it attached while a new one opens
                            // would have the server fan every frame out twice.
                            LogCollector.w(TAG, "SSE resync requested: $data")
                            lastEventId = null
                            close()
                            listener.onClosed("resync", upgradeRejected = false)
                        }
                        else -> listener.onText(data)
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    listener.onClosed("sse stream closed", upgradeRejected = false)
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    LogCollector.e(TAG, "SSE failure: ${t?.message} httpCode=${response?.code}")
                    listener.onClosed("sse failure ${t?.message}", upgradeRejected = false)
                }
            })
    }

    /**
     * Enqueue a frame for POST.
     *
     * This is ASYNC and returns true once accepted, because `WebSocket.send`
     * is also non-blocking and callers -- including the rc_user_message retry
     * queue, which runs on the main looper -- assume it never blocks. Doing the
     * HTTP call inline would throw NetworkOnMainThreadException from exactly
     * the retry path this transport exists to keep working.
     *
     * Frames are posted one at a time so ordering matches the WebSocket's.
     */
    override fun send(payload: String): Boolean {
        val request = Request.Builder()
            .url(sendUrl)
            .addHeader("x-api-key", apiKey)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            sendQueue.execute {
                try {
                    postClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            LogCollector.e(TAG, "SSE send rejected: HTTP ${resp.code}")
                        }
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "SSE send failed: ${e.message}")
                }
            }
            true
        } catch (e: Exception) {
            // Executor rejected the task (shut down).
            LogCollector.e(TAG, "SSE send not enqueued: ${e.message}")
            false
        }
    }

    override fun close() {
        try { eventSource?.cancel() } catch (_: Exception) {}
        eventSource = null
    }

    /** Release the send thread. Only on full client shutdown, not a reconnect. */
    fun shutdown() {
        close()
        try { sendQueue.shutdownNow() } catch (_: Exception) {}
    }
}
