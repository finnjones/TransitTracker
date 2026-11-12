package app.transittracker.ui.feature.map

import app.transittracker.core.FakeData
import app.transittracker.model.LatLng
import app.transittracker.model.Stop
import app.transittracker.model.StopCall
import app.transittracker.model.VehiclePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dead reckoning, label placement, stop matching and the schematic fallback. */
class MapRulesTest {
    private val t0 = 1_000_000_000L

    private fun call(i: Int, name: String, arr: Long?, dep: Long?, passed: Boolean = false, id: String = "S$i") =
        StopCall(i + 1, id, name, null, false, arr, dep, arr, dep, skipped = false, passed = passed)

    // Three stops 1 km apart on a straight line east: A (dep t0), B (arr t0+100 s, dep +130 s), C (arr +230 s).
    private val calls = listOf(
        call(0, "A", t0, t0, passed = true),
        call(1, "B", t0 + 100_000, t0 + 130_000),
        call(2, "C", t0 + 230_000, t0 + 230_000),
    )
    private val geometry = schematicGeometry(calls).let { g ->
        // A straight path for easy numbers (schematic steps only after every third stop anyway).
        assertEquals(listOf(0.0, 1_000.0, 2_000.0), g.stopDistances)
        g
    }
    private val proj = MapProjection.Sydney

    private fun vehicleAt(x: Double, at: Long, speed: Float? = null) =
        VehiclePosition("v1", position = proj.unproject(MapPoint(x, 0.0)), timestampMs = at, speedMps = speed)

    @Test
    fun reckoningCreepsFromTheReportAndStopsAtTheNextStop() {
        // Reported 400 m along at t0+40 s; B (1000 m) is due at t0+100 s: 10 m/s.
        val r = reckon(vehicleAt(400.0, t0 + 40_000), calls, geometry, nowMs = t0 + 40_000)
        assertEquals(400.0, r.reportDistance, 0.5)
        assertEquals(1_000.0, r.limitDistance, 1e-6)
        assertEquals(10.0, r.speedMps, 0.05)
        assertEquals(400.0, r.distanceAt(t0 + 40_000), 0.5)
        assertEquals(600.0, r.distanceAt(t0 + 60_000), 1.0)
        // Never past the next stop, however late the next report is.
        assertEquals(1_000.0, r.distanceAt(t0 + 500_000), 1e-6)
        // Reduce motion: holds still at the report.
        assertEquals(400.0, r.distanceAt(t0 + 60_000, reduceMotion = true), 0.5)
    }

    @Test
    fun reckoningNeverRunsBackwardsAndGlidesToNewReports() {
        val first = reckon(vehicleAt(400.0, t0 + 40_000), calls, geometry, nowMs = t0 + 40_000)
        // At t0+60 s it is drawn at 600 m; a report says 550 m (GPS behind): it holds at 600.
        val behind = reckon(vehicleAt(550.0, t0 + 60_000), calls, geometry, nowMs = t0 + 60_000, previous = first)
        assertEquals(600.0, behind.fromDistance, 1.0)
        assertTrue(behind.reportDistance >= behind.fromDistance)
        var last = 0.0
        for (ms in 60_000L..200_000L step 250) {
            val d = behind.distanceAt(t0 + ms)
            assertTrue(d >= last - 1e-9, "ran backwards at $ms: $d < $last")
            last = d
        }
        // A report ahead: it glides there over one second, not a jump.
        val ahead = reckon(vehicleAt(800.0, t0 + 62_000), calls, geometry, nowMs = t0 + 62_000, previous = behind)
        val start = ahead.distanceAt(t0 + 62_000)
        val mid = ahead.distanceAt(t0 + 62_500)
        val end = ahead.distanceAt(t0 + 63_000)
        assertTrue(start < mid && mid < end, "$start $mid $end")
        assertEquals(ahead.fromDistance, start, 1e-6)
        assertTrue(end >= 800.0)
    }

    @Test
    fun aVehicleStandingAtAStopWaitsForItsDeparture() {
        // At B (1000 m) at t0+105 s; B departs at t0+130 s.
        val r = reckon(vehicleAt(1_000.0, t0 + 105_000), calls, geometry, nowMs = t0 + 105_000)
        assertEquals(2_000.0, r.limitDistance, 1e-6)
        assertEquals(1_000.0, r.distanceAt(t0 + 125_000), 0.5)
        assertTrue(r.distanceAt(t0 + 180_000) > 1_400.0)
        // Reported speed wins over the timetable's.
        val fast = reckon(vehicleAt(400.0, t0 + 40_000, speed = 20f), calls, geometry, nowMs = t0 + 40_000)
        assertEquals(20.0, fast.speedMps, 1e-6)
    }

    @Test
    fun positionsOlderThanTwoMinutesAreNotShown() {
        assertTrue(positionFresh(vehicleAt(0.0, t0), t0 + 119_000))
        assertFalse(positionFresh(vehicleAt(0.0, t0), t0 + 121_000))
        assertFalse(positionFresh(null, t0))
    }

