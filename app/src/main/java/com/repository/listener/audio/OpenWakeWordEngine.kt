package com.repository.listener.audio

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.repository.listener.util.LogCollector
import java.nio.FloatBuffer

class OpenWakeWordEngine(private val context: Context) {

    companion object {
        private const val TAG = "OpenWakeWordEngine"
        private const val MEL_MODEL = "melspectrogram.onnx"
        private const val EMB_MODEL = "embedding_model.onnx"
        private const val CLS_MODEL = "sireneviy.onnx"

        private const val CHUNK_SIZE = 1280      // 80ms at 16kHz
        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76
        private const val EMBEDDING_DIM = 96
        private const val N_FRAMES = 16
        private const val MAX_MEL_BUFFER = 200
    }

    private var env: OrtEnvironment? = null
    private var melSession: OrtSession? = null
    private var embSession: OrtSession? = null
    private var clsSession: OrtSession? = null

    private val audioAccumulator = ShortArray(CHUNK_SIZE)
    private var audioAccumPos = 0
    private var melBuffer = ArrayList<FloatArray>(MAX_MEL_BUFFER)
    private var embBuffer = ArrayList<FloatArray>(N_FRAMES)
    private var lastScore = 0f
    private var melVersion = 0L  // incremented when mel buffer actually grows
    private var lastEmbMelVersion = -1L  // mel version when last embedding was extracted

    fun initialize() {
        try {
            env = OrtEnvironment.getEnvironment()
            val ortEnv = env!!
            LogCollector.i(TAG, "Loading openWakeWord models from assets...")
            melSession = ortEnv.createSession(context.assets.open(MEL_MODEL).readBytes())
            embSession = ortEnv.createSession(context.assets.open(EMB_MODEL).readBytes())
            clsSession = ortEnv.createSession(context.assets.open(CLS_MODEL).readBytes())
            LogCollector.i(TAG, "openWakeWord models loaded (mel + embedding + classifier)")
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to load openWakeWord models: ${e.message}")
        }
    }

    /**
     * Feed audio to mel pipeline only. Call for ALL audio to keep mel buffer warm.
     * Does NOT extract embeddings or run classifier.
     */
    @Synchronized
    fun feedMel(samples: ShortArray) {
        val ortEnv = env ?: return
        val mel = melSession ?: return

        var srcPos = 0
        while (srcPos < samples.size) {
            val toCopy = minOf(samples.size - srcPos, CHUNK_SIZE - audioAccumPos)
            System.arraycopy(samples, srcPos, audioAccumulator, audioAccumPos, toCopy)
            audioAccumPos += toCopy
            srcPos += toCopy

            if (audioAccumPos < CHUNK_SIZE) continue
            audioAccumPos = 0

            val floatSamples = FloatArray(CHUNK_SIZE) { audioAccumulator[it].toFloat() / 32768f }
            val melFrames = runMelspectrogram(ortEnv, mel, floatSamples) ?: continue
            for (frame in melFrames) { melBuffer.add(frame) }
            while (melBuffer.size > MAX_MEL_BUFFER) { melBuffer.removeAt(0) }
            melVersion++
        }
    }

    /**
     * Extract embedding from current mel buffer and run classifier.
     * Call only during speech (VAD-passed). Embeddings buffer should
     * contain only speech features, not silence/noise.
     */
    /**
     * Extract embedding from latest mel window. Call on every chunk (like Python predict()).
     * Keeps embBuffer continuously up-to-date with latest audio embeddings.
     */
    @Synchronized
    fun updateEmbeddings() {
        val ortEnv = env ?: return
        val emb = embSession ?: return

        if (melBuffer.size < MEL_WINDOW) return
        if (melVersion == lastEmbMelVersion) return
        lastEmbMelVersion = melVersion

        val embedding = runEmbedding(ortEnv, emb) ?: return
        embBuffer.add(embedding)
        if (embBuffer.size > N_FRAMES) { embBuffer.removeAt(0) }
    }

