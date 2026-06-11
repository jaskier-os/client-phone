package com.repository.navigation

import android.util.Log
import com.repository.navigation.model.InstructionType
import com.repository.navigation.model.NavigationInstruction
import com.repository.navigation.model.RouteAlternative
import com.repository.navigation.model.RouteInfo
import com.repository.navigation.model.TransitSection
import com.repository.navigation.model.TransitSectionType
import com.repository.navigation.model.TransportMethodInfo
import com.repository.navigation.model.TransportMode
import com.yandex.mapkit.RequestPoint
import com.yandex.mapkit.RequestPointType
import com.yandex.mapkit.directions.DirectionsFactory
import com.yandex.mapkit.directions.driving.DrivingOptions
import com.yandex.mapkit.directions.driving.DrivingRoute
import com.yandex.mapkit.directions.driving.DrivingRouterType
import com.yandex.mapkit.directions.driving.DrivingSession
import com.yandex.mapkit.directions.driving.VehicleOptions
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.geometry.Subpolyline
import com.yandex.mapkit.transport.TransportFactory
import com.yandex.mapkit.transport.masstransit.Route
import com.yandex.mapkit.transport.masstransit.RouteOptions
import com.yandex.mapkit.transport.masstransit.Session
import com.yandex.mapkit.transport.masstransit.TimeOptions
import com.yandex.mapkit.transport.masstransit.FitnessOptions
import com.yandex.mapkit.transport.masstransit.TransitOptions
import com.yandex.runtime.Error
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object RouteEngine {

    private const val TAG = "RouteEngine"

    private val drivingRouter by lazy {
        DirectionsFactory.getInstance().createDrivingRouter(DrivingRouterType.ONLINE)
    }

    private val masstransitRouter by lazy {
        TransportFactory.getInstance().createMasstransitRouter()
    }

    private val pedestrianRouter by lazy {
        TransportFactory.getInstance().createPedestrianRouter()
    }

    private val bicycleRouter by lazy {
        TransportFactory.getInstance().createBicycleRouterV2()
    }

    /** Touch all lazy router vals to force initialization on the main thread. */
    fun warmUp() {
        drivingRouter
        masstransitRouter
        pedestrianRouter
        bicycleRouter
        Log.d(TAG, "All routers warmed up")
    }

    // Hold references to active sessions to prevent GC during async callbacks
    private val activeSessions = mutableListOf<Any>()

    private fun addSession(session: Any) {
        synchronized(activeSessions) { activeSessions.add(session) }
    }

    private fun clearSessions() {
        synchronized(activeSessions) { activeSessions.clear() }
    }

    private val handler = Handler(Looper.getMainLooper())
    private const val PER_ROUTER_TIMEOUT_MS = 10_000L

    fun queryAllModes(
        from: Point,
        to: Point,
        callback: (List<TransportMethodInfo>) -> Unit
    ) {
        val results = mutableListOf<TransportMethodInfo>()
        val remaining = AtomicInteger(4)
        val callbackFired = AtomicBoolean(false)
        val startTime = System.currentTimeMillis()

        fun onResult(label: String, info: TransportMethodInfo?) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "$label responded in ${elapsed}ms: ${if (info != null) "OK" else "null"}")
            synchronized(results) {
                if (info != null) results.add(info)
            }
            if (remaining.decrementAndGet() == 0) {
                if (callbackFired.compareAndSet(false, true)) {
                    clearSessions()
                    synchronized(results) {
                        Log.d(TAG, "All routers done, ${results.size} results")
                        callback(results.toList())
                    }
                }
            }
        }

        // Per-router timeout: if any router hangs, treat it as null after 10s
        // and fire the callback early with whatever results we have
        val timeoutRunnable = Runnable {
            val pending = remaining.get()
            if (pending > 0 && callbackFired.compareAndSet(false, true)) {
                Log.w(TAG, "Per-router timeout: $pending routers still pending, returning ${4 - pending} results")
                clearSessions()
                synchronized(results) {
                    callback(results.toList())
                }
            }
        }
        handler.postDelayed(timeoutRunnable, PER_ROUTER_TIMEOUT_MS)

        Log.d(TAG, "queryAllModes: dispatching 4 routers from=$from to=$to")
        queryDrivingSummary(from, to) { onResult("driving", it) }
        queryTransitSummary(from, to) { onResult("transit", it) }
        queryPedestrianSummary(from, to) { onResult("pedestrian", it) }
        queryBicycleSummary(from, to) { onResult("bicycle", it) }
    }

    fun buildRoute(
        from: Point,
        to: Point,
        waypoints: List<Point>,
        mode: TransportMode,
        callback: (RouteInfo?) -> Unit
    ) {
        val wrappedCallback: (RouteInfo?) -> Unit = { result ->
            clearSessions()
            callback(result)
        }
        when (mode) {
            TransportMode.DRIVING -> buildDrivingRoute(from, to, waypoints, wrappedCallback)
            TransportMode.TRANSIT -> buildTransitRoute(from, to, waypoints, wrappedCallback)
            TransportMode.WALKING -> buildPedestrianRoute(from, to, waypoints, wrappedCallback)
            TransportMode.BICYCLE -> buildBicycleRoute(from, to, waypoints, wrappedCallback)
        }
    }

    fun rebuildWithWaypoint(
        currentRoute: RouteInfo,
        waypoint: Point,
        callback: (RouteInfo?) -> Unit
    ) {
        val waypoints = currentRoute.waypoints + waypoint
        buildRoute(currentRoute.from, currentRoute.to, waypoints, currentRoute.mode, callback)
    }

    fun queryMode(
        from: Point,
        to: Point,
        mode: TransportMode,
        callback: (TransportMethodInfo?) -> Unit
    ) {
        when (mode) {
            TransportMode.DRIVING -> queryDrivingSummary(from, to, callback)
            TransportMode.TRANSIT -> queryTransitSummary(from, to, callback)
            TransportMode.WALKING -> queryPedestrianSummary(from, to, callback)
            TransportMode.BICYCLE -> queryBicycleSummary(from, to, callback)
        }
    }

    fun buildRouteAlternatives(
        from: Point,
        to: Point,
        mode: TransportMode,
        waypoints: List<Point> = emptyList(),
        departureTime: java.util.Date? = null,
        callback: (List<RouteAlternative>) -> Unit
    ) {
        when (mode) {
            TransportMode.TRANSIT -> buildTransitAlternatives(from, to, waypoints, departureTime, callback)
            TransportMode.DRIVING -> buildDrivingAlternatives(from, to, waypoints, callback)
            TransportMode.WALKING -> buildRoute(from, to, waypoints, mode) { route ->
                if (route != null) {
                    callback(listOf(RouteAlternative("walking_0", mode, route, route.distanceFormatted)))
                } else {
                    callback(emptyList())
                }
            }
            TransportMode.BICYCLE -> buildRoute(from, to, waypoints, mode) { route ->
                if (route != null) {
                    callback(listOf(RouteAlternative("bicycle_0", mode, route, route.distanceFormatted)))
                } else {
                    callback(emptyList())
                }
            }
        }
    }

    private fun buildTransitAlternatives(
        from: Point, to: Point,
        waypoints: List<Point> = emptyList(),
        departureTime: java.util.Date? = null,
        callback: (List<RouteAlternative>) -> Unit
    ) {
        val points = buildRequestPoints(from, to, waypoints)
        val effectiveTime = departureTime ?: java.util.Date()
        val timeOptions = TimeOptions(effectiveTime.time / 1000, null)
        val session = masstransitRouter.requestRoutes(
            points, TransitOptions(0, timeOptions), RouteOptions(FitnessOptions()),
            object : Session.RouteListener {
                override fun onMasstransitRoutes(routes: MutableList<Route>) {
                    val alternatives = routes.mapIndexed { index, route ->
                        val routeInfo = parseTransitRoute(route, from, to, waypoints)
                        val summary = routeInfo.transitSections
                            .filter { it.type != TransitSectionType.WALK }
                            .joinToString(" + ") { section ->
                                section.lineName ?: section.type.name
                            }
                            .ifEmpty { "Walk" }
                        RouteAlternative("transit_$index", TransportMode.TRANSIT, routeInfo, summary)
                    }
                    clearSessions()
                    callback(alternatives)
                }

                override fun onMasstransitRoutesError(error: Error) {
                    Log.e(TAG, "Transit alternatives error: $error")
                    clearSessions()
                    callback(emptyList())
                }
            }
        )
        addSession(session)
    }

    private fun buildDrivingAlternatives(
        from: Point, to: Point,
        waypoints: List<Point> = emptyList(),
        callback: (List<RouteAlternative>) -> Unit
    ) {
        val points = mutableListOf(
            RequestPoint(from, RequestPointType.WAYPOINT, null, null, null)
        )
        for (wp in waypoints) {
            points.add(RequestPoint(wp, RequestPointType.WAYPOINT, null, null, null))
        }
        points.add(RequestPoint(to, RequestPointType.WAYPOINT, null, null, null))
        val options = DrivingOptions().setRoutesCount(3)
        val vehicleOptions = VehicleOptions()
        val session = drivingRouter.requestRoutes(
            points, options, vehicleOptions,
            object : DrivingSession.DrivingRouteListener {
                override fun onDrivingRoutes(routes: MutableList<DrivingRoute>) {
                    val alternatives = routes.mapIndexed { index, route ->
                        val weight = route.metadata.weight
                        val polylinePoints = route.geometry.points
                        val instructions = route.sections.map { section ->
                            val annotation = section.metadata.annotation
                            NavigationInstruction(
                                type = InstructionType.DRIVE,
                                text = annotation.action?.let { actionToText(it) }
                                    ?: "Continue driving"
                            )
                        }
                        val routeInfo = RouteInfo(
                            mode = TransportMode.DRIVING,
                            polylinePoints = polylinePoints,
                            etaSeconds = weight.timeWithTraffic.value.toLong(),
                            etaFormatted = weight.timeWithTraffic.text,
                            distanceMeters = weight.distance.value.toInt(),
                            distanceFormatted = weight.distance.text,
                            instructions = instructions,
                            from = from,
                            to = to
                        )
                        RouteAlternative(
                            "driving_$index", TransportMode.DRIVING, routeInfo,
                            "${weight.distance.text}, ${weight.timeWithTraffic.text}"
                        )
                    }
                    clearSessions()
                    callback(alternatives)
                }

                override fun onDrivingRoutesError(error: Error) {
                    Log.e(TAG, "Driving alternatives error: $error")
                    clearSessions()
                    callback(emptyList())
                }
            }
        )
        addSession(session)
    }

    /** Parse a single MapKit transit Route into our RouteInfo model. Reused by buildTransitRoute and alternatives. */
    private fun parseTransitRoute(route: Route, from: Point, to: Point, waypoints: List<Point>): RouteInfo {
        val weight = route.metadata.weight
        val timeSec = weight.time.value.toLong()
        val timeText = weight.time.text
        val routeGeometry = route.geometry
        val instructions = mutableListOf<NavigationInstruction>()
        val transitSections = mutableListOf<TransitSection>()
        var cumulativeEta = 0L

        for (section in route.sections) {
            val sectionMeta = section.metadata
            val sectionData = sectionMeta.data
            val sectionWeight = sectionMeta.weight
            val durationSec = sectionWeight.time.value.toLong()
            cumulativeEta += durationSec

            val sectionPoints = extractSectionPoints(routeGeometry, section.geometry)

            val transportData = sectionData.transports
            if (transportData != null && transportData.isNotEmpty()) {
                val transport = transportData.first()
                val line = transport.line
                val lineName = line.name
                val lineShortName = line.shortName
                val vehicleTypes = line.vehicleTypes
                val instructionType = vehicleTypeToInstructionType(vehicleTypes)
                val sectionType = vehicleTypeToSectionType(vehicleTypes)
                val lineColor = line.style?.color?.let { it.toInt() or 0xFF000000.toInt() }
                val recommendedThread = transport.transports.firstOrNull { it.isRecommended }
                    ?: transport.transports.firstOrNull()
                val departureTimeText = recommendedThread?.estimation?.departureTime?.text
                val threadDirection = recommendedThread?.thread?.description

                val stops = section.stops
                val boardStop = stops.firstOrNull()?.metadata?.stop?.name
                val alightStop = stops.lastOrNull()?.metadata?.stop?.name

                instructions.add(
                    NavigationInstruction(
                        type = instructionType,
                        text = "Take $lineName" +
                                (boardStop?.let { " from $it" } ?: "") +
                                (alightStop?.let { " to $it" } ?: ""),
                        lineName = lineName,
                        stopName = boardStop
                    )
                )
                if (alightStop != null) {
                    instructions.add(
                        NavigationInstruction(
                            type = InstructionType.EXIT_TRANSPORT,
                            text = "Exit at $alightStop",
                            stopName = alightStop
                        )
                    )
                }

                transitSections.add(
                    TransitSection(
                        type = sectionType,
                        polylinePoints = sectionPoints,
                        durationSeconds = durationSec,
                        durationFormatted = sectionWeight.time.text,
                        distanceMeters = sectionWeight.walkingDistance.value.toInt(),
                        distanceFormatted = sectionWeight.walkingDistance.text,
                        lineName = lineName,
                        lineShortName = lineShortName,
                        lineShortNames = lineShortName?.let { listOf(it) } ?: emptyList(),
                        vehicleType = vehicleTypes.firstOrNull(),
                        boardStop = boardStop,
                        alightStop = alightStop,
                        stopCount = stops.size,
                        cumulativeEtaSeconds = cumulativeEta,
                        direction = threadDirection,
                        lineColor = lineColor,
                        departureTimeText = departureTimeText
                    )
                )
            } else {
                instructions.add(
                    NavigationInstruction(type = InstructionType.WALK, text = "Walk")
                )
                transitSections.add(
                    TransitSection(
                        type = TransitSectionType.WALK,
                        polylinePoints = sectionPoints,
                        durationSeconds = durationSec,
                        durationFormatted = sectionWeight.time.text,
                        distanceMeters = sectionWeight.walkingDistance.value.toInt(),
                        distanceFormatted = sectionWeight.walkingDistance.text,
                        lineName = null,
                        lineShortName = null,
                        vehicleType = null,
                        boardStop = null,
                        alightStop = null,
                        stopCount = 0,
                        cumulativeEtaSeconds = cumulativeEta
                    )
                )
            }
        }

        val mergedSections = mergeConsecutiveWalks(transitSections)
        val taggedSections = tagTransferWalks(mergedSections)
        val walkDist = weight.walkingDistance
        return RouteInfo(
            mode = TransportMode.TRANSIT,
            polylinePoints = routeGeometry.points,
            etaSeconds = timeSec,
            etaFormatted = timeText,
            distanceMeters = walkDist.value.toInt(),
            distanceFormatted = walkDist.text,
            instructions = instructions,
            from = from,
            to = to,
            waypoints = waypoints,
            transitSections = taggedSections
        )
    }

    // -- Driving --

    private fun queryDrivingSummary(from: Point, to: Point, callback: (TransportMethodInfo?) -> Unit) {
        val points = listOf(
            RequestPoint(from, RequestPointType.WAYPOINT, null, null, null),
            RequestPoint(to, RequestPointType.WAYPOINT, null, null, null)
        )
        val options = DrivingOptions().setRoutesCount(1)
        val vehicleOptions = VehicleOptions()
        val session = drivingRouter.requestRoutes(
            points, options, vehicleOptions,
            object : DrivingSession.DrivingRouteListener {
                override fun onDrivingRoutes(routes: MutableList<DrivingRoute>) {
                    val route = routes.firstOrNull()
                    if (route == null) {
                        callback(null)
                        return
                    }
                    val weight = route.metadata.weight
                    callback(
                        TransportMethodInfo(
                            methodId = "driving_${route.routeId}",
                            mode = TransportMode.DRIVING,
                            etaSeconds = weight.timeWithTraffic.value.toLong(),
                            etaFormatted = weight.timeWithTraffic.text,
                            description = "By car: ${weight.distance.text}, ${weight.timeWithTraffic.text}",
                            distanceMeters = weight.distance.value.toInt()
                        )
                    )
                }

                override fun onDrivingRoutesError(error: Error) {
                    Log.e(TAG, "Driving route error: $error")
                    callback(null)
                }
            }
        )
        addSession(session)
    }

    private fun buildDrivingRoute(
        from: Point, to: Point, waypoints: List<Point>,
        callback: (RouteInfo?) -> Unit
    ) {
        val points = buildRequestPoints(from, to, waypoints)
        val options = DrivingOptions().setRoutesCount(1)
        val vehicleOptions = VehicleOptions()
        val session = drivingRouter.requestRoutes(
            points, options, vehicleOptions,
            object : DrivingSession.DrivingRouteListener {
                override fun onDrivingRoutes(routes: MutableList<DrivingRoute>) {
                    val route = routes.firstOrNull()
                    if (route == null) {
                        callback(null)
                        return
                    }
                    val weight = route.metadata.weight
                    val polylinePoints = route.geometry.points
                    val instructions = route.sections.map { section ->
                        val annotation = section.metadata.annotation
                        NavigationInstruction(
                            type = InstructionType.DRIVE,
                            text = annotation.action?.let { actionToText(it) }
                                ?: "Continue driving"
                        )
                    }
                    callback(
                        RouteInfo(
                            mode = TransportMode.DRIVING,
                            polylinePoints = polylinePoints,
                            etaSeconds = weight.timeWithTraffic.value.toLong(),
                            etaFormatted = weight.timeWithTraffic.text,
                            distanceMeters = weight.distance.value.toInt(),
                            distanceFormatted = weight.distance.text,
                            instructions = instructions,
                            from = from,
                            to = to,
                            waypoints = waypoints
                        )
                    )
                }

                override fun onDrivingRoutesError(error: Error) {
                    Log.e(TAG, "Driving build error: $error")
                    callback(null)
                }
            }
        )
        addSession(session)
    }

    // -- Transit (Masstransit) --

    private fun queryTransitSummary(from: Point, to: Point, callback: (TransportMethodInfo?) -> Unit) {
        val points = listOf(
            RequestPoint(from, RequestPointType.WAYPOINT, null, null, null),
            RequestPoint(to, RequestPointType.WAYPOINT, null, null, null)
        )
        val session = masstransitRouter.requestRoutes(
            points, TransitOptions(0, TimeOptions()), RouteOptions(FitnessOptions()),
            object : Session.RouteListener {
                override fun onMasstransitRoutes(routes: MutableList<Route>) {
                    val route = routes.firstOrNull()
                    if (route == null) {
                        callback(null)
                        return
                    }
                    val weight = route.metadata.weight
                    val timeSec = weight.time.value.toLong()
                    val timeText = weight.time.text
                    val walkDist = weight.walkingDistance
                    callback(
                        TransportMethodInfo(
                            methodId = "transit_0",
                            mode = TransportMode.TRANSIT,
                            etaSeconds = timeSec,
                            etaFormatted = timeText,
                            description = "Transit: $timeText, walk ${walkDist.text}",
                            distanceMeters = walkDist.value.toInt()
                        )
                    )
                }

                override fun onMasstransitRoutesError(error: Error) {
                    Log.e(TAG, "Transit route error: $error")
                    callback(null)
                }
            }
        )
        addSession(session)
    }

    private fun buildTransitRoute(
        from: Point, to: Point, waypoints: List<Point>,
        callback: (RouteInfo?) -> Unit
    ) {
        val points = buildRequestPoints(from, to, waypoints)
        val session = masstransitRouter.requestRoutes(
            points, TransitOptions(0, TimeOptions()), RouteOptions(FitnessOptions()),
            object : Session.RouteListener {
                override fun onMasstransitRoutes(routes: MutableList<Route>) {
                    val route = routes.firstOrNull()
                    if (route == null) {
                        callback(null)
                        return
                    }
                    callback(parseTransitRoute(route, from, to, waypoints))
                }

                override fun onMasstransitRoutesError(error: Error) {
                    Log.e(TAG, "Transit build error: $error")
                    callback(null)
                }
            }
        )
        addSession(session)
    }

    private fun tagTransferWalks(sections: List<TransitSection>): List<TransitSection> {
        return sections.mapIndexed { i, section ->
            if (section.type == TransitSectionType.WALK && i > 0 && i < sections.size - 1
                && sections[i - 1].type != TransitSectionType.WALK
                && sections[i + 1].type != TransitSectionType.WALK) {
                section.copy(isTransfer = true)
            } else {
                section
            }
        }
    }

    private fun mergeConsecutiveWalks(sections: List<TransitSection>): List<TransitSection> {
        if (sections.size < 2) return sections
        val merged = mutableListOf<TransitSection>()
        var i = 0
        while (i < sections.size) {
            val current = sections[i]
            if (current.type == TransitSectionType.WALK && i + 1 < sections.size && sections[i + 1].type == TransitSectionType.WALK) {
                // Accumulate consecutive walks
                var totalDuration = current.durationSeconds
                var totalDistance = current.distanceMeters
                var points = current.polylinePoints.toMutableList()
                var j = i + 1
                while (j < sections.size && sections[j].type == TransitSectionType.WALK) {
                    totalDuration += sections[j].durationSeconds
                    totalDistance += sections[j].distanceMeters
                    points.addAll(sections[j].polylinePoints)
                    j++
                }
                val durationMin = (totalDuration + 59) / 60
                val durationText = if (durationMin > 0) "$durationMin min" else "<1 min"
                val distanceText = if (totalDistance >= 1000) "${"%.1f".format(totalDistance / 1000.0)} km" else "$totalDistance m"
                merged.add(current.copy(
                    polylinePoints = points,
                    durationSeconds = totalDuration,
                    durationFormatted = durationText,
                    distanceMeters = totalDistance,
                    distanceFormatted = distanceText,
                    cumulativeEtaSeconds = sections[j - 1].cumulativeEtaSeconds
                ))
                i = j
            } else {
                merged.add(current)
                i++
            }
        }
        return merged
    }

    // -- Pedestrian --

    private fun queryPedestrianSummary(from: Point, to: Point, callback: (TransportMethodInfo?) -> Unit) {
        val points = listOf(
            RequestPoint(from, RequestPointType.WAYPOINT, null, null, null),
            RequestPoint(to, RequestPointType.WAYPOINT, null, null, null)
        )
        val session = pedestrianRouter.requestRoutes(
            points, TimeOptions(), RouteOptions(FitnessOptions()),
            object : Session.RouteListener {
                override fun onMasstransitRoutes(routes: MutableList<Route>) {
                    val route = routes.firstOrNull()
                    if (route == null) {
                        callback(null)
                        return
                    }
                    val weight = route.metadata.weight
                    val timeSec = weight.time.value.toLong()
                    val timeText = weight.time.text
                    val walkDist = weight.walkingDistance
                    callback(
                        TransportMethodInfo(
                            methodId = "walking_0",
                            mode = TransportMode.WALKING,
                            etaSeconds = timeSec,
                            etaFormatted = timeText,
                            description = "Walk: ${walkDist.text}",
                            distanceMeters = walkDist.value.toInt()
                        )
                    )
                }

                override fun onMasstransitRoutesError(error: Error) {
                    Log.e(TAG, "Pedestrian route error: $error")
                    callback(null)
                }
            }
        )
        addSession(session)
    }

    private fun buildPedestrianRoute(
        from: Point, to: Point, waypoints: List<Point>,
        callback: (RouteInfo?) -> Unit
    ) {
        val points = buildRequestPoints(from, to, waypoints)
        val session = pedestrianRouter.requestRoutes(
            points, TimeOptions(), RouteOptions(FitnessOptions()),
            object : Session.RouteListener {
                override fun onMasstransitRoutes(routes: MutableList<Route>) {
                    val route = routes.firstOrNull()
                    if (route == null) {
                        callback(null)
                        return
                    }
                    val weight = route.metadata.weight
                    val timeSec = weight.time.value.toLong()
                    val timeText = weight.time.text
                    val walkDist = weight.walkingDistance
                    val instructions = mutableListOf<NavigationInstruction>()

                    for (section in route.sections) {
                        instructions.add(
                            NavigationInstruction(
                                type = InstructionType.WALK,
                                text = "Walk"
                            )
                        )
                    }

                    callback(
                        RouteInfo(
                            mode = TransportMode.WALKING,
                            polylinePoints = route.geometry.points,
                            etaSeconds = timeSec,
                            etaFormatted = timeText,
                            distanceMeters = walkDist.value.toInt(),
                            distanceFormatted = walkDist.text,
                            instructions = instructions,
                            from = from,
                            to = to,
                            waypoints = waypoints
                        )
                    )
                }

                override fun onMasstransitRoutesError(error: Error) {
                    Log.e(TAG, "Pedestrian build error: $error")
                    callback(null)
                }
            }
        )
        addSession(session)
    }

    // -- Bicycle --

    private fun queryBicycleSummary(from: Point, to: Point, callback: (TransportMethodInfo?) -> Unit) {
        val points = listOf(
            RequestPoint(from, RequestPointType.WAYPOINT, null, null, null),
            RequestPoint(to, RequestPointType.WAYPOINT, null, null, null)
        )
        val session = bicycleRouter.requestRoutes(
            points, TimeOptions(), RouteOptions(FitnessOptions()),
            object : Session.RouteListener {
                override fun onMasstransitRoutes(routes: MutableList<Route>) {
                    val route = routes.firstOrNull()
                    if (route == null) {
                        callback(null)
                        return
                    }
                    val weight = route.metadata.weight
                    val timeSec = weight.time.value.toLong()
                    val timeText = weight.time.text
                    val walkDist = weight.walkingDistance
                    callback(
                        TransportMethodInfo(
                            methodId = "bicycle_0",
                            mode = TransportMode.BICYCLE,
                            etaSeconds = timeSec,
                            etaFormatted = timeText,
                            description = "Bicycle: ${walkDist.text}",
                            distanceMeters = walkDist.value.toInt()
                        )
                    )
                }

                override fun onMasstransitRoutesError(error: Error) {
                    Log.e(TAG, "Bicycle route error: $error")
                    callback(null)
                }
            }
        )
        addSession(session)
    }

    private fun buildBicycleRoute(
        from: Point, to: Point, waypoints: List<Point>,
        callback: (RouteInfo?) -> Unit
    ) {
        val points = buildRequestPoints(from, to, waypoints)
        val session = bicycleRouter.requestRoutes(
            points, TimeOptions(), RouteOptions(FitnessOptions()),
            object : Session.RouteListener {
                override fun onMasstransitRoutes(routes: MutableList<Route>) {
                    val route = routes.firstOrNull()
                    if (route == null) {
                        callback(null)
                        return
                    }
                    val weight = route.metadata.weight
                    val timeSec = weight.time.value.toLong()
                    val timeText = weight.time.text
                    val walkDist = weight.walkingDistance
                    val instructions = mutableListOf<NavigationInstruction>()

                    for (section in route.sections) {
                        instructions.add(
                            NavigationInstruction(
                                type = InstructionType.CYCLE,
                                text = "Cycle"
                            )
                        )
                    }

                    callback(
                        RouteInfo(
                            mode = TransportMode.BICYCLE,
                            polylinePoints = route.geometry.points,
                            etaSeconds = timeSec,
                            etaFormatted = timeText,
                            distanceMeters = walkDist.value.toInt(),
                            distanceFormatted = walkDist.text,
                            instructions = instructions,
                            from = from,
                            to = to,
                            waypoints = waypoints
                        )
                    )
                }

                override fun onMasstransitRoutesError(error: Error) {
                    Log.e(TAG, "Bicycle build error: $error")
                    callback(null)
                }
            }
        )
        addSession(session)
    }

    // -- Helpers --

    private fun buildRequestPoints(
        from: Point, to: Point, waypoints: List<Point>
    ): List<RequestPoint> {
        val points = mutableListOf<RequestPoint>()
        points.add(RequestPoint(from, RequestPointType.WAYPOINT, null, null, null))
        for (wp in waypoints) {
            points.add(RequestPoint(wp, RequestPointType.VIAPOINT, null, null, null))
        }
        points.add(RequestPoint(to, RequestPointType.WAYPOINT, null, null, null))
        return points
    }

    private fun vehicleTypeToSectionType(vehicleTypes: List<String>): TransitSectionType {
        val type = vehicleTypes.firstOrNull()?.lowercase() ?: return TransitSectionType.BUS
        return when {
            type.contains("metro") || type.contains("subway") || type.contains("underground") -> TransitSectionType.METRO
            type.contains("tram") -> TransitSectionType.TRAM
            type.contains("trolleybus") -> TransitSectionType.TROLLEYBUS
            type.contains("suburban") -> TransitSectionType.SUBURBAN
            type.contains("bus") -> TransitSectionType.BUS
            type.contains("train") || type.contains("rail") -> TransitSectionType.TRAIN
            else -> TransitSectionType.BUS
        }
    }

    private fun extractSectionPoints(
        routeGeometry: Polyline,
        subpolyline: Subpolyline
    ): List<Point> {
        val allPoints = routeGeometry.points
        if (allPoints.isEmpty()) return emptyList()

        val begin = subpolyline.begin
        val end = subpolyline.end
        val result = mutableListOf<Point>()

        val startSegIdx = begin.segmentIndex
        val startPos = begin.segmentPosition

        if (startPos > 0.0 && startSegIdx < allPoints.size - 1) {
            result.add(interpolate(allPoints[startSegIdx], allPoints[startSegIdx + 1], startPos))
        } else {
            result.add(allPoints[startSegIdx])
        }

        for (i in (startSegIdx + 1)..end.segmentIndex) {
            if (i < allPoints.size) result.add(allPoints[i])
        }

        val endSegIdx = end.segmentIndex
        val endPos = end.segmentPosition
        if (endPos > 0.0 && endSegIdx < allPoints.size - 1) {
            val endPoint = interpolate(allPoints[endSegIdx], allPoints[endSegIdx + 1], endPos)
            if (result.isEmpty() || result.last() != endPoint) {
                result.add(endPoint)
            }
        }

        return result
    }

    private fun interpolate(a: Point, b: Point, t: Double): Point {
        return Point(
            a.latitude + (b.latitude - a.latitude) * t,
            a.longitude + (b.longitude - a.longitude) * t
        )
    }

    private fun vehicleTypeToInstructionType(vehicleTypes: List<String>): InstructionType {
        val type = vehicleTypes.firstOrNull()?.lowercase() ?: return InstructionType.BOARD_BUS
        return when {
            type.contains("metro") || type.contains("subway") || type.contains("underground") -> InstructionType.BOARD_METRO
            type.contains("tram") -> InstructionType.BOARD_TRAM
            type.contains("trolleybus") -> InstructionType.BOARD_TROLLEYBUS
            type.contains("suburban") -> InstructionType.BOARD_SUBURBAN
            type.contains("bus") -> InstructionType.BOARD_BUS
            type.contains("train") || type.contains("rail") -> InstructionType.BOARD_TRAIN
            else -> InstructionType.BOARD_BUS
        }
    }

    private fun actionToText(action: com.yandex.mapkit.directions.driving.Action): String {
        return when (action) {
            com.yandex.mapkit.directions.driving.Action.STRAIGHT -> "Continue straight"
            com.yandex.mapkit.directions.driving.Action.SLIGHT_LEFT -> "Turn slightly left"
            com.yandex.mapkit.directions.driving.Action.SLIGHT_RIGHT -> "Turn slightly right"
            com.yandex.mapkit.directions.driving.Action.LEFT -> "Turn left"
            com.yandex.mapkit.directions.driving.Action.RIGHT -> "Turn right"
            com.yandex.mapkit.directions.driving.Action.HARD_LEFT -> "Turn hard left"
            com.yandex.mapkit.directions.driving.Action.HARD_RIGHT -> "Turn hard right"
            com.yandex.mapkit.directions.driving.Action.FORK_LEFT -> "Take left fork"
            com.yandex.mapkit.directions.driving.Action.FORK_RIGHT -> "Take right fork"
            com.yandex.mapkit.directions.driving.Action.UTURN_LEFT -> "Make U-turn left"
            com.yandex.mapkit.directions.driving.Action.UTURN_RIGHT -> "Make U-turn right"
            com.yandex.mapkit.directions.driving.Action.ENTER_ROUNDABOUT -> "Enter roundabout"
            com.yandex.mapkit.directions.driving.Action.LEAVE_ROUNDABOUT -> "Exit roundabout"
            com.yandex.mapkit.directions.driving.Action.BOARD_FERRY -> "Board ferry"
            com.yandex.mapkit.directions.driving.Action.LEAVE_FERRY -> "Leave ferry"
            com.yandex.mapkit.directions.driving.Action.EXIT_LEFT -> "Take exit left"
            com.yandex.mapkit.directions.driving.Action.EXIT_RIGHT -> "Take exit right"
            com.yandex.mapkit.directions.driving.Action.WAYPOINT -> "Waypoint reached"
            com.yandex.mapkit.directions.driving.Action.FINISH -> "Arrive at destination"
            com.yandex.mapkit.directions.driving.Action.UNKNOWN -> "Continue"
        }
    }
}