    @Test
    fun theGhostSitsWhereTheTimetablePutsIt() {
        assertEquals(500.0, timetabledDistance(calls, geometry, t0 + 50_000)!!, 1e-6)
        assertEquals(1_000.0, timetabledDistance(calls, geometry, t0 + 110_000)!!, 1e-6) // dwelling at B
        assertEquals(1_500.0, timetabledDistance(calls, geometry, t0 + 180_000)!!, 1e-6)
    }

    // ---------------------------------------------------------------- labels

    @Test
    fun labelsNeverOverlapAndGoInPriorityOrder() {
        val bounds = Box(0f, 0f, 400f, 400f)
        fun marker(x: Float, y: Float) = Box(x, y, x + 10, y + 10)
        val reqs = listOf(
            LabelRequest("low", marker(100f, 100f), 80f, 20f, priority = 10),
            LabelRequest("high", marker(130f, 100f), 80f, 20f, priority = 0),
            LabelRequest("edge", marker(385f, 380f), 80f, 20f, priority = 5),
        )
        val placed = placeLabels(reqs, bounds, reqs.map { it.anchor }, gap = 2f)
        // The high-priority label takes its first choice (right of its marker).
        assertEquals(LabelSide.RIGHT, sideOf(placed.getValue("high"), reqs[1].anchor))
        // The other near one moves to a free side.
        val low = placed.getValue("low")
        assertFalse(low.overlaps(placed.getValue("high")))
        // Every placed label is on screen, clear of other markers and of each other.
        val boxes = placed.values.toList()
        for (b in boxes) assertTrue(b.inside(bounds))
        for (i in boxes.indices) for (j in i + 1 until boxes.size) assertFalse(boxes[i].overlaps(boxes[j]))
        for ((id, b) in placed) for (r in reqs) if (r.id != id) assertFalse(b.overlaps(r.anchor), "$id over ${r.id}'s marker")
        // Near the corner only the left/above sides fit.
        val edge = placed.getValue("edge")
        assertTrue(edge.right <= 385f)
    }

    @Test
    fun labelsWithNoRoomAreDroppedAndObstaclesAreAvoided() {
        val bounds = Box(0f, 0f, 100f, 40f)
        val a = LabelRequest("a", Box(45f, 15f, 55f, 25f), 90f, 20f, 0)
        // 90 px wide does not fit beside a marker in the middle of a 100 px view: dropped.
        assertTrue(placeLabels(listOf(a), bounds).isEmpty())
        val b = LabelRequest("b", Box(0f, 15f, 10f, 25f), 30f, 10f, 0)
        val free = placeLabels(listOf(b), bounds, gap = 2f).getValue("b")
        assertEquals(LabelSide.RIGHT, sideOf(free, b.anchor))
        val blocked = placeLabels(listOf(b), bounds, obstacles = listOf(Box(10f, 0f, 60f, 40f)), gap = 2f)
        assertNull(blocked["b"]) // right is blocked and left is off screen; above/below hit the obstacle
    }

    @Test
    fun aLabelOffTheEdgeSlidesAlongItsSideRatherThanBeingDropped() {
        val bounds = Box(0f, 0f, 200f, 200f)
        // A flag below a marker 20 px from the right edge: centred it would run 30 px off.
        val near = LabelRequest("near", Box(170f, 90f, 180f, 100f), 100f, 20f, 0, listOf(LabelSide.BELOW))
        val b = placeLabels(listOf(near), bounds, gap = 2f).getValue("near")
        assertEquals(200f, b.right)
        assertTrue(b.left < 175f) // still under its marker's centre
        // More than half its width off: no slide reaches, so it is dropped.
        val far = LabelRequest("far", Box(200f, 90f, 210f, 100f), 20f, 20f, 0, listOf(LabelSide.BELOW))
        assertNull(slideInside(labelBox(far.anchor, 20f, 20f, LabelSide.BELOW, 2f), LabelSide.BELOW, bounds))
        // Beside a marker near the top, a label slides down.
        val top = slideInside(labelBox(Box(50f, 0f, 60f, 4f), 40f, 20f, LabelSide.RIGHT, 2f), LabelSide.RIGHT, bounds)!!
        assertEquals(0f, top.top)
    }

    @Test
    fun aStopOnALoopIsSnappedWhereTheLineFirstReachesIt() {
        // Out east along y = 0, up, back west along y = 100, down, and east again along y = 0:
        // the stop at x = 500 is passed at 500 m and again on the second lap.
        val loop = MapPath(listOf(MapPoint(0.0, 0.0), MapPoint(1000.0, 0.0), MapPoint(1000.0, 100.0), MapPoint(0.0, 100.0), MapPoint(0.0, 0.0), MapPoint(1000.0, 0.0)))
        val stop = MapPoint(500.0, 3.0)
        assertEquals(500.0, loop.snapAhead(stop).distance, 1e-6)
        // After the first lap it is caught on the second.
        assertEquals(2700.0, loop.snapAhead(stop, from = 1000.0).distance, 1e-6)
    }

