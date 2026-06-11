package com.repository.navigation.provider

import com.repository.navigation.model.NavigationInstruction
import com.repository.navigation.model.InstructionType
import com.repository.navigation.model.RouteInfo
import com.repository.navigation.model.TransitSection
import com.repository.navigation.model.TransitSectionType
import com.repository.navigation.model.TransportMode
import com.yandex.mapkit.geometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

/**
 * JVM unit tests for [GoogleRoutingEngine.stitchTransitLegs] -- the pure logic
 * that stitches the per-leg primary transit routes of a multi-stop journey
 * (origin -> intermediate -> destination, computed as sequential single-leg
 * transit calls because Routes API v2 rejects intermediates for TRANSIT) into one
 * end-to-end RouteInfo.
 *
 * No Android/network dependencies: RouteInfo / TransitSection are plain data
 * classes and Point is a value type, so fixtures build directly.
 */
class GoogleTransitChainingTest {

    private val engine = GoogleRoutingEngine()

    private fun section(
        type: TransitSectionType,
        duration: Long,
        distance: Int,
        cumulative: Long,
        lineShortNames: List<String> = emptyList(),
        pts: List<Point> = emptyList()
    ): TransitSection = TransitSection(
        type = type,
        polylinePoints = pts,
        durationSeconds = duration,
        durationFormatted = "${duration}s",
        distanceMeters = distance,
        distanceFormatted = "${distance}m",
        lineName = if (lineShortNames.isNotEmpty()) lineShortNames.first() else null,
        lineShortName = lineShortNames.firstOrNull(),
        lineShortNames = lineShortNames,
        vehicleType = null,
        boardStop = null,
        alightStop = null,
        stopCount = 0,
        cumulativeEtaSeconds = cumulative
    )

    private fun leg(
        from: Point,
        to: Point,
        etaSeconds: Long,
        distanceMeters: Int,
        sections: List<TransitSection>,
        polyline: List<Point>
    ): RouteInfo = RouteInfo(
        mode = TransportMode.TRANSIT,
        polylinePoints = polyline,
        etaSeconds = etaSeconds,
        etaFormatted = "x",
        distanceMeters = distanceMeters,
        distanceFormatted = "x",
        instructions = listOf(NavigationInstruction(type = InstructionType.WALK, text = "Walk")),
        from = from,
        to = to,
        waypoints = emptyList(),
        transitSections = sections
    )

    private val origin = Point(41.0, 28.0)
    private val mid = Point(41.1, 28.1)
    private val dest = Point(41.2, 28.2)

    private fun twoLegFixture(): List<RouteInfo> {
        // Leg 1: walk(60) + bus(300). Leg-local cumulative: 60, 360.
        val leg1 = leg(
            from = origin, to = mid, etaSeconds = 360, distanceMeters = 4000,
            sections = listOf(
                section(TransitSectionType.WALK, 60, 200, 60, pts = listOf(Point(1.0, 1.0))),
                section(TransitSectionType.BUS, 300, 3800, 360, lineShortNames = listOf("34", "34G"),
                    pts = listOf(Point(2.0, 2.0)))
            ),
            polyline = listOf(Point(1.0, 1.0), Point(2.0, 2.0))
        )
        // Leg 2: metro(420) + walk(120). Leg-local cumulative: 420, 540.
        val leg2 = leg(
            from = mid, to = dest, etaSeconds = 540, distanceMeters = 6000,
            sections = listOf(
                section(TransitSectionType.METRO, 420, 5500, 420, lineShortNames = listOf("M2"),
                    pts = listOf(Point(3.0, 3.0))),
                section(TransitSectionType.WALK, 120, 500, 540, pts = listOf(Point(4.0, 4.0)))
            ),
            polyline = listOf(Point(3.0, 3.0), Point(4.0, 4.0))
        )
        return listOf(leg1, leg2)
    }

    @Test
    fun stitchesSectionsInOrder() {
        val stitched = engine.stitchTransitLegs(twoLegFixture())!!
        val types = stitched.transitSections.map { it.type }
        assertEquals(
            listOf(
                TransitSectionType.WALK,
                TransitSectionType.BUS,
                TransitSectionType.METRO,
                TransitSectionType.WALK
            ),
            types
        )
    }

