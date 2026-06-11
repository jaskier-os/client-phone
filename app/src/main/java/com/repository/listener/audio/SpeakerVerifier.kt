package com.repository.listener.audio

import android.content.Context
import com.repository.listener.util.LogCollector
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.repository.listener.config.AppConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*

/**
 * On-device speaker verification using ECAPA-TDNN (ONNX).
 *
 * The ONNX model is exported from SpeechBrain (spkrec-ecapa-voxceleb) and takes
 * normalized Fbank features [1, T, 80] as input.
 *
 * Pipeline: raw audio -> center-pad -> framing -> Hamming window -> DFT(400) ->
 *           power spectrum -> mel filterbank -> 10*log10 -> top_db clamp ->
 *           mean subtraction -> ONNX encoder -> 192-dim embedding
 *
 * The mel filterbank is pre-extracted from SpeechBrain and loaded from a binary asset
 * to guarantee exact match with the training pipeline.
 */
class SpeakerVerifier(private val context: Context) {

    companion object {
        private const val TAG = "SpeakerVerifier"
        private const val MODEL_FILE = "ecapa_tdnn.onnx"
        private const val PROFILE_FILE = "voice_profile.bin"
        private const val MEL_FILTERBANK_FILE = "mel_filterbank.bin"
        private const val EMBEDDING_DIM = 192

        // Fbank parameters matching SpeechBrain spkrec-ecapa-voxceleb
        private const val N_FFT = 400
        private const val WIN_LENGTH = 400
        private const val HOP_LENGTH = 160
        private const val N_MELS = 80
        private const val SPEC_SIZE = N_FFT / 2 + 1  // 201
        private const val TOP_DB = 80.0f
        private const val AMIN = 1e-10f
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var profileEmbedding: FloatArray? = null
    private var melFilterbank: Array<FloatArray>? = null
    private var hammingWindow: FloatArray? = null
    // Precomputed DFT twiddle factors [SPEC_SIZE][N_FFT] -- eliminates trig calls from inner loop
    private var dftCos: Array<FloatArray>? = null
    private var dftSin: Array<FloatArray>? = null

    fun initialize() {
        env = OrtEnvironment.getEnvironment()

        val modelBytes = context.assets.open(MODEL_FILE).readBytes()
        session = env!!.createSession(modelBytes)
        LogCollector.i(TAG, "ECAPA-TDNN ONNX model loaded")

        profileEmbedding = loadProfile()
        LogCollector.i(TAG, "Voice profile loaded (${profileEmbedding!!.size} dims)")

        melFilterbank = loadMelFilterbank()
        LogCollector.i(TAG, "Mel filterbank loaded ($N_MELS x $SPEC_SIZE)")

        hammingWindow = createHammingWindow()
        precomputeDftTwiddles()
    }

    private fun loadProfile(): FloatArray {
        val bytes = context.assets.open(PROFILE_FILE).readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        buffer.asFloatBuffer().get(floats)
        return floats
    }

    /**
     * Load pre-extracted SpeechBrain mel filterbank from binary asset.
     * Format: int32 n_mels, int32 spec_size, then n_mels * spec_size float32 values (row-major).
     */
    private fun loadMelFilterbank(): Array<FloatArray> {
        val bytes = context.assets.open(MEL_FILTERBANK_FILE).readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val nMels = buffer.int
        val specSize = buffer.int
        val fb = Array(nMels) { FloatArray(specSize) }
        for (m in 0 until nMels) {
            for (k in 0 until specSize) {
                fb[m][k] = buffer.float
            }
        }
        return fb
    }

    private fun createHammingWindow(): FloatArray {
        return FloatArray(WIN_LENGTH) { i ->
            (0.54f - 0.46f * cos(2.0f * PI.toFloat() * i / (WIN_LENGTH - 1)))
        }
    }

    fun verify(audio: FloatArray): Pair<Boolean, Float> {
        val ortEnv = env ?: return Pair(false, 0f)
        val ortSession = session ?: return Pair(false, 0f)
        val profile = profileEmbedding ?: return Pair(false, 0f)

        LogCollector.i(TAG, "verify(): input audio ${audio.size} samples (${audio.size / AudioFeatures.SAMPLE_RATE.toFloat()}s)")
        val features = computeFbank(audio)
        if (features.isEmpty()) {
            LogCollector.e(TAG, "verify(): computeFbank returned empty features")
            return Pair(false, 0f)
        }

        val numFrames = features.size / N_MELS
        LogCollector.i(TAG, "verify(): fbank features computed: ${features.size} values, $numFrames frames x $N_MELS mels")

        val inputTensor = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(features),
            longArrayOf(1, numFrames.toLong(), N_MELS.toLong())
        )

        return try {
            val results = ortSession.run(mapOf("features" to inputTensor))
            val outputTensor = results.get("embedding").get() as OnnxTensor

            val rawEmbedding = FloatArray(EMBEDDING_DIM)
            outputTensor.floatBuffer.get(rawEmbedding)
            results.close()

            // L2-normalize
            var norm = 0f
            for (v in rawEmbedding) norm += v * v
            norm = sqrt(norm)
            LogCollector.i(TAG, "verify(): raw embedding norm=%.4f first5=[%.4f, %.4f, %.4f, %.4f, %.4f]".format(
                norm, rawEmbedding[0], rawEmbedding[1], rawEmbedding[2], rawEmbedding[3], rawEmbedding[4]))
            if (norm > 0f) {
                for (i in rawEmbedding.indices) rawEmbedding[i] /= norm
            }

            // Cosine similarity (profile is already normalized)
            var similarity = 0f
            for (i in 0 until EMBEDDING_DIM) {
                similarity += rawEmbedding[i] * profile[i]
            }

            val verified = similarity >= AppConfig.SPEAKER_SIMILARITY_THRESHOLD
            LogCollector.i(TAG, "verify(): similarity=%.4f threshold=%.2f verified=$verified profile_first5=[%.4f, %.4f, %.4f, %.4f, %.4f]".format(
                similarity, AppConfig.SPEAKER_SIMILARITY_THRESHOLD,
                profile[0], profile[1], profile[2], profile[3], profile[4]))
            Pair(verified, similarity)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Speaker verification inference error: ${e.message}")
            Pair(false, 0f)
        } finally {
            inputTensor.close()
        }
    }

