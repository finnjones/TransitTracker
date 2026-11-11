package app.transittracker.server.app

import app.transittracker.model.LatLng
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolylineTest {
    @Test
    fun `encodes the example from Google's documentation`() {
        val points = listOf(LatLng(38.5, -120.2), LatLng(40.7, -120.95), LatLng(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Polyline.encode(points))
    }

    @Test
    fun `encodes the single-value example from Google's documentation`() {
        // -179.9832104 encodes as "`~oia@"; a zero delta encodes as "?".
        assertEquals("`~oia@?", Polyline.encode(listOf(LatLng(-179.9832104, 0.0))))
    }

    @Test
    fun `empty and origin`() {
        assertEquals("", Polyline.encode(emptyList()))
        assertEquals("??", Polyline.encode(listOf(LatLng(0.0, 0.0))))
    }

    @Test
    fun `decode is the inverse of encode at precision 5`() {
        val points = listOf(
            LatLng(-33.882896, 151.206681), // Central
            LatLng(-33.873211, 151.206970),
            LatLng(-33.865143, 151.211493),
            LatLng(-33.865143, 151.211493), // repeated point -> zero deltas
            LatLng(-33.8615, 151.2108),
        )
        val decoded = Polyline.decode(Polyline.encode(points))
        assertEquals(points.size, decoded.size)
        points.zip(decoded).forEach { (a, b) ->
            assertTrue(abs(a.lat - b.lat) <= 0.000005 && abs(a.lon - b.lon) <= 0.000005, "$a vs $b")
        }
        assertEquals(
            listOf(LatLng(38.5, -120.2), LatLng(40.7, -120.95), LatLng(43.252, -126.453)),
            Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@"),
        )
    }
}
