package app.transittracker.ui

import app.transittracker.model.Departure
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Journey
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.RouteOption
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceRef
import app.transittracker.model.TripLeg
import app.transittracker.model.wayIdFor
import app.transittracker.ui.feature.home.bestFollowing
import app.transittracker.ui.feature.home.bestHeadline
import app.transittracker.ui.feature.home.journeysShown
import app.transittracker.ui.feature.home.leaveAtFor
import app.transittracker.ui.feature.home.wayName
import app.transittracker.ui.feature.home.waysLabel
import app.transittracker.ui.feature.places.WayRow
import app.transittracker.ui.feature.places.withEditedWays
import app.transittracker.ui.feature.plan.combineWays
import app.transittracker.ui.feature.plan.hasAllWays
import app.transittracker.ui.feature.plan.returnTripOf
import app.transittracker.ui.feature.plan.routeToJoin
import app.transittracker.ui.feature.plan.tripIdFor
import app.transittracker.ui.feature.timetable.journeysForTab
import app.transittracker.ui.places.PlaceIcon
import app.transittracker.ui.places.PlaceStop
import app.transittracker.ui.places.SavedPlace
import app.transittracker.ui.places.TripPlaces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A saved route with several ways: which way Home offers, the timetable tabs, saving and editing ways. */
class WaysRulesTest {
    private val min = 60_000L
    private val now = 1_000_000_000L

    /** The T9 from Strathfield (S) to Central (C). */
    private val train = listOf(TripLeg(setOf("S"), setOf("C"), routeIds = setOf("T9")))

    /** The 400 bus from Albert Rd (B) to Central (C). */
    private val bus = listOf(TripLeg(setOf("B"), setOf("C"), routeIds = setOf("400")))

    private val trainWay = RouteOption(wayIdFor(train), train)
    private val busWay = RouteOption(wayIdFor(bus), bus)
    private val route = SavedTrip(tripIdFor(train), "Strathfield", "Central", train).withWays(listOf(trainWay, busWay))

    private fun home(trainWalk: Int, busWalk: Int = 2) = SavedPlace(
        "home", "Home", PlaceIcon.HOME, "Albert Rd", LatLng(0.0, 0.0),
        listOf(PlaceStop("S", "Strathfield", trainWalk, 1000), PlaceStop("B", "Albert Rd", busWalk, 100)),
    )

    private fun run(id: String, way: RouteOption, from: String, line: String, leaveMin: Int, arriveMin: Int, status: DepartureStatus = DepartureStatus.ON_TIME) =
        Journey(
            listOf(
                Departure(
                    ref = ServiceRef(id, "20261111"), routeId = line, routeName = line, routeColor = null,
                    mode = if (line == "400") Mode.BUS else Mode.TRAIN, headsign = "Central", originStopId = from,
                    originPlatform = null, platformChanged = false,
                    scheduledDepartureMs = now + leaveMin * min, expectedDepartureMs = now + leaveMin * min,
                    destinationStopId = "C", scheduledArrivalMs = now + arriveMin * min, expectedArrivalMs = now + arriveMin * min,
                    delaySeconds = 0, status = status,
                ),
            ),
            wayId = way.id,
        )

    // Train at +10 arrives +30; bus at +5 arrives +35; next train at +25 arrives +45; bus at +20 arrives +50.
    private val t1 = run("t1", trainWay, "S", "T9", 10, 30)
    private val b1 = run("b1", busWay, "B", "400", 5, 35)
    private val t2 = run("t2", trainWay, "S", "T9", 25, 45)
    private val b2 = run("b2", busWay, "B", "400", 20, 50)
    private val runs = listOf(b1, t1, b2, t2)

    // ------------------------------------------------------------ Home

    @Test
    fun homeOffersTheWayThatArrivesFirstAmongThoseYouCanStillMakeWithoutRunning() {
        // A 5 minute walk to the station: leave by +5 for the train, which arrives first.
        assertSame(t1, bestHeadline(route, runs, home(trainWalk = 5), now))
        // A 15 minute walk: the train needed leaving 5 minutes ago, so the bus.
        assertSame(b1, bestHeadline(route, runs, home(trainWalk = 15), now))
        // Not from a saved place: by departure, so the train.
        assertSame(t1, bestHeadline(route, runs, null, now))
    }

    @Test
    fun homeRunsForAServiceWhenNothingElseCanBeMade() {
        // Both need running; the train is still there and arrives first.
        assertSame(t1, bestHeadline(route, listOf(t1, b1), home(trainWalk = 15, busWalk = 9), now))
    }

    @Test
    fun aCancelledRunNeverHeadlines() {
        val cancelled = run("t1", trainWay, "S", "T9", 10, 30, DepartureStatus.CANCELLED)
        assertSame(b1, bestHeadline(route, listOf(b1, cancelled, t2), null, now))
    }

    @Test
    fun theServicesUnderTheHeadlineAreTheOnesNoOtherWayBeats() {
        val place = home(trainWalk = 5)
        // Under the +10 train: the +5 bus (leave +3, arrive +35) is beaten by that train (leave +5,
        // arrive +30), and the +20 bus (leave +18, arrive +50) by the +25 train (leave +20, arrive +45).
        assertEquals(listOf(t2), bestFollowing(route, runs, t1, place))
        assertEquals(now + 20 * min, leaveAtFor(route, t2, place))
    }

    @Test
    fun aPinnedWayIsTheOnlyOneHomeShows() {
        val pinned = route.copy(pinnedWayId = busWay.id)
        assertEquals(listOf(b1, b2), journeysShown(pinned, runs))
        assertEquals(runs, journeysShown(route, runs))
    }

