package com.repository.navigation

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Geometry
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.search.Response
import com.yandex.mapkit.search.SearchFactory
import com.yandex.mapkit.search.SearchManagerType
import com.yandex.mapkit.search.SearchOptions
import com.yandex.mapkit.search.SearchType
import com.yandex.mapkit.search.Session
import com.yandex.mapkit.search.ToponymObjectMetadata
import com.yandex.runtime.Error
import java.util.concurrent.atomic.AtomicBoolean

data class ReverseGeocodeResult(
    val name: String,
    val city: String?,
    val postalCode: String?,
    val fullAddress: String
)

object GeocoderHelper {

    private const val TAG = "GeocoderHelper"
    private const val GEOCODE_TIMEOUT_MS = 5_000L

    private val searchManager by lazy {
        SearchFactory.getInstance().createSearchManager(SearchManagerType.ONLINE)
    }

    // Hold references to active sessions to prevent GC
    private val activeSessions = mutableListOf<Session>()

    fun reverseGeocode(point: Point, callback: (ReverseGeocodeResult?) -> Unit) {
        val responded = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        var session: Session? = null

        val timeoutRunnable = Runnable {
            if (responded.compareAndSet(false, true)) {
                Log.w(TAG, "Reverse geocode timed out for: (${point.latitude}, ${point.longitude})")
                session?.let { activeSessions.remove(it) }
                callback(null)
            }
        }
        handler.postDelayed(timeoutRunnable, GEOCODE_TIMEOUT_MS)

        try {
            val options = SearchOptions().apply {
                searchTypes = SearchType.GEO.value
                resultPageSize = 1
            }
            handler.post {
                session = searchManager.submit(
                    point,
                    null,
                    options,
                    object : Session.SearchListener {
                        override fun onSearchResponse(response: Response) {
                            handler.removeCallbacks(timeoutRunnable)
                            if (responded.compareAndSet(false, true)) {
                                val geoObj = response.collection.children.firstOrNull()?.obj
                                if (geoObj != null) {
                                    val toponym = geoObj.metadataContainer.getItem(ToponymObjectMetadata::class.java)
                                    val address = toponym?.address
                                    val components = address?.components ?: emptyList()
                                    val name = geoObj.name ?: toponym?.address?.formattedAddress ?: ""
                                    val city = components.firstOrNull { component ->
                                        component.kinds.any { it == com.yandex.mapkit.search.Address.Component.Kind.LOCALITY }
                                    }?.name
                                    // Yandex MapKit doesn't expose a POSTAL_CODE kind; extract from formatted address if present
                                    val postalCode: String? = null
                                    val fullAddress = address?.formattedAddress ?: name
                                    Log.i(TAG, "Reverse geocoded (${point.latitude}, ${point.longitude}) -> $name")
                                    session?.let { activeSessions.remove(it) }
                                    callback(ReverseGeocodeResult(name, city, postalCode, fullAddress))
                                } else {
                                    Log.w(TAG, "No reverse geocoding result for: (${point.latitude}, ${point.longitude})")
                                    session?.let { activeSessions.remove(it) }
                                    callback(null)
                                }
                            }
                        }

                        override fun onSearchError(error: Error) {
                            handler.removeCallbacks(timeoutRunnable)
                            if (responded.compareAndSet(false, true)) {
                                Log.e(TAG, "Reverse geocode failed: $error")
                                session?.let { activeSessions.remove(it) }
                                callback(null)
                            }
                        }
                    }
                )
                session?.let { activeSessions.add(it) }
            }
        } catch (e: Exception) {
            handler.removeCallbacks(timeoutRunnable)
            if (responded.compareAndSet(false, true)) {
                Log.e(TAG, "Reverse geocode exception: ${e.message}")
                session?.let { activeSessions.remove(it) }
                callback(null)
            }
        }
    }

    fun geocode(address: String, callback: (Point?) -> Unit) {
        val responded = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        var session: Session? = null

        val timeoutRunnable = Runnable {
            if (responded.compareAndSet(false, true)) {
                Log.w(TAG, "Geocode timed out for: $address")
                session?.let { activeSessions.remove(it) }
                callback(null)
            }
        }
        handler.postDelayed(timeoutRunnable, GEOCODE_TIMEOUT_MS)

        try {
            val options = SearchOptions().apply {
                searchTypes = SearchType.GEO.value
                resultPageSize = 1
            }
            // Search within a broad bounding box covering Russia/Europe/Asia
            val geometry = Geometry.fromBoundingBox(
                BoundingBox(Point(35.0, 19.0), Point(82.0, 180.0))
            )
            // MapKit SearchManager must be created and used on the main thread
            handler.post {
                session = searchManager.submit(
                    address,
                    geometry,
                    options,
                    object : Session.SearchListener {
                        override fun onSearchResponse(response: Response) {
                            handler.removeCallbacks(timeoutRunnable)
                            if (responded.compareAndSet(false, true)) {
                                val point = response.collection.children.firstOrNull()
                                    ?.obj?.geometry?.firstOrNull()?.point
                                if (point != null) {
                                    Log.i(TAG, "Geocoded '$address' -> (${point.latitude}, ${point.longitude})")
                                } else {
                                    Log.w(TAG, "No geocoding result for: $address")
                                }
                                session?.let { activeSessions.remove(it) }
                                callback(point)
                            }
                        }

                        override fun onSearchError(error: Error) {
                            handler.removeCallbacks(timeoutRunnable)
                            if (responded.compareAndSet(false, true)) {
                                Log.e(TAG, "Geocode failed for '$address': $error")
                                session?.let { activeSessions.remove(it) }
                                callback(null)
                            }
                        }
                    }
                )
                session?.let { activeSessions.add(it) }
            }
        } catch (e: Exception) {
            handler.removeCallbacks(timeoutRunnable)
            if (responded.compareAndSet(false, true)) {
                Log.e(TAG, "Geocode exception for '$address': ${e.message}")
                session?.let { activeSessions.remove(it) }
                callback(null)
            }
        }
    }
}
