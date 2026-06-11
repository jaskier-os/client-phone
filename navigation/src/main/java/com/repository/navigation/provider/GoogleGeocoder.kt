package com.repository.navigation.provider

import android.net.Uri
import android.util.Log
import com.repository.navigation.BuildConfig
import com.repository.navigation.ReverseGeocodeResult
import com.yandex.mapkit.geometry.Point
import okhttp3.Request
import org.json.JSONObject

/**
 * Google implementation of [Geocoder] using the classic Geocoding API
 * (https://maps.googleapis.com/maps/api/geocode/json). The API key is passed as
 * the `key=` query param. All HTTP runs off the main thread (OkHttp) and every
 * callback is delivered on the MAIN thread, matching the Yandex impl contract.
 */
class GoogleGeocoder : Geocoder {

    private companion object {
        const val TAG = "GoogleGeocoder"
        const val BASE = "https://maps.googleapis.com/maps/api/geocode/json"
    }

    override fun geocode(address: String, callback: (Point?) -> Unit) {
        val url = Uri.parse(BASE).buildUpon()
            .appendQueryParameter("address", address)
            .appendQueryParameter("key", BuildConfig.GOOGLE_MAPS_API_KEY)
            .build()
            .toString()

        val request = Request.Builder().url(url).get().build()
        GoogleHttp.enqueue(
            request,
            onBody = { body ->
                val point = try {
                    val json = JSONObject(body)
                    val results = json.optJSONArray("results")
                    val first = if (results != null && results.length() > 0) results.getJSONObject(0) else null
                    val loc = first
                        ?.optJSONObject("geometry")
                        ?.optJSONObject("location")
                    if (loc != null) Point(loc.getDouble("lat"), loc.getDouble("lng")) else null
                } catch (e: Exception) {
                    Log.e(TAG, "geocode parse error: $e")
                    null
                }
                callback(point)
            },
            onFailure = {
                Log.e(TAG, "geocode request failed")
                callback(null)
            }
        )
    }

    override fun reverseGeocode(point: Point, callback: (ReverseGeocodeResult?) -> Unit) {
        val url = Uri.parse(BASE).buildUpon()
            .appendQueryParameter("latlng", "${point.latitude},${point.longitude}")
            .appendQueryParameter("key", BuildConfig.GOOGLE_MAPS_API_KEY)
            .build()
            .toString()

        val request = Request.Builder().url(url).get().build()
        GoogleHttp.enqueue(
            request,
            onBody = { body ->
                val result = try {
                    val json = JSONObject(body)
                    val results = json.optJSONArray("results")
                    val first = if (results != null && results.length() > 0) results.getJSONObject(0) else null
                    if (first != null) {
                        val full = first.optString("formatted_address", "")
                        // Name = first comma-delimited segment (street-level), mirroring
                        // the concise label the Yandex name field carries.
                        val name = full.substringBefore(",").trim().ifEmpty { full }
                        // Map Google address_component types -> long_name so we can pull
                        // the city (locality) and postal code for ReverseGeocodeResult.
                        val components = HashMap<String, String>()
                        val arr = first.optJSONArray("address_components")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val c = arr.getJSONObject(i)
                                val longName = c.optString("long_name", "")
                                val types = c.optJSONArray("types")
                                if (types != null) {
                                    for (t in 0 until types.length()) {
                                        components[types.getString(t)] = longName
                                    }
                                }
                            }
                        }
                        if (full.isEmpty()) {
                            null
                        } else {
                            val city = components["locality"]
                                ?: components["postal_town"]
                                ?: components["administrative_area_level_2"]
                            val postalCode = components["postal_code"]
                            ReverseGeocodeResult(name, city, postalCode, full)
                        }
                    } else null
                } catch (e: Exception) {
                    Log.e(TAG, "reverseGeocode parse error: $e")
                    null
                }
                callback(result)
            },
            onFailure = {
                Log.e(TAG, "reverseGeocode request failed")
                callback(null)
            }
        )
    }
}
