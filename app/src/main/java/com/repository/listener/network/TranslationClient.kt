package com.repository.listener.network

import com.repository.listener.util.LogCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TranslationClient(
    private val baseUrl: String,
    private val apiKey: String
) {

    companion object {
        private const val TAG = "TranslationClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Synchronous translation -- call from a background thread.
     * sourceLang/targetLang use NLLB codes: "eng_Latn", "rus_Cyrl", etc.
     * Returns translated text or null on error.
     */
    fun translate(text: String, sourceLang: String, targetLang: String): String? {
        return try {
            val body = JSONObject().apply {
                put("text", text)
                put("source_lang", sourceLang)
                put("target_lang", targetLang)
            }.toString()

            val requestBuilder = Request.Builder()
                .url("$baseUrl/api/v1/translate")
                .post(body.toRequestBody("application/json".toMediaType()))

            if (apiKey.isNotEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                requestBuilder.addHeader("x-api-key", apiKey)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                LogCollector.e(TAG, "Translation failed: HTTP ${response.code} - $responseBody")
                return null
            }

            val json = JSONObject(responseBody ?: "{}")
            val translations = json.optJSONArray("translations")
            val translated = translations?.optJSONObject(0)?.optString("text", "")?.trim()
            LogCollector.i(TAG, "Translation result: $translated")
            translated?.ifEmpty { null }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Translation error: ${e.message}")
            null
        }
    }
}
