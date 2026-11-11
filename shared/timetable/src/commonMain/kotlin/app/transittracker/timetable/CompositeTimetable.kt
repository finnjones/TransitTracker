package app.transittracker.timetable

import app.transittracker.model.DepartureQuery
import app.transittracker.model.LatLng
import app.transittracker.model.Route
import app.transittracker.model.RouteId
import app.transittracker.model.ScheduledDeparture
import app.transittracker.model.ScheduledTrip
import app.transittracker.model.ServiceDate
import app.transittracker.model.ShapeId
import app.transittracker.model.Stop
import app.transittracker.model.StopId
import app.transittracker.model.Timetable
import app.transittracker.model.Transfer
import app.transittracker.model.TripId

/**
 * Several packs (e.g. `syd-rail` + `syd-ferry`) seen as one [Timetable]. Lookups return the
 * first pack's answer; list queries are merged, de-duplicated and re-sorted. A stop present in
 * several packs (a shared interchange) is returned once, from the first pack, but its child
 * stops and routes are collected from all of them.
 *
 * [version] is the packs' versions joined with `+` in list order.
 */
class CompositeTimetable(val packs: List<Timetable>) : Timetable {
    override val version: String = packs.joinToString("+") { it.version }

    override fun stop(id: StopId): Stop? = packs.firstNotNullOfOrNull { it.stop(id) }

    override fun stopsAtStation(stationId: StopId): List<Stop> =
        packs.flatMap { it.stopsAtStation(stationId) }.distinctBy { it.id }

    override fun searchStops(query: String, limit: Int): List<Stop> =
        packs.flatMap { it.searchStops(query, limit) }
            .distinctBy { it.id }
            .sortedWith(StopSearch.comparator(query))
            .take(limit)

    override fun stopsNear(position: LatLng, radiusMeters: Double, limit: Int): List<Stop> =
        packs.flatMap { it.stopsNear(position, radiusMeters, limit) }
            .distinctBy { it.id }
            .sortedWith(compareBy({ Geo.haversineMeters(position, it.position) }, { it.id }))
            .take(limit)

    override fun route(id: RouteId): Route? = packs.firstNotNullOfOrNull { it.route(id) }

    override fun routes(): List<Route> = packs.flatMap { it.routes() }.distinctBy { it.id }

    override fun routesAtStop(stopId: StopId): List<Route> =
        packs.flatMap { it.routesAtStop(stopId) }.distinctBy { it.id }

    override fun trip(tripId: TripId): ScheduledTrip? = packs.firstNotNullOfOrNull { it.trip(tripId) }

    override fun runsOn(tripId: TripId, serviceDate: ServiceDate): Boolean = packs.any { it.runsOn(tripId, serviceDate) }

    override fun departures(query: DepartureQuery): List<ScheduledDeparture> =
        packs.flatMap { it.departures(query) }
            .distinctBy { it.tripId to it.serviceDate }
            .sortedWith(compareBy({ it.departureSecs }, { it.arrivalSecs }, { it.tripId }))
            .take(query.limit)

    override fun shape(shapeId: ShapeId): List<LatLng>? = packs.firstNotNullOfOrNull { it.shape(shapeId) }

    override fun transfers(fromStopId: StopId): List<Transfer> =
        packs.flatMap { it.transfers(fromStopId) }.distinctBy { it.toStopId }

    /** Closes every pack; the first failure is rethrown after all have been attempted. */
    override fun close() {
        var error: Throwable? = null
        for (p in packs) {
            try {
                p.close()
            } catch (t: Throwable) {
                if (error == null) error = t else error.addSuppressed(t)
            }
        }
        error?.let { throw it }
    }

    override fun toString(): String = "CompositeTimetable($version)"
}
