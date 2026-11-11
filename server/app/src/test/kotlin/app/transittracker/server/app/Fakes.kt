package app.transittracker.server.app

import app.transittracker.model.Alert
import app.transittracker.model.AlertsResponse
import app.transittracker.model.DepartureQuery
import app.transittracker.model.FeedHealth
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.PackInfo
import app.transittracker.model.RealtimeResponse
import app.transittracker.model.Route
import app.transittracker.model.RouteId
import app.transittracker.model.ScheduledDeparture
import app.transittracker.model.ScheduledTrip
import app.transittracker.model.ServiceDate
import app.transittracker.model.ShapeId
import app.transittracker.model.Stop
import app.transittracker.model.StopDelta
import app.transittracker.model.StopId
import app.transittracker.model.Timetable
import app.transittracker.model.Transfer
import app.transittracker.model.TripDelta
import app.transittracker.model.TripId
import app.transittracker.model.VehiclesResponse
import app.transittracker.model.WallClock
import app.transittracker.model.WatchDto
import app.transittracker.server.spi.AlertsSnapshot
import app.transittracker.server.spi.DemandKind
import app.transittracker.server.spi.LiveData
import app.transittracker.server.spi.LiveStatus
import app.transittracker.server.spi.PushResult
import app.transittracker.server.spi.RefreshResult
import app.transittracker.server.spi.TimetableRepository
import app.transittracker.server.spi.TripChangeBatch
import app.transittracker.server.spi.WatchService
import app.transittracker.server.spi.WatchStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class MutableClock(@Volatile var now: Long = 1_762_819_200_000L) : WallClock {
    override fun nowMillis(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

class FakeTimetable(private val shapes: Map<ShapeId, List<LatLng>>) : Timetable {
    override val version: String = "test-1"
    override fun stop(id: StopId): Stop? = null
    override fun stopsAtStation(stationId: StopId): List<Stop> = emptyList()
    override fun searchStops(query: String, limit: Int): List<Stop> = emptyList()
    override fun stopsNear(position: LatLng, radiusMeters: Double, limit: Int): List<Stop> = emptyList()
    override fun route(id: RouteId): Route? = null
    override fun routes(): List<Route> = emptyList()
    override fun routesAtStop(stopId: StopId): List<Route> = emptyList()
    override fun trip(tripId: TripId): ScheduledTrip? = null
    override fun runsOn(tripId: TripId, serviceDate: ServiceDate): Boolean = false
    override fun departures(query: DepartureQuery): List<ScheduledDeparture> = emptyList()
    override fun shape(shapeId: ShapeId): List<LatLng>? = shapes[shapeId]
    override fun transfers(fromStopId: StopId): List<Transfer> = emptyList()
    override fun close() = Unit
}

class FakeTimetableRepository(
    timetable: Timetable? = null,
    var packList: List<PackInfo> = emptyList(),
    val files: Map<Pair<String, String>, Path> = emptyMap(),
) : TimetableRepository {
    override val current = MutableStateFlow(timetable)
    override fun packs(): List<PackInfo> = packList
    override fun packFile(name: String, version: String): Path? = files[name to version]
    override suspend fun refresh(force: Boolean) = RefreshResult(emptyList(), emptyList(), emptyMap())
    override fun start(scope: CoroutineScope) = Unit
}

class FakeLiveData(private val clock: WallClock) : LiveData {
    val realtimeCalls = mutableListOf<Pair<Set<RouteId>, DemandKind>>()
    var trips: List<TripDelta> = listOf(
        TripDelta(
            tripId = "T1.100",
            startDate = "20261111",
            routeId = "T1",
            stops = listOf(StopDelta(stopSequence = 3, stopId = "2150401", arrivalDelayS = 120, departureDelayS = 120)),
            updatedMs = 1_000,
        ),
    )
    var alertsSnapshot = AlertsSnapshot(
        etag = "alerts-v1",
        response = AlertsResponse(serverTimeMs = 0, alerts = listOf(Alert(id = "a1", headline = "Trackwork", modes = listOf(Mode.TRAIN)))),
    )
    var lastAlertModes: Set<Mode>? = null
    var failWith: Throwable? = null

    override suspend fun realtime(routeIds: Set<RouteId>, demand: DemandKind): RealtimeResponse {
        failWith?.let { throw it }
        realtimeCalls += routeIds to demand
        return RealtimeResponse(
            serverTimeMs = clock.nowMillis(),
            timetableVersion = "test-1",
            feeds = listOf(FeedHealth(feed = "sydneytrains", mode = Mode.TRAIN, dataTimeMs = 1_000)),
            trips = trips.filter { it.routeId in routeIds },
            alertsEtag = alertsSnapshot.etag,
        )
    }

    override suspend fun trip(tripId: TripId, serviceDate: ServiceDate, demand: DemandKind) = RealtimeResponse(
        serverTimeMs = clock.nowMillis(),
        trips = trips.filter { it.tripId == tripId && it.startDate == serviceDate },
    )

    override suspend fun vehicles(routeIds: Set<RouteId>) = VehiclesResponse(serverTimeMs = clock.nowMillis())

    override suspend fun alerts(modes: Set<Mode>?): AlertsSnapshot {
        lastAlertModes = modes
        return alertsSnapshot
    }

    override fun keepWarm(key: String, routeIds: Set<RouteId>, demand: DemandKind, untilMillis: Long) = Unit
    override val tripChanges: SharedFlow<TripChangeBatch> = MutableSharedFlow()
    override val alertChanges: SharedFlow<List<Alert>> = MutableSharedFlow()
    override fun cachedTrip(tripId: TripId, serviceDate: ServiceDate): TripDelta? = null
    override fun status() = LiveStatus(callsToday = 42, dailyBudget = 50_000, feeds = emptyList())
    override fun start(scope: CoroutineScope) = Unit
}

/** In-memory [WatchService] that records calls. */
class FakeWatchService : WatchService {
    val watches = ConcurrentHashMap<String, MutableMap<String, WatchDto>>()
    var testResult: PushResult = PushResult.Sent("msg-1")

    override fun list(deviceId: String): List<WatchDto> = watches[deviceId]?.values?.toList().orEmpty()
    override fun upsert(deviceId: String, watch: WatchDto): WatchDto {
        val stored = watch.copy(updatedMs = 5)
        watches.getOrPut(deviceId) { linkedMapOf() }[watch.id] = stored
        return stored
    }
    override fun delete(deviceId: String, watchId: String): Boolean = watches[deviceId]?.remove(watchId) != null
    override suspend fun sendTest(deviceId: String, watchId: String): PushResult = testResult
    override fun status() = WatchStatus(totalWatches = watches.values.sumOf { it.size }, activeWatches = 0, sentToday = 0, failedToday = 0)
    override fun start(scope: CoroutineScope) = Unit
}

/** Test fixtures under the repository's `testdata` directory (path from the `tt.testdata` property). */
object Fixtures {
    val testdata: Path = java.nio.file.Paths.get(
        System.getProperty("tt.testdata") ?: error("System property tt.testdata is not set (see server/app/build.gradle.kts)"),
    )

    /** The synthetic `mini-rail` GTFS feed (see testdata/gtfs/README.md). */
    val miniRail: Path = testdata.resolve("gtfs/mini-rail")
}