    /**
     * Run classifier on current embedding buffer. Call only during speech (VAD-gated).
     */
    @Synchronized
    fun classify(): Float {
        val ortEnv = env ?: return lastScore
        val cls = clsSession ?: return lastScore

        if (embBuffer.size < N_FRAMES) return lastScore

        lastScore = runClassifier(ortEnv, cls)
        return lastScore
    }

    /**
     * Combined feed + classify for test_oww (processes complete WAV file).
     */
    @Synchronized
    fun processChunk(samples: ShortArray): Float {
        val ortEnv = env ?: return 0f
        val mel = melSession ?: return 0f
        val emb = embSession ?: return 0f
        val cls = clsSession ?: return 0f

        var srcPos = 0
        while (srcPos < samples.size) {
            val toCopy = minOf(samples.size - srcPos, CHUNK_SIZE - audioAccumPos)
            System.arraycopy(samples, srcPos, audioAccumulator, audioAccumPos, toCopy)
            audioAccumPos += toCopy
            srcPos += toCopy

            if (audioAccumPos < CHUNK_SIZE) continue
            audioAccumPos = 0

            val floatSamples = FloatArray(CHUNK_SIZE) { audioAccumulator[it].toFloat() / 32768f }
            val melFrames = runMelspectrogram(ortEnv, mel, floatSamples) ?: continue
            for (frame in melFrames) { melBuffer.add(frame) }
            while (melBuffer.size > MAX_MEL_BUFFER) { melBuffer.removeAt(0) }
            if (melBuffer.size < MEL_WINDOW) continue

            val embedding = runEmbedding(ortEnv, emb) ?: continue
            embBuffer.add(embedding)
            if (embBuffer.size > N_FRAMES) { embBuffer.removeAt(0) }
            if (embBuffer.size < N_FRAMES) {
                LogCollector.d(TAG, "processChunk: emb=${embBuffer.size}/$N_FRAMES mel=${melBuffer.size}")
                continue
            }

            lastScore = runClassifier(ortEnv, cls)
            if (lastScore > 0.01f) {
                LogCollector.d(TAG, "processChunk: score=${"%.4f".format(lastScore)} emb=${embBuffer.size} mel=${melBuffer.size}")
            }
        }
        return lastScore
    }

    /**
     * Test a WAV file. Resets buffers, pads with silence, processes, restores.
     */
    @Synchronized
    fun testFile(samples: ShortArray, threshold: Float = 0.8f, requiredHits: Int = 1): Map<String, Any> {
        val savedAccumPos = audioAccumPos
        val savedAccum = audioAccumulator.copyOf()
        val savedMel = ArrayList(melBuffer)
        val savedEmb = ArrayList(embBuffer)

        audioAccumPos = 0
        melBuffer.clear()
        embBuffer.clear()

        val silenceBefore = ShortArray(CHUNK_SIZE * 25)
        val silenceAfter = ShortArray(CHUNK_SIZE * 8)
        val fullAudio = ShortArray(silenceBefore.size + samples.size + silenceAfter.size)
        System.arraycopy(silenceBefore, 0, fullAudio, 0, silenceBefore.size)
        System.arraycopy(samples, 0, fullAudio, silenceBefore.size, samples.size)
        System.arraycopy(silenceAfter, 0, fullAudio, silenceBefore.size + samples.size, silenceAfter.size)

        val wavStart = silenceBefore.size
        val wavEnd = silenceBefore.size + samples.size
        val scores = mutableListOf<Float>()
        var maxScore = 0f

        for (i in fullAudio.indices step CHUNK_SIZE) {
            val end = minOf(i + CHUNK_SIZE, fullAudio.size)
            val score = processChunk(fullAudio.copyOfRange(i, end))
            if (end > wavStart && i < wavEnd) {
                scores.add(score)
                if (score > maxScore) maxScore = score
            }
        }

        audioAccumPos = savedAccumPos
        System.arraycopy(savedAccum, 0, audioAccumulator, 0, savedAccum.size)
        melBuffer.clear(); melBuffer.addAll(savedMel)
        embBuffer.clear(); embBuffer.addAll(savedEmb)

        val aboveThreshold = scores.count { it > threshold }
        return mapOf(
            "max_score" to maxScore,
            "frames_above_threshold" to aboveThreshold,
            "total_frames" to scores.size,
            "high_scores" to scores.filter { it > 0.1f },
            "detected" to (aboveThreshold >= requiredHits),
        )
    }

