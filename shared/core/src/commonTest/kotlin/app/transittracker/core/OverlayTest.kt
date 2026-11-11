package app.transittracker.core

import app.transittracker.model.AddedStop
import app.transittracker.model.DepartureStatus
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopDelta
import app.transittracker.model.TripDelta
import app.transittracker.model.TripLeg
import app.transittracker.model.TripStatus
import app.transittracker.model.departuresAfter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayTest {
    private val tt = TestNet.timetable()
    private val now = at(6, 50)

    /** The scheduled R1-04 (07:00 from A) A → B departure. */
    private fun scheduled() = tt.departuresAfter(setOf("A1", "A2"), setOf("B1", "B2"), at(6, 59), limit = 1).single()

    private fun overlay(vararg deltas: TripDelta, receivedMs: Long = now, pollMs: Long = 30_000) =
        Overlay(tt, snapshotOf(*deltas, receivedMs = receivedMs, pollMs = pollMs), now)

    private fun dep(delayS: Int?, arrDelayS: Int? = null) =
        StopDelta(1, "A1", arrivalDelayS = arrDelayS, departureDelayS = delayS)

    @Test
    fun noDeltaIsTimetabled() {
        val d = overlay().departure(scheduled())
        assertEquals("R1-04", d.ref.tripId)
        assertEquals(DepartureStatus.TIMETABLED, d.status)
        assertEquals(d.scheduledDepartureMs, d.expectedDepartureMs)
        assertNull(d.delaySeconds)
        assertNull(d.dataAgeMs)
        assertEquals("1", d.originPlatform)
        assertEquals("T1", d.routeName)
    }

    @Test
    fun statusThresholds() {
        val s = scheduled()
        val cases = mapOf(0 to DepartureStatus.ON_TIME, 59 to DepartureStatus.ON_TIME, 60 to DepartureStatus.LATE,
            -59 to DepartureStatus.ON_TIME, -60 to DepartureStatus.EARLY, 600 to DepartureStatus.LATE)
        for ((delay, expected) in cases) {
            val d = overlay(delta("R1-04", DAY, "R1", dep(delay))).departure(s)
            assertEquals(expected, d.status, "delay $delay")
            assertEquals(delay, d.delaySeconds)
            assertEquals(s.departureMillis + delay * 1000L, d.expectedDepartureMs)
        }
    }

    @Test
    fun arrivalDelayUsedWhenNoDepartureDelayAndDestinationDelayApplied() {
        val d = overlay(
            delta("R1-04", DAY, "R1", dep(null, arrDelayS = 120), StopDelta(2, "B1", arrivalDelayS = 300, departureDelayS = 330)),
        ).departure(scheduled())
        assertEquals(120, d.delaySeconds)
        assertEquals(DepartureStatus.LATE, d.status)
        assertEquals(d.scheduledArrivalMs + 300_000, d.expectedArrivalMs)
    }

    @Test
    fun missingStopInheritsPreviousDelay() {
        // Only the origin is listed: the destination inherits its delay.
        val d = overlay(delta("R1-04", DAY, "R1", dep(180))).departure(scheduled())
        assertEquals(d.scheduledArrivalMs + 180_000, d.expectedArrivalMs)
    }

    @Test
    fun cancelledAndSkippedOrigin() {
        val cancelled = overlay(delta("R1-04", status = TripStatus.CANCELLED)).departure(scheduled())
        assertEquals(DepartureStatus.CANCELLED, cancelled.status)
        val skipped = overlay(delta("R1-04", DAY, "R1", StopDelta(1, "A1", 0, 0, skipped = true))).departure(scheduled())
        assertEquals(DepartureStatus.CANCELLED, skipped.status)
    }

    @Test
    fun platformChange() {
        val d = overlay(delta("R1-04", DAY, "R1", StopDelta(1, "A1", 0, 0, actualStopId = "A2"))).departure(scheduled())
        assertTrue(d.platformChanged)
        assertEquals("2", d.originPlatform)
        assertEquals("A2", d.originStopId)
        assertEquals(DepartureStatus.ON_TIME, d.status)

        val same = overlay(delta("R1-04", DAY, "R1", StopDelta(1, "A1", 0, 0, actualStopId = "A1"))).departure(scheduled())
        assertFalse(same.platformChanged)
    }

    @Test
    fun staleFromDeltaOrAge() {
        val fresh = overlay(delta("R1-04", DAY, "R1", dep(0)), receivedMs = now - 20_000).departure(scheduled())
        assertFalse(fresh.stale)
        assertEquals(20_000, fresh.dataAgeMs)

        val flagged = overlay(delta("R1-04", DAY, "R1", dep(0), stale = true)).departure(scheduled())
        assertTrue(flagged.stale)

        // Older than 2 × the 30 s poll interval.
        val old = overlay(delta("R1-04", DAY, "R1", dep(0)), receivedMs = now - 61_000).departure(scheduled())
        assertTrue(old.stale)
        assertEquals(61_000, old.dataAgeMs)
        val ok = overlay(delta("R1-04", DAY, "R1", dep(0)), receivedMs = now - 59_000).departure(scheduled())
        assertFalse(ok.stale)
    }

    @Test
    fun afterMidnightDepartureUsesPreviousServiceDate() {
        val after = at(0, 20, NEXT_DAY)
        val deps = tt.departuresAfter(setOf("A1"), setOf("B1"), after, limit = 3)
        val late = deps.first()
        assertEquals("R1-LATE", late.tripId)
        assertEquals(DAY, late.serviceDate)
        assertEquals(at(0, 30, NEXT_DAY), late.departureMillis)

        val o = Overlay(tt, snapshotOf(delta("R1-LATE", DAY, "R1", dep(240)), receivedMs = after), after)
        val d = o.departure(late)
        assertEquals(ServiceRef("R1-LATE", DAY), d.ref)
        assertEquals(DepartureStatus.LATE, d.status)
        assertEquals(at(0, 34, NEXT_DAY), d.expectedDepartureMs)

        // A delta for the same trip id on the wrong date must not apply.
        val wrong = Overlay(tt, snapshotOf(delta("R1-LATE", NEXT_DAY, "R1", dep(240)), receivedMs = after), after).departure(late)
        assertEquals(DepartureStatus.TIMETABLED, wrong.status)
    }

    @Test
    fun addedTripsAreInsertedInTimeOrder() {
        val added = TripDelta(
            "EXTRA-1", DAY, "R1", TripStatus.ADDED, headsign = "Bravo",
            addedStops = listOf(
                AddedStop(1, "A2", departureMs = at(7, 5)),
                AddedStop(2, "B2", arrivalMs = at(7, 22)),
            ),
        )
        val planner = JourneyPlanner(tt, Overlay(tt, snapshotOf(added, receivedMs = now), now))
        val deps = planner.legDepartures(TripLeg(setOf("A"), setOf("B")), at(6, 58), 4)
        assertEquals(listOf("R1-04", "EXTRA-1", "R1-05", "R1-06"), deps.map { it.ref.tripId })
        val extra = deps[1]
        assertEquals(DepartureStatus.ADDED, extra.status)
        assertEquals("2", extra.originPlatform)
        assertEquals(at(7, 22), extra.expectedArrivalMs)
        assertEquals("Bravo", extra.headsign)

        // An added trip that serves the stations in the wrong order is ignored.
        val reverse = added.copy(tripId = "EXTRA-2", addedStops = listOf(AddedStop(1, "B2", departureMs = at(7, 6)), AddedStop(2, "A2", arrivalMs = at(7, 26))))
        val p2 = JourneyPlanner(tt, Overlay(tt, snapshotOf(reverse, receivedMs = now), now))
        assertTrue(p2.legDepartures(TripLeg(setOf("A"), setOf("B")), at(6, 58), 4).none { it.ref.tripId == "EXTRA-2" })
    }

    @Test
    fun lateServiceScheduledEarlierStillListed() {
        // R1-03 was due at 06:45 but runs 10 min late: at 06:50 it is still upcoming.
        val planner = JourneyPlanner(tt, overlay(delta("R1-03", DAY, "R1", dep(600))))
        val deps = planner.legDepartures(TripLeg(setOf("A"), setOf("B")), now, 3)
        assertEquals(listOf("R1-03", "R1-04", "R1-05"), deps.map { it.ref.tripId })
        assertEquals(DepartureStatus.LATE, deps[0].status)
    }

    @Test
    fun serviceDetailMarksPassedCallsAndAppliesDelays() {
        // R1-02 left A at 06:30; at 06:52 the feed only lists stops 2 and 3 (remaining).
        val o = Overlay(
            tt,
            snapshotOf(
                delta("R1-02", DAY, "R1", StopDelta(2, "B1", 120, 120, actualStopId = "B2"), StopDelta(3, "C1", 90, null)),
                receivedMs = at(6, 52),
            ),
            at(6, 52),
        )
        val detail = o.serviceDetail(ServiceRef("R1-02", DAY))!!
        assertEquals(DepartureStatus.LATE, detail.status)
        assertEquals(3, detail.calls.size)
        val (a, b, c) = detail.calls
        assertTrue(a.passed)
        assertNull(a.expectedDepartureMs)
        assertFalse(b.passed) // due 06:51 + 2 min
        assertEquals(at(6, 53), b.expectedDepartureMs)
        assertTrue(b.platformChanged)
        assertEquals("2", b.platform)
        assertEquals(at(7, 11, ss = 30), c.expectedArrivalMs)

        val timetabled = Overlay(tt, LiveSnapshot.EMPTY, at(6, 52)).serviceDetail(ServiceRef("R1-02", DAY))!!
        assertEquals(DepartureStatus.TIMETABLED, timetabled.status)
        assertEquals(listOf(true, true, false), timetabled.calls.map { it.passed })
    }

    @Test
    fun addedServiceDetail() {
        val added = TripDelta(
            "EXTRA-1", DAY, "R1", TripStatus.ADDED,
            addedStops = listOf(AddedStop(1, "A2", departureMs = at(7, 5)), AddedStop(2, "B2", arrivalMs = at(7, 22))),
        )
        val detail = Overlay(tt, snapshotOf(added, receivedMs = now), now).serviceDetail(ServiceRef("EXTRA-1", DAY))!!
        assertEquals(DepartureStatus.ADDED, detail.status)
        assertEquals(listOf("A2", "B2"), detail.calls.map { it.stopId })
        assertEquals("Bravo", detail.headsign)
        assertEquals(listOf("Alpha", "Bravo"), detail.calls.map { it.stopName })
    }
}
