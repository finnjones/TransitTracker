package app.transittracker.ui

import app.transittracker.model.DepartureStatus
import app.transittracker.model.Mode
import app.transittracker.model.StopCall
import app.transittracker.ui.sprites.VehicleState
import app.transittracker.ui.sprites.vehicleState
import app.transittracker.ui.strip.Leg
import app.transittracker.ui.strip.StripLayout
import app.transittracker.ui.strip.StripStopKind
import app.transittracker.ui.strip.journeyStripModel
import app.transittracker.ui.strip.legSentence
import app.transittracker.ui.strip.legSpans
import app.transittracker.ui.strip.vehicleProgress
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StripMathTest {
    private val min = 60_000L

    /** Stops at 0, 4, 8 … minutes (departures), arriving 30 s earlier; names A, B, C … */
    private fun calls(n: Int, skipped: Set<Int> = emptySet(), expectedShiftMin: Long = 0) = (0 until n).map { i ->
        val dep = i * 4 * min
        StopCall(
            i + 1, "S$i", ('A' + i).toString(), null, false,
            scheduledArrivalMs = dep - 30_000, scheduledDepartureMs = dep,
            expectedArrivalMs = dep - 30_000 + expectedShiftMin * min, expectedDepartureMs = dep + expectedShiftMin * min,
            skipped = i in skipped, passed = false,
        )
    }

    @Test
    fun progressBetweenStopsIsProportionalToTime() {
        // Leave B at 4:00, arrive C at 7:30: at 5:45 the vehicle is half way.
        val p = vehicleProgress(calls(5), 5 * min + 45_000)!!
        assertEquals(1, p.fromIndex); assertEquals(2, p.toIndex)
        assertEquals(0.5f, p.progress, 0.001f)
        assertFalse(p.atStop)
        assertEquals(1.5f, p.position, 0.001f)
    }

    @Test
    fun atStopBetweenArrivalAndDeparture() {
        val p = vehicleProgress(calls(5), 8 * min - 10_000)!!
        assertTrue(p.atStop); assertEquals(2, p.fromIndex); assertEquals(2, p.toIndex)
    }

    @Test
    fun usesExpectedTimes() {
        // Two minutes late: at 5:45 it has only just left A's... B's expected departure is 6:00.
        val p = vehicleProgress(calls(5, expectedShiftMin = 2), 5 * min + 45_000)!!
        assertTrue(p.atStop); assertEquals(1, p.fromIndex)
    }

    @Test
    fun skippedCallsAreIgnored() {
        val p = vehicleProgress(calls(5, skipped = setOf(2)), 9 * min)!!
        assertEquals(1, p.fromIndex); assertEquals(3, p.toIndex)
        // From 4:00 to 11:30.
        assertEquals((5f * 60) / (7.5f * 60), p.progress, 0.001f)
    }

    @Test
    fun beforeStartAndAfterEnd() {
        val start = vehicleProgress(calls(3), -5 * min)!!
        assertTrue(start.notStarted); assertEquals(0, start.fromIndex)
        val end = vehicleProgress(calls(3), 60 * min)!!
        assertTrue(end.finished); assertEquals(2, end.fromIndex)
        assertNull(vehicleProgress(emptyList(), 0))
    }

    @Test
    fun stripLayoutAndSentence() {
        // Ten stops, yours is F (5), destination J (9); vehicle between C and D.
        val m = journeyStripModel(calls(10), "S5", "S9", 2 * 4 * min + 2 * min, Mode.TRAIN)
        val kinds = m.stops.map { it.callIndex to it.kind }
        assertEquals(listOf(2 to StripStopKind.PASSED, 3 to StripStopKind.AHEAD, 4 to StripStopKind.AHEAD, 5 to StripStopKind.YOURS, 9 to StripStopKind.DESTINATION), kinds)
        assertEquals(StripLayout.YOURS_X, m.stops[3].x)
        assertEquals(StripLayout.DESTINATION_X, m.stops[4].x)
        assertTrue(m.stops[0].labelled && !m.stops[1].labelled)
        val x = m.vehicleX!!
        assertTrue(x > m.stops[0].x && x < m.stops[1].x, "vehicle between C and D: $x")
        assertEquals("The train has left C and is two stops from F.", m.sentence)
    }

    @Test
    fun vehicleFarBackWaitsAtLeftEdgeAndPastYoursMovesTowardsDestination() {
        val far = journeyStripModel(calls(10), "S5", "S9", 2 * min)
        assertEquals(0f, far.vehicleX)
        val past = journeyStripModel(calls(10), "S5", "S9", 7 * 4 * min - 10_000)
        assertTrue(past.vehicleX!! > StripLayout.YOURS_X && past.vehicleX!! < StripLayout.DESTINATION_X)
        assertEquals("The train is at H, past F.", past.sentence)
        val ghost = journeyStripModel(calls(10), "S5", "S9", 4 * 4 * min + 2 * min, Mode.BUS, ghost = true)
        assertEquals("Timetable estimate. The bus has left E and is approaching F.", ghost.sentence)
    }

    @Test
    fun vehicleStatePriority() {
        assertEquals(VehicleState.Cancelled, vehicleState(DepartureStatus.CANCELLED, true, 600, true))
        assertEquals(VehicleState.Ghost, vehicleState(DepartureStatus.TIMETABLED, false, null, false))
        assertEquals(VehicleState.Stale, vehicleState(DepartureStatus.LATE, true, 300, false))
        assertEquals(VehicleState.Late(4, atStop = true), vehicleState(DepartureStatus.LATE, false, 240, true))
        assertEquals(VehicleState.AtStop, vehicleState(DepartureStatus.ON_TIME, false, 59, true))
        assertEquals(VehicleState.Moving, vehicleState(DepartureStatus.EARLY, false, -120, false))
    }

    @Test
    fun legSpansAreProportionalAndOnWholeDots() {
        val legs = listOf(Leg.Walk(3), Leg.Ride(14, Mode.TRAIN, "T9", Color.Red), Leg.Walk(7), Leg.Ride(30, Mode.BUS, "333", Color.Blue))
        val spans = legSpans(legs, 700f, 2f, 4f)
        assertEquals(4, spans.size)
        assertEquals(0f, spans[0].left)
        for (s in spans.dropLast(1)) assertEquals(0f, s.width % 4f, "whole dots: $s")
        val last = spans.last()
        assertEquals(700f, last.left + last.width, 0.01f)
        assertTrue(spans[3].width > spans[1].width && spans[1].width > spans[2].width)
        assertTrue(legSpans(listOf(Leg.Walk(0), Leg.Walk(5)), 100f, 2f, 4f)[0].width >= 4f)
        assertEquals("Walk 3 minutes, T9 train 14 minutes, change 7 minutes, 333 bus 30 minutes", legSentence(legs))
        assertNotNull(legSentence(emptyList()))
    }
}
