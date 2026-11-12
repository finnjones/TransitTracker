package app.transittracker.ui.feature.follow

import app.transittracker.model.Departure
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Journey
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.Route
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceDetail
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopCall
import app.transittracker.model.TrackingState
import app.transittracker.model.TripLeg
import app.transittracker.ui.feature.home.Followed
import app.transittracker.ui.feature.home.HomeInputs
import app.transittracker.ui.feature.home.buildHomeState
import app.transittracker.ui.feature.home.onTheWayOf
import app.transittracker.ui.feature.map.JourneyLegInput
import app.transittracker.ui.feature.map.JourneyMapInput
import app.transittracker.ui.feature.map.buildJourneyMapState
import app.transittracker.ui.feature.map.geometryFromStops
import app.transittracker.ui.nav.MapLeg
import app.transittracker.ui.platform.LocationFix
import app.transittracker.ui.settings.CardDensity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Home card and the journey map of a train-then-bus journey the phone is on. */
class OnTheWayTest {
    private val min = 60_000L
    private val t0 = 1_800_000_000_000L
    private fun at(m: Int) = t0 + m * min

    private val trip = SavedTrip(
        "train-bus", "Strathfield", "Uni", folder = "To uni",
        legs = listOf(
            TripLeg(setOf("STR"), setOf("PAR")),
            TripLeg(setOf("PAR-C"), setOf("UNI"), minConnectionMinutes = 4),
        ),
    )
    private val other = SavedTrip("other", "Central", "Bondi", listOf(TripLeg(setOf("CEN"), setOf("BJN"))), folder = "To uni")

    private fun dep(trip: String, mode: Mode, from: String, to: String, d: Int, a: Int, status: DepartureStatus = DepartureStatus.ON_TIME) = Departure(
        ServiceRef(trip, "20261111"), "R-$trip", if (mode == Mode.BUS) "600" else "T1", null, mode, "Somewhere", from,
        if (mode == Mode.BUS) "C" else "2", false, at(d), at(d), to, at(a), at(a), 0, status,
    )

    private val train = dep("T1-0800", Mode.TRAIN, "STR", "PAR", 0, 20)
    private val bus = dep("600-0823", Mode.BUS, "PAR-C", "UNI", 23, 40)
    private val journey = Journey(listOf(train, bus))
    private fun riding(leg: Int = 0) = Followed(FollowMatch(trip.id, train.ref, leg, FollowStage.RIDING, 0.0), journey)

    @Test
    fun ridingTheTrainShowsTheBusToCatchAndATightChange() {
        val otw = onTheWayOf(trip, riding(), mapOf("PAR" to "Parramatta"))
        assertEquals(train, otw.leg)
        assertEquals(bus, otw.next)
        assertEquals(3, otw.changeMinutes)
        // Three minutes to change where the route allows four.
        assertTrue(otw.tight)
        assertEquals("Parramatta", otw.legEnd)
        assertEquals(at(40), otw.arrivalMs)
    }

    @Test
    fun onTheBusThereIsNoConnection() {
        val otw = onTheWayOf(trip, riding(leg = 1), emptyMap())
        assertEquals(bus, otw.leg)
        assertNull(otw.next)
        assertTrue(otw.isLastLeg)
    }

    @Test
    fun theFollowedTripComesFirstWithTheOtherServicesUnderIt() {
        val next1 = Journey(listOf(dep("T1-0810", Mode.TRAIN, "STR", "PAR", 10, 30), dep("600-0833", Mode.BUS, "PAR-C", "UNI", 33, 50)))
        val next2 = Journey(listOf(dep("T1-0820", Mode.TRAIN, "STR", "PAR", 20, 40), dep("600-0843", Mode.BUS, "PAR-C", "UNI", 43, 60)))
        val inputs = HomeInputs(
            trips = listOf(other.copy(order = 0), trip.copy(order = 1)),
            upcoming = mapOf(trip.id to listOf(next1, next2), other.id to emptyList()),
            status = null, density = CardDensity.COMFORTABLE, places = emptyList(), links = emptyMap(),
            follow = riding(),
        )
        val s = buildHomeState(inputs, at(5), "20261111")
        val first = s.folders.first()
        assertNull(first.name)
        assertEquals(trip.id, first.cards.single().trip.id)
        assertNotNull(first.cards.single().onTheWay)
        assertEquals(listOf(next1, next2), first.cards.single().following)
        // The rest of its folder is unchanged.
        assertEquals(listOf("other"), s.folders[1].cards.map { it.trip.id })
        assertFalse(s.folders[1].cards.single().onTheWay != null)
    }

