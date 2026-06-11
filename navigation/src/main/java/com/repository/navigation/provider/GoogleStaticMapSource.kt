package com.repository.navigation.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.repository.navigation.BuildConfig
import com.repository.navigation.model.TransitSection
import com.yandex.mapkit.geometry.Point
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * MinimapImageSource backed by the Google Maps Static API.
 *
 * Renders the offscreen minimap bitmap streamed to the AR glasses HUD. The returned
 * bitmap covers the EXACT logical web-mercator extent produced by
 * YandexMinimapImageSource.render() (a 600x300 logical request, north-up) so the
 * downstream arrow math in MapBitmapStreamer stays aligned. It is returned at the full
 * scale=2 pixel density (1200x600) -- the shared MapBitmapStreamer OUTPUT contract -- so
 * no resolution is thrown away before the bitmap reaches the glasses warp.
 *
 * Known limitations vs the Yandex offscreen renderer:
 *  - The Static API draws a non-removable Google logo in the corner.
 *  - The Static API is strictly top-down (no tilt); Yandex uses a tilted camera. The
 *    orchestrator is aware that arrow calibration may differ for this provider.
 *  - Night styling is best-effort via the `style=` JSON-style parameters; it cannot be
 *    a pixel match to Yandex's dark theme.
 */
class GoogleStaticMapSource(private val context: Context) : MinimapImageSource {

    // Google web-mercator pedestrian zoom range: 13 (district) .. 19 (building).
    override val minZoom: Int = 13
    override val maxZoom: Int = 19

    // Each render is a paid/rate-limited Static Maps HTTP GET: NOT safe to call every
    // tick. The streamer throttles this provider to a movement-OR-time gate.
    override val isCheapPerFrame: Boolean = false

    // Route state mirrors YandexMinimapImageSource: sections take precedence, then
    // fallbackPoints, then plain routePoints.
    private var routePoints: List<Point> = emptyList()
    private var routeSections: List<TransitSection> = emptyList()
    private var fallbackPoints: List<Point> = emptyList()

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var httpClient: OkHttpClient? = null

    @Volatile
    private var cancelled: Boolean = false

    companion object {
        private const val TAG = "GoogleStaticMapSource"

        // LOGICAL size request to the Static API. This defines the web-mercator extent
        // and zoom semantics and MUST equal the logical extent of every other provider
        // (MapBitmapStreamer.CROP_WIDTH/CROP_HEIGHT) so the arrow stays aligned.
        private const val WIDTH = 600
        private const val HEIGHT = 300
        // scale=2 makes the Static API return a 2x pixel-density image (1200x600 for a
        // 600x300 size request) covering the SAME extent. We return those native pixels
        // as-is -- they match the shared OUTPUT contract -- instead of downscaling, so the
        // glasses perspective warp samples a dense bitmap and the map stops looking pixelated.
        private const val SCALE = 2
        // Final returned pixel resolution = the scale=2 native pixels (no downscale).
        private const val OUT_WIDTH = WIDTH * SCALE
        private const val OUT_HEIGHT = HEIGHT * SCALE
        private const val DEFAULT_ZOOM = 16

        // YandexMinimapImageSource draws every route polyline in solid white.
        // Match that intent: white, with a zoom-scaled weight (see weightForZoom).
        private const val COLOR_ROUTE = 0xFFFFFFFF.toInt()
        // Route weight scales with zoom across [ROUTE_WEIGHT_MIN..ROUTE_WEIGHT_MAX]
        // over the provider zoom range so the line stays proportional: thin when
        // zoomed out (dense streets), thicker when zoomed in.
        private const val ROUTE_WEIGHT_MIN = 1
        private const val ROUTE_WEIGHT_MAX = 4

        private const val STATIC_MAP_BASE = "https://maps.googleapis.com/maps/api/staticmap"
    }

