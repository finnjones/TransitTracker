package app.transittracker.server.live

import app.transittracker.model.Timetable
import app.transittracker.model.WallClock
import app.transittracker.server.spi.DemandKind
import app.transittracker.server.spi.FeedDefinition
import app.transittracker.server.spi.FeedStatus
import app.transittracker.server.spi.ServerConfig
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FetchPolicyTest {
    private val start = at(DAY, hms(7, 55))
    private val timetable = FakeTimetable()

    /** A feed with trip updates only, so call counts are easy to follow. */
    private val tuOnly = FeedDefinition("sydneytrains", app.transittracker.model.Mode.TRAIN, "/tu", null, null)

    private fun TestScope.live(
        fetcher: FeedFetcher,
        config: ServerConfig = testConfig(),
        tt: Timetable? = timetable,
        clock: WallClock = virtualClock(start),
    ) = LiveDataImpl(
        config = config,
        timetable = MutableStateFlow(tt),
        fetcher = fetcher,
        clock = clock,
        recorder = null,
        rateLimiter = virtualRateLimiter(100),
        backoff = BackoffPolicy(random = Random(7)),
    )

    private fun TestScope.okFetcher(latencyMs: Long = 0) = FakeFetcher(latencyMs = latencyMs).also { f ->
        val clock = virtualClock(start)
        f.responder = { _, kind ->
            val entities = if (kind == FeedKind.TRIP_UPDATES) {
                listOf(tuEntity(tripUpdate("T1-A", stus = listOf(stu(seq = 2, depDelay = 60)))))
            } else {
                emptyList()
            }
            FetchResult.Success(feedMessage(clock.nowMillis(), entities))
        }
    }

    private fun LiveDataImpl.tuStatus(): FeedStatus = status().feeds.single { it.kind == "tripupdates" && it.feedId == "sydneytrains" }

    @Test
    fun `concurrent requests share one fetch per feed kind`() = runTest {
        val fetcher = okFetcher(latencyMs = 500)
        val live = live(fetcher)
        val responses = List(10) { async { live.realtime(setOf("T1")) } }.awaitAll()
        assertEquals(1, fetcher.calls(TRAINS, FeedKind.TRIP_UPDATES))
        assertEquals(1, fetcher.calls(TRAINS, FeedKind.VEHICLES))
        assertEquals(1, fetcher.calls(TRAINS, FeedKind.ALERTS))
        assertEquals(0, fetcher.calls(METRO, FeedKind.TRIP_UPDATES))
        assertTrue(responses.all { r -> r.trips.map { it.tripId } == listOf("T1-A") })
    }

    @Test
    fun `minimum age depends on the demand kind`() = runTest {
        val fetcher = okFetcher()
        val live = live(fetcher)
        fun tu() = fetcher.calls(TRAINS, FeedKind.TRIP_UPDATES)
        fun alerts() = fetcher.calls(TRAINS, FeedKind.ALERTS)

        live.realtime(setOf("T1"), DemandKind.SCREEN)
        assertEquals(1, tu())
        advanceTimeBy(10_000)
        live.realtime(setOf("T1"), DemandKind.SCREEN) // 10 s < 15 s
        assertEquals(1, tu())
        advanceTimeBy(6_000)
        live.realtime(setOf("T1"), DemandKind.TRACKING) // 16 s >= 15 s
        assertEquals(2, tu())
        assertEquals(1, alerts()) // alerts: 60 s
        advanceTimeBy(20_000)
        live.realtime(setOf("T1"), DemandKind.WATCH) // 20 s < 30 s
        assertEquals(2, tu())
        advanceTimeBy(11_000)
        live.realtime(setOf("T1"), DemandKind.WATCH) // 31 s >= 30 s
        assertEquals(3, tu())
        assertEquals(1, alerts())
        advanceTimeBy(30_000)
        live.alerts(null) // 77 s since the alerts fetch
        assertEquals(2, alerts())
        // WATCH demand never fetches vehicles.
        assertEquals(2, fetcher.calls(TRAINS, FeedKind.VEHICLES))
    }

    @Test
    fun `throttling responses back off exponentially with jitter`() = runTest {
        val statuses = ArrayDeque(listOf(403, 503, 429, 401))
        val fetcher = FakeFetcher { _, _ -> FetchResult.HttpError(statuses.removeFirstOrNull() ?: 403) }
        val live = live(fetcher, testConfig(feeds = listOf(tuOnly)))
        val clock = virtualClock(start)
        val expectedBase = listOf(30_000L, 60_000L, 120_000L, 240_000L)

        for ((i, base) in expectedBase.withIndex()) {
            live.realtime(setOf("T1"))
            assertEquals(i + 1, fetcher.totalCalls(), "throttled responses are not retried immediately")
            val status = live.tuStatus()
            val wait = status.backoffUntilMs!! - clock.nowMillis()
            assertTrue(wait in base..minOf(300_000, base * 12 / 10), "backoff $wait for error ${i + 1}")
            assertEquals(i + 1, status.consecutiveErrors)
            // Within the backoff: no call, cached (here: no) data served.
            advanceTimeBy(wait - 1_000)
            live.realtime(setOf("T1"))
            assertEquals(i + 1, fetcher.totalCalls())
            advanceTimeBy(1_000)
        }
        val health = live.realtime(setOf("T1")).feeds.single()
        assertFalse(health.ok)
        assertTrue(health.stale)
    }

    @Test
    fun `backoff is capped at five minutes`() {
        val policy = BackoffPolicy(random = Random(1))
        assertTrue(policy.delayMs(1) in 30_000L..36_000L)
        assertEquals(300_000, policy.delayMs(5))
        assertEquals(300_000, policy.delayMs(50))
    }

    @Test
    fun `server errors and timeouts retry once after 5 s then back off`() = runTest {
        val results = ArrayDeque<FetchResult>(
            listOf(
                FetchResult.HttpError(500), FetchResult.HttpError(502),
                FetchResult.NetworkError("timeout", timeout = true),
            ),
        )
        val clock = virtualClock(start)
        val fetcher = FakeFetcher { _, _ ->
            results.removeFirstOrNull() ?: FetchResult.Success(feedMessage(clock.nowMillis(), emptyList()))
        }
        val live = live(fetcher, testConfig(feeds = listOf(tuOnly)))

        live.realtime(setOf("T1"))
        assertEquals(2, fetcher.totalCalls())
        assertEquals(5_000, testScheduler.currentTime, "retry waits 5 s")
        val status = live.tuStatus()
        assertEquals(502, status.lastHttpStatus)
        assertTrue(status.backoffUntilMs!! - clock.nowMillis() in 30_000L..36_000L)

        advanceTimeBy(40_000)
        live.realtime(setOf("T1")) // timeout, then retry succeeds
        assertEquals(4, fetcher.totalCalls())
        assertEquals(0, live.tuStatus().consecutiveErrors)
        assertEquals(200, live.tuStatus().lastHttpStatus)
    }

    @Test
    fun `daily budget stops calls, survives restarts and resets at Sydney midnight`() = runTest {
        val dataDir = Files.createTempDirectory("tt-budget")
        val config = testConfig(dataDir = dataDir, feeds = listOf(tuOnly), dailyCallBudget = 2)
        val fetcher = okFetcher()
        val live = live(fetcher, config)

        live.realtime(setOf("T1"))
        advanceTimeBy(16_000)
        live.realtime(setOf("T1"))
        assertEquals(2, fetcher.totalCalls())
        advanceTimeBy(16_000)
        val exhausted = live.realtime(setOf("T1"))
        assertEquals(2, fetcher.totalCalls())
        assertTrue(exhausted.feeds.single().stale, "data served after the budget is used up is stale")
        assertEquals(listOf("T1-A"), exhausted.trips.map { it.tripId }, "cached data is still served")
        assertTrue(exhausted.trips.single().stale)
        assertEquals(2, live.status().callsToday)

        // A restart reads the persisted counter.
        val restarted = live(fetcher, config)
        assertEquals(2, restarted.status().callsToday)
        restarted.realtime(setOf("T1"))
        assertEquals(2, fetcher.totalCalls())

        // After Sydney midnight the budget is fresh again.
        val tomorrow = WallClock { at(NEXT_DAY, hms(0, 1)) + testScheduler.currentTime }
        val nextDay = live(fetcher, config, clock = tomorrow)
        assertEquals(0, nextDay.status().callsToday)
        nextDay.realtime(setOf("T1"))
        assertEquals(3, fetcher.totalCalls())
        assertEquals(1, nextDay.status().callsToday)
    }

    @Test
    fun `without an API key TfNSW is never called and responses are empty and stale`() = runTest {
        val fetcher = okFetcher()
        val live = live(fetcher, testConfig(apiKey = null))
        val response = live.realtime(setOf("T1"))
        assertEquals(0, fetcher.totalCalls())
        assertTrue(response.trips.isEmpty())
        val health = response.feeds.single()
        assertTrue(health.stale)
        assertFalse(health.ok)
        assertEquals(0, live.status().callsToday)
        assertTrue(live.vehicles(setOf("T1")).vehicles.isEmpty())
        assertTrue(live.alerts(null).response.alerts.isEmpty())
        assertEquals(0, fetcher.totalCalls())
    }

    @Test
    fun `replay fetcher works without a key and does not use the budget`() = runTest {
        val dir = Files.createTempDirectory("tt-replay")
        Files.write(dir.resolve("sydneytrains-tripupdates.pb"), feedMessage(start, listOf(tuEntity(tripUpdate("T1-A")))))
        val live = live(ReplayFetcher(dir), testConfig(apiKey = null, feeds = listOf(tuOnly)))
        assertEquals(listOf("T1-A"), live.realtime(setOf("T1")).trips.map { it.tripId })
        assertEquals(0, live.status().callsToday)
    }

    @Test
    fun `http fetcher sends the apikey header and maps statuses`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seenAuth = mutableListOf<String?>()
        server.createContext("/ok") { exchange ->
            seenAuth += exchange.requestHeaders.getFirst("Authorization")
            val body = byteArrayOf(1, 2, 3)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/busy") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val fetcher = HttpFeedFetcher(base, "secret-for-test")
            val ok = FeedDefinition("f", app.transittracker.model.Mode.TRAIN, "/ok", "/busy", null)
            runBlocking {
                val success = fetcher.fetch(ok, FeedKind.TRIP_UPDATES)
                assertIs<FetchResult.Success>(success)
                assertEquals(listOf<Byte>(1, 2, 3), success.bytes.toList())
                assertEquals(listOf<String?>("apikey secret-for-test"), seenAuth)
                assertEquals(FetchResult.HttpError(503), fetcher.fetch(ok, FeedKind.VEHICLES))
                assertIs<FetchResult.NotConfigured>(fetcher.fetch(ok, FeedKind.ALERTS))
                // No key: no request at all.
                assertIs<FetchResult.NotConfigured>(HttpFeedFetcher(base, null).fetch(ok, FeedKind.TRIP_UPDATES))
                assertEquals(1, seenAuth.size)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `the TfNSW limiter spaces every call, with no burst`() = runTest {
        val limiter = TfnswQuota.rateLimiter(4, nanoTime = { testScheduler.currentTime * 1_000_000 })
        val times = (1..5).map { limiter.acquire(); testScheduler.currentTime }
        assertEquals(listOf(0L, 250L, 500L, 750L, 1000L), times)
    }

    @Test
    fun `rate limiter allows a burst then spaces calls`() = runTest {
        val limiter = virtualRateLimiter(4)
        repeat(4) { limiter.acquire() }
        assertEquals(0, testScheduler.currentTime)
        limiter.acquire()
        assertEquals(250, testScheduler.currentTime)
        limiter.acquire()
        assertEquals(500, testScheduler.currentTime)
    }
}
