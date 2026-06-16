package com.repository.navigation.provider

import android.util.Log
import com.repository.navigation.ui.SuggestItem
import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.search.SearchFactory
import com.yandex.mapkit.search.SearchManagerType
import com.yandex.mapkit.search.SuggestOptions
import com.yandex.mapkit.search.SuggestResponse
import com.yandex.mapkit.search.SuggestSession
import com.yandex.mapkit.search.SuggestType
import com.yandex.runtime.Error
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Yandex implementation of [PlaceSuggest]. Owns the Yandex SuggestSession that
 * previously lived in NavigationFragment. Created lazily so merely linking the
 * class never inits MapKit.
 */
class YandexPlaceSuggest : PlaceSuggest {

    companion object {
        private const val TAG = "YandexPlaceSuggest"
        // Same Moscow-region bias the fragment used.
        private val SUGGEST_AREA = BoundingBox(Point(55.5, 37.3), Point(56.1, 37.9))
    }

    private val searchManager by lazy {
        SearchFactory.getInstance().createSearchManager(SearchManagerType.COMBINED)
    }
    private var suggestSession: SuggestSession? = null
    // True while this suggest session holds the shared map engine (acquired on first suggest,
    // released on reset). Scoped to the session rather than per-call because superseded suggest
    // listeners may never fire.
    private val engineHeld = AtomicBoolean(false)

    override fun suggest(query: String, center: Point, callback: (List<SuggestItem>) -> Unit) {
        // Engine hold: suggest is interactive and superseded calls may never fire their
        // listener, so a per-call acquire/release would leak. Instead the whole suggest
        // session is scoped: first suggest acquires, reset() releases (search UI closed).
        if (engineHeld.compareAndSet(false, true)) MapProviders.acquireEngine("place_suggest")
        val session = suggestSession ?: searchManager.createSuggestSession().also { suggestSession = it }
        val options = SuggestOptions().setSuggestTypes(
            SuggestType.GEO.value or SuggestType.BIZ.value or SuggestType.TRANSIT.value
        )
        session.suggest(query, SUGGEST_AREA, options, object : SuggestSession.SuggestListener {
            override fun onResponse(response: SuggestResponse) {
                val items = response.items.take(8).map { item ->
                    val dist = item.center?.let { c ->
                        formatDistance(haversineMeters(center, c))
                    }
                    SuggestItem(
                        title = item.title.text,
                        subtitle = item.subtitle?.text ?: "",
                        point = item.center,
                        distanceFormatted = dist
                    )
                }
                callback(items)
            }

            override fun onError(error: Error) {
                Log.e(TAG, "Suggest error: $error")
                callback(emptyList())
            }
        })
    }

    override fun reset() {
        suggestSession = null
        if (engineHeld.compareAndSet(true, false)) MapProviders.releaseEngine("place_suggest")
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

    private fun formatDistance(meters: Double): String =
        if (meters >= 1000) "%.1f km".format(meters / 1000.0) else "${meters.toInt()} m"
}
