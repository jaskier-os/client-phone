package com.repository.navigation.provider

import android.content.Context
import android.util.Log
import com.repository.navigation.BuildConfig
import com.repository.navigation.JourneyLocationSource

/**
 * Google provider. Mirrors YandexMapProvider's shape but needs NO global SDK
 * init: the Maps Android SDK self-initializes from the manifest meta-data key
 * (com.google.android.geo.API_KEY) on first MapView use, and the routing /
 * geocoding / places / static-map paths are plain HTTP keyed by
 * BuildConfig.GOOGLE_MAPS_API_KEY. init() therefore only stores the application
 * Context (so createLocationEngine, which gets no Context, can build a
 * Context-backed GoogleLocationEngine) and warns if the key is blank.
 *
 * Members are lazy so merely linking this class never touches any Google object.
 */
class GoogleMapProvider : MapProvider {

    companion object {
        private const val TAG = "GoogleMapProvider"
        const val ID = "google"
    }

    override val id: String = ID

    override val routing: RoutingEngine by lazy { GoogleRoutingEngine() }
    override val geocoder: Geocoder by lazy { GoogleGeocoder() }
    override val placeSearch: PlaceSearch by lazy { GooglePlaceSearch() }
    override val placeSuggest: PlaceSuggest by lazy { GooglePlaceSuggest() }

    // Stored in init(); createLocationEngine receives no Context but
    // GoogleLocationEngine (FusedLocation + mock ticker) requires one.
    private lateinit var appContext: Context

    override fun createMinimapImageSource(context: Context): MinimapImageSource =
        GoogleStaticMapSource(context.applicationContext)

    override fun createInteractiveMap(context: Context, locationEngine: LocationEngine): InteractiveMap =
        GoogleInteractiveMap(context, locationEngine)

    // Google ignores the JourneyLocationSource: GoogleLocationEngine drives a
    // FusedLocation client + its own synthetic mock ticker off the stored Context.
    override fun createLocationEngine(source: JourneyLocationSource): LocationEngine =
        GoogleLocationEngine(appContext)

    override fun init(context: Context) {
        appContext = context.applicationContext
        if (BuildConfig.GOOGLE_MAPS_API_KEY.isBlank()) {
            Log.w(TAG, "init: GOOGLE_MAPS_API_KEY is blank; Google HTTP APIs and the map will not work")
        } else {
            Log.d(TAG, "init: GOOGLE_MAPS_API_KEY present (${BuildConfig.GOOGLE_MAPS_API_KEY.take(8)}...)")
        }
    }

    override fun onProcessStart() { /* Google needs no process-level map start */ }
    override fun onProcessStop() { /* Google needs no process-level map stop */ }

    override val requiresProcessMapStart: Boolean = false
}