    /**
     * Compute log-mel filterbank features matching SpeechBrain's Fbank exactly.
     *
     * Pipeline: center-pad (reflect) -> framing -> Hamming window -> DFT(400) ->
     *           power spectrum (201 bins) -> mel filterbank (80 bins) ->
     *           10*log10 -> top_db clamp -> per-utterance mean subtraction
     *
     * Returns flat float array of shape [numFrames * N_MELS] (row-major).
     */
    private fun computeFbank(audio: FloatArray): FloatArray {
        if (audio.size < WIN_LENGTH) {
            LogCollector.w(TAG, "computeFbank: audio too short (${audio.size} < $WIN_LENGTH)")
            return FloatArray(0)
        }

        val mel = melFilterbank ?: return FloatArray(0)
        val window = hammingWindow ?: return FloatArray(0)

        // Center-pad with reflection (N_FFT/2 on each side)
        val pad = N_FFT / 2
        val padded = reflectPad(audio, pad)

        val numFrames = 1 + (padded.size - WIN_LENGTH) / HOP_LENGTH
        if (numFrames <= 0) return FloatArray(0)

        val result = FloatArray(numFrames * N_MELS)

        for (frame in 0 until numFrames) {
            val start = frame * HOP_LENGTH

            // Apply Hamming window
            val windowed = FloatArray(WIN_LENGTH)
            for (i in 0 until WIN_LENGTH) {
                windowed[i] = padded[start + i] * window[i]
            }

            // DFT with n=400 -> power spectrum (201 bins)
            val powerSpec = realDftPower(windowed)

            // Apply mel filterbank + 10*log10
            for (m in 0 until N_MELS) {
                var energy = 0f
                for (k in 0 until SPEC_SIZE) {
                    energy += mel[m][k] * powerSpec[k]
                }
                result[frame * N_MELS + m] = 10f * log10(maxOf(energy, AMIN))
            }
        }

        // top_db clamping
        var maxDb = Float.NEGATIVE_INFINITY
        for (v in result) if (v > maxDb) maxDb = v
        val minDb = maxDb - TOP_DB
        for (i in result.indices) {
            if (result[i] < minDb) result[i] = minDb
        }

        // Per-utterance mean subtraction (SpeechBrain InputNormalization with std_norm=False)
        val mean = FloatArray(N_MELS)
        for (frame in 0 until numFrames) {
            for (m in 0 until N_MELS) {
                mean[m] += result[frame * N_MELS + m]
            }
        }
        for (m in 0 until N_MELS) mean[m] /= numFrames
        for (frame in 0 until numFrames) {
            for (m in 0 until N_MELS) {
                result[frame * N_MELS + m] -= mean[m]
            }
        }

        return result
    }

