package app.transittracker.core

import app.transittracker.model.AlarmKind
import app.transittracker.model.AlarmSpec
import app.transittracker.model.SavedTrip
import app.transittracker.model.StopDelta
import app.transittracker.model.TripLeg
import app.transittracker.model.TripStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JourneyTest {
    private val tt = TestNet.timetable()
    private val now = at(6, 50)
    private val single = SavedTrip("t1", "Alpha", "Bravo", listOf(TripLeg(setOf("A"), setOf("B"))))
    private val twoLeg = SavedTrip(
        "t2", "Alpha", "Charlie",
        listOf(
            TripLeg(setOf("A"), setOf("B"), routeIds = setOf("R1")),
            TripLeg(setOf("B"), setOf("C"), routeIds = setOf("R2"), minConnectionMinutes = 5),
        ),
    )

    private fun planner(live: LiveSnapshot = LiveSnapshot.EMPTY) = JourneyPlanner(tt, Overlay(tt, live, now))

    @Test
    fun singleLegIsDepartures() {
        val journeys = planner().upcoming(single, now, 3)
        assertEquals(listOf("R1-04", "R1-05", "R1-06"), journeys.map { it.departure.ref.tripId })
        assertTrue(journeys.all { it.legs.size == 1 })
    }

    @Test
    fun multiLegChainsWithConnectionTime() {
        val journeys = planner().upcoming(twoLeg, now, 3)
        assertEquals(3, journeys.size)
        // 07:00 → B 07:20 (+5 min) → 07:30; 07:15 → 07:35 (+5) → 07:40 exactly; 07:30 → 07:50 (+5) → 08:00.
        assertEquals(listOf("R1-04" to "R2-09", "R1-05" to "R2-10", "R1-06" to "R2-12"),
            journeys.map { it.legs[0].ref.tripId to it.legs[1].ref.tripId })
        assertEquals(at(7, 45), journeys[0].arrival.expectedArrivalMs)
    }

    @Test
    fun lateFirstLegMissesConnectionAndCancelledConnectionIsSkipped() {
        val live = snapshotOf(
            delta("R1-04", "20261111", "R1", StopDelta(1, "A1", 0, 0), StopDelta(2, "B1", 360, 360)),
            delta("R2-10", "20261111", "R2", status = TripStatus.CANCELLED),
            receivedMs = now,
        )
        val journeys = planner(live).upcoming(twoLeg, now, 2)
        // B at 07:26 + 5 → 07:31: R2-10 (07:40) is cancelled → R2-11 (07:50).
        assertEquals("R2-11", journeys[0].legs[1].ref.tripId)
        // 07:15 → 07:35 + 5 = 07:40, R2-10 cancelled → R2-11 too.
        assertEquals("R2-11", journeys[1].legs[1].ref.tripId)
    }

    @Test
    fun cancelledFirstLegListedOnlyForSingleLegTrips() {
        val live = snapshotOf(delta("R1-04", status = TripStatus.CANCELLED), receivedMs = now)
        assertEquals("R1-04", planner(live).upcoming(single, now, 1).single().departure.ref.tripId)
        assertEquals("R1-05", planner(live).upcoming(twoLeg, now, 1).single().departure.ref.tripId)
    }

    @Test
    fun underwayHoldsJourneysThatHaveLeftUntilTheyArrive() {
        val t = at(7, 30)
        val p = JourneyPlanner(tt, Overlay(tt, LiveSnapshot.EMPTY, t))
        // 06:45 arrives 07:25 (more than two minutes ago): done. 07:00 and 07:15 have left; 07:30 has not.
        val journeys = p.underway(twoLeg, t)
        assertEquals(listOf("R1-04" to "R2-09", "R1-05" to "R2-10"), journeys.map { it.legs[0].ref.tripId to it.legs[1].ref.tripId })
        // Two minutes' grace after the last arrival.
        assertEquals(listOf("R1-03", "R1-04", "R1-05"), p.underway(twoLeg, at(7, 26)).map { it.departure.ref.tripId }.take(3))
    }

    @Test
    fun underwayMovesTheConnectionWhenTheFirstLegRunsLate() {
        val t = at(7, 10)
        val live = snapshotOf(
            delta("R1-04", "20261111", "R1", StopDelta(1, "A1", 0, 0), StopDelta(2, "B1", 360, 360)),
            receivedMs = t,
        )
        val journey = JourneyPlanner(tt, Overlay(tt, live, t)).underway(twoLeg, t).single { it.departure.ref.tripId == "R1-04" }
        // B at 07:26 + 5 min → 07:31: the 07:30 is missed, the 07:40 is the connection.
        assertEquals("R2-10", journey.legs[1].ref.tripId)
    }

    @Test
    fun serviceDayIncludesAfterMidnightTrips() {
        val day = planner().forServiceDay(single, DAY)
        assertEquals(14, day.size)
        assertEquals("R1-00", day.first().departure.ref.tripId)
        assertEquals("R1-LATE", day.last().departure.ref.tripId)
        assertEquals(at(0, 30, NEXT_DAY), day.last().departure.scheduledDepartureMs)
        // Multi-leg: the after-midnight first leg has no connection (R2 stops at 10:00), so it is dropped.
        val multi = planner().forServiceDay(twoLeg, DAY)
        assertTrue(multi.none { it.departure.ref.tripId == "R1-LATE" })
        assertTrue(multi.all { it.legs.size == 2 })
    }

    @Test
    fun alarmTimesBeforeDeparture() {
        val alarm = AlarmSpec("a", "t1", AlarmKind.BEFORE_DEPARTURE, minutesBefore = 10)
        // 07:00 - 10 min = 06:50 is not after now; next are 07:05, 07:20, 07:35.
        assertEquals(listOf(at(7, 5), at(7, 20), at(7, 35)), alarmTimes(tt, single, alarm, now, 3))
    }

    @Test
    fun alarmTimesBeforeArrival() {
        val alarm = AlarmSpec("a", "t2", AlarmKind.BEFORE_ARRIVAL, minutesBefore = 5)
        // Journeys already under way still get arrival alarms: 06:30 → R2-06 arrives 07:15,
        // 06:45 → R2-07 arrives 07:25. (06:15 → 06:55 arrival gives 06:50, not after now.)
        assertEquals(listOf(at(7, 10), at(7, 20)), alarmTimes(tt, twoLeg, alarm, now, 2))
    }

    @Test
    fun alarmTimesHonourDaysOfWeek() {
        // 2026-11-11 is a Wednesday; only Fridays (5). The Thursday-service 24:30 trip leaves at
        // 00:30 on Friday, so it counts as Friday.
        val alarm = AlarmSpec("a", "t1", AlarmKind.BEFORE_DEPARTURE, minutesBefore = 10, daysOfWeek = listOf(5))
        val times = alarmTimes(tt, single, alarm, now, 3)
        assertEquals(listOf(at(0, 20, "20261113"), at(5, 50, "20261113"), at(6, 5, "20261113")), times)
    }
}
