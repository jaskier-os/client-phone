package com.repository.navigation.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.ViewGroup
import com.repository.navigation.R
import com.repository.navigation.model.TransitSection
import com.repository.navigation.model.TransitSectionType
import com.repository.navigation.ui.TransitColors
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.ScreenPoint
import com.yandex.mapkit.ScreenRect
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.InputListener
import com.yandex.mapkit.map.Map as YMap
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.map.PolylineMapObject
import com.yandex.mapkit.layers.ObjectEvent
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.user_location.UserLocationLayer
import com.yandex.mapkit.user_location.UserLocationObjectListener
import com.yandex.mapkit.user_location.UserLocationView

/**
 * Yandex implementation of [InteractiveMap]. Owns the MapView, UserLocationLayer,
 * InputListener, route polylines, destination marker, and camera. This is the
 * single place the phone-side Yandex map SDK is touched; NavigationFragment must
 * carry zero com.yandex.* references and delegate every map op here.
 *
 * The MapKitFactory.onStart/onStop process-ticking that the fragment used to do
 * is folded into onStart/onStop here.
 */
class YandexInteractiveMap(
    private val context: Context,
    private val locationEngine: LocationEngine
) : InteractiveMap {

    private var mapView: MapView? = null
    private var userLocationLayer: UserLocationLayer? = null
    private var userLocationView: UserLocationView? = null
    private var compassHeading = 0f

    private var routePolyline: PolylineMapObject? = null
    private val transitPolylines = mutableListOf<PolylineMapObject>()

    // Follow mode: while enabled, onGeoFix pans the camera onto each incoming fix
    // (the SDK user-location layer draws the dot off the same LocationManager) so
    // the map actually tracks the user instead of standing still. followInsets lifts
    // the focus rect above the bottom sheet so the dot sits in the exposed area.
    @Volatile private var followEnabled = false
    @Volatile private var followZoom = 16f
    @Volatile private var followInsets = Rect(0, 0, 0, 0)
    private var destinationPlacemark: PlacemarkMapObject? = null

    private val geoFixListener = GeoFixListener { fix -> onGeoFix(fix) }
    @Volatile private var lastFix: GeoFix? = null

    private var onTap: ((Point) -> Unit)? = null
    private var onLongTap: ((Point) -> Unit)? = null

    private val inputListener = object : InputListener {
        override fun onMapTap(map: YMap, point: Point) { onTap?.invoke(point) }
        override fun onMapLongTap(map: YMap, point: Point) { onLongTap?.invoke(point) }
    }

    private val locationObjectListener = object : UserLocationObjectListener {
        override fun onObjectAdded(view: UserLocationView) {
            userLocationView = view
            view.arrow.setDirection(compassHeading)
        }
        override fun onObjectRemoved(view: UserLocationView) { userLocationView = null }
        override fun onObjectUpdated(view: UserLocationView, event: ObjectEvent) {
            view.arrow.setDirection(compassHeading)
        }
    }

    override fun attach(container: ViewGroup) {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.view_yandex_map, container, false) as MapView
        container.addView(view)
        mapView = view
        val map = view.map
        map.isNightModeEnabled = true
        map.addInputListener(inputListener)
        userLocationLayer = MapKitFactory.getInstance().createUserLocationLayer(view.mapWindow)
        userLocationLayer?.isVisible = true
        userLocationLayer?.setObjectListener(locationObjectListener)
        // Drive camera-follow from the same fix stream that moves the dot.
        locationEngine.subscribe(geoFixListener)
    }

    // Yandex MapView only needs onStart/onStop; the rest are no-ops to satisfy the
    // interface (Google's MapView needs the full lifecycle pumped).
    override fun onCreate(savedInstanceState: android.os.Bundle?) {}

    override fun onStart() {
        MapKitFactory.getInstance().onStart()
        mapView?.onStart()
    }

    override fun onResume() {}

    override fun onPause() {}

    override fun onStop() {
        mapView?.onStop()
        MapKitFactory.getInstance().onStop()
    }

    override fun onLowMemory() {}

    override fun onDestroy() {
        locationEngine.unsubscribe(geoFixListener)
        clearRoute()
        clearDestination()
        mapView?.map?.removeInputListener(inputListener)
        userLocationView = null
        userLocationLayer?.setObjectListener(null)
        userLocationLayer = null
        mapView = null
    }

    override fun setMyLocationEnabled(enabled: Boolean) {
        userLocationLayer?.isVisible = enabled
    }

    override fun updateHeading(deg: Float) {
        compassHeading = deg
        userLocationView?.arrow?.setDirection(deg)
    }

    override fun addRoute(points: List<Point>) {
        if (points.size < 2) return
        val map = mapView?.map ?: return
        clearRoute()
        routePolyline = map.mapObjects.addPolyline(Polyline(points)).apply {
            setStrokeColor(androidx.core.content.ContextCompat.getColor(context, R.color.nav_orange))
            setStrokeWidth(5f)
        }
    }

    override fun addTransit(sections: List<TransitSection>) {
        val map = mapView?.map ?: return
        clearRoute()
        var segmentIndex = 0
        for ((idx, section) in sections.withIndex()) {
            if (section.polylinePoints.size < 2) continue
            val sectionUnderground = section.vehicleType?.equals("underground", ignoreCase = true) == true ||
                section.type == TransitSectionType.METRO
            val prev = sections.getOrNull(idx - 1)
            val next = sections.getOrNull(idx + 1)
            val prevUnder = prev != null && (prev.vehicleType?.equals("underground", ignoreCase = true) == true ||
                prev.type == TransitSectionType.METRO)
            val nextUnder = next != null && (next.vehicleType?.equals("underground", ignoreCase = true) == true ||
                next.type == TransitSectionType.METRO)
            val walkUnderground = section.type == TransitSectionType.WALK && section.isTransfer && (prevUnder || nextUnder)
            val color = if (section.type == TransitSectionType.WALK) {
                TransitColors.androidColorForSectionType(context, section.type)
            } else {
                section.lineColor ?: TransitColors.segmentColor(context, segmentIndex++)
            }
            val poly = map.mapObjects.addPolyline(Polyline(section.polylinePoints)).apply {
                setStrokeColor(color)
                setStrokeWidth(5f)
                if (section.type == TransitSectionType.WALK && (section.isTransfer || walkUnderground)) {
                    dashLength = 10f; gapLength = 5f
                } else if (sectionUnderground) {
                    dashLength = 14f; gapLength = 6f
                }
            }
            transitPolylines.add(poly)
        }
    }

    override fun clearRoute() {
        routePolyline?.let { mapView?.map?.mapObjects?.remove(it) }
        routePolyline = null
        for (p in transitPolylines) mapView?.map?.mapObjects?.remove(p)
        transitPolylines.clear()
    }

    override fun setDestination(point: Point, label: String?, icon: Bitmap?) {
        val map = mapView?.map ?: return
        destinationPlacemark?.let { map.mapObjects.remove(it) }
        val provider = icon?.let { com.yandex.runtime.image.ImageProvider.fromBitmap(it) }
        destinationPlacemark = map.mapObjects.addPlacemark().apply {
            geometry = point
            provider?.let { setIcon(it) }
        }
    }

    override fun clearDestination() {
        destinationPlacemark?.let { mapView?.map?.mapObjects?.remove(it) }
        destinationPlacemark = null
    }

    override fun moveCamera(point: Point, zoom: Float, insets: Rect) {
        // Lift the focus rect above the bottom sheet so the followed user marker
        // sits in the exposed upper area rather than under the sheet.
        applyFocusRect(insets)
        mapView?.map?.move(
            CameraPosition(point, zoom, 0f, 0f),
            Animation(Animation.Type.SMOOTH, 0.5f), null
        )
    }

    override fun setFollowMode(enabled: Boolean, zoom: Float, insets: Rect) {
        followEnabled = enabled
        followZoom = zoom
        followInsets = insets
        if (enabled) {
            applyFocusRect(insets)
            // Snap onto the last known fix immediately so enabling follow (or the
            // sheet sliding) re-centres without waiting for the next location tick.
            lastFix?.let { centerOn(it.lat, it.lng) }
        }
    }

    /**
     * Pan the camera onto each incoming fix while following. The user-location layer
     * already moves the dot off the same LocationManager; centring here makes the map
     * track the user and keeps the dot in the focus rect above the bottom sheet.
     */
    private fun onGeoFix(fix: GeoFix) {
        lastFix = fix
        if (!followEnabled) return
        applyFocusRect(followInsets)
        centerOn(fix.lat, fix.lng)
    }

    private fun centerOn(lat: Double, lng: Double) {
        // Instant move (no animation) so a fast mock journey never lags behind and
        // drifts the dot off-centre; the dot and camera stay locked on the same fix.
        mapView?.map?.move(CameraPosition(Point(lat, lng), followZoom, 0f, 0f))
    }

    override fun moveCameraToFit(points: List<Point>, insets: Rect) {
        if (points.isEmpty()) return
        applyFocusRect(insets)
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for (p in points) {
            if (p.latitude < minLat) minLat = p.latitude
            if (p.latitude > maxLat) maxLat = p.latitude
            if (p.longitude < minLon) minLon = p.longitude
            if (p.longitude > maxLon) maxLon = p.longitude
        }
        val centerLat = (minLat + maxLat) / 2.0
        val centerLon = (minLon + maxLon) / 2.0
        val span = maxOf(maxLat - minLat, maxLon - minLon)
        val zoom = when {
            span < 0.005 -> 16f; span < 0.01 -> 15f; span < 0.05 -> 13f
            span < 0.1 -> 12f; span < 0.5 -> 10f; span < 1.0 -> 8f; else -> 6f
        }
        mapView?.map?.move(
            CameraPosition(Point(centerLat, centerLon), zoom, 0f, 0f),
            Animation(Animation.Type.SMOOTH, 0.5f), null
        )
    }

    /** Set the visible focus rect so the camera centers above the bottom sheet. */
    private fun applyFocusRect(insets: Rect) {
        val mv = mapView ?: return
        val mapHeight = mv.height
        val mapWidth = mv.width
        if (mapHeight <= 0 || mapWidth <= 0) return
        val visibleBottom = (mapHeight - insets.bottom).coerceAtLeast(mapHeight / 4)
        mv.mapWindow.focusRect = ScreenRect(
            ScreenPoint(0f, 0f),
            ScreenPoint(mapWidth.toFloat(), visibleBottom.toFloat())
        )
    }

    override fun zoomIn() = zoomBy(1f)
    override fun zoomOut() = zoomBy(-1f)

    private fun zoomBy(delta: Float) {
        val map = mapView?.map ?: return
        val pos = map.cameraPosition
        map.move(
            CameraPosition(pos.target, pos.zoom + delta, pos.azimuth, pos.tilt),
            Animation(Animation.Type.SMOOTH, 0.3f), null
        )
    }

    override fun setOnMapTap(listener: ((Point) -> Unit)?) { onTap = listener }
    override fun setOnMapLongTap(listener: ((Point) -> Unit)?) { onLongTap = listener }
}