    /**
     * Reflect-pad audio with [pad] samples on each side.
     * Matches numpy's np.pad(audio, (pad, pad), mode='reflect').
     */
    private fun reflectPad(audio: FloatArray, pad: Int): FloatArray {
        val out = FloatArray(audio.size + 2 * pad)
        // Left reflection
        for (i in 0 until pad) {
            out[i] = audio[pad - i]
        }
        // Center (original audio)
        System.arraycopy(audio, 0, out, pad, audio.size)
        // Right reflection
        for (i in 0 until pad) {
            out[pad + audio.size + i] = audio[audio.size - 2 - i]
        }
        return out
    }

    /**
     * Precompute DFT twiddle factors cos(2*pi*k*n/N) and sin(2*pi*k*n/N)
     * for k=0..SPEC_SIZE-1, n=0..N_FFT-1.
     * This eliminates all trig calls from the inner loop, giving ~50x speedup.
     * Memory: 2 * 201 * 400 * 4 bytes = ~627 KB.
     */
    private fun precomputeDftTwiddles() {
        val cosTable = Array(SPEC_SIZE) { FloatArray(N_FFT) }
        val sinTable = Array(SPEC_SIZE) { FloatArray(N_FFT) }
        for (k in 0 until SPEC_SIZE) {
            val angleStep = -2.0 * PI * k / N_FFT
            for (n in 0 until N_FFT) {
                val angle = (angleStep * n).toFloat()
                cosTable[k][n] = cos(angle)
                sinTable[k][n] = sin(angle)
            }
        }
        dftCos = cosTable
        dftSin = sinTable
        LogCollector.i(TAG, "DFT twiddle factors precomputed ($SPEC_SIZE x $N_FFT)")
    }

    /**
     * Compute power spectrum |DFT(x)|^2 for first SPEC_SIZE (201) frequency bins.
     * Uses precomputed twiddle factors -- inner loop is pure multiply-accumulate.
     */
    private fun realDftPower(signal: FloatArray): FloatArray {
        val cosT = dftCos!!
        val sinT = dftSin!!
        val power = FloatArray(SPEC_SIZE)
        for (k in 0 until SPEC_SIZE) {
            var re = 0f
            var im = 0f
            val ck = cosT[k]
            val sk = sinT[k]
            for (i in 0 until N_FFT) {
                val s = signal[i]
                re += s * ck[i]
                im += s * sk[i]
            }
            power[k] = re * re + im * im
        }
        return power
    }

    fun release() {
        session?.close()
        env?.close()
        session = null
        env = null
        profileEmbedding = null
        melFilterbank = null
        hammingWindow = null
        dftCos = null
        dftSin = null
        LogCollector.i(TAG, "Speaker verifier released")
    }
}
