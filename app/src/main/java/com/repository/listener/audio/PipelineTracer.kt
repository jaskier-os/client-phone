package com.repository.listener.audio

import com.repository.listener.config.AppConfig
import com.repository.listener.util.LogCollector
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe singleton that tracks voice pipeline attempts and session statistics.
 *
 * When VAD first passes (speech detected), a new "attempt" starts with a unique ID.
 * Each subsequent gate logs its accept/reject decision tagged with that attempt ID,
 * making it easy to trace why a specific utterance failed to trigger.
 *
 * All counters use AtomicLong for lock-free thread safety on the audio thread.
 *
 * Tracks phone and glasses pipelines separately via source parameter.
 */
object PipelineTracer {
    private const val TAG = "PipelineTracer"

    // Per-source counters
    class SourceCounters {
        val totalChunks = AtomicLong(0)
        val rmsGatedChunks = AtomicLong(0)
        val vadPassedChunks = AtomicLong(0)
        val vadHangoverChunks = AtomicLong(0)
        val vadGatedChunks = AtomicLong(0)
        val vadBypassedChunks = AtomicLong(0)
        val owwFedChunks = AtomicLong(0)
        val owwResults = AtomicLong(0)
        val boundaryRejections = AtomicLong(0)
        val cooldownRejections = AtomicLong(0)
        val speakerRejections = AtomicLong(0)
        val speakerAcceptances = AtomicLong(0)
        val successfulDetections = AtomicLong(0)
        val orchestratorGated = AtomicLong(0)

        fun reset() {
            totalChunks.set(0)
            rmsGatedChunks.set(0)
            vadPassedChunks.set(0)
            vadHangoverChunks.set(0)
            vadGatedChunks.set(0)
            vadBypassedChunks.set(0)
            owwFedChunks.set(0)
            owwResults.set(0)
            boundaryRejections.set(0)
            cooldownRejections.set(0)
            speakerRejections.set(0)
            speakerAcceptances.set(0)
            successfulDetections.set(0)
            orchestratorGated.set(0)
        }
    }

    val phone = SourceCounters()
    val glasses = SourceCounters()

    private fun counters(source: String) = if (source == "glasses") glasses else phone

    // Attempt tracking (single audio thread only -- see class doc)
    private val attemptCounter = AtomicInteger(0)
    @Volatile var currentAttemptId = 0
        private set
    private var attemptActive = false
    private var attemptStartTime = 0L

    // Continuous RMS monitoring (rolling window ~2s at 512 samples / 16kHz)
    private val rmsHistory = FloatArray(60)
    private val rmsWritePos = AtomicInteger(0)

    fun onChunkReceived(rms: Float, source: String = "phone") {
        val c = counters(source)
        c.totalChunks.incrementAndGet()

        if (source == "phone") {
            val pos = rmsWritePos.getAndIncrement()
            rmsHistory[pos % rmsHistory.size] = rms

            if (AppConfig.debugPipeline && (pos + 1) % 60 == 0) {
                val count = minOf(pos + 1, rmsHistory.size)
                var sum = 0f
                var max = 0f
                for (i in 0 until count) {
                    sum += rmsHistory[i]
                    if (rmsHistory[i] > max) max = rmsHistory[i]
                }
                val avg = sum / count
                LogCollector.d(TAG, "MIC LEVEL: avg=%.5f max=%.5f (threshold=%.4f)"
                    .format(avg, max, AppConfig.RMS_THRESHOLD))
            }
        }
    }

    fun onRmsGated(rms: Float, source: String = "phone") {
        counters(source).rmsGatedChunks.incrementAndGet()
    }

    fun onVadPassed(vadProb: Float, rms: Float, source: String = "phone") {
        counters(source).vadPassedChunks.incrementAndGet()

        if (!attemptActive) {
            currentAttemptId = attemptCounter.incrementAndGet()
            attemptActive = true
            attemptStartTime = System.currentTimeMillis()
            if (AppConfig.debugPipeline) {
                LogCollector.i(TAG, "[A$currentAttemptId] ATTEMPT START ($source): " +
                    "rms=%.4f vadProb=%.3f".format(rms, vadProb))
            }
        }
    }

    fun onVadHangover(vadProb: Float, remainingMs: Long, source: String = "phone") {
        counters(source).vadHangoverChunks.incrementAndGet()
        if (AppConfig.debugPipeline) {
            LogCollector.d(TAG, "[A$currentAttemptId] VAD HANGOVER ($source): " +
                "vadProb=%.3f remainingMs=${remainingMs}".format(vadProb))
        }
    }

    fun onVadBypassed(rms: Float, source: String = "phone") {
        counters(source).vadBypassedChunks.incrementAndGet()
    }

    fun onVadGated(vadProb: Float, source: String = "phone") {
        counters(source).vadGatedChunks.incrementAndGet()

        if (attemptActive) {
            val elapsed = System.currentTimeMillis() - attemptStartTime
            if (AppConfig.debugPipeline) {
                LogCollector.i(TAG, "[A$currentAttemptId] ATTEMPT END ($source, VAD dropped): " +
                    "vadProb=%.3f after ${elapsed}ms".format(vadProb))
            }
            attemptActive = false
        }
    }