    @Test
    fun theCardSaysHowManyWaysOrWhichIsPinned() {
        assertEquals("BEST OF 2 WAYS", waysLabel(route) { "x" })
        assertEquals("PINNED · 400", waysLabel(route.copy(pinnedWayId = busWay.id)) { wayName(route, it, runs) })
        assertNull(waysLabel(SavedTrip("one", "S", "C", train)) { "x" })
    }

    @Test
    fun aWayIsCalledByItsLabelElseItsLines() {
        assertEquals("T9", wayName(route, trainWay, runs))
        assertEquals("Way 2", wayName(route, busWay, emptyList()))
        assertEquals("Bus from home", wayName(route, busWay.copy(label = "Bus from home"), runs))
        // A way on any line, from runs of two lines, and runs another way reached first.
        val any = RouteOption(wayIdFor(listOf(TripLeg(setOf("S"), setOf("C")))), listOf(TripLeg(setOf("S"), setOf("C"))))
        val t1Shared = t1.copy(alsoWays = setOf(any.id))
        val t3 = run("t3", any, "S", "T1", 40, 60)
        assertEquals("T1/T9", wayName(route, any, listOf(t1Shared, t3)))
    }

    // ------------------------------------------------------------ timetable tabs

    @Test
    fun timetableTabsShowOneWayOrTheBestOfAll() {
        assertEquals(listOf(t1, t2), journeysForTab(route, runs, trainWay.id, null))
        // BEST without a place: each bus leaves before a train that arrives sooner.
        assertEquals(listOf(t1, t2), journeysForTab(route, runs, null, null))
        // Arriving later but leaving later still counts: a bus at +12 arriving +33 stays.
        val b3 = run("b3", busWay, "B", "400", 12, 33)
        assertEquals(listOf(t1, b3, t2), journeysForTab(route, listOf(t1, b3, t2), null, null))
        // A run shared with another way shows on that way's tab too.
        val shared = b1.copy(alsoWays = setOf(trainWay.id))
        assertEquals(listOf(shared, t1, t2), journeysForTab(route, listOf(shared, t1, b2, t2), trainWay.id, null))
        // One way: everything.
        val one = SavedTrip("one", "S", "C", train)
        assertEquals(runs, journeysForTab(one, runs, null, null))
    }

    // ------------------------------------------------------------ saving

    @Test
    fun theReturnReversesEveryWayAndKeepsThePin() {
        val back = returnTripOf(route.copy(pinnedWayId = busWay.id))
        assertEquals(2, back.ways.size)
        assertEquals(setOf("C"), back.ways[1].legs.single().originStopIds)
        assertEquals(setOf("B"), back.ways[1].legs.single().destinationStopIds)
        assertEquals(back.ways[1].id, back.pinnedWayId)
        assertEquals("Central", back.originName)
    }

    @Test
    fun aNewWayJoinsTheRouteBetweenTheSamePlaces() {
        val busOnly = SavedTrip(tripIdFor(bus), "Albert Rd", "Central", bus)
        val trainOnly = SavedTrip(tripIdFor(train), "Strathfield", "Central", train)
        val links = mapOf(trainOnly.id to TripPlaces(trainOnly.id, "home", "work"))
        assertEquals(trainOnly, routeToJoin(listOf(trainOnly), links, busOnly, "home", "work"))
        assertNull(routeToJoin(listOf(trainOnly), links, busOnly, "home", "gym"), "another place")
        assertNull(routeToJoin(listOf(trainOnly), links, busOnly, null, null), "no places, and different stations")
    }

    @Test
    fun withoutPlacesANewWayJoinsTheRouteBetweenTheSameStations() {
        val trainOnly = SavedTrip(tripIdFor(train), "Strathfield", "Central", train)
        val viaT1 = listOf(TripLeg(setOf("S"), setOf("C"), routeIds = setOf("T1")))
        val t1Only = SavedTrip(tripIdFor(viaT1), "Strathfield", "Central", viaT1)
        assertEquals(trainOnly, routeToJoin(listOf(trainOnly), emptyMap(), t1Only, null, null))
        // A route that is linked to places is not joined by a journey between stations.
        assertNull(routeToJoin(listOf(trainOnly), mapOf(trainOnly.id to TripPlaces(trainOnly.id, "home", null)), t1Only, null, null))
        // The same pattern saved again always joins (its other ways are kept).
        assertEquals(route, routeToJoin(listOf(route), emptyMap(), SavedTrip(route.id, "S", "C", train), "x", "y"))
        assertTrue(hasAllWays(route, SavedTrip(route.id, "S", "C", train)))
    }

    @Test
    fun waysSavedTogetherAreNamedAfterTheJourneysEndsWhenTheyStartApart() {
        val combined = combineWays(
            listOf(SavedTrip(tripIdFor(train), "Strathfield", "Central", train), SavedTrip(tripIdFor(bus), "Albert Rd", "Central", bus)),
            "Home", "Work",
        )
        assertEquals("Home", combined.originName)
        assertEquals("Central", combined.destinationName)
        assertEquals(listOf(trainWay.id, busWay.id), combined.ways.map { it.id })
        assertEquals(tripIdFor(train), combined.id)
    }

    // ------------------------------------------------------------ editing

    @Test
    fun editingWaysRenamesAndRemovesThem() {
        val edited = withEditedWays(route, listOf(WayRow(trainWay.id, 1, " ", removed = true), WayRow(busWay.id, 2, " Bus ")))
        assertEquals(listOf(busWay.copy(label = "Bus")), edited.ways)
        assertEquals(bus, edited.legs)
        // Removing every way changes nothing (the screen does not allow it).
        assertEquals(route, withEditedWays(route, listOf(WayRow(trainWay.id, 1, "", removed = true), WayRow(busWay.id, 2, "", removed = true))))
    }
}
