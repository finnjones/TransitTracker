package app.transittracker.server.planner

import app.transittracker.model.LatLng
import app.transittracker.model.LegKind
import app.transittracker.model.Mode
import app.transittracker.model.Occupancy
import app.transittracker.model.PlanTimeKind
import app.transittracker.model.changes
import app.transittracker.model.rides
import app.transittracker.model.walkSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mapping of the hand-made TfNSW answers in `testdata/planner/tfnsw` (not recorded from the live
 * API: see testdata/planner/README.md) against [PlannerTestTimetable].
 */
class TripMapperTest {
    private val timetable = PlannerTestTimetable()
    private val ends = JourneyEnds(LatLng(-33.8949, 151.1377), LatLng(-33.8920, 151.2780))

    private fun map(fixture: String, kind: PlanTimeKind = PlanTimeKind.DEPART_AT, tt: PlannerTestTimetable? = timetable): MappedPlan =
        TripMapper(tt).map(TfnswJson.decodeFromString(TripResponseDto.serializer(), PlannerFixtures.tfnsw(fixture)), ends, kind)

    @Test
    fun `a direct train is one matched ride, earliest arrival first, and the taxi journey is dropped`() {
        val plan = map("trip_direct_train.json")
        assertEquals(1, plan.droppedJourneys, "the journey needing a taxi")
        assertEquals(2, plan.itineraries.size)
        assertEquals(2, plan.matchedRides)
        assertEquals(0, plan.unmatchedRides)

        val first = plan.itineraries.first()
        val ride = first.legs.single()
        assertEquals(LegKind.RIDE, ride.kind)
        assertEquals(Mode.TRAIN, ride.mode)
        assertEquals("T9", ride.routeName)
        assertEquals("Gordon via Central", ride.headsign)
        assertEquals("Strathfield", ride.from.name)
        assertEquals("3", ride.from.platform)
        assertEquals("2135233", ride.from.stopId)
        assertEquals(local("08:02"), ride.from.scheduledMs)
        assertEquals(local("08:02"), ride.from.expectedMs, "real-time controlled: the planner's estimate is kept")
        assertEquals("Central", ride.to.name)
        assertEquals("16", ride.to.platform)
        assertEquals("2000336", ride.to.stopId)
        assertEquals(local("08:14"), ride.to.scheduledMs)
        assertEquals(720, ride.durationS)
        assertEquals(2, ride.stopCount, "Burwood and Central; the boarding stop does not count")
        assertEquals(Occupancy.FEW_SEATS_AVAILABLE, ride.occupancy)
        assertTrue(ride.realtime)
        assertNotNull(ride.path)
        // Matched by the planner's real-time trip id.
        assertEquals("121A.1450.133.8.T.8.86753001", ride.tripId)
        assertEquals(DAY, ride.serviceDate)
        assertEquals("T9", ride.routeId)
        assertEquals("D11F2F", ride.routeColor)
        assertTrue(ride.matched)

        assertEquals(local("08:17"), plan.itineraries[1].legs.single().from.scheduledMs)
    }

    @Test
    fun `arrive-by answers put the latest departure first`() {
        val plan = map("trip_direct_train.json", PlanTimeKind.ARRIVE_BY)
        assertEquals(listOf(local("08:17"), local("08:02")), plan.itineraries.map { it.legs.first().from.scheduledMs })
    }