    @Test
    fun oneStopOffTheShapeOnlyStraightensTheStretchesAroundIt() {
        val shape = MapPath(listOf(MapPoint(0.0, 0.0), MapPoint(500.0, 200.0), MapPoint(1000.0, 0.0), MapPoint(1500.0, 200.0), MapPoint(2000.0, 0.0)))
        val stops = listOf(MapPoint(0.0, 0.0), MapPoint(1000.0, 0.0), MapPoint(1500.0, -900.0), MapPoint(2000.0, 0.0))
        val straight = ServiceGeometry(MapPath(stops), stops, MapPath(stops).cumulative.toList(), stops.map { null }, listOf("A", "B", "C", "D"), listOf("a", "b", "c", "d"), schematic = false)
        val g = followShape(straight, shape)
        // A to B follows the shape's bend; B to C to D runs straight to the stop 900 m off it.
        assertEquals(listOf(MapPoint(0.0, 0.0), MapPoint(500.0, 200.0), MapPoint(1000.0, 0.0), MapPoint(1500.0, -900.0), MapPoint(2000.0, 0.0)), g.path.points)
        assertEquals(listOf(0, 2, 3, 4).map { g.path.cumulative[it] }, g.stopDistances)
        // With no stop near it, the shape is not used at all.
        val far = followShape(straight, MapPath(listOf(MapPoint(0.0, 5000.0), MapPoint(2000.0, 5000.0))))
        assertEquals(straight, far)
    }

    private fun sideOf(b: Box, anchor: Box): LabelSide = when {
        b.left >= anchor.right -> LabelSide.RIGHT
        b.right <= anchor.left -> LabelSide.LEFT
        b.bottom <= anchor.top -> LabelSide.ABOVE
        else -> LabelSide.BELOW
    }

    // ---------------------------------------------------------------- geometry

    @Test
    fun stopsAreMatchedByIdThenByName() {
        val central = FakeData.stops.first { it.id == FakeData.CENTRAL }
        val other = Stop("999", "Central Park", null, LatLng(-33.9, 151.1))
        val platformCall = call(0, "Central Station", 1, 1, id = FakeData.platform(FakeData.CENTRAL, 3))
        assertEquals(central, matchStop(platformCall, listOf(other, central), null))
        // A real-core style id with no relation to the station's: matched by name.
        val byName = call(0, "Central Station", 1, 1, id = "2000322")
        assertEquals(central, matchStop(byName, listOf(other, central), null))
        assertNull(matchStop(call(0, "Nowhere", 1, 1, id = "x"), listOf(other, central), null))
    }

    @Test
    fun missingPositionsAreFilledOrFallBackToTheSchematic() {
        val five = (0 until 5).map { call(it, "Stop $it", t0 + it * 60_000L, t0 + it * 60_000L) }
        val a = LatLng(-33.88, 151.00)
        val c = LatLng(-33.88, 151.02)
        val g = geometryFromStops(five, listOf(null, a, null, c, null))
        assertFalse(g.schematic)
        val xs = g.stopPoints.map { it.x }
        // Evenly spaced between the found ones, and extended at both ends with the same spacing.
        val step = xs[3] - xs[2]
        for (i in 1 until 5) assertEquals(step, xs[i] - xs[i - 1], 1e-6)
        // One position (or none) is not enough: schematic.
        val s = geometryFromStops(five, listOf(null, a, null, null, null))
        assertTrue(s.schematic)
        assertEquals((0 until 5).map { it * SCHEMATIC_SPACING }, s.stopPoints.map { it.x })
        // Gently stepped: down half a spacing after every third stop, never back up.
        assertEquals(listOf(0.0, 0.0, 0.0, 500.0, 500.0), s.stopPoints.map { it.y })
        assertEquals(s.path.length, s.stopDistances.last(), 1e-6)
    }

    @Test
    fun linesMergeEachStretchOnceInBothDirections() {
        val north = (0 until 3).map { call(it, listOf("A", "B", "C")[it], 0, 0) }
        val south = north.reversed().mapIndexed { i, c -> c.copy(stopSequence = i + 1) }
        val pos = listOf(LatLng(-33.90, 151.0), LatLng(-33.85, 151.0), LatLng(-33.80, 151.0))
        val line = mergeLine("L", listOf(geometryFromStops(north, pos), geometryFromStops(south, pos.reversed())))
        assertEquals(setOf("A|B", "B|C"), line.segments.map { it.key }.toSet())
        assertEquals(listOf("A", "B", "C"), line.stations.map { it.name })
        assertNotNull(line.segments.first().path)
    }
}
