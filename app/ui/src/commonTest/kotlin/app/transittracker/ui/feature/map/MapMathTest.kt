package app.transittracker.ui.feature.map

import app.transittracker.core.FakeData
import app.transittracker.model.LatLng
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Projection, paths, snapping, rasterising, camera and lanes (MapMath.kt). */
class MapMathTest {
    private val p = MapProjection.Sydney
    private fun pos(id: String): LatLng = FakeData.stops.first { it.id == id }.position

    private fun haversine(a: LatLng, b: LatLng): Double {
        val r = 6_371_008.8
        val dLat = (b.lat - a.lat) * PI / 180
        val dLon = (b.lon - a.lon) * PI / 180
        val h = sin(dLat / 2).let { it * it } + cos(a.lat * PI / 180) * cos(b.lat * PI / 180) * sin(dLon / 2).let { it * it }
        return 2 * r * asin(sqrt(h))
    }

    @Test
    fun projectionKeepsSydneysShape() {
        val central = p.project(pos(FakeData.CENTRAL))
        val townHall = p.project(pos(FakeData.TOWN_HALL))
        val chatswood = p.project(pos(FakeData.CHATSWOOD))
        val parramatta = p.project(pos(FakeData.PARRAMATTA))
        // Town Hall is the origin; Central is about 1.2 km due south of it.
        assertTrue(abs(townHall.x) < 1 && abs(townHall.y) < 1)
        assertTrue(central.y in 1_150.0..1_250.0, "Central ${central.y} m south")
        assertTrue(abs(central.x) < 20)
        // North is up (negative y), west is left (negative x).
        assertTrue(chatswood.y < 0 && parramatta.x < 0)
        // Distances match the great circle within 0.5 % across the network (no stretching).
        val ids = listOf(FakeData.CENTRAL, FakeData.CHATSWOOD, FakeData.PARRAMATTA, FakeData.TALLAWONG, FakeData.STRATHFIELD)
        for (a in ids) for (b in ids) if (a != b) {
            val flat = p.project(pos(a)).distanceTo(p.project(pos(b)))
            val real = haversine(pos(a), pos(b))
            assertTrue(abs(flat - real) / real < 0.005, "$a-$b: $flat vs $real")
        }
    }

    @Test
    fun projectionRoundTrips() {
        val ll = LatLng(-33.6913, 150.9061)
        val back = p.unproject(p.project(ll))
        assertEquals(ll.lat, back.lat, 1e-9)
        assertEquals(ll.lon, back.lon, 1e-9)
    }

    private val ell = MapPath(listOf(MapPoint(0.0, 0.0), MapPoint(100.0, 0.0), MapPoint(100.0, 100.0)))

    @Test
    fun pathDistancesPointsAndHeadings() {
        assertEquals(200.0, ell.length)
        assertEquals(MapPoint(50.0, 0.0), ell.pointAt(50.0))
        assertEquals(MapPoint(100.0, 30.0), ell.pointAt(130.0))
        assertEquals(MapPoint(100.0, 100.0), ell.pointAt(999.0))
        // East first (270), then south (0); at the corner it keeps the heading it arrived with.
        assertEquals(270.0, ell.headingAt(20.0), 1e-9)
        assertEquals(270.0, ell.headingAt(100.0), 1e-9)
        assertEquals(0.0, ell.headingAt(150.0), 1e-9)
        assertEquals(180.0, headingOf(MapPoint(0.0, -1.0)), 1e-9) // north
        assertEquals(90.0, headingOf(MapPoint(-1.0, 0.0)), 1e-9) // west
        assertEquals(4, headingStep(90.0, 16))
        assertEquals(0, headingStep(359.0, 16))
    }

