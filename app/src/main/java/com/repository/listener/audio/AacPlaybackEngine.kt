package com.repository.listener.audio

import android.media.*
import com.repository.listener.util.LogCollector
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Decodes ADTS AAC files via MediaExtractor + MediaCodec and plays via AudioTrack.
 * Replaces ExoPlayer which cannot seek accurately in ADTS (no seek table, CBR estimation fails
 * on silence-heavy files where frame sizes vary dramatically).
 *
 * Position tracking uses decoder's presentationTimeUs which reflects actual frame timestamps,
 * not byte-offset estimates.
 */
class AacPlaybackEngine {

    companion object {
        private const val TAG = "AacPlayback"
        private const val TIMEOUT_US = 5000L
    }

    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var decodeThread: Thread? = null

    private val playing = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val pendingSeekUs = AtomicLong(-1)

    /** Current playback position in ms. Readable from any thread. */
    @Volatile var positionMs: Long = 0
        private set

    var onComplete: (() -> Unit)? = null

    fun prepare(file: File): Boolean {
        try {
            val ext = MediaExtractor()
            ext.setDataSource(file.absolutePath)
            if (ext.trackCount == 0) {
                ext.release()
                LogCollector.e(TAG, "No tracks in ${file.name}")
                return false
            }
            ext.selectTrack(0)
            val format = ext.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
                ext.release()
                return false
            }
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val dec = MediaCodec.createDecoderByType(mime)
            dec.configure(format, null, null, 0)
            dec.start()

            val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            val trk = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            extractor = ext
            decoder = dec
            audioTrack = trk

            LogCollector.e(TAG, "Prepared ${file.name}: ${sampleRate}Hz, ${channels}ch")
            return true
        } catch (e: Exception) {
            LogCollector.e(TAG, "Prepare failed: ${e.message}")
            release()
            return false
        }
    }

    fun seekTo(positionMs: Long) {
        this.positionMs = positionMs
        pendingSeekUs.set(positionMs * 1000L)
    }

    /**
     * Reliable seek for ADTS AAC: rewind to start, then scan forward frame-by-frame.
     * If target is beyond actual file content (common for silence-heavy recordings where
     * metadata duration is inflated), backs up to play from the last available frame.
     *
     * Returns true if successfully positioned, false if file has no data.
     */
    private fun scanToPosition(ext: MediaExtractor, targetUs: Long): Boolean {
        ext.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var skipped = 0
        var lastValidTimeUs = -1L

        // First pass: scan forward to target
        while (true) {
            val t = ext.sampleTime
            if (t < 0) break       // EOF
            lastValidTimeUs = t
            if (t >= targetUs) {
                LogCollector.e(TAG, "scanToPosition: target=${targetUs/1000}ms, skipped $skipped frames, landed at ${t/1000}ms")
                return true
            }
            ext.advance()
            skipped++
        }

        // Target is beyond actual file content.
        // Rewind to the start of the last available audio and play from there.
        if (lastValidTimeUs < 0) {
            LogCollector.e(TAG, "scanToPosition: file has no audio data")
            return false
        }

        LogCollector.e(TAG, "scanToPosition: target=${targetUs/1000}ms beyond file content (${lastValidTimeUs/1000}ms, $skipped frames). Rewinding to find playable position.")

        // Rewind and seek to ~5 seconds before the last frame (so there's something to play)
        val rewindTargetUs = maxOf(0L, lastValidTimeUs - 5_000_000L)
        ext.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        while (true) {
            val t = ext.sampleTime
            if (t < 0) break
            if (t >= rewindTargetUs) {
                LogCollector.e(TAG, "scanToPosition: rewound to ${t/1000}ms (last content at ${lastValidTimeUs/1000}ms)")
                return true
            }
            ext.advance()
        }
        return false
    }

    fun play() {
        if (playing.get()) {
            // Resume from pause
            paused.set(false)
            try { audioTrack?.play() } catch (_: Exception) {}
            return
        }

        playing.set(true)
        paused.set(false)
        released.set(false)

        try { audioTrack?.play() } catch (_: Exception) {}

        decodeThread = Thread({
            val ext = extractor ?: return@Thread
            val dec = decoder ?: return@Thread
            val trk = audioTrack ?: return@Thread
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false

            // Apply initial seek by scanning forward (CBR seekTo is broken on ADTS)
            val initialSeek = pendingSeekUs.getAndSet(-1)
            if (initialSeek >= 0) {
                if (!scanToPosition(ext, initialSeek)) {
                    LogCollector.e(TAG, "Initial seek failed: no audio data in file")
                    android.os.Handler(android.os.Looper.getMainLooper()).post { onComplete?.invoke() }
                    return@Thread
                }
                val actualUs = ext.sampleTime
                positionMs = if (actualUs >= 0) actualUs / 1000 else 0
                LogCollector.e(TAG, "Initial seek to ${initialSeek/1000}ms, playing from ${positionMs}ms")
            }

            while (playing.get() && !released.get()) {
                // Handle pending seek
                val seekUs = pendingSeekUs.getAndSet(-1)
                if (seekUs >= 0) {
                    if (scanToPosition(ext, seekUs)) {
                        dec.flush()
                        inputDone = false
                        trk.flush()
                        val actualUs = ext.sampleTime
                        positionMs = if (actualUs >= 0) actualUs / 1000 else 0
                        LogCollector.e(TAG, "Seek to ${seekUs/1000}ms, playing from ${positionMs}ms")
                    } else {
                        LogCollector.e(TAG, "Seek to ${seekUs/1000}ms failed: no data")
                    }
                }

                // Handle pause
                if (paused.get()) {
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                    continue
                }

                // Feed input to decoder
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val size = ext.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
                            ext.advance()
                        }
                    }
                }

                // Drain output from decoder -> AudioTrack
                val outIdx = dec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIdx >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        dec.releaseOutputBuffer(outIdx, false)
                        LogCollector.e(TAG, "Reached end of stream")
                        val handler = android.os.Handler(android.os.Looper.getMainLooper())
                        handler.post { onComplete?.invoke() }
                        break
                    }
                    if (bufferInfo.size > 0) {
                        val pcmBuf = dec.getOutputBuffer(outIdx)!!
                        pcmBuf.position(bufferInfo.offset)
                        pcmBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val bytes = ByteArray(bufferInfo.size)
                        pcmBuf.get(bytes)
                        trk.write(bytes, 0, bytes.size)

                        // Position from actual decoded frame timestamp
                        positionMs = bufferInfo.presentationTimeUs / 1000
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                }
            }

            LogCollector.e(TAG, "Decode thread exiting, playing=${playing.get()}")
        }, "AacPlayback").apply {
            isDaemon = true
            start()
        }
    }

    fun pause() {
        paused.set(true)
        try { audioTrack?.pause() } catch (_: Exception) {}
    }

    fun isPlaying(): Boolean = playing.get() && !paused.get()

    fun release() {
        released.set(true)
        playing.set(false)
        paused.set(false)

        decodeThread?.join(3000)
        decodeThread = null

        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null

        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null

        try { extractor?.release() } catch (_: Exception) {}
        extractor = null
    }
}
