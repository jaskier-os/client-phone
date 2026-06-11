package com.repository.listener.audio

import android.content.Context
import com.repository.listener.config.AppConfig
import com.repository.listener.util.LogCollector

class WakeWordDetector(private val context: Context) {

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val VAD_HANGOVER_MS = 2000L  // Keep classifying 2s after VAD drops (OWW has ~1-2s pipeline latency)
    }

    interface WakeWordListener {
        fun onWakeWordDetected()
    }

    enum class Mode { WAKE, CANCEL, TTS_INTERRUPT }

    private var listener: WakeWordListener? = null
    private var mode: Mode = Mode.WAKE

    var owwEngine: OpenWakeWordEngine? = null
        private set
    private var speakerVerifier: SpeakerVerifier? = null
    private var vadEngine: VadEngine? = null

    var source: String = "phone"
    var vadWakeThreshold: Float = AppConfig.VAD_WAKE_THRESHOLD

    // Rolling buffer for speaker verification (2s)
    private val rollingBuffer = ShortArray(32000)
    private var rollingWritePos = 0
    private var rollingFilled = false

    private var lastDetectionTime = 0L

    // Sliding window for OWW scoring
    private val scoreHistory = mutableListOf<Boolean>()

    // VAD-gated classification
    private var vadSpeechActive = false
    private var vadLastSpeechTime = 0L

    fun setListener(listener: WakeWordListener) {
        this.listener = listener
    }

    fun initialize() {
        owwEngine = OpenWakeWordEngine(context)
        owwEngine!!.initialize()
        LogCollector.i(TAG, "WakeWordDetector initialized (openWakeWord, VAD-gated)")
    }

    fun setMode(newMode: Mode) {
        if (this.mode == newMode) return
        this.mode = newMode
        if (newMode == Mode.WAKE) {
            scoreHistory.clear()
            vadSpeechActive = false
        }
        LogCollector.i(TAG, "Mode switched to $newMode")
    }

    fun setSpeakerVerifier(verifier: SpeakerVerifier) {
        this.speakerVerifier = verifier
    }

    fun setVadEngine(vad: VadEngine) {
        this.vadEngine = vad
    }

    fun feedAudio(samples: ShortArray) {
        if (mode != Mode.WAKE) return

        // Always update rolling buffer (needed for speaker verification on detection)
        for (s in samples) {
            rollingBuffer[rollingWritePos] = s
            rollingWritePos++
            if (rollingWritePos >= rollingBuffer.size) {
                rollingWritePos = 0
                rollingFilled = true
            }
        }

        val rms = calculateRms(samples)
        PipelineTracer.onChunkReceived(rms, source)

        // RMS gate -- skip truly silent chunks
        if (AppConfig.ENABLE_RMS_GATE && rms < AppConfig.RMS_THRESHOLD) {
            PipelineTracer.onRmsGated(rms, source)
            return
        }

        // Full mel->embedding->classify pipeline on every chunk (matches Python predict())
        val engine = owwEngine ?: return
        val score = engine.processChunk(samples)

        // Run VAD to gate scoring logic (prevent false positives from noise)
        val floatSamples = FloatArray(samples.size) { samples[it] / 32768f }
        val vadProb = vadEngine?.processChunk(floatSamples) ?: 1.0f
        val now = System.currentTimeMillis()

        if (vadProb > vadWakeThreshold) {
            vadLastSpeechTime = now
            if (!vadSpeechActive) {
                vadSpeechActive = true
                LogCollector.d(TAG, "[$source] VAD speech start prob=${"%.3f".format(vadProb)} rms=${"%.4f".format(rms)}")
            }
            // Score only during speech
            processScore(score, engine)
        } else {
            if (vadSpeechActive && (now - vadLastSpeechTime) > VAD_HANGOVER_MS) {
                vadSpeechActive = false
                scoreHistory.clear()
                LogCollector.d(TAG, "[$source] VAD speech end")
            } else if (vadSpeechActive) {
                // Hangover -- still process scores
                processScore(score, engine)
            }
        }
    }

    private fun processScore(score: Float, engine: OpenWakeWordEngine) {
        if (score > 0.01f) {
            LogCollector.d(TAG, "[$source] OWW score=${"%.4f".format(score)}")
        }

        if (score > 0.1f) {
            scoreHistory.add(score >= AppConfig.OWW_THRESHOLD)
            while (scoreHistory.size > AppConfig.OWW_WINDOW_SIZE) {
                scoreHistory.removeAt(0)
            }

            val hits = scoreHistory.count { it }
            if (hits >= AppConfig.OWW_REQUIRED_HITS) {
                val timeSinceLast = System.currentTimeMillis() - lastDetectionTime
                if (timeSinceLast < AppConfig.WAKE_COOLDOWN_MS) return

                LogCollector.i(TAG, "[$source] OWW DETECTION: score=${"%.4f".format(score)} hits=$hits/${AppConfig.OWW_WINDOW_SIZE}")

                if (AppConfig.SPEAKER_VERIFICATION_ENABLED) {
                    val verifier = speakerVerifier
                    if (verifier != null) {
                        val audio = extractRollingBuffer()
                        val (verified, similarity) = verifier.verify(audio)
                        if (!verified) {
                            LogCollector.i(TAG, "[$source] SPEAKER REJECTED: ${"%.4f".format(similarity)}")
                            PipelineTracer.onSpeakerRejected(similarity, AppConfig.SPEAKER_SIMILARITY_THRESHOLD, source)
                            scoreHistory.clear()
                            return
                        }
                        LogCollector.i(TAG, "[$source] SPEAKER ACCEPTED: ${"%.4f".format(similarity)}")
                        PipelineTracer.onSpeakerAccepted(similarity, AppConfig.SPEAKER_SIMILARITY_THRESHOLD, source)
                    }
                }

                LogCollector.i(TAG, "[$source] WAKE WORD FIRED!")
                PipelineTracer.onDetectionFired("sireneviy", "oww", mode.name, source)
                lastDetectionTime = System.currentTimeMillis()
                scoreHistory.clear()
                // Don't clear embeddings -- they take 16 frames (~1.3s) to rebuild,
                // causing missed detections. Cooldown timer prevents re-firing.
                listener?.onWakeWordDetected()
            }
        }
    }

    fun dumpRollingBuffer(file: java.io.File) {
        val totalSamples = if (rollingFilled) rollingBuffer.size else rollingWritePos
        val ordered = ShortArray(totalSamples)
        if (rollingFilled) {
            System.arraycopy(rollingBuffer, rollingWritePos, ordered, 0, rollingBuffer.size - rollingWritePos)
            System.arraycopy(rollingBuffer, 0, ordered, rollingBuffer.size - rollingWritePos, rollingWritePos)
        } else {
            System.arraycopy(rollingBuffer, 0, ordered, 0, rollingWritePos)
        }
        val byteBuffer = java.nio.ByteBuffer.allocate(44 + totalSamples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val dataSize = totalSamples * 2
        byteBuffer.put("RIFF".toByteArray()); byteBuffer.putInt(36 + dataSize)
        byteBuffer.put("WAVE".toByteArray()); byteBuffer.put("fmt ".toByteArray())
        byteBuffer.putInt(16); byteBuffer.putShort(1); byteBuffer.putShort(1)
        byteBuffer.putInt(16000); byteBuffer.putInt(32000); byteBuffer.putShort(2); byteBuffer.putShort(16)
        byteBuffer.put("data".toByteArray()); byteBuffer.putInt(dataSize)
        for (s in ordered) byteBuffer.putShort(s)
        file.writeBytes(byteBuffer.array())
        LogCollector.i(TAG, "Dumped ${totalSamples} samples to ${file.absolutePath}")
    }

    fun reset() {
        // Don't clear OWW embeddings -- they take 16 frames (~2s) to rebuild.
        // Clearing them causes missed detections since speech is ~1s long.
        // Score history and VAD state are reset to prevent stale detections.
        scoreHistory.clear()
        vadSpeechActive = false
        LogCollector.i(TAG, "Detector reset (scores cleared, embeddings preserved)")
    }

    fun release() {
        owwEngine?.release()
        owwEngine = null
        LogCollector.i(TAG, "Detector released")
    }

    private fun extractRollingBuffer(): FloatArray {
        val totalSamples = if (rollingFilled) rollingBuffer.size else rollingWritePos
        val audio = FloatArray(totalSamples)
        if (rollingFilled) {
            var dst = 0
            for (i in rollingWritePos until rollingBuffer.size) {
                audio[dst++] = rollingBuffer[i] / 32768f
            }
            for (i in 0 until rollingWritePos) {
                audio[dst++] = rollingBuffer[i] / 32768f
            }
        } else {
            for (i in 0 until rollingWritePos) {
                audio[i] = rollingBuffer[i] / 32768f
            }
        }
        return audio
    }

    private fun calculateRms(audioData: ShortArray): Float {
        var sum = 0.0
        for (sample in audioData) {
            val normalized = sample.toFloat() / 32768f
            sum += normalized * normalized
        }
        return kotlin.math.sqrt(sum / audioData.size).toFloat()
    }
}