    @Test
    fun `a train-to-train change gets an explicit walk from the foot-path information`() {
        val plan = map("trip_train_change.json")
        val itinerary = plan.itineraries.single()
        assertEquals(listOf(LegKind.RIDE, LegKind.WALK, LegKind.RIDE), itinerary.legs.map { it.kind })
        assertEquals(1, itinerary.changes)

        val (first, walk, second) = itinerary.legs
        assertEquals("Epping", first.from.name)
        assertEquals("121A.1450.133.8.T.8.86753100", first.tripId, "rule 1: real-time trip id")

        assertEquals(180, walk.durationS)
        assertEquals("Strathfield", walk.from.name)
        assertEquals("5", walk.from.platform)
        assertEquals("3", walk.to.platform)
        assertEquals(local("08:05"), walk.from.scheduledMs)
        assertEquals(local("08:08"), walk.to.scheduledMs)
        assertNotNull(walk.path, "the interchange path")

        // The planner's real-time id is not a GTFS trip: matched by line, time and stops instead.
        assertEquals("131B.1201.120.12.K.8.86750812", second.tripId)
        assertEquals("T2", second.routeId)
        assertEquals("0098CD", second.routeColor)
        assertEquals(local("08:12"), second.from.scheduledMs)
        assertEquals(local("08:13"), second.from.expectedMs)
        assertEquals(local("08:23"), second.to.expectedMs)
        assertEquals(2, plan.matchedRides)
    }

    @Test
    fun `train then bus with walks at both ends and a merged walk at the change`() {
        val plan = map("trip_train_bus_walks.json")
        val legs = plan.itineraries.single().legs
        assertEquals(listOf(LegKind.WALK, LegKind.RIDE, LegKind.WALK, LegKind.RIDE, LegKind.WALK), legs.map { it.kind })

        val (toStation, train, change, bus, toEnd) = legs
        assertEquals("Smith St", toStation.from.name)
        assertNull(toStation.from.stopId)
        assertEquals("Summer Hill", toStation.to.name)
        assertEquals(420, toStation.durationS)
        assertEquals(480, toStation.distanceM)

        assertEquals("131B.1201.120.12.K.8.86753200", train.tripId)
        assertEquals(local("07:33"), train.from.expectedMs)
        assertEquals(Occupancy.STANDING_ROOM_ONLY, train.occupancy)

        // Two walking legs (platform -> concourse -> stand) merged into one.
        assertEquals("Central", change.from.name)
        assertEquals("16", change.from.platform)
        assertEquals("Central", change.to.name)
        assertEquals("Stand B", change.to.platform)
        assertEquals(420, change.durationS)
        assertEquals(370, change.distanceM)

        assertEquals(Mode.BUS, bus.mode)
        assertEquals("333", bus.routeName)
        assertEquals("Stand B", bus.from.platform)
        assertEquals("Campbell Pde at Hall St", bus.to.name)
        assertNull(bus.to.stopId, "not in the timetable")
        assertNull(bus.tripId, "bus lines are not in the rail timetable: never guessed")
        assertNull(bus.expectedMs())

        // The walk to the destination came as foot-path information (AFTER) on the bus.
        assertEquals(300, toEnd.durationS)
        assertEquals(local("08:21"), toEnd.from.scheduledMs)
        assertEquals(local("08:26"), toEnd.to.scheduledMs)
        assertEquals(ends.to, toEnd.to.position)

        assertEquals(1, plan.matchedRides)
        assertEquals(1, plan.unmatchedRides)
        assertEquals(420 + 420 + 300, plan.itineraries.single().walkSeconds)
    }

    @Test
    fun `a walk-only answer is one walk`() {
        val plan = map("trip_walk_only.json")
        val walk = plan.itineraries.single().legs.single()
        assertEquals(LegKind.WALK, walk.kind)
        assertEquals("Smith St", walk.from.name)
        assertEquals("Lackey St", walk.to.name)
        assertEquals(720, walk.durationS)
        assertEquals(900, walk.distanceM)
        assertEquals(local("09:00"), walk.from.scheduledMs)
        assertEquals(local("09:12"), walk.to.scheduledMs)
        assertTrue(plan.itineraries.single().rides.isEmpty())
    }

    @Test
    fun `a cancelled ride is kept, flagged and still matched`() {
        val ride = map("trip_cancelled.json").itineraries.single().legs.single()
        assertTrue(ride.cancelled)
        assertEquals("121A.1450.133.8.T.8.86753009", ride.tripId)
    }

