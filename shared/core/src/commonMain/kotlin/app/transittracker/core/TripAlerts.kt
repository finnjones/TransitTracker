package app.transittracker.core

import app.transittracker.model.Alert
import app.transittracker.model.Mode
import app.transittracker.model.RouteId
import app.transittracker.model.SavedTrip
import app.transittracker.model.StopId
import app.transittracker.model.Timetable
import app.transittracker.model.TripLeg

/**
 * The alerts among [alerts] that touch [trip] (any of its ways), in their original order. An alert
 * touches the trip when it names:
 * - a route of one of its legs: the leg's [TripLeg.routeIds], or, for a leg that takes any route,
 *   the routes that call at both its origin and its destination station;
 * - one of the legs' origin or destination stops, the parent station of such a stop, or a
 *   platform of such a station;
 * - nothing but modes (a network-wide alert), one of which the trip's routes use.
 *
 * Without a [timetable] only the ids saved in the trip itself are compared.
 */
fun alertsForTrip(trip: SavedTrip, alerts: List<Alert>, timetable: Timetable?): List<Alert> {
    if (alerts.isEmpty() || trip.legs.isEmpty()) return emptyList()
    val stops = HashSet<StopId>()
    val routes = HashSet<RouteId>()
    for (leg in trip.ways.flatMap { it.legs }) {
        for (id in leg.originStopIds + leg.destinationStopIds) stops += relatedStops(id, timetable)
        routes += leg.routeIds ?: timetable?.let { routesServing(it, leg) }.orEmpty()
    }
    val modes: Set<Mode> = timetable?.let { tt -> routes.mapNotNullTo(HashSet()) { tt.route(it)?.mode } }.orEmpty()
    return alerts.filter { alert ->
        alert.routeIds.any { it in routes } ||
            alert.stopIds.any { it in stops } ||
            (alert.routeIds.isEmpty() && alert.stopIds.isEmpty() && alert.tripIds.isEmpty() && alert.modes.any { it in modes })
    }
}

/** [id], its parent station, and its platforms when it is a station. */
private fun relatedStops(id: StopId, timetable: Timetable?): Set<StopId> {
    val stop = timetable?.stop(id) ?: return setOf(id)
    val result = mutableSetOf(id)
    val parent = stop.parentId
    if (parent != null) result += parent else timetable.stopsAtStation(id).mapTo(result) { it.id }
    return result
}

/** Routes calling at one of the leg's origin stops and at one of its destination stops. */
private fun routesServing(timetable: Timetable, leg: TripLeg): Set<RouteId> {
    fun routesAt(ids: Set<StopId>): Set<RouteId> = ids
        .flatMap { id -> timetable.stopsAtStation(id).map { it.id } + id }
        .toSet()
        .flatMapTo(HashSet()) { stopId -> timetable.routesAtStop(stopId).map { it.id } }
    return routesAt(leg.originStopIds) intersect routesAt(leg.destinationStopIds)
}
