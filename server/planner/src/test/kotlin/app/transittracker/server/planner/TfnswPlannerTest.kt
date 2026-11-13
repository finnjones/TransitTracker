package app.transittracker.server.planner

import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.PlaceKind
import app.transittracker.model.PlanEndpoint
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanTimeKind
import app.transittracker.model.Timetable
import app.transittracker.server.live.BackoffPolicy
import app.transittracker.server.live.DailyCallBudget
import app.transittracker.server.live.RateLimiter
import app.transittracker.server.live.TfnswQuota
import app.transittracker.server.spi.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TfnswPlannerTest {
    private val dir = Files.createTempDirectory("tt-planner")
    private val clock = MutableClock(local("07:50"))
    private val timetable = MutableStateFlow<Timetable?>(PlannerTestTimetable())
    private val fetcher = FakePlannerFetcher()
    private val quota = quota(budget = 100)

    private fun quota(budget: Int) = TfnswQuota(
        DailyCallBudget(budget, dir.resolve("calls.txt"), clock),
        RateLimiter(1000),
        budget,
    )

    private fun planner(
        f: PlannerFetcher = fetcher,
        q: TfnswQuota? = quota,
    ) = TfnswPlanner(timetable, f, q, clock, liveModes = listOf(Mode.TRAIN, Mode.METRO), backoff = BackoffPolicy(random = Random(1)))

    private val strathfield = LatLng(-33.871770, 151.094250)
    private val central = LatLng(-33.883970, 151.206840)
    private fun request(
        from: LatLng = strathfield,
        to: LatLng = central,
        timeMs: Long? = local("08:00"),
        max: Int = 5,
        excluded: List<Mode> = emptyList(),
        kind: PlanTimeKind = PlanTimeKind.DEPART_AT,
        fromPlace: String? = null,
    ) = PlanRequest(PlanEndpoint(from, fromPlace), PlanEndpoint(to), timeMs, kind, max, excluded)

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private suspend fun code(block: suspend () -> Unit): Pair<Int, String> {
        val e = assertFailsWith<ApiException> { block() }
        return e.status to e.code
    }

    // ---- request to TfNSW ----

    @Test
    fun `trip parameters follow the Swagger`() = runTest {
        val response = planner().plan(request(excluded = listOf(Mode.BUS, Mode.FERRY), fromPlace = "200060"))
        val params = fetcher.calls.single().second
        assertEquals(PlannerEndpoint.TRIP, fetcher.calls.single().first)
        assertEquals(
            listOf(
                "outputFormat" to "rapidJSON",
                "coordOutputFormat" to "EPSG:4326",
                "depArrMacro" to "dep",
                "itdDate" to "20261111",
                "itdTime" to "0800",
                "type_origin" to "any",
                "name_origin" to "200060",
                "type_destination" to "coord",
                "name_destination" to "151.206840:-33.883970:EPSG:4326",
                "calcNumberOfTrips" to "6",
                "TfNSWTR" to "true",
                "excludedMeans" to "checkbox",
                "exclMOT_5" to "1",
                "exclMOT_9" to "1",
                "exclMOT_11" to "1",
            ),
            params,
        )
        assertEquals("planner-test-1", response.timetableVersion)
        assertEquals(listOf(Mode.TRAIN, Mode.METRO), response.liveModes)
        assertEquals(2, response.itineraries.size)
    }

    @Test
    fun `arrive-by and now`() = runTest {
        planner().plan(request(kind = PlanTimeKind.ARRIVE_BY, timeMs = null))
        val params = fetcher.calls.single().second.toMap()
        assertEquals("arr", params["depArrMacro"])
        assertEquals("0750", params["itdTime"])
        assertFalse("excludedMeans" in params, "never sent without an exclMOT_*")
    }

    @Test
    fun `maxItineraries trims the answer`() = runTest {
        assertEquals(1, planner().plan(request(max = 1)).itineraries.size)
    }

    // ---- validation ----

    @Test
    fun `invalid requests are 400 bad_request`() = runTest {
        val p = planner()
        val bad = 400 to "bad_request"
        assertEquals(bad, code { p.plan(request(from = LatLng(-27.47, 153.03))) }, "Brisbane")
        assertEquals(bad, code { p.plan(request(to = LatLng(-33.87, 139.9))) })
        assertEquals(bad, code { p.plan(request(to = LatLng(Double.NaN, 151.0))) })
        assertEquals(bad, code { p.plan(request(to = LatLng(-33.871900, 151.094500))) }, "about 30 m apart")
        assertEquals(bad, code { p.plan(request(max = 0)) })
        assertEquals(bad, code { p.plan(request(max = 7)) })
        assertEquals(bad, code { p.plan(request(timeMs = clock.now - 8L * 24 * 3600_000)) })
        assertEquals(bad, code { p.plan(request(timeMs = clock.now + 61L * 24 * 3600_000)) })
        assertEquals(bad, code { p.plan(request(fromPlace = "bad\nid")) })
        assertEquals(bad, code { p.places("a", null, 10) })
        assertEquals(bad, code { p.places("x".repeat(81), null, 10) })
        assertEquals(bad, code { p.places("central", null, 21) })
        assertEquals(bad, code { p.places("central", null, 0) })
        assertEquals(bad, code { p.places("central", LatLng(0.0, 0.0), 10) })
        assertTrue(fetcher.calls.isEmpty(), "nothing invalid reaches TfNSW")
        // Within limits.
        p.plan(request(timeMs = clock.now - 6L * 24 * 3600_000, max = 6))
        p.plan(request(timeMs = clock.now + 59L * 24 * 3600_000))
    }

    // ---- caching ----

    @Test
    fun `identical plans are cached for 60 s with positions rounded to 5 places and time to the minute`() = runTest {
        val p = planner()
        p.plan(request(timeMs = local("08:00")))
        p.plan(request(timeMs = local("08:00") + 59_000, from = LatLng(-33.871770004, 151.094250004), max = 2))
        assertEquals(1, fetcher.calls.size)
        assertEquals(1, p.status().cacheHits)
        p.plan(request(timeMs = local("08:01")))
        assertEquals(2, fetcher.calls.size, "another minute")
        p.plan(request(excluded = listOf(Mode.BUS)))
        assertEquals(3, fetcher.calls.size, "other modes")
        clock.advance(60_000)
        p.plan(request(timeMs = local("08:00")))
        assertEquals(4, fetcher.calls.size, "expired")
    }

    @Test
    fun `identical place searches are cached for 5 minutes`() = runTest {
        val p = planner()
        p.places("Central", null, 10)
        p.places("  central ", LatLng(-33.88, 151.2), 3)
        assertEquals(1, fetcher.calls.size)
        clock.advance(4 * 60_000)
        p.places("central", null, 10)
        assertEquals(1, fetcher.calls.size)
        clock.advance(61_000)
        p.places("central", null, 10)
        assertEquals(2, fetcher.calls.size)
    }

    // ---- places ----

    @Test
    fun `a house search that finds only the street searches the number and street again`() = runTest {
        fetcher.stopFinderFor = { text ->
            PlannerFetch.Success(PlannerFixtures.live(if (text == "100 George St") "stop_finder-100-george-st.json" else "stop_finder-100-george-st-sydney.json"))
        }
        val p = planner()
        val found = p.places("Unit 5/100 George St, Sydney", null, 6).places
        assertEquals(
            listOf("100 George St, Sydney", "100 George St"),
            fetcher.calls.map { c -> c.second.first { it.first == "name_sf" }.second },
            "the unit is dropped, then the suburb TfNSW does not file it under",
        )
        assertEquals("100 George St" to "The Rocks", found.first().name to found.first().detail)
        assertTrue(found.all { it.name.startsWith("100") }, "houses fill the list before the street")

        // Cached under the search without the unit.
        p.places("100 George St, Sydney", null, 6)
        assertEquals(2, fetcher.calls.size)
    }

    @Test
    fun `a search without a house number, or one that found a house, is sent once`() = runTest {
        fetcher.stopFinderFor = { PlannerFetch.Success(PlannerFixtures.live("stop_finder-100-george-st.json")) }
        val p = planner()
        p.places("George St", null, 6)
        p.places("100 George St, The Rocks", null, 6)
        assertEquals(2, fetcher.calls.size)
    }

    @Test
    fun `house searches are read as number, street and suburb`() {
        val q = AddressQuery.parse("Unit 5/100 George Street, The Rocks NSW 2000")
        assertEquals("100 George Street, The Rocks NSW 2000", q.text)
        assertEquals("100", q.number)
        assertEquals("100 George Street", q.numberAndStreet)
        assertEquals(listOf("the", "rocks"), q.suburbWords)
        assertTrue(q.sameStreet("100 George St"))
        assertTrue(!q.sameStreet("100 St Georges Cr") && !q.sameStreet("100 King George St"))

        assertEquals("12 St Johns Rd", AddressQuery.parse("12 St Johns Rd Glebe").numberAndStreet, "a leading St is Saint")
        assertEquals(listOf("glebe"), AddressQuery.parse("12 St Johns Rd Glebe").suburbWords)
        assertEquals("1 Macquarie St", AddressQuery.parse("Level 3, 1 Macquarie St, Sydney").numberAndStreet)
        assertEquals("12A", AddressQuery.parse("3/12A Smith St").number)
        assertNull(AddressQuery.parse("100 George St").numberAndStreet, "nothing after the street: nothing to drop")
        assertNull(AddressQuery.parse("Central Station").number)
        assertNull(AddressQuery.parse("2000").number)
        assertTrue(AddressQuery.parse("100 George").sameStreet("100 George St"), "no type typed: any type")

        assertTrue(AddressQuery.sameNumber("12-18", "12"))
        assertTrue(AddressQuery.sameNumber("100a", "100A"))
        assertTrue(!AddressQuery.sameNumber("100A", "100"))
    }

    @Test
    fun `places are mapped, unknown types dropped, stations first and nearer ones next`() = runTest {
        val p = planner()
        val all = p.places("central", null, 10).places
        assertEquals(listOf("200060", "2000338", "streetID:1500001234:0:95308100:-1:Central Ave:Manly"), all.take(3).map { it.id })
        assertEquals(5, all.size, "the 'unknown' location is dropped")
        val central = all.first()
        assertEquals(PlaceKind.STOP, central.kind)
        assertEquals("Central Station", central.name)
        assertEquals("Sydney", central.detail)
        assertEquals("200060", central.stopId)
        assertEquals(listOf(Mode.TRAIN, Mode.METRO, Mode.LIGHT_RAIL, Mode.BUS, Mode.COACH), central.modes)
        val platform = all.single { it.id == "2000338" }
        assertEquals("Central Station", platform.name)
        assertEquals("2000338", platform.stopId)
        assertEquals(PlaceKind.ADDRESS, all[2].kind)
        assertEquals("Central Ave", all[2].name)
        assertEquals("Manly", all[2].detail)
        assertEquals(PlaceKind.LOCALITY, all.single { it.name == "Central Tilba" }.kind)

        // Stations matching the search first (TfNSW's best match, then stops named "central"), then nearer places.
        val nearGosford = p.places("central", LatLng(-33.4300, 151.3400), 4).places
        assertEquals(listOf(PlaceKind.STOP, PlaceKind.STOP, PlaceKind.POI, PlaceKind.ADDRESS), nearGosford.map { it.kind }, "stations, then Gosford, then Manly")
        val params = fetcher.calls.first().second
        assertEquals(
            listOf("outputFormat" to "rapidJSON", "type_sf" to "any", "name_sf" to "central", "coordOutputFormat" to "EPSG:4326", "TfNSWSF" to "true"),
            params,
        )
    }

    // ---- quota and failures ----

    @Test
    fun `stops near a position ask coord for stops of every mode and map the live answer`() = runTest {
        val p = planner()
        val answer = p.stopsNear(central, 600)
        val (endpoint, params) = fetcher.calls.single()
        assertEquals(PlannerEndpoint.COORD, endpoint)
        assertEquals(
            listOf(
                "outputFormat" to "rapidJSON", "coord" to "151.206840:-33.883970:EPSG:4326", "coordOutputFormat" to "EPSG:4326",
                "inclFilter" to "1", "type_1" to "STOP", "radius_1" to "600",
            ),
            params,
        )
        val stops = answer.places
        assertEquals(16, stops.size)
        assertTrue(stops.all { it.kind == PlaceKind.STOP })
        // Nearest first; the name comes from STOP_NAME_WITH_PLACE, not "undefined, undefined".
        val first = stops.first()
        assertEquals("200060", first.id)
        assertEquals("Central Station", first.name)
        assertEquals("Sydney", first.detail)
        assertEquals("200060", first.stopId) // in the timetable
        assertEquals(listOf(Mode.TRAIN, Mode.METRO, Mode.LIGHT_RAIL, Mode.BUS, Mode.COACH), first.modes)
        val bus = stops.single { it.id == "G201081" }
        assertEquals("Elizabeth St at Devonshire St", bus.name)
        assertEquals("Surry Hills", bus.detail)
        assertEquals(null, bus.stopId) // buses are not in the rail timetable
        assertEquals(listOf(Mode.BUS), bus.modes)
        assertEquals(listOf(Mode.LIGHT_RAIL), stops.single { it.name == "Capitol Square Light Rail" }.modes)
    }

    @Test
    fun `stops near a position are cached by position to about 11 m`() = runTest {
        val p = planner()
        p.stopsNear(central, 600)
        p.stopsNear(LatLng(central.lat + 0.00001, central.lon - 0.00001), 600)
        assertEquals(1, fetcher.calls.size)
        p.stopsNear(central, 1500) // another radius is another question
        assertEquals(2, fetcher.calls.size)
        clock.advance(61 * 60_000L)
        p.stopsNear(central, 600)
        assertEquals(3, fetcher.calls.size)
    }

    @Test
    fun `stops near a position outside NSW or with a bad radius are 400`() = runTest {
        val p = planner()
        assertEquals(400 to "bad_request", code { p.stopsNear(LatLng(51.5, -0.12), 500) })
        assertEquals(400 to "bad_request", code { p.stopsNear(central, 0) })
        assertEquals(400 to "bad_request", code { p.stopsNear(central, 2_001) })
        assertTrue(fetcher.calls.isEmpty())
    }

    @Test
    fun `calls draw on the shared quota and stop when it is used up`() = runTest {
        val shared = quota(budget = 2)
        assertTrue(shared.acquire(), "a live feed call earlier today")
        val p = planner(q = shared)
        p.plan(request())
        assertEquals(2, shared.callsToday)
        assertEquals(503 to "planner_unavailable", code { p.places("central", null, 10) })
        assertEquals(1, fetcher.calls.size)
        // Cached answers are still served.
        assertEquals(2, p.plan(request()).itineraries.size)
    }

    @Test
    fun `replay calls do not use the quota`() = runTest {
        val shared = quota(budget = 1)
        val p = planner(f = ReplayPlannerFetcher(PlannerFixtures.replay), q = shared)
        p.places("central", null, 10)
        p.places("chatswood", null, 10)
        assertEquals(0, shared.callsToday)
    }

    @Test
    fun `throttling backs off with 503 and does not call again meanwhile`() = runTest {
        val p = planner()
        fetcher.trip = PlannerFetch.HttpError(429, "Rate limit quota violation")
        assertEquals(503 to "planner_unavailable", code { p.plan(request()) })
        assertEquals(503 to "planner_unavailable", code { p.plan(request(timeMs = local("08:05"))) })
        assertEquals(1, fetcher.calls.size, "backing off")
        assertTrue(p.status().backoffUntilMs!! > clock.now)
        assertEquals(429, p.status().lastHttpStatus)
        clock.advance(6 * 60_000)
        fetcher.trip = PlannerFetch.Success(PlannerFixtures.tfnsw("trip_direct_train.json"))
        assertEquals(2, p.plan(request()).itineraries.size)
        assertEquals(null, p.status().backoffUntilMs)
    }

    @Test
    fun `unusable answers are 502 planner_upstream`() = runTest {
        val p = planner()
        for ((i, answer) in listOf(
            PlannerFetch.HttpError(500, "oops"),
            PlannerFetch.NetworkError("timeout", timeout = true),
            PlannerFetch.Success("<html>not json</html>"),
            PlannerFetch.Success("""{"error":{"message":"invalid parameter"}}"""),
        ).withIndex()) {
            fetcher.trip = answer
            assertEquals(502 to "planner_upstream", code { p.plan(request(timeMs = local("08:00") + i * 60_000L)) }, answer.toString())
        }
        assertEquals(null, p.status().backoffUntilMs, "no backoff for non-throttle errors")
        assertEquals(4, p.status().upstreamFailures)
    }

    @Test
    fun `no key and no replay data is 503 and never calls`() = runTest {
        val off = FakePlannerFetcher(configured = false)
        val p = planner(f = off)
        assertFalse(p.available)
        assertEquals(503 to "planner_unavailable", code { p.plan(request()) })
        assertEquals(503 to "planner_unavailable", code { p.places("central", null, 10) })
        assertTrue(off.calls.isEmpty())
        assertEquals("off", p.status().source)
        assertFalse(planner(f = HttpPlannerFetcher("https://api.transport.nsw.gov.au", null)).available)
    }

    @Test
    fun `status counts requests and matched rides`() = runTest {
        val p = planner()
        fetcher.trip = PlannerFetch.Success(PlannerFixtures.tfnsw("trip_train_bus_walks.json"))
        p.plan(request())
        p.plan(request())
        val status = p.status()
        assertEquals(2, status.planRequests)
        assertEquals(1, status.cacheHits)
        assertEquals(1, status.upstreamCalls)
        assertEquals(1, status.matchedRides)
        assertEquals(1, status.unmatchedRides)
        assertEquals("tfnsw", status.source)
    }

    // ---- replay ----

    @Test
    fun `replay moves the recorded journeys to the requested week and filters searches`() = runTest {
        val p = planner(f = ReplayPlannerFetcher(PlannerFixtures.replay), q = null)
        // A week later; the mini-rail ids are not in this test timetable, so nothing is matched.
        val nextWeek = local("06:40", "20261118")
        clock.now = nextWeek
        val plan = p.plan(request(timeMs = nextWeek))
        assertEquals(local("06:56", "20261118"), plan.itineraries.first().legs.first().from.scheduledMs)
        assertEquals(2, plan.itineraries.first().rides.size)

        val found = p.places("north sydney", null, 10).places
        assertEquals(listOf("North Sydney Station"), found.map { it.name })
        assertTrue(p.places("zzz", null, 10).places.isEmpty())
    }

    private val app.transittracker.model.Itinerary.rides get() = legs.filter { it.kind == app.transittracker.model.LegKind.RIDE }
}
