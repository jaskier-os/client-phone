package com.repository.listener.network

import com.repository.listener.util.LogCollector
import okio.ByteString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class StreamWebSocket(
    private val url: String,
    private val streamId: Int,
    private val streamType: String,
    private val onBinaryReceived: ((ByteArray) -> Unit)? = null,
    private val onConnected: (() -> Unit)? = null,
    private val onDisconnected: (() -> Unit)? = null,
    private val apiKey: String? = null
) {
    companion object {
        private const val TAG = "StreamWebSocket"
    }

    private var ws: WebSocket? = null
    private var client: OkHttpClient? = null
    @Volatile var isConnected = false
        private set

    fun connect() {
        client = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).apply {
            if (!apiKey.isNullOrEmpty()) addHeader("x-api-key", apiKey)
        }.build()
        ws = client!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                LogCollector.i(TAG, "Stream WS connected: $streamType (stream $streamId)")
                onConnected?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onBinaryReceived?.invoke(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                LogCollector.w(TAG, "Unexpected text on stream WS: $streamType")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                LogCollector.i(TAG, "Stream WS closed: $streamType ($code)")
                onDisconnected?.invoke()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                LogCollector.e(TAG, "Stream WS failure: $streamType - ${t.message}")
                onDisconnected?.invoke()
            }
        })
    }

    fun sendBinary(data: ByteArray) {
        ws?.send(ByteString.of(*data))
    }

    fun disconnect() {
        isConnected = false
        ws?.close(1000, "Stream ended")
        ws = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
    }
}
