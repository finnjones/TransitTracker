package app.transittracker.ui.feature.plan

import app.transittracker.core.InMemoryKeyValueStore
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Itinerary
import app.transittracker.model.ItineraryLeg
import app.transittracker.model.LatLng
import app.transittracker.model.LegKind
import app.transittracker.model.LegPoint
import app.transittracker.model.Mode
import app.transittracker.model.PlanTimeKind
import app.transittracker.model.ServiceDetail
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopCall
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.nav.JourneyEnd
import app.transittracker.ui.planning.FakeJourneyPlanner
import app.transittracker.ui.settings.ClockFormat
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure rules of the planner screens: times, margins, live data, sorting, saving. */
class PlanRulesTest {
    private val min = 60_000L
    private val t0 = SampleData.NOW_MS / min * min // 07:38
    private val sydney = TimeZone.of("Australia/Sydney")

    private fun bondi(delay: Int = 4) = FakeJourneyPlanner.itineraries(t0, delay)

    // ------------------------------------------------------------ margins

    @Test
    fun marginsAreGreenFromThreeMinutesAmberUnderAndRedWhenTheWalkNoLongerFits() {
        assertEquals(Margin.OK, margin(3 * min))
        assertEquals(Margin.OK, margin(10 * min))
        assertEquals(Margin.TIGHT, margin(3 * min - 1_000))
        assertEquals(Margin.TIGHT, margin(0))
        assertEquals(Margin.MISSED, margin(-1_000))
        assertEquals(Margin.MISSED, margin(5 * min, cancelled = true))
        assertEquals(2, spareMinutesOf(2 * min + 59_000))
        assertEquals(-1, spareMinutesOf(-1_000))
        assertEquals(-2, spareMinutesOf(-61_000))
    }

    // ------------------------------------------------------------ timeline

    @Test
    fun theMockupJourneyLaysOutAsItsSteps() {
        val t = journeyTimeline(bondi().first(), fromName = "Home", toName = "Campbell Pde")
        assertEquals(t0 + 1 * min, t.leaveMs) // 07:39
        assertEquals(t0 + 55 * min, t.arriveMs) // 08:33
        assertEquals(54, t.minutes)
        assertEquals(2, t.changes)
        assertEquals(12, t.walkMinutes)
        val kinds = t.steps.map { it::class.simpleName }
        assertEquals(
            listOf("Start", "Walk", "Board", "Alight", "Change", "Board", "Alight", "Change", "Board", "Alight", "Walk", "End"),
            kinds,
        )
        assertEquals(listOf(3, 3), t.connections.map { it.spareMinutes })
        assertTrue(t.connections.all { it.margin == Margin.OK })
        assertEquals("24", t.connections[0].toPlatform)
        // T9 4 min late (planner live), T4 on time, the 333 timetabled.
        assertEquals(
            listOf(DepartureStatus.LATE, DepartureStatus.ON_TIME, DepartureStatus.TIMETABLED),
            t.rides.map { it.times.status },
        )
        assertEquals(listOf(3, 14, 7, 11, 6, 11, 2), stripParts(t).map { it.minutes })
        assertEquals(LiveState(app.transittracker.ui.format.Lamp.TRIANGLE, "T9 4 MIN LATE · CHANGE STILL OK", app.transittracker.ui.theme.ColorRole.CAUTION, "the T9 is 4 minutes late, the change still works"), liveState(t))
    }

    @Test
    fun aLaterT9MakesTheChangeTightThenMissesIt() {
        val tight = journeyTimeline(bondi(6).first())
        assertEquals(Margin.TIGHT, tight.connections[0].margin)
        assertEquals(1, tight.connections[0].spareMinutes)
        assertEquals("T9 6 MIN LATE · 1 MIN TO CHANGE", liveState(tight)?.words)

        val missed = journeyTimeline(bondi(9).first())
        val c = assertNotNull(missed.missed)
        assertEquals(-2, c.spareMinutes)
        assertEquals("MISSED BY 2 MIN", marginWords(c))
        assertEquals("T9 9 MIN LATE · MISSES THE T4", liveState(missed)?.words)
        assertEquals(
            "The T9 now reaches Central at 08:01. With the 4 minute walk you would miss the T4 at 08:03. Go back for another journey.",
            missedSentence(c, sydney, ClockFormat.H24),
        )
    }

    // ------------------------------------------------------------ live data from the phone

    private fun point(name: String, stop: String?, at: Long, platform: String? = null) =
        LegPoint(name, LatLng(0.0, 0.0), stop, platform, at)

