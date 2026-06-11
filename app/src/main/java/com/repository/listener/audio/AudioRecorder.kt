package com.repository.listener.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.repository.listener.util.LogCollector
import androidx.annotation.RequiresPermission

class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHUNK_SIZE = 512 // samples, matches Silero VAD input size
    }

    interface ChunkListener {
        fun onChunk(samples: ShortArray)
    }

    private var listener: ChunkListener? = null
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    @Volatile
    private var isRecording = false
    private var chunkCount = 0L

    fun setChunkListener(listener: ChunkListener) {
        this.listener = listener
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (isRecording) return

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer, CHUNK_SIZE * 2) // at least CHUNK_SIZE samples in bytes

        // Use MIC source (matching Clawsses/Tuner reference implementations).
        // CXR SDK routes glasses mic to phone via BT SCO transparently.
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            LogCollector.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return
        }

        isRecording = true
        chunkCount = 0L

        // Attach AcousticEchoCanceler to suppress phone speaker bleed into mic
        val sessionId = audioRecord!!.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                echoCanceler = AcousticEchoCanceler.create(sessionId)
                echoCanceler?.enabled = true
                LogCollector.i(TAG, "AcousticEchoCanceler enabled (session=$sessionId)")
            } catch (e: Exception) {
                LogCollector.w(TAG, "AEC create failed: ${e.message}")
            }
        } else {
            LogCollector.w(TAG, "AcousticEchoCanceler not available on this device")
        }

        // Attach NoiseSuppressor for ambient noise reduction
        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
                LogCollector.i(TAG, "NoiseSuppressor enabled (session=$sessionId)")
            } catch (e: Exception) {
                LogCollector.w(TAG, "NoiseSuppressor create failed: ${e.message}")
            }
        } else {
            LogCollector.w(TAG, "NoiseSuppressor not available on this device")
        }

        // Log routing info
        val routedDevice = audioRecord?.routedDevice
        LogCollector.i(TAG, "AudioRecord routed to: type=${routedDevice?.type} name=${routedDevice?.productName} addr=${routedDevice?.address}")

        audioRecord?.startRecording()

        recordThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ShortArray(CHUNK_SIZE)
            var globalMaxAmp = 0
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, CHUNK_SIZE) ?: -1
                if (read == CHUNK_SIZE) {
                    chunkCount++
                    var maxAbs = 0
                    for (i in 0 until read) {
                        val abs = kotlin.math.abs(buffer[i].toInt())
                        if (abs > maxAbs) maxAbs = abs
                    }
                    if (maxAbs > globalMaxAmp) globalMaxAmp = maxAbs
                    // Log first 50 chunks, then every 200, plus any spike > 10
                    if (chunkCount <= 50 || chunkCount % 200 == 0L || maxAbs > 10) {
                        LogCollector.i(TAG, "Chunk #$chunkCount maxAmp=$maxAbs (peak=$globalMaxAmp)")
                    }
                    listener?.onChunk(buffer.copyOf())
                } else if (read < 0) {
                    LogCollector.e(TAG, "AudioRecord read error: $read")
                    break
                }
            }
        }, "AudioRecorder-Thread").apply {
            isDaemon = true
            start()
        }

        LogCollector.i(TAG, "Recording started at ${SAMPLE_RATE}Hz, chunk=$CHUNK_SIZE, source=MIC")
    }

    fun stop() {
        isRecording = false
        recordThread?.join(2000)
        recordThread = null
        try { echoCanceler?.release() } catch (_: Exception) {}
        echoCanceler = null
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        LogCollector.i(TAG, "Recording stopped")
    }
}
