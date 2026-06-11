package com.repository.navigation.provider

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [GoogleRoutingEngine.unionLineNamesByStopPair] -- the pure
 * grouping logic that unions equivalent transit line short-names (34, 34G, 34AS...)
 * across the primary + alternative routes of a single computeRoutes response,
 * keyed by board->alight stop pair.
 *
 * org.json is on the unit-test classpath (Robolectric-free) so JSON fixtures parse
 * directly. The method is reached via reflection because it lives on the engine,
 * but it depends on nothing Android-specific.
 */
class GoogleRoutingLineGroupingTest {

    private val engine = GoogleRoutingEngine()

    @Suppress("UNCHECKED_CAST")
    private fun union(json: String): Map<String, List<String>> {
        val m = GoogleRoutingEngine::class.java
            .getDeclaredMethod("unionLineNamesByStopPair", JSONObject::class.java)
        m.isAccessible = true
        return m.invoke(engine, JSONObject(json)) as Map<String, List<String>>
    }

    /** A transit step with given short name + departure/arrival lat-lng. */
    private fun step(short: String, depLat: Double, depLng: Double, arrLat: Double, arrLng: Double): String = """
        {
          "transitDetails": {
            "transitLine": { "nameShort": "$short" },
            "stopDetails": {
              "departureStop": { "name": "A", "location": { "latLng": { "latitude": $depLat, "longitude": $depLng } } },
              "arrivalStop":   { "name": "B", "location": { "latLng": { "latitude": $arrLat, "longitude": $arrLng } } }
            }
          }
        }
    """.trimIndent()

    private fun route(vararg steps: String): String =
        """{ "legs": [ { "steps": [ ${steps.joinToString(",")} ] } ] }"""

    private fun response(vararg routes: String): String =
        """{ "routes": [ ${routes.joinToString(",")} ] }"""

    @Test
    fun unionsLinesAcrossAlternativesSharingStopPair() {
        // Three alternatives, same board->alight pair, different lines.
        val json = response(
            route(step("34", 1.0, 2.0, 3.0, 4.0)),
            route(step("34G", 1.0, 2.0, 3.0, 4.0)),
            route(step("34AS", 1.0, 2.0, 3.0, 4.0))
        )
        val groups = union(json)
        assertEquals(1, groups.size)
        val lines = groups.values.first()
        assertEquals(listOf("34", "34G", "34AS"), lines)
    }

    @Test
    fun deduplicatesRepeatedLine() {
        val json = response(
            route(step("34", 1.0, 2.0, 3.0, 4.0)),
            route(step("34", 1.0, 2.0, 3.0, 4.0)),
            route(step("34G", 1.0, 2.0, 3.0, 4.0))
        )
        val lines = union(json).values.first()
        assertEquals(listOf("34", "34G"), lines)
    }

    @Test
    fun separatesDifferentStopPairs() {
        // Two legs with distinct stop pairs across alternatives.
        val json = response(
            route(
                step("34", 1.0, 2.0, 3.0, 4.0),
                step("99", 5.0, 6.0, 7.0, 8.0)
            ),
            route(
                step("34G", 1.0, 2.0, 3.0, 4.0),
                step("99X", 5.0, 6.0, 7.0, 8.0)
            )
        )
        val groups = union(json)
        assertEquals(2, groups.size)
        val keys = groups.keys.toList()
        assertEquals(listOf("34", "34G"), groups[keys[0]])
        assertEquals(listOf("99", "99X"), groups[keys[1]])
    }

    @Test
    fun groupsByLatLngDespiteStopNameDifferences() {
        // Same coordinates, different (localized) stop names -> still one group.
        val a = """
            { "transitDetails": { "transitLine": { "nameShort": "34" },
              "stopDetails": {
                "departureStop": { "name": "Main St", "location": { "latLng": { "latitude": 1.0, "longitude": 2.0 } } },
                "arrivalStop":   { "name": "Park",    "location": { "latLng": { "latitude": 3.0, "longitude": 4.0 } } } } } }
        """.trimIndent()
        val b = """
            { "transitDetails": { "transitLine": { "nameShort": "34G" },
              "stopDetails": {
                "departureStop": { "name": "Glavnaya", "location": { "latLng": { "latitude": 1.000001, "longitude": 2.000001 } } },
                "arrivalStop":   { "name": "Pk",       "location": { "latLng": { "latitude": 3.0, "longitude": 4.0 } } } } } }
        """.trimIndent()
        val json = response(route(a), route(b))
        val groups = union(json)
        assertEquals(1, groups.size)
        assertEquals(listOf("34", "34G"), groups.values.first())
    }