    @Synchronized
    fun clearScores() {
        lastScore = 0f
    }

    fun embBufferSize(): Int = embBuffer.size
    fun melBufferSize(): Int = melBuffer.size

    @Synchronized
    fun clearEmbeddings() {
        embBuffer.clear()
        lastScore = 0f
        lastEmbMelVersion = -1L
    }

    @Synchronized
    fun reset() {
        audioAccumPos = 0
        melBuffer.clear()
        embBuffer.clear()
        lastScore = 0f
        melVersion = 0L
        lastEmbMelVersion = -1L
    }

    fun release() {
        clsSession?.close()
        embSession?.close()
        melSession?.close()
        env?.close()
        clsSession = null
        embSession = null
        melSession = null
        env = null
        LogCollector.i(TAG, "openWakeWord engine released")
    }

    private fun runMelspectrogram(ortEnv: OrtEnvironment, session: OrtSession, audio: FloatArray): List<FloatArray>? {
        return try {
            val inputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong()))
            val results = session.run(mapOf("input" to inputTensor))
            val output = results.get("output").get() as OnnxTensor
            val shape = output.info.shape
            val nFrames = shape[2].toInt()
            val rawData = output.floatBuffer
            val frames = ArrayList<FloatArray>(nFrames)
            for (i in 0 until nFrames) {
                val frame = FloatArray(MEL_BINS)
                for (j in 0 until MEL_BINS) { frame[j] = rawData.get(i * MEL_BINS + j) / 10f + 2f }
                frames.add(frame)
            }
            results.close()
            inputTensor.close()
            frames
        } catch (e: Exception) {
            LogCollector.e(TAG, "Mel inference error: ${e.message}")
            null
        }
    }

    private fun runEmbedding(ortEnv: OrtEnvironment, session: OrtSession): FloatArray? {
        return try {
            val startIdx = melBuffer.size - MEL_WINDOW
            val inputData = FloatArray(MEL_WINDOW * MEL_BINS)
            for (i in 0 until MEL_WINDOW) {
                System.arraycopy(melBuffer[startIdx + i], 0, inputData, i * MEL_BINS, MEL_BINS)
            }
            val inputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(inputData), longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1))
            val results = session.run(mapOf("input_1" to inputTensor))
            val output = results.get("conv2d_19").get() as OnnxTensor
            val embedding = FloatArray(EMBEDDING_DIM)
            output.floatBuffer.get(embedding)
            results.close()
            inputTensor.close()
            embedding
        } catch (e: Exception) {
            LogCollector.e(TAG, "Embedding inference error: ${e.message}")
            null
        }
    }

    private fun runClassifier(ortEnv: OrtEnvironment, session: OrtSession): Float {
        return try {
            val inputData = FloatArray(N_FRAMES * EMBEDDING_DIM)
            for (i in 0 until N_FRAMES) {
                System.arraycopy(embBuffer[i], 0, inputData, i * EMBEDDING_DIM, EMBEDDING_DIM)
            }
            val inputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(inputData), longArrayOf(1, N_FRAMES.toLong(), EMBEDDING_DIM.toLong()))
            val results = session.run(mapOf("input" to inputTensor))
            val output = results.get("output").get() as OnnxTensor
            val score = output.floatBuffer.get(0)
            results.close()
            inputTensor.close()
            score
        } catch (e: Exception) {
            LogCollector.e(TAG, "Classifier inference error: ${e.message}")
            0f
        }
    }
}
