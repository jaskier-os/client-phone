package com.repository.listener.network

import com.repository.listener.util.LogCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

class TranscriptionClient(
    private val baseUrl: String,
    private val apiKey: String
) {

    companion object {
        private const val TAG = "TranscriptionClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Synchronous transcription -- call from a background thread.
     * Takes raw PCM bytes (16kHz, mono, 16-bit) and returns transcribed text or null on error.
     */
    fun transcribe(audioData: ByteArray, provider: String = "local", language: String = "ru"): String? {
        return try {
            val wavBytes = buildWavBytes(audioData)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    "recording.wav",
                    wavBytes.toRequestBody("audio/wav".toMediaType())
                )
                .build()

            val requestBuilder = Request.Builder()
                .url("$baseUrl/api/v1/transcribe?provider=$provider&language=$language")
                .post(requestBody)

            if (apiKey.isNotEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                requestBuilder.addHeader("x-api-key", apiKey)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string()

            if (!response.isSuccessful) {
                LogCollector.e(TAG, "Transcription failed: HTTP ${response.code} - $body")
                return null
            }

            val json = JSONObject(body ?: "{}")
            val text = json.optString("text", "").trim()
            LogCollector.i(TAG, "Transcription result: $text")
            text.ifEmpty { null }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Transcription error: ${e.message}")
            null
        }
    }

    /**
     * Creates a valid WAV file from raw PCM data.
     * Format: RIFF header + WAVE + fmt chunk (PCM, 1 channel, 16000 Hz, 16-bit) + data chunk.
     */
    private fun buildWavBytes(pcmData: ByteArray): ByteArray {
        val numChannels: Short = 1
        val sampleRate = 16000
        val bitsPerSample: Short = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = (numChannels * bitsPerSample / 8).toShort()
        val dataSize = pcmData.size
        val fileSize = 36 + dataSize // total - 8 bytes for RIFF header

        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(fileSize)
        buffer.put("WAVE".toByteArray())

        // fmt subchunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // subchunk size for PCM
        buffer.putShort(1) // audio format: PCM
        buffer.putShort(numChannels)
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign)
        buffer.putShort(bitsPerSample)

        // data subchunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        buffer.put(pcmData)

        return buffer.array()
    }
}
