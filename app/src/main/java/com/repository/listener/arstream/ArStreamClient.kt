package com.repository.listener.arstream

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
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

    /**
     * Taps on the two audio directions, for the session recorder. They fire only for audio that is
     * actually played / actually sent, so a muted side contributes nothing and the recording shows
     * the mute exactly as the user heard it.
     */
    var onUplinkAudio: ((ShortArray) -> Unit)? = null
    var onPhoneMicAudio: ((ShortArray, Int) -> Unit)? = null

    // Per-socket bind, NOT bindProcessToNetwork: a call-length process bind would drag the
    // orchestrator WebSocket onto the p2p link, which has no internet route.
    private val joiner = WifiDirectJoiner(context, bindProcessNetwork = false)

    private var videoSocket: Socket? = null
    private var audioSocket: Socket? = null
    @Volatile private var audioOut: OutputStream? = null
    private var track: AudioTrack? = null

    private val running = AtomicBoolean(false)

    /** Latches on the first disconnect so concurrent callers cannot race the teardown. */
    private val closed = AtomicBoolean(false)
    private val outboundAudio = LinkedBlockingQueue<ByteArray>(AUDIO_QUEUE_CAPACITY)

    /**
     * Control frames (mute, keyframe, stop) ride a separate queue that is drained first and never
     * evicted. On the shared queue an overflow drop could silently discard a mute -- leaving the
     * far end transmitting after the user muted it.
     */
    private val outboundControl = LinkedBlockingQueue<ByteArray>()

    private var audioRecvThread: Thread? = null

    // Traffic counters, logged periodically. Without these a silent link is indistinguishable
    // from a stalled decoder, which is exactly the ambiguity that wastes debugging time.
    @Volatile private var videoFrameCount = 0L
    @Volatile private var audioFrameCount = 0L
    @Volatile private var micFrameCount = 0L
    @Volatile private var samplesWritten = 0L

    /** Samples the AudioTrack refused because its buffer was full. Silent truncation otherwise. */
    @Volatile private var shortWrites = 0L
    @Volatile private var droppedSamples = 0L

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

        LogCollector.i(TAG, "connect: joining group ip=$ip video=$videoPort audio=$audioPort")
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

        // Binding to a p2p Network is best-effort, NOT a precondition.
        //
        // This hardware runs the group owner on 192.168.43.1 and reuses wlan0
        // (`p2p_no_group_iface=1`), so there is no separate p2p Network to resolve -- the existing
        // file-sync and sideload paths have always found none and reach the glasses over the
        // routing table regardless. Requiring one here blocked every session on this device.
        val network = joiner.p2pNetwork
        LogCollector.i(
            TAG,
            if (network != null) "binding sockets to the p2p network"
            else "no distinct p2p network (group shares wlan0) -- connecting via the routing table"
        )
        // A disconnect may have landed while we were joining; do not open sockets nobody will close.
        if (closed.get()) {
            joiner.close()
            return false
        }
        running.set(true)

        return try {
            // Retry like GlassesSyncClient does: the group is formed but not always immediately
            // routable, and the first connect can refuse before the GO's listener is reachable.
            videoSocket = openSocketWithRetry(network, ip, videoPort)
            audioSocket = openSocketWithRetry(network, ip, audioPort)
            audioOut = audioSocket!!.getOutputStream()

            startPlayback()
            thread(name = "ArStream-videoRecv") { videoLoop() }
            audioRecvThread = thread(name = "ArStream-audioRecv") { audioLoop() }
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

    private fun openSocketWithRetry(network: Network?, ip: String, port: Int): Socket {
        var last: Exception? = null
        for (attempt in 1..CONNECT_ATTEMPTS) {
            try {
                return openSocket(network, ip, port)
            } catch (e: Exception) {
                last = e
                LogCollector.i(TAG, "connect $ip:$port attempt $attempt failed: ${e.message}")
                if (attempt < CONNECT_ATTEMPTS) Thread.sleep(CONNECT_RETRY_BASE_MS * attempt)
            }
        }
        throw last ?: java.io.IOException("connect to $ip:$port failed")
    }

    private fun openSocket(network: Network?, ip: String, port: Int): Socket {
        val s = Socket()
        // Per-socket bind when a distinct p2p Network exists; this leaves the process default (and
        // therefore the orchestrator WebSocket) untouched, unlike the process-wide bind the
        // sideload path uses. When there is no such Network the routing table already reaches the
        // group owner -- that is how file sync has always worked here.
        try {
            network?.bindSocket(s)
        } catch (e: Exception) {
            LogCollector.i(TAG, "bindSocket failed (${e.message}); continuing unbound")
        }
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
                    // USAGE_MEDIA -> STREAM_MUSIC -> loudspeaker at normal media volume.
                    // USAGE_VOICE_COMMUNICATION would land on STREAM_VOICE_CALL, which without a
                    // real call routes to the EARPIECE at call volume and reads as silence. This
                    // gives up the platform voice-comm AEC; the glasses run WebRtcAecm internally,
                    // and inaudible-with-AEC is worse than audible-without.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
        logAudioRouting()
    }

    /**
     * One-shot routing diagnostic. "Which stream did the platform pick, and is its volume zero"
     * is invisible from every other log line, and it is the whole question when audio is silent.
     */
    private fun logAudioRouting() {
        val t = track
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val streamType = try { t?.streamType } catch (_: Exception) { null }
        LogCollector.i(
            TAG,
            "routing usage=MEDIA streamType=$streamType (STREAM_MUSIC=${AudioManager.STREAM_MUSIC}) " +
                "musicVolume=${am?.getStreamVolume(AudioManager.STREAM_MUSIC)}/" +
                "${am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} " +
                "audioMode=${am?.mode} trackState=${t?.state} playState=${t?.playState}"
        )
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
                videoFrameCount++
                if (videoFrameCount == 1L || videoFrameCount % 150L == 0L) {
                    val flags = if (body.size > 1) body[1].toInt() and 0xFF else -1
                    LogCollector.i(TAG, "video frame #$videoFrameCount len=$len flags=$flags")
                }
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
                audioFrameCount++
                onUplinkAudio?.invoke(pcm)
                // NON_BLOCKING so teardown is never stuck behind a full playback buffer.
                val written = track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING) ?: 0
                if (written > 0) samplesWritten += written
                if (written < pcm.size) {
                    // Ignoring the return silently truncates playback, which presents as chopped
                    // audio and is indistinguishable from a routing failure without this counter.
                    shortWrites++
                    droppedSamples += (pcm.size - written.coerceAtLeast(0))
                    if (shortWrites == 1L || shortWrites % 100L == 0L) {
                        LogCollector.i(
                            TAG,
                            "short write #$shortWrites wrote=$written/${pcm.size} droppedSamples=$droppedSamples"
                        )
                    }
                }
                if (audioFrameCount == 1L || audioFrameCount % 200L == 0L) {
                    var peak = 0
                    for (s in pcm) { val a = kotlin.math.abs(s.toInt()); if (a > peak) peak = a }
                    // playbackHeadPosition proves the samples are being CONSUMED, not just accepted:
                    // a track routed nowhere still takes writes but its head never advances.
                    val head = try { track?.playbackHeadPosition } catch (_: Exception) { null }
                    LogCollector.i(
                        TAG,
                        "uplink audio #$audioFrameCount samples=${pcm.size} peak=$peak " +
                            "head=$head written=$samplesWritten shortWrites=$shortWrites"
                    )
                }
            } catch (e: Exception) {
                if (running.get()) LogCollector.e(TAG, "audio stream ended: ${e.message}")
                break
            }
        }
    }

    private fun audioSendLoop() {
        while (running.get()) {
            // Control first, always: a mute must not wait behind queued audio.
            val frame = outboundControl.poll() ?: try {
                outboundAudio.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
            } catch (_: InterruptedException) {
                break
            }
            writeFrame(frame)
        }
    }

    private fun writeFrame(frame: ByteArray) {
        val out = audioOut ?: return
        try {
            out.write(frame)
            out.flush()
        } catch (e: Exception) {
            if (running.get()) LogCollector.e(TAG, "audio send failed: ${e.message}")
        }
    }

    /** Feed phone mic PCM (16 kHz mono) for the glasses wearer to hear. */
    fun sendMicAudio(pcm: ShortArray, length: Int) {
        if (!running.get() || !state.shouldSendPhoneMic()) return
        onPhoneMicAudio?.invoke(pcm, length)
        micFrameCount++
        if (micFrameCount == 1L || micFrameCount % 200L == 0L) {
            var peak = 0
            for (i in 0 until length) { val a = kotlin.math.abs(pcm[i].toInt()); if (a > peak) peak = a }
            LogCollector.i(TAG, "phone mic uplink #$micFrameCount len=$length peak=$peak")
        }
        offer(ArStreamProtocol.frameAudio(pcm, length))
    }

    fun setPhoneMicMuted(muted: Boolean) {
        state.setPhoneMicMuted(muted)
        outboundControl.offer(ArStreamProtocol.frameControl(ArStreamProtocol.CTRL_MUTE_PHONE_MIC, muted))
    }

    fun setGlassesMicMuted(muted: Boolean) {
        state.setGlassesMicMuted(muted)
        outboundControl.offer(ArStreamProtocol.frameControl(ArStreamProtocol.CTRL_MUTE_GLASSES_MIC, muted))
    }

    /** Ask for a fresh config + IDR, e.g. after the TextureView Surface was recreated. */
    fun requestKeyframe() {
        outboundControl.offer(ArStreamProtocol.frameControl(ArStreamProtocol.CTRL_REQUEST_KEYFRAME, true))
    }

    /** Drop the oldest audio when the link cannot keep up; never blocks the mic callback. */
    private fun offer(frame: ByteArray) {
        if (!outboundAudio.offer(frame)) {
            outboundAudio.poll()
            outboundAudio.offer(frame)
        }
    }

    /**
     * Tear the session down. Safe to call from any thread and any number of times, but MUST NOT be
     * called from the main thread: it writes to a socket and joins a thread.
     */
    fun disconnect() {
        // CAS, not a plain read: onDestroy and an error path can call this concurrently, and two
        // callers both proceeding would release the AudioTrack while the other is still writing.
        if (!closed.compareAndSet(false, true)) return

        if (!running.get()) {
            // Still make sure a half-open join is released.
            try { joiner.close() } catch (_: Exception) {}
            return
        }
        // Send STOP while the socket is still up and the send loop still running -- writing it
        // after clearing `running` would queue it into a loop that has already exited.
        writeFrame(ArStreamProtocol.frameControl(ArStreamProtocol.CTRL_STOP, true))

        running.set(false)

        // Closing the sockets is what unblocks the readers parked in readInt().
        try { videoSocket?.close() } catch (_: Exception) {}
        try { audioSocket?.close() } catch (_: Exception) {}
        videoSocket = null
        audioSocket = null
        audioOut = null

        // Join the receive thread before releasing the track: it may be inside track.write(),
        // which closing the socket does NOT interrupt, and releasing under it would crash native.
        try { audioRecvThread?.join(AUDIO_JOIN_TIMEOUT_MS) } catch (_: InterruptedException) {}
        audioRecvThread = null

        try { track?.pause() } catch (_: Exception) {}
        try { track?.flush() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null

        outboundAudio.clear()
        outboundControl.clear()
        try { joiner.close() } catch (e: Exception) { LogCollector.e(TAG, "joiner close: ${e.message}") }
        LogCollector.i(TAG, "disconnected")
    }

    private companion object {
        const val TAG = "ArStreamClient"
        const val JOIN_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 10_000
        const val AUDIO_QUEUE_CAPACITY = 100
        const val POLL_MS = 200L
        const val AUDIO_JOIN_TIMEOUT_MS = 500L

        /** Same 3-attempt / linear-backoff shape GlassesSyncClient uses for its pulls. */
        const val CONNECT_ATTEMPTS = 3
        const val CONNECT_RETRY_BASE_MS = 500L
    }
}
