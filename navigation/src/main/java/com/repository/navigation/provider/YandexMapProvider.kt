package com.repository.navigation.provider

import android.content.Context
import android.util.Log
import com.repository.navigation.BuildConfig
import com.repository.navigation.JourneyLocationSource
import com.yandex.mapkit.MapKitFactory

/**
 * Yandex provider. The ONLY place MapKitFactory.setApiKey/initialize is called.
 * Members are lazy so merely linking this class never inits MapKit.
 */
class YandexMapProvider : MapProvider {

    companion object {
        private const val TAG = "YandexMapProvider"
        const val ID = "yandex"
    }

    override val id: String = ID

    override val routing: RoutingEngine by lazy { YandexRoutingEngine() }
    override val geocoder: Geocoder by lazy { YandexGeocoder() }
    override val placeSearch: PlaceSearch by lazy { YandexPlaceSearch() }
    override val placeSuggest: PlaceSuggest by lazy { YandexPlaceSuggest() }

    override fun createMinimapImageSource(context: Context): MinimapImageSource =
        YandexMinimapImageSource(context.applicationContext)

    // The UserLocationLayer follows the active LocationManager/simulator so the dot
    // tracks the mock journey natively, but the CAMERA must be driven from the same
    // GeoFix stream (follow mode) so the map actually pans with the user and stays
    // aligned above the bottom sheet -- hence the locationEngine is passed through.
    override fun createInteractiveMap(context: Context, locationEngine: LocationEngine): InteractiveMap =
        YandexInteractiveMap(context, locationEngine)

    override fun createLocationEngine(source: JourneyLocationSource): LocationEngine =
        YandexLocationEngine(source)

    @Volatile private var initialized = false

    /** Override key at runtime (must be set before init). */
    var apiKeyOverride: String? = null

    override fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val key = apiKeyOverride
                ?: BuildConfig.MAPKIT_API_KEY.ifEmpty { null }
            if (key == null) {
                Log.w(TAG, "init: no MapKit API key configured; Yandex map features disabled")
                return
            }
            Log.d(TAG, "init: apiKey=${key.take(8)}... override=$apiKeyOverride")
            MapKitFactory.setApiKey(key)
            MapKitFactory.initialize(context)
            initialized = true
        }
    }

    override fun onProcessStart() {
        if (!initialized) return
        try { MapKitFactory.getInstance().onStart() } catch (e: Exception) {
            Log.w(TAG, "onProcessStart failed: ${e.message}")
        }
    }

    override fun onProcessStop() {
        if (!initialized) return
        try { MapKitFactory.getInstance().onStop() } catch (e: Exception) {
            Log.w(TAG, "onProcessStop failed: ${e.message}")
        }
    }

    override val requiresProcessMapStart: Boolean = true
}
