package com.repository.listener.arstream

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Network
import com.repository.listener.sync.WifiDirectJoiner
import com.repository.listener.util.LogCollector
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Phone-side client for the glasses' live AR stream.
 *
 * Joins the glasses' WiFi-Direct group, then opens two sockets: video (decoded straight into
 * [com.repository.listener.ui.ScreenStreamDecoder]) and duplex audio.
 */
class ArStreamClient(
    private val context: Context,
    private val state: ArStreamSessionState,
) {

    /** Raw H.264 frame bodies (10-byte header + NAL), ready for ScreenStreamDecoder.feedFrame. */
    var onVideoFrame: ((ByteArray) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // Per-socket bind, NOT bindProcessToNetwork: a call-length process bind would drag the
    // orchestrator WebSocket onto the p2p link, which has no internet route.
    private val joiner = WifiDirectJoiner(context, bindProcessNetwork = false)

    private var videoSocket: Socket? = null
    private var audioSocket: Socket? = null
    @Volatile private var audioOut: OutputStream? = null
    private var track: AudioTrack? = null

    private val running = AtomicBoolean(false)
    private val outboundAudio = LinkedBlockingQueue<ByteArray>(AUDIO_QUEUE_CAPACITY)

    /**
     * Join the group and connect. Blocking -- call from a background thread.
     *
     * @param details the `details` object from the glasses' start_ar_stream result.
     */
    fun connect(details: JSONObject, videoPort: Int, audioPort: Int): Boolean {
        if (running.get()) return true

        val ip = details.optString("ip")
        if (ip.isEmpty()) {
            onError?.invoke("glasses returned no group IP")
            return false
        }

        val joined = LinkedBlockingQueue<Pair<Boolean, String>>()
        joiner.remoteLog = { LogCollector.i(TAG, it) }
        joiner.onReady = { joined.offer(true to it) }
        joiner.onFailed = { joined.offer(false to it) }
        joiner.join(
            WifiDirectJoiner.GroupDetails(
                ssid = details.optString("ssid"),
                passphrase = details.optString("passphrase"),
                ip = ip,
                port = details.optInt("port"),
                deviceAddress = details.optString("deviceAddress").ifEmpty { null },
            )
        )

        val res = joined.poll(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (res == null) {
            joiner.close()
            onError?.invoke("timed out joining the glasses WiFi-Direct group")
            return false
        }
        if (!res.first) {
            joiner.close()
            onError?.invoke("WiFi-Direct join failed: ${res.second}")
            return false
        }

        val network = joiner.p2pNetwork
        running.set(true)

        return try {
            videoSocket = openSocket(network, ip, videoPort)
            audioSocket = openSocket(network, ip, audioPort)
            audioOut = audioSocket!!.getOutputStream()

            startPlayback()
            thread(name = "ArStream-videoRecv") { videoLoop() }
            thread(name = "ArStream-audioRecv") { audioLoop() }
            thread(name = "ArStream-audioSend") { audioSendLoop() }

            // The decoder caches no SPS/PPS, so ask for config + IDR right away rather than
            // waiting up to a GOP (or forever, if the encoder already sent its only config).
            requestKeyframe()
            onConnected?.invoke()
            LogCollector.i(TAG, "connected to $ip video=$videoPort audio=$audioPort")
            true
        } catch (e: Exception) {
            LogCollector.e(TAG, "connect failed: ${e.message}")
            onError?.invoke("failed to connect to the glasses stream: ${e.message}")
            disconnect()
            false
        }
    }

    private fun openSocket(network: Network?, ip: String, port: Int): Socket {
        val s = Socket()
        // Bind THIS socket to the p2p network, leaving the process default untouched.
        network?.bindSocket(s)
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
        return s
    }

    private fun startPlayback() {
        val minBuf = AudioTrack.getMinBufferSize(
            ArStreamProtocol.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Voice-communication so the platform routes and processes this as a call leg.
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(ArStreamProtocol.AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
    }

    private fun videoLoop() {
        val socket = videoSocket ?: return
        val input = try {
            DataInputStream(socket.getInputStream().buffered())
        } catch (e: Exception) {
            if (running.get()) onError?.invoke("video stream unavailable: ${e.message}")
            return
        }
        while (running.get()) {
            try {
                val len = input.readInt()
                if (len <= 0 || len > ArStreamProtocol.MAX_FRAME_BYTES) {
                    LogCollector.e(TAG, "bad video frame length $len")
                    break
                }
                val body = ByteArray(len)
                input.readFully(body)
                onVideoFrame?.invoke(body)
            } catch (e: Exception) {
                if (running.get()) {
                    LogCollector.e(TAG, "video stream ended: ${e.message}")
                    onError?.invoke("video stream ended")
                }
                break
            }
        }
    }

    private fun audioLoop() {
        val socket = audioSocket ?: return
        val input = try {
            DataInputStream(socket.getInputStream().buffered())
        } catch (e: Exception) {
            if (running.get()) LogCollector.e(TAG, "audio stream unavailable: ${e.message}")
            return
        }
        while (running.get()) {
            try {
                val len = input.readInt()
                if (len <= 0 || len > ArStreamProtocol.MAX_FRAME_BYTES) {
                    LogCollector.e(TAG, "bad audio frame length $len")
                    break
                }
                val body = ByteArray(len)
                input.readFully(body)
                if (body[0] != ArStreamProtocol.MSG_AUDIO) continue
                // Honour the mute locally too, so audio already in flight when the user muted
                // does not play after the fact.
                if (!state.shouldAcceptGlassesAudio()) continue
                val pcm = ArStreamProtocol.decodeAudio(body)
                track?.write(pcm, 0, pcm.size)
            } catch (e: Exception) {
                if (running.get()) LogCollector.e(TAG, "audio stream ended: ${e.message}")
                break
            }
        }
    }

    private fun audioSendLoop() {
        while (running.get()) {
            val frame = try {
                outboundAudio.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
            } catch (_: InterruptedException) {
                break
            }
            val out = audioOut ?: continue
            try {
                out.write(frame)
                out.flush()
            } catch (e: Exception) {
                if (running.get()) LogCollector.e(TAG, "audio send failed: ${e.message}")
            }
        }
    }

    /** Feed phone mic PCM (16 kHz mono) for the glasses wearer to hear. */
    fun sendMicAudio(pcm: ShortArray, length: Int) {
        if (!running.get() || !state.shouldSendPhoneMic()) return
        offer(ArStreamProtocol.frameAudio(pcm, length))
    }

    fun setPhoneMicMuted(muted: Boolean) {
        state.setPhoneMicMuted(muted)
        offer(ArStreamProtocol.frameControl(ArStreamProtocol.CTRL_MUTE_PHONE_MIC, muted))
    }

    fun setGlassesMicMuted(muted: Boolean) {
        state.setGlassesMicMuted(muted)
        offer(ArStreamProtocol.frameControl(ArStreamProtocol.CTRL_MUTE_GLASSES_MIC, muted))
    }

    /** Ask for a fresh config + IDR, e.g. after the TextureView Surface was recreated. */
    fun requestKeyframe() {
        offer(ArStreamProtocol.frameControl(ArStreamProtocol.CTRL_REQUEST_KEYFRAME, true))
    }

    private fun offer(frame: ByteArray) {
        if (!outboundAudio.offer(frame)) {
            outboundAudio.poll()
            outboundAudio.offer(frame)
        }
    }

    fun disconnect() {
        if (!running.getAndSet(false)) {
            // Still make sure a half-open join is released.
            try { joiner.close() } catch (_: Exception) {}
            return
        }
        try { outboundAudio.put(ArStreamProtocol.frameControl(ArStreamProtocol.CTRL_STOP, true)) } catch (_: Exception) {}
        try { audioOut?.flush() } catch (_: Exception) {}

        try { videoSocket?.close() } catch (_: Exception) {}
        try { audioSocket?.close() } catch (_: Exception) {}
        videoSocket = null
        audioSocket = null
        audioOut = null

        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null

        outboundAudio.clear()
        try { joiner.close() } catch (e: Exception) { LogCollector.e(TAG, "joiner close: ${e.message}") }
        LogCollector.i(TAG, "disconnected")
    }

    private companion object {
        const val TAG = "ArStreamClient"
        const val JOIN_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 10_000
        const val AUDIO_QUEUE_CAPACITY = 100
        const val POLL_MS = 200L
    }
}
