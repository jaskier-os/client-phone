package com.repository.navigation.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.repository.navigation.model.TransitSection
import com.repository.navigation.model.TransitSectionType
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.MapType
import com.yandex.mapkit.map.OffscreenMapWindow
import com.yandex.mapkit.map.PolylineMapObject

/**
 * Yandex implementation of [MinimapImageSource]. Owns the OffscreenMapWindow and
 * the route polylines drawn on it. Renders by moving the camera, waiting for
 * MapLoadedListener, capturing a screenshot, and center-cropping to 600x300.
 *
 * Note: keeps the historical camera tilt = 40f so the rendered frame matches the
 * previous in-MapBitmapStreamer behavior exactly. The arrow mercator math lives
 * in MapBitmapStreamer and is unchanged for the Yandex provider.
 */
class YandexMinimapImageSource(
    private val context: Context
) : MinimapImageSource {

    // Yandex MapKit zoom range (unchanged -- its existing scales were already good).
    override val minZoom: Int = 4
    override val maxZoom: Int = 19

    // Local GPU offscreen capture: cheap to render continuously at the streamer tick.
    override val isCheapPerFrame: Boolean = true

    // Current route stroke width, scaled to the active zoom (see strokeWidthForZoom).
    // A fixed pixel width looks too thick when zoomed out (dense streets) and too
    // thin when zoomed in; scaling keeps the route proportional to street widths.
    @Volatile private var currentRouteWidth: Float = ROUTE_WIDTH_DEFAULT

    companion object {
        private const val TAG = "YandexMinimapSource"
        // Offscreen window must be larger than the scaled crop (CROP_WIDTH*SCALE_FACTOR
        // = 1200) so the centered crop has non-negative origin. Original design kept
        // ~1.33x headroom over the crop (800 over 600); preserve that at 2x density.
        private const val MAP_SIZE = 1600
        // CROP_WIDTH/CROP_HEIGHT is the LOGICAL web-mercator extent (in native map pixels
        // at the active zoom) that MapBitmapStreamer's arrow math normalizes against. It
        // MUST equal MapBitmapStreamer.CROP_WIDTH/CROP_HEIGHT (600x300) so the arrow stays
        // aligned across providers.
        private const val CROP_WIDTH = 600
        private const val CROP_HEIGHT = 300
        // Route stroke width scales with zoom across [ROUTE_WIDTH_MIN..ROUTE_WIDTH_MAX]
        // over the provider zoom range. ROUTE_WIDTH_DEFAULT (~5) is the value at the
        // default slider position so the look is unchanged at default zoom.
        private const val ROUTE_WIDTH_MIN = 1.0f
        private const val ROUTE_WIDTH_MAX = 3.5f
        private const val ROUTE_WIDTH_DEFAULT = 3.0f
        // Render the offscreen window at 2x device-pixel density so the captured screenshot
        // (and thus the cropped frame) is CROP_*xSCALE_FACTOR pixels covering the SAME
        // logical extent. This matches the shared OUTPUT contract (1200x600) and feeds the
        // glasses perspective warp a dense bitmap, eliminating waveguide pixelation. Pixel
        // density only -- extent/zoom/center are unchanged, so the arrow does not move.
        private const val SCALE_FACTOR = 2f
        // Render flat top-down (north-up, no tilt). The perspective tilt is now
        // applied uniformly on the glasses (KeystoneFrame) ABOVE the heading-up
        // map rotation, so both providers share one tilt. A non-zero camera tilt
        // here would (a) double up with the glasses keystone and (b) make the
        // frame a non-flat plane, which shears wrong when rotated under the keystone.
        private const val CAMERA_TILT = 0f

        // Hide everything except buildings and roads -- the waveguide displays only
        // green; hiding ground polygons leaves pure black. ALL text captions/labels
        // and POI markers are also hidden: on the tiny monochrome waveguide street-name
        // text and place icons are illegible clutter that obscures the route.
        private const val VOID_LANDSCAPE_STYLE = """
            [
              {"stylers":[{"visibility":"off"}],"elements":"label"},
              {"tags":"poi","stylers":[{"visibility":"off"}]},
              {"tags":"land","stylers":[{"visibility":"off"}]},
              {"tags":"vegetation","stylers":[{"visibility":"off"}]},
              {"tags":"park","stylers":[{"visibility":"off"}]},
              {"tags":"terrain","stylers":[{"visibility":"off"}]},
              {"tags":"urban_area","stylers":[{"visibility":"off"}]},
              {"tags":"residential","stylers":[{"visibility":"off"}]},
              {"tags":"industrial","stylers":[{"visibility":"off"}]},
              {"tags":"cemetery","stylers":[{"visibility":"off"}]},
              {"tags":"hospital","stylers":[{"visibility":"off"}]},
              {"tags":"educational","stylers":[{"visibility":"off"}]},
              {"tags":"recreation","stylers":[{"visibility":"off"}]},
              {"tags":"sand","stylers":[{"visibility":"off"}]},
              {"tags":"beach","stylers":[{"visibility":"off"}]},
              {"tags":"glacier","stylers":[{"visibility":"off"}]},
              {"tags":"marsh","stylers":[{"visibility":"off"}]},
              {"tags":"forest","stylers":[{"visibility":"off"}]},
              {"tags":"water","stylers":[{"visibility":"off"}]},
              {"tags":"region","stylers":[{"visibility":"off"}]},
              {"tags":"country","stylers":[{"visibility":"off"}]},
              {"tags":"transit","stylers":[{"visibility":"off"}]},
              {"tags":"admin","stylers":[{"visibility":"off"}]}
            ]
        """
    }

    private val handler = Handler(Looper.getMainLooper())
    private var offscreenWindow: OffscreenMapWindow? = null
    private val routePolylines: MutableList<PolylineMapObject> = mutableListOf()

    // Pending one-shot render callback, fired from MapLoadedListener.
    private var pendingCallback: ((Bitmap?) -> Unit)? = null
    private var pendingZoom: Int = 16
    private var pendingLat: Double = Double.NaN
    private var pendingLng: Double = Double.NaN

    private val mapLoadedListener = com.yandex.mapkit.map.MapLoadedListener {
        val cb = pendingCallback ?: return@MapLoadedListener
        pendingCallback = null
        captureAndCrop(cb)
    }

    override fun start() {
        if (offscreenWindow != null) return
        try {
            val window = MapKitFactory.getInstance().createOffscreenMapWindow(MAP_SIZE, MAP_SIZE)
            // Render at 2x density: captureScreenshot() returns width*height*scaleFactor px.
            window.mapWindow.setScaleFactor(SCALE_FACTOR)
            window.mapWindow.map.isNightModeEnabled = true
            window.mapWindow.map.mapType = MapType.VECTOR_MAP
            window.mapWindow.map.setAwesomeModelsEnabled(true)
            val styled = window.mapWindow.map.setMapStyle(VOID_LANDSCAPE_STYLE.trimIndent())
            window.mapWindow.map.setMapLoadedListener(mapLoadedListener)
            offscreenWindow = window
            Log.i(TAG, "start: OffscreenMapWindow ${MAP_SIZE}x${MAP_SIZE} scaleFactor=$SCALE_FACTOR styled=$styled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create OffscreenMapWindow: ${e.message}")
        }
    }

    override fun stop() {
        pendingCallback = null
        routePolylines.clear()
        offscreenWindow = null
    }

    override fun render(
        centerLat: Double,
        centerLng: Double,
        zoomLevel: Int,
        callback: (Bitmap?) -> Unit
    ) {
        val window = offscreenWindow
        if (window == null) {
            callback(null)
            return
        }
        pendingLat = centerLat
        pendingLng = centerLng
        pendingZoom = zoomLevel
        pendingCallback = callback
        // Keep the route stroke proportional to the current zoom.
        val w = strokeWidthForZoom(zoomLevel)
        if (w != currentRouteWidth) {
            currentRouteWidth = w
            for (poly in routePolylines) poly.setStrokeWidth(w)
        }
        try {
            window.mapWindow.map.move(
                CameraPosition(Point(centerLat, centerLng), zoomLevel.toFloat(), 0f, CAMERA_TILT)
            )
            // If tiles are already loaded the MapLoadedListener may not re-fire;
            // capture directly as a fallback on the next handler tick.
            handler.post {
                if (pendingCallback != null) {
                    pendingCallback = null
                    captureAndCrop(callback)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "render failed: ${e.message}")
            pendingCallback = null
            callback(null)
        }
    }

    /**
     * Linearly map the integer [zoomLevel] within [minZoom]..[maxZoom] to a route
     * stroke width in [ROUTE_WIDTH_MIN]..[ROUTE_WIDTH_MAX]. Zoomed out -> thin so
     * the route doesn't swamp the dense street grid; zoomed in -> thicker.
     */
    private fun strokeWidthForZoom(zoomLevel: Int): Float {
        val span = (maxZoom - minZoom).coerceAtLeast(1)
        val t = ((zoomLevel - minZoom).toFloat() / span).coerceIn(0f, 1f)
        return ROUTE_WIDTH_MIN + t * (ROUTE_WIDTH_MAX - ROUTE_WIDTH_MIN)
    }

    private fun captureAndCrop(callback: (Bitmap?) -> Unit) {
        val window = offscreenWindow
        if (window == null) {
            callback(null)
            return
        }
        try {
            val screenshot = window.captureScreenshot()
            val shotW = screenshot.width
            val shotH = screenshot.height
            // Desired crop = the CROP_WIDTH x CROP_HEIGHT logical extent at the
            // screenshot's actual pixel density. captureScreenshot() does not always
            // scale its output by SCALE_FACTOR, so derive density from the real
            // screenshot size rather than assuming, and clamp the crop to fit so the
            // origin can never go negative (the "x must be >= 0" crash).
            val density = (shotW.toFloat() / MAP_SIZE).coerceAtLeast(1f)
            val cropW = (CROP_WIDTH * density).toInt().coerceAtMost(shotW)
            val cropH = (CROP_HEIGHT * density).toInt().coerceAtMost(shotH)
            val x = ((shotW - cropW) / 2).coerceAtLeast(0)
            val y = ((shotH - cropH) / 2).coerceAtLeast(0)
            val cropped = Bitmap.createBitmap(screenshot, x, y, cropW, cropH)
            if (cropped !== screenshot) screenshot.recycle()
            Log.i(TAG, "captureAndCrop: screenshot ${shotW}x${shotH} density=$density cropped ${cropW}x${cropH} at ($x,$y)")
            callback(cropped)
        } catch (e: Exception) {
            Log.e(TAG, "captureAndCrop failed: ${e.message}")
            callback(null)
        }
    }

    override fun setRoute(points: List<Point>) {
        setRouteSections(emptyList(), points)
    }

    override fun setRouteSections(sections: List<TransitSection>, fallbackPoints: List<Point>) {
        handler.post {
            val map = offscreenWindow?.mapWindow?.map ?: return@post
            for (p in routePolylines) map.mapObjects.remove(p)
            routePolylines.clear()
            if (sections.isEmpty()) {
                if (fallbackPoints.size < 2) return@post
                val poly = map.mapObjects.addPolyline(Polyline(fallbackPoints)).apply {
                    setStrokeColor(Color.rgb(255, 255, 255))
                    setStrokeWidth(currentRouteWidth)
                }
                routePolylines.add(poly)
                return@post
            }
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
                val poly = map.mapObjects.addPolyline(Polyline(section.polylinePoints)).apply {
                    setStrokeColor(Color.rgb(255, 255, 255))
                    setStrokeWidth(currentRouteWidth)
                    if (sectionUnderground || walkUnderground) {
                        dashLength = 14f
                        gapLength = 6f
                    }
                }
                routePolylines.add(poly)
            }
        }
    }

    override fun clearRoute() {
        handler.post {
            val map = offscreenWindow?.mapWindow?.map ?: return@post
            for (p in routePolylines) map.mapObjects.remove(p)
            routePolylines.clear()
        }
    }
}
