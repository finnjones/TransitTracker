@file:OptIn(ExperimentalSerializationApi::class)

package app.transittracker.server.app

import app.transittracker.model.ConfigResponse
import app.transittracker.model.LatLng
import app.transittracker.model.MediaTypes
import app.transittracker.model.Mode
import app.transittracker.model.PlaceKind
import app.transittracker.model.PlacesResponse
import app.transittracker.model.PlanEndpoint
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanResponse
import app.transittracker.model.ServiceTime
import app.transittracker.server.live.DailyCallBudget
import app.transittracker.server.live.RateLimiter
import app.transittracker.server.live.TfnswQuota
import app.transittracker.server.pipeline.PipelineOptions
import app.transittracker.server.planner.PlannerEndpoint
import app.transittracker.server.planner.PlannerFetch
import app.transittracker.server.planner.PlannerFetcher
import app.transittracker.server.planner.TfnswPlanner
import app.transittracker.server.spi.ServerConfig
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import org.slf4j.LoggerFactory

/** Answers Trip Planner calls with the hand-made fixtures in testdata/planner/tfnsw; counts calls. */
class FixturePlannerFetcher(
    override val isRemote: Boolean = true,
    var trip: String = "trip_direct_train.json",
) : PlannerFetcher {
    val calls = AtomicInteger()
    var throttled = false
    override val configured: Boolean get() = true

    override suspend fun fetch(endpoint: PlannerEndpoint, params: List<Pair<String, String>>): PlannerFetch {
        calls.incrementAndGet()
        if (throttled) return PlannerFetch.HttpError(429, "quota")
        val file = when (endpoint) {
            PlannerEndpoint.TRIP -> "tfnsw/$trip"
            PlannerEndpoint.STOP_FINDER -> "tfnsw/stop_finder_central.json"
            PlannerEndpoint.COORD -> "live/coord-central.json"
        }
        return PlannerFetch.Success(Files.readString(Fixtures.testdata.resolve("planner").resolve(file)))
    }
}

/** `/v1/places` and `/v1/plan`: auth, validation, per-device limits, caching, 503 and the shared budget. */
class PlannerApiTest {
    private val dir: Path = createTempDirectory("tt-planner-api")
    // Wednesday 11 Nov 2026 07:50 Sydney.
    private val clock = MutableClock(ServiceTime.toEpochMillis("20261111", 7 * 3600 + 50 * 60))
    private val db = Database.open(dir.resolve("tt.db"))
    private val devices = SqliteDeviceStore(db, clock)
    private val config = ServerConfig(dataDir = dir, adminToken = "admin")
    private val fetcher = FixturePlannerFetcher()
    private val quota = TfnswQuota(DailyCallBudget(1000, dir.resolve("calls.txt"), clock), RateLimiter(1000), 1000)
    private val planner = TfnswPlanner(FakeTimetableRepository().current, fetcher, quota, clock, listOf(Mode.TRAIN))

    private fun deps(p: app.transittracker.server.spi.Planner = planner) = ApiDependencies(
        config, devices, SqliteNotificationLog(db), FakeTimetableRepository(), FakeLiveData(clock), FakeWatchService(), clock,
        planner = p,
    )

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun api(d: ApiDependencies = deps(), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { transitTrackerApi(d) }
        block()
    }

    private fun token(): String = devices.join(devices.createInvite().code, "android", "1.0").deviceToken

    private val request = PlanRequest(
        from = PlanEndpoint(LatLng(-33.871770, 151.094250)),
        to = PlanEndpoint(LatLng(-33.883970, 151.206840)),
        timeMs = ServiceTime.toEpochMillis("20261111", 8 * 3600),
    )

