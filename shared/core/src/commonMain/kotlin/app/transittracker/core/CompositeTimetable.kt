package app.transittracker.core

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
 * Several timetable packs (e.g. `syd-rail` + `syd-ferry`) seen as one [Timetable].
 *
 * Lookups return the first pack's answer; list queries are merged and de-duplicated.
 * [close] does nothing: the parts are owned (and closed) by whoever opened them.
 */
class CompositeTimetable(val parts: List<Timetable>) : Timetable {
    override val version: String get() = parts.joinToString(",") { it.version }

    override fun stop(id: StopId): Stop? = parts.firstNotNullOfOrNull { it.stop(id) }

    override fun stopsAtStation(stationId: StopId): List<Stop> =
        parts.flatMap { it.stopsAtStation(stationId) }.distinctBy { it.id }

    override fun searchStops(query: String, limit: Int): List<Stop> =
        parts.flatMap { it.searchStops(query, limit) }.distinctBy { it.id }.take(limit)

    override fun stopsNear(position: LatLng, radiusMeters: Double, limit: Int): List<Stop> =
        parts.flatMap { it.stopsNear(position, radiusMeters, limit) }
            .distinctBy { it.id }
            .sortedBy { distanceMeters(position, it.position) }
            .take(limit)

    override fun route(id: RouteId): Route? = parts.firstNotNullOfOrNull { it.route(id) }

    override fun routes(): List<Route> = parts.flatMap { it.routes() }.distinctBy { it.id }

    override fun routesAtStop(stopId: StopId): List<Route> = parts.flatMap { it.routesAtStop(stopId) }.distinctBy { it.id }

    override fun trip(tripId: TripId): ScheduledTrip? = parts.firstNotNullOfOrNull { it.trip(tripId) }

    override fun runsOn(tripId: TripId, serviceDate: ServiceDate): Boolean = parts.any { it.runsOn(tripId, serviceDate) }

    override fun departures(query: DepartureQuery): List<ScheduledDeparture> {
        if (parts.size == 1) return parts[0].departures(query)
        return parts.flatMap { it.departures(query) }
            .distinctBy { it.tripId to it.serviceDate }
            .sortedBy { it.departureMillis }
            .take(query.limit)
    }

    override fun shape(shapeId: ShapeId): List<LatLng>? = parts.firstNotNullOfOrNull { it.shape(shapeId) }

    override fun transfers(fromStopId: StopId): List<Transfer> = parts.flatMap { it.transfers(fromStopId) }.distinct()

    override fun close() = Unit
}