    // ---------------------------------------------------------------- the journey map

    private fun call(seq: Int, id: String, at: LatLng, arr: Long?, dep: Long?, passed: Boolean = false) =
        StopCall(seq, id, id, null, false, arr, dep, arr, dep, skipped = false, passed = passed, position = at)

    private fun tracked(d: Departure, calls: List<StopCall>, mode: Mode, color: String) = TrackingState(
        ServiceDetail(d.ref, Route(d.routeId, d.routeName, "", mode, color), d.headsign, DepartureStatus.ON_TIME, calls),
        vehicle = null, nextCall = calls.firstOrNull { !it.passed }, updatedMs = at(0),
    )

    @Test
    fun theMapDrawsTheTrainTheWalkAndTheBus() {
        val str = LatLng(-33.873, 151.093)
        val lid = LatLng(-33.866, 151.045)
        val par = LatLng(-33.817, 151.003)
        val parC = LatLng(-33.8165, 151.0045)
        val uni = LatLng(-33.796, 150.993)
        val trainCalls = listOf(
            call(1, "STR", str, null, at(0), passed = true),
            call(2, "LID", lid, at(8), at(8)),
            call(3, "PAR", par, at(20), null),
        )
        val busCalls = listOf(call(1, "PAR-C", parC, null, at(23)), call(2, "UNI", uni, at(40), null))
        val legs = listOf(
            JourneyLegInput(MapLeg(train.ref, "STR", "PAR"), tracked(train, trainCalls, Mode.TRAIN, "F99D1C"), geometryFromStops(trainCalls, trainCalls.map { it.position }), null),
            JourneyLegInput(MapLeg(bus.ref, "PAR-C", "UNI"), tracked(bus, busCalls, Mode.BUS, "00B5EF"), geometryFromStops(busCalls, busCalls.map { it.position }), null),
        )
        val fix = LocationFix(lid, at(10))
        val follow = FollowState(FollowStatus.FOLLOWING, FollowMatch(trip.id, train.ref, 0, FollowStage.RIDING, 0.0), fix)
        val s = buildJourneyMapState(JourneyMapInput(trip, legs, follow), at(10))

        assertEquals(listOf("leg-0", "leg-1", "walk-0"), s.scene.lines.map { it.id })
        assertTrue(s.scene.lines.single { it.id == "walk-0" }.walk)
        // The train under way; the bus (timetabled, not left yet) is not drawn standing on its stop.
        assertEquals(listOf("vehicle-0"), s.scene.vehicles.map { it.id })
        assertNotNull(s.scene.you)
        assertTrue(s.following)
        assertEquals(0, s.currentLeg)
        assertEquals("ARRIVES PAR IN", s.countdown?.label)
        assertEquals(at(20), s.countdown?.targetMs)
        assertEquals(3, s.changes.single().minutes)
        assertTrue(s.changes.single().tight)
        assertEquals("Strathfield to Uni", s.title)

        // Without the phone's location the clock says the same; once the train is in, the bus is next.
        val later = buildJourneyMapState(JourneyMapInput(trip, legs), at(21))
        assertFalse(later.following)
        assertEquals(1, later.currentLeg)
        assertEquals(at(23), later.countdown?.targetMs)
        // After the bus arrives: done.
        assertEquals("ARRIVED AT UNI", buildJourneyMapState(JourneyMapInput(trip, legs), at(41)).bigWords)
    }
}