    @Test
    fun snappingFindsTheNearestPointAlongThePath() {
        val s = ell.snap(MapPoint(40.0, 12.0))
        assertEquals(40.0, s.distance, 1e-9)
        assertEquals(12.0, s.offset, 1e-9)
        val t = ell.snap(MapPoint(130.0, 60.0))
        assertEquals(160.0, t.distance, 1e-9)
        // Beyond the end: clamped to the last point.
        assertEquals(200.0, ell.snap(MapPoint(100.0, 300.0)).distance, 1e-9)
        // Restricted to a stretch: a point near the start is held at the stretch's start.
        assertEquals(120.0, ell.snap(MapPoint(10.0, 0.0), from = 120.0).distance, 1e-9)
    }

    @Test
    fun rasterLinesAreGaplessWholeDots() {
        for ((a, b) in listOf((0 to 0) to (17 to 5), (3 to 9) to (-4 to -12), (0 to 0) to (0 to 6), (2 to 2) to (2 to 2))) {
            val cells = rasterLine(a.first, a.second, b.first, b.second)
            assertEquals(a, cells.first())
            assertEquals(b, cells.last())
            for (i in 1 until cells.size) {
                val dx = abs(cells[i].first - cells[i - 1].first)
                val dy = abs(cells[i].second - cells[i - 1].second)
                assertTrue(dx <= 1 && dy <= 1 && dx + dy >= 1, "gap or repeat at $i in $cells")
            }
        }
    }

    @Test
    fun rasterisedPathsCarryIncreasingDistances() {
        val cam = MapCamera(MapPoint(50.0, 50.0), metresPerDot = 2.0)
        val cells = rasterPath(ell, cam, 100, 100)
        assertEquals(0.0, cells.first().distance, 1e-9)
        assertEquals(200.0, cells.last().distance, 1e-9)
        assertTrue(cells.zipWithNext().all { (a, b) -> b.distance >= a.distance })
        // 50 dots east then 50 south, the corner once.
        assertEquals(101, cells.size)
    }

    @Test
    fun cameraZoomKeepsTheAnchorAndFitShowsTheBox() {
        val limits = ZoomLimits(3.0, 1_000.0)
        val cam = MapCamera(MapPoint(0.0, 0.0), 10.0)
        val anchor = cam.toMap(30.0, 70.0, 200, 300)
        val z = cam.zoomAbout(2.0, 30.0, 70.0, 200, 300, limits)
        assertEquals(5.0, z.metresPerDot)
        val after = z.toMap(30.0, 70.0, 200, 300)
        assertEquals(anchor.x, after.x, 1e-6)
        assertEquals(anchor.y, after.y, 1e-6)
        // Limits hold.
        assertEquals(3.0, cam.zoomAbout(100.0, 0.0, 0.0, 200, 300, limits).metresPerDot)
        // Fit: every corner of the box lands inside the margins.
        val box = MapBox(-5_000.0, -2_000.0, 15_000.0, 3_000.0)
        val f = MapCamera.fit(box, 195, 400, ZoomLimits.Default, marginDots = 10)
        for (corner in listOf(MapPoint(box.minX, box.minY), MapPoint(box.maxX, box.maxY))) {
            val d = f.toDots(corner, 195, 400)
            assertTrue(d.x in 9.0..186.0 && d.y in 9.0..391.0, "$corner at $d")
        }
    }

    @Test
    fun sharedTrackGetsSideBySideLanes() {
        val lanes = assignLanes(mapOf("a|b" to setOf("T1", "T9"), "b|c" to setOf("T1")), listOf("T1", "T9"))
        assertEquals(-0.5, lanes["a|b" to "T1"])
        assertEquals(0.5, lanes["a|b" to "T9"])
        assertEquals(0.0, lanes["b|c" to "T1"])
        assertEquals(segmentKey("Central", "Town Hall"), segmentKey("Town Hall", "Central"))
        // Perpendicular steps snap to the eight directions.
        assertEquals(0 to 1, laneStep(MapPoint(0.0, 0.0), MapPoint(10.0, 0.0)))
        assertEquals(-1 to 0, laneStep(MapPoint(0.0, 0.0), MapPoint(0.0, 10.0)))
    }
}