    override fun start() {
        cancelled = false
        if (httpClient == null) {
            httpClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    override fun stop() {
        cancelled = true
        httpClient?.dispatcher?.cancelAll()
        httpClient = null
    }

    override fun setRoute(points: List<Point>) {
        routePoints = points
        routeSections = emptyList()
        fallbackPoints = emptyList()
    }

    override fun setRouteSections(sections: List<TransitSection>, fallbackPoints: List<Point>) {
        routeSections = sections
        this.fallbackPoints = fallbackPoints
        routePoints = emptyList()
    }

    override fun clearRoute() {
        routePoints = emptyList()
        routeSections = emptyList()
        fallbackPoints = emptyList()
    }

    override fun render(centerLat: Double, centerLng: Double, zoomLevel: Int, callback: (Bitmap?) -> Unit) {
        val client = httpClient
        if (client == null) {
            Log.e(TAG, "render called before start; httpClient is null")
            deliver(callback, null)
            return
        }
        if (cancelled) {
            Log.w(TAG, "render called after stop; ignoring")
            deliver(callback, null)
            return
        }

        val zoom = if (zoomLevel > 0) zoomLevel else DEFAULT_ZOOM
        val url = buildUrl(centerLat, centerLng, zoom)

        val request = Request.Builder().url(url).get().build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Static map request failed: ${e.message}")
                deliver(callback, null)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "Static map HTTP ${resp.code}")
                        deliver(callback, null)
                        return
                    }
                    val bytes = resp.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        Log.e(TAG, "Static map response body empty")
                        deliver(callback, null)
                        return
                    }
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (decoded == null) {
                        Log.e(TAG, "Failed to decode static map bytes")
                        deliver(callback, null)
                        return
                    }
                    // Guarantee the output contract: exactly OUT_WIDTH x OUT_HEIGHT,
                    // north-up, covering the WIDTH x HEIGHT logical extent. The scale=2
                    // response (OUT_WIDTH x OUT_HEIGHT) is returned untouched -- no downscale,
                    // no resolution loss. Any unexpected size is scaled up/down to the
                    // contract (do NOT crop -- cropping would discard geography).
                    val out = when {
                        decoded.width == OUT_WIDTH && decoded.height == OUT_HEIGHT -> decoded
                        else -> cropOrScaleTo(decoded, OUT_WIDTH, OUT_HEIGHT)
                    }
                    Log.i(TAG, "render: fetched ${decoded.width}x${decoded.height} returned ${out.width}x${out.height}")
                    deliver(callback, out)
                }
            }
        })
    }

    private fun deliver(callback: (Bitmap?) -> Unit, bitmap: Bitmap?) {
        mainHandler.post { callback(bitmap) }
    }

    private fun buildUrl(centerLat: Double, centerLng: Double, zoom: Int): String {
        val sb = StringBuilder(STATIC_MAP_BASE)
        sb.append("?center=").append(centerLat).append(',').append(centerLng)
        sb.append("&zoom=").append(zoom)
        sb.append("&size=").append(WIDTH).append('x').append(HEIGHT)
        sb.append("&scale=").append(SCALE)
        sb.append("&maptype=roadmap")
        // North-up: no heading/rotation param exists on the Static API; it is always
        // north-up and top-down, matching the Yandex azimuth=0 contract.

        // Best-effort dark/night styling to approximate the HUD look. The Static API
        // cannot reproduce the exact Yandex dark theme.
        for (style in NIGHT_STYLES) {
            sb.append("&style=").append(encode(style))
        }

        // Route paths. Precedence matches Yandex drawRoute(): sections > fallback > route.
        appendRoutePaths(sb, zoom)

        sb.append("&key=").append(encode(BuildConfig.GOOGLE_MAPS_API_KEY))
        return sb.toString()
    }

    private fun appendRoutePaths(sb: StringBuilder, zoom: Int) {
        // Precedence mirrors Yandex setRouteSections(): sections first, then
        // fallbackPoints, then the plain setRoute() points. All drawn white (the
        // Yandex offscreen renderer ignores per-line colour and uses solid white).
        val weight = weightForZoom(zoom)
        if (routeSections.isNotEmpty()) {
            for (section in routeSections) {
                if (section.polylinePoints.size < 2) continue
                appendPath(sb, section.polylinePoints, COLOR_ROUTE, weight)
            }
        } else if (fallbackPoints.size >= 2) {
            appendPath(sb, fallbackPoints, COLOR_ROUTE, weight)
        } else if (routePoints.size >= 2) {
            appendPath(sb, routePoints, COLOR_ROUTE, weight)
        }
    }

    /**
     * Linearly map the integer [zoom] within [minZoom]..[maxZoom] to a Static API
     * path weight in [ROUTE_WEIGHT_MIN]..[ROUTE_WEIGHT_MAX]. Zoomed out -> thin so
     * the route doesn't swamp the street grid; zoomed in -> thicker.
     */
    private fun weightForZoom(zoom: Int): Int {
        val span = (maxZoom - minZoom).coerceAtLeast(1)
        val t = ((zoom - minZoom).toFloat() / span).coerceIn(0f, 1f)
        return Math.round(ROUTE_WEIGHT_MIN + t * (ROUTE_WEIGHT_MAX - ROUTE_WEIGHT_MIN))
    }

    private fun appendPath(sb: StringBuilder, points: List<Point>, color: Int, weight: Int) {
        val encoded = encodePolyline(points)
        if (encoded.isEmpty()) return
        // Static API path colour is 0xRRGGBBAA; convert from Android 0xAARRGGBB.
        val colorParam = "0x" + toRrggbbaa(color)
        val pathValue = "weight:$weight|color:$colorParam|enc:$encoded"
        sb.append("&path=").append(encode(pathValue))
    }

    /**
     * High-quality (bilinear) scale of an unexpected-size response to the target
     * resolution. Used only as a fallback when the API does not return the expected
     * scale=2 frame. Always scales (never crops) so the full geographic extent is
     * preserved and the arrow stays aligned.
     */
    private fun cropOrScaleTo(src: Bitmap, w: Int, h: Int): Bitmap {
        return try {
            val scaled = Bitmap.createScaledBitmap(src, w, h, true)
            if (scaled !== src) src.recycle()
            scaled
        } catch (e: Exception) {
            Log.e(TAG, "cropOrScaleTo failed: ${e.message}")
            src
        }
    }

    // ---- Helpers ----

    private fun toRrggbbaa(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return String.format("%02x%02x%02x%02x", r, g, b, a)
    }

    private fun encode(value: String): String {
        return try {
            java.net.URLEncoder.encode(value, "UTF-8")
        } catch (e: Exception) {
            value
        }
    }

    /**
     * Google encoded-polyline encoding, precision 1e5.
     * https://developers.google.com/maps/documentation/utilities/polylinealgorithm
     */
    private fun encodePolyline(points: List<Point>): String {
        if (points.isEmpty()) return ""
        val result = StringBuilder()
        var prevLat = 0L
        var prevLng = 0L
        for (p in points) {
            val lat = Math.round(p.latitude * 1e5)
            val lng = Math.round(p.longitude * 1e5)
            encodeSignedValue(lat - prevLat, result)
            encodeSignedValue(lng - prevLng, result)
            prevLat = lat
            prevLng = lng
        }
        return result.toString()
    }

    private fun encodeSignedValue(value: Long, output: StringBuilder) {
        var v = value shl 1
        if (value < 0) {
            v = v.inv()
        }
        while (v >= 0x20) {
            output.append(((0x20 or (v.toInt() and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        output.append((v.toInt() + 63).toChar())
    }
}

/**
 * Best-effort night/dark style descriptors for the Static API. Each becomes one
 * `style=` query param. This approximates, but cannot exactly match, the Yandex
 * dark HUD theme.
 */
private val NIGHT_STYLES = listOf(
    "element:geometry|color:0x242f3e",
    // Hide ALL text labels (street names etc) and ALL POI markers/icons: on the tiny
    // monochrome waveguide they are illegible clutter that obscures the route.
    "element:labels|visibility:off",
    "feature:poi|visibility:off",
    "feature:transit|element:labels|visibility:off",
    "feature:road|element:geometry|color:0x38414e",
    "feature:road|element:geometry.stroke|color:0x212a37",
    "feature:water|element:geometry|color:0x17263c"
)
