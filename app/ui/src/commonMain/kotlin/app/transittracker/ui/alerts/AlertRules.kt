package app.transittracker.ui.alerts

import app.transittracker.model.Alert
import app.transittracker.model.AlertSeverity
import app.transittracker.model.Journey
import app.transittracker.model.RouteId
import app.transittracker.model.SavedTrip
import app.transittracker.model.StopId
import app.transittracker.model.TransitCore
import app.transittracker.model.TripId

/*
 * Which alerts touch which saved trip. One rule for the whole app: the Alerts tab, its badge in
 * the bottom bar and anything else that says "this affects your trip" use these functions, so
 * they cannot disagree.
 */

/** What a saved trip passes through: its stops as saved, the lines it can use, its services today. */
data class TripFootprint(val stops: Set<StopId>, val routes: Set<RouteId>, val services: Set<TripId> = emptySet())

/**
 * The footprint of [trip]: every origin and destination stop of its legs as saved; its line
 * filters, the [lines] its unfiltered legs can use (see [tripLines]) and the lines of its upcoming
 * [journeys]; and those services' trip ids.
 */
fun tripFootprint(trip: SavedTrip, journeys: List<Journey> = emptyList(), lines: Set<RouteId> = emptySet()): TripFootprint {
    val legs = journeys.flatMap { it.legs }
    return TripFootprint(
        stops = trip.legs.flatMap { it.originStopIds + it.destinationStopIds }.toSet(),
        routes = trip.legs.flatMap { it.routeIds.orEmpty() }.toSet() + lines + legs.map { it.routeId },
        services = legs.map { it.ref.tripId }.toSet(),
    )
}

/**
 * The lines [trip] can use, without asking for departures: a leg's line filter, or for a leg
 * without one the lines that call at both of its ends. Most saved trips have no filter
 * ("Strathfield to Central, any line"), and an alert about a line names the line, not the stops,
 * so without this a trackwork alert would never be matched to such a trip.
 */
suspend fun tripLines(core: TransitCore, trip: SavedTrip): Set<RouteId> {
    val lines = mutableSetOf<RouteId>()
    for (leg in trip.legs) {
        val filter = leg.routeIds
        if (filter != null) {
            lines += filter
            continue
        }
        val atOrigin = linesAtAny(core, leg.originStopIds)
        if (atOrigin.isEmpty()) continue
        lines += atOrigin intersect linesAtAny(core, leg.destinationStopIds)
    }
    return lines
}

private suspend fun linesAtAny(core: TransitCore, stops: Set<StopId>): Set<RouteId> =
    stops.flatMap { stop -> runCatching { core.linesAt(stop) }.getOrDefault(emptyList()) }.map { it.id }.toSet()

/** True when [alert] names a stop, a line or a service of [footprint]. */
fun alertTouchesTrip(alert: Alert, footprint: TripFootprint): Boolean =
    alert.stopIds.any { it in footprint.stops } ||
        alert.routeIds.any { it in footprint.routes } ||
        alert.tripIds.any { it in footprint.services }

/** True when [alert] applies at [nowMs] (no periods = always). */
fun Alert.isActive(nowMs: Long): Boolean = activePeriods.isEmpty() || activePeriods.any { p ->
    (p.startMs == null || p.startMs!! <= nowMs) && (p.endMs == null || nowMs < p.endMs!!)
}

/** True when every period of [alert] is over. An alert with no period is in force until it is withdrawn. */
fun alertEnded(alert: Alert, nowMs: Long): Boolean =
    alert.activePeriods.isNotEmpty() && alert.activePeriods.none { p -> p.endMs == null || nowMs < p.endMs!! }

/** Worst first: SEVERE, WARNING, INFO, UNKNOWN. */
fun severityRank(s: AlertSeverity): Int = when (s) {
    AlertSeverity.SEVERE -> 0
    AlertSeverity.WARNING -> 1
    AlertSeverity.INFO -> 2
    AlertSeverity.UNKNOWN -> 3
}