    fun onOwwFed(source: String = "phone") {
        counters(source).owwFedChunks.incrementAndGet()
    }

    fun onOwwResult(lang: String, text: String, isFinal: Boolean, source: String = "phone") {
        if (!isFinal) return
        counters(source).owwResults.incrementAndGet()
        LogCollector.i(TAG, "[A$currentAttemptId] OWW RESULT ($source) [$lang]: '$text'")
    }

    fun onBoundaryRejected(keyword: String, text: String, lang: String, source: String = "phone") {
        counters(source).boundaryRejections.incrementAndGet()
        LogCollector.i(TAG, "[A$currentAttemptId] REJECTED by WORD BOUNDARY ($source) [$lang]: " +
            "'$keyword' not matched in '$text'")
    }

    fun onCooldownRejected(keyword: String, lang: String, timeSinceLast: Long, cooldown: Long, source: String = "phone") {
        counters(source).cooldownRejections.incrementAndGet()
        LogCollector.i(TAG, "[A$currentAttemptId] REJECTED by COOLDOWN ($source) [$lang]: " +
            "'$keyword' ${timeSinceLast}ms < ${cooldown}ms")
    }

    fun onSpeakerRejected(similarity: Float, threshold: Float, source: String = "phone") {
        counters(source).speakerRejections.incrementAndGet()
        LogCollector.w(TAG, "[A$currentAttemptId] REJECTED by SPEAKER VERIFY ($source): " +
            "similarity=%.4f < threshold=%.2f".format(similarity, threshold))
        attemptActive = false
    }

    fun onSpeakerAccepted(similarity: Float, threshold: Float, source: String = "phone") {
        counters(source).speakerAcceptances.incrementAndGet()
        LogCollector.w(TAG, "[A$currentAttemptId] PASSED SPEAKER VERIFY ($source): " +
            "similarity=%.4f >= threshold=%.2f".format(similarity, threshold))
    }

    fun onDetectionFired(keyword: String, lang: String, mode: String, source: String = "phone") {
        counters(source).successfulDetections.incrementAndGet()
        LogCollector.i(TAG, "[A$currentAttemptId] DETECTION FIRED ($source): '$keyword' [$lang] mode=$mode")
        attemptActive = false
    }

    fun onOrchestratorGated(source: String = "phone") {
        counters(source).orchestratorGated.incrementAndGet()
        if (AppConfig.debugPipeline) {
            LogCollector.i(TAG, "[A$currentAttemptId] REJECTED by ORCHESTRATOR GATE ($source): not connected")
        }
    }

    private fun formatSourceDiag(label: String, c: SourceCounters): String = buildString {
        appendLine("-- $label: Audio Input --")
        appendLine("Total chunks: ${c.totalChunks.get()}")
        appendLine("RMS gated: ${c.rmsGatedChunks.get()}")
        appendLine("VAD passed: ${c.vadPassedChunks.get()}")
        appendLine("VAD hangover: ${c.vadHangoverChunks.get()}")
        appendLine("VAD gated: ${c.vadGatedChunks.get()}")
        appendLine("VAD bypassed: ${c.vadBypassedChunks.get()}")
        appendLine("Fed to OWW: ${c.owwFedChunks.get()}")
        appendLine("OWW results: ${c.owwResults.get()}")
        appendLine("Boundary rejected: ${c.boundaryRejections.get()}")
        appendLine("Cooldown rejected: ${c.cooldownRejections.get()}")
        appendLine("Speaker rejected: ${c.speakerRejections.get()}")
        appendLine("Speaker accepted: ${c.speakerAcceptances.get()}")
        appendLine("Orchestrator gated: ${c.orchestratorGated.get()}")
        appendLine("Detections: ${c.successfulDetections.get()}")
    }

    fun getDiagnosticSummary(): String {
        val pos = rmsWritePos.get()
        val count = minOf(pos, rmsHistory.size)
        val avgRms = if (count > 0) {
            var sum = 0f
            for (i in 0 until count) sum += rmsHistory[i]
            sum / count
        } else 0f

        return buildString {
            appendLine("=== Pipeline Diagnostic Summary ===")
            appendLine("Debug mode: ${AppConfig.debugPipeline}")
            appendLine("Phone avg RMS: %.5f (threshold: %.4f)".format(avgRms, AppConfig.RMS_THRESHOLD))
            appendLine("Total attempts: ${attemptCounter.get()}")
            appendLine("Attempt active: $attemptActive")
            appendLine("")
            append(formatSourceDiag("Phone", phone))
            appendLine("")
            append(formatSourceDiag("Glasses", glasses))
        }
    }

    fun reset() {
        attemptCounter.set(0)
        currentAttemptId = 0
        attemptActive = false
        attemptStartTime = 0L
        rmsWritePos.set(0)
        phone.reset()
        glasses.reset()
    }
}
