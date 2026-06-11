package com.repository.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.repository.navigation.model.TransitSection
import com.repository.navigation.provider.GeoFix
import com.repository.navigation.provider.GeoFixListener
import com.repository.navigation.provider.LocationEngine
import com.repository.navigation.provider.MinimapImageSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renders north-up minimap bitmaps for the glasses minimap. Provider-neutral: the
 * actual "give me a centered bitmap" work is delegated to a [MinimapImageSource]
 * (Yandex offscreen capture or Google Static Maps). This class keeps the cadence,
 * a single high-quality scale to the OUTPUT contract + WEBP encode (raw bytes), and the
 * arrow mercator math.
 *
 * Two cadences:
 *  - Heavy base bitmap: for CHEAP providers (Yandex GPU capture) re-rendered + encoded
 *    every CAPTURE_INTERVAL_MS tick at the current position (continuous, paced for FPS;
 *    slow ticks drop via the capturing and in-flight-send guards). For NON-cheap
 *    providers (Google Static Maps HTTP) the render is throttled to a movement-OR-time
 *    gate so a paid fetch is not issued every tick. The scale+grayscale+WEBP encode runs
 *    on a dedicated worker thread, off the UI thread.
 *  - Light arrow sample (normX, normY, headingDeg): pushed every ARROW_INTERVAL_MS.
 */
