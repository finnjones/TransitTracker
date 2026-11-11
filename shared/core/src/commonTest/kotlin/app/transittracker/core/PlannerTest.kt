package app.transittracker.core

import app.transittracker.model.Itinerary
import app.transittracker.model.ItineraryLeg
import app.transittracker.model.LatLng
import app.transittracker.model.LegKind
import app.transittracker.model.LegPoint
import app.transittracker.model.Mode
import app.transittracker.model.Place
import app.transittracker.model.PlaceKind
import app.transittracker.model.PlacesResponse
import app.transittracker.model.PlacesResult
import app.transittracker.model.PlanEndpoint
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanResponse
import app.transittracker.model.PlanResult
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [RemoteJourneyPlanner] and the planner calls of [ApiClient], against a scripted server. */
class PlannerTest {
    private val strathfield = Place("sf-1", PlaceKind.STOP, "Strathfield Station", "Strathfield", LatLng(-33.8718, 151.0943), stopId = "213510", modes = listOf(Mode.TRAIN))
    private val albert = Place("addr-1", PlaceKind.ADDRESS, "10 Albert Rd", "Strathfield", LatLng(-33.8731, 151.0935))

    private val request = PlanRequest(
        from = PlanEndpoint(LatLng(-33.8170, 151.0047), placeId = "sf-1"),
        to = PlanEndpoint(LatLng(-33.8840, 151.2062)),
        timeMs = at(8, 0),
        maxItineraries = 9,
    )

    private val response = PlanResponse(
        serverTimeMs = at(7, 59),
        itineraries = listOf(
            Itinerary(
                id = "abc123",
                legs = listOf(
                    ItineraryLeg(LegKind.WALK, LegPoint("Start", LatLng(-33.8170, 151.0047), scheduledMs = at(8, 0)), LegPoint("Parramatta", LatLng(-33.8172, 151.0050), stopId = "P2", platform = "2", scheduledMs = at(8, 5)), durationS = 300, distanceM = 350),
                    ItineraryLeg(
                        LegKind.RIDE,
                        LegPoint("Parramatta", LatLng(-33.8172, 151.0050), stopId = "P2", platform = "2", scheduledMs = at(8, 5), expectedMs = at(8, 7)),
                        LegPoint("Central", LatLng(-33.8840, 151.2062), stopId = "C2", platform = "16", scheduledMs = at(8, 30)),
                        durationS = 1500, mode = Mode.TRAIN, routeName = "T1", headsign = "Berowra", routeColor = "F99D1C",
                        stopCount = 4, realtime = true, routeId = "T1", tripId = "T1E-0805", serviceDate = DAY,
                    ),
                ),
            ),
        ),
        timetableVersion = "v1",
        liveModes = listOf(Mode.TRAIN, Mode.METRO),
    )

    private suspend fun TestScope.joinedHarness(): Harness = Harness(this).also { it.join("tok-p") }

    private fun problem(code: String, status: Int) = """{"code":"$code","title":"Problem","status":$status}"""

    // ---- Places ----

    @Test
    fun placesHappyPath() = runTest {
        val h = joinedHarness()
        h.server.on("GET", "/v1/places") { json(PlacesResponse.serializer(), PlacesResponse(at(8, 0), listOf(strathfield, albert))) }

        val result = h.core.planner.searchPlaces("  strath field ", near = LatLng(-33.87, 151.09), limit = 5)

        assertEquals(PlacesResult.Found(listOf(strathfield, albert)), result)
        val sent = h.server.requestsTo("/v1/places").single()
        assertEquals("strath field", sent.query["q"])
        assertEquals("-33.87", sent.query["lat"])
        assertEquals("151.09", sent.query["lon"])
        assertEquals("5", sent.query["limit"])
        assertEquals("Bearer tok-p", sent.headers["Authorization"])
        assertEquals("application/json", sent.headers["Accept"])
    }

    @Test
    fun placesWithoutPositionAndLimitsClamped() = runTest {
        val h = joinedHarness()
        h.server.on("GET", "/v1/places") { json(PlacesResponse.serializer(), PlacesResponse(at(8, 0))) }

        assertEquals(PlacesResult.Found(emptyList()), h.core.planner.searchPlaces("central", limit = 50))
        h.core.planner.searchPlaces("central", limit = 0)

        val sent = h.server.requestsTo("/v1/places")
        assertFalse("lat" in sent[0].query || "lon" in sent[0].query)
        assertEquals(listOf("20", "1"), sent.map { it.query["limit"] })
    }

    @Test
    fun shortQueriesAreNotSentAndLongOnesAreCut() = runTest {
        val h = joinedHarness()
        h.server.on("GET", "/v1/places") { json(PlacesResponse.serializer(), PlacesResponse(at(8, 0), listOf(albert))) }

        for (q in listOf("", " ", "a", "  b  ")) {
            assertEquals(PlacesResult.Found(emptyList()), h.core.planner.searchPlaces(q), "query '$q'")
        }
        assertTrue(h.server.requestsTo("/v1/places").isEmpty(), "no call for short queries")

        val long = "x".repeat(79) + " " + "y".repeat(40)
        h.core.planner.searchPlaces(long)
        assertEquals("x".repeat(79), h.server.requestsTo("/v1/places").single().query["q"], "cut to 80 and trimmed")
        assertEquals(null, RemoteJourneyPlanner.normalizePlaceQuery("z"))
        assertEquals("ab", RemoteJourneyPlanner.normalizePlaceQuery(" ab "))
    }

