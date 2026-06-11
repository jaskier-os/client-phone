package com.repository.navigation.provider

import android.util.Log
import com.repository.navigation.BuildConfig
import com.repository.navigation.ui.SuggestItem
import com.yandex.mapkit.geometry.Point
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

/**
 * Google implementation of [PlaceSuggest] using Places API (NEW) Autocomplete
 * (https://places.googleapis.com/v1/places:autocomplete), location-biased to
 * [center]. New Autocomplete returns placeId + text but NOT lat/lng, so the
 * SuggestItem.point is left null (exactly as the Yandex impl leaves it null when
 * a suggestion has no resolved center); the destination is resolved later via
 * geocode-on-select. A session token is reused across keystrokes for billing and
 * cancelled (with any in-flight call) by [reset]. All callbacks on MAIN thread.
 */
class GooglePlaceSuggest : PlaceSuggest {

    private companion object {
        const val TAG = "GooglePlaceSuggest"
        const val URL = "https://places.googleapis.com/v1/places:autocomplete"
        const val FIELD_MASK =
            "suggestions.placePrediction.text," +
            "suggestions.placePrediction.structuredFormat.mainText," +
            "suggestions.placePrediction.structuredFormat.secondaryText"
    }

    private var sessionToken: String = UUID.randomUUID().toString()
    private var inFlight: Call? = null

    override fun suggest(query: String, center: Point, callback: (List<SuggestItem>) -> Unit) {
        // Cancel any previous in-flight suggest before issuing a new one.
        inFlight?.cancel()

        val payload = JSONObject().apply {
            put("input", query)
            put("sessionToken", sessionToken)
            put("locationBias", JSONObject().apply {
                put("circle", JSONObject().apply {
                    put("center", JSONObject().apply {
                        put("latitude", center.latitude)
                        put("longitude", center.longitude)
                    })
                    put("radius", 50_000.0)
                })
            })
        }

        val request = Request.Builder()
            .url(URL)
            .addHeader("X-Goog-Api-Key", BuildConfig.GOOGLE_MAPS_API_KEY)
            .addHeader("X-Goog-FieldMask", FIELD_MASK)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        inFlight = GoogleHttp.enqueue(
            request,
            onBody = { body -> callback(parse(body)) },
            onFailure = {
                Log.e(TAG, "suggest request failed")
                callback(emptyList())
            }
        )
    }

    private fun parse(body: String): List<SuggestItem> {
        val items = ArrayList<SuggestItem>()
        try {
            val json = JSONObject(body)
            val suggestions = json.optJSONArray("suggestions") ?: return items
            val limit = minOf(suggestions.length(), 8)
            for (i in 0 until limit) {
                val pred = suggestions.getJSONObject(i).optJSONObject("placePrediction") ?: continue
                val structured = pred.optJSONObject("structuredFormat")
                val title = structured?.optJSONObject("mainText")?.optString("text", "")
                    ?: pred.optJSONObject("text")?.optString("text", "")
                    ?: ""
                val subtitle = structured?.optJSONObject("secondaryText")?.optString("text", "") ?: ""
                if (title.isEmpty()) continue
                items.add(
                    SuggestItem(
                        title = title,
                        subtitle = subtitle,
                        point = null,
                        distanceFormatted = null
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "suggest parse error: $e")
        }
        return items
    }

    override fun reset() {
        inFlight?.cancel()
        inFlight = null
        // Start a fresh autocomplete session for the next destination edit.
        sessionToken = UUID.randomUUID().toString()
    }
}
