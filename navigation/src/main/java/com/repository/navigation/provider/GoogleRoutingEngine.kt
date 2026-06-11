package com.repository.navigation.provider

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.repository.navigation.BuildConfig
import com.repository.navigation.model.InstructionType
import com.repository.navigation.model.NavigationInstruction
import com.repository.navigation.model.RouteAlternative
import com.repository.navigation.model.RouteInfo
import com.repository.navigation.model.TransitSection
import com.repository.navigation.model.TransitSectionType
import com.repository.navigation.model.TransportMethodInfo
import com.repository.navigation.model.TransportMode
import com.yandex.mapkit.geometry.Point
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Date
import java.util.Locale

/**
 * Google Routes API v2 implementation of [RoutingEngine]. Mirrors the behaviour
 * and return shapes of YandexRoutingEngine / RouteEngine so the rest of the app
 * sees no difference between providers.
 *
 * All HTTP runs off the main thread (OkHttp enqueue); every callback is delivered
 * on the main thread (Handler on the main Looper) because downstream code assumes
 * main, matching the Yandex implementation.
 */
class GoogleRoutingEngine : RoutingEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private val jsonMedia = "application/json".toMediaType()

    override fun warmUp() {
        // OkHttpClient is created eagerly; nothing else to initialize.
    }

    // region RoutingEngine API

    override fun queryAllModes(
        from: Point,
        to: Point,
        callback: (List<TransportMethodInfo>) -> Unit
    ) {
        val modes = TransportMode.values().toList()
        val results = arrayOfNulls<TransportMethodInfo>(modes.size)
        var remaining = modes.size
        if (remaining == 0) {
            deliverOnMain { callback(emptyList()) }
            return
        }
        for ((index, mode) in modes.withIndex()) {
            // queryMode already delivers on main; aggregate there to stay on main.
            queryMode(from, to, mode) { info ->
                results[index] = info
                remaining -= 1
                if (remaining == 0) {
                    callback(results.filterNotNull())
                }
            }
        }
    }

    override fun queryMode(
        from: Point,
        to: Point,
        mode: TransportMode,
        callback: (TransportMethodInfo?) -> Unit
    ) {
        val body = buildRequestBody(from, to, emptyList(), mode, alternatives = false, departureTime = null, transitPref = null)
        enqueue(body) { json ->
            val info = json?.let { firstRoute(it) }?.let { parseMethodInfo(it, mode) }
            deliverOnMain { callback(info) }
        }
    }

    override fun buildRoute(
        from: Point,
        to: Point,
        waypoints: List<Point>,
        mode: TransportMode,
        callback: (RouteInfo?) -> Unit
    ) {
        val body = buildRequestBody(from, to, waypoints, mode, alternatives = false, departureTime = null, transitPref = null)
        enqueue(body) { json ->
            val lineGroups = json?.let { unionLineNamesByStopPair(it) } ?: emptyMap()
            val info = json?.let { firstRoute(it) }?.let { parseRouteInfo(it, mode, from, to, waypoints, lineGroups) }
            if (info == null) {
                // HTTP can succeed (200) yet carry no routes (e.g. no transit
                // coverage for this area/time -> empty body). Log so a "failed to
                // build route" is diagnosable in the field.
                Log.w(TAG, "buildRoute mode=$mode produced no route (json=${json != null})")
            }
            deliverOnMain { callback(info) }
        }
    }

    override fun rebuildWithWaypoint(
        currentRoute: RouteInfo,
        waypoint: Point,
        callback: (RouteInfo?) -> Unit
    ) {
        val from = currentRoute.from
        val to = currentRoute.to
        val waypoints = currentRoute.waypoints + waypoint
        val mode = currentRoute.mode
        val body = buildRequestBody(from, to, waypoints, mode, alternatives = false, departureTime = null, transitPref = null)
        enqueue(body) { json ->
            val lineGroups = json?.let { unionLineNamesByStopPair(it) } ?: emptyMap()
            val info = json?.let { firstRoute(it) }?.let { parseRouteInfo(it, mode, from, to, waypoints, lineGroups) }
            deliverOnMain { callback(info) }
        }
    }

    override fun buildRouteAlternatives(
        from: Point,
        to: Point,
        mode: TransportMode,
        waypoints: List<Point>,
        departureTime: Date?,
        callback: (List<RouteAlternative>) -> Unit
    ) {
        if (mode == TransportMode.TRANSIT) {
            if (waypoints.isNotEmpty()) {
                // Routes API v2 rejects intermediates for TRANSIT (HTTP 400
                // "Intermediate waypoints are not supported for TRANSIT travel
                // mode"). A multi-stop transit journey must be computed as
                // sequential single-leg transit calls and stitched together,
                // matching the Google Maps "add a stop" behaviour.
                buildChainedTransitAlternatives(from, to, waypoints, departureTime, callback)
                return
            }
            buildTransitAlternativesMultiPass(from, to, waypoints, departureTime, callback)
            return
        }
        val body = buildRequestBody(
            from, to, waypoints, mode,
            alternatives = true, departureTime = departureTime, transitPref = null
        )
        enqueue(body) { json ->
            val routes = json?.optJSONArray("routes")
            if (routes == null || routes.length() == 0) {
                deliverOnMain { callback(emptyList()) }
                return@enqueue
            }
            val lineGroups = unionLineNamesByStopPair(json)
            val alternatives = buildAlternativesFromRoutes(routes, mode, from, to, waypoints, lineGroups)
            deliverOnMain { callback(alternatives) }
        }
    }

    /**
     * Transit-only multi-pass route fetch to maximize the set of equivalent line
     * numbers per board->alight stop pair.
     *
     * Routes API v2 returns exactly one transitLine per step, caps the alternatives
     * of a single computeRoutes call, and accepts only ONE
     * transitPreferences.routingPreference value per request. To surface lines that
     * Google ranks lower we issue a bounded fan-out of computeRoutes calls, each
     * with a different routing-preference bias, then union every returned route's
     * lines by stop-pair into a single lineGroups map shared across all passes.
     *
     * Call budget: at most TRANSIT_PASSES (3) billed computeRoutes calls per
     * planJourneyAlternatives invocation:
     *   pass 0 = default (no preference)  -> also the source of the RouteAlternative list
     *   pass 1 = LESS_WALKING
     *   pass 2 = FEWER_TRANSFERS
     *
     * The RouteAlternative list (the cards the user picks between) comes from pass 0
     * only, so durations/summaries stay deterministic; the extra passes contribute
     * only additional nameShort values into the chip union.
     */
    private fun buildTransitAlternativesMultiPass(
        from: Point,
        to: Point,
        waypoints: List<Point>,
        departureTime: Date?,
        callback: (List<RouteAlternative>) -> Unit
    ) {
        val passes = TRANSIT_ROUTING_PASSES
        val responses = arrayOfNulls<JSONObject>(passes.size)
        var remaining = passes.size
        val lock = Any()

        for ((index, pref) in passes.withIndex()) {
            val body = buildRequestBody(
                from, to, waypoints, TransportMode.TRANSIT,
                alternatives = true, departureTime = departureTime, transitPref = pref
            )
            enqueue(body) { json ->
                synchronized(lock) {
                    responses[index] = json
                    remaining -= 1
                    if (remaining > 0) return@enqueue
                }
                // All passes done. Pass 0 drives the alternative cards; every pass
                // feeds the union so chips carry the widest set of equivalent lines.
                val lineGroups = mergeLineGroups(responses.toList())
                val primary = responses[0]
                val routes = primary?.optJSONArray("routes")
                if (routes == null || routes.length() == 0) {
                    deliverOnMain { callback(emptyList()) }
                    return@enqueue
                }
                val alternatives = buildAlternativesFromRoutes(
                    routes, TransportMode.TRANSIT, from, to, waypoints, lineGroups
                )
                deliverOnMain { callback(alternatives) }
            }
        }
    }

    /**
     * Multi-stop TRANSIT routing. Routes API v2 returns HTTP 400 when a TRANSIT
     * request carries intermediates, so a journey from->wp1->...->wpN->to cannot
     * be one call. Instead split it into consecutive single-leg transit calls
     * (from->wp1, wp1->wp2, ..., wpN->to), each computed via the existing
     * multi-pass preference fan-out WITHOUT intermediates, then stitch the per-leg
     * primary routes into one end-to-end RouteAlternative.
     *
     * Call budget: legs x TRANSIT_ROUTING_PASSES billed computeRoutes calls. A
     * 3-point trip (origin + 1 intermediate + destination) is 2 legs x 3 passes =
     * 6 calls. The legs are fetched SEQUENTIALLY (leg K cannot start until leg K-1
     * resolves) because transit is schedule-dependent: leg K must depart at the
     * arrival time of leg K-1, otherwise Google returns lines that cannot actually
     * be caught after reaching the waypoint. The stitch happens after the last leg.
     *
     * Schedule chaining: leg 1 uses the caller's departureTime (or now if null);
     * leg K>1 departs at leg1Departure + sum(durations of legs 1..K-1), computed by
     * the pure [legDepartureTime] helper and passed into that leg's request body
     * (buildRequestBody already serializes departureTime for TRANSIT as RFC3339).
     *
     * If ANY leg yields no route (e.g. no transit coverage for that segment) the
     * whole chained call fails gracefully: the callback receives an empty list and
     * a clear log line names the failed leg index.
     */
    private fun buildChainedTransitAlternatives(
        from: Point,
        to: Point,
        waypoints: List<Point>,
        departureTime: Date?,
        callback: (List<RouteAlternative>) -> Unit
    ) {
        // Consecutive leg endpoints: [from, wp1, wp2, ..., wpN, to].
        val points = ArrayList<Point>()
        points.add(from)
        points.addAll(waypoints)
        points.add(to)
        val legCount = points.size - 1

        // Leg 1's departure anchors the schedule. Null means "now"; Google rejects a
        // past departureTime, so anchor to the current instant and chain forward.
        val anchorDeparture = departureTime ?: Date()
        val legResults = arrayOfNulls<RouteInfo>(legCount)

        fun fetchLeg(legIndex: Int) {
            if (legIndex >= legCount) {
                val legs = legResults.toList()
                val stitched = stitchTransitLegs(legs)
                if (stitched == null) {
                    val failedLeg = legs.indexOfFirst { it == null }
                    Log.w(TAG, "Chained transit failed: leg $failedLeg of $legCount returned no route")
                    deliverOnMain { callback(emptyList()) }
                    return
                }
                val id = "transit_chained_0"
                val summary = stitched.distanceFormatted
                deliverOnMain {
                    callback(listOf(RouteAlternative(id, TransportMode.TRANSIT, stitched, summary)))
                }
                return
            }

            val legFrom = points[legIndex]
            val legTo = points[legIndex + 1]
            // Departure = anchor + travel time of all already-resolved prior legs.
            val priorDurations = (0 until legIndex).map { legResults[it]?.etaSeconds ?: 0L }
            val legDeparture = legDepartureTime(anchorDeparture, priorDurations)

            // No waypoints per leg => no intermediates => no HTTP 400.
            fetchTransitLegPrimary(legFrom, legTo, legDeparture) { info ->
                if (info == null) {
                    Log.w(TAG, "Chained transit failed: leg $legIndex of $legCount returned no route")
                    deliverOnMain { callback(emptyList()) }
                    return@fetchTransitLegPrimary
                }
                legResults[legIndex] = info
                fetchLeg(legIndex + 1)
            }
        }

        fetchLeg(0)
    }

    /**
     * Departure time for a transit leg in a schedule-chained journey. Pure function
     * (no Android/network), testable directly.
     *
     * Leg 1 departs at [anchorDeparture] (the caller's departureTime, or now). Leg K
     * (K>1) departs at anchorDeparture + the summed travel time of every prior leg,
     * so each computeRoutes call returns transit options actually catchable after
     * arriving at the waypoint. [priorLegDurationsSeconds] are the etaSeconds of the
     * legs preceding this one (empty for leg 1).
     */
    fun legDepartureTime(anchorDeparture: Date, priorLegDurationsSeconds: List<Long>): Date {
        val offsetSeconds = priorLegDurationsSeconds.sum()
        return Date(anchorDeparture.time + offsetSeconds * 1000L)
    }

    /**
     * Fetch ONE transit leg's primary (best) route as a RouteInfo, running the
     * bounded multi-pass preference fan-out so the leg's line chips carry the full
     * equivalent-line union. Delivers off the main thread (no deliverOnMain) so the
     * chained orchestrator can join several legs before hopping to main once.
     * Delivers null if the leg has no transit route.
     */
    private fun fetchTransitLegPrimary(
        from: Point,
        to: Point,
        departureTime: Date?,
        onResult: (RouteInfo?) -> Unit
    ) {
        val passes = TRANSIT_ROUTING_PASSES
        val responses = arrayOfNulls<JSONObject>(passes.size)
        var remaining = passes.size
        val lock = Any()

        for ((index, pref) in passes.withIndex()) {
            val body = buildRequestBody(
                from, to, emptyList(), TransportMode.TRANSIT,
                alternatives = true, departureTime = departureTime, transitPref = pref
            )
            enqueue(body) { json ->
                val done: Boolean
                synchronized(lock) {
                    responses[index] = json
                    remaining -= 1
                    done = remaining == 0
                }
                if (!done) return@enqueue
                val lineGroups = mergeLineGroups(responses.toList())
                val primary = responses[0]
                val route = primary?.let { firstRoute(it) }
                val info = route?.let {
                    parseRouteInfo(it, TransportMode.TRANSIT, from, to, emptyList(), lineGroups)
                }
                onResult(info)
            }
        }
    }

    /**
     * Stitch consecutive transit-leg primary routes into one end-to-end RouteInfo.
     * Pure function (no Android/network), testable with RouteInfo fixtures:
     *   - sections concatenated in leg order (walk/transit/walk per leg, in turn);
     *   - cumulativeEtaSeconds recomputed as a running sum over the full section
     *     list so it is monotonic end-to-end;
     *   - polylinePoints concatenated in leg order;
     *   - etaSeconds / distanceMeters summed across legs, strings re-derived;
     *   - per-section lineShortNames chips carried through unchanged.
     * Returns null if [legs] is empty or any leg is null (leg had no route).
     */
    fun stitchTransitLegs(legs: List<RouteInfo?>): RouteInfo? {
        if (legs.isEmpty()) return null
        if (legs.any { it == null }) return null
        val solid = legs.filterNotNull()

        val polyline = ArrayList<Point>()
        val sections = ArrayList<TransitSection>()
        val instructions = ArrayList<NavigationInstruction>()
        var totalEta = 0L
        var totalDistance = 0
        var running = 0L

        for (leg in solid) {
            polyline.addAll(leg.polylinePoints)
            totalEta += leg.etaSeconds
            totalDistance += leg.distanceMeters
            instructions.addAll(leg.instructions)
            sections.addAll(leg.transitSections)
        }

        // Each leg typically ends with a walk and the next begins with one; after
        // concatenation those become consecutive Walk rows. Merge them (and any other
        // adjacent walks across the boundary) so the itinerary reads as one walk per
        // transfer, matching the per-leg merge done in parseRouteInfo.
        val merged = mergeConsecutiveWalks(sections)

        // Recompute cumulative ETA over the merged list so it stays monotonic.
        val mergedWithEta = ArrayList<TransitSection>(merged.size)
        for (section in merged) {
            running += section.durationSeconds
            mergedWithEta.add(section.copy(cumulativeEtaSeconds = running))
        }

        val first = solid.first()
        val last = solid.last()
        // Intermediates are everything between the first leg's origin and the last
        // leg's destination -- i.e. each interior leg boundary.
        val waypoints = solid.dropLast(1).map { it.to }

        return RouteInfo(
            mode = TransportMode.TRANSIT,
            polylinePoints = polyline,
            etaSeconds = totalEta,
            etaFormatted = formatDuration(totalEta),
            distanceMeters = totalDistance,
            distanceFormatted = formatDistance(totalDistance),
            instructions = instructions,
            from = first.from,
            to = last.to,
            waypoints = waypoints,
            transitSections = mergedWithEta
        )
    }

    private fun buildAlternativesFromRoutes(
        routes: JSONArray,
        mode: TransportMode,
        from: Point,
        to: Point,
        waypoints: List<Point>,
        lineGroups: Map<String, List<String>>
    ): List<RouteAlternative> {
        val alternatives = ArrayList<RouteAlternative>()
        for (i in 0 until routes.length()) {
            val routeJson = routes.optJSONObject(i) ?: continue
            val info = parseRouteInfo(routeJson, mode, from, to, waypoints, lineGroups) ?: continue
            val id = "${mode.name.lowercase(Locale.US)}_$i"
            val summary = buildSummary(routeJson, info)
            alternatives.add(RouteAlternative(id, mode, info, summary))
        }
        return alternatives
    }

    // endregion

    // region HTTP

    private val fieldMask: String = listOf(
        "routes.duration",
        "routes.distanceMeters",
        "routes.polyline.encodedPolyline",
        "routes.description",
        "routes.legs.duration",
        "routes.legs.distanceMeters",
        "routes.legs.polyline.encodedPolyline",
        "routes.legs.steps.distanceMeters",
        "routes.legs.steps.staticDuration",
        "routes.legs.steps.polyline.encodedPolyline",
        "routes.legs.steps.travelMode",
        "routes.legs.steps.navigationInstruction.instructions",
        "routes.legs.steps.transitDetails.transitLine.name",
        "routes.legs.steps.transitDetails.transitLine.nameShort",
        "routes.legs.steps.transitDetails.transitLine.color",
        "routes.legs.steps.transitDetails.transitLine.vehicle.type",
        "routes.legs.steps.transitDetails.stopDetails.departureStop.name",
        "routes.legs.steps.transitDetails.stopDetails.departureStop.location",
        "routes.legs.steps.transitDetails.stopDetails.arrivalStop.name",
        "routes.legs.steps.transitDetails.stopDetails.arrivalStop.location",
        "routes.legs.steps.transitDetails.stopDetails.departureTime",
        "routes.legs.steps.transitDetails.headsign",
        "routes.legs.steps.transitDetails.stopCount",
        "routes.legs.steps.transitDetails.localizedValues.departureTime.time.text"
    ).joinToString(",")

    private fun enqueue(body: JSONObject, onResult: (JSONObject?) -> Unit) {
        val request = try {
            Request.Builder()
                .url("https://routes.googleapis.com/directions/v2:computeRoutes")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Goog-Api-Key", BuildConfig.GOOGLE_MAPS_API_KEY)
                .addHeader("X-Goog-FieldMask", fieldMask)
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build request: ${e.message}")
            onResult(null)
            return
        }

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "HTTP request failed: ${e.message}")
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val raw = resp.body?.string()
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "HTTP ${resp.code}: ${raw ?: "no body"}")
                        onResult(null)
                        return
                    }
                    if (raw.isNullOrBlank()) {
                        Log.e(TAG, "Empty response body")
                        onResult(null)
                        return
                    }
                    val parsed = try {
                        JSONObject(raw)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse response JSON: ${e.message}")
                        null
                    }
                    onResult(parsed)
                }
            }
        })
    }

    private fun firstRoute(json: JSONObject): JSONObject? {
        val routes = json.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null
        return routes.optJSONObject(0)
    }

    private fun deliverOnMain(action: () -> Unit) {
        mainHandler.post(action)
    }

    // endregion

    // region request body

    private fun buildRequestBody(
        from: Point,
        to: Point,
        waypoints: List<Point>,
        mode: TransportMode,
        alternatives: Boolean,
        departureTime: Date?,
        transitPref: String?
    ): JSONObject {
        val body = JSONObject()
        body.put("origin", waypointJson(from))
        body.put("destination", waypointJson(to))
        if (waypoints.isNotEmpty()) {
            val intermediates = JSONArray()
            for (wp in waypoints) {
                intermediates.put(waypointJson(wp))
            }
            body.put("intermediates", intermediates)
        }
        body.put("travelMode", travelModeFor(mode))
        if (mode == TransportMode.DRIVING) {
            body.put("routingPreference", "TRAFFIC_AWARE")
        }
        // Transit needs alternatives ON so we can union equivalent line numbers
        // (e.g. 34, 34G, 34AS) across alternative routes that share a board->alight
        // stop pair. Routes API v2 returns one transitLine per step and gives no
        // documented param to raise the alternatives count, so a single call yields
        // only 2-4 lines per leg; buildTransitAlternativesMultiPass fans this out
        // across routing-preference biases to widen the union. NOTE: Routes API v2
        // returns NO alternatives when intermediates are present, so a transit route
        // with waypoints relies entirely on the multi-pass preference variants.
        body.put("computeAlternativeRoutes", if (mode == TransportMode.TRANSIT) true else alternatives)
        if (mode == TransportMode.TRANSIT) {
            if (transitPref != null) {
                // transitPreferences.routingPreference is a single enum per call
                // (LESS_WALKING | FEWER_TRANSFERS); biases must be tried in
                // separate calls and the results unioned.
                val prefs = JSONObject()
                prefs.put("routingPreference", transitPref)
                body.put("transitPreferences", prefs)
            }
            if (departureTime != null) {
                // RFC3339 UTC timestamp.
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                body.put("departureTime", sdf.format(departureTime))
            }
        }
        body.put("languageCode", Locale.getDefault().toLanguageTag())
        body.put("units", "METRIC")
        return body
    }

    private fun waypointJson(point: Point): JSONObject {
        val latLng = JSONObject()
        latLng.put("latitude", point.latitude)
        latLng.put("longitude", point.longitude)
        val location = JSONObject()
        location.put("latLng", latLng)
        val wp = JSONObject()
        wp.put("location", location)
        return wp
    }

    private fun travelModeFor(mode: TransportMode): String = when (mode) {
        TransportMode.DRIVING -> "DRIVE"
        TransportMode.WALKING -> "WALK"
        TransportMode.BICYCLE -> "BICYCLE"
        TransportMode.TRANSIT -> "TRANSIT"
    }

    // endregion

    // region parsing

    private fun parseMethodInfo(route: JSONObject, mode: TransportMode): TransportMethodInfo {
        val durationSec = parseDurationSeconds(route.optString("duration"))
        val distanceM = route.optInt("distanceMeters", 0)
        return TransportMethodInfo(
            methodId = "${mode.name.lowercase(Locale.US)}_0",
            mode = mode,
            etaSeconds = durationSec,
            etaFormatted = formatDuration(durationSec),
            description = formatDistance(distanceM),
            distanceMeters = distanceM
        )
    }

    private fun parseRouteInfo(
        route: JSONObject,
        mode: TransportMode,
        from: Point,
        to: Point,
        waypoints: List<Point>,
        lineGroups: Map<String, List<String>> = emptyMap()
    ): RouteInfo? {
        val durationSec = parseDurationSeconds(route.optString("duration"))
        val distanceM = route.optInt("distanceMeters", 0)

        val polylinePoints = decodePolyline(
            route.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty()
        ).ifEmpty { decodeGeometryFromLegs(route) }

        val instructions = ArrayList<NavigationInstruction>()
        val transitSections = ArrayList<TransitSection>()
        var cumulativeEta = 0L

        val legs = route.optJSONArray("legs")
        if (legs != null) {
            for (li in 0 until legs.length()) {
                val leg = legs.optJSONObject(li) ?: continue
                val steps = leg.optJSONArray("steps") ?: continue
                for (si in 0 until steps.length()) {
                    val step = steps.optJSONObject(si) ?: continue
                    val stepDistanceM = step.optInt("distanceMeters", 0)
                    val stepDurationSec = parseDurationSeconds(step.optString("staticDuration"))
                    val transitDetails = step.optJSONObject("transitDetails")
                    val stepGeometry = decodePolyline(
                        step.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty()
                    )

                    if (mode == TransportMode.TRANSIT) {
                        cumulativeEta += stepDurationSec
                        if (transitDetails != null) {
                            val section = buildTransitSection(
                                transitDetails, stepDurationSec, stepDistanceM,
                                cumulativeEta, stepGeometry, lineGroups
                            )
                            transitSections.add(section)
                            instructions.add(boardInstruction(section))
                        } else {
                            transitSections.add(
                                walkSection(stepDurationSec, stepDistanceM, cumulativeEta, stepGeometry)
                            )
                            instructions.add(NavigationInstruction(type = InstructionType.WALK, text = "Walk"))
                        }
                    } else {
                        instructions.add(
                            buildManeuverInstruction(step, mode)
                        )
                    }
                }
            }
        }

        // Google returns each walking maneuver as its own step, so a transit route
        // can show many consecutive "Walk" sections between legs. Merge them into a
        // single Walk (matching Yandex, which collapses consecutive walks).
        val mergedSections = mergeConsecutiveWalks(transitSections)

        return RouteInfo(
            mode = mode,
            polylinePoints = polylinePoints,
            etaSeconds = durationSec,
            etaFormatted = formatDuration(durationSec),
            distanceMeters = distanceM,
            distanceFormatted = formatDistance(distanceM),
            instructions = instructions,
            from = from,
            to = to,
            waypoints = waypoints,
            transitSections = mergedSections
        )
    }

    /** Collapse consecutive WALK sections into one (Google emits a walk step per
     *  maneuver; Yandex merges them). Accumulates duration/distance/geometry. */
    private fun mergeConsecutiveWalks(sections: List<TransitSection>): List<TransitSection> {
        if (sections.size < 2) return sections
        val merged = mutableListOf<TransitSection>()
        var i = 0
        while (i < sections.size) {
            val current = sections[i]
            if (current.type == TransitSectionType.WALK &&
                i + 1 < sections.size && sections[i + 1].type == TransitSectionType.WALK) {
                var totalDuration = current.durationSeconds
                var totalDistance = current.distanceMeters
                val points = current.polylinePoints.toMutableList()
                var j = i + 1
                while (j < sections.size && sections[j].type == TransitSectionType.WALK) {
                    totalDuration += sections[j].durationSeconds
                    totalDistance += sections[j].distanceMeters
                    points.addAll(sections[j].polylinePoints)
                    j++
                }
                merged.add(current.copy(
                    polylinePoints = points,
                    durationSeconds = totalDuration,
                    durationFormatted = formatDuration(totalDuration),
                    distanceMeters = totalDistance,
                    distanceFormatted = formatDistance(totalDistance),
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

    private fun decodeGeometryFromLegs(route: JSONObject): List<Point> {
        val combined = ArrayList<Point>()
        val legs = route.optJSONArray("legs") ?: return combined
        for (li in 0 until legs.length()) {
            val leg = legs.optJSONObject(li) ?: continue
            val encoded = leg.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty()
            combined.addAll(decodePolyline(encoded))
        }
        return combined
    }

    private fun buildManeuverInstruction(step: JSONObject, mode: TransportMode): NavigationInstruction {
        val nav = step.optJSONObject("navigationInstruction")
        val text = nav?.optString("instructions").orEmpty()
        val type = when (mode) {
            TransportMode.DRIVING -> InstructionType.DRIVE
            TransportMode.BICYCLE -> InstructionType.CYCLE
            TransportMode.WALKING -> InstructionType.WALK
            TransportMode.TRANSIT -> InstructionType.WALK
        }
        return NavigationInstruction(type = type, text = if (text.isNotBlank()) text else "Continue")
    }

    /**
     * Union the transit line short-names across ALL routes (primary + alternatives)
     * in one computeRoutes response, grouped by board->alight stop pair. Google
     * returns exactly one transitLine per step, so the only way to surface the full
     * set of equivalent lines (34, 34G, 34AS...) is to merge across alternatives
     * that ride the same stop pair.
     *
     * Returns a map from stopPairKey -> ordered de-duplicated list of nameShort
     * values (first-seen order across the routes array). Pure function: testable
     * with a JSON fixture, no Android/network dependencies.
     */
    /**
     * Union the per-response stop-pair line maps from several computeRoutes passes
     * into one map. Preserves first-seen order both of stop-pair keys and of the
     * nameShort values within each key, deduplicating across passes. Pure function.
     */
    fun mergeLineGroups(responses: List<JSONObject?>): Map<String, List<String>> {
        val merged = LinkedHashMap<String, MutableList<String>>()
        for (json in responses) {
            if (json == null) continue
            for ((key, names) in unionLineNamesByStopPair(json)) {
                val bucket = merged.getOrPut(key) { mutableListOf() }
                for (name in names) if (name !in bucket) bucket.add(name)
            }
        }
        return merged
    }

    fun unionLineNamesByStopPair(json: JSONObject): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        val routes = json.optJSONArray("routes") ?: return result
        for (ri in 0 until routes.length()) {
            val route = routes.optJSONObject(ri) ?: continue
            val legs = route.optJSONArray("legs") ?: continue
            for (li in 0 until legs.length()) {
                val leg = legs.optJSONObject(li) ?: continue
                val steps = leg.optJSONArray("steps") ?: continue
                for (si in 0 until steps.length()) {
                    val step = steps.optJSONObject(si) ?: continue
                    val transitDetails = step.optJSONObject("transitDetails") ?: continue
                    val short = transitDetails.optJSONObject("transitLine")
                        ?.optString("nameShort")?.takeIf { it.isNotBlank() } ?: continue
                    val key = stopPairKey(transitDetails.optJSONObject("stopDetails"))
                    val bucket = result.getOrPut(key) { mutableListOf() }
                    if (short !in bucket) bucket.add(short)
                }
            }
        }
        return result
    }

    /**
     * Stable grouping key for a transit step's board->alight pair. Prefers stop
     * lat-lng (robust against localized/renamed stop strings); falls back to stop
     * names when locations are absent.
     */
    private fun stopPairKey(stopDetails: JSONObject?): String {
        if (stopDetails == null) return "?->?"
        val dep = stopDetails.optJSONObject("departureStop")
        val arr = stopDetails.optJSONObject("arrivalStop")
        val depKey = stopKey(dep)
        val arrKey = stopKey(arr)
        return "$depKey->$arrKey"
    }

    private fun stopKey(stop: JSONObject?): String {
        if (stop == null) return "?"
        val latLng = stop.optJSONObject("location")?.optJSONObject("latLng")
        if (latLng != null && latLng.has("latitude") && latLng.has("longitude")) {
            val lat = latLng.optDouble("latitude")
            val lng = latLng.optDouble("longitude")
            // Round to ~1m so float jitter across alternatives still groups together.
            return "${String.format(Locale.US, "%.5f", lat)},${String.format(Locale.US, "%.5f", lng)}"
        }
        return stop.optString("name").takeIf { it.isNotBlank() } ?: "?"
    }

    private fun buildTransitSection(
        transitDetails: JSONObject,
        durationSec: Long,
        distanceM: Int,
        cumulativeEta: Long,
        geometry: List<Point>,
        lineGroups: Map<String, List<String>> = emptyMap()
    ): TransitSection {
        val line = transitDetails.optJSONObject("transitLine")
        val lineName = line?.optString("name")?.takeIf { it.isNotBlank() }
        val lineShortName = line?.optString("nameShort")?.takeIf { it.isNotBlank() }
        val lineColor = parseColor(line?.optString("color"))
        val vehicleType = line?.optJSONObject("vehicle")?.optString("type")?.takeIf { it.isNotBlank() }

        val stopDetails = transitDetails.optJSONObject("stopDetails")
        val boardStop = stopDetails?.optJSONObject("departureStop")?.optString("name")?.takeIf { it.isNotBlank() }
        val alightStop = stopDetails?.optJSONObject("arrivalStop")?.optString("name")?.takeIf { it.isNotBlank() }

        // Equivalent line numbers (e.g. 34, 34G, 34AS) unioned across alternative
        // routes sharing this board->alight stop pair. Primary line first, then the
        // rest in first-seen order. Falls back to the single line when no group.
        val groupKey = stopPairKey(stopDetails)
        val grouped = lineGroups[groupKey].orEmpty()
        val lineShortNames = when {
            grouped.isNotEmpty() -> {
                if (lineShortName != null) {
                    (listOf(lineShortName) + grouped.filter { it != lineShortName })
                } else {
                    grouped
                }
            }
            lineShortName != null -> listOf(lineShortName)
            else -> emptyList()
        }
        val departureText = stopDetails?.optJSONObject("departureTime")?.let { timestampText(it) }
            ?: transitDetails.optJSONObject("localizedValues")
                ?.optJSONObject("departureTime")?.optJSONObject("time")?.optString("text")
                ?.takeIf { it.isNotBlank() }
        val direction = transitDetails.optString("headsign").takeIf { it.isNotBlank() }
        val stopCount = transitDetails.optInt("stopCount", 0)

        return TransitSection(
            type = mapVehicleType(vehicleType),
            polylinePoints = geometry,
            durationSeconds = durationSec,
            durationFormatted = formatDuration(durationSec),
            distanceMeters = distanceM,
            distanceFormatted = formatDistance(distanceM),
            lineName = lineName,
            lineShortName = lineShortName,
            lineShortNames = lineShortNames,
            vehicleType = vehicleType,
            boardStop = boardStop,
            alightStop = alightStop,
            stopCount = stopCount,
            cumulativeEtaSeconds = cumulativeEta,
            direction = direction,
            isTransfer = false,
            lineColor = lineColor,
            departureTimeText = departureText
        )
    }

    private fun walkSection(
        durationSec: Long,
        distanceM: Int,
        cumulativeEta: Long,
        geometry: List<Point>
    ): TransitSection = TransitSection(
        type = TransitSectionType.WALK,
        polylinePoints = geometry,
        durationSeconds = durationSec,
        durationFormatted = formatDuration(durationSec),
        distanceMeters = distanceM,
        distanceFormatted = formatDistance(distanceM),
        lineName = null,
        lineShortName = null,
        vehicleType = null,
        boardStop = null,
        alightStop = null,
        stopCount = 0,
        cumulativeEtaSeconds = cumulativeEta,
        direction = null,
        isTransfer = false,
        lineColor = null,
        departureTimeText = null
    )

    private fun boardInstruction(section: TransitSection): NavigationInstruction {
        val type = when (section.type) {
            TransitSectionType.BUS -> InstructionType.BOARD_BUS
            TransitSectionType.METRO -> InstructionType.BOARD_METRO
            TransitSectionType.TRAM -> InstructionType.BOARD_TRAM
            TransitSectionType.TROLLEYBUS -> InstructionType.BOARD_TROLLEYBUS
            TransitSectionType.TRAIN -> InstructionType.BOARD_TRAIN
            TransitSectionType.SUBURBAN -> InstructionType.BOARD_SUBURBAN
            TransitSectionType.FERRY -> InstructionType.BOARD_FERRY
            TransitSectionType.CABLE_CAR -> InstructionType.BOARD_CABLE_CAR
            TransitSectionType.FUNICULAR -> InstructionType.BOARD_FUNICULAR
            TransitSectionType.GONDOLA -> InstructionType.BOARD_GONDOLA
            TransitSectionType.HIGH_SPEED_TRAIN -> InstructionType.BOARD_HIGH_SPEED_TRAIN
            TransitSectionType.SHARE_TAXI -> InstructionType.BOARD_SHARE_TAXI
            TransitSectionType.OTHER -> InstructionType.BOARD_OTHER
            TransitSectionType.WALK -> InstructionType.WALK
        }
        val label = section.lineShortName ?: section.lineName ?: ""
        return NavigationInstruction(
            type = type,
            text = if (label.isNotBlank()) "Board $label" else "Board",
            lineName = section.lineName ?: section.lineShortName,
            stopName = section.boardStop
        )
    }

    private fun buildSummary(route: JSONObject, info: RouteInfo): String {
        val description = route.optString("description").orEmpty()
        return if (description.isNotBlank()) description else info.distanceFormatted
    }

    // endregion

    // region mapping

    private fun mapVehicleType(type: String?): TransitSectionType {
        val t = type?.lowercase(Locale.US) ?: return TransitSectionType.OTHER
        return when {
            t.contains("high_speed") || t.contains("long_distance") -> TransitSectionType.HIGH_SPEED_TRAIN
            t.contains("subway") || t.contains("metro") -> TransitSectionType.METRO
            t.contains("tram") || t.contains("light_rail") || t.contains("monorail") -> TransitSectionType.TRAM
            t.contains("trolleybus") -> TransitSectionType.TROLLEYBUS
            t.contains("share_taxi") -> TransitSectionType.SHARE_TAXI
            t.contains("ferry") -> TransitSectionType.FERRY
            t.contains("cable_car") -> TransitSectionType.CABLE_CAR
            t.contains("funicular") -> TransitSectionType.FUNICULAR
            t.contains("gondola") || t.contains("aerial") -> TransitSectionType.GONDOLA
            t.contains("commuter") || t.contains("heavy_rail") || t.contains("suburban") -> TransitSectionType.SUBURBAN
            t.contains("rail") || t.contains("train") -> TransitSectionType.TRAIN
            t.contains("bus") -> TransitSectionType.BUS
            else -> TransitSectionType.OTHER
        }
    }

    // endregion

    // region low-level helpers

    /** Parses a protobuf duration string such as "123s" into whole seconds. */
    private fun parseDurationSeconds(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val trimmed = raw.trim().removeSuffix("s")
        return trimmed.toDoubleOrNull()?.toLong() ?: 0L
    }

    private fun formatDuration(seconds: Long): String =
        com.repository.navigation.util.DurationFormat.format(seconds)

    private fun formatDistance(meters: Int): String {
        return if (meters >= 1000) {
            String.format(Locale.US, "%.1f km", meters / 1000.0)
        } else {
            String.format(Locale.US, "%d m", meters)
        }
    }

    /** Parses a "#RRGGBB" color into an opaque ARGB int, or null if unparseable. */
    private fun parseColor(color: String?): Int? {
        if (color.isNullOrBlank()) return null
        val hex = color.trim().removePrefix("#")
        if (hex.length != 6) return null
        val rgb = hex.toLongOrNull(16) ?: return null
        return 0xFF000000.toInt() or (rgb.toInt() and 0x00FFFFFF)
    }

    private fun timestampText(obj: JSONObject): String? {
        val text = obj.optString("text")
        return text.takeIf { it.isNotBlank() }
    }

    /** Decodes a Google encoded polyline (precision 1e5) into a list of points. */
    private fun decodePolyline(encoded: String): List<Point> {
        if (encoded.isBlank()) return emptyList()
        val result = ArrayList<Point>()
        var index = 0
        val length = encoded.length
        var lat = 0
        var lng = 0

        while (index < length) {
            var shift = 0
            var bits = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                bits = bits or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < length)
            val deltaLat = if (bits and 1 != 0) (bits shr 1).inv() else bits shr 1
            lat += deltaLat

            shift = 0
            bits = 0
            do {
                b = encoded[index++].code - 63
                bits = bits or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < length)
            val deltaLng = if (bits and 1 != 0) (bits shr 1).inv() else bits shr 1
            lng += deltaLng

            result.add(Point(lat / 1e5, lng / 1e5))
        }
        return result
    }

    // endregion

    companion object {
        private const val TAG = "GoogleRoutingEngine"

        /**
         * Bounded set of transit routing-preference biases fanned out per
         * planJourneyAlternatives call. One billed computeRoutes call per entry
         * (3 total). null = no preference (also the source of the alternative cards);
         * the others surface lines Google ranks lower under the default bias.
         */
        private val TRANSIT_ROUTING_PASSES: List<String?> =
            listOf(null, "LESS_WALKING", "FEWER_TRANSFERS")
    }
}