    private val ref1 = ServiceRef("T9N-0745", "20261111")

    /** Walk 3, T9 Strathfield 07:45 -> Central 07:59 (matched), walk 4 (Central), T1 08:05 (not matched, live). */
    private fun twoRides(): Itinerary = Itinerary(
        "x",
        listOf(
            ItineraryLeg(LegKind.WALK, point("Home", null, t0 + 4 * min), point("Strathfield", null, t0 + 7 * min), 180, 250),
            ItineraryLeg(
                LegKind.RIDE, point("Strathfield", "S-P3", t0 + 7 * min, "3"), point("Central", "C-P3", t0 + 21 * min, "3"), 840,
                mode = Mode.TRAIN, routeName = "T9", routeId = "T9", tripId = ref1.tripId, serviceDate = ref1.serviceDate,
            ),
            ItineraryLeg(LegKind.WALK, point("Central", null, t0 + 21 * min), point("Central", null, t0 + 25 * min), 240, 300),
            ItineraryLeg(
                LegKind.RIDE, point("Central", "C-P16", t0 + 27 * min, "16"), point("Redfern", "R-P1", t0 + 32 * min, "1"), 300,
                mode = Mode.TRAIN, routeName = "T1", realtime = true, routeId = "T1",
            ),
        ),
    )

    private fun call(seq: Int, id: String, name: String, sched: Long, delay: Long, changed: Boolean = false) = StopCall(
        seq, id, name, if (changed) "6" else "3", changed, sched, sched, sched + delay, sched + delay, skipped = false, passed = false,
    )

    private fun detail(delay: Long, status: DepartureStatus = DepartureStatus.LATE, boardId: String = "S-P3", changed: Boolean = false) = ServiceDetail(
        ref1, null, "Gordon", status,
        listOf(
            call(1, boardId, "Strathfield Station Platform 3", t0 + 7 * min, delay, changed),
            call(2, "C-P3", "Central Station Platform 3", t0 + 21 * min, delay),
        ),
    )

    @Test
    fun thePhonesLiveDataMovesTheRideAndTheMarginsAfterIt() {
        val it = twoRides()
        val planned = journeyTimeline(it)
        assertEquals(2, planned.connections[0].spareMinutes) // 07:59 + 4 = 08:03, T1 at 08:05
        assertEquals(DepartureStatus.TIMETABLED, planned.rides[0].times.status)

        val late = journeyTimeline(it, mapOf(ref1 to detail(3 * min)))
        val r = late.rides[0].times
        assertTrue(r.fromPhone)
        assertEquals(DepartureStatus.LATE, r.status)
        assertEquals(180, r.delaySeconds)
        assertEquals(t0 + 10 * min, r.departMs)
        assertEquals(Margin.MISSED, late.connections[0].margin) // 08:02 + 4 = 08:06 > 08:05
        assertEquals(t0 + 7 * min, late.leaveMs) // leave 3 minutes later too
    }

    @Test
    fun aChangedPlatformIsFoundByNameAndTimetabledPhoneDataLeavesThePlannersTimes() {
        val it = twoRides()
        val moved = journeyTimeline(it, mapOf(ref1 to detail(0, DepartureStatus.ON_TIME, boardId = "S-P6", changed = true)))
        val r = moved.rides[0].times
        assertEquals(DepartureStatus.ON_TIME, r.status)
        assertEquals("6", r.platform)
        assertTrue(r.platformChanged)

        val noLive = journeyTimeline(it, mapOf(ref1 to detail(0, DepartureStatus.TIMETABLED)))
        assertEquals(false, noLive.rides[0].times.fromPhone)
        assertNull(phoneRideTimes(it.legs[1], detail(0, DepartureStatus.TIMETABLED)))

        val cancelled = journeyTimeline(it, mapOf(ref1 to detail(0, DepartureStatus.CANCELLED)))
        assertEquals(Margin.MISSED, cancelled.connections[0].margin)
        assertEquals("CONNECTION LOST", marginWords(cancelled.connections[0]))
        assertEquals("T9 CANCELLED", liveState(cancelled)?.words)
    }

    // ------------------------------------------------------------ options

