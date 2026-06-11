package com.repository.listener.ui.views

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.repository.listener.config.AppConfig
import com.repository.listener.network.TranscriberStreamClient
import com.repository.listener.util.LogCollector
import android.content.Context

class ChatVoiceRecorder(private val context: Context) {

    companion object {
        private const val TAG = "ChatVoiceRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    interface RecordingListener {
        fun onPartialTranscript(text: String)
        fun onFinalTranscript(text: String)
        fun onError(message: String)
        fun onAmplitude(amplitude: Float)
    }

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var transcriberClient: TranscriberStreamClient? = null
    private var captureThread: Thread? = null
    @Volatile private var recording = false

    fun isRecording(): Boolean = recording

    fun startRecording(listener: RecordingListener) {
        if (recording) {
            LogCollector.w(TAG, "Already recording, ignoring start")
            return
        }

        val url = AppConfig.getOrchestratorHttpUrl(context)
        val apiKey = AppConfig.getApiKey(context)
        val provider = AppConfig.getSttProvider(context)
        val language = AppConfig.getSttLanguage(context)

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            listener.onError("Invalid audio buffer size")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )
        } catch (e: SecurityException) {
            listener.onError("Microphone permission denied")
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            LogCollector.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            listener.onError("Failed to initialize microphone")
            return
        }

        transcriberClient = TranscriberStreamClient(url, apiKey).also { client ->
            client.start(object : TranscriberStreamClient.Listener {
                override fun onPartial(text: String) {
                    listener.onPartialTranscript(text)
                }
                override fun onFinal(text: String) {
                    listener.onFinalTranscript(text)
                }
                override fun onError(message: String) {
                    LogCollector.e(TAG, "Transcriber error: $message")
                    listener.onError(message)
                }
            }, sourceLang = language, provider = provider)
        }

        recording = true
        audioRecord?.startRecording()

        captureThread = Thread({
            LogCollector.i(TAG, "Audio capture thread started")
            val buffer = ShortArray(bufferSize / 2)
            while (recording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    val samples = if (read == buffer.size) buffer else buffer.copyOf(read)
                    transcriberClient?.feedAudio(samples)
                    // Calculate RMS amplitude for waveform visualization
                    var sum = 0L
                    for (s in samples) sum += s.toLong() * s.toLong()
                    val rms = Math.sqrt(sum.toDouble() / samples.size)
                    val normalized = (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
                    listener.onAmplitude(normalized)
                }
            }
            LogCollector.i(TAG, "Audio capture thread ended")
        }, "ChatVoiceCapture").also { it.start() }
    }

    fun stopRecording(): String? {
        if (!recording) return null
        recording = false

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        captureThread?.join(1000)
        captureThread = null

        val finalText = transcriberClient?.stopAndWaitForFinal(3000)
        transcriberClient = null

        LogCollector.i(TAG, "Recording stopped, final text: '$finalText'")
        return finalText
    }

    fun cancelRecording() {
        if (!recording) return
        recording = false

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        captureThread?.join(1000)
        captureThread = null

        transcriberClient?.release()
        transcriberClient = null

        LogCollector.i(TAG, "Recording cancelled")
    }
}
