package app.transittracker.core

import app.transittracker.model.DepartureQuery
import app.transittracker.model.LatLng
import app.transittracker.model.Route
import app.transittracker.model.RouteId
import app.transittracker.model.ScheduledDeparture
import app.transittracker.model.ScheduledTrip
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceId
import app.transittracker.model.ServiceTime
import app.transittracker.model.ShapeId
import app.transittracker.model.Stop
import app.transittracker.model.StopId
import app.transittracker.model.Timetable
import app.transittracker.model.Transfer
import app.transittracker.model.TripId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A [Timetable] held in memory, for tests, previews and [FakeTransitCore].
 *
 * @param serviceRuns whether a service id runs on a service date (default: every day).
 */
class InMemoryTimetable(
    stops: List<Stop>,
    routes: List<Route>,
    trips: List<ScheduledTrip>,
    override val version: String = "test",
    private val shapes: Map<ShapeId, List<LatLng>> = emptyMap(),
    private val transfers: List<Transfer> = emptyList(),
    private val serviceRuns: (ServiceId, ServiceDate) -> Boolean = { _, _ -> true },
) : Timetable {
    private val stopsById = stops.associateBy { it.id }
    private val routesById = routes.associateBy { it.id }
    private val tripsById = trips.associateBy { it.tripId }

    /** Set when [close] is called; lets tests check that replaced packs are closed. */
    var closed: Boolean = false
        private set

    override fun stop(id: StopId): Stop? = stopsById[id]

    override fun stopsAtStation(stationId: StopId): List<Stop> {
        val station = stopsById[stationId] ?: return emptyList()
        return listOf(station) + stopsById.values.filter { it.parentId == stationId }
    }

    override fun searchStops(query: String, limit: Int): List<Stop> =
        stopsById.values.filter { it.parentId == null && it.name.contains(query, ignoreCase = true) }.take(limit)

    override fun stopsNear(position: LatLng, radiusMeters: Double, limit: Int): List<Stop> =
        stopsById.values.filter { it.parentId == null }
            .map { it to distanceMeters(position, it.position) }
            .filter { it.second <= radiusMeters }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }

    override fun route(id: RouteId): Route? = routesById[id]

    override fun routes(): List<Route> = routesById.values.toList()

    override fun routesAtStop(stopId: StopId): List<Route> {
        val ids = stopsAtStation(stopId).map { it.id }.toSet() + stopId
        return tripsById.values.filter { t -> t.stopTimes.any { it.stopId in ids } }
            .map { it.routeId }.distinct().mapNotNull { routesById[it] }
    }

    override fun trip(tripId: TripId): ScheduledTrip? = tripsById[tripId]

    override fun runsOn(tripId: TripId, serviceDate: ServiceDate): Boolean {
        val trip = tripsById[tripId] ?: return false
        return serviceRuns(trip.serviceId, serviceDate)
    }

    override fun departures(query: DepartureQuery): List<ScheduledDeparture> {
        val result = ArrayList<ScheduledDeparture>()
        for (trip in tripsById.values) {
            if (query.routeIds != null && trip.routeId !in query.routeIds!!) continue
            if (!serviceRuns(trip.serviceId, query.serviceDate)) continue
            val times = trip.stopTimes.sortedBy { it.stopSequence }
            val oi = times.indexOfFirst { it.stopId in query.originStopIds && it.pickup }
            if (oi < 0) continue
            val origin = times[oi]
            if (origin.departureSecs < query.fromSecs || origin.departureSecs > query.toSecs) continue
            val dest = times.drop(oi + 1).firstOrNull { it.stopId in query.destinationStopIds && it.dropOff } ?: continue
            result += ScheduledDeparture(
                tripId = trip.tripId,
                routeId = trip.routeId,
                serviceDate = query.serviceDate,
                headsign = trip.headsign,
                originStopId = origin.stopId,
                originStopSequence = origin.stopSequence,
                departureSecs = origin.departureSecs,
                destinationStopId = dest.stopId,
                destinationStopSequence = dest.stopSequence,
                arrivalSecs = dest.arrivalSecs,
                departureMillis = ServiceTime.toEpochMillis(query.serviceDate, origin.departureSecs),
                arrivalMillis = ServiceTime.toEpochMillis(query.serviceDate, dest.arrivalSecs),
            )
        }
        return result.sortedBy { it.departureSecs }.take(query.limit)
    }

    override fun shape(shapeId: ShapeId): List<LatLng>? = shapes[shapeId]

    override fun transfers(fromStopId: StopId): List<Transfer> = transfers.filter { it.fromStopId == fromStopId }

    override fun close() {
        closed = true
    }
}

/** Great-circle distance in metres. */
internal fun distanceMeters(a: LatLng, b: LatLng): Double {
    val r = 6_371_000.0
    val dLat = (b.lat - a.lat).toRadians()
    val dLon = (b.lon - a.lon).toRadians()
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(a.lat.toRadians()) * cos(b.lat.toRadians()) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(sqrt(h))
}

private fun Double.toRadians() = this * kotlin.math.PI / 180.0
