package app.transittracker.ui

import app.transittracker.model.Alert
import app.transittracker.model.AlertSeverity
import app.transittracker.model.DataStatus
import app.transittracker.model.Departure
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Journey
import app.transittracker.model.Mode
import app.transittracker.model.PackStatus
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceRef
import app.transittracker.model.ServiceTime
import app.transittracker.model.TimeRange
import app.transittracker.model.TripLeg
import app.transittracker.ui.feature.home.DataChip
import app.transittracker.ui.feature.timetable.DayRelation
import app.transittracker.ui.feature.timetable.NextService
import app.transittracker.ui.feature.timetable.TimetableInputs
import app.transittracker.ui.feature.timetable.alertsForTrip
import app.transittracker.ui.feature.timetable.buildTimetableState
import app.transittracker.ui.feature.timetable.canStepBack
import app.transittracker.ui.feature.timetable.canStepForward
import app.transittracker.ui.feature.timetable.daysFrom
import app.transittracker.ui.feature.timetable.dayRelation
import app.transittracker.ui.feature.timetable.entriesOf
import app.transittracker.ui.feature.timetable.headlineLeg
import app.transittracker.ui.feature.timetable.isTimetableOnly
import app.transittracker.ui.feature.timetable.splitEarlier
import app.transittracker.ui.feature.timetable.stepDay
import app.transittracker.ui.feature.timetable.tripTitleParts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure rules of the trip timetable: days, folding, alerts, the state for given inputs. */
class TimetableRulesTest {
    private val min = 60_000L
    private val today = "20261111"
    private val now = ServiceTime.toEpochMillis(today, 7 * 3600 + 38 * 60)

    private fun leg(
        id: String,
        depMin: Int,
        status: DepartureStatus = DepartureStatus.ON_TIME,
        delayMin: Int = 0,
        route: String = "T9",
        durationMin: Int = 14,
        date: String = today,
    ): Departure {
        val sched = ServiceTime.toEpochMillis(date, depMin * 60)
        return Departure(
            ref = ServiceRef(id, date), routeId = route, routeName = route, routeColor = "D11F2F", mode = Mode.TRAIN,
            headsign = "Gordon", originStopId = "A-P3", originPlatform = "3", platformChanged = false,
            scheduledDepartureMs = sched, expectedDepartureMs = sched + delayMin * min,
            destinationStopId = "B-P3", scheduledArrivalMs = sched + durationMin * min,
            expectedArrivalMs = sched + (durationMin + delayMin) * min,
            delaySeconds = if (status == DepartureStatus.TIMETABLED) null else delayMin * 60,
            status = status,
        )
    }

    private fun journey(id: String, depMin: Int, status: DepartureStatus = DepartureStatus.ON_TIME, delayMin: Int = 0) =
        Journey(listOf(leg(id, depMin, status, delayMin)))

    private val trip = SavedTrip("trip", "Strathfield", "Central", listOf(TripLeg(setOf("A"), setOf("B"))))

    // ---------------------------------------------------------------- days

    @Test
    fun stepDayCrossesMidnightMonthAndYearEnds() {
        assertEquals("20261112", stepDay("20261111", 1))
        assertEquals("20261201", stepDay("20261130", 1))
        assertEquals("20261130", stepDay("20261201", -1))
        assertEquals("20270101", stepDay("20261231", 1))
        assertEquals("20261231", stepDay("20270101", -1))
    }

    @Test
    fun stepDayKnowsLeapDays() {
        assertEquals("20260301", stepDay("20260228", 1))
        assertEquals("20240229", stepDay("20240228", 1))
        assertEquals("20240301", stepDay("20240229", 1))
        assertEquals("20240228", stepDay("20240301", -2))
    }

    @Test
    fun stepDayIsItsOwnInverseAndStepsSeveralDays() {
        for (d in listOf("20261031", "20260101", "20261029")) {
            assertEquals(d, stepDay(stepDay(d, 1), -1))
        }
        assertEquals("20261111", stepDay("20261031", 11))
    }

    @Test
    fun daysAndRelationsAreCountedFromToday() {
        assertEquals(0, daysFrom(today, today))
        assertEquals(1, daysFrom(today, "20261112"))
        assertEquals(20, daysFrom(today, "20261201"))
        assertEquals(-1, daysFrom(today, "20261110"))
        assertEquals(DayRelation.TODAY, dayRelation(today, today))
        assertEquals(DayRelation.TOMORROW, dayRelation("20261112", today))
        assertEquals(DayRelation.YESTERDAY, dayRelation("20261110", today))
        assertEquals(DayRelation.OTHER, dayRelation("20261113", today))
        // Across a month end.
        assertEquals(DayRelation.TOMORROW, dayRelation("20261201", "20261130"))
    }

