package com.repository.navigation.provider

import com.repository.navigation.model.TransitSectionType
import com.yandex.mapkit.geometry.Point
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for the pure-logic pieces of [GoogleRoutingEngine].
 *
 * Both targeted functions (decodePolyline, mapVehicleType) are private instance
 * methods, so they are reached via reflection on a freshly constructed engine.
 * GoogleRoutingEngine's field initializers (Handler(Looper.getMainLooper()),
 * OkHttpClient()) construct fine on the plain JVM unit-test classpath, and
 * com.yandex.mapkit.geometry.Point is constructable on the JVM as well, which is
 * what makes these tests feasible without Robolectric.
 *
 * The encoder (GoogleStaticMapSource.encodePolyline) is deliberately NOT covered
 * here: its enclosing class takes an android.content.Context in its constructor,
 * which is not available on the pure JVM classpath. A decode->encode roundtrip
 * would therefore require Robolectric (rejected by the task) or reimplementing an
 * encoder inside the test (which would not exercise production code).
 */
class GoogleRoutingEngineTest {

    private val engine = GoogleRoutingEngine()

    @Suppress("UNCHECKED_CAST")
    private fun decode(encoded: String): List<Point> {
        val m = GoogleRoutingEngine::class.java
            .getDeclaredMethod("decodePolyline", String::class.java)
        m.isAccessible = true
        return m.invoke(engine, encoded) as List<Point>
    }

    private fun mapVehicle(type: String?): TransitSectionType {
        val m = GoogleRoutingEngine::class.java
            .getDeclaredMethod("mapVehicleType", String::class.java)
        m.isAccessible = true
        return m.invoke(engine, type) as TransitSectionType
    }

    // region feasibility: Point on the JVM

    @Test
    fun yandexPointConstructsOnJvm() {
        val p = Point(38.5, -120.2)
        assertEquals(38.5, p.latitude, 1e-9)
        assertEquals(-120.2, p.longitude, 1e-9)
    }

    // endregion

    // region decodePolyline

    @Test
    fun decodesGoogleCanonicalExample() {
        // The canonical example from Google's encoded-polyline documentation.
        val points = decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)

        assertEquals(38.5, points[0].latitude, 1e-5)
        assertEquals(-120.2, points[0].longitude, 1e-5)

        assertEquals(40.7, points[1].latitude, 1e-5)
        assertEquals(-120.95, points[1].longitude, 1e-5)

        assertEquals(43.252, points[2].latitude, 1e-5)
        assertEquals(-126.453, points[2].longitude, 1e-5)
    }

    @Test
    fun decodesEmptyStringToEmptyList() {
        assertEquals(0, decode("").size)
        assertEquals(0, decode("   ").size)
    }

    @Test
    fun decodesFirstPairOfCanonicalExampleInIsolation() {
        // The first coordinate pair of the canonical example, on its own, must
        // decode to that same first point. These bytes are authoritative (taken
        // verbatim from Google's documented example), not hand-rolled.
        val points = decode("_p~iF~ps|U")
        assertEquals(1, points.size)
        assertEquals(38.5, points[0].latitude, 1e-5)
        assertEquals(-120.2, points[0].longitude, 1e-5)
    }

    // endregion

    // region mapVehicleType

    @Test
    fun mapsKnownVehicleTypes() {
        assertEquals(TransitSectionType.FERRY, mapVehicle("FERRY"))
        assertEquals(TransitSectionType.METRO, mapVehicle("SUBWAY"))
        // HEAVY_RAIL matches "heavy_rail" -> SUBURBAN (commuter/heavy_rail/suburban).
        assertEquals(TransitSectionType.SUBURBAN, mapVehicle("HEAVY_RAIL"))
        assertEquals(TransitSectionType.TRAM, mapVehicle("TRAM"))
        assertEquals(TransitSectionType.BUS, mapVehicle("BUS"))
        assertEquals(TransitSectionType.FUNICULAR, mapVehicle("FUNICULAR"))
        // GONDOLA_LIFT matches "gondola" -> GONDOLA.
        assertEquals(TransitSectionType.GONDOLA, mapVehicle("GONDOLA_LIFT"))
        assertEquals(TransitSectionType.CABLE_CAR, mapVehicle("CABLE_CAR"))
        // LONG_DISTANCE_TRAIN matches "long_distance" -> HIGH_SPEED_TRAIN.
        assertEquals(TransitSectionType.HIGH_SPEED_TRAIN, mapVehicle("LONG_DISTANCE_TRAIN"))
        assertEquals(TransitSectionType.HIGH_SPEED_TRAIN, mapVehicle("HIGH_SPEED_TRAIN"))
        assertEquals(TransitSectionType.SHARE_TAXI, mapVehicle("SHARE_TAXI"))
    }

    @Test
    fun mapsCaseInsensitively() {
        assertEquals(TransitSectionType.BUS, mapVehicle("bus"))
        assertEquals(TransitSectionType.METRO, mapVehicle("Subway"))
    }

    @Test
    fun mapsUnknownAndNullToOther() {
        assertEquals(TransitSectionType.OTHER, mapVehicle("SPACESHIP"))
        assertEquals(TransitSectionType.OTHER, mapVehicle(""))
        assertEquals(TransitSectionType.OTHER, mapVehicle(null))
    }

    // endregion
}
