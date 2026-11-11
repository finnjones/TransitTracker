package app.transittracker.server.planner

import app.transittracker.model.Itinerary
import app.transittracker.model.LatLng
import app.transittracker.model.LegKind
import app.transittracker.model.Mode
import app.transittracker.model.Occupancy
import app.transittracker.model.PlaceKind
import app.transittracker.model.PlanTimeKind
import app.transittracker.model.arriveMs
import app.transittracker.model.departMs
import app.transittracker.model.rides
import app.transittracker.server.pipeline.GtfsBundle
import app.transittracker.server.pipeline.GtfsLoader
import app.transittracker.server.pipeline.PackBuildRequest
import app.transittracker.server.pipeline.PackBuilder
import app.transittracker.server.pipeline.PackIdMaps
import app.transittracker.server.pipeline.ValidationReport
import app.transittracker.timetable.JvmTimetables
import app.transittracker.timetable.TimetablePack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real TfNSW Trip Planner answers (`testdata/planner/live`, captured 11 Nov 2026, API version
 * 10.6.21.17) mapped against a pack built from the real GTFS records they refer to
 * (`testdata/planner/live/gtfs`, cut from that day's Sydney Trains and Metro bundles). Each test
 * pins a mapping behaviour the live API showed.
 */
class LiveCaptureTest {
    private val live: Path = PlannerFixtures.dir.resolve("live")
    private val tmp: Path = Files.createTempDirectory("tt-live-capture")
    private val timetable: TimetablePack = run {
        val request = PackBuildRequest("syd-rail", "live-capture", LocalDate.of(2026, 11, 11))
        val feed = GtfsBundle.open(live.resolve("gtfs")).use { GtfsLoader.load(it, ValidationReport.Builder()) }
        val built = PackBuilder.buildContent(feed, request, PackIdMaps())
        JvmTimetables.open(PackBuilder.writeArtifacts(built, request, tmp).sqlite)
    }

    @AfterTest
    fun tearDown() {
        timetable.close()
        tmp.toFile().deleteRecursively()
    }

    private fun text(name: String) = Files.readString(live.resolve(name))

    private fun map(name: String, from: LatLng, to: LatLng, kind: PlanTimeKind = PlanTimeKind.DEPART_AT): MappedPlan =
        TripMapper(timetable).map(TfnswJson.decodeFromString(TripResponseDto.serializer(), text(name)), JourneyEnds(from, to), kind)

    /** The planner's `RealtimeTripId` of every ride, in order, per journey kept. */
    private fun realtimeIds(name: String): List<String> =
        Json.parseToJsonElement(text(name)).jsonObject["journeys"]!!.jsonArray.flatMap { j ->
            j.jsonObject["legs"]!!.jsonArray.mapNotNull { leg ->
                ((leg.jsonObject["transportation"] as? JsonObject)?.get("properties") as? JsonObject)?.get("RealtimeTripId")?.jsonPrimitive?.content
            }
        }

    private fun sydney(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(SYDNEY).toLocalTime().toString()

    private fun Itinerary.shape(): List<String> = legs.map { if (it.kind == LegKind.WALK) "walk ${it.durationS}s" else "${it.mode} ${it.routeName}" }

    // ---- journeys ----

    @Test
    fun `train to metro at Epping has the change walk, real stop counts and matched trips`() {
        val plan = map("trip-strathfield-macquarie-university.json", STRATHFIELD, MACQUARIE_UNI)
        assertEquals(2, plan.droppedJourneys, "two on-demand journeys (product class 10, iconId 23) are dropped")
        assertEquals(6, plan.itineraries.size)
        assertEquals(12, plan.matchedRides)
        assertEquals(0, plan.unmatchedRides)

        val first = plan.itineraries.first()
        assertEquals(listOf("TRAIN CCN", "walk 120s", "METRO M1"), first.shape())
        val (ccn, walk, metro) = first.legs
        // Express: stopSequence lists 9 stations, the train calls only at Epping.
        assertEquals(1, ccn.stopCount)
        assertEquals("Strathfield", ccn.from.name)
        assertEquals("3", ccn.from.platform)
        assertEquals("2135233", ccn.from.stopId)
        assertEquals("N671.1421.148.16.D.10.92279575", ccn.tripId)
        assertEquals("CCN_1c", ccn.routeId)
        assertEquals("20261111", ccn.serviceDate)
        assertTrue(ccn.realtime)
        assertEquals(Occupancy.UNKNOWN, ccn.occupancy)
        // footPathInfo AFTER 120 s with footPathInfoRedundant = true, and no walking leg: the walk is ours.
        assertEquals("Epping", walk.from.name)
        assertEquals("19:14", sydney(walk.from.scheduledMs))
        assertEquals("19:16", sydney(walk.to.scheduledMs))
        assertEquals("2121225", walk.to.stopId)
        assertEquals("5", metro.from.platform)
        assertEquals(1, metro.stopCount)
        assertEquals("SMNW_M1", metro.routeId)
        assertEquals(Occupancy.MANY_SEATS_AVAILABLE, metro.occupancy)

        // The all-stations T9 calls at every listed station.
        val t9 = plan.itineraries.first { it.rides.first().routeName == "T9" }.rides.first()
        assertEquals(8, t9.stopCount)
        assertEquals(Occupancy.FEW_SEATS_AVAILABLE, t9.occupancy)

        // Rule 1 for every ride: the planner's real-time trip id is the GTFS trip id (trains and metro).
        val ids = realtimeIds("trip-strathfield-macquarie-university.json").toSet()
        assertTrue(plan.itineraries.flatMap { it.rides }.all { it.tripId in ids })
    }

    @Test
    fun `train to bus at Bondi Junction walks the change and keeps the bus stand off the station's id`() {
        val plan = map("trip-central-bondi-beach.json", CENTRAL, BONDI_BEACH)
        assertEquals(6, plan.itineraries.size)
        val first = plan.itineraries.first()
        assertEquals(listOf("TRAIN T4", "walk 180s", "BUS 379", "walk 420s"), first.shape())
        val (train, change, bus, last) = first.legs
        assertEquals("24", train.from.platform)
        assertEquals("626N.1421.148.56.T.8.92126685", train.tripId)
        assertEquals("Bondi Junction", change.to.name)
        assertNull(change.to.stopId, "the bus stand is not in the rail pack")
        assertEquals("Bondi Junction", bus.from.name)
        assertEquals("Stand D", bus.from.platform)
        // TfNSW gives the bus stand the railway station as parent (202210): not the bus stop's id.
        assertNull(bus.from.stopId)
        assertNull(bus.tripId)
        assertEquals("Glenayr Ave at Hall St", bus.to.name)
        assertNull(bus.to.platform, "an ordinary bus stop's platformName is its name")
        assertEquals(7, bus.stopCount)
        assertEquals(456, last.distanceM)
        assertEquals("Bondi Beach", last.to.name)

        val second = plan.itineraries[1]
        val bus333 = second.rides.last()
        assertEquals("Bondi Beach, Campbell Pde", bus333.to.name)
        assertEquals("Stand C", bus333.to.platform)
        assertEquals(6, plan.matchedRides)
        assertEquals(6, plan.unmatchedRides, "the buses: not in the rail pack")
    }

    @Test
    fun `coordinate start, two train changes and a ferry`() {
        val plan = map("trip-parramatta-manly-wharf.json", PARRAMATTA, MANLY_WHARF)
        val first = plan.itineraries.first()
        assertEquals(listOf("walk 300s", "TRAIN T1", "walk 120s", "TRAIN T8", "walk 240s", "FERRY F1"), first.shape())
        val walk = first.legs[0]
        val t1 = first.legs[1]
        val t8 = first.legs[3]
        val ferry = first.legs[5]
        assertEquals("3 Darcy St", walk.from.name, "TfNSW names the coordinate's address")
        assertEquals(134, walk.distanceM)
        assertEquals("Parramatta", walk.to.name)
        assertEquals("2150411", walk.to.stopId)
        assertEquals("1", t1.from.platform)
        // The GTFS trip ends at Central; the planner runs it on to Town Hall (the through-running
        // continuation is a different GTFS trip in the same block). Not matched: never guessed.
        assertNull(t1.tripId)
        // Express: Strathfield, Redfern, Central, Town Hall (stopSequence lists 15 stations).
        assertEquals(4, t1.stopCount)
        assertEquals("88-F.1421.148.48.A.8.92128759", t8.tripId)
        assertEquals("Circular Quay", ferry.from.name)
        assertEquals("Wharf 3, Side A", ferry.from.platform)
        assertNull(ferry.from.stopId, "the wharf's parent is the railway station")
        assertEquals("Manly Wharf", ferry.to.name)
        assertEquals("Wharf 1", ferry.to.platform)
        assertEquals("20261112", t8.serviceDate)
    }

    @Test
    fun `arrive-by answers end before the time asked, latest departure first`() {
        val plan = map("trip-macquarie-university-opera-house-arrive-by.json", MACQUARIE_UNI, OPERA_HOUSE, PlanTimeKind.ARRIVE_BY)
        assertEquals(6, plan.itineraries.size)
        val arriveBy = Instant.parse("2026-11-11T22:00:00Z").toEpochMilli() // 09:00 on 12 Nov in Sydney
        assertTrue(plan.itineraries.all { it.arriveMs <= arriveBy })
        assertEquals(plan.itineraries.map { it.departMs }.sortedDescending(), plan.itineraries.map { it.departMs })
        assertTrue(plan.itineraries.all { it.shape() == listOf("METRO M1", "walk 1200s") })
        assertEquals(6, plan.matchedRides)
        assertEquals("Martin Place", plan.itineraries.first().rides.single().to.name)
    }

    @Test
    fun `excluding the metro leaves trains and buses, with the change walk at Epping`() {
        val plan = map("trip-strathfield-macquarie-university-no-metro.json", STRATHFIELD, MACQUARIE_UNI)
        assertEquals(2, plan.droppedJourneys)
        assertTrue(plan.itineraries.flatMap { it.rides }.none { it.mode == Mode.METRO })
        val first = plan.itineraries.first()
        assertEquals(listOf("TRAIN CCN", "walk 180s", "BUS 550"), first.shape())
        val bus = first.rides.last()
        assertNull(bus.from.stopId)
        assertEquals("Epping Station, Beecroft Rd", bus.from.name)
        assertEquals("Stand C", bus.from.platform)
        // The planner chose the bus by its live time: planned 19:10, before the train arrives at 19:14.
        assertEquals("19:10", sydney(bus.from.scheduledMs))
        assertEquals("19:22:24", sydney(bus.from.expectedMs!!))
    }

    @Test
    fun `trips cancelled in real time are left out by the planner, not flagged`() {
        val plan = map("trip-central-lewisham.json", CENTRAL, LatLng(-33.893279, 151.147273))
        assertEquals(6, plan.itineraries.size)
        assertTrue(plan.itineraries.flatMap { it.rides }.none { it.cancelled })
        // 84-W (T2 from Central at 19:47) was CANCELLED in the trip update feed at the time.
        assertTrue("84-W.1421.148.112.A.8.92125940" !in realtimeIds("trip-central-lewisham.json"))
    }

    @Test
    fun `no route, an unknown place id and a walk outside NSW`() {
        assertEquals(0, map("trip-no-route.json", LatLng(-33.90, 151.60), LatLng(-33.95, 151.65)).itineraries.size)
        assertEquals(0, map("trip-unknown-place-id.json", STRATHFIELD, MACQUARIE_UNI).itineraries.size)
        val melbourne = map("trip-melbourne-walk.json", LatLng(-37.8183, 144.9671), LatLng(-37.8136, 144.9631)).itineraries.single()
        assertEquals(listOf("walk 735s"), melbourne.shape())
    }

    // ---- places ----

    private fun places(name: String, query: String, near: LatLng? = null, limit: Int = 6) =
        PlaceRanking.rank(PlaceMapper(timetable).map(TfnswJson.decodeFromString(StopFinderResponseDto.serializer(), text(name))), query, near, limit)

    @Test
    fun `a station search finds the station first, wherever the phone is`() {
        val plain = places("stop_finder-strathfield.json", "strathfield")
        assertEquals(listOf("Strathfield Station", "North Strathfield Station"), plain.take(2).map { it.name })
        val station = plain.first()
        assertEquals("213510", station.id)
        assertEquals(PlaceKind.STOP, station.kind)
        assertEquals("Strathfield", station.detail)
        assertEquals("213510", station.stopId)
        assertEquals(listOf(Mode.TRAIN, Mode.BUS, Mode.COACH), station.modes)

        // A phone in the CBD: "Strathfield College, Haymarket" is nearer, but the stations come first.
        val cbd = places("stop_finder-strathfield.json", "strathfield", LatLng(-33.8688, 151.2093))
        assertEquals(listOf("Strathfield Station", "North Strathfield Station", "Strathfield College"), cbd.take(3).map { it.name })
        // A phone in St Johns Park, next to "Strathfield Cl".
        val west = places("stop_finder-strathfield.json", "strathfield", LatLng(-33.8885, 150.9041))
        assertEquals(listOf("Strathfield Station", "North Strathfield Station", "Strathfield Cl"), west.take(3).map { it.name })
    }

    @Test
    fun `stop names keep every part but the platform`() {
        val central = places("stop_finder-central.json", "central", limit = 10)
        assertEquals("Central Station", central.first().name)
        assertEquals("200060", central.first().stopId)
        assertEquals(listOf(Mode.TRAIN, Mode.METRO, Mode.LIGHT_RAIL, Mode.BUS, Mode.COACH), central.first().modes)
        // The first part alone ("Davistown") would hide which wharf it is.
        assertEquals("Davistown, Central RSL Wharf", central.single { it.id == "225114" }.name)

        val opera = places("stop_finder-sydney-opera-house.json", "sydney opera house").single()
        assertEquals(PlaceKind.POI, opera.kind)
        assertEquals("Sydney Opera House", opera.name)
        assertEquals("Sydney", opera.detail)

        val manly = places("stop_finder-manly-wharf.json", "manly wharf").first()
        assertEquals("Manly Wharf", manly.name)
        assertEquals(listOf(Mode.FERRY), manly.modes)
        assertNull(manly.stopId, "ferries are not in the rail pack")
    }

    private companion object {
        val SYDNEY: ZoneId = ZoneId.of("Australia/Sydney")
        val STRATHFIELD = LatLng(-33.871672, 151.094286)
        val MACQUARIE_UNI = LatLng(-33.777282, 151.118267)
        val CENTRAL = LatLng(-33.884024, 151.206203)
        val BONDI_BEACH = LatLng(-33.890461, 151.277142)
        val PARRAMATTA = LatLng(-33.81749, 151.005325)
        val MANLY_WHARF = LatLng(-33.799541, 151.284282)
        val OPERA_HOUSE = LatLng(-33.858485, 151.214258)
    }
}
