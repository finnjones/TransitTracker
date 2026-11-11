package app.transittracker.server.watch

import app.transittracker.model.Alert
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.RealtimeResponse
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
import app.transittracker.model.TripDelta
import app.transittracker.model.TripId
import app.transittracker.model.VehiclesResponse
import app.transittracker.model.WallClock
import app.transittracker.model.WatchDto
import app.transittracker.server.spi.AlertsSnapshot
import app.transittracker.server.spi.DemandKind
import app.transittracker.server.spi.DeviceRecord
import app.transittracker.server.spi.DeviceRepository
import app.transittracker.server.spi.LiveData
import app.transittracker.server.spi.LiveStatus
import app.transittracker.server.spi.NotificationLog
import app.transittracker.server.spi.NotificationLogEntry
import app.transittracker.server.spi.PushMessage
import app.transittracker.server.spi.PushResult
import app.transittracker.server.spi.PushSender
import app.transittracker.server.spi.StoredWatch
import app.transittracker.server.spi.TripChangeBatch
import app.transittracker.server.spi.WatchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant
import kotlin.time.ExperimentalTime

class MutableClock(var now: Long) : WallClock {
    override fun nowMillis(): Long = now
}

/** Epoch millis of a Sydney wall-clock time, e.g. `sydney("2026-11-11", "07:42")`. */
@OptIn(ExperimentalTime::class)
fun sydney(date: String, time: String): Long =
    LocalDateTime(LocalDate.parse(date), LocalTime.parse(time)).toInstant(ServiceTime.SYDNEY).toEpochMilliseconds()

/** A trip definition: stop times as (stopId, GTFS seconds); runs on [isoDays] (null = every day). */
data class FakeTrip(
    val tripId: TripId,
    val routeId: RouteId,
    val calls: List<Pair<StopId, Int>>,
    val isoDays: Set<Int>? = null,
    val headsign: String = "Central",
) {
    val scheduled = ScheduledTrip(
        tripId = tripId,
        routeId = routeId,
        serviceId = "svc-$tripId",
        headsign = headsign,
        stopTimes = calls.mapIndexed { i, (stop, secs) -> ScheduledStopTime(i + 1, stop, secs, secs) },
    )
}

/** In-memory [Timetable] with just enough behaviour for the watch engine. */
class FakeTimetable(
    private val stops: List<Stop>,
    private val routes: List<Route>,
    private val trips: List<FakeTrip>,
    override val version: String = "test-1",
) : Timetable {
    override fun stop(id: StopId) = stops.firstOrNull { it.id == id }
    override fun stopsAtStation(stationId: StopId) = listOfNotNull(stop(stationId)) + stops.filter { it.parentId == stationId }
    override fun searchStops(query: String, limit: Int) = emptyList<Stop>()
    override fun stopsNear(position: LatLng, radiusMeters: Double, limit: Int) = emptyList<Stop>()
    override fun route(id: RouteId) = routes.firstOrNull { it.id == id }
    override fun routes() = routes
    override fun routesAtStop(stopId: StopId) =
        trips.filter { t -> t.calls.any { it.first == stopId } }.map { it.routeId }.distinct().mapNotNull { route(it) }
    override fun trip(tripId: TripId) = trips.firstOrNull { it.tripId == tripId }?.scheduled
    override fun runsOn(tripId: TripId, serviceDate: ServiceDate): Boolean {
        val trip = trips.firstOrNull { it.tripId == tripId } ?: return false
        return trip.isoDays == null || ServiceTime.isoDayOfWeek(serviceDate) in trip.isoDays
    }

    override fun departures(query: app.transittracker.model.DepartureQuery): List<ScheduledDeparture> =
        trips.asSequence()
            .filter { runsOn(it.tripId, query.serviceDate) }
            .filter { query.routeIds == null || it.routeId in query.routeIds!! }
            .mapNotNull { trip ->
                val (o, d) = CoverageCalculator.findLeg(trip.scheduled, query.originStopIds, query.destinationStopIds)
                    ?: return@mapNotNull null
                if (o.departureSecs !in query.fromSecs..query.toSecs) return@mapNotNull null
                ScheduledDeparture(
                    tripId = trip.tripId,
                    routeId = trip.routeId,
                    serviceDate = query.serviceDate,
                    headsign = trip.headsign,
                    originStopId = o.stopId,
                    originStopSequence = o.stopSequence,
                    departureSecs = o.departureSecs,
                    destinationStopId = d.stopId,
                    destinationStopSequence = d.stopSequence,
                    arrivalSecs = d.arrivalSecs,
                    departureMillis = ServiceTime.toEpochMillis(query.serviceDate, o.departureSecs),
                    arrivalMillis = ServiceTime.toEpochMillis(query.serviceDate, d.arrivalSecs),
                )
            }
            .sortedBy { it.departureMillis }
            .take(query.limit)
            .toList()

    override fun shape(shapeId: ShapeId): List<LatLng>? = null
    override fun transfers(fromStopId: StopId) = emptyList<Transfer>()
    override fun close() {}
}

/**
 * Test network: Strathfield (STR, platforms STR1..STR6) and Central (CEN, CEN1..CEN3) on T1/T9;
 * Lidcombe (LID) and Burwood (BUR) on T3 only.
 */
object TestNetwork {
    private val origin = LatLng(-33.87, 151.09)

