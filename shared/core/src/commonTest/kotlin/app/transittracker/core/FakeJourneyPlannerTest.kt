package app.transittracker.core

import app.transittracker.model.DepartureStatus
import app.transittracker.model.Itinerary
import app.transittracker.model.LegKind
import app.transittracker.model.Mode
import app.transittracker.model.Place
import app.transittracker.model.PlacesResult
import app.transittracker.model.PlanEndpoint
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanResult
import app.transittracker.model.PlanTimeKind
import app.transittracker.model.ServiceRef
import app.transittracker.model.WallClock
import app.transittracker.model.arriveMs
import app.transittracker.model.changes
import app.transittracker.model.departMs
import app.transittracker.model.rides
import app.transittracker.model.walkSeconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeJourneyPlannerTest {
    private val now = at(8, 3)
    private val planner = FakeJourneyPlanner(WallClock { now })

    private fun place(name: String): Place = FakeJourneyPlanner.PLACES.first { it.name == name }
    private fun endpoint(name: String) = place(name).let { PlanEndpoint(it.position, it.id) }

    private val farRequest = PlanRequest(from = endpoint("100 George St"), to = endpoint("Bondi Beach"))

    private suspend fun found(request: PlanRequest): List<Itinerary> =
        assertIs<PlanResult.Found>(planner.plan(request)).response.itineraries

    @Test
    fun placeSearch() = runTest {
        val central = assertIs<PlacesResult.Found>(planner.searchPlaces("cent")).places
        assertEquals("Central Station", central.first().name)
        assertEquals(FakeData.CENTRAL, central.first().stopId)
        assertTrue(central.any { it.name.startsWith("Central Station, Eddy Av") })

        val parramatta = assertIs<PlacesResult.Found>(planner.searchPlaces("parra", near = place("Bondi Beach").position)).places
        assertTrue(parramatta.size >= 3)
        assertEquals(parramatta.map { distanceMeters(place("Bondi Beach").position, it.position) }.sorted(), parramatta.map { distanceMeters(place("Bondi Beach").position, it.position) })

        assertEquals(listOf("10 Albert Rd"), assertIs<PlacesResult.Found>(planner.searchPlaces("albert stra")).places.map { it.name })
        assertEquals(PlacesResult.Found(emptyList()), planner.searchPlaces("c"))
        assertEquals(1, assertIs<PlacesResult.Found>(planner.searchPlaces("station", limit = 1)).places.size)
    }

    @Test
    fun simulatedOutcomes() = runTest {
        planner.simulate = FakeJourneyPlanner.Simulate.OFFLINE
        assertEquals(PlacesResult.Offline, planner.searchPlaces("central"))
        assertEquals(PlanResult.Offline, planner.plan(farRequest))
        planner.simulate = FakeJourneyPlanner.Simulate.UNAVAILABLE
        assertEquals(PlanResult.Unavailable, planner.plan(farRequest))
        planner.simulate = FakeJourneyPlanner.Simulate.FAILED
        assertIs<PlacesResult.Failed>(planner.searchPlaces("central"))
        planner.simulate = FakeJourneyPlanner.Simulate.NOTHING_FOUND
        assertTrue(found(farRequest).isEmpty())
        planner.simulate = FakeJourneyPlanner.Simulate.NONE
        assertTrue(found(farRequest).isNotEmpty())
    }

    @Test
    fun departAtGivesEachKindOfJourney() = runTest {
        val itineraries = found(farRequest)
        assertEquals(3, itineraries.size, "no walk-only answer for far ends")
        assertEquals(itineraries.map { it.arriveMs }.sorted(), itineraries.map { it.arriveMs }, "earliest arrival first")

        val trainTrain = itineraries.single { it.rides.map { r -> r.mode } == listOf(Mode.TRAIN, Mode.TRAIN) }
        assertEquals(1, trainTrain.changes)
        assertEquals(listOf("T1", "T9"), trainTrain.rides.map { it.routeName })
        assertEquals(listOf(LegKind.WALK, LegKind.RIDE, LegKind.WALK, LegKind.RIDE, LegKind.WALK), trainTrain.legs.map { it.kind })
        assertEquals("Strathfield", trainTrain.legs[2].from.name)
        assertTrue(trainTrain.rides.all { it.matched && it.routeColor != null && it.stopId() != null })

        val trainBus = itineraries.single { it.rides.map { r -> r.mode } == listOf(Mode.TRAIN, Mode.BUS) }
        assertEquals(listOf(LegKind.WALK, LegKind.RIDE, LegKind.WALK, LegKind.RIDE, LegKind.WALK), trainBus.legs.map { it.kind })
        assertTrue(trainBus.legs.filter { it.kind == LegKind.WALK }.all { it.durationS > 0 && (it.distanceM ?: 0) > 0 })
        val bus = trainBus.rides.last()
        assertEquals("Stand D", bus.from.platform)
        assertNull(bus.tripId, "the bus is not in the sample timetable")
        assertEquals(15 * 60, trainBus.walkSeconds)

        val cancelled = itineraries.single { itinerary -> itinerary.rides.any { it.cancelled } }
        assertEquals(Mode.BUS, cancelled.rides.single().mode)

        for (itinerary in itineraries) {
            assertEquals("100 George St", itinerary.legs.first().from.name)
            assertEquals("Bondi Beach", itinerary.legs.last().to.name)
            assertTrue(itinerary.departMs >= now, "leaves after now")
            itinerary.legs.zipWithNext().forEach { (a, b) -> assertTrue(a.to.scheduledMs <= b.from.scheduledMs, "legs in order in ${itinerary.id}") }
            itinerary.legs.forEach { assertTrue(it.durationS > 0 && it.path != null) }
        }
        assertEquals(itineraries.map { it.id }.toSet().size, itineraries.size)
    }

    @Test
    fun answersAreDeterministicAndRidesOpenInTheFakeCore() = runTest {
        assertEquals(found(farRequest), FakeJourneyPlanner(WallClock { now }).let { (it.plan(farRequest) as PlanResult.Found).response.itineraries })
        val core = FakeTransitCore(clock = WallClock { now })
        val ride = core.planner.itineraries(farRequest).first { it.rides.first().matched }.rides.first()
        val detail = core.service(ServiceRef(ride.tripId!!, ride.serviceDate!!)).first()
        val boarding = detail.calls.first { it.stopId == ride.from.stopId }
        assertEquals(ride.from.scheduledMs, boarding.scheduledDepartureMs)
        if (detail.status == DepartureStatus.CANCELLED) assertTrue(ride.cancelled) else assertEquals(boarding.expectedDepartureMs, ride.from.expectedMs ?: ride.from.scheduledMs)
    }

    @Test
    fun walkOnlyForCloseEnds() = runTest {
        val itineraries = found(PlanRequest(from = endpoint("Town Hall Station"), to = endpoint("Queen Victoria Building")))
        val walk = itineraries.first()
        assertEquals(listOf(LegKind.WALK), walk.legs.map { it.kind })
        assertEquals(0, walk.changes)
        assertTrue(walk.walkSeconds in 60..600)
        assertEquals(4, itineraries.size)
    }

    @Test
    fun arriveByEndsInTime() = runTest {
        val arriveBy = at(10, 0)
        val itineraries = found(farRequest.copy(timeMs = arriveBy, timeKind = PlanTimeKind.ARRIVE_BY))
        assertEquals(3, itineraries.size)
        itineraries.forEach { assertTrue(it.legs.last().to.scheduledMs <= arriveBy, it.id) }
        assertTrue(itineraries.all { it.legs.last().to.scheduledMs > arriveBy - 60 * MIN }, "not absurdly early")
        assertEquals(itineraries.map { it.departMs }.sortedDescending(), itineraries.map { it.departMs }, "latest departure first")
    }

    @Test
    fun requestOptions() = runTest {
        val noBus = found(farRequest.copy(excludedModes = listOf(Mode.BUS)))
        assertTrue(noBus.isNotEmpty() && noBus.flatMap { it.rides }.none { it.mode == Mode.BUS })
        assertEquals(1, found(farRequest.copy(maxItineraries = 1)).size)
        val later = found(farRequest.copy(timeMs = at(13, 0)))
        assertTrue(later.all { it.departMs >= at(13, 0) })
        val tooClose = planner.plan(PlanRequest(from = endpoint("Central Station"), to = PlanEndpoint(place("Central Station").position)))
        assertEquals(PlanResult.Failed(PlannerMessages.BAD_JOURNEY), tooClose)
    }

    @Test
    fun fakeCoreExposesThePlanner() = runTest {
        val core = FakeTransitCore(clock = WallClock { now })
        assertNotNull(assertIs<PlanResult.Found>(core.planner.plan(farRequest)).response.itineraries.firstOrNull())
    }

    @Test
    fun polylineRoundTrip() {
        val points = listOf(app.transittracker.model.LatLng(-33.8840, 151.2062), app.transittracker.model.LatLng(-33.89081, 151.27431), app.transittracker.model.LatLng(-33.0, 150.0))
        assertEquals(points, decodePolyline(encodePolyline(points)))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encodePolyline(listOf(app.transittracker.model.LatLng(38.5, -120.2), app.transittracker.model.LatLng(40.7, -120.95), app.transittracker.model.LatLng(43.252, -126.453))))
    }

    private fun app.transittracker.model.ItineraryLeg.stopId() = from.stopId
}
