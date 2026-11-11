package app.transittracker.timetable

import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    @Test
    fun polylineMatchesGoogleReferenceExample() {
        val points = listOf(LatLng(38.5, -120.2), LatLng(40.7, -120.95), LatLng(43.252, -126.453))
        val encoded = Polyline.encode(points)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encoded)
        val decoded = Polyline.decode(encoded)
        assertEquals(points.size, decoded.size)
        points.zip(decoded).forEach { (a, b) ->
            assertTrue(abs(a.lat - b.lat) < 1e-5 && abs(a.lon - b.lon) < 1e-5)
        }
    }

    @Test
    fun douglasPeuckerDropsNearlyCollinearPoints() {
        // A straight north-south line with a 3 m wobble, then a real 200 m corner.
        val line = listOf(
            LatLng(-33.8800, 151.2000),
            LatLng(-33.8790, 151.20003),
            LatLng(-33.8780, 151.2000),
            LatLng(-33.8770, 151.2000),
            LatLng(-33.8770, 151.2022),
        )
        val simplified = Polyline.simplify(line, 10.0)
        assertEquals(listOf(line[0], line[3], line[4]), simplified)
    }

    @Test
    fun haversineIsAboutRight() {
        // Central to Town Hall is roughly 1.05 km.
        val d = Geo.haversineMeters(LatLng(-33.8832, 151.2063), LatLng(-33.8737, 151.2069))
        assertTrue(d in 1000.0..1100.0, "distance $d")
    }

    @Test
    fun routeTypesMapToModes() {
        assertEquals(Mode.TRAIN, ModeBits.modeForRouteType(2))
        assertEquals(Mode.METRO, ModeBits.modeForRouteType(401))
        assertEquals(Mode.LIGHT_RAIL, ModeBits.modeForRouteType(900))
        assertEquals(Mode.BUS, ModeBits.modeForRouteType(712))
        assertEquals(Mode.FERRY, ModeBits.modeForRouteType(4))
        assertEquals(setOf(Mode.TRAIN, Mode.FERRY), ModeBits.decode(ModeBits.encode(listOf(Mode.TRAIN, Mode.FERRY))))
    }
}