    @Test
    fun theThreeButtonsReSortTheSameResults() {
        val options = bondi().mapIndexed { i, it -> optionSummary(i, it, emptyMap()) }
        assertEquals(listOf(54, 56, 66), options.map { it.minutes })
        assertEquals(options.sortedBy { it.arriveMs }.map { it.rank }, sortOptions(options, OptionSort.ARRIVE_FIRST).map { it.rank })
        assertEquals(2, sortOptions(options, OptionSort.FEWER_CHANGES).first().rank)
        assertEquals(1, sortOptions(options, OptionSort.LESS_WALKING).first().rank)
        assertEquals(listOf(12, 11, 38), options.map { it.walkMinutes })
    }

    @Test
    fun arriveFirstOrdersByArrivalNotByTimeTravelling() {
        val base = optionSummary(0, bondi().first(), emptyMap())
        fun option(rank: Int, leaveMin: Int, arriveMin: Int) =
            base.copy(rank = rank, timeline = base.timeline.copy(leaveMs = t0 + leaveMin * min, arriveMs = t0 + arriveMin * min))
        val quickButLate = option(0, leaveMin = 30, arriveMin = 70) // 40 min, arrives last
        val slowButFirst = option(1, leaveMin = 0, arriveMin = 50) // 50 min, arrives first
        val sameArrivalLeavesLater = option(2, leaveMin = 10, arriveMin = 50) // 40 min, arrives first too
        val sorted = sortOptions(listOf(quickButLate, slowButFirst, sameArrivalLeavesLater), OptionSort.ARRIVE_FIRST)
        // Arrival first; on the same arrival, the shorter one (it leaves later).
        assertEquals(listOf(2, 1, 0), sorted.map { it.rank })
    }

    @Test
    fun theLiveModesNoteNamesOnlyTheModesRidden() {
        val live = listOf(Mode.TRAIN, Mode.METRO)
        assertEquals("TRAINS ARE LIVE. BUS TIMES ARE TIMETABLED.", liveModesNote(live, setOf(Mode.TRAIN, Mode.BUS)))
        assertEquals("TRAINS AND METRO ARE LIVE.", liveModesNote(live, setOf(Mode.METRO, Mode.TRAIN)))
        assertEquals("METRO IS LIVE.", liveModesNote(live, setOf(Mode.METRO)))
        assertEquals("BUS AND FERRY TIMES ARE TIMETABLED.", liveModesNote(live, setOf(Mode.FERRY, Mode.BUS)))
        assertNull(liveModesNote(live, emptySet()))
    }

    @Test
    fun words() {
        assertEquals("PLAT 3", platformWords("3"))
        assertEquals("STAND B", platformWords("Stand B"))
        assertEquals("2 CHANGES · 12 MIN WALK", summaryWords(2, 12))
        assertEquals("NO CHANGES · NO WALKING", summaryWords(0, 0))
        assertEquals("1:10", leaveClock(t0 + 70_000, t0))
        assertEquals("0:00", leaveClock(t0 - 5_000, t0))
        assertEquals("1:02:03", leaveClock(t0 + 3_723_000, t0))
        assertEquals("1 minute 10 seconds", leaveClockSpoken(t0 + 70_000, t0))
    }

    // ------------------------------------------------------------ saving

    @Test
    fun aJourneyBecomesASavedTripWithOneLegPerRide() {
        val it = twoRides().let { i ->
            // Give the T1 its stops so the whole journey can be saved.
            i.copy(legs = i.legs.mapIndexed { n, l -> if (n == 3) l.copy(routeId = "T1") else l })
        }
        assertNull(saveBlocker(it))
        val trip = savedTripOf(it, mapOf("S-P3" to "S", "C-P3" to "C", "C-P16" to "C"))
        assertEquals("Strathfield", trip.originName)
        assertEquals("Redfern", trip.destinationName)
        assertEquals(2, trip.legs.size)
        assertEquals(setOf("S"), trip.legs[0].originStopIds)
        assertEquals(setOf("C"), trip.legs[0].destinationStopIds)
        assertEquals(setOf("T9"), trip.legs[0].routeIds)
        assertEquals(setOf("C"), trip.legs[1].originStopIds)
        assertEquals(setOf("R-P1"), trip.legs[1].destinationStopIds) // not mapped: kept as is
        assertEquals(4, trip.legs[1].minConnectionMinutes) // the walk at Central
        assertEquals("route-S.C.T9_C.R-P1.T1", trip.id)
        // Saving the same pattern again gives the same id (replaces, no copy).
        assertEquals(trip.id, savedTripOf(it, mapOf("S-P3" to "S", "C-P3" to "C", "C-P16" to "C")).id)

        val back = returnTripOf(trip)
        assertEquals("Redfern", back.originName)
        assertEquals("Strathfield", back.destinationName)
        assertEquals(listOf(setOf("R-P1"), setOf("C")), back.legs.map { it.originStopIds })
        assertEquals(listOf(setOf("C"), setOf("S")), back.legs.map { it.destinationStopIds })
        assertEquals(listOf(setOf("T1"), setOf("T9")), back.legs.map { it.routeIds })
        assertEquals(4, back.legs[1].minConnectionMinutes)
        assertEquals("route-R-P1.C.T1_C.S.T9", back.id)
    }

