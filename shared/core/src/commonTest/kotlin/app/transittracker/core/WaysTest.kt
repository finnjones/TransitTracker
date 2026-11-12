package app.transittracker.core

import app.transittracker.model.AlarmKind
import app.transittracker.model.AlarmSpec
import app.transittracker.model.Alert
import app.transittracker.model.RouteOption
import app.transittracker.model.SavedTrip
import app.transittracker.model.TripLeg
import app.transittracker.model.bestRuns
import app.transittracker.model.wayIdFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A saved trip with more than one way of getting from A to B. */
class WaysTest {
    private val tt = TestNet.timetable()
    private val now = at(6, 50)

    /** Stay on R1 from Alpha to Charlie (40 min). */
    private val direct = listOf(TripLeg(setOf("A"), setOf("C"), routeIds = setOf("R1")))

    /** R1 to Bravo, then R2 to Charlie. */
    private val change = listOf(
        TripLeg(setOf("A"), setOf("B"), routeIds = setOf("R1")),
        TripLeg(setOf("B"), setOf("C"), routeIds = setOf("R2")),
    )

    private val oneWay = SavedTrip("t", "Alpha", "Charlie", direct)
    private val twoWays = oneWay.withWays(listOf(RouteOption(wayIdFor(direct), direct), RouteOption(wayIdFor(change), change, "Change at Bravo")))

    private fun planner() = JourneyPlanner(tt, Overlay(tt, LiveSnapshot.EMPTY, now))

    @Test
    fun aTripSavedBeforeWaysHasOneWayMadeOfItsLegs() {
        assertEquals(listOf(RouteOption(wayIdFor(direct), direct)), oneWay.ways)
        assertTrue(oneWay.options.isEmpty())
        // One way without a label stays stored the old way.
        assertEquals(oneWay, oneWay.withWays(oneWay.ways))
    }

    @Test
    fun withWaysKeepsLegsAsTheFirstWayAndClearsAPinOnAWayThatIsGone() {
        assertEquals(direct, twoWays.legs)
        assertEquals(2, twoWays.ways.size)
        val pinned = twoWays.copy(pinnedWayId = wayIdFor(change))
        assertEquals(wayIdFor(change), pinned.pinnedWay?.id)
        val reordered = pinned.withWays(pinned.ways.reversed())
        assertEquals(change, reordered.legs)
        assertEquals(wayIdFor(change), reordered.pinnedWayId)
        assertNull(pinned.withWays(listOf(pinned.ways[0])).pinnedWayId)
        assertFailsWith<IllegalArgumentException> { twoWays.withWays(emptyList()) }
    }

    @Test
    fun upcomingMergesEveryWayInDepartureOrderTaggedWithItsWay() {
        val journeys = planner().upcoming(twoWays, now, 2)
        assertEquals(4, journeys.size)
        assertEquals(journeys.sortedBy { it.departure.expectedDepartureMs }, journeys)
        assertEquals(2, journeys.count { it.wayId == wayIdFor(direct) })
        assertEquals(2, journeys.count { it.wayId == wayIdFor(change) })
        // 07:00 either way: direct reaches Charlie 07:40, changing at Bravo (07:20 + 3 → 07:30) 07:45.
        val first = journeys.filter { it.departure.ref.tripId == "R1-04" }
        assertEquals(setOf(at(7, 40), at(7, 45)), first.map { it.arrival.expectedArrivalMs }.toSet())
    }

    @Test
    fun theSameRunReachedByTwoWaysIsListedOnce() {
        val anyLine = listOf(TripLeg(setOf("A"), setOf("C")))
        val trip = oneWay.withWays(listOf(RouteOption(wayIdFor(anyLine), anyLine), RouteOption(wayIdFor(direct), direct)))
        val journeys = planner().upcoming(trip, now, 3)
        assertEquals(listOf("R1-04", "R1-05", "R1-06"), journeys.map { it.departure.ref.tripId })
        assertTrue(journeys.all { it.wayId == wayIdFor(anyLine) })
        // ...and still counts as a run of the R1-only way.
        assertTrue(journeys.all { it.follows(wayIdFor(direct)) && it.alsoWays == setOf(wayIdFor(direct)) })
    }

    @Test
    fun aSingleWayTripIsUnchangedApartFromTheTag() {
        val single = SavedTrip("s", "Alpha", "Bravo", listOf(TripLeg(setOf("A"), setOf("B"))))
        val journeys = planner().upcoming(single, now, 3)
        assertEquals(listOf("R1-04", "R1-05", "R1-06"), journeys.map { it.departure.ref.tripId })
        assertTrue(journeys.all { it.wayId == single.ways.single().id })
    }

    @Test
    fun serviceDayAndUnderwayCoverEveryWay() {
        val day = planner().forServiceDay(twoWays, DAY)
        assertEquals(setOf(wayIdFor(direct), wayIdFor(change)), day.mapNotNull { it.wayId }.toSet())
        val t = at(7, 10)
        val underway = JourneyPlanner(tt, Overlay(tt, LiveSnapshot.EMPTY, t)).underway(twoWays, t)
        assertEquals(setOf(wayIdFor(direct), wayIdFor(change)), underway.filter { it.departure.ref.tripId == "R1-04" }.mapNotNull { it.wayId }.toSet())
    }

    @Test
    fun liveDataWatchesTheLinesOfEveryWay() {
        assertEquals(setOf("R1", "R2"), planner().routesOf(twoWays, emptyList()))
    }

    @Test
    fun alertsOnALineOfAnyWayTouchTheTrip() {
        val alert = Alert(id = "a", headline = "M1 delays", routeIds = listOf("R2"))
        assertEquals(emptyList(), alertsForTrip(oneWay, listOf(alert), tt))
        assertEquals(listOf(alert), alertsForTrip(twoWays, listOf(alert), tt))
    }

    @Test
    fun alarmsFollowThePinnedWayElseTheRunsNoOtherWayBeats() {
        val alarm = AlarmSpec("al", "t", AlarmKind.BEFORE_ARRIVAL, minutesBefore = 0)
        // Changing at Bravo never arrives before staying on the same train, so only direct arrivals:
        // 06:15, 06:30 and 06:45 from Alpha reach Charlie at 06:55, 07:10 and 07:25.
        val best = alarmTimes(tt, twoWays, alarm, now, 3)
        assertEquals(listOf(at(6, 55), at(7, 10), at(7, 25)), best)
        // Pinned to the change: 06:30 from Alpha is at Bravo 06:50, + 3 → the 07:00 metro → 07:15.
        val pinned = alarmTimes(tt, twoWays.copy(pinnedWayId = wayIdFor(change)), alarm, now, 3)
        assertEquals(listOf(at(6, 55), at(7, 15), at(7, 25)), pinned)
    }

    @Test
    fun bestRunsDropsRunsThatLeaveEarlierAndArriveNoSooner() {
        data class Run(val name: String, val leave: Int, val arrive: Int)
        val runs = listOf(
            Run("slow early", 0, 50),
            Run("fast later", 5, 40),
            Run("tie a", 10, 45),
            Run("tie b", 10, 45),
            Run("same leave slower", 10, 48),
            Run("last", 20, 60),
        )
        val kept = bestRuns(runs, { it.leave.toLong() }, { it.arrive.toLong() }).map { it.name }
        assertEquals(listOf("fast later", "tie a", "tie b", "last"), kept)
    }
}