    @Test
    fun theDayControlsStopWhereThePlanSaysTheyDo() {
        assertFalse(canStepBack(today, today))
        assertFalse(canStepBack("20261110", today))
        assertTrue(canStepBack("20261112", today))
        assertTrue(canStepForward(today, null))
        val pack = DataStatus(
            packs = listOf(PackStatus("rail", "1", "20261113", false), PackStatus("bus", "1", "20261120", false)),
            lastLiveUpdateMs = null, liveStale = false, serverReachable = true, joined = true, attribution = "",
        )
        assertTrue(canStepForward("20261112", pack), "the 13th is the last valid day of the shortest pack")
        assertFalse(canStepForward("20261113", pack))
    }

    // ---------------------------------------------------------------- rows

    @Test
    fun entriesAreInDepartureOrderWithTheirDuration() {
        val e = entriesOf(listOf(journey("b", 8 * 60), journey("a", 7 * 60 + 45, DepartureStatus.LATE, delayMin = 4)))
        assertEquals(listOf("a", "b"), e.map { it.journey.departure.ref.tripId })
        assertEquals(14, e[1].durationMinutes)
        assertEquals(14, e[0].durationMinutes) // a late service still takes 14 minutes
    }

    @Test
    fun onTodayServicesThatHaveLeftAreFoldedAboveNow() {
        val entries = entriesOf(
            listOf(journey("gone", 7 * 60 + 30), journey("late", 7 * 60 + 35, DepartureStatus.LATE, delayMin = 4), journey("next", 7 * 60 + 45)),
        )
        val (earlier, upcoming) = splitEarlier(entries, today, today, now)
        // "late" is timetabled for 07:35 but leaves at 07:39, after now (07:38): it is still to come.
        assertEquals(listOf("gone"), earlier.map { it.journey.departure.ref.tripId })
        assertEquals(listOf("late", "next"), upcoming.map { it.journey.departure.ref.tripId })
    }

    @Test
    fun aServiceLeavingThisMinuteIsStillToCome() {
        val e = entriesOf(listOf(journey("now", 7 * 60 + 38)))
        assertEquals(emptyList(), splitEarlier(e, today, today, now).first)
    }

    @Test
    fun otherDaysFoldNothing() {
        val tomorrow = "20261112"
        val e = entriesOf(listOf(Journey(listOf(leg("a", 5 * 60, date = tomorrow))), Journey(listOf(leg("b", 23 * 60, date = tomorrow)))))
        val (earlier, upcoming) = splitEarlier(e, tomorrow, today, now)
        assertTrue(earlier.isEmpty())
        assertEquals(2, upcoming.size)
        // Yesterday is not folded either: it is simply another day.
        assertTrue(splitEarlier(e, "20261110", today, now).first.isEmpty())
    }

    @Test
    fun theRowShowsTheWorstLegAndTimetableOnlyMeansNoLiveLeg() {
        val ok = leg("a", 400)
        val cancelled = leg("b", 420, DepartureStatus.CANCELLED)
        val late = leg("c", 430, DepartureStatus.LATE, delayMin = 3)
        assertEquals(cancelled, headlineLeg(Journey(listOf(ok, cancelled))))
        assertEquals(late, headlineLeg(Journey(listOf(ok, late))))
        assertEquals(ok, headlineLeg(Journey(listOf(ok))))
        val tt = journey("t", 400, DepartureStatus.TIMETABLED)
        assertTrue(isTimetableOnly(listOf(tt, tt)))
        assertFalse(isTimetableOnly(listOf(tt, journey("l", 410))))
        assertFalse(isTimetableOnly(emptyList()))
    }

    // ---------------------------------------------------------------- alerts

    private fun alert(
        id: String,
        routes: List<String> = emptyList(),
        periods: List<TimeRange> = emptyList(),
        severity: AlertSeverity = AlertSeverity.WARNING,
    ) = Alert(id, "Headline $id", severity = severity, routeIds = routes, activePeriods = periods)

    @Test
    fun alertsOnTheTripsLinesAreFoundForTheDayShown() {
        val journeys = listOf(journey("a", 400))
        val dayStart = ServiceTime.referenceMillis("20261113")
        val weekend = alert("weekend", listOf("T9"), listOf(TimeRange(dayStart + 3600_000, dayStart + 30 * 3600_000)))
        val otherLine = alert("other", listOf("T4"))
        val always = alert("always", listOf("T9"))
        val over = alert("over", listOf("T9"), listOf(TimeRange(dayStart - 96 * 3600_000, dayStart - 72 * 3600_000)))
        val onStop = Alert("stop", "At a stop", stopIds = listOf("A"))
        val all = listOf(weekend, otherLine, always, over, onStop)
        assertEquals(listOf("weekend", "always"), alertsForTrip(all, trip, journeys, "20261113").map { it.id }.sorted().reversed())
        assertEquals(listOf("always"), alertsForTrip(all, trip, journeys, "20261111").map { it.id })
        // The line comes from the trip's own filter when the day has no services.
        val filtered = trip.copy(legs = listOf(TripLeg(setOf("A"), setOf("B"), routeIds = setOf("T9"))))
        assertEquals(listOf("always"), alertsForTrip(all, filtered, emptyList(), "20261111").map { it.id })
    }

