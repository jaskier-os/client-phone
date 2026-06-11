package com.repository.navigation.provider

import android.util.Log
import com.repository.navigation.BuildConfig
import com.yandex.mapkit.geometry.Point
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Google implementation of [PlaceSearch] using Places API (NEW) Text Search
 * (https://places.googleapis.com/v1/places:searchText), location-biased to
 * [center]. Produces the EXACT same JSON the Yandex impl produces:
 *   { "places": [ { name, address, category, lat, lng, distanceMeters } ] }
 * Errors / empty -> { "places": [] }. All callbacks delivered on the MAIN thread.
 */
class GooglePlaceSearch : PlaceSearch {

    private companion object {
        const val TAG = "GooglePlaceSearch"
        const val URL = "https://places.googleapis.com/v1/places:searchText"
        // Only request the fields we map; keeps response small and billing low.
        const val FIELD_MASK =
            "places.displayName,places.formattedAddress,places.location,places.primaryType,places.primaryTypeDisplayName"
    }

    override fun search(
        query: String,
        center: Point,
        radiusMeters: Double,
        callback: (JSONObject) -> Unit
    ) {
        val payload = JSONObject().apply {
            put("textQuery", query)
            put("locationBias", JSONObject().apply {
                put("circle", JSONObject().apply {
                    put("center", JSONObject().apply {
                        put("latitude", center.latitude)
                        put("longitude", center.longitude)
                    })
                    put("radius", radiusMeters)
                })
            })
        }

        val request = Request.Builder()
            .url(URL)
            .addHeader("X-Goog-Api-Key", BuildConfig.GOOGLE_MAPS_API_KEY)
            .addHeader("X-Goog-FieldMask", FIELD_MASK)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        GoogleHttp.enqueue(
            request,
            onBody = { body -> callback(parse(body, center, radiusMeters)) },
            onFailure = {
                Log.e(TAG, "search request failed")
                callback(JSONObject().put("places", JSONArray()))
            }
        )
    }

    private fun parse(body: String, center: Point, radiusMeters: Double): JSONObject {
        val collected = mutableListOf<Pair<Double, JSONObject>>()
        try {
            val json = JSONObject(body)
            val places = json.optJSONArray("places")
            if (places != null) {
                for (i in 0 until places.length()) {
                    val p = places.getJSONObject(i)
                    val loc = p.optJSONObject("location") ?: continue
                    val lat = loc.optDouble("latitude", Double.NaN)
                    val lng = loc.optDouble("longitude", Double.NaN)
                    if (lat.isNaN() || lng.isNaN()) continue
                    val point = Point(lat, lng)
                    val name = p.optJSONObject("displayName")?.optString("text", "") ?: ""
                    val address = p.optString("formattedAddress", "")
                    val category = p.optJSONObject("primaryTypeDisplayName")?.optString("text", "")
                        ?: p.optString("primaryType", "")
                    val distanceMeters = haversineMeters(center, point)
                    if (distanceMeters > radiusMeters) continue
                    val placeJson = JSONObject().apply {
                        put("name", name)
                        put("address", address)
                        put("category", category)
                        put("lat", point.latitude)
                        put("lng", point.longitude)
                        put("distanceMeters", distanceMeters.toInt())
                    }
                    collected.add(distanceMeters to placeJson)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "search parse error: $e")
        }
        collected.sortBy { it.first }
        val placesArray = JSONArray()
        for ((_, obj) in collected) placesArray.put(obj)
        return JSONObject().put("places", placesArray)
    }

    private fun haversineMeters(a: Point, b: Point): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
                sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(sqrt(h))
    }
}
