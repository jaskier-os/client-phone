package com.repository.listener.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import com.repository.listener.util.LogCollector

class SystemAudioCapturer(
    private val mediaProjection: MediaProjection
) {
    companion object {
        private const val TAG = "SystemAudioCapturer"
        private const val CAPTURE_RATE = 48000  // Must match device remote submix native rate
        private const val OUTPUT_RATE = 16000   // Transcriber expects 16kHz
        private const val DOWNSAMPLE_RATIO = CAPTURE_RATE / OUTPUT_RATE  // 3
    }

    interface Listener {
        fun onSystemAudioChunk(samples: ShortArray)
    }

    var listener: Listener? = null
    @Volatile var isCapturing = false
        private set

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    fun start(): Boolean {
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(CAPTURE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(
            CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(bufferSize, 4096))
                .build()
        } catch (e: UnsupportedOperationException) {
            LogCollector.e(TAG, "Failed to create AudioRecord for playback capture: ${e.message}")
            return false
        } catch (e: SecurityException) {
            LogCollector.e(TAG, "Security exception creating AudioRecord: ${e.message}")
            return false
        }

        isCapturing = true
        audioRecord?.startRecording()
        LogCollector.i(TAG, "System audio capture started (${CAPTURE_RATE}Hz -> ${OUTPUT_RATE}Hz mono)")

        captureThread = Thread({
            // Read at 48kHz, downsample 3:1 to 16kHz
            val captureBuffer = ShortArray(1536)  // 1536 samples @ 48kHz = 32ms -> 512 samples @ 16kHz
            val outputBuffer = ShortArray(512)
            while (isCapturing) {
                val read = audioRecord?.read(captureBuffer, 0, captureBuffer.size) ?: -1
                if (read > 0) {
                    val outLen = read / DOWNSAMPLE_RATIO
                    for (i in 0 until outLen) {
                        outputBuffer[i] = captureBuffer[i * DOWNSAMPLE_RATIO]
                    }
                    listener?.onSystemAudioChunk(outputBuffer.copyOf(outLen))
                }
            }
        }, "SystemAudioCapture").also { it.start() }
        return true
    }

    fun stop() {
        isCapturing = false
        captureThread?.join(1000)
        captureThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        LogCollector.i(TAG, "System audio capture stopped")
    }
}
