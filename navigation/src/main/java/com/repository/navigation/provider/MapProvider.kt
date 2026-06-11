package com.repository.navigation.provider

import android.content.Context
import com.repository.navigation.JourneyLocationSource

/**
 * Aggregate of all map concerns for one provider. Stateless members (routing,
 * geocoder, placeSearch, placeSuggest) are exposed as properties; stateful ones
 * (interactive map, minimap source, location engine) are created per-consumer via
 * factory methods so each NavigationFragment / MapBitmapStreamer / NavigationManager
 * gets its own instance.
 *
 * init() is the ONLY place a provider may do SDK initialization (the Yandex impl
 * calls MapKitFactory.setApiKey/initialize there; the Google impl does no Yandex
 * work). Linking a provider must never init an SDK -- keep all native objects lazy.
 */
interface MapProvider {
    val id: String

    val routing: RoutingEngine
    val geocoder: Geocoder
    val placeSearch: PlaceSearch
    val placeSuggest: PlaceSuggest

    fun createMinimapImageSource(context: Context): MinimapImageSource

    /**
     * Build an [InteractiveMap]. [locationEngine] is the SAME engine the journey
     * uses (created via [createLocationEngine]); N10: Google has no global hook to
     * move the map dot during a mock journey, so GoogleInteractiveMap subscribes
     * to this engine's GeoFix stream and draws its own user marker. Yandex ignores
     * it (UserLocationLayer already follows the active LocationManager/simulator).
     */
    fun createInteractiveMap(context: Context, locationEngine: LocationEngine): InteractiveMap

    /**
     * Build a [LocationEngine] over the per-journey [source]. Yandex wraps the
     * source's real<->simulator swap; Google ignores the source and drives a
     * FusedLocation client + a synthetic mock ticker.
     */
    fun createLocationEngine(source: JourneyLocationSource): LocationEngine

    /** Perform any SDK init (key + factory). Idempotent. */
    fun init(context: Context)

    /** Process-level lifecycle. Yandex pumps MapKitFactory.onStart/onStop here. */
    fun onProcessStart()
    fun onProcessStop()

    /** True if this provider needs process-level map start/stop (Yandex only). */
    val requiresProcessMapStart: Boolean
}
