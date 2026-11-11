package app.transittracker.core

import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.RealtimeResponse
import app.transittracker.model.Route
import app.transittracker.model.ScheduledStopTime
import app.transittracker.model.ScheduledTrip
import app.transittracker.model.ServiceTime
import app.transittracker.model.Stop
import app.transittracker.model.StopDelta
import app.transittracker.model.TripDelta
import app.transittracker.model.WallClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.protobuf.ProtoBuf

/** 2026-11-11 (a Wednesday) at local midnight, Sydney. */
const val DAY = "20261111"
const val NEXT_DAY = "20261112"

fun at(hh: Int, mm: Int, date: String = DAY, ss: Int = 0): Long = ServiceTime.toEpochMillis(date, hh * 3600 + mm * 60 + ss)

const val MIN = 60_000L

/**
 * Test network:
 *  station A (platforms A1, A2) --R1--> station B (B1, B2) --R2--> station C (C1)
 *
 * R1 trips leave A every 15 min 06:00..09:00 (A→B 20 min, calling at B then C at +40) plus an
 * after-midnight trip at 24:30. R2 trips leave B every 10 min 06:00..10:00 (B→C 15 min).
 */
object TestNet {
    val stops = listOf(
        Stop("A", "Alpha", null, LatLng(-33.80, 151.00)),
        Stop("A1", "Alpha Platform 1", "A", LatLng(-33.80, 151.00), "1"),
        Stop("A2", "Alpha Platform 2", "A", LatLng(-33.80, 151.00), "2"),
        Stop("B", "Bravo", null, LatLng(-33.85, 151.10)),
        Stop("B1", "Bravo Platform 1", "B", LatLng(-33.85, 151.10), "1"),
        Stop("B2", "Bravo Platform 2", "B", LatLng(-33.85, 151.10), "2"),
        Stop("C", "Charlie", null, LatLng(-33.90, 151.20)),
        Stop("C1", "Charlie Platform 1", "C", LatLng(-33.90, 151.20), "1"),
    )
    val routes = listOf(
        Route("R1", "T1", "Red Line", Mode.TRAIN, "FF0000"),
        Route("R2", "M1", "Metro", Mode.METRO, "00FF00"),
    )

    fun r1(id: String, startSecs: Int) = ScheduledTrip(
        tripId = id, routeId = "R1", serviceId = "S", headsign = "Charlie",
        stopTimes = listOf(
            ScheduledStopTime(1, "A1", startSecs, startSecs),
            ScheduledStopTime(2, "B1", startSecs + 20 * 60, startSecs + 21 * 60),
            ScheduledStopTime(3, "C1", startSecs + 40 * 60, startSecs + 40 * 60),
        ),
    )

    fun r2(id: String, startSecs: Int) = ScheduledTrip(
        tripId = id, routeId = "R2", serviceId = "S", headsign = "Charlie",
        stopTimes = listOf(
            ScheduledStopTime(1, "B2", startSecs, startSecs),
            ScheduledStopTime(2, "C1", startSecs + 15 * 60, startSecs + 15 * 60),
        ),
    )

    val trips: List<ScheduledTrip> =
        (0..12).map { r1("R1-%02d".fmt(it), 6 * 3600 + it * 15 * 60) } +
            r1("R1-LATE", 24 * 3600 + 30 * 60) +
            (0..24).map { r2("R2-%02d".fmt(it), 6 * 3600 + it * 10 * 60) }

    fun timetable(version: String = "v1"): InMemoryTimetable = InMemoryTimetable(stops, routes, trips, version)

    private fun String.fmt(n: Int) = replace("%02d", n.toString().padStart(2, '0'))
}

/** A clock driven by the test scheduler's virtual time, starting at [startMs]. */
fun TestScope.virtualClock(startMs: Long): WallClock = WallClock { startMs + testScheduler.currentTime }

fun delta(
    tripId: String,
    date: String = DAY,
    routeId: String = "R1",
    vararg stops: StopDelta,
    status: app.transittracker.model.TripStatus = app.transittracker.model.TripStatus.SCHEDULED,
    stale: Boolean = false,
) = TripDelta(tripId, date, routeId, status, stops.toList(), stale = stale)