    @Test
    fun placesFailureMapping() = runTest {
        val h = joinedHarness()
        var answer: suspend FakeServer.HandlerScope.() -> io.ktor.client.request.HttpResponseData = { respond("") }
        h.server.on("GET", "/v1/places") { answer() }
        suspend fun search() = h.core.planner.searchPlaces("central")

        answer = { throw RuntimeException("connection refused") }
        assertEquals(PlacesResult.Offline, search())

        answer = { respond(problem("planner_unavailable", 503), HttpStatusCode.ServiceUnavailable) }
        assertEquals(PlacesResult.Unavailable, search())

        answer = { respond(problem("rate_limited", 429), HttpStatusCode.TooManyRequests) }
        assertEquals(PlacesResult.Failed(PlannerMessages.TOO_MANY), search())

        answer = { respond("", HttpStatusCode.TooManyRequests) }
        assertEquals(PlacesResult.Failed(PlannerMessages.TOO_MANY), search(), "429 without a problem body")

        answer = { respond(problem("bad_request", 400), HttpStatusCode.BadRequest) }
        assertEquals(PlacesResult.Failed(PlannerMessages.BAD_SEARCH), search())

        answer = { respond(problem("unauthorized", 401), HttpStatusCode.Unauthorized) }
        assertEquals(PlacesResult.Failed(PlannerMessages.NOT_JOINED), search())

        answer = { respond("""{"places": [""") }
        assertEquals(PlacesResult.Failed(PlannerMessages.UNREADABLE), search(), "malformed JSON")

        answer = { respond("""{"places": []}""") }
        assertEquals(PlacesResult.Failed(PlannerMessages.UNREADABLE), search(), "required field missing")
    }

    // ---- Plan ----

    @Test
    fun planHappyPath() = runTest {
        val h = joinedHarness()
        h.server.on("POST", "/v1/plan") { json(PlanResponse.serializer(), response) }

        val result = h.core.planner.plan(request)

        assertEquals(PlanResult.Found(response), result)
        val found = (result as PlanResult.Found).response.itineraries.single()
        assertTrue(found.legs[1].matched)
        val sent = h.server.requestsTo("/v1/plan").single()
        assertEquals("Bearer tok-p", sent.headers["Authorization"])
        val body = ApiJson.decodeFromString(PlanRequest.serializer(), sent.body!!)
        assertEquals(request.copy(maxItineraries = 6), body, "maxItineraries kept within 1..6")
    }

    @Test
    fun planWithNothingFound() = runTest {
        val h = joinedHarness()
        h.server.on("POST", "/v1/plan") { respond("""{"serverTimeMs": 5, "unknownField": true}""") }
        assertEquals(PlanResult.Found(PlanResponse(serverTimeMs = 5)), h.core.planner.plan(request))
    }

    @Test
    fun planFailureMapping() = runTest {
        val h = joinedHarness()
        var answer: suspend FakeServer.HandlerScope.() -> io.ktor.client.request.HttpResponseData = { respond("") }
        h.server.on("POST", "/v1/plan") { answer() }
        suspend fun plan() = h.core.planner.plan(request)

        answer = { throw RuntimeException("no route to host") }
        assertEquals(PlanResult.Offline, plan())

        answer = { respond(problem("planner_unavailable", 503), HttpStatusCode.ServiceUnavailable) }
        assertEquals(PlanResult.Unavailable, plan())

        answer = { respond("Service Unavailable", HttpStatusCode.ServiceUnavailable) }
        assertEquals(PlanResult.Failed(PlannerMessages.SERVER_ERROR), plan(), "a 503 that is not the planner's")

        answer = { respond(problem("rate_limited", 429), HttpStatusCode.TooManyRequests) }
        assertEquals(PlanResult.Failed(PlannerMessages.TOO_MANY), plan())

        answer = { respond(problem("planner_upstream", 502), HttpStatusCode.BadGateway) }
        assertEquals(PlanResult.Failed(PlannerMessages.UPSTREAM), plan())

        answer = { respond(problem("bad_request", 400), HttpStatusCode.BadRequest) }
        assertEquals(PlanResult.Failed(PlannerMessages.BAD_JOURNEY), plan())

        answer = { respond(problem("not_found", 404), HttpStatusCode.NotFound) }
        assertEquals(PlanResult.Failed(PlannerMessages.GENERIC), plan())

        answer = { respond("<html>oops</html>") }
        assertEquals(PlanResult.Failed(PlannerMessages.UNREADABLE), plan(), "malformed body")

        answer = { respond("""{"serverTimeMs": 1, "itineraries": [{"id": "x"}]}""") }
        assertEquals(PlanResult.Failed(PlannerMessages.UNREADABLE), plan(), "itinerary without legs")
    }

    @Test
    fun notJoinedStillAsksAndMapsTheRefusal() = runTest {
        val h = Harness(this)
        h.server.on("POST", "/v1/plan") { req ->
            if (req.headers["Authorization"] == null) respond(problem("unauthorized", 401), HttpStatusCode.Unauthorized) else json(PlanResponse.serializer(), response)
        }
        assertEquals(PlanResult.Failed(PlannerMessages.NOT_JOINED), h.core.planner.plan(request))
    }
}