    private fun station(id: String, name: String, platforms: Int) =
        listOf(Stop(id, name, position = origin, modes = setOf(Mode.TRAIN))) +
            (1..platforms).map { Stop("$id$it", "$name Platform $it", parentId = id, position = origin, platformCode = "$it") }

    val stops = station("STR", "Strathfield Station", 6) + station("CEN", "Central Station", 3) +
        station("LID", "Lidcombe Station", 1) + station("BUR", "Burwood Station", 1)

    val routes = listOf(
        Route("T1", "T1", "North Shore & Western Line", Mode.TRAIN),
        Route("T9", "T9", "Northern Line", Mode.TRAIN),
        Route("T3", "T3", "Liverpool & Inner West Line", Mode.TRAIN),
    )

    fun secs(hhmm: String): Int = hhmm.split(":").let { it[0].toInt() * 3600 + it[1].toInt() * 60 }

    /** STR -> CEN taking 15 minutes. */
    fun strToCen(id: String, route: RouteId, departs: String, platform: String = "STR3", isoDays: Set<Int>? = null) =
        FakeTrip(id, route, listOf(platform to secs(departs), "CEN1" to secs(departs) + 15 * 60), isoDays)

    val trips = listOf(
        strToCen("T1-0705", "T1", "07:05"),
        strToCen("T1-0742", "T1", "07:42"),
        strToCen("T9-0751", "T9", "07:51"),
        strToCen("T1-0812", "T1", "08:12"),
        strToCen("T1-WKDY-0820", "T1", "08:20", isoDays = setOf(1, 2, 3, 4, 5)),
        FakeTrip("R1-0745", "T1", listOf("CEN1" to secs("07:45"), "STR4" to secs("08:00")), headsign = "Strathfield"),
        FakeTrip("T3-0730", "T3", listOf("LID1" to secs("07:30"), "BUR1" to secs("07:40")), headsign = "Burwood"),
        // Early-morning trips for daylight-saving tests (GTFS times, may exceed 24:00).
        strToCen("T1-G0030", "T1", "00:30"),
        strToCen("T1-G0130", "T1", "01:30"),
        strToCen("T1-G0230", "T1", "02:30"),
        strToCen("T1-G2430", "T1", "24:30"),
    )

    fun timetable(extra: List<FakeTrip> = emptyList()) = FakeTimetable(stops, routes, trips + extra)
}

class InMemoryWatchRepository : WatchRepository {
    private val watches = LinkedHashMap<Pair<String, String>, WatchDto>()
    override fun list(deviceId: String) = watches.filterKeys { it.first == deviceId }.values.toList()
    override fun all() = watches.map { (k, v) -> StoredWatch(k.first, v) }
    override fun get(deviceId: String, watchId: String) = watches[deviceId to watchId]
    override fun upsert(deviceId: String, watch: WatchDto) { watches[deviceId to watch.id] = watch }
    override fun delete(deviceId: String, watchId: String) = watches.remove(deviceId to watchId) != null
}

class InMemoryDeviceRepository : DeviceRepository {
    val devices = LinkedHashMap<String, DeviceRecord>()
    fun add(id: String, token: String? = "token-$id", enabled: Boolean = true) {
        devices[id] = DeviceRecord(id, token, "android", "1.0", enabled, 0, 0, revoked = false)
    }
    override fun get(deviceId: String) = devices[deviceId]
    override fun all() = devices.values.toList()
    override fun clearPushToken(deviceId: String) {
        devices[deviceId]?.let { devices[deviceId] = it.copy(pushToken = null) }
    }
}

class InMemoryNotificationLog : NotificationLog {
    val entries = ArrayList<NotificationLogEntry>()
    override fun record(entry: NotificationLogEntry) { entries += entry }
    override fun recent(limit: Int) = entries.takeLast(limit).reversed()
}

class RecordingPushSender : PushSender {
    val sent = ArrayList<Pair<String, PushMessage>>()
    var next: PushResult = PushResult.Sent("msg")
    override suspend fun send(token: String, message: PushMessage): PushResult {
        sent += token to message
        return next
    }
}

data class KeepWarmCall(val key: String, val routeIds: Set<RouteId>, val demand: DemandKind, val untilMillis: Long)

class FakeLiveData : LiveData {
    val keepWarmCalls = ArrayList<KeepWarmCall>()
    val cached = HashMap<Pair<TripId, ServiceDate>, TripDelta>()
    override val tripChanges = MutableSharedFlow<TripChangeBatch>(extraBufferCapacity = 16)
    override val alertChanges = MutableSharedFlow<List<Alert>>(extraBufferCapacity = 16)

    override suspend fun realtime(routeIds: Set<RouteId>, demand: DemandKind): RealtimeResponse = TODO("not used")
    override suspend fun trip(tripId: TripId, serviceDate: ServiceDate, demand: DemandKind): RealtimeResponse = TODO("not used")
    override suspend fun vehicles(routeIds: Set<RouteId>): VehiclesResponse = TODO("not used")
    override suspend fun alerts(modes: Set<Mode>?): AlertsSnapshot = TODO("not used")
    override fun keepWarm(key: String, routeIds: Set<RouteId>, demand: DemandKind, untilMillis: Long) {
        keepWarmCalls += KeepWarmCall(key, routeIds, demand, untilMillis)
    }
    override fun cachedTrip(tripId: TripId, serviceDate: ServiceDate) = cached[tripId to serviceDate]
    override fun status() = LiveStatus(0, 0, emptyList())
    override fun start(scope: CoroutineScope) {}
}
