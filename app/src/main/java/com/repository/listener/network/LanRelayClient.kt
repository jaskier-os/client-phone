package com.repository.listener.network

import com.repository.listener.util.LogCollector
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Direct LAN signaling channel to the desktop relay's local WebSocket server
 * (`ws://<pc-lan-ip>:<port>/ws/device`). Speaks the audio-relay subset of the
 * orchestrator envelope protocol so the phone can stream audio to a same-network
 * PC without the cloud hop.
 *
 * This is deliberately narrow: it only carries audio-relay + WebRTC signaling.
 * Everything else (AI requests, TTS, RC, Telegram) stays on the main cloud
 * [OrchestratorClient]. The WebRTC media path and [WebRTCClient] are unchanged --
 * this class just substitutes the transport for the handful of audio-relay
 * signaling messages.
 */
class LanRelayClient(
    private val url: String,
    private val deviceId: String,
) {
    companion object {
        private const val TAG = "LanRelayClient"
        private const val CONNECT_TIMEOUT_MS = 4_000L
    }

    interface Listener {
        fun onConnected()
        fun onClosed(reason: String)
        fun onAudioRelayAck(sampleRate: Int, channels: Int, bitrate: Int, frameSize: Int, frameDurationMs: Int)
        fun onAudioRelayError(reason: String)
        fun onWebRTCOffer(streamId: Int, sdp: String)
    }

    @Volatile
    var listener: Listener? = null

    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    var isConnected = false
        private set

    @Volatile
    private var closed = false

    fun connect() {
        closed = false
        val http = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .build()
        client = http
        val request = Request.Builder().url(url).build()
        LogCollector.i(TAG, "Connecting to LAN relay at $url")
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                webSocket.send(Protocol.createIdentifyMessage(deviceId).toString())
                LogCollector.i(TAG, "LAN relay connected, sent identify")
                listener?.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                if (!closed) listener?.onClosed("closed:$code")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                LogCollector.w(TAG, "LAN relay failure: ${t.message}")
                if (!closed) listener?.onClosed("failure:${t.message}")
            }
        })
    }

    private fun handleMessage(text: String) {
        val env = try { JSONObject(text) } catch (e: Exception) {
            LogCollector.w(TAG, "Bad JSON from LAN relay: ${e.message}")
            return
        }
        when (env.optString("type")) {
            Protocol.TYPE_AUDIO_RELAY_ACK -> listener?.onAudioRelayAck(
                env.optInt("sampleRate", 48000),
                env.optInt("channels", 2),
                env.optInt("bitrate", 64000),
                env.optInt("frameSize", 960),
                env.optInt("frameDurationMs", 20),
            )
            Protocol.TYPE_AUDIO_RELAY_ERROR -> listener?.onAudioRelayError(env.optString("reason", "unknown"))
            Protocol.TYPE_WEBRTC_OFFER -> listener?.onWebRTCOffer(
                env.optInt("streamId", 0),
                env.optString("sdp", ""),
            )
            // The desktop bundles all ICE candidates in the offer SDP (no trickle
            // ICE), and audio is unidirectional desktop -> phone, so no webrtc_ice
            // is exchanged on this path.
            Protocol.TYPE_HEALTH -> { /* server does not ping; nothing to do */ }
        }
    }

    private fun send(msg: JSONObject): Boolean {
        val socket = ws ?: return false
        return try {
            socket.send(msg.toString())
        } catch (e: Exception) {
            LogCollector.w(TAG, "Send failed: ${e.message}")
            false
        }
    }

    fun sendAudioRelayStart(bitrate: Int): Boolean =
        send(Protocol.createAudioRelayStart(deviceId, bitrate))

    fun sendAudioRelayStop(): Boolean =
        send(Protocol.createAudioRelayStop(deviceId))

    fun sendWebRTCAnswer(streamId: Int, sdp: String): Boolean =
        send(Protocol.createWebRTCAnswer(streamId, sdp))

    fun close() {
        closed = true
        isConnected = false
        try {
            ws?.close(1000, null)
        } catch (_: Exception) {
        }
        ws = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
    }
}
