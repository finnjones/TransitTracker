package app.transittracker.server.planner

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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Hand-made TfNSW answers under `testdata/planner` (path from the `tt.testdata` system property). */
object PlannerFixtures {
    val dir: Path = Paths.get(
        System.getProperty("tt.testdata") ?: error("System property tt.testdata is not set (see server/planner/build.gradle.kts)"),
    ).resolve("planner")

    fun tfnsw(name: String): String = Files.readString(dir.resolve("tfnsw").resolve(name))

    fun live(name: String): String = Files.readString(dir.resolve("live").resolve(name))

    val replay: Path = dir.resolve("replay/planner")
}

class MutableClock(@Volatile var now: Long) : WallClock {
    override fun nowMillis(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

/** Sydney local time on 2026-11-11 (the fixtures' Wednesday) as epoch ms. */
fun local(hhmm: String, date: ServiceDate = DAY): Long {
    val (h, m) = hhmm.split(':').map { it.toInt() }
    return ServiceTime.toEpochMillis(date, h * 3600 + m * 60)
}

const val DAY = "20261111"

/**
 * A small in-memory timetable with the stations, lines and trips the `testdata/planner/tfnsw`
 * fixtures use (ids are invented in TfNSW's style). Every trip runs only on [DAY].
 */
class PlannerTestTimetable : Timetable {
    override val version: String = "planner-test-1"

    private val stops = LinkedHashMap<StopId, Stop>()
    private val routes = LinkedHashMap<RouteId, Route>()
    private val trips = LinkedHashMap<TripId, ScheduledTrip>()

    init {
        station("213510", "Strathfield Station", -33.8718, 151.0943, "2135233" to "3", "2135235" to "5")
        station("200060", "Central Station", -33.8840, 151.2063, "2000336" to "16", "2000338" to "18")
        station("212110", "Epping Station", -33.7726, 151.0822, "2121231" to "1")
        station("201510", "Redfern Station", -33.8916, 151.1987, "2015140" to "10")
        station("213110", "Summer Hill Station", -33.8914, 151.1386, "2131232" to "2")
        station("213410", "Burwood Station", -33.8774, 151.1035, "2134231" to "1")
        route("T9", "D11F2F")
        route("T2", "0098CD")
        // Direct train fixture (rule 1: the planner's real-time trip ids are these GTFS trip ids).
        trip("121A.1450.133.8.T.8.86753001", "T9", "2135233" to "08:02", "2134231" to "08:05", "2000336" to "08:14")
        trip("121A.1450.133.8.T.8.86753002", "T9", "2135233" to "08:17", "2134231" to "08:20", "2000336" to "08:29")
        // Train change fixture: T9 Epping -> Strathfield (rule 1), then T2 Strathfield -> Redfern (rule 2).
        trip("121A.1450.133.8.T.8.86753100", "T9", "2121231" to "07:45", "2135235" to "08:05")
        trip("131B.1201.120.12.K.8.86750812", "T2", "2135233" to "08:12", "2134231" to "08:15", "2015140" to "08:22")
        // Train then bus fixture: T2 Summer Hill -> Central.
        trip("131B.1201.120.12.K.8.86753200", "T2", "2131232" to "07:31", "2000336" to "07:43")
        // Cancelled fixture.
        trip("121A.1450.133.8.T.8.86753009", "T9", "2135233" to "09:02", "2000336" to "09:14")
        // Two T9s leaving Strathfield at 08:32 for Central: a planner ride at that time is ambiguous.
        trip("121A.1450.133.8.T.8.86753032", "T9", "2135233" to "08:32", "2000336" to "08:44")
        trip("121A.1450.133.8.T.8.86753033", "T9", "2135235" to "08:32", "2000336" to "08:45")
    }

    private fun station(id: StopId, name: String, lat: Double, lon: Double, vararg platforms: Pair<StopId, String>) {
        stops[id] = Stop(id, name, null, LatLng(lat, lon), modes = setOf(Mode.TRAIN))
        for ((pid, code) in platforms) {
            stops[pid] = Stop(pid, "$name Platform $code", id, LatLng(lat, lon), platformCode = code, modes = setOf(Mode.TRAIN))
        }
    }

    private fun route(name: String, color: String) {
        routes[name] = Route(name, name, "$name line", Mode.TRAIN, color = color)
    }

    private fun trip(id: TripId, route: RouteId, vararg calls: Pair<StopId, String>) {
        trips[id] = ScheduledTrip(
            tripId = id,
            routeId = route,
            serviceId = "WD",
            headsign = "",
            stopTimes = calls.mapIndexed { i, (stop, time) ->
                val (h, m) = time.split(':').map { it.toInt() }
                ScheduledStopTime(i + 1, stop, h * 3600 + m * 60, h * 3600 + m * 60)
            },
        )
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
    override fun runsOn(tripId: TripId, serviceDate: ServiceDate): Boolean = tripId in trips && serviceDate == DAY

    override fun departures(query: DepartureQuery): List<ScheduledDeparture> = trips.values
        .filter { query.routeIds == null || it.routeId in query.routeIds!! }
        .filter { runsOn(it.tripId, query.serviceDate) }
        .mapNotNull { trip ->
            val st = trip.stopTimes
            val i = st.indexOfFirst { it.stopId in query.originStopIds && it.departureSecs in query.fromSecs..query.toSecs }
            if (i < 0) return@mapNotNull null
            val j = (i + 1 until st.size).firstOrNull { st[it].stopId in query.destinationStopIds } ?: return@mapNotNull null
            ScheduledDeparture(
                tripId = trip.tripId, routeId = trip.routeId, serviceDate = query.serviceDate, headsign = trip.headsign,
                originStopId = st[i].stopId, originStopSequence = st[i].stopSequence, departureSecs = st[i].departureSecs,
                destinationStopId = st[j].stopId, destinationStopSequence = st[j].stopSequence, arrivalSecs = st[j].arrivalSecs,
                departureMillis = ServiceTime.toEpochMillis(query.serviceDate, st[i].departureSecs),
                arrivalMillis = ServiceTime.toEpochMillis(query.serviceDate, st[j].arrivalSecs),
            )
        }
        .sortedBy { it.departureMillis }
        .take(query.limit)

    override fun shape(shapeId: ShapeId): List<LatLng>? = null
    override fun transfers(fromStopId: StopId): List<Transfer> = emptyList()
    override fun close() = Unit
}

/** A [PlannerFetcher] that answers from strings and records every call. */
class FakePlannerFetcher(
    override val isRemote: Boolean = true,
    override val configured: Boolean = true,
) : PlannerFetcher {
    val calls = ArrayList<Pair<PlannerEndpoint, List<Pair<String, String>>>>()
    var trip: PlannerFetch = PlannerFetch.Success(PlannerFixtures.tfnsw("trip_direct_train.json"))
    var stopFinder: PlannerFetch = PlannerFetch.Success(PlannerFixtures.tfnsw("stop_finder_central.json"))
    var coord: PlannerFetch = PlannerFetch.Success(PlannerFixtures.live("coord-central.json"))

    override suspend fun fetch(endpoint: PlannerEndpoint, params: List<Pair<String, String>>): PlannerFetch {
        calls += endpoint to params
        return when (endpoint) {
            PlannerEndpoint.TRIP -> trip
            PlannerEndpoint.STOP_FINDER -> stopFinder
            PlannerEndpoint.COORD -> coord
        }
    }
}
