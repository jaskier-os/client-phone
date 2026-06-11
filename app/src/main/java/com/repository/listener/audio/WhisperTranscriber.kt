package com.repository.listener.audio

import android.content.Context
import com.repository.listener.util.LogCollector
import com.whispercpp.java.whisper.WhisperLib
import java.io.File

class WhisperTranscriber(private val context: Context) {
    companion object {
        private const val TAG = "WhisperTranscriber"
        private const val SAMPLE_RATE = 16000
        private const val NUM_THREADS = 4
    }

    private var whisperContext: Long = 0L
    private val audioBuffer = mutableListOf<ShortArray>()
    private val modelFile: File
        get() = File(context.filesDir, "whisper-model/ggml-base.bin")

    fun isModelAvailable(): Boolean {
        return modelFile.exists() && modelFile.length() > 0
    }

    fun initialize(): Boolean {
        return try {
            if (!WhisperLib.isLoaded()) {
                LogCollector.e(TAG, "libwhisper.so not loaded")
                return false
            }
            if (!isModelAvailable()) {
                LogCollector.e(TAG, "Model not available at ${modelFile.absolutePath}")
                return false
            }
            whisperContext = WhisperLib.initContext(modelFile.absolutePath)
            if (whisperContext == 0L) {
                LogCollector.e(TAG, "Failed to init whisper context")
                return false
            }
            LogCollector.i(TAG, "Whisper model loaded (tiny, multilingual)")
            true
        } catch (e: Throwable) {
            LogCollector.e(TAG, "Failed to initialize Whisper: ${e.message}")
            false
        }
    }

    fun startUtterance() {
        synchronized(audioBuffer) {
            audioBuffer.clear()
        }
    }

    fun feedAudio(samples: ShortArray): String? {
        synchronized(audioBuffer) {
            audioBuffer.add(samples.copyOf())
        }
        // Whisper is batch-only, no partial results during accumulation
        return null
    }

    fun endUtterance(): String {
        if (whisperContext == 0L) return ""

        // Collect all accumulated audio
        val allSamples: ShortArray
        synchronized(audioBuffer) {
            val totalSize = audioBuffer.sumOf { it.size }
            if (totalSize == 0) return ""
            allSamples = ShortArray(totalSize)
            var offset = 0
            for (chunk in audioBuffer) {
                System.arraycopy(chunk, 0, allSamples, offset, chunk.size)
                offset += chunk.size
            }
            audioBuffer.clear()
        }

        // Convert to float [-1.0, 1.0]
        val floatData = FloatArray(allSamples.size) { allSamples[it].toFloat() / 32768f }

        return try {
            // Run whisper transcription (blocking)
            LogCollector.i(TAG, "Transcribing ${allSamples.size} samples (${allSamples.size / SAMPLE_RATE}s)...")
            WhisperLib.fullTranscribe(whisperContext, NUM_THREADS, floatData)
            val segmentCount = WhisperLib.getTextSegmentCount(whisperContext)
            LogCollector.i(TAG, "Whisper returned $segmentCount segments")
            val sb = StringBuilder()
            for (i in 0 until segmentCount) {
                val text = WhisperLib.getTextSegment(whisperContext, i)
                LogCollector.i(TAG, "  Segment $i: '${text ?: "(null)"}'")
                if (!text.isNullOrBlank()) {
                    sb.append(text.trim())
                    if (i < segmentCount - 1) sb.append(" ")
                }
            }
            val result = sb.toString().trim()
            if (result.isNotEmpty()) {
                LogCollector.i(TAG, "Transcribed ${allSamples.size} samples: '$result'")
            }
            result
        } catch (e: Throwable) {
            LogCollector.e(TAG, "Transcription failed: ${e.message}")
            ""
        }
    }

    fun release() {
        if (whisperContext != 0L) {
            try {
                WhisperLib.freeContext(whisperContext)
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to free context: ${e.message}")
            }
            whisperContext = 0L
        }
        synchronized(audioBuffer) {
            audioBuffer.clear()
        }
        LogCollector.i(TAG, "Whisper released")
    }
}
