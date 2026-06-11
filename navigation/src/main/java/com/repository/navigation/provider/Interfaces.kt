package com.repository.navigation.provider

import android.graphics.Rect
import android.view.ViewGroup
import com.repository.navigation.ReverseGeocodeResult
import com.repository.navigation.model.RouteAlternative
import com.repository.navigation.model.RouteInfo
import com.repository.navigation.model.TransitSection
import com.repository.navigation.model.TransportMethodInfo
import com.repository.navigation.model.TransportMode
import com.repository.navigation.ui.SuggestItem
import com.yandex.mapkit.geometry.Point
import org.json.JSONObject

/**
 * Provider-neutral routing engine. Signatures mirror the original RouteEngine
 * object call sites exactly. All geometry is carried as
 * com.yandex.mapkit.geometry.Point, which is constructible without MapKit init,
 * so it stays the neutral lat/lng currency for both providers.
 */
interface RoutingEngine {
    fun queryAllModes(
        from: Point,
        to: Point,
        callback: (List<TransportMethodInfo>) -> Unit
    )

    fun buildRoute(
        from: Point,
        to: Point,
        waypoints: List<Point>,
        mode: TransportMode,
        callback: (RouteInfo?) -> Unit
    )

    fun rebuildWithWaypoint(
        currentRoute: RouteInfo,
        waypoint: Point,
        callback: (RouteInfo?) -> Unit
    )

    fun queryMode(
        from: Point,
        to: Point,
        mode: TransportMode,
        callback: (TransportMethodInfo?) -> Unit
    )

    fun buildRouteAlternatives(
        from: Point,
        to: Point,
        mode: TransportMode,
        waypoints: List<Point> = emptyList(),
        departureTime: java.util.Date? = null,
        callback: (List<RouteAlternative>) -> Unit
    )

    /** Touch any lazy/native state to force initialization on the main thread. */
    fun warmUp()
}

/** Provider-neutral geocoder. Returns the existing ReverseGeocodeResult model. */
interface Geocoder {
    fun geocode(address: String, callback: (Point?) -> Unit)
    fun reverseGeocode(point: Point, callback: (ReverseGeocodeResult?) -> Unit)
}

/**
 * Provider-neutral POI search. Result JSON keeps the existing
 * { "places": [ { name, address, category, lat, lng, distanceMeters } ] } schema.
 */
interface PlaceSearch {
    fun search(
        query: String,
        center: Point,
        radiusMeters: Double = 1000.0,
        callback: (JSONObject) -> Unit
    )
}

/** Provider-neutral autocomplete for the destination-edit dropdown. */
interface PlaceSuggest {
    fun suggest(
        query: String,
        center: Point,
        callback: (List<SuggestItem>) -> Unit
    )

    /** Release any provider-held suggest session. */
    fun reset()
}

/**
 * Async source of the 600x300 north-up minimap bitmap streamed to the glasses.
 * Render is async (callback) because the Yandex offscreen capture is inherently
 * deferred (camera move -> MapLoadedListener -> captureScreenshot) and Google's
 * path is HTTP. Route state is held on the source (setRoute/setRouteSections/
 * clearRoute) so MapBitmapStreamer only asks for "a centered bitmap".
 */
interface MinimapImageSource {
    /**
     * Provider-specific integer zoom range for the pedestrian minimap. The glasses
     * slider is an abstract 0..1 fraction; the streamer maps that fraction into
     * [minZoom]..[maxZoom] for the ACTIVE provider, so one slider position frames a
     * comparable ground area regardless of which provider is on (Yandex MapKit and
     * Google web-mercator zoom scales are not identical).
     */
    val minZoom: Int
    val maxZoom: Int

    /**
     * True if a single [render] is cheap enough to call continuously (~12/sec) --
     * e.g. a local GPU offscreen capture (Yandex). False for providers where each
     * render is a paid/rate-limited network fetch (Google Static Maps HTTP GET); the
     * streamer throttles those to a movement-OR-time gate to avoid cost/quota/429s.
     */
    val isCheapPerFrame: Boolean