fun snapshotOf(vararg deltas: TripDelta, receivedMs: Long, pollMs: Long = 30_000) = LiveSnapshot(
    trips = deltas.associate { TripKey(it.tripId, it.startDate) to LiveTrip(it, receivedMs) },
    pollIntervalMs = pollMs,
    lastUpdateMs = receivedMs,
)

/** One recorded request. */
data class Recorded(val timeMs: Long, val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: String?)

/**
 * A scripted tt-server on Ktor's MockEngine, running on the test scheduler so polling can be
 * checked in virtual time.
 */
class FakeServer(private val scope: TestScope, var serverTimeMs: () -> Long = { 0L }) {
    val requests = mutableListOf<Recorded>()
    private val handlers = mutableListOf<Pair<(Recorded) -> Boolean, suspend HandlerScope.(Recorded) -> HttpResponseData>>()

    /** Receiver of request handlers: responses carry `X-Server-Time`. */
    inner class HandlerScope(private val mock: MockRequestHandleScope) {
        fun respond(content: String, status: HttpStatusCode = HttpStatusCode.OK, headers: Headers = jsonHeaders()) =
            mock.respond(content, status, headers)

        fun respond(content: ByteArray, status: HttpStatusCode = HttpStatusCode.OK, headers: Headers = protoHeaders()) =
            mock.respond(content, status, headers)

        fun <T> json(serializer: KSerializer<T>, value: T, status: HttpStatusCode = HttpStatusCode.OK, extra: Map<String, String> = emptyMap()) =
            respond(ApiJson.encodeToString(serializer, value), status, jsonHeaders(extra))

        fun realtime(value: RealtimeResponse, etag: String? = null) =
            respond(ProtoBuf.encodeToByteArray(RealtimeResponse.serializer(), value), HttpStatusCode.OK, protoHeaders(etag?.let { mapOf(HttpHeaders.ETag to it) }.orEmpty()))
    }

    fun on(method: String, path: String, handler: suspend HandlerScope.(Recorded) -> HttpResponseData) {
        handlers.add(0, { r: Recorded -> r.method == method && (r.path == path || (path.endsWith("*") && r.path.startsWith(path.dropLast(1)))) } to handler)
    }

    fun requestsTo(path: String) = requests.filter { it.path == path }

    val client: HttpClient by lazy {
        val config = MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(scope.testScheduler)
            addHandler { req -> handle(req) }
        }
        HttpClient(MockEngine(config))
    }

    private suspend fun MockRequestHandleScope.handle(req: HttpRequestData): HttpResponseData {
        val body = when (val c = req.body) {
            is TextContent -> c.text
            is OutgoingContent.ByteArrayContent -> c.bytes().decodeToString()
            else -> null
        }
        val rec = Recorded(
            timeMs = scope.testScheduler.currentTime,
            method = req.method.value,
            path = req.url.encodedPath,
            query = req.url.parameters.entries().associate { it.key to it.value.joinToString(",") },
            headers = req.headers.entries().associate { it.key to it.value.joinToString(",") },
            body = body,
        )
        requests += rec
        val handler = handlers.firstOrNull { it.first(rec) }?.second
            ?: return respond("""{"code":"not_found","title":"Not found","status":404}""", HttpStatusCode.NotFound, jsonHeaders())
        return HandlerScope(this).handler(rec)
    }

    fun jsonHeaders(extra: Map<String, String> = emptyMap()) = headersOf(
        *(mapOf(HttpHeaders.ContentType to "application/json", "X-Server-Time" to serverTimeMs().toString()) + extra)
            .map { it.key to listOf(it.value) }.toTypedArray(),
    )

    fun protoHeaders(extra: Map<String, String> = emptyMap()) = headersOf(
        *(mapOf(HttpHeaders.ContentType to "application/x-protobuf", "X-Server-Time" to serverTimeMs().toString()) + extra)
            .map { it.key to listOf(it.value) }.toTypedArray(),
    )
}
