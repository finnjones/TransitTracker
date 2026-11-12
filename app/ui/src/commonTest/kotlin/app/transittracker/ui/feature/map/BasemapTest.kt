package app.transittracker.ui.feature.map

import app.transittracker.core.encodePolyline
import app.transittracker.model.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BasemapTest {
    private val ring = listOf(LatLng(-33.85, 151.20), LatLng(-33.85, 151.25), LatLng(-33.86, 151.25), LatLng(-33.85, 151.20))
    private val road = listOf(LatLng(-33.87, 151.20), LatLng(-33.88, 151.21))

    @Test
    fun parsesEachKindIntoFlatArraysWithBoxes() {
        val text = buildString {
            appendLine("TTBASEMAP 1 -34.20 150.55 -33.40 151.40")
            appendLine("W" + encodePolyline(ring))
            appendLine("M" + encodePolyline(road))
            appendLine("X" + encodePolyline(road)) // an unknown kind is skipped
            appendLine("L" + encodePolyline(road.take(1))) // one point is no line
        }
        val map = Basemap.parse(text)!!
        assertEquals(listOf(BaseKind.WATER, BaseKind.MAIN), map.kinds.toList())
        assertEquals(listOf(0, 4, 6), map.starts.toList())
        val p = MapProjection.Sydney
        val a = p.project(road[0])
        val b = p.project(road[1])
        // The road's box, from its two ends.
        assertEquals(minOf(a.x, b.x), map.boxes[4].toDouble(), 0.5)
        assertEquals(maxOf(a.y, b.y), map.boxes[7].toDouble(), 0.5)
        assertTrue(map.meets(1, MapBox(a.x - 1, a.y - 1, a.x + 1, a.y + 1)))
        assertTrue(!map.meets(1, MapBox(a.x + 5_000, a.y, a.x + 6_000, a.y + 1)))
        assertTrue(map.bounds.width > 70_000) // the header's area, 0.85 degrees of longitude
    }

    @Test
    fun aFileWithoutTheHeaderIsNotABaseMap() {
        assertNull(Basemap.parse("W" + encodePolyline(ring)))
        assertNull(Basemap.parse(""))
    }

    @Test
    fun streetsThinOutAsTheMapZoomsOut() {
        // Street level: everything, main roads two dots wide.
        assertEquals(listOf(1, 1, 2, 1), listOf(BaseKind.LOCAL, BaseKind.SECONDARY, BaseKind.MAIN, BaseKind.RAIL).map { baseWidthDots(it, 4.0) })
        // A suburb: no local streets.
        assertEquals(listOf(0, 1, 1, 1), listOf(BaseKind.LOCAL, BaseKind.SECONDARY, BaseKind.MAIN, BaseKind.RAIL).map { baseWidthDots(it, 20.0) })
        // A whole journey across the city: water and railways only.
        assertEquals(listOf(0, 0, 0, 1), listOf(BaseKind.LOCAL, BaseKind.SECONDARY, BaseKind.MAIN, BaseKind.RAIL).map { baseWidthDots(it, 100.0) })
        assertEquals(1, baseWidthDots(BaseKind.WATER, 1_000.0))
    }
}