    /** Begin a render session (e.g. create the offscreen window). Call on MAIN. */
    fun start()

    /** Tear down the render session. Call on MAIN. */
    fun stop()

    /**
     * Render a north-up bitmap centered on [centerLat],[centerLng] at the given
     * integer web-mercator [zoomLevel]. Delivers the bitmap (or null on failure)
     * via [callback] on the MAIN thread. Width/height contract = 600x300.
     */
    fun render(
        centerLat: Double,
        centerLng: Double,
        zoomLevel: Int,
        callback: (android.graphics.Bitmap?) -> Unit
    )

    fun setRoute(points: List<Point>)
    fun setRouteSections(sections: List<TransitSection>, fallbackPoints: List<Point> = emptyList())
    fun clearRoute()
}

/**
 * Provider-neutral interactive phone map. Wraps the concrete map view, hides
 * all SDK lifecycle so the host fragment carries zero provider imports.
 *
 * Full Android view lifecycle is forwarded (onCreate/onStart/onResume/onPause/
 * onStop/onLowMemory/onDestroy) because Google's MapView REQUIRES every one of
 * these to be pumped or it leaks / never renders. Yandex's MapView only needs
 * onStart/onStop, so the Yandex impl makes the rest no-ops.
 *
 * TODO(iteration 2): GoogleInteractiveMap.getMapAsync resolves the GoogleMap
 * asynchronously. Any map op called before onMapReady (addRoute / addTransit /
 * moveCamera / moveCameraToFit / setDestination / setMyLocationEnabled) MUST be
 * buffered and replayed once the map is ready, or the first route/camera/marker
 * will be silently dropped. This is an impl detail of GoogleInteractiveMap only;
 * it requires NO change to this interface.
 */
interface InteractiveMap {
    /** Inflate/attach the concrete map view into [container]. */
    fun attach(container: ViewGroup)

    fun onCreate(savedInstanceState: android.os.Bundle?)
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onLowMemory()
    fun onDestroy()

    fun setMyLocationEnabled(enabled: Boolean)
    fun updateHeading(deg: Float)

    fun addRoute(points: List<Point>)
    fun addTransit(sections: List<TransitSection>)
    fun clearRoute()

    fun setDestination(point: Point, label: String?, icon: android.graphics.Bitmap?)
    fun clearDestination()

    fun moveCamera(point: Point, zoom: Float, insets: Rect)
    fun moveCameraToFit(points: List<Point>, insets: Rect)
    /**
     * Enable/disable user-follow mode. While enabled the map centres the camera on
     * the SAME location fix that draws the user marker (atomically, on each fix) so
     * the marker stays centred above the bottom sheet. [zoom] is the follow zoom and
     * [insets] carries the bottom-sheet offset. Disabling stops auto-centring.
     */
    fun setFollowMode(enabled: Boolean, zoom: Float, insets: Rect)
    fun zoomIn()
    fun zoomOut()

    fun setOnMapTap(listener: ((Point) -> Unit)?)
    fun setOnMapLongTap(listener: ((Point) -> Unit)?)
}

/**
 * Provider-neutral location stream + mock simulator. All callbacks on MAIN.
 * [requestSingleFix] must work WITHOUT a prior subscribe (used by restoreSession
 * and the ADB single-fix). Implementations key adapters on listener identity so
 * the real<->mock swap keeps subscriber identity stable.
 */
interface LocationEngine {
    fun subscribe(listener: GeoFixListener)
    fun unsubscribe(listener: GeoFixListener)

    fun enableMock(polyline: List<Point>, speedMps: Double, onFinished: () -> Unit)
    fun disableMock()
    val mockActive: Boolean

    fun requestSingleFix(callback: (GeoFix?) -> Unit)
}