    private suspend fun ApplicationTestBuilder.plan(token: String?, body: String = Codecs.json.encodeToString(PlanRequest.serializer(), request)) =
        client.post("/v1/plan") {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.problemCode(): String {
        assertEquals(MediaTypes.PROBLEM_JSON, contentType()?.withoutParameters().toString())
        val body = Codecs.json.parseToJsonElement(bodyAsText()).jsonObject
        assertEquals(status.value, body["status"]!!.jsonPrimitive.int)
        return body["code"]!!.jsonPrimitive.content
    }

    @Test
    fun `both endpoints need a device token`() = api {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/places?q=central").status)
        assertEquals(HttpStatusCode.Unauthorized, plan(null).status)
        assertEquals(HttpStatusCode.Unauthorized, plan("not-a-token").status)
        assertEquals(0, fetcher.calls.get())
    }

    @Test
    fun `places answers json and protobuf`() = api {
        val token = token()
        val json = client.get("/v1/places?q=central&lat=-33.88&lon=151.2&limit=3") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, json.status, json.bodyAsText())
        assertEquals("no-store", json.headers[HttpHeaders.CacheControl])
        val places = Codecs.json.decodeFromString(PlacesResponse.serializer(), json.bodyAsText())
        assertEquals(clock.now, places.serverTimeMs)
        assertEquals(3, places.places.size)
        assertEquals("Central Station", places.places.first().name)
        assertEquals(PlaceKind.STOP, places.places.first().kind)

        val proto = client.get("/v1/places?q=central") { bearerAuth(token); header(HttpHeaders.Accept, MediaTypes.PROTOBUF) }
        val decoded = ProtoBuf.decodeFromByteArray(PlacesResponse.serializer(), proto.bodyAsBytes())
        assertEquals(5, decoded.places.size)
        assertEquals(1, fetcher.calls.get(), "the second search came from the cache")
    }

