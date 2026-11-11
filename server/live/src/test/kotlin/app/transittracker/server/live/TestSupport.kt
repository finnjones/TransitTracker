package app.transittracker.server.live

import app.transittracker.model.DepartureQuery
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.Route
import app.transittracker.model.RouteId
import app.transittracker.model.ScheduledDeparture
import app.transittracker.model.ScheduledStopTime
import app.transittracker.model.ScheduledTrip
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceTime
import app.transittracker.model.ShapeId
import app.transittracker.model.Stop
import app.transittracker.model.StopId
import app.transittracker.model.Timetable
import app.transittracker.model.Transfer
import app.transittracker.model.TripId
import app.transittracker.model.WallClock
import app.transittracker.server.spi.FeedDefinition
import app.transittracker.server.spi.ServerConfig
import com.google.transit.realtime.FeedEntity
import com.google.transit.realtime.FeedHeader
import com.google.transit.realtime.FeedMessage
import com.google.transit.realtime.TripDescriptor
import com.google.transit.realtime.TripUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Service date used by most tests: Wednesday 11 Nov 2026 (AEDT, UTC+11). */
const val DAY: ServiceDate = "20261111"
const val PREV_DAY: ServiceDate = "20261110"
const val NEXT_DAY: ServiceDate = "20261112"

fun hms(h: Int, m: Int, s: Int = 0): Int = h * 3600 + m * 60 + s

/** Epoch ms of GTFS time [secs] on [date]. */
fun at(date: ServiceDate, secs: Int): Long = ServiceTime.toEpochMillis(date, secs)

/** Small in-memory timetable: T1 (train) and M1 (metro) with a few trips. */
class FakeTimetable(override val version: String = "test-1") : Timetable {
    val stops = mutableMapOf<StopId, Stop>()
    val routes = mutableMapOf<RouteId, Route>()
    val trips = mutableMapOf<TripId, ScheduledTrip>()
    val runs = mutableMapOf<TripId, Set<ServiceDate>>()

    init {
        station("CEN", "Central", "CEN1", "CEN2")
        station("RED", "Redfern", "RED1", "RED2")
        station("STR", "Strathfield", "STR1", "STR2")
        station("HBY", "Hornsby", "HBY1", "HBY2")
        station("TAL", "Tallawong", "TAL1")
        station("CHA", "Chatswood", "CHA1")
        routes["T1"] = Route("T1", "T1", "North Shore & Western Line", Mode.TRAIN)
        routes["M1"] = Route("M1", "M1", "Metro North West & Bankstown Line", Mode.METRO)

        trip(
            "T1-A", "T1", "Hornsby", setOf(PREV_DAY, DAY, NEXT_DAY),
            st(1, "CEN1", hms(8, 0)), st(2, "RED1", hms(8, 10), hms(8, 11)), st(3, "STR1", hms(8, 20)), st(4, "HBY1", hms(8, 30)),
        )
        trip(
            "T1-B", "T1", "Hornsby", setOf(DAY),
            st(1, "CEN2", hms(9, 0)), st(2, "RED2", hms(9, 10)), st(3, "STR2", hms(9, 20)), st(4, "HBY2", hms(9, 30)),
        )
        // After-midnight trip of service day DAY: runs 00:30-00:50 on the next calendar day.
        trip(
            "T1-LATE", "T1", "Strathfield", setOf(DAY),
            st(1, "CEN1", hms(24, 30)), st(2, "RED1", hms(24, 40)), st(3, "STR1", hms(24, 50)),
        )
        trip(
            "M1-A", "M1", "Tallawong", setOf(DAY),
            st(1, "CHA1", hms(8, 0)), st(2, "TAL1", hms(8, 40)),
        )
    }

    private fun station(id: String, name: String, vararg platforms: String) {
        stops[id] = Stop(id, name, position = LatLng(-33.88, 151.2))
        for (p in platforms) stops[p] = Stop(p, "$name Platform ${p.last()}", parentId = id, position = LatLng(-33.88, 151.2))
    }

    private fun st(seq: Int, stop: String, arr: Int, dep: Int = arr) = ScheduledStopTime(seq, stop, arr, dep)

    private fun trip(id: String, route: String, headsign: String, dates: Set<ServiceDate>, vararg stopTimes: ScheduledStopTime) {
        trips[id] = ScheduledTrip(id, route, "svc-$id", headsign, stopTimes = stopTimes.toList())
        runs[id] = dates
    }

    override fun stop(id: StopId): Stop? = stops[id]
    override fun stopsAtStation(stationId: StopId): List<Stop> =
        listOfNotNull(stops[stationId]) + stops.values.filter { it.parentId == stationId }
    override fun searchStops(query: String, limit: Int): List<Stop> = emptyList()
    override fun stopsNear(position: LatLng, radiusMeters: Double, limit: Int): List<Stop> = emptyList()
    override fun route(id: RouteId): Route? = routes[id]
    override fun routes(): List<Route> = routes.values.toList()
    override fun routesAtStop(stopId: StopId): List<Route> = emptyList()
    override fun trip(tripId: TripId): ScheduledTrip? = trips[tripId]
    override fun runsOn(tripId: TripId, serviceDate: ServiceDate): Boolean = serviceDate in runs[tripId].orEmpty()
    override fun departures(query: DepartureQuery): List<ScheduledDeparture> = emptyList()
    override fun shape(shapeId: ShapeId): List<LatLng>? = null
    override fun transfers(fromStopId: StopId): List<Transfer> = emptyList()
    override fun close() = Unit
}

