package com.repository.navigation

import android.content.Context
import android.util.Log
import com.repository.navigation.db.NavigationDatabase
import com.repository.navigation.model.JourneySession
import com.repository.navigation.model.RouteAlternative
import com.repository.navigation.model.RouteInfo
import com.repository.navigation.model.TransportMethodInfo
import com.repository.navigation.model.TransportMode
import com.repository.navigation.model.TransitSection
import com.repository.navigation.provider.GeoFix
import com.repository.navigation.provider.GeoFixListener
import com.repository.navigation.provider.LocationEngine
import com.repository.navigation.provider.MapProviders
import com.yandex.mapkit.geometry.Point
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class NavigationManager private constructor(context: Context) {

    enum class State { IDLE, PLANNING, PREVIEW, ACTIVE }

    companion object {
        private const val TAG = "NavigationManager"
        private const val EXPIRY_MULTIPLIER = 2
        // Distance from the densified route polyline that counts as deviation.
        // Raised from 50m and computed against samples spaced every
        // DENSIFY_SPACING_M so a long straight segment doesn't trigger false
        // positives just because its only vertices are far apart.
        private const val DEVIATION_THRESHOLD_M = 150.0
        private const val DENSIFY_SPACING_M = 10.0
        private const val REROUTE_COOLDOWN_MS = 10_000L
        // Upper bound on a session-restore attempt. Guards against a silent location-fix hang
        // leaving restoreInFlight stuck true (which would pin the map engine started forever).
        // MUST stay >= the location engines' single-fix timeout (Google's SINGLE_FIX_TIMEOUT_MS)
        // so a legitimately slow GPS cold-fix is not clipped mid-restore. The route rebuild runs
        // after setState(ACTIVE), under the journey hold, so it is unaffected by this timeout.
        private const val RESTORE_TIMEOUT_MS = 20_000L
        // Master switch: keep auto-reroute disabled while the deviation logic
        // is being tuned. Flip back to true to re-enable.
        private const val AUTO_REROUTE_ENABLED = false

        @Volatile
        private var instance: NavigationManager? = null

        fun getInstance(context: Context): NavigationManager {
            return instance ?: synchronized(this) {
                instance ?: NavigationManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sessionTracker = SessionTracker(context)
    private val db = NavigationDatabase.getInstance(context)

    private val locationSource = JourneyLocationSource()
    // Provider-neutral location engine over the per-journey source. Built from the
    // active provider so a Google journey drives the same downstream listeners.
    // A var (not val) so a runtime provider switch can rebind it -- otherwise the
    // singleton keeps the engine built from the OLD provider while routing/minimap
    // follow the new one (the iteration-1 split-brain blocker).
    // @Volatile: reassigned by rebindProvider() on the main thread, read by journey/streamer code.
    @Volatile
    private var locationEngine: LocationEngine =
        MapProviders.active.createLocationEngine(locationSource)
    // The provider id the cached locationEngine was built from. Used to detect a
    // stale engine after MapProviders.switchTo and rebind it.
    // @Volatile: reassigned by rebindProvider() on the main thread, read by journey/streamer code.
    @Volatile
    private var locationEngineProviderId: String = MapProviders.active.id
    private var locationListener: GeoFixListener? = null

    // True between the start of restoreSession() and the point a session is
    // either restored (-> ACTIVE) or confirmed absent. Treated as NOT idle so a
    // provider switch can't race a session restore (iteration-1 review finding).
    // Backing field + edge-triggered engine hold: a session restore runs a location fix +
    // geocode/route BEFORE state becomes ACTIVE, so the SDK engine must be held for the whole
    // restore window (state is still IDLE during it). Setting this true/false acquires/releases
    // the engine on the edge, regardless of which of the several restore exit paths runs.
    @Volatile private var restoreInFlightBacking: Boolean = false
    private var restoreWatchdog: Job? = null
    private var restoreInFlight: Boolean
        get() = restoreInFlightBacking
        set(value) {
            if (value == restoreInFlightBacking) return
            restoreInFlightBacking = value
            if (value) {
                MapProviders.acquireEngine("session_restore")
                // Watchdog: requestSingleFix can hang silently (no GPS fix, no throw, callback
                // never fires), which would pin restoreInFlight true forever and leak the map
                // engine. Force-clear after a timeout so the engine is always released.
                restoreWatchdog?.cancel()
                restoreWatchdog = scope.launch {
                    delay(RESTORE_TIMEOUT_MS)
                    if (restoreInFlightBacking) {
                        Log.w(TAG, "Session restore timed out after ${RESTORE_TIMEOUT_MS}ms -- clearing")
                        restoreInFlight = false
                    }
                }
            } else {
                MapProviders.releaseEngine("session_restore")
                restoreWatchdog?.cancel()
                restoreWatchdog = null
            }
        }

    /** True while a programmatically-driven mock journey is active. */
    val isMockJourney: Boolean get() = locationEngine.mockActive

    private var currentState: State = State.IDLE
    private var currentSession: JourneySession? = null
    private var currentRoute: RouteInfo? = null
    // Cached densified copy of the active route's polyline, sampled every
    // DENSIFY_SPACING_M so deviation checks stay accurate on long straight
    // segments. Rebuilt whenever currentRoute changes.
    private var densifiedRoute: List<Point> = emptyList()
    private var cachedMethods: Map<String, TransportMethodInfo> = emptyMap()
    private var cachedFrom: Point? = null
    private var cachedTo: Point? = null
    private var previewedRoute: RouteInfo? = null
    private var previewedMethodId: String? = null
    private var selectedAlternative: RouteAlternative? = null

    @Volatile private var rerouting = false
    private var lastRerouteTime = 0L

    // Last position the active LocationListener saw, in either real or mock
    // mode. Exposed via getLastKnownLocation() so the test harness can read
    // sim position without round-tripping through Yandex's single-update API
    // (which doesn't fire reliably while a simulator is the active manager).
    @Volatile private var lastFixLat: Double = Double.NaN
    @Volatile private var lastFixLng: Double = Double.NaN
    @Volatile private var lastFixHeading: Double? = null
    @Volatile private var lastFixSpeed: Double? = null
    @Volatile private var lastFixAtMs: Long = 0L

    private val arrivalDetector = ArrivalDetector {
        Log.i(TAG, "Arrived at destination")
        stopJourney()
        stateListener?.onInstructionChanged("Arrived at destination")
    }

    var stateListener: NavigationStateListener? = null

    /** Set by :app module to send the raw WEBP map frame bytes over BT. Null when glasses not connected. */
    var sendMapBitmap: ((ByteArray) -> Unit)? = null

    /** Set by :app module to send arrow samples (normX, normY, headingDeg). */
    var sendMapArrow: ((Float, Float, Float) -> Unit)? = null

    /** Set by :app module to send device commands to glasses over BT. (type, paramsJson) */
    var sendDeviceCommand: ((String, String) -> Unit)? = null

    private var mapBitmapStreamer: MapBitmapStreamer? = null
    private var mapTransmitEnabled: Boolean = true
    private var currentStepIndex: Int = 0
    private var stepAdvanceConfirmations: Int = 0
    private var pendingStreamOnRouteRebuilt = false
    private var routeRebuildJob: Job? = null

    /**
     * Gate the map bitmap + arrow streaming. Used by the :app module to skip
     * BT transmission when the glasses HUD is dark (off-head or screen off).
     * When toggled on, an immediate base bitmap refresh is requested so the
     * glasses don't show a stale frame.
     */
    fun setMapTransmitEnabled(enabled: Boolean) {
        if (mapTransmitEnabled == enabled) return
        mapTransmitEnabled = enabled
        val streamer = mapBitmapStreamer ?: return
        streamer.transmitEnabled = enabled
        if (enabled) streamer.refreshNow()
    }

    // Previous fix used to derive heading when GPS bearing is missing.
    private var lastHeadingLat: Double = Double.NaN
    private var lastHeadingLng: Double = Double.NaN

    private fun setState(state: State) {
        val prev = currentState
        currentState = state
        // Hold the shared map engine for the whole journey (PLANNING/PREVIEW/ACTIVE) -- a journey
        // can be prepared/started from the glasses or phone while the map tab is hidden, and its
        // geocode/route/minimap work all need the SDK engine running. Release on return to IDLE.
        val wasActive = prev != State.IDLE
        val nowActive = state != State.IDLE
        if (nowActive && !wasActive) MapProviders.acquireEngine("journey")
        else if (!nowActive && wasActive) MapProviders.releaseEngine("journey")
        stateListener?.onStateChanged(state)
    }

    fun getState(): State = currentState

    /**
     * True when it is safe to swap the active map provider: state is IDLE, there
     * is no current session, and no session restore is in flight. The restore
     * gate closes the iteration-1 race where restoreSession() has begun (single
     * fix requested) but state is still IDLE momentarily.
     */
    fun canSwitchProvider(): Boolean =
        currentState == State.IDLE && currentSession == null && !restoreInFlight

    /**
     * Rebind provider-derived state to [MapProviders.active] after a runtime
     * switch. MUST be called only when [canSwitchProvider] is true. Drops and
     * recreates the cached LocationEngine so the next journey, minimap streamer
     * and interactive map all run on the new provider (routing/geocoder/minimap
     * are already resolved per-call from MapProviders.active). No-op if the engine
     * already matches the active provider.
     */
    fun rebindProvider() {
        if (locationEngineProviderId == MapProviders.active.id) return
        // We only switch while IDLE, so no listener should be subscribed; stop the
        // old engine defensively in case a stray subscriber remained.
        locationListener?.let { locationEngine.unsubscribe(it) }
        locationListener = null
        if (locationEngine.mockActive) locationEngine.disableMock()
        locationEngine = MapProviders.active.createLocationEngine(locationSource)
        locationEngineProviderId = MapProviders.active.id
        Log.i(TAG, "Rebound NavigationManager LocationEngine to provider=$locationEngineProviderId")
    }

    fun getCurrentRoute(): RouteInfo? = currentRoute

    private fun setActiveRoute(route: RouteInfo?) {
        currentRoute = route
        densifiedRoute = if (route != null) densifyRoute(route.polylinePoints, DENSIFY_SPACING_M) else emptyList()
    }

    fun getCurrentSession(): JourneySession? = currentSession

    /** Step index the phone last advanced to (the value sent to glasses). */
    fun getCurrentStepIndex(): Int = currentStepIndex

    /** Densified copy of the active polyline used for deviation checks. */
    fun getDensifiedRoute(): List<Point> = densifiedRoute

    /**
     * Set the minimap zoom from the glasses slider's abstract 0..1 fraction. The
     * streamer maps it into the active provider's own zoom range and refreshes.
     * No-op when no streamer is running.
     */
    fun setMinimapZoomFraction(fraction: Float) {
        mapBitmapStreamer?.setZoomFraction(fraction)
    }

    fun getMinimapZoomFraction(): Float =
        mapBitmapStreamer?.getZoomFraction() ?: MapBitmapStreamer.DEFAULT_ZOOM_FRACTION

    data class LastFix(
        val lat: Double, val lng: Double,
        val headingDeg: Double?, val speedMps: Double?,
        val atMs: Long
    )

    /** Latest position the navigation listener saw. Null if no fix yet. */
    fun getLastKnownLocation(): LastFix? {
        if (lastFixLat.isNaN() || lastFixLng.isNaN() || lastFixAtMs == 0L) return null
        return LastFix(lastFixLat, lastFixLng, lastFixHeading, lastFixSpeed, lastFixAtMs)
    }

    fun planJourney(from: Point, to: Point, callback: (List<TransportMethodInfo>) -> Unit) {
        setState(State.PLANNING)
        cachedFrom = from
        cachedTo = to
        MapProviders.active.routing.queryAllModes(from, to) { methods ->
            cachedMethods = methods.associateBy { it.methodId }
            stateListener?.onMethodsReady(methods)
            callback(methods)
        }
    }

    fun planJourneyAlternatives(
        from: Point, to: Point, mode: TransportMode,
        waypoints: List<Point> = emptyList(),
        departureTime: java.util.Date? = null,
        callback: (List<RouteAlternative>) -> Unit
    ) {
        cachedFrom = from
        cachedTo = to
        // Routing uses the native SDK engine. This can be called from the route-options screen
        // while state is still IDLE (no journey holding the engine), so hold it for the query.
        MapProviders.acquireEngine("route_alternatives")
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val release = { if (released.compareAndSet(false, true)) MapProviders.releaseEngine("route_alternatives") }
        MapProviders.active.routing.buildRouteAlternatives(from, to, mode, waypoints, departureTime) { alternatives ->
            release()
            stateListener?.onRouteAlternativesReady(mode, alternatives)
            callback(alternatives)
        }
    }

    fun selectAlternative(alternative: RouteAlternative) {
        selectedAlternative = alternative
        setActiveRoute(alternative.routeInfo)
        val methodId = alternative.alternativeId
        val methodInfo = TransportMethodInfo(
            methodId = methodId,
            mode = alternative.mode,
            etaSeconds = alternative.routeInfo.etaSeconds,
            etaFormatted = alternative.routeInfo.etaFormatted,
            description = alternative.summary,
            distanceMeters = alternative.routeInfo.distanceMeters
        )
        cachedMethods = cachedMethods + (methodId to methodInfo)
        previewedRoute = alternative.routeInfo
        previewedMethodId = methodId
    }

    fun prepareJourney(from: Point, to: Point, mode: TransportMode, callback: (RouteInfo?) -> Unit) {
        setState(State.PLANNING)
        cachedFrom = from
        cachedTo = to
        MapProviders.active.routing.buildRoute(from, to, emptyList(), mode) { route ->
            if (route == null) {
                Log.e(TAG, "Failed to build route for mode $mode")
                setState(State.IDLE)
                callback(null)
                return@buildRoute
            }
            setActiveRoute(route)
            val methodId = "${mode.name.lowercase()}_0"
            val methodInfo = TransportMethodInfo(
                methodId = methodId,
                mode = mode,
                etaSeconds = route.etaSeconds,
                etaFormatted = route.etaFormatted,
                description = "${mode.name}: ${route.distanceFormatted}, ${route.etaFormatted}",
                distanceMeters = route.distanceMeters
            )
            cachedMethods = mapOf(methodId to methodInfo)
            stateListener?.onMethodsReady(listOf(methodInfo))
            callback(route)
        }
    }

    fun startPreparedJourney(onError: ((String) -> Unit)? = null, callback: (Long, Long) -> Unit) {
        val route = currentRoute
        if (route == null || currentState != State.PLANNING) {
            val msg = "Cannot start: no prepared journey (state=$currentState)"
            Log.e(TAG, msg)
            onError?.invoke(msg)
            return
        }
        val method = cachedMethods.values.firstOrNull()
        if (method == null) {
            val msg = "Cannot start: no cached method"
            Log.e(TAG, msg)
            onError?.invoke(msg)
            return
        }
        val from = cachedFrom
        val to = cachedTo
        if (from == null || to == null) {
            val msg = "Cannot start: missing origin or destination"
            Log.e(TAG, msg)
            onError?.invoke(msg)
            return
        }

        val now = System.currentTimeMillis()
        val etaMs = route.etaSeconds * 1000
        val session = JourneySession(
            from = from,
            to = to,
            transportMode = method.mode,
            methodId = method.methodId,
            estimatedEtaSeconds = route.etaSeconds,
            createdAt = now,
            plannedEndTime = now + etaMs,
            expiryTime = now + EXPIRY_MULTIPLIER * etaMs
        )
        scope.launch {
            val saved = sessionTracker.startSession(session)
            currentSession = saved
            setState(State.ACTIVE)
            startLocationTracking(method.mode)
            arrivalDetector.setDestination(to.latitude, to.longitude)
            stateListener?.onRouteUpdated(route.polylinePoints)
            if (route.instructions.isNotEmpty()) {
                stateListener?.onInstructionChanged(route.instructions.first().text)
            }
            stateListener?.onEtaUpdated(route.etaSeconds)

            // Start streaming map bitmaps to glasses
            startMapStreaming(route)

            callback(saved.id, route.etaSeconds)
        }
    }

    fun chooseMethodAndStart(methodId: String, callback: (Long, Long) -> Unit) {
        val method = cachedMethods[methodId]
        if (method == null) {
            Log.e(TAG, "Unknown methodId: $methodId")
            return
        }
        val from = cachedFrom ?: return
        val to = cachedTo ?: return

        MapProviders.active.routing.buildRoute(from, to, emptyList(), method.mode) { route ->
            if (route == null) {
                Log.e(TAG, "Failed to build route for $methodId")
                setState(State.IDLE)
                return@buildRoute
            }
            beginSession(route, methodId, method.mode, callback)
        }
    }

    fun previewRoute(methodId: String, callback: (RouteInfo?) -> Unit) {
        val method = cachedMethods[methodId]
        if (method == null) {
            Log.e(TAG, "Unknown methodId: $methodId")
            callback(null)
            return
        }
        val from = cachedFrom ?: run { callback(null); return }
        val to = cachedTo ?: run { callback(null); return }

        MapProviders.active.routing.buildRoute(from, to, emptyList(), method.mode) { route ->
            if (route == null) {
                Log.e(TAG, "Failed to build preview route for $methodId")
                callback(null)
                return@buildRoute
            }
            previewedRoute = route
            previewedMethodId = methodId
            setState(State.PREVIEW)
            stateListener?.onRoutePreviewReady(route)
            callback(route)
        }
    }

    fun startFromPreview(callback: (Long, Long) -> Unit) {
        val route = previewedRoute ?: return
        val methodId = previewedMethodId ?: return
        val method = cachedMethods[methodId] ?: return

        previewedRoute = null
        previewedMethodId = null

        beginSession(route, methodId, method.mode, callback)
    }

    private fun beginSession(
        route: RouteInfo,
        methodId: String,
        mode: TransportMode,
        callback: (Long, Long) -> Unit
    ) {
        val from = cachedFrom ?: return
        val to = cachedTo ?: return

        setActiveRoute(route)
        val now = System.currentTimeMillis()
        val etaMs = route.etaSeconds * 1000
        val session = JourneySession(
            from = from,
            to = to,
            transportMode = mode,
            methodId = methodId,
            estimatedEtaSeconds = route.etaSeconds,
            createdAt = now,
            plannedEndTime = now + etaMs,
            expiryTime = now + EXPIRY_MULTIPLIER * etaMs
        )
        // Flip the UI to ACTIVE immediately so the user gets feedback on the
        // first click; the suspending DB write below previously delayed this by
        // ~1s on cold-start, making the button look unresponsive.
        setState(State.ACTIVE)
        startLocationTracking(mode)
        arrivalDetector.setDestination(to.latitude, to.longitude)
        stateListener?.onRouteUpdated(route.polylinePoints)
        if (route.instructions.isNotEmpty()) {
            stateListener?.onInstructionChanged(route.instructions.first().text)
        }
        stateListener?.onEtaUpdated(route.etaSeconds)
        startMapStreaming(route)

        scope.launch {
            if (currentState != State.ACTIVE) return@launch
            val saved = sessionTracker.startSession(session)
            currentSession = saved
            callback(saved.id, route.etaSeconds)
        }
    }

    private fun startMapStreaming(route: RouteInfo, restoring: Boolean = false) {
        val sender = sendMapBitmap ?: return
        mapBitmapStreamer?.stop()
        val minimapSource = MapProviders.active.createMinimapImageSource(appContext)
        val streamer = MapBitmapStreamer(appContext, sender, locationEngine, minimapSource)
        streamer.sendArrow = { x, y, h -> sendMapArrow?.invoke(x, y, h) }
        streamer.transmitEnabled = mapTransmitEnabled
        streamer.start()
        streamer.setRouteSections(route.transitSections, route.polylinePoints)
        mapBitmapStreamer = streamer
        pendingStreamOnRouteRebuilt = false
        sendDeviceCommand?.invoke("nav_minimap_start", "{}")
        sendJourneySteps(route)
        if (!restoring) {
            currentStepIndex = 0
            stepAdvanceConfirmations = 0
        }
        // Emit the initial step index whenever the route carries steps -- transit
        // legs OR turn-by-turn driving/walking instructions. Without this the
        // glasses keep currentNavStepIndex = -1 and never reveal the step revolver
        // (gated on index >= 0), so a driving journey showed the map but no steps.
        if (route.transitSections.isNotEmpty() || route.instructions.isNotEmpty()) {
            emitStepIndex(currentStepIndex)
        }
        Log.i(TAG, "MapBitmapStreamer started for glasses (restoring=$restoring, stepIndex=$currentStepIndex)")
    }

    /**
     * Push the active step index to BOTH the glasses (nav_step_index device
     * command) and the phone UI (onStepChanged). Previously only the glasses were
     * notified, so the phone's active-steps list never re-highlighted the current
     * step as the journey progressed.
     */
    private fun emitStepIndex(index: Int) {
        sendDeviceCommand?.invoke("nav_step_index", JSONObject().put("index", index).toString())
        stateListener?.onStepChanged(index)
    }

    private fun sendJourneySteps(route: RouteInfo) {
        val sender = sendDeviceCommand ?: return
        val stepsJson = routeInstructionsToJson(route)
        sender.invoke("nav_steps", stepsJson.toString())
    }

    fun cancelPreview() {
        previewedRoute = null
        previewedMethodId = null
        val methods = cachedMethods.values.toList()
        if (methods.isNotEmpty()) {
            setState(State.PLANNING)
            stateListener?.onMethodsReady(methods)
        } else {
            setState(State.IDLE)
        }
    }

    fun stopJourney() {
        val session = currentSession ?: return
        routeRebuildJob?.cancel()
        routeRebuildJob = null
        pendingStreamOnRouteRebuilt = false
        mapBitmapStreamer?.stop()
        mapBitmapStreamer = null
        sendDeviceCommand?.invoke("nav_minimap_stop", "{}")
        Log.i(TAG, "MapBitmapStreamer stopped")
        stopLocationTracking()
        arrivalDetector.reset()
        scope.launch {
            sessionTracker.endSession(session.id)
        }
        currentStepIndex = 0
        stepAdvanceConfirmations = 0
        lastHeadingLat = Double.NaN
        lastHeadingLng = Double.NaN
        currentSession = null
        setActiveRoute(null)
        previewedRoute = null
        previewedMethodId = null
        selectedAlternative = null
        cachedMethods = emptyMap()
        cachedFrom = null
        cachedTo = null
        setState(State.IDLE)
    }

    fun modifyJourney(waypointLat: Double, waypointLng: Double, callback: (Long) -> Unit) {
        val route = currentRoute ?: return
        val waypoint = Point(waypointLat, waypointLng)
        MapProviders.active.routing.rebuildWithWaypoint(route, waypoint) { newRoute ->
            if (newRoute == null) {
                Log.e(TAG, "Failed to rebuild route with waypoint")
                return@rebuildWithWaypoint
            }
            setActiveRoute(newRoute)
            currentStepIndex = 0
            stepAdvanceConfirmations = 0
            sendJourneySteps(newRoute)
            if (newRoute.transitSections.isNotEmpty()) {
                emitStepIndex(0)
            }
            val session = currentSession ?: return@rebuildWithWaypoint
            scope.launch {
                val entity = db.journeySessionDao().getById(session.id) ?: return@launch
                val updated = entity.copy(
                    waypointLat = waypointLat,
                    waypointLng = waypointLng,
                    currentStepIndex = 0
                )
                db.journeySessionDao().update(updated)
                currentSession = session.copy(waypoint = waypoint)
                stateListener?.onRouteUpdated(newRoute.polylinePoints)
                stateListener?.onEtaUpdated(newRoute.etaSeconds)
                if (newRoute.instructions.isNotEmpty()) {
                    stateListener?.onInstructionChanged(newRoute.instructions.first().text)
                }
                callback(newRoute.etaSeconds)
            }
        }
    }

    fun getCurrentEta(): Pair<Long, String> {
        val route = currentRoute ?: return Pair(0L, "0 min")
        return Pair(route.etaSeconds, route.etaFormatted)
    }

    fun getJourneyInfo(): JSONObject {
        val json = JSONObject()
        json.put("state", currentState.name)
        when (currentState) {
            State.IDLE -> { /* just state */ }
            State.PLANNING -> {
                cachedTo?.let {
                    json.put("destinationLat", it.latitude)
                    json.put("destinationLng", it.longitude)
                }
                cachedMethods.values.firstOrNull()?.let { method ->
                    json.put("methodId", method.methodId)
                    json.put("transportMode", method.mode.name)
                    json.put("etaSeconds", method.etaSeconds)
                    json.put("etaFormatted", method.etaFormatted)
                    json.put("distanceMeters", method.distanceMeters)
                }
                currentRoute?.let { route ->
                    json.put("steps", routeInstructionsToJson(route))
                }
            }
            State.PREVIEW -> {
                cachedTo?.let {
                    json.put("destinationLat", it.latitude)
                    json.put("destinationLng", it.longitude)
                }
                previewedMethodId?.let { json.put("methodId", it) }
                previewedRoute?.let { route ->
                    json.put("etaSeconds", route.etaSeconds)
                    json.put("etaFormatted", route.etaFormatted)
                    json.put("distanceMeters", route.distanceMeters)
                    json.put("distanceFormatted", route.distanceFormatted)
                    json.put("transportMode", route.mode.name)
                    json.put("steps", routeInstructionsToJson(route))
                }
            }
            State.ACTIVE -> {
                currentSession?.let { session ->
                    json.put("destinationLat", session.to.latitude)
                    json.put("destinationLng", session.to.longitude)
                    json.put("transportMode", session.transportMode.name)
                    json.put("sessionId", session.id)
                    json.put("methodId", session.methodId)
                }
                currentRoute?.let { route ->
                    json.put("etaSeconds", route.etaSeconds)
                    json.put("etaFormatted", route.etaFormatted)
                    json.put("distanceMeters", route.distanceMeters)
                }
            }
        }
        return json
    }

    fun routeInstructionsToJson(route: RouteInfo): JSONArray {
        val arr = JSONArray()
        if (route.transitSections.isNotEmpty()) {
            val sections = route.transitSections
            for ((idx, section) in sections.withIndex()) {
                val undergroundTransit = section.vehicleType?.equals("underground", ignoreCase = true) == true ||
                    (section.type == com.repository.navigation.model.TransitSectionType.METRO)
                val undergroundWalk = section.type == com.repository.navigation.model.TransitSectionType.WALK && run {
                    val prev = sections.getOrNull(idx - 1)
                    val next = sections.getOrNull(idx + 1)
                    val prevUnder = prev != null && (prev.vehicleType?.equals("underground", ignoreCase = true) == true ||
                        prev.type == com.repository.navigation.model.TransitSectionType.METRO)
                    val nextUnder = next != null && (next.vehicleType?.equals("underground", ignoreCase = true) == true ||
                        next.type == com.repository.navigation.model.TransitSectionType.METRO)
                    section.isTransfer && (prevUnder || nextUnder)
                }
                val isUnderground = undergroundTransit || undergroundWalk
                arr.put(JSONObject().apply {
                    put("type", section.type.name)
                    put("durationSeconds", section.durationSeconds)
                    put("durationFormatted", section.durationFormatted)
                    put("distanceMeters", section.distanceMeters)
                    put("distanceFormatted", section.distanceFormatted)
                    put("stopCount", section.stopCount)
                    section.lineName?.let { put("lineName", it) }
                    section.lineShortName?.let { put("lineShortName", it) }
                    if (section.lineShortNames.isNotEmpty()) {
                        put("lineShortNames", JSONArray(section.lineShortNames))
                    }
                    section.lineColor?.let { put("lineColor", String.format("#%06X", it and 0xFFFFFF)) }
                    section.vehicleType?.let { put("vehicleType", it) }
                    section.boardStop?.let { put("boardStop", it) }
                    section.alightStop?.let { put("alightStop", it) }
                    section.direction?.let { put("direction", it) }
                    put("isUnderground", isUnderground)
                })
            }
        } else {
            for (inst in route.instructions) {
                arr.put(JSONObject().apply {
                    put("type", inst.type.name)
                    put("text", inst.text)
                    inst.lineName?.let { put("lineName", it) }
                    inst.stopName?.let { put("stopName", it) }
                })
            }
        }
        return arr
    }

    fun getAvailableMethods(from: Point, to: Point, callback: (List<TransportMethodInfo>) -> Unit) {
        // Called from the AIDL NavigationService while state may be IDLE; hold the SDK engine
        // for the query so the native routers are running.
        MapProviders.acquireEngine("available_methods")
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val release = { if (released.compareAndSet(false, true)) MapProviders.releaseEngine("available_methods") }
        MapProviders.active.routing.queryAllModes(from, to) { methods ->
            release()
            callback(methods)
        }
    }

    fun restoreSession(context: Context) {
        // Close the provider-switch window for the whole restore attempt: from here
        // until a session is restored (-> ACTIVE) or confirmed absent, canSwitchProvider() is false.
        restoreInFlight = true
        // requestSingleFix itself can throw (provider engine not ready) or call back
        // synchronously with null; if it throws, the callback never runs, so guard the
        // call so restoreInFlight never stays stuck true (the permanent-no-op bug).
        try {
            locationEngine.requestSingleFix { fix ->
                if (fix == null) {
                    Log.w(TAG, "Location not available during session restore")
                    restoreInFlight = false
                    return@requestSingleFix
                }
                val currentPoint = Point(fix.lat, fix.lng)
                scope.launch {
                    // restoreSession() hits the DB and can throw; clear the guard on any
                    // failure too so a single bad restore doesn't disable switching forever.
                    val session = try {
                        sessionTracker.restoreSession(currentPoint)
                    } catch (t: Throwable) {
                        Log.e(TAG, "restoreSession failed", t)
                        restoreInFlight = false
                        return@launch
                    }
                    if (session == null) {
                        Log.i(TAG, "No active session to restore")
                        restoreInFlight = false
                        return@launch
                    }

                    currentSession = session
                    cachedFrom = currentPoint
                    cachedTo = session.to
                    currentStepIndex = session.currentStepIndex
                    stepAdvanceConfirmations = 0
                    setState(State.ACTIVE)
                    restoreInFlight = false
                    arrivalDetector.setDestination(
                        session.to.latitude,
                        session.to.longitude
                    )
                    startLocationTracking(session.transportMode)
                    stateListener?.onEtaUpdated(session.estimatedEtaSeconds)
                    Log.i(TAG, "Session restored: ${session.id}, stepIndex=$currentStepIndex, rebuilding route")

                    val waypoints = listOfNotNull(session.waypoint)
                    rebuildRouteWithRetry(currentPoint, session.to, waypoints, session.transportMode)
                }
            }
        } catch (t: Throwable) {
            // requestSingleFix threw synchronously: callback never ran, so clear here.
            Log.e(TAG, "restoreSession: requestSingleFix threw", t)
            restoreInFlight = false
        }
    }

    private fun rebuildRouteWithRetry(
        from: Point, to: Point, waypoints: List<Point>,
        mode: TransportMode, attempt: Int = 1
    ) {
        MapProviders.active.routing.buildRoute(from, to, waypoints, mode) { route ->
            if (currentState != State.ACTIVE) {
                Log.i(TAG, "Route rebuild aborted: no longer ACTIVE")
                return@buildRoute
            }
            if (route != null) {
                setActiveRoute(route)
                stateListener?.onRouteUpdated(route.polylinePoints)
                stateListener?.onEtaUpdated(route.etaSeconds)
                if (route.instructions.isNotEmpty()) {
                    stateListener?.onInstructionChanged(route.instructions.first().text)
                }
                if (sendMapBitmap != null) {
                    startMapStreaming(route, restoring = true)
                } else {
                    pendingStreamOnRouteRebuilt = true
                }
                Log.i(TAG, "Route rebuilt after restore (attempt $attempt): ${route.distanceFormatted}, ${route.etaFormatted}")
            } else if (attempt < 3) {
                val delayMs = (1L shl attempt) * 1000L  // 2s, 4s
                Log.w(TAG, "Route rebuild failed (attempt $attempt), retrying in ${delayMs}ms")
                routeRebuildJob = scope.launch {
                    delay(delayMs)
                    if (currentState != State.ACTIVE) return@launch
                    rebuildRouteWithRetry(from, to, waypoints, mode, attempt + 1)
                }
            } else {
                Log.e(TAG, "Route rebuild failed after 3 attempts")
                stateListener?.onError("Route rebuild failed -- navigation active without route display")
            }
        }
    }

    /**
     * Called when glasses BT disconnects. Pauses map streaming to avoid wasting CPU
     * rendering bitmaps into a dead connection. The journey stays ACTIVE; streaming
     * resumes via [onBtReady] on reconnect.
     */
    fun onBtLost() {
        if (currentState != State.ACTIVE) return
        mapBitmapStreamer?.stop()
        mapBitmapStreamer = null
        pendingStreamOnRouteRebuilt = false
        Log.i(TAG, "MapBitmapStreamer paused (BT lost)")
    }

    /**
     * Called when BT lambdas become available (service start or glasses reconnect).
     * If a journey is ACTIVE, kicks off map streaming and sends current nav state to glasses.
     */
    fun onBtReady() {
        if (currentState != State.ACTIVE) return
        if (mapBitmapStreamer != null) return  // already streaming
        val route = currentRoute
        if (route != null) {
            startMapStreaming(route, restoring = true)
        } else {
            pendingStreamOnRouteRebuilt = true
        }
    }

    private fun startLocationTracking(mode: TransportMode) {
        stopLocationTracking()
        val listener = GeoFixListener { fix ->
            lastFixLat = fix.lat
            lastFixLng = fix.lng
            lastFixHeading = fix.headingDeg?.toDouble()
            lastFixSpeed = fix.speedMps
            lastFixAtMs = System.currentTimeMillis()
            arrivalDetector.updatePosition(fix.lat, fix.lng)
            stateListener?.onLocationUpdated(fix.lat, fix.lng)
            checkDeviation(fix.lat, fix.lng)
            updateCurrentStep(fix.lat, fix.lng)
            updateStreamerHeading(fix, fix.lat, fix.lng)
        }
        locationEngine.subscribe(listener)
        locationListener = listener
    }

    /**
     * Source of truth for the location stream during a journey. The
     * MapBitmapStreamer subscribes through this so a mock journey drives both
     * the navigation listener and the bitmap streamer from the same simulated
     * polyline walk.
     */
    fun getLocationSource(): JourneyLocationSource = locationSource

    /** Provider-neutral location engine driving the journey + mock. */
    fun getLocationEngine(): LocationEngine = locationEngine

    private fun checkDeviation(lat: Double, lng: Double) {
        if (!AUTO_REROUTE_ENABLED) return
        if (currentState != State.ACTIVE || rerouting) return
        val route = currentRoute ?: return
        val now = System.currentTimeMillis()
        if (now - lastRerouteTime < REROUTE_COOLDOWN_MS) return

        val samples = densifiedRoute.takeIf { it.isNotEmpty() } ?: route.polylinePoints
        val minDist = minDistanceToRoute(lat, lng, samples)
        if (minDist > DEVIATION_THRESHOLD_M) {
            Log.i(TAG, "Deviation detected: ${minDist.toInt()}m from route, rerouting")
            rerouting = true
            lastRerouteTime = now
            val from = Point(lat, lng)
            val to = cachedTo ?: return
            MapProviders.active.routing.buildRoute(from, to, emptyList(), route.mode) { newRoute ->
                rerouting = false
                if (newRoute == null) {
                    Log.e(TAG, "Reroute failed")
                    return@buildRoute
                }
                setActiveRoute(newRoute)
                currentStepIndex = 0
                stepAdvanceConfirmations = 0
                sendJourneySteps(newRoute)
                if (newRoute.transitSections.isNotEmpty()) {
                    emitStepIndex(0)
                }
                stateListener?.onRouteUpdated(newRoute.polylinePoints)
                stateListener?.onEtaUpdated(newRoute.etaSeconds)
                if (newRoute.instructions.isNotEmpty()) {
                    stateListener?.onInstructionChanged(newRoute.instructions.first().text)
                }
                Log.i(TAG, "Rerouted: ${newRoute.distanceFormatted}, ${newRoute.etaFormatted}")
            }
        }
    }

    private fun minDistanceToRoute(lat: Double, lng: Double, points: List<Point>): Double {
        if (points.isEmpty()) return Double.MAX_VALUE
        var min = Double.MAX_VALUE
        for (p in points) {
            val d = haversineM(lat, lng, p.latitude, p.longitude)
            if (d < min) min = d
        }
        return min
    }

    private fun updateStreamerHeading(fix: GeoFix, lat: Double, lng: Double) {
        val streamer = mapBitmapStreamer ?: return
        val deg: Float = if (fix.headingDeg != null) {
            fix.headingDeg
        } else if (!lastHeadingLat.isNaN() && !lastHeadingLng.isNaN()) {
            bearingDeg(lastHeadingLat, lastHeadingLng, lat, lng).toFloat()
        } else {
            0f
        }
        streamer.updateHeading(deg)
        lastHeadingLat = lat
        lastHeadingLng = lng
    }

    private fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)
        val y = Math.sin(dLng) * Math.cos(phi2)
        val x = Math.cos(phi1) * Math.sin(phi2) -
                Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLng)
        val brng = Math.toDegrees(Math.atan2(y, x))
        return (brng + 360.0) % 360.0
    }

    private fun densifyRoute(points: List<Point>, spacingM: Double): List<Point> {
        if (points.size < 2) return points
        val out = ArrayList<Point>(points.size * 4)
        out.add(points[0])
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val segLen = haversineM(a.latitude, a.longitude, b.latitude, b.longitude)
            if (segLen <= spacingM) { out.add(b); continue }
            val steps = (segLen / spacingM).toInt()
            for (s in 1..steps) {
                val t = s.toDouble() / (steps + 1).toDouble()
                out.add(Point(
                    a.latitude + (b.latitude - a.latitude) * t,
                    a.longitude + (b.longitude - a.longitude) * t
                ))
            }
            out.add(b)
        }
        return out
    }

    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun updateCurrentStep(lat: Double, lng: Double) {
        val route = currentRoute ?: return
        val sections = route.transitSections
        if (sections.isEmpty()) return  // transit routes only
        if (currentStepIndex >= sections.size - 1) return  // already on last step

        val currentSection = sections[currentStepIndex]
        val endPoint = currentSection.polylinePoints.lastOrNull()
        if (endPoint == null) {
            Log.w(TAG, "Step $currentStepIndex has empty polyline, cannot track")
            return
        }
        val distToEnd = haversineM(lat, lng, endPoint.latitude, endPoint.longitude)

        if (distToEnd < 50.0) {
            // Require 2 consecutive GPS fixes within threshold to avoid GPS bounce
            stepAdvanceConfirmations++
            if (stepAdvanceConfirmations >= 2) {
                currentStepIndex++
                stepAdvanceConfirmations = 0
                Log.i(TAG, "Step index advanced to $currentStepIndex")

                // Skip over short sections we may have passed through
                while (currentStepIndex < sections.size - 1) {
                    val nextEnd = sections[currentStepIndex].polylinePoints.lastOrNull() ?: break
                    if (haversineM(lat, lng, nextEnd.latitude, nextEnd.longitude) < 50.0) {
                        currentStepIndex++
                        Log.i(TAG, "Skipped short section, now at $currentStepIndex")
                    } else {
                        break
                    }
                }

                emitStepIndex(currentStepIndex)
                val idx = currentStepIndex
                currentSession?.let { s ->
                    scope.launch { sessionTracker.updateStepIndex(s.id, idx) }
                }
            }
        } else {
            stepAdvanceConfirmations = 0
        }
    }

    private fun stopLocationTracking() {
        val listener = locationListener ?: return
        locationEngine.unsubscribe(listener)
        locationListener = null
    }

    /**
     * Plan a route between [from] and [to] for the given [mode], start the
     * journey using the established session/streamer/arrival pipeline, then
     * drive the user along the resulting polyline via Yandex's
     * LocationSimulator at [speedMps] m/s. When the simulator reaches the end
     * of the polyline (or the geofence fires), the journey is auto-stopped.
     *
     * If [methodId] is null, defaults to "${mode.name.lowercase()}_0" (the
     * convention chooseMethodAndStart uses internally).
     *
     * Defaults if speedMps == null: walking 1.4, bicycle 5.5, transit 10.0,
     * driving 13.9 m/s.
     */
    fun startMockJourney(
        from: Point,
        to: Point,
        mode: TransportMode,
        methodId: String? = null,
        speedMps: Double? = null,
        onError: ((String) -> Unit)? = null,
        callback: (Long, Long) -> Unit
    ) {
        val effectiveSpeed = speedMps ?: defaultSpeedFor(mode)
        Log.i(TAG, "startMockJourney: $mode @ ${"%.2f".format(effectiveSpeed)} m/s " +
                "from=${from.latitude},${from.longitude} to=${to.latitude},${to.longitude}")

        cachedFrom = from
        cachedTo = to
        setState(State.PLANNING)

        MapProviders.active.routing.buildRoute(from, to, emptyList(), mode) { route ->
            if (route == null) {
                val msg = "Mock: failed to build route for $mode"
                Log.e(TAG, msg)
                setState(State.IDLE)
                onError?.invoke(msg)
                return@buildRoute
            }
            beginMockJourney(route, mode, methodId, effectiveSpeed, callback)
        }
    }

    /**
     * Shared post-routing body of a mock journey: register the method, begin the
     * session (state -> ACTIVE, arrival detector armed, streamer started) and then
     * drive the simulator along [route]'s polyline at [speedMps]. Used by both
     * [startMockJourney] (after a real buildRoute) and
     * [startMockJourneyWithRoute] (offline, fabricated route).
     */
    private fun beginMockJourney(
        route: RouteInfo,
        mode: TransportMode,
        methodId: String?,
        speedMps: Double,
        callback: (Long, Long) -> Unit
    ) {
        val effectiveMethodId = methodId ?: "${mode.name.lowercase()}_0"
        val methodInfo = TransportMethodInfo(
            methodId = effectiveMethodId,
            mode = mode,
            etaSeconds = route.etaSeconds,
            etaFormatted = route.etaFormatted,
            description = "${mode.name}: ${route.distanceFormatted}, ${route.etaFormatted}",
            distanceMeters = route.distanceMeters
        )
        cachedMethods = mapOf(effectiveMethodId to methodInfo)
        beginSession(route, effectiveMethodId, mode) { sessionId, eta ->
            // Walk the SAME geometry the UI draws. For transit, the drawn path and
            // step-advancement use the per-section polylines (sections union), while
            // route.polylinePoints is Google's decimated overview -- walking the
            // overview made the user arrow sit 50-100m off the drawn route. Use the
            // section union when present so arrow and path stay in sync.
            val sectionPolyline = route.transitSections.flatMap { it.polylinePoints }
            val polyline = if (sectionPolyline.size >= 2) sectionPolyline else route.polylinePoints
            if (polyline.size < 2) {
                Log.w(TAG, "Mock: route has <2 polyline points, simulator skipped")
            } else {
                locationEngine.enableMock(polyline, speedMps) {
                    // Simulator finished: ArrivalDetector usually fires first,
                    // but cover the case where the polyline ends short of the
                    // 50m geofence (e.g. transit drop-off offset).
                    if (currentSession != null) {
                        Log.i(TAG, "Mock: simulator exhausted, stopping journey")
                        stopJourney()
                    }
                }
            }
            callback(sessionId, eta)
        }
    }

    /**
     * Test seam: drive the full mock-journey machinery from a fabricated [route]
     * WITHOUT any routing/network call (no Google key required). Sets
     * cachedFrom/cachedTo from the route endpoints, then reuses the same
     * post-routing body [startMockJourney] runs after buildRoute returns:
     * begins the session (state -> ACTIVE, arrival detector armed, map streamer
     * started) and walks [route.polylinePoints] at [speedMps]. [onFinished] is
     * invoked once the session has been started.
     */
    @androidx.annotation.VisibleForTesting
    fun startMockJourneyWithRoute(
        route: RouteInfo,
        speedMps: Double,
        onFinished: () -> Unit = {}
    ) {
        cachedFrom = route.from
        cachedTo = route.to
        setState(State.PLANNING)
        beginMockJourney(route, route.mode, null, speedMps) { _, _ -> onFinished() }
    }

    /** Cancels an in-progress mock journey (and the journey itself). */
    fun stopMockJourney() {
        if (locationEngine.mockActive) {
            locationEngine.disableMock()
        }
        if (currentSession != null) {
            stopJourney()
        }
    }

    private fun defaultSpeedFor(mode: TransportMode): Double = when (mode) {
        TransportMode.WALKING -> 1.4
        TransportMode.BICYCLE -> 5.5
        TransportMode.TRANSIT -> 10.0
        TransportMode.DRIVING -> 13.9
    }
}
