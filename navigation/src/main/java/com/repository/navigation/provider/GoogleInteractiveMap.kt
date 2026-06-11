package com.repository.navigation.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.repository.navigation.R
import com.repository.navigation.model.TransitSection
import com.repository.navigation.model.TransitSectionType
import com.repository.navigation.ui.TransitColors
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.yandex.mapkit.geometry.Point

/**
 * Google Maps implementation of [InteractiveMap]. Owns a programmatic [MapView],
 * the resolved [GoogleMap], route polylines, the destination marker, the camera,
 * and tap/long-tap listeners. This is the single place the phone-side Google Maps
 * SDK is touched; NavigationFragment carries zero com.google.android.gms.* refs.
 *
 * The GoogleMap is NOT ready synchronously: getMapAsync resolves it on a later
 * main-thread tick. Every map op (addRoute/addTransit/setDestination/moveCamera/
 * moveCameraToFit/setMyLocationEnabled/zoom/listeners/heading) is therefore
 * buffered into [pendingOps] while the map is null and replayed in onMapReady.
 * Once ready, ops run immediately.
 *
 * N10 mock user marker: Google has no global hook to move the blue dot during a
 * mock journey, so this class subscribes to the provided [locationEngine] GeoFix
 * stream. While [LocationEngine.mockActive] is true it draws/moves a manual
 * [userMarker] at each fix and disables the SDK my-location layer; otherwise it
 * uses the SDK my-location layer and hides the manual marker. The stream
 * subscription is released in onDestroy.
 *
 * The Maps SDK reads its API key from the app manifest meta-data
 * (com.google.android.geo.API_KEY); no key is touched here.
 *
 * All map operations run on the MAIN thread. GeoFix callbacks already arrive on
 * MAIN per the LocationEngine contract.
 */