val TRAINS = FeedDefinition(
    id = "sydneytrains",
    mode = Mode.TRAIN,
    tripUpdatesPath = "/v2/gtfs/realtime/sydneytrains",
    vehiclePositionsPath = "/v2/gtfs/vehiclepos/sydneytrains",
    alertsPath = "/v2/gtfs/alerts/sydneytrains",
)
val METRO = FeedDefinition(
    id = "metro",
    mode = Mode.METRO,
    tripUpdatesPath = "/v2/gtfs/realtime/metro",
    vehiclePositionsPath = "/v2/gtfs/vehiclepos/metro",
    alertsPath = "/v2/gtfs/alerts/metro",
)

fun testConfig(
    dataDir: Path = Files.createTempDirectory("tt-live-test"),
    apiKey: String? = "test-key",
    feeds: List<FeedDefinition> = listOf(TRAINS, METRO),
    dailyCallBudget: Int = 50_000,
) = ServerConfig(
    dataDir = dataDir,
    tfnswApiKey = apiKey,
    feeds = feeds,
    dailyCallBudget = dailyCallBudget,
    pollIntervalS = 30,
    trackerPollIntervalS = 15,
)

// ---- GTFS-R builders ----

fun feedMessage(timeMs: Long, entities: List<FeedEntity>): ByteArray =
    FeedMessage(header_ = FeedHeader(gtfs_realtime_version = "2.0", timestamp = timeMs / 1000), entity = entities).encode()

fun tripUpdate(
    tripId: String?,
    startDate: String? = DAY,
    relationship: TripDescriptor.ScheduleRelationship? = null,
    stus: List<TripUpdate.StopTimeUpdate> = emptyList(),
    timestampMs: Long? = null,
    routeId: String? = null,
    vehicleId: String? = null,
    properties: TripUpdate.TripProperties? = null,
): TripUpdate = TripUpdate(
    trip = TripDescriptor(trip_id = tripId, start_date = startDate, schedule_relationship = relationship, route_id = routeId),
    stop_time_update = stus,
    timestamp = timestampMs?.let { it / 1000 },
    vehicle = vehicleId?.let { com.google.transit.realtime.VehicleDescriptor(id = it) },
    trip_properties = properties,
)

fun tuEntity(update: TripUpdate, id: String = update.trip.trip_id ?: "e") = FeedEntity(id = id, trip_update = update)

fun stu(
    seq: Int? = null,
    stopId: String? = null,
    arrDelay: Int? = null,
    depDelay: Int? = null,
    arrTimeMs: Long? = null,
    depTimeMs: Long? = null,
    relationship: TripUpdate.StopTimeUpdate.ScheduleRelationship? = null,
): TripUpdate.StopTimeUpdate = TripUpdate.StopTimeUpdate(
    stop_sequence = seq,
    stop_id = stopId,
    arrival = if (arrDelay != null || arrTimeMs != null) TripUpdate.StopTimeEvent(delay = arrDelay, time = arrTimeMs?.let { it / 1000 }) else null,
    departure = if (depDelay != null || depTimeMs != null) TripUpdate.StopTimeEvent(delay = depDelay, time = depTimeMs?.let { it / 1000 }) else null,
    schedule_relationship = relationship,
)

// ---- fakes ----

/** Scriptable fetcher: answers from [responder] and counts calls per feed + kind. */
class FakeFetcher(
    override val isRemote: Boolean = true,
    private val latencyMs: Long = 0,
    var responder: (FeedDefinition, FeedKind) -> FetchResult = { _, _ -> FetchResult.HttpError(404) },
) : FeedFetcher {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun calls(feed: FeedDefinition, kind: FeedKind): Int = counts["${feed.id}-${kind.id}"]?.get() ?: 0
    fun totalCalls(): Int = counts.values.sumOf { it.get() }

    override suspend fun fetch(feed: FeedDefinition, kind: FeedKind): FetchResult {
        counts.getOrPut("${feed.id}-${kind.id}") { AtomicInteger() }.incrementAndGet()
        if (latencyMs > 0) delay(latencyMs)
        return responder(feed, kind)
    }
}

/** Wall clock that follows the test's virtual time, starting at [startMs]. */
fun TestScope.virtualClock(startMs: Long): WallClock = WallClock { startMs + testScheduler.currentTime }

fun TestScope.virtualRateLimiter(perSecond: Int = 4): RateLimiter =
    RateLimiter(perSecond, nanoTime = { testScheduler.currentTime * 1_000_000 })
