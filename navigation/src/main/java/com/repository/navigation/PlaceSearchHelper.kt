package com.repository.navigation

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Geometry
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.search.BusinessObjectMetadata
import com.yandex.mapkit.search.Response
import com.yandex.mapkit.search.SearchFactory
import com.yandex.mapkit.search.SearchManagerType
import com.yandex.mapkit.search.SearchOptions
import com.yandex.mapkit.search.SearchType
import com.yandex.mapkit.search.Session
import com.yandex.runtime.Error
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object PlaceSearchHelper {

    private const val TAG = "PlaceSearchHelper"
    private const val SEARCH_TIMEOUT_MS = 10_000L
    private const val DEFAULT_RADIUS_M = 1000.0
    private const val MAX_RESULTS = 10

    private val searchManager by lazy {
        SearchFactory.getInstance().createSearchManager(SearchManagerType.ONLINE)
    }

    private val activeSessions = mutableListOf<Session>()

    fun search(
        query: String,
        center: Point,
        radiusMeters: Double = DEFAULT_RADIUS_M,
        callback: (JSONObject) -> Unit
    ) {
        val responded = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        var session: Session? = null

        val timeoutRunnable = Runnable {
            if (responded.compareAndSet(false, true)) {
                Log.w(TAG, "Place search timed out for: $query")
                session?.let { activeSessions.remove(it) }
                callback(JSONObject().put("places", JSONArray()).put("error", "Search timed out"))
            }
        }
        handler.postDelayed(timeoutRunnable, SEARCH_TIMEOUT_MS)

        try {
            val options = SearchOptions().apply {
                searchTypes = SearchType.BIZ.value
                resultPageSize = MAX_RESULTS
            }

            val radius = radiusMeters.coerceIn(100.0, 10_000.0)
            val deltaLat = radius / 111_320.0
            val deltaLng = radius / (111_320.0 * cos(Math.toRadians(center.latitude)))
            val geometry = Geometry.fromBoundingBox(
                BoundingBox(
                    Point(center.latitude - deltaLat, center.longitude - deltaLng),
                    Point(center.latitude + deltaLat, center.longitude + deltaLng)
                )
            )

            handler.post {
                session = searchManager.submit(
                    query,
                    geometry,
                    options,
                    object : Session.SearchListener {
                        override fun onSearchResponse(response: Response) {
                            handler.removeCallbacks(timeoutRunnable)
                            if (responded.compareAndSet(false, true)) {
                                val placeList = mutableListOf<JSONObject>()
                                for (child in response.collection.children) {
                                    val obj = child.obj ?: continue
                                    val point = obj.geometry.firstOrNull()?.point ?: continue
                                    val bizMeta = obj.metadataContainer.getItem(BusinessObjectMetadata::class.java)
                                    val dist = haversineMeters(center, point).toInt()

                                    val place = JSONObject().apply {
                                        put("name", obj.name ?: "Unknown")
                                        put("address", bizMeta?.address?.formattedAddress ?: "")
                                        put("category", bizMeta?.categories?.firstOrNull()?.name ?: "")
                                        put("lat", point.latitude)
                                        put("lng", point.longitude)
                                        put("distanceMeters", dist)
                                    }
                                    placeList.add(place)
                                }
                                placeList.sortBy { it.getInt("distanceMeters") }
                                val places = JSONArray()
                                placeList.forEach { places.put(it) }
                                Log.i(TAG, "Found ${places.length()} places for '$query'")
                                session?.let { activeSessions.remove(it) }
                                callback(JSONObject().put("places", places))
                            }
                        }

                        override fun onSearchError(error: Error) {
                            handler.removeCallbacks(timeoutRunnable)
                            if (responded.compareAndSet(false, true)) {
                                Log.e(TAG, "Place search failed for '$query': $error")
                                session?.let { activeSessions.remove(it) }
                                callback(JSONObject().put("places", JSONArray()).put("error", "Search failed: $error"))
                            }
                        }
                    }
                )
                session?.let { activeSessions.add(it) }
            }
        } catch (e: Exception) {
            handler.removeCallbacks(timeoutRunnable)
            if (responded.compareAndSet(false, true)) {
                Log.e(TAG, "Place search exception for '$query': ${e.message}")
                session?.let { activeSessions.remove(it) }
                callback(JSONObject().put("places", JSONArray()).put("error", "Exception: ${e.message}"))
            }
        }
    }

    private fun haversineMeters(a: Point, b: Point): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val sinLat = sin(dLat / 2)
        val sinLng = sin(dLng / 2)
        val h = sinLat * sinLat +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sinLng * sinLng
        return 2 * r * asin(sqrt(h))
    }
}