    @Test
    fun `a ride that cannot be matched keeps the planner's times and no timetable trip`() {
        val plan = map("trip_unmatched.json")
        val ride = plan.itineraries.single().legs.single()
        assertNull(ride.tripId)
        assertNull(ride.routeId)
        assertNull(ride.serviceDate)
        assertNull(ride.routeColor)
        assertEquals("2135233", ride.from.stopId, "stop ids are still set when they exist")
        assertEquals(local("07:58"), ride.from.scheduledMs)
        assertNull(ride.from.expectedMs, "not real-time controlled")
        assertEquals(0, plan.matchedRides)
        assertEquals(1, plan.unmatchedRides)
    }

    @Test
    fun `no journeys found is an empty list`() {
        val plan = map("trip_no_journeys.json")
        assertTrue(plan.itineraries.isEmpty())
        assertEquals(0, plan.droppedJourneys)
    }

    @Test
    fun `without a timetable nothing is matched and no stop ids are set`() {
        val plan = map("trip_direct_train.json", tt = null)
        val ride = plan.itineraries.first().legs.single()
        assertNull(ride.tripId)
        assertNull(ride.from.stopId)
        assertEquals("3", ride.from.platform)
        assertEquals(0, plan.matchedRides)
        assertEquals(2, plan.unmatchedRides)
    }

    @Test
    fun `itinerary ids are stable and differ between journeys`() {
        val a = map("trip_direct_train.json").itineraries.map { it.id }
        val b = map("trip_direct_train.json").itineraries.map { it.id }
        assertEquals(a, b)
        assertNotEquals(a[0], a[1])
        assertTrue(a.all { it.length == 16 })
        assertEquals(map("trip_walk_only.json").itineraries.single().id, map("trip_walk_only.json").itineraries.single().id)
    }

    @Test
    fun `product classes map to modes and unsupported modes`() {
        fun cls(c: Int, icon: Int? = null) = ProductClasses.classify(TransportationDto(product = ProductDto(productClass = c), iconId = icon), null)
        assertEquals(LegClass.Ride(Mode.TRAIN), cls(1))
        assertEquals(LegClass.Ride(Mode.METRO), cls(2))
        assertEquals(LegClass.Ride(Mode.LIGHT_RAIL), cls(4))
        assertEquals(LegClass.Ride(Mode.BUS), cls(5))
        assertEquals(LegClass.Ride(Mode.COACH), cls(7))
        assertEquals(LegClass.Ride(Mode.FERRY), cls(9))
        assertEquals(LegClass.Ride(Mode.BUS), cls(11))
        assertEquals(LegClass.Walk, cls(99))
        assertEquals(LegClass.Walk, cls(100))
        assertEquals(LegClass.Unsupported, cls(101), "bicycle")
        assertEquals(LegClass.Unsupported, cls(105), "taxi")
        assertEquals(LegClass.Unsupported, cls(5, icon = 23), "on demand")
    }

    @Test
    fun `names lose Station and the platform`() {
        assertEquals("Central", Names.stopName(LegStopDto(disassembledName = "Central Station, Platform 16")))
        assertEquals("Seven Hills", Names.stopName(LegStopDto(disassembledName = "Seven Hills Station, Stand A")))
        assertEquals("Seven Hills Rd opp Monaro St", Names.stopName(LegStopDto(disassembledName = "Seven Hills Rd opp Monaro St")))
        assertEquals("Circular Quay", Names.stopName(LegStopDto(name = "Circular Quay, Wharf 4, Sydney")))
        assertEquals("Wharf 4", Names.platform(LegStopDto(name = "Circular Quay, Wharf 4, Sydney")))
        assertEquals("Stand A", Names.platform(LegStopDto(disassembledName = "Seven Hills Station, Stand A")))
        assertNull(Names.platform(LegStopDto(disassembledName = "Seven Hills Rd opp Monaro St")))
    }

    private fun app.transittracker.model.ItineraryLeg.expectedMs(): Long? = from.expectedMs ?: to.expectedMs
}