class GoogleInteractiveMap(
    private val context: Context,
    private val locationEngine: LocationEngine
) : InteractiveMap, OnMapReadyCallback {

    private companion object {
        const val TAG = "GoogleInteractiveMap"
        const val ROUTE_WIDTH = 14f
        const val USER_ARROW_DP = 36f
    }

    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null

    /** Ops requested before the GoogleMap resolved; replayed in onMapReady. */
    private val pendingOps = ArrayDeque<(GoogleMap) -> Unit>()

    private var routePolyline: Polyline? = null
    private val transitPolylines = mutableListOf<Polyline>()
    private var destinationMarker: Marker? = null

    private var onTap: ((Point) -> Unit)? = null
    private var onLongTap: ((Point) -> Unit)? = null

    // Desired my-location state as last requested by the host. Honored only while
    // not in mock mode; mock mode forces the SDK layer off and uses userMarker.
    private var desiredMyLocation = false

    // Mock user marker state.
    private var userMarker: Marker? = null
    private var userArrowDescriptor: BitmapDescriptor? = null

    // Follow mode: when on, onGeoFix centres the camera on the same fix that draws
    // the marker so the two never desync (independent subscribers receive fixes at
    // different times; centring inside onGeoFix keeps the marker exactly centred).
    @Volatile private var followEnabled = false
    @Volatile private var followZoom = 16f
    @Volatile private var followBottomInsetPx = 0

    // Tracks the previous mockActive value so onGeoFix can reconcile the SDK dot
    // vs. the manual marker exactly on the mock<->real transition edge.
    private var lastMockActive = false
    private var lastFix: GeoFix? = null
    private var compassHeading = 0f

    private val geoFixListener = GeoFixListener { fix -> onGeoFix(fix) }

    // --- Buffering helper ----------------------------------------------------

    /** Run [op] now if the map is ready, else buffer it for onMapReady replay. */
    private fun withMap(op: (GoogleMap) -> Unit) {
        val map = googleMap
        if (map != null) {
            op(map)
        } else {
            pendingOps.addLast(op)
        }
    }

    // --- Conversions ---------------------------------------------------------

    private fun Point.toLatLng() = LatLng(latitude, longitude)
    private fun LatLng.toPoint() = Point(latitude, longitude)

    // --- Lifecycle / attach --------------------------------------------------

    override fun attach(container: ViewGroup) {
        val view = MapView(context)
        container.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        mapView = view
        // Resolve the GoogleMap asynchronously; ops buffer until onMapReady.
        view.getMapAsync(this)
        // Start receiving fixes so the mock marker can render once the map is up.
        locationEngine.subscribe(geoFixListener)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // MapView REQUIRES its own onCreate Bundle. We pass through the host's
        // savedInstanceState; the Maps SDK ignores it if it does not contain a
        // MapView state bundle, so forwarding the raw bundle is safe.
        mapView?.onCreate(savedInstanceState)
    }

    override fun onStart() {
        mapView?.onStart()
    }

    override fun onResume() {
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
    }

    override fun onStop() {
        mapView?.onStop()
    }

    override fun onLowMemory() {
        mapView?.onLowMemory()
    }

    override fun onDestroy() {
        locationEngine.unsubscribe(geoFixListener)
        clearRoute()
        clearDestination()
        userMarker?.remove()
        userMarker = null
        googleMap?.setOnMapClickListener(null)
        googleMap?.setOnMapLongClickListener(null)
        googleMap = null
        pendingOps.clear()
        mapView?.onDestroy()
        mapView = null
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.isBuildingsEnabled = true
        try {
            map.uiSettings.isMyLocationButtonEnabled = false
        } catch (e: SecurityException) {
            Log.w(TAG, "uiSettings access failed: ${e.message}")
        }
        // Wire native click listeners straight through; the per-op buffered
        // setOnMapTap/LongTap below only mutate onTap/onLongTap fields.
        map.setOnMapClickListener { latLng -> onTap?.invoke(latLng.toPoint()) }
        map.setOnMapLongClickListener { latLng -> onLongTap?.invoke(latLng.toPoint()) }

        // Replay buffered ops in submission order.
        while (pendingOps.isNotEmpty()) {
            val op = pendingOps.removeFirst()
            op(map)
        }

        // Reconcile location layer / mock marker with current state.
        applyLocationMode()
    }

    // --- My-location / mock marker ------------------------------------------

    override fun setMyLocationEnabled(enabled: Boolean) {
        desiredMyLocation = enabled
        withMap { applyLocationMode() }
    }

    /**
     * Reconcile the SDK my-location layer and the manual mock marker against the
     * current mock state. In mock mode the SDK layer is forced off and a manual
     * marker is shown; otherwise the SDK layer follows [desiredMyLocation] and
     * the manual marker is hidden.
     */
    private fun applyLocationMode() {
        val map = googleMap ?: return
        if (locationEngine.mockActive) {
            setSdkMyLocation(map, false)
            lastFix?.let { renderUserMarker(it) }
        } else {
            userMarker?.remove()
            userMarker = null
            setSdkMyLocation(map, desiredMyLocation)
        }
    }

    private fun setSdkMyLocation(map: GoogleMap, enabled: Boolean) {
        try {
            @Suppress("MissingPermission")
            map.isMyLocationEnabled = enabled
        } catch (e: SecurityException) {
            // Location permission not granted; leave the layer off.
            Log.w(TAG, "isMyLocationEnabled($enabled) denied: ${e.message}")
        }
    }

    private fun onGeoFix(fix: GeoFix) {
        lastFix = fix
        if (fix.headingDeg != null) compassHeading = fix.headingDeg
        val mockNow = locationEngine.mockActive
        withMap { map ->
            if (mockNow) {
                if (!lastMockActive) {
                    // real -> mock transition: kill the SDK blue dot so we do not
                    // draw the manual marker on top of it (double marker).
                    setSdkMyLocation(map, false)
                }
                renderUserMarker(fix)
            } else if (lastMockActive) {
                // mock -> real transition: drop the manual marker and restore the
                // SDK dot to the host's desired state.
                userMarker?.remove()
                userMarker = null
                setSdkMyLocation(map, desiredMyLocation)
            }
            // Centre on the SAME fix that drew the marker so the two never desync.
            if (followEnabled) {
                val h = mapView?.height ?: 0
                val bottomInset = if (h > 0) followBottomInsetPx.coerceIn(0, h / 2) else 0
                map.setPadding(0, 0, 0, bottomInset)
                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(fix.lat, fix.lng), followZoom)
                )
            }
        }
        lastMockActive = mockNow
    }

    private fun renderUserMarker(fix: GeoFix) {
        val map = googleMap ?: return
        val pos = LatLng(fix.lat, fix.lng)
        val marker = userMarker
        if (marker == null) {
            userMarker = map.addMarker(
                MarkerOptions()
                    .position(pos)
                    .anchor(0.5f, 0.5f)
                    .flat(true)
                    .rotation(compassHeading)
                    .zIndex(2f)
                    .icon(userArrowIcon())
            )
        } else {
            marker.position = pos
            marker.rotation = compassHeading
        }
    }

    /**
     * Directional heading arrow used for the user position. A plain teardrop pin
     * cannot convey travel direction; this flat, rotatable chevron points along the
     * path being followed (the Marker is drawn flat and rotated to the heading).
     * Rasterized once from the vector drawable and cached.
     */
    private fun userArrowIcon(): BitmapDescriptor {
        userArrowDescriptor?.let { return it }
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_nav_user_arrow)
            ?: return BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
        val size = (USER_ARROW_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap).also { userArrowDescriptor = it }
    }

    override fun updateHeading(deg: Float) {
        // During a mock journey the arrow must point along the PATH (the GeoFix
        // bearing toward the next vertex, applied in onGeoFix), not where the phone
        // is physically pointing. Ignore the device compass while mocking so it does
        // not fight the travel-direction heading. Real navigation keeps using the
        // compass for the SDK location layer.
        if (locationEngine.mockActive) return
        compassHeading = deg
    }

    // --- Routes --------------------------------------------------------------

    override fun addRoute(points: List<Point>) {
        if (points.size < 2) return
        withMap { map ->
            clearRoute()
            routePolyline = map.addPolyline(
                PolylineOptions()
                    .addAll(points.map { it.toLatLng() })
                    .color(routeColor())
                    .width(ROUTE_WIDTH)
            )
        }
    }

    private fun routeColor(): Int = try {
        androidx.core.content.ContextCompat.getColor(context, R.color.nav_orange)
    } catch (e: Exception) {
        Color.rgb(0xFF, 0x8C, 0x00)
    }

    override fun addTransit(sections: List<TransitSection>) {
        withMap { map ->
            clearRoute()
            var segmentIndex = 0
            for ((idx, section) in sections.withIndex()) {
                if (section.polylinePoints.size < 2) continue
                val sectionUnderground =
                    section.vehicleType?.equals("underground", ignoreCase = true) == true ||
                        section.type == TransitSectionType.METRO
                val prev = sections.getOrNull(idx - 1)
                val next = sections.getOrNull(idx + 1)
                val prevUnder = prev != null &&
                    (prev.vehicleType?.equals("underground", ignoreCase = true) == true ||
                        prev.type == TransitSectionType.METRO)
                val nextUnder = next != null &&
                    (next.vehicleType?.equals("underground", ignoreCase = true) == true ||
                        next.type == TransitSectionType.METRO)
                val walkUnderground = section.type == TransitSectionType.WALK &&
                    section.isTransfer && (prevUnder || nextUnder)
                val color = if (section.type == TransitSectionType.WALK) {
                    TransitColors.androidColorForSectionType(context, section.type)
                } else {
                    section.lineColor ?: TransitColors.segmentColor(context, segmentIndex++)
                }
                val opts = PolylineOptions()
                    .addAll(section.polylinePoints.map { it.toLatLng() })
                    .color(color)
                    .width(ROUTE_WIDTH)
                val dashed = (section.type == TransitSectionType.WALK &&
                    (section.isTransfer || walkUnderground)) || sectionUnderground
                if (dashed) {
                    opts.pattern(
                        listOf(
                            com.google.android.gms.maps.model.Dash(20f),
                            com.google.android.gms.maps.model.Gap(12f)
                        )
                    )
                }
                transitPolylines.add(map.addPolyline(opts))
            }
        }
    }

    override fun clearRoute() {
        routePolyline?.remove()
        routePolyline = null
        for (p in transitPolylines) p.remove()
        transitPolylines.clear()
    }

    // --- Destination ---------------------------------------------------------

    override fun setDestination(point: Point, label: String?, icon: Bitmap?) {
        withMap { map ->
            destinationMarker?.remove()
            val opts = MarkerOptions().position(point.toLatLng())
            if (label != null) opts.title(label)
            if (icon != null) {
                opts.icon(BitmapDescriptorFactory.fromBitmap(icon))
            }
            destinationMarker = map.addMarker(opts)
        }
    }

    override fun clearDestination() {
        destinationMarker?.remove()
        destinationMarker = null
    }

    // --- Camera --------------------------------------------------------------

    override fun moveCamera(point: Point, zoom: Float, insets: Rect) {
        withMap { map ->
            // Lift the camera target above the bottom sheet so the followed user
            // marker sits in the exposed upper area rather than under the sheet.
            val h = mapView?.height ?: 0
            val bottomInset = if (h > 0) insets.bottom.coerceIn(0, h / 2) else 0
            map.setPadding(0, 0, 0, bottomInset)
            // Instant move (not animateCamera): the follow camera is driven on every
            // location fix, and a multi-second animation cannot keep up with a moving
            // user (the marker drifts off-centre and can leave the visible area).
            // moveCamera snaps the target to the user each fix, keeping it centred.
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(point.toLatLng(), zoom)
            )
        }
    }

    override fun setFollowMode(enabled: Boolean, zoom: Float, insets: Rect) {
        followEnabled = enabled
        followZoom = zoom
        followBottomInsetPx = insets.bottom.coerceAtLeast(0)
        // Snap immediately to the last known fix so enabling follow re-centres at
        // once rather than waiting for the next location update.
        if (enabled) {
            lastFix?.let { fix ->
                withMap { map ->
                    val h = mapView?.height ?: 0
                    val bottomInset = if (h > 0) followBottomInsetPx.coerceIn(0, h / 2) else 0
                    map.setPadding(0, 0, 0, bottomInset)
                    map.moveCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(fix.lat, fix.lng), zoom)
                    )
                }
            }
        }
    }

    override fun moveCameraToFit(points: List<Point>, insets: Rect) {
        if (points.isEmpty()) return
        withMap { map ->
            val builder = LatLngBounds.builder()
            for (p in points) builder.include(p.toLatLng())
            val bounds = builder.build()
            val base = (32 * context.resources.displayMetrics.density).toInt()
            applyFitWhenSized(map, bounds, base, insets.bottom.coerceAtLeast(0))
        }
    }

    /**
     * Fit [bounds] using the dimensioned newLatLngBounds overload + moveCamera
     * (instant). The single-int overload silently no-ops when the GL projection
     * is not ready yet (early in a journey), leaving the camera at the default
     * world view; the width/height overload works without a laid-out projection.
     * If the view has no size yet, defer one layout pass via View.post and retry.
     */
    private fun applyFitWhenSized(map: GoogleMap, bounds: LatLngBounds, requestedPad: Int, bottomInsetPx: Int) {
        val view = mapView ?: return
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) {
            view.post { applyFitWhenSized(map, bounds, requestedPad, bottomInsetPx) }
            return
        }
        // Padding is breathing room only. The bottom sheet's large inset must NOT
        // be fed in as bounds padding: padding near half the view starves the fit
        // and collapses the zoom to a near-world view. Cap it to a small fraction
        // of the smaller dimension so the route fills the frame. Bottom-sheet
        // occlusion is handled separately via map.setPadding below.
        val pad = requestedPad.coerceIn(0, (minOf(w, h) * 0.12).toInt())
        // Offset the visible region for the bottom sheet so the fitted route sits
        // in the exposed upper area rather than under the sheet.
        val bottomInset = bottomInsetPx.coerceIn(0, h / 2)
        map.setPadding(0, 0, 0, bottomInset)
        try {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, w, h, pad))
        } catch (e: RuntimeException) {
            Log.w(TAG, "newLatLngBounds(w=$w,h=$h,pad=$pad) failed: ${e.message}; retrying pad=0")
            try {
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, w, h, 0))
            } catch (e2: RuntimeException) {
                Log.w(TAG, "newLatLngBounds dimensioned(0) failed: ${e2.message}; skipping fit")
            }
        }
    }

    override fun zoomIn() {
        withMap { it.animateCamera(CameraUpdateFactory.zoomIn()) }
    }

    override fun zoomOut() {
        withMap { it.animateCamera(CameraUpdateFactory.zoomOut()) }
    }

    // --- Input listeners -----------------------------------------------------

    override fun setOnMapTap(listener: ((Point) -> Unit)?) {
        onTap = listener
    }

    override fun setOnMapLongTap(listener: ((Point) -> Unit)?) {
        onLongTap = listener
    }
}
