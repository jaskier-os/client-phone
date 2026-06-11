package com.repository.listener.audio

import kotlin.math.*

/**
 * Shared DSP utilities for audio feature extraction.
 * Used by SpeakerVerifier (Fbank) and MfccDtwDetector (MFCC).
 */
object AudioFeatures {

    const val SAMPLE_RATE = 16000
    const val N_FFT = 400
    const val FFT_SIZE = 512
    const val N_MELS = 80
    const val N_MFCC = 13
    const val WIN_LENGTH = 400    // 25ms
    const val HOP_LENGTH = 160    // 10ms
    const val F_MIN = 0.0f
    const val F_MAX = 8000.0f
    const val PRE_EMPHASIS = 0.97f
    const val SPEC_SIZE = FFT_SIZE / 2 + 1  // 257

    /**
     * Create Hann window of given length.
     */
    fun createHannWindow(length: Int = WIN_LENGTH): FloatArray {
        return FloatArray(length) { i ->
            (0.5f * (1f - cos(2.0f * PI.toFloat() * i / length))).toFloat()
        }
    }

    /**
     * Create triangular mel filterbank matrix [N_MELS][SPEC_SIZE].
     */
    fun createMelFilterbank(
        nMels: Int = N_MELS,
        fftSize: Int = FFT_SIZE,
        sampleRate: Int = SAMPLE_RATE,
        fMin: Float = F_MIN,
        fMax: Float = F_MAX
    ): Array<FloatArray> {
        val specSize = fftSize / 2 + 1

        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val melPoints = FloatArray(nMels + 2) { i ->
            melToHz(melMin + i * (melMax - melMin) / (nMels + 1))
        }

        val binPoints = IntArray(nMels + 2) { i ->
            ((melPoints[i] * fftSize) / sampleRate).roundToInt()
        }

        val filterbank = Array(nMels) { FloatArray(specSize) }
        for (m in 0 until nMels) {
            val fStart = binPoints[m]
            val fCenter = binPoints[m + 1]
            val fEnd = binPoints[m + 2]

            for (k in fStart until fCenter) {
                if (fCenter > fStart) {
                    filterbank[m][k] = (k - fStart).toFloat() / (fCenter - fStart)
                }
            }
            for (k in fCenter until fEnd) {
                if (fEnd > fCenter) {
                    filterbank[m][k] = (fEnd - k).toFloat() / (fEnd - fCenter)
                }
            }
        }
        return filterbank
    }

    /**
     * Compute power spectrum |FFT(x)|^2 using Cooley-Tukey radix-2 FFT.
     * Input signal must be zero-padded to fftSize (power of 2).
     */
    fun realFftPower(signal: FloatArray, fftSize: Int = FFT_SIZE, specSize: Int = SPEC_SIZE): FloatArray {
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        val n = signal.size.coerceAtMost(fftSize)
        for (i in 0 until n) re[i] = signal[i]

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until fftSize) {
            if (i < j) {
                val tmpRe = re[i]; re[i] = re[j]; re[j] = tmpRe
                val tmpIm = im[i]; im[i] = im[j]; im[j] = tmpIm
            }
            var m = fftSize / 2
            while (m >= 1 && j >= m) { j -= m; m /= 2 }
            j += m
        }

        // Cooley-Tukey butterfly
        var step = 2
        while (step <= fftSize) {
            val halfStep = step / 2
            val angleStep = -2.0 * PI / step
            for (k in 0 until halfStep) {
                val angle = (angleStep * k).toFloat()
                val wRe = cos(angle)
                val wIm = sin(angle)
                var i = k
                while (i < fftSize) {
                    val tRe = wRe * re[i + halfStep] - wIm * im[i + halfStep]
                    val tIm = wRe * im[i + halfStep] + wIm * re[i + halfStep]
                    re[i + halfStep] = re[i] - tRe
                    im[i + halfStep] = im[i] - tIm
                    re[i] += tRe
                    im[i] += tIm
                    i += step
                }
            }
            step *= 2
        }

        val power = FloatArray(specSize)
        for (k in 0 until specSize) {
            power[k] = re[k] * re[k] + im[k] * im[k]
        }
        return power
    }

    /**
     * Precompute DCT-II matrix [nMfcc x nMels] for MFCC extraction.
     * dct[k][n] = cos(PI * k * (n + 0.5) / nMels)
     */
    fun createDctMatrix(nMfcc: Int = N_MFCC, nMels: Int = N_MELS): Array<FloatArray> {
        return Array(nMfcc) { k ->
            FloatArray(nMels) { n ->
                cos(PI * k * (n + 0.5) / nMels).toFloat()
            }
        }
    }

    /**
     * Apply DCT-II to log-mel energies to get MFCC coefficients.
     */
    fun computeMfcc(logMelEnergies: FloatArray, dctMatrix: Array<FloatArray>): FloatArray {
        val nMfcc = dctMatrix.size
        val nMels = logMelEnergies.size
        val mfcc = FloatArray(nMfcc)
        for (k in 0 until nMfcc) {
            var sum = 0f
            for (n in 0 until nMels) {
                sum += dctMatrix[k][n] * logMelEnergies[n]
            }
            mfcc[k] = sum
        }
        return mfcc
    }

    fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)
    fun melToHz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)
}
