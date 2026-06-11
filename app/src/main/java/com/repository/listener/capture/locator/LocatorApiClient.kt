package com.repository.listener.capture.locator

import android.content.Context
import android.util.Log
import com.repository.listener.capture.signals.LocatorSignals
import com.repository.listener.config.AppConfig
import com.repository.listener.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Successfully resolved location from the Yandex Locator API.
 */
data class LocatorResult(
    val lat: Double,
    val lon: Double,
    val accuracy: Double,
)

/**
 * Client for POST https://locator.api.maps.yandex.ru/v1/locate
 *
 * Responsibilities:
 *   1. Shape the [LocatorSignals] into the request body expected by the API.
 *   2. Apply the global [LocatorRateLimiter] before every HTTP call.
 *   3. Retry up to 3 attempts with exponential backoff on retryable failures.
 *   4. Parse the response or return null if the service cannot locate us
 *      (empty body, bad key, malformed request, or all retries exhausted).
 *
 * This client is stateless -- multiple instances are safe. State that MUST
 * be process-wide (rate limiter) lives in [LocatorRateLimiter].
 */
class LocatorApiClient(
    private val context: Context,
    private val httpClient: OkHttpClient = DEFAULT_CLIENT,
    /**
     * Override the endpoint (for tests). Null uses the production URL.
     * Exposed as a method-level override on [locate] instead of a field so
     * callers can't accidentally pin it.
     */
) {
    companion object {
        const val TAG = "LocatorApiClient"
        const val DEFAULT_ENDPOINT = "https://locator.api.maps.yandex.ru/v1/locate"
        const val MAX_ATTEMPTS = 3
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val DEFAULT_CLIENT = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)  // we manage retries explicitly
            .build()
    }

    /**
     * Outcome categories used internally by the retry loop.
     */
    private sealed class Attempt {
        data class Success(val result: LocatorResult) : Attempt()
        /** 200 OK with empty object -- service has no data for this area. */
        object Unknown : Attempt()
        /** Retryable: 429, 5xx, IO exception. */
        data class Retryable(val reason: String) : Attempt()
        /** Fatal: 400, 403, malformed payload. */
        data class Fatal(val reason: String) : Attempt()
    }

    /**
     * Main entry point. Returns:
     *   - non-null [LocatorResult] on success
     *   - null for any failure path: unknown location, fatal error, all retries exhausted
     *
     * The caller (LocationProvider) is responsible for falling back to the
     * Fused provider when this returns null.
     *
     * @param endpointOverride optional endpoint URL (for tests).
     */
    suspend fun locate(
        signals: LocatorSignals,
        endpointOverride: String? = null,
    ): LocatorResult? = withContext(Dispatchers.IO) {
        if (signals.isEmpty()) {
            Log.d(TAG, "LOCATE_SKIP empty signals")
            return@withContext null
        }

        val apiKey = AppConfig.getLocatorApiKey(context)
        if (apiKey.isBlank()) {
            Log.w(TAG, "LOCATE_SKIP no LOCATOR_API_KEY configured")
            LogCollector.w(TAG, "Locator API key not configured; skipping Locator path")
            return@withContext null
        }

        val endpoint = (endpointOverride ?: DEFAULT_ENDPOINT) + "?apikey=$apiKey"
        // Serialize the body ONCE before the retry loop -- signals don't change
        // between attempts, so re-serializing would just burn CPU + GC pressure.
        val bodyJson = signals.toRequestJson().toString()

        for (attempt in 1..MAX_ATTEMPTS) {
            val waitedMs = LocatorRateLimiter.acquire()
            val startMs = System.currentTimeMillis()
            val outcome = performRequest(endpoint, bodyJson)
            val rttMs = System.currentTimeMillis() - startMs

            when (outcome) {
                is Attempt.Success -> {
                    Log.d(TAG, "LOCATE_OK attempt=$attempt rtt=${rttMs}ms wait=${waitedMs}ms " +
                        "lat=${outcome.result.lat} lon=${outcome.result.lon} acc=${outcome.result.accuracy}m " +
                        "signals=${signals.summary()}")
                    LogCollector.i(TAG, "Locator resolved (attempt $attempt, rtt=${rttMs}ms): " +
                        "lat=${outcome.result.lat} lon=${outcome.result.lon} acc=${outcome.result.accuracy}m")
                    return@withContext outcome.result
                }
                is Attempt.Unknown -> {
                    Log.d(TAG, "LOCATE_UNKNOWN attempt=$attempt service has no data for this area")
                    LogCollector.w(TAG, "Locator returned empty location -- area not covered")
                    return@withContext null  // no point retrying
                }
                is Attempt.Fatal -> {
                    Log.e(TAG, "LOCATE_FATAL attempt=$attempt reason=${outcome.reason}")
                    LogCollector.e(TAG, "Locator fatal error: ${outcome.reason}")
                    return@withContext null  // no point retrying
                }
                is Attempt.Retryable -> {
                    if (attempt == MAX_ATTEMPTS) {
                        Log.e(TAG, "LOCATE_EXHAUSTED after $MAX_ATTEMPTS attempts: ${outcome.reason}")
                        LogCollector.e(TAG, "Locator retries exhausted: ${outcome.reason}")
                        return@withContext null
                    }
                    val backoff = backoffMs(attempt)
                    Log.w(TAG, "LOCATE_RETRY attempt=$attempt/$MAX_ATTEMPTS reason=${outcome.reason} " +
                        "next_in=${backoff}ms")
                    LogCollector.w(TAG, "Locator retry $attempt/$MAX_ATTEMPTS after ${outcome.reason}, backing off ${backoff}ms")
                    delay(backoff)
                }
            }
        }
        null
    }

    private fun performRequest(endpoint: String, bodyJson: String): Attempt {
        return try {
            val req = Request.Builder()
                .url(endpoint)
                .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val code = resp.code
                val bodyStr = resp.body?.string().orEmpty()
                when (code) {
                    200 -> parseSuccessBody(bodyStr)
                    400 -> Attempt.Fatal("HTTP 400 ${bodyStr.take(120)}")
                    403 -> Attempt.Fatal("HTTP 403 ${bodyStr.take(120)}")
                    429, in 500..599 -> Attempt.Retryable("HTTP $code ${bodyStr.take(120)}")
                    else -> Attempt.Retryable("HTTP $code ${bodyStr.take(120)}")
                }
            }
        } catch (e: IOException) {
            Attempt.Retryable("IO ${e.javaClass.simpleName}: ${e.message}")
        } catch (e: Exception) {
            Attempt.Fatal("unexpected ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun parseSuccessBody(body: String): Attempt {
        if (body.isBlank()) return Attempt.Unknown
        return try {
            val root = JSONObject(body)
            val loc = root.optJSONObject("location") ?: return Attempt.Unknown
            val point = loc.optJSONObject("point") ?: return Attempt.Unknown
            val lat = point.optDouble("lat", Double.NaN)
            val lon = point.optDouble("lon", Double.NaN)
            val accuracy = loc.optDouble("accuracy", Double.NaN)
            if (lat.isNaN() || lon.isNaN() || accuracy.isNaN()) {
                return Attempt.Fatal("malformed response body")
            }
            Attempt.Success(LocatorResult(lat = lat, lon = lon, accuracy = accuracy))
        } catch (e: Exception) {
            Attempt.Fatal("parse error: ${e.message}")
        }
    }

    /**
     * Exponential backoff with +/-20% jitter.
     *   attempt 1 -> ~500 ms  (after the 1st failure, before the 2nd try)
     *   attempt 2 -> ~1000 ms (after the 2nd failure, before the 3rd try)
     */
    private fun backoffMs(attempt: Int): Long {
        val base = 500L * (1L shl (attempt - 1))  // 500, 1000, 2000...
        val jitter = Random.nextDouble(0.8, 1.2)
        return (base * jitter).toLong()
    }
}