    @Test
    fun emptyResponseYieldsEmptyMap() {
        assertTrue(union("""{ "routes": [] }""").isEmpty())
        assertTrue(union("{}").isEmpty())
    }

    @Test
    fun skipsStepsWithoutTransitLine() {
        // A walking step (no transitDetails) must not crash or pollute groups.
        val walk = """{ "navigationInstruction": { "instructions": "Walk" } }"""
        val json = response(route(walk, step("12", 1.0, 2.0, 3.0, 4.0)))
        val groups = union(json)
        assertEquals(1, groups.size)
        assertEquals(listOf("12"), groups.values.first())
    }

    @Suppress("UNCHECKED_CAST")
    private fun merge(vararg jsons: String): Map<String, List<String>> {
        val m = GoogleRoutingEngine::class.java
            .getDeclaredMethod("mergeLineGroups", List::class.java)
        m.isAccessible = true
        val responses: List<JSONObject?> = jsons.map { JSONObject(it) }
        return m.invoke(engine, responses) as Map<String, List<String>>
    }

    @Test
    fun mergesLinesAcrossMultiplePassesForSameStopPair() {
        // Simulates the bounded transit multi-pass fan-out: three separate
        // computeRoutes responses (default, LESS_WALKING, FEWER_TRANSFERS), each
        // surfacing different equivalent lines on the SAME board->alight pair. The
        // merged union must collect ALL distinct nameShort values, in first-seen
        // order across passes, deduped.
        val pass0 = response(
            route(step("34", 1.0, 2.0, 3.0, 4.0)),
            route(step("34G", 1.0, 2.0, 3.0, 4.0))
        )
        val pass1 = response(
            route(step("34", 1.0, 2.0, 3.0, 4.0)),    // dup -> dropped
            route(step("34AS", 1.0, 2.0, 3.0, 4.0)),
            route(step("34C", 1.0, 2.0, 3.0, 4.0))
        )
        val pass2 = response(
            route(step("X34", 1.0, 2.0, 3.0, 4.0)),
            route(step("34G", 1.0, 2.0, 3.0, 4.0))    // dup -> dropped
        )
        val groups = merge(pass0, pass1, pass2)
        assertEquals(1, groups.size)
        assertEquals(listOf("34", "34G", "34AS", "34C", "X34"), groups.values.first())
    }

    @Test
    fun mergePreservesDistinctStopPairsAcrossPasses() {
        // Different passes can also discover entirely new stop pairs (a pass that
        // routes via a different corridor). The merge keeps each pair separate and
        // unions lines per pair.
        val pass0 = response(route(step("10", 1.0, 2.0, 3.0, 4.0)))
        val pass1 = response(
            route(step("10E", 1.0, 2.0, 3.0, 4.0)),       // same pair as pass0
            route(step("500T", 5.0, 6.0, 7.0, 8.0))       // new pair
        )
        val groups = merge(pass0, pass1)
        assertEquals(2, groups.size)
        val keys = groups.keys.toList()
        assertEquals(listOf("10", "10E"), groups[keys[0]])
        assertEquals(listOf("500T"), groups[keys[1]])
    }

    @Test
    fun mergeIgnoresNullAndEmptyResponses() {
        // A failed pass (null response) or an empty one must not break the union.
        val m = GoogleRoutingEngine::class.java
            .getDeclaredMethod("mergeLineGroups", List::class.java)
        m.isAccessible = true
        val responses = listOf<JSONObject?>(
            null,
            JSONObject("""{ "routes": [] }"""),
            JSONObject(response(route(step("7", 1.0, 2.0, 3.0, 4.0))))
        )
        @Suppress("UNCHECKED_CAST")
        val groups = m.invoke(engine, responses) as Map<String, List<String>>
        assertEquals(1, groups.size)
        assertEquals(listOf("7"), groups.values.first())
    }
}
