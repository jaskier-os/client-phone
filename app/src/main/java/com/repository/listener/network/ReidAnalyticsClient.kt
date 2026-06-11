package com.repository.listener.network

import com.repository.listener.util.LogCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ReidAnalyticsClient(private val baseUrl: String, private val apiKey: String) {

    companion object {
        private const val TAG = "ReidAnalyticsClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val longClient = client.newBuilder()
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    fun searchPhone(phone: String): JSONObject? {
        return try {
            val json = JSONObject().apply { put("phone", phone) }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/v1/reid/search-phone")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("x-api-key", apiKey)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    LogCollector.e(TAG, "Search phone failed: HTTP ${response.code} - $responseBody")
                    return null
                }

                LogCollector.i(TAG, "Search phone result received")
                JSONObject(responseBody ?: "{}")
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Search phone error: ${e.message}")
            null
        }
    }

    fun getPerson(personId: String): JSONObject? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/v1/reid/persons/$personId")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("x-api-key", apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    LogCollector.e(TAG, "Get person failed: HTTP ${response.code} - $responseBody")
                    return null
                }

                JSONObject(responseBody ?: "{}")
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Get person error: ${e.message}")
            null
        }
    }

    fun getPersons(limit: Int, offset: Int): JSONObject? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/v1/reid/persons?limit=$limit&offset=$offset")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("x-api-key", apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    LogCollector.e(TAG, "Get persons failed: HTTP ${response.code} - $responseBody")
                    return null
                }

                JSONObject(responseBody ?: "{}")
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Get persons error: ${e.message}")
            null
        }
    }

    fun searchPersons(query: String, limit: Int = 20): JSONObject? {
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/v1/reid/persons/search?q=$encodedQuery&limit=$limit")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("x-api-key", apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    LogCollector.e(TAG, "Search persons failed: HTTP ${response.code} - $responseBody")
                    return null
                }

                JSONObject(responseBody ?: "{}")
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Search persons error: ${e.message}")
            null
        }
    }

    fun getPersonSightings(personId: String, limit: Int, offset: Int): JSONObject? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/v1/reid/persons/$personId/sightings?limit=$limit&offset=$offset")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("x-api-key", apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    LogCollector.e(TAG, "Get sightings failed: HTTP ${response.code} - $responseBody")
                    return null
                }

                JSONObject(responseBody ?: "{}")
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Get sightings error: ${e.message}")
            null
        }
    }

    fun batchPhoneLookup(phones: List<String>): Map<String, String> {
        return try {
            val json = JSONObject().apply {
                val arr = org.json.JSONArray()
                phones.forEach { arr.put(it) }
                put("phones", arr)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/v1/reid/persons/batch-phone-lookup")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("x-api-key", apiKey)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    LogCollector.e(TAG, "Batch phone lookup failed: HTTP ${response.code}")
                    return emptyMap()
                }

                val result = JSONObject(responseBody ?: "{}")
                val results = result.optJSONObject("results") ?: return emptyMap()
                val map = mutableMapOf<String, String>()
                results.keys().forEach { phone ->
                    val entry = results.getJSONObject(phone)
                    map[phone] = entry.getString("personId")
                }
                map
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Batch phone lookup error: ${e.message}")
            emptyMap()
        }
    }

    fun createSighting(
        personId: String, cameraId: String, score: Float,
        lat: Double, lng: Double, accuracy: Double,
        headingDegrees: Float? = null
    ): JSONObject? {
        return try {
            LogCollector.i(TAG, "REID_API_REQ createSighting personId=$personId camera=$cameraId score=$score heading=$headingDegrees")
            val json = JSONObject().apply {
                put("person_id", personId)
                put("camera_id", cameraId)
                put("confidence_score", score.toDouble())
                put("detected_at", java.time.Instant.now().toString())
                put("person_latitude", lat)
                put("person_longitude", lng)
                put("location_confidence", accuracy)
                if (headingDegrees != null) put("heading_degrees", headingDegrees.toDouble())
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/v1/reid/sightings")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("x-api-key", apiKey)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    LogCollector.e(TAG, "REID_API_RESP createSighting FAILED status=${response.code} body=$responseBody")
                    return null
                }

                LogCollector.i(TAG, "REID_API_RESP createSighting status=${response.code} personId=$personId")
                JSONObject(responseBody ?: "{}")
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Create sighting error: ${e.message}")
            null
        }
    }

    fun getPersonSnapshots(personId: String): JSONObject? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/v1/reid/persons/$personId/snapshots")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("x-api-key", apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    LogCollector.e(TAG, "Get snapshots failed: HTTP ${response.code} - $responseBody")
                    return null
                }

                JSONObject(responseBody ?: "{}")
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Get snapshots error: ${e.message}")
            null
        }
    }

    fun getSimilarPersons(personId: String, threshold: Float = 0.7f, limit: Int = 10): JSONObject? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/v1/reid/persons/$personId/similar?threshold=$threshold&limit=$limit")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("x-api-key", apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    LogCollector.e(TAG, "Get similar persons failed: HTTP ${response.code} - $responseBody")
                    return null
                }

                JSONObject(responseBody ?: "{}")
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Get similar persons error: ${e.message}")
            null
        }
    }

    fun updatePerson(personId: String, isActive: Boolean): JSONObject? {
        return try {
            val json = JSONObject().apply { put("is_active", isActive) }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/v1/reid/persons/$personId")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("x-api-key", apiKey)
                .put(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    LogCollector.e(TAG, "Update person failed: HTTP ${response.code} - $responseBody")
                    return null
                }

                JSONObject(responseBody ?: "{}")
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Update person error: ${e.message}")
            null
        }
    }

    fun searchPersonInfo(personId: String, searchType: String, query: String, botUsername: String? = null, service: String? = null): JSONObject? {
        return try {
            val json = JSONObject().apply {
                put("searchType", searchType)
                put("query", query)
                if (!service.isNullOrBlank()) put("service", service)
                if (!botUsername.isNullOrBlank()) {
                    put("options", JSONObject().put("botUsername", botUsername.trimStart('@')))
                }
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/v1/reid/persons/$personId/search-info")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("x-api-key", apiKey)
                .post(body)
                .build()

            longClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    LogCollector.e(TAG, "Search person info failed: HTTP ${response.code} - $responseBody")
                    return null
                }

                LogCollector.i(TAG, "Search person info result received for $searchType")
                JSONObject(responseBody ?: "{}")
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Search person info error: ${e.message}")
            null
        }
    }

    fun deletePerson(personId: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/v1/reid/persons/$personId")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("x-api-key", apiKey)
                .delete()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    LogCollector.e(TAG, "Delete person failed: HTTP ${response.code}")
                    return false
                }

                LogCollector.i(TAG, "Person $personId deleted")
                true
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Delete person error: ${e.message}")
            false
        }
    }

    fun getOsintPhotoUrl(filename: String): String {
        return "$baseUrl/api/v1/reid/osint-photos/$filename"
    }

    fun getPersonImage(personId: String): ByteArray? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/v1/reid/persons/$personId/image")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("x-api-key", apiKey)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Get person image error: ${e.message}")
            null
        }
    }

    fun searchByPhoto(imageBytes: ByteArray, threshold: Float = 0.7f, limit: Int = 5): JSONObject? {
        return try {
            LogCollector.i(TAG, "REID_API_REQ searchByPhoto imgSize=${imageBytes.size} threshold=$threshold limit=$limit")
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "image", "face.webp",
                    imageBytes.toRequestBody("image/webp".toMediaType())
                )
                .addFormDataPart("threshold", threshold.toString())
                .addFormDataPart("limit", limit.toString())
                .build()

            val request = Request.Builder()
                .url("$baseUrl/api/v1/reid/persons/search/photo")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("x-api-key", apiKey)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    LogCollector.e(TAG, "REID_API_RESP searchByPhoto FAILED status=${response.code} body=$responseBody")
                    return null
                }

                LogCollector.i(TAG, "REID_API_RESP searchByPhoto status=${response.code} bodyLen=${responseBody?.length ?: 0}")
                JSONObject(responseBody ?: "{}")
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "Search by photo error: ${e.message}")
            null
        }
    }
}
