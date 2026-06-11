package com.repository.listener.audio

import android.content.Context
import com.repository.listener.util.LogCollector
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

class VadEngine(private val context: Context) {

    companion object {
        private const val TAG = "VadEngine"
        private const val MODEL_FILE = "silero_vad.onnx"
        private const val STATE_DIM = 128
        private const val INPUT_DIM = 576
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    // Internal state tensors: shape [1, 1, 128]
    private var hData = FloatArray(STATE_DIM)
    private var cData = FloatArray(STATE_DIM)

    // Accumulation buffer for building 576-wide frames
    private val accumulator = mutableListOf<Float>()

    fun initialize() {
        env = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open(MODEL_FILE).readBytes()
        session = env!!.createSession(modelBytes)
        resetState()
        LogCollector.i(TAG, "Silero VAD model loaded (input_dim=$INPUT_DIM, state_dim=$STATE_DIM)")
    }

    @Synchronized
    fun processChunk(samples: FloatArray): Float {
        // Accumulate samples and run inference when we have enough for a frame
        accumulator.addAll(samples.toList())

        var lastProb = 0f
        while (accumulator.size >= INPUT_DIM) {
            val frame = FloatArray(INPUT_DIM)
            for (i in 0 until INPUT_DIM) {
                frame[i] = accumulator[i]
            }
            // Remove consumed samples
            accumulator.subList(0, INPUT_DIM).clear()

            lastProb = runInference(frame)
        }
        return lastProb
    }

    @Synchronized
    private fun runInference(frame: FloatArray): Float {
        val ortEnv = env ?: return 0f
        val ortSession = session ?: return 0f

        // Input tensor: [1, 576]
        val inputTensor = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(frame),
            longArrayOf(1, INPUT_DIM.toLong())
        )

        // State tensors: [1, 1, 128]
        val hTensor = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(hData),
            longArrayOf(1, 1, STATE_DIM.toLong())
        )
        val cTensor = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(cData),
            longArrayOf(1, 1, STATE_DIM.toLong())
        )

        val inputs = mapOf(
            "input" to inputTensor,
            "h" to hTensor,
            "c" to cTensor
        )

        return try {
            val results = ortSession.run(inputs)

            // Output probability
            val outputTensor = results.get("speech_probs").get() as OnnxTensor
            val probability = outputTensor.floatBuffer.get(0)

            // Update h and c state
            val hnTensor = results.get("hn").get() as OnnxTensor
            val cnTensor = results.get("cn").get() as OnnxTensor
            hnTensor.floatBuffer.get(hData)
            cnTensor.floatBuffer.get(cData)

            results.close()
            probability
        } catch (e: Exception) {
            LogCollector.e(TAG, "VAD inference error: ${e.message}")
            0f
        } finally {
            inputTensor.close()
            hTensor.close()
            cTensor.close()
        }
    }

    @Synchronized
    fun reset() {
        resetState()
        accumulator.clear()
    }

    @Synchronized
    fun release() {
        // Close only the per-instance session. OrtEnvironment is a
        // process-wide singleton (OrtEnvironment.getEnvironment()) shared
        // by every ONNX engine in the app -- closing it from here tore
        // down the runtime under OpenWakeWord / SpeakerVerifier threads
        // and Scudo reported the resulting use-after-free as a native
        // heap crash on whichever inference thread happened to be
        // running. Leave env alone.
        session?.close()
        session = null
        env = null
        LogCollector.i(TAG, "VAD engine released")
    }

    private fun resetState() {
        hData = FloatArray(STATE_DIM)
        cData = FloatArray(STATE_DIM)
        accumulator.clear()
    }
}