    @Test
    fun cumulativeEtaIsMonotonicRunningSum() {
        val stitched = engine.stitchTransitLegs(twoLegFixture())!!
        val cumulative = stitched.transitSections.map { it.cumulativeEtaSeconds }
        // Running sum of 60, 300, 420, 120 = 60, 360, 780, 900.
        assertEquals(listOf(60L, 360L, 780L, 900L), cumulative)
        // Strictly monotonic non-decreasing end-to-end.
        for (i in 1 until cumulative.size) {
            assert(cumulative[i] >= cumulative[i - 1])
        }
        // Final cumulative == sum of all section durations.
        val sumDurations = stitched.transitSections.sumOf { it.durationSeconds }
        assertEquals(sumDurations, cumulative.last())
    }

    @Test
    fun etaAndDistanceAreSummedAcrossLegs() {
        val legs = twoLegFixture()
        val stitched = engine.stitchTransitLegs(legs)!!
        assertEquals(legs.sumOf { it.etaSeconds }, stitched.etaSeconds)
        assertEquals(legs.sumOf { it.distanceMeters }, stitched.distanceMeters)
        assertEquals(900L, stitched.etaSeconds)
        assertEquals(10000, stitched.distanceMeters)
    }

    @Test
    fun polylinePointsConcatenatedInOrder() {
        val stitched = engine.stitchTransitLegs(twoLegFixture())!!
        // Compare by lat/lng: Yandex Point uses identity equals, so map to a value
        // representation before asserting order.
        val actual = stitched.polylinePoints.map { it.latitude to it.longitude }
        assertEquals(
            listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0, 4.0 to 4.0),
            actual
        )
    }

    @Test
    fun lineShortNamesPreservedPerSection() {
        val stitched = engine.stitchTransitLegs(twoLegFixture())!!
        val bus = stitched.transitSections.first { it.type == TransitSectionType.BUS }
        val metro = stitched.transitSections.first { it.type == TransitSectionType.METRO }
        assertEquals(listOf("34", "34G"), bus.lineShortNames)
        assertEquals(listOf("M2"), metro.lineShortNames)
    }

    @Test
    fun endpointsAndWaypointsReflectFullJourney() {
        val stitched = engine.stitchTransitLegs(twoLegFixture())!!
        assertEquals(origin.latitude to origin.longitude, stitched.from.latitude to stitched.from.longitude)
        assertEquals(dest.latitude to dest.longitude, stitched.to.latitude to stitched.to.longitude)
        assertEquals(1, stitched.waypoints.size)
        assertEquals(mid.latitude to mid.longitude, stitched.waypoints[0].latitude to stitched.waypoints[0].longitude)
        assertEquals(TransportMode.TRANSIT, stitched.mode)
    }

    @Test
    fun anyNullLegYieldsNull() {
        val legs = twoLegFixture()
        assertNull(engine.stitchTransitLegs(listOf(legs[0], null)))
        assertNull(engine.stitchTransitLegs(listOf(null, legs[1])))
    }

    @Test
    fun emptyLegsYieldNull() {
        assertNull(engine.stitchTransitLegs(emptyList()))
    }

    // region schedule chaining: legDepartureTime

    @Test
    fun firstLegDepartsAtAnchor() {
        // Leg 1 has no prior legs, so it departs exactly at the caller's anchor.
        val anchor = Date(1_700_000_000_000L)
        assertEquals(anchor, engine.legDepartureTime(anchor, emptyList()))
    }

    @Test
    fun legKDepartsAtAnchorPlusSumOfPriorLegDurations() {
        // Real transit is schedule-dependent: leg K must depart at the arrival time
        // of leg K-1, i.e. anchor + sum(durations of legs 1..K-1).
        val anchor = Date(1_700_000_000_000L)
        // Leg 2 after a 600s leg 1.
        assertEquals(Date(anchor.time + 600_000L), engine.legDepartureTime(anchor, listOf(600L)))
        // Leg 3 after legs of 600s and 300s.
        assertEquals(Date(anchor.time + 900_000L), engine.legDepartureTime(anchor, listOf(600L, 300L)))
    }

    // endregion
}
