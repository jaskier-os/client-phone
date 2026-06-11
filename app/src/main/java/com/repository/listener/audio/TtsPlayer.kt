package com.repository.listener.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import com.repository.listener.util.LogCollector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class TtsPlayer(private val context: Context) {

    companion object {
        private const val TAG = "TtsPlayer"
        private const val WAV_HEADER_SIZE = 44
        private const val AMPLITUDE_WINDOW_MS = 50
    }

    interface TtsListener {
        fun onPlaybackStarted()
        fun onPlaybackFinished()
        fun onInterrupted(requestId: String)
        fun onTtsAmplitude(level: Float)
    }

    data class TtsChunk(
        val requestId: String,
        val pcmData: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val bitDepth: Int,
        val isFinal: Boolean
    )

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private var listener: TtsListener? = null
    private val queue = ConcurrentLinkedQueue<TtsChunk>()
    private val isPlaying = AtomicBoolean(false)
    private val isInterrupted = AtomicBoolean(false)
    private var playbackThread: Thread? = null
    private var currentTrack: AudioTrack? = null
    @Volatile
    private var currentRequestId: String? = null

    fun setListener(listener: TtsListener) {
        this.listener = listener
    }

    fun enqueue(requestId: String, audioBase64: String, isFinal: Boolean) {
        val raw: ByteArray
        try {
            raw = Base64.decode(audioBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to decode audio base64: ${e.message}")
            return
        }

        if (raw.size <= WAV_HEADER_SIZE) {
            LogCollector.w(TAG, "Audio chunk too small (${raw.size} bytes), skipping")
            return
        }

        // Parse WAV header for audio parameters
        val header = ByteBuffer.wrap(raw, 0, WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.position(22)
        val channels = header.short.toInt()
        val sampleRate = header.int
        header.position(34)
        val bitDepth = header.short.toInt()

        val pcmData = raw.copyOfRange(WAV_HEADER_SIZE, raw.size)

        queue.add(TtsChunk(requestId, pcmData, sampleRate, channels, bitDepth, isFinal))
        LogCollector.i(TAG, "Enqueued chunk for $requestId (${pcmData.size} bytes, final=$isFinal)")

        // Recover from dead playback thread
        if (playbackThread?.isAlive == false) {
            LogCollector.w(TAG, "Playback thread found dead, resetting state")
            isPlaying.set(false)
        }

        if (isPlaying.compareAndSet(false, true)) {
            isInterrupted.set(false)
            LogCollector.i(TAG, "Starting playback thread for $requestId")
            startPlaybackThread()
        }
    }

    fun interrupt() {
        val rid = currentRequestId
        isInterrupted.set(true)
        queue.clear()

        currentTrack?.let {
            try {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.pause()
                    it.flush()
                }
                if (it.playState != AudioTrack.PLAYSTATE_STOPPED) {
                    it.stop()
                }
            } catch (_: Exception) {}
        }

        releaseAudioFocus()
        currentRequestId = null
        isPlaying.set(false)

        if (rid != null) {
            LogCollector.i(TAG, "TTS playback interrupted for $rid")
            listener?.onInterrupted(rid)
        }
    }

    fun release() {
        interrupt()
        playbackThread = null
    }

    private fun requestAudioFocus() {
        // Ensure media volume is audible
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (currentVolume == 0) {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val minAudible = maxOf(1, maxVolume / 4)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, minAudible, 0)
            LogCollector.i(TAG, "Media volume was 0, set to $minAudible/$maxVolume")
        }

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        audioFocusRequest = focusRequest
        audioManager.requestAudioFocus(focusRequest)
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }

    private fun startPlaybackThread() {
        playbackThread = Thread({
            try {
                requestAudioFocus()
                var notifiedStart = false
                var lastChunkWasFinal = false
                var track: AudioTrack? = null
                var totalFramesWritten = 0L
                var trackSampleRate = 0

                while (!isInterrupted.get()) {
                    val chunk = queue.poll()
                    if (chunk == null) {
                        if (lastChunkWasFinal) break
                        Thread.sleep(100)
                        continue
                    }

                    if (!notifiedStart) {
                        currentRequestId = chunk.requestId
                        listener?.onPlaybackStarted()
                        notifiedStart = true
                    }

                    // Recreate AudioTrack when sample rate changes between chunks
                    if (track == null || chunk.sampleRate != trackSampleRate) {
                        track?.let { old ->
                            if (totalFramesWritten > 0 && !isInterrupted.get()) {
                                while (old.playbackHeadPosition < totalFramesWritten.toInt() && !isInterrupted.get()) {
                                    Thread.sleep(50)
                                }
                            }
                            try { old.stop(); old.release() } catch (_: Exception) {}
                        }
                        if (track != null) {
                            LogCollector.i(TAG, "Sample rate changed: ${trackSampleRate}Hz -> ${chunk.sampleRate}Hz, recreating AudioTrack")
                        }
                        track = createStreamTrack(chunk)
                        currentTrack = track
                        trackSampleRate = chunk.sampleRate
                        totalFramesWritten = 0
                        track.play()
                    }

                    lastChunkWasFinal = chunk.isFinal
                    totalFramesWritten += streamChunk(track, chunk)
                }

                // Drain: wait for all written frames to finish playing
                track?.let { t ->
                    if (!isInterrupted.get()) {
                        while (t.playbackHeadPosition < totalFramesWritten.toInt() && !isInterrupted.get()) {
                            Thread.sleep(50)
                        }
                    }
                    listener?.onTtsAmplitude(0f)
                    try {
                        t.stop()
                        t.release()
                    } catch (_: Exception) {}
                    if (currentTrack === t) currentTrack = null
                }

                releaseAudioFocus()

                if (!isInterrupted.get()) {
                    currentRequestId = null
                    isPlaying.set(false)
                    if (notifiedStart) {
                        listener?.onPlaybackFinished()
                    }
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Playback thread error: ${e.message}")
                currentRequestId = null
                isPlaying.set(false)
            }
        }, "tts-playback").also { it.start() }
    }

    private fun createStreamTrack(chunk: TtsChunk): AudioTrack {
        val channelConfig = if (chunk.channels == 1)
            AudioFormat.CHANNEL_OUT_MONO
        else
            AudioFormat.CHANNEL_OUT_STEREO

        val audioFormat = when (chunk.bitDepth) {
            16 -> AudioFormat.ENCODING_PCM_16BIT
            8 -> AudioFormat.ENCODING_PCM_8BIT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

        val minBuf = AudioTrack.getMinBufferSize(chunk.sampleRate, channelConfig, audioFormat)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(chunk.sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * Stream PCM data to the AudioTrack in small windows, emitting amplitude for each.
     * write() blocks when the internal buffer is full, naturally pacing output.
     */
    private fun streamChunk(track: AudioTrack, chunk: TtsChunk): Long {
        val bytesPerFrame = (chunk.bitDepth / 8) * chunk.channels
        val windowBytes = chunk.sampleRate * chunk.channels * (chunk.bitDepth / 8) * AMPLITUDE_WINDOW_MS / 1000
        var offset = 0
        var framesWritten = 0L

        while (offset < chunk.pcmData.size && !isInterrupted.get()) {
            val writeSize = minOf(windowBytes, chunk.pcmData.size - offset)

            // Compute RMS amplitude for this window
            if (chunk.bitDepth == 16 && writeSize >= 2) {
                var sumSq = 0.0
                var count = 0
                val buf = ByteBuffer.wrap(chunk.pcmData, offset, writeSize).order(ByteOrder.LITTLE_ENDIAN)
                while (buf.remaining() >= 2) {
                    val sample = buf.short.toDouble()
                    sumSq += sample * sample
                    count++
                }
                if (count > 0) {
                    val rms = kotlin.math.sqrt(sumSq / count)
                    val level = (rms / 16384.0).toFloat().coerceIn(0f, 1f)
                    listener?.onTtsAmplitude(level)
                }
            }

            val written = track.write(chunk.pcmData, offset, writeSize)
            if (written < 0) break
            offset += written
            framesWritten += written / bytesPerFrame
        }

        return framesWritten
    }
}