    @Test
    fun aJourneyWithoutStopIdsCannotBeSaved() {
        val reason = assertNotNull(saveBlocker(bondi().first()))
        assertTrue(reason.startsWith("The T9 train from Strathfield is not in the phone's timetable"), reason)
        val unknown = assertNotNull(unknownStopsReason(twoRides(), setOf("C-P3")))
        assertTrue("Central" in unknown, unknown)
        assertNull(unknownStopsReason(twoRides(), emptySet()))
        assertEquals("Home to Work by T9 and T4", routeDescription("Home", "Work", bondi().first().copy(legs = bondi().first().legs.dropLast(2))))
    }

    // ------------------------------------------------------------ time

    @Test
    fun whenTimesMoveInFiveMinuteStepsBetweenNowAndAWeekAhead() {
        val now = SampleData.NOW_MS // 07:38
        val first = initialPickedTime(now)
        assertEquals(t0 + 2 * min, first) // 07:40
        assertEquals(t0 + 7 * min, stepTime(first, 5, now))
        assertEquals(t0 - 3 * min, stepTime(first, -5, now)) // 07:35, the current five minutes
        assertEquals(t0 - 3 * min, stepTime(first, -60, now)) // never earlier
        assertEquals(false, canStepBack(t0 - 3 * min, 5, now))
        assertTrue(canStepForward(first, 24 * 60, now))
        assertEquals(null to PlanTimeKind.DEPART_AT, planTime(WhenKind.NOW, first))
        assertEquals(first to PlanTimeKind.ARRIVE_BY, planTime(WhenKind.ARRIVE_BY, first))
        assertEquals("TODAY", dayWords(first, now, sydney))
        assertEquals("TOMORROW", dayWords(first + 24 * 60 * min, now, sydney))
        assertEquals("FRI 13 NOV", dayWords(first + 2 * 24 * 60 * min, now, sydney))
        assertEquals("LEAVING NOW · 07:38", whenWords(null, PlanTimeKind.DEPART_AT, now, sydney, ClockFormat.H24))
        assertEquals("ARRIVE BY 07:40 · TOMORROW", whenWords(first + 24 * 60 * min, PlanTimeKind.ARRIVE_BY, now, sydney, ClockFormat.H24))
    }

    @Test
    fun recentSearchesKeepTheNewestFirstWithoutCopies() {
        val a = RecentSearch(JourneyEnd("Home", LatLng(1.0, 1.0)), JourneyEnd("Work", LatLng(2.0, 2.0)))
        val b = RecentSearch(JourneyEnd("Gym", LatLng(3.0, 3.0)), JourneyEnd("Work", LatLng(2.0, 2.0)))
        var list = addRecent(emptyList(), a)
        list = addRecent(list, b)
        list = addRecent(list, a)
        assertEquals(listOf(a, b), list)
        repeat(10) { n -> list = addRecent(list, RecentSearch(JourneyEnd("P$n", LatLng(n.toDouble(), 0.0)), a.to)) }
        assertEquals(MAX_RECENT, list.size)
    }

    @Test
    fun recentSearchesSurviveARestartWhenTheShellGivesAStore() = runTest {
        val a = RecentSearch(JourneyEnd("Home", LatLng(1.0, 1.0)), JourneyEnd("Work", LatLng(2.0, 2.0)))
        val b = RecentSearch(JourneyEnd("Gym", LatLng(3.0, 3.0)), JourneyEnd("Work", LatLng(2.0, 2.0)))
        val kv = InMemoryKeyValueStore()
        val first = KeyValueRecentSearches(kv, this)
        first.add(a)
        first.add(b)
        testScheduler.advanceUntilIdle()
        // "Restart": a new store over the same key-value store.
        val second = KeyValueRecentSearches(kv, this)
        second.add(a) // added before the stored list has loaded: stays first, no copy
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(a, b), second.recent.value)
        // Anything unreadable in the store is ignored.
        kv.put("plan.recent", "not json")
        val third = KeyValueRecentSearches(kv, this)
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList(), third.recent.value)
    }
}