class MapBitmapStreamer(
    private val context: Context,
    private val sendBitmap: (ByteArray) -> Unit,
    private val locationEngine: LocationEngine,
    private val source: MinimapImageSource
) {
    companion object {
        private const val TAG = "MapBitmapStreamer"
        // CROP_WIDTH/CROP_HEIGHT is the LOGICAL web-mercator extent (in native map
        // pixels at the active integer zoom) that every provider must render and that
        // the arrow normalization divides against to produce 0..1 fractions. It is NOT
        // a pixel-resolution knob -- it defines the geographic span + zoom semantics.
        // Changing it would move the arrow and rescale the visible area, so it stays 600x300.
        private const val CROP_WIDTH = 600
        private const val CROP_HEIGHT = 300
        // OUTPUT_WIDTH/OUTPUT_HEIGHT is the SHIPPED pixel resolution of the base frame
        // sent to the glasses. It is DECOUPLED from CROP_* (the mercator extent): it only
        // controls how many pixels we encode for that SAME 2:1 extent. The glasses display
        // the frame in a 330x330 centerCrop ImageView clipped to a 288x144 window, so the
        // 1200x600 we used to ship was ~5.5x more pixels than the waveguide can resolve.
        // We keep the 2:1 aspect (NOT a square): the arrow math in sendArrowSample maps the
        // full CROP_WIDTH/CROP_HEIGHT (2:1) extent onto the shipped frame, and the glasses
        // centerCrop that same 2:1 frame into their square. Center-cropping to a square on
        // the phone would drop the left/right thirds of the extent and misalign the arrow,
        // so we only SHRINK the 2:1 frame -- unambiguously arrow-safe, just fewer bytes.
        // Sized from the on-device UI dump: the visible map window (mapContentView) is
        // 432x216 px. Ship at 448x224 -- at/above the display resolution so the frame is
        // NOT upscaled (upscaling is what made 320x160 blurry), still 2:1 (arrow-safe).
        // At grayscale + q68 a dense Yandex frame is ~8 KB, ~10 FPS over the measured
        // ~78 KB/s map socket -- sharp AND in the target FPS range.
        private const val OUTPUT_WIDTH = 448
        private const val OUTPUT_HEIGHT = 224
        // WEBP lossy quality for the shipped base frame. Tunable: the glasses collapse the
        // frame to monochrome green and view it through a small waveguide window. Keep the
        // RESOLUTION native (sharpness) and trade QUALITY for bytes/FPS: q48 at 448x224 is
        // ~7-8 KB (~10 FPS over the ~78 KB/s map socket) while staying crisp on the
        // monochrome waveguide where fine chroma artifacts are invisible.
        private const val WEBP_QUALITY = 48
        // Frame timer. The base bitmap re-renders every tick at the current position for a
        // smooth continuous map (no movement gate). 80ms targets ~12.5 FPS; the capturing
        // guard naturally drops ticks when a render is slow, and the in-flight send guard
        // drops a frame if the prior socket write has not finished.
        private const val CAPTURE_INTERVAL_MS = 80L
        // Arrow sample (tiny: normX, normY, heading) -- 100ms gives smoother rotation.
        private const val ARROW_INTERVAL_MS = 100L
        // The glasses zoom slider is an abstract 0..1 fraction; the actual integer
        // zoom is derived per-provider from source.minZoom..source.maxZoom. Default
        // fraction 0.8 keeps Yandex at its old default (4 + 0.8*15 = 16) and gives
        // Google a tight pedestrian view (13 + 0.8*6 = ~18).
        const val DEFAULT_ZOOM_FRACTION = 0.8f
        // Throttle for NON-cheap providers (Google Static Maps: each render is a paid
        // HTTP GET). For these we re-render only when the user has moved a meaningful
        // distance OR at least THROTTLED_MIN_INTERVAL_MS has elapsed since the last
        // frame (so a stationary Google user does not refetch every 80ms tick, but the
        // map still refreshes about once a second). Cheap providers (Yandex GPU
        // capture) ignore this and render continuously at the CAPTURE_INTERVAL_MS tick.
        private const val THROTTLED_RECAPTURE_METERS = 8.0
        private const val THROTTLED_MIN_INTERVAL_MS = 1000L
        // How long a travel-direction heading suppresses the compass fallback. Must
        // comfortably exceed the fix cadence (~1s mock / GPS) so a moving user never
        // falls back to the physical-orientation compass between fixes. After this
        // long with no travel update the user is treated as stationary and the
        // compass may take over again.
        private const val TRAVEL_HEADING_HOLD_MS = 5000L
    }

    @Volatile private var active = false
    @Volatile private var currentLat = Double.NaN
    @Volatile private var currentLng = Double.NaN

    // Abstract slider position in [0,1]. Mapped into the active provider's integer
    // zoom range on each render via zoomLevelForFraction().
    @Volatile private var currentZoomFraction: Float = DEFAULT_ZOOM_FRACTION
    private fun zoomLevelForFraction(f: Float): Int {
        val lo = source.minZoom
        val hi = source.maxZoom
        return Math.round(lo + f.coerceIn(0f, 1f) * (hi - lo)).coerceIn(lo, hi)
    }
    private val currentZoomLevel: Int get() = zoomLevelForFraction(currentZoomFraction)
    private val activeZoom: Float get() = currentZoomLevel.toFloat()

    @Volatile private var currentHeadingDeg: Float = 0f

    // Heading authority: while the user is moving, the direction of travel along
    // the route (GPS bearing or movement tangent) owns the heading so the arrow
    // points down the path. The TYPE_ROTATION_VECTOR compass is only a fallback
    // for when the user is standing still (no travel direction to derive). Without
    // this gate the 50Hz compass -- which during a desk-bound mock journey reads
    // the phone's static physical orientation -- overwrites the ~1Hz travel
    // bearing every frame, making the map jump and the arrow ignore the spline.
    @Volatile private var lastTravelHeadingMs: Long = 0L

    @Volatile private var lastCapturedLat: Double? = null
    @Volatile private var lastCapturedLng: Double? = null
    @Volatile private var capturing = false

    // Last time a frame was handed to the encode worker, for the throttled (non-cheap)
    // provider path. Read/written on the UI thread (captureAndSend + render callback).
    private var lastFrameSentMs: Long = 0L

    // In-flight send guard. The encode+send now runs on a worker thread; this guard
    // drops a frame if the previous frame's encode/write has not finished, so a slow
    // link or slow encode can never build a backlog. compareAndSet picks one winner;
    // the finally on the worker resets it.
    private val mapSendInFlight = AtomicBoolean(false)

    // Dedicated worker for the scale + grayscale + WEBP encode + socket write. Keeps
    // that ~1MB-per-frame bitmap work and GC pressure off the UI thread. Created in
    // start(), torn down in stop().
    private var encodeThread: HandlerThread? = null
    private var encodeHandler: Handler? = null

    // Reusable encode scratch, owned by the encode thread (allocate once, not per frame).
    private val grayPaint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }
    private val encodeStream = ByteArrayOutputStream()

    /** Set by NavigationManager to push arrow samples over BT (normX, normY, headingDeg). */
    var sendArrow: ((Float, Float, Float) -> Unit)? = null

    @Volatile var transmitEnabled: Boolean = true

    fun refreshNow() {
        lastCapturedLat = null
        lastCapturedLng = null
        handler.post { if (active) captureAndSend() }
    }

    /** Set the abstract slider position (0..1). Mapped into the active provider's range. */
    fun setZoomFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped == currentZoomFraction) return
        currentZoomFraction = clamped
        Log.i(TAG, "setZoomFraction: $clamped -> level ${zoomLevelForFraction(clamped)} (range ${source.minZoom}..${source.maxZoom})")
        refreshNow()
    }

    fun getZoomFraction(): Float = currentZoomFraction

    private val handler = Handler(Looper.getMainLooper())

    private val locationListener = GeoFixListener { fix -> onFix(fix) }

    private fun onFix(fix: GeoFix) {
        val isFirstFix = currentLat.isNaN() || currentLng.isNaN()
        val prevLat = currentLat
        val prevLng = currentLng
        currentLat = fix.lat
        currentLng = fix.lng

        if (isFirstFix) {
            // Kick an immediate render as soon as the first fix lands.
            handler.post { if (active) captureAndSend() }
        }

        val gpsBearing = fix.headingDeg
        val speed = fix.speedMps ?: 0.0
        if (gpsBearing != null && speed >= 0.3) {
            currentHeadingDeg = gpsBearing
            lastTravelHeadingMs = SystemClock.elapsedRealtime()
        } else if (!prevLat.isNaN() && !prevLng.isNaN()) {
            val moved = haversineMeters(prevLat, prevLng, currentLat, currentLng)
            if (moved >= 1.0) {
                currentHeadingDeg = bearingDeg(prevLat, prevLng, currentLat, currentLng).toFloat()
                lastTravelHeadingMs = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!active) return
            captureAndSend()
            handler.postDelayed(this, CAPTURE_INTERVAL_MS)
        }
    }

    private val arrowRunnable = object : Runnable {
        override fun run() {
            if (!active) return
            sendArrowSample()
            handler.postDelayed(this, ARROW_INTERVAL_MS)
        }
    }

    private var sensorManager: SensorManager? = null
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            // Stationary-only fallback: defer to the travel-direction heading while
            // the user is moving. If a travel bearing was set recently, ignore the
            // compass so it can't fight the route tangent (the desk-mock jitter).
            if (SystemClock.elapsedRealtime() - lastTravelHeadingMs < TRAVEL_HEADING_HOLD_MS) return
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val az = Math.toDegrees(orientation[0].toDouble()).toFloat()
            currentHeadingDeg = ((az % 360f) + 360f) % 360f
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        if (active) return
        active = true

        locationEngine.subscribe(locationListener)

        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager = sm
        sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensor ->
            sm.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }

        source.start()

        val thread = HandlerThread("MapEncode").also { it.start() }
        encodeThread = thread
        encodeHandler = Handler(thread.looper)

        handler.postDelayed(captureRunnable, CAPTURE_INTERVAL_MS)
        handler.postDelayed(arrowRunnable, ARROW_INTERVAL_MS)
    }

    fun setRoute(points: List<com.yandex.mapkit.geometry.Point>) {
        source.setRoute(points)
    }

    fun setRouteSections(
        sections: List<TransitSection>,
        fallbackPoints: List<com.yandex.mapkit.geometry.Point> = emptyList()
    ) {
        source.setRouteSections(sections, fallbackPoints)
    }

    fun clearRoute() {
        source.clearRoute()
    }

    fun stop() {
        active = false
        handler.removeCallbacks(captureRunnable)
        handler.removeCallbacks(arrowRunnable)
        locationEngine.unsubscribe(locationListener)
        sensorManager?.unregisterListener(sensorListener)
        sensorManager = null
        source.stop()
        encodeThread?.quitSafely()
        encodeThread = null
        encodeHandler = null
        mapSendInFlight.set(false)
        lastCapturedLat = null
        lastCapturedLng = null
        Log.i(TAG, "stop")
    }

    fun updateHeading(deg: Float) {
        // Called by NavigationManager with the journey's travel-direction heading
        // (GPS bearing or route-tangent). This is authoritative over the compass,
        // so stamp it as a travel heading to hold off the stationary sensor fallback.
        currentHeadingDeg = deg
        lastTravelHeadingMs = SystemClock.elapsedRealtime()
    }

    private var debugTickCount = 0
    private fun captureAndSend() {
        debugTickCount++
        if (!transmitEnabled) {
            if (debugTickCount % 5 == 1) Log.i(TAG, "captureAndSend: skip (transmitEnabled=false)")
            return
        }
        if (capturing) return
        if (currentLat.isNaN() || currentLng.isNaN()) {
            if (debugTickCount % 5 == 1) Log.i(TAG, "captureAndSend: skip (no fix yet, currentLat=$currentLat)")
            return
        }

        // Throttle non-cheap providers (Google Static Maps: each render is a paid HTTP
        // GET). Render only when the user moved a meaningful distance OR enough time has
        // elapsed since the last frame. Cheap providers (Yandex GPU) render every tick.
        if (!source.isCheapPerFrame) {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastFrameSentMs
            val anchorLat = lastCapturedLat
            val anchorLng = lastCapturedLng
            val movedFar = anchorLat == null || anchorLng == null ||
                haversineMeters(anchorLat, anchorLng, currentLat, currentLng) >= THROTTLED_RECAPTURE_METERS
            if (!movedFar && elapsed < THROTTLED_MIN_INTERVAL_MS) return
        }

        val renderLat = currentLat
        val renderLng = currentLng
        capturing = true
        source.render(renderLat, renderLng, currentZoomLevel) { bitmap ->
            capturing = false
            if (bitmap == null) {
                Log.w(TAG, "render returned null")
                return@render
            }
            // Stamp the throttle timer and arrow-center on the render callback (UI
            // thread), then hand the heavy encode+write to the worker.
            lastFrameSentMs = SystemClock.elapsedRealtime()
            lastCapturedLat = renderLat
            lastCapturedLng = renderLng
            dispatchEncode(bitmap)
        }
    }

    /**
     * UI-thread: reserve the in-flight slot and post the scale+grayscale+WEBP encode +
     * socket write to the encode worker. Drops the frame (recycling the bitmap) if a
     * prior frame is still in flight so no backlog can build.
     */
    private fun dispatchEncode(cropped: Bitmap) {
        val worker = encodeHandler
        if (worker == null || !mapSendInFlight.compareAndSet(false, true)) {
            cropped.recycle()
            return
        }
        worker.post {
            try {
                encodeAndSend(cropped)
            } finally {
                mapSendInFlight.set(false)
            }
        }
    }

    /** Encode-worker thread only. Owns grayPaint + encodeStream + all bitmaps here. */
    private fun encodeAndSend(cropped: Bitmap) {
        try {
            val srcW = cropped.width
            val srcH = cropped.height
            // Single high-quality (bilinear) scale of the 2:1 mercator extent down to the
            // shipped OUTPUT resolution (480x240). Skipped only if the provider already
            // handed us exactly that size.
            val scaled =
                if (srcW == OUTPUT_WIDTH && srcH == OUTPUT_HEIGHT) cropped
                else Bitmap.createScaledBitmap(cropped, OUTPUT_WIDTH, OUTPUT_HEIGHT, true)
            if (cropped !== scaled) cropped.recycle()

            // Grayscale (saturation 0) before encode. The glasses collapse the frame to
            // monochrome green anyway, so dropping chroma is free visually and flattens
            // the WEBP chroma planes for a ~1.5-2x smaller payload. Reuse the hoisted
            // saturation-0 Paint; draw into a fresh bitmap, then recycle the colored one.
            val gray = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888)
            Canvas(gray).drawBitmap(scaled, 0f, 0f, grayPaint)
            scaled.recycle()

            encodeStream.reset()
            gray.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, encodeStream)
            gray.recycle()

            val bytes = encodeStream.toByteArray()
            sendBitmap(bytes)

            // Gate the per-frame log to ~1/sec (the encode runs up to ~12/sec).
            if (debugTickCount % 12 == 1) {
                Log.i(
                    TAG,
                    "encodeAndSend: source ${srcW}x${srcH} output ${OUTPUT_WIDTH}x${OUTPUT_HEIGHT} " +
                        "webp(q$WEBP_QUALITY)=${bytes.size}B"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "encodeAndSend failed: ${e.message}")
        }
    }

    private fun sendArrowSample() {
        if (!transmitEnabled) return
        val centerLat = lastCapturedLat ?: return
        val centerLng = lastCapturedLng ?: return
        val send = sendArrow ?: return
        if (currentLat.isNaN() || currentLng.isNaN()) return

        // Web-Mercator approximation matching the rendered frame at activeZoom.
        val metersPerPixel =
            156543.03392 * cos(Math.toRadians(centerLat)) / 2.0.pow(activeZoom.toDouble())
        val dxMeters = (currentLng - centerLng) * cos(Math.toRadians(centerLat)) * 111320.0
        val dyMeters = (currentLat - centerLat) * 110540.0
        val dxPx = dxMeters / metersPerPixel
        val dyPx = -dyMeters / metersPerPixel

        // Normalize against the LOGICAL extent (CROP_*, native mercator px at this zoom),
        // NOT the shipped pixel resolution (OUTPUT_*). dxPx/dyPx are computed in native
        // mercator pixels via metersPerPixel, so dividing by CROP_* yields the correct
        // 0..1 fraction. Raising OUTPUT_* changes pixel density only and never moves the
        // arrow, since extent/center/zoom are identical to the rendered frame.
        val w = CROP_WIDTH.toDouble()
        val h = CROP_HEIGHT.toDouble()
        val normX = ((w / 2.0 + dxPx) / w).coerceIn(0.0, 1.0).toFloat()
        val normY = ((h / 2.0 + dyPx) / h).coerceIn(0.0, 1.0).toFloat()
        send(normX, normY, currentHeadingDeg)
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