    @Test
    fun theMostSevereAlertComesFirst() {
        val journeys = listOf(journey("a", 400))
        val list = alertsForTrip(
            listOf(alert("w", listOf("T9")), alert("s", listOf("T9"), severity = AlertSeverity.SEVERE), alert("i", listOf("T9"), severity = AlertSeverity.INFO)),
            trip, journeys, today,
        )
        assertEquals(listOf("s", "w", "i"), list.map { it.id })
    }

    // ---------------------------------------------------------------- the whole state

    private fun inputs(journeys: List<Journey>?, date: String = today, status: DataStatus? = null, next: Map<String, NextService?> = emptyMap(), trip: SavedTrip? = this.trip) =
        TimetableInputs(tripsRead = true, trip = trip, date = date, journeys = journeys, status = status, next = next)

    @Test
    fun stateIsLoadingUntilTheTripIsRead() {
        val s = buildTimetableState(TimetableInputs(tripsRead = false, trip = null, date = today, journeys = null), now)
        assertTrue(s.loading)
        assertTrue(buildTimetableState(inputs(null, trip = null), now).let { !it.loading && !it.tripFound })
    }

    @Test
    fun stateFoldsEarlierServicesAndShowsTodayWithTheNowMarker() {
        val js = listOf(journey("gone", 7 * 60 + 30), journey("next", 7 * 60 + 45), journey("after", 8 * 60))
        val s = buildTimetableState(inputs(js), now)
        assertTrue(s.isToday)
        assertEquals(1, s.earlier.size)
        assertEquals(2, s.upcoming.size)
        assertFalse(s.isEmpty)
        assertFalse(s.nothingToCome)
        assertFalse(s.timetableOnly)
        assertEquals(listOf("Strathfield", "Central"), s.titleParts)
        assertNull(s.stationsLine)
    }

    @Test
    fun anEmptyDayHasNoServicesAndTheNextOneWhenFound() {
        val none = buildTimetableState(inputs(emptyList()), now)
        assertTrue(none.isEmpty)
        assertTrue(none.nothingToCome)
        assertFalse(none.nextSearched)
        val next = NextService("20261112", ServiceTime.toEpochMillis("20261112", 5 * 3600))
        val found = buildTimetableState(inputs(emptyList(), next = mapOf(today to next)), now)
        assertEquals(next, found.next)
        assertTrue(found.nextSearched)
        val missing = buildTimetableState(inputs(emptyList(), next = mapOf(today to null)), now)
        assertNull(missing.next)
        assertTrue(missing.nextSearched)
    }

    @Test
    fun whenEverythingHasLeftTodayNothingIsToComeButTheDayIsNotEmpty() {
        val s = buildTimetableState(inputs(listOf(journey("gone", 7 * 60 + 30))), now)
        assertFalse(s.isEmpty)
        assertTrue(s.nothingToCome)
    }

    @Test
    fun aFutureDayWithoutLiveDataIsTimetableOnly() {
        val tomorrow = "20261112"
        val js = listOf(Journey(listOf(leg("a", 5 * 60, DepartureStatus.TIMETABLED, date = tomorrow))))
        val s = buildTimetableState(inputs(js, date = tomorrow), now)
        assertTrue(s.timetableOnly)
        assertFalse(s.isToday)
        assertTrue(s.earlier.isEmpty())
        assertTrue(s.canGoBack)
    }

    @Test
    fun theChipAndBannersFollowTheDataStatus() {
        // A timetable is installed (there are journeys); only the server cannot be reached.
        val installed = listOf(app.transittracker.model.PackStatus("syd-rail", "1", null, updateAvailable = false))
        val offline = DataStatus(installed, null, liveStale = false, serverReachable = false, joined = true, attribution = "")
        val s = buildTimetableState(inputs(listOf(journey("a", 8 * 60)), status = offline), now)
        assertEquals(DataChip.Offline, s.chip)
        assertEquals(1, s.banners.size)
    }

    @Test
    fun placesGiveTheTitleAndTheStationsLine() {
        val from = app.transittracker.ui.places.SavedPlace("h", "Home", app.transittracker.ui.places.PlaceIcon.HOME, "", app.transittracker.model.LatLng(0.0, 0.0), emptyList())
        assertEquals(listOf("Home", "Central"), tripTitleParts(trip, from, null))
        assertEquals(listOf("Strathfield", "Central"), tripTitleParts(trip, null, null))
        assertEquals(listOf("My commute"), tripTitleParts(trip.copy(customName = "My commute"), null, null))
        val s = buildTimetableState(
            inputs(emptyList()).copy(places = listOf(from), links = mapOf("trip" to app.transittracker.ui.places.TripPlaces("trip", "h", null))), now,
        )
        assertEquals(listOf("Home", "Central"), s.titleParts)
        assertEquals("STRATHFIELD TO CENTRAL", s.stationsLine)
    }
}