    @Test
    fun `places validates its parameters`() = api {
        val token = token()
        for (query in listOf("", "?q=a", "?q=central&lat=-33.8", "?q=central&lat=x&lon=151", "?q=central&limit=50", "?q=central&limit=x", "?q=central&lat=51.5&lon=0.1")) {
            val response = client.get("/v1/places$query") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, response.status, query)
            assertEquals("bad_request", response.problemCode())
        }
        assertEquals(0, fetcher.calls.get())
    }

    @Test
    fun `stops near answers the stops of every mode, nearest first`() = api {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/stops/near?lat=-33.884&lon=151.2068").status)
        val token = token()
        val response = client.get("/v1/stops/near?lat=-33.884&lon=151.2068&radius=600") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val stops = Codecs.json.decodeFromString(PlacesResponse.serializer(), response.bodyAsText()).places
        assertEquals("Central Station", stops.first().name)
        assertTrue(stops.any { Mode.BUS in it.modes })
        assertTrue(stops.any { it.modes == listOf(Mode.LIGHT_RAIL) })
        for (query in listOf("", "?lat=-33.884", "?lat=x&lon=151.2", "?lat=-33.884&lon=151.2&radius=x", "?lat=-33.884&lon=151.2&radius=5000", "?lat=51.5&lon=0.1")) {
            val bad = client.get("/v1/stops/near$query") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, bad.status, query)
            assertEquals("bad_request", bad.problemCode())
        }
        assertEquals(1, fetcher.calls.get())
    }

    @Test
    fun `plan answers json and protobuf, best first`() = api {
        val token = token()
        val response = plan(token)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = Codecs.json.decodeFromString(PlanResponse.serializer(), response.bodyAsText())
        assertEquals(2, body.itineraries.size)
        assertEquals(listOf(Mode.TRAIN), body.liveModes)
        assertEquals("T9", body.itineraries.first().legs.single().routeName)

        val proto = client.post("/v1/plan") {
            bearerAuth(token)
            contentType(ContentType.parse(MediaTypes.PROTOBUF))
            header(HttpHeaders.Accept, MediaTypes.PROTOBUF)
            setBody(ProtoBuf.encodeToByteArray(PlanRequest.serializer(), request.copy(maxItineraries = 1)))
        }
        assertEquals(HttpStatusCode.OK, proto.status)
        assertEquals(body.itineraries.take(1), ProtoBuf.decodeFromByteArray(PlanResponse.serializer(), proto.bodyAsBytes()).itineraries)
        assertEquals(1, fetcher.calls.get(), "identical plans are cached")
    }

    @Test
    fun `plan validates its body`() = api {
        val token = token()
        assertEquals("bad_request", plan(token, """{"nope":""").problemCode())
        val outside = request.copy(to = PlanEndpoint(LatLng(-27.47, 153.03)))
        assertEquals("bad_request", plan(token, Codecs.json.encodeToString(PlanRequest.serializer(), outside)).problemCode())
        val tooClose = request.copy(to = PlanEndpoint(LatLng(-33.871800, 151.094300)))
        assertEquals("bad_request", plan(token, Codecs.json.encodeToString(PlanRequest.serializer(), tooClose)).problemCode())
        val tooMany = request.copy(maxItineraries = 7)
        assertEquals("bad_request", plan(token, Codecs.json.encodeToString(PlanRequest.serializer(), tooMany)).problemCode())
        assertEquals(0, fetcher.calls.get())
    }

    @Test
    fun `plan is limited per device with a small burst`() = api {
        val first = token()
        repeat(10) { assertEquals(HttpStatusCode.OK, plan(first).status) }
        plan(first).let {
            assertEquals(HttpStatusCode.TooManyRequests, it.status)
            assertEquals("rate_limited", it.problemCode())
            assertNotNull(it.headers[HttpHeaders.RetryAfter])
        }
        assertEquals(HttpStatusCode.OK, plan(token()).status, "another device")
        clock.advance(120_000) // one more every 2 minutes (30 an hour)
        assertEquals(HttpStatusCode.OK, plan(first).status)
        assertEquals(HttpStatusCode.TooManyRequests, plan(first).status)
    }

    @Test
    fun `plan rate limit logs a stable pseudonymous device tag`() = api {
        val firstBearer = token()
        val secondBearer = token()
        val logger = LoggerFactory.getLogger("app.transittracker.server.app.RateLimit") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { context = logger.loggerContext; start() }
        logger.addAppender(appender)
        try {
            repeat(11) { plan(firstBearer) }
            repeat(11) { plan(secondBearer) }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        val messages = appender.list.map { it.formattedMessage }
        assertEquals(22, messages.size)
        assertEquals(2, messages.count { it.startsWith("Rate limited") })
        assertEquals(20, messages.count { it.startsWith("Rate allowed") })
        assertTrue(messages.all { it.contains("POST /v1/plan") })
        val tags = messages.map { Regex("subject=([0-9a-f]{12})").find(it)?.groupValues?.get(1) }
        assertTrue(tags.all { it != null }, messages.toString())
        assertEquals(2, tags.toSet().size, messages.toString())
        assertTrue(messages.none { it.contains(firstBearer) || it.contains(secondBearer) })
    }

    @Test
    fun `places is limited per device`() = api {
        val token = token()
        repeat(20) { assertEquals(HttpStatusCode.OK, client.get("/v1/places?q=central") { bearerAuth(token) }.status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/v1/places?q=central") { bearerAuth(token) }.status)
        clock.advance(1_000) // 60 a minute
        assertEquals(HttpStatusCode.OK, client.get("/v1/places?q=central") { bearerAuth(token) }.status)
    }

    @Test
    fun `without a planner both endpoints are 503 and config says so`() = api(deps(NoPlanner)) {
        val token = token()
        assertEquals("planner_unavailable", plan(token).problemCode())
        client.get("/v1/places?q=central") { bearerAuth(token) }.let {
            assertEquals(HttpStatusCode.ServiceUnavailable, it.status)
            assertEquals("planner_unavailable", it.problemCode())
        }
        val config = Codecs.json.decodeFromString(ConfigResponse.serializer(), client.get("/v1/config") { bearerAuth(token) }.bodyAsText())
        assertEquals(false, config.features["planner"])
        assertEquals(MAX_WATCHES_PER_DEVICE, config.maxWatches)
    }

    @Test
    fun `config advertises the planner and throttling is 503`() = api {
        val token = token()
        val config = Codecs.json.decodeFromString(ConfigResponse.serializer(), client.get("/v1/config") { bearerAuth(token) }.bodyAsText())
        assertEquals(true, config.features["planner"])
        assertEquals(10, config.maxWatches)
        fetcher.throttled = true
        assertEquals("planner_unavailable", plan(token).problemCode())
    }

    @Test
    fun `planner and live feeds share one TfNSW budget, shown on status`() {
        val shared = config.copy(dataDir = dir.resolve("server"), dailyCallBudget = 2, recordIntervalMinutes = 0)
        val tripPlanner = FixturePlannerFetcher()
        TtServer(
            shared, clock, fetcher = FakeFeedFetcher(clock), push = CapturingPushSender(),
            pipelineOptions = PipelineOptions(zstdLevel = 3), plannerFetcher = tripPlanner,
        ).use { tt ->
            testApplication {
                application { transitTrackerApi(tt.api) }
                val device = tt.devices.join(tt.devices.createInvite().code, "android", "1.0").deviceToken
                assertEquals(HttpStatusCode.OK, plan(device).status)
                // The live data's status reports the planner's call: one budget for the key.
                assertEquals(1, tt.services.live.status().callsToday)
                assertTrue(tt.services.quota.acquire(), "a live feed call")
                assertEquals(2, tt.services.live.status().callsToday)
                // Budget used up: the next uncached plan is refused without calling TfNSW.
                val later = request.copy(timeMs = request.timeMs!! + 5 * 60_000)
                assertEquals("planner_unavailable", plan(device, Codecs.json.encodeToString(PlanRequest.serializer(), later)).problemCode())
                assertEquals(1, tripPlanner.calls.get())

                val status = Codecs.json.decodeFromString(
                    StatusReport.serializer(),
                    client.get("/status") { bearerAuth("admin"); header(HttpHeaders.Accept, MediaTypes.JSON) }.bodyAsText(),
                )
                assertEquals(2, status.live.callsToday)
                assertEquals(2, status.live.dailyBudget)
                assertEquals("tfnsw", status.planner.source)
                assertEquals(2, status.planner.planRequests)
                assertEquals(1, status.planner.upstreamCalls)
                assertEquals(0, status.planner.matchedRides, "no timetable loaded")
                assertEquals(2, status.planner.unmatchedRides)
                val html = client.get("/status") { bearerAuth("admin") }.bodyAsText()
                assertTrue("Journey planner" in html)
                assertFalse("151.0942" in html, "no positions on the status page")
            }
        }
    }

    @Test
    fun `tt-admin planner-capture saves the raw answer and needs a key`() {
        val out = java.io.ByteArrayOutputStream()
        val err = java.io.ByteArrayOutputStream()
        val file = dir.resolve("captures/trip.json")
        val code = plannerCapture(
            listOf("plan", "-33.87177,151.09425", "-33.88397,151.20684", "--at", "2026-11-11T08:00", file.toString()),
            config, java.io.PrintStream(out, true), java.io.PrintStream(err, true), clock, fetcher,
        )
        assertEquals(0, code, err.toString())
        assertEquals(Files.readString(Fixtures.testdata.resolve("planner/tfnsw/trip_direct_train.json")), Files.readString(file))
        assertEquals(1, fetcher.calls.get())
        assertEquals(1, TfnswQuota.fromConfig(config, clock).callsToday, "counted in today's budget")

        val noKey = plannerCapture(
            listOf("places", "central", dir.resolve("p.json").toString()),
            config, java.io.PrintStream(out, true), java.io.PrintStream(err, true), clock,
        )
        assertEquals(1, noKey)
        assertTrue("TFNSW_API_KEY is not set" in err.toString())
        assertEquals(2, plannerCapture(listOf("plan", "nowhere", "x", "y"), config, java.io.PrintStream(out), java.io.PrintStream(err), clock, fetcher))
    }
}
