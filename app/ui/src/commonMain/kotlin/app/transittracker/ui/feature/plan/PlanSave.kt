package app.transittracker.ui.feature.plan

import app.transittracker.model.Itinerary
import app.transittracker.model.ItineraryLeg
import app.transittracker.model.LegKind
import app.transittracker.model.Mode
import app.transittracker.model.SavedTrip
import app.transittracker.model.StopId
import app.transittracker.model.TripLeg
import app.transittracker.model.rides

/*
 * SAVE ROUTE (plan 5.6 "Saved routes"): a planned journey becomes a SavedTrip that remembers the
 * pattern, not one departure: one TripLeg per ride (its stops and line), and the walk before each
 * later ride as that leg's minimum connection time. Pure; the state holder does the lookups.
 */

/** Minimum connection when the planner gives no walk between two rides (same platform or stop). */
const val NO_WALK_CONNECTION_MINUTES = 3

private fun modeWord(mode: Mode?) = when (mode) {
    Mode.TRAIN -> "train"
    Mode.METRO -> "metro"
    Mode.LIGHT_RAIL -> "light rail"
    Mode.BUS -> "bus"
    Mode.COACH -> "coach"
    Mode.FERRY -> "ferry"
    Mode.OTHER, null -> "service"
}

private fun rideName(leg: ItineraryLeg) = "${leg.routeName.orEmpty()} ${modeWord(leg.mode)}".trim()

/**
 * Why [itinerary] cannot be saved from what it carries, or null when it can: every ride needs
 * the stop ids of where it is boarded and left (the planner sets them only for stops in the
 * timetable). Saving anything less would make a route Home cannot show honestly.
 */
fun saveBlocker(itinerary: Itinerary): String? {
    val rides = itinerary.rides
    if (rides.isEmpty()) return "This journey is a walk, so there is no route to save."
    val missing = rides.firstOrNull { it.from.stopId == null || it.to.stopId == null } ?: return null
    return "The ${rideName(missing)} from ${missing.from.name} is not in the phone's timetable, " +
        "so this journey can't be saved as a route."
}

/** Why the journey cannot be saved because the phone does not know [unknown] stops, or null. */
fun unknownStopsReason(itinerary: Itinerary, unknown: Set<StopId>): String? {
    val ride = itinerary.rides.firstOrNull { it.from.stopId in unknown || it.to.stopId in unknown } ?: return null
    val where = if (ride.from.stopId in unknown) ride.from.name else ride.to.name
    return "The phone's timetable does not have the ${rideName(ride)} stop at $where yet, " +
        "so this journey can't be saved as a route. Update the timetable and try again."
}

/**
 * The saved trip for [itinerary] (call only when [saveBlocker] is null). [stationOf] maps the
 * planner's platform stop ids to the station the phone knows them under, so the route keeps
 * working when a platform changes; ids not in it are kept as they are.
 */
fun savedTripOf(itinerary: Itinerary, stationOf: Map<StopId, StopId> = emptyMap()): SavedTrip {
    val legs = itinerary.legs
    val rideIdx = legs.indices.filter { legs[it].kind == LegKind.RIDE }
    require(rideIdx.isNotEmpty()) { "no rides" }
    val tripLegs = rideIdx.mapIndexed { k, i ->
        val ride = legs[i]
        val from = ride.from.stopId ?: error("ride without stop ids")
        val to = ride.to.stopId ?: error("ride without stop ids")
        val connection = if (k == 0) NO_WALK_CONNECTION_MINUTES else {
            val walks = (rideIdx[k - 1] + 1 until i).map { legs[it] }.filter { it.kind == LegKind.WALK }
            if (walks.isEmpty()) NO_WALK_CONNECTION_MINUTES else (walks.sumOf { it.durationS } + 59) / 60
        }
        TripLeg(
            originStopIds = setOf(stationOf[from] ?: from),
            destinationStopIds = setOf(stationOf[to] ?: to),
            routeIds = ride.routeId?.let { setOf(it) },
            minConnectionMinutes = connection,
        )
    }
    val first = legs[rideIdx.first()]
    val last = legs[rideIdx.last()]
    return SavedTrip(
        id = tripIdFor(tripLegs),
        originName = first.from.name,
        destinationName = last.to.name,
        legs = tripLegs,
    )
}

/**
 * The same route the other way: rides in reverse order, each from where it ended to where it
 * started, on the same lines; the walk at each change stays with that change.
 */
fun returnTripOf(trip: SavedTrip): SavedTrip {
    val n = trip.legs.size
    val legs = (0 until n).map { j ->
        val forward = trip.legs[n - 1 - j]
        TripLeg(
            originStopIds = forward.destinationStopIds,
            destinationStopIds = forward.originStopIds,
            routeIds = forward.routeIds,
            minConnectionMinutes = if (j == 0) NO_WALK_CONNECTION_MINUTES else trip.legs[n - j].minConnectionMinutes,
        )
    }
    return SavedTrip(
        id = tripIdFor(legs),
        originName = trip.destinationName,
        destinationName = trip.originName,
        legs = legs,
    )
}

/**
 * A stable id for a route pattern, so saving the same route again replaces it instead of adding
 * a copy: "route-213510.200060.T9".
 */
fun tripIdFor(legs: List<TripLeg>): String = "route-" + legs.joinToString("_") { l ->
    listOf(l.originStopIds.sorted().joinToString("+"), l.destinationStopIds.sorted().joinToString("+"), l.routeIds.orEmpty().sorted().joinToString("+"))
        .joinToString(".")
}

/** "Home to Work by T9" / "Strathfield to Central by T9 and T4" for the save dialog. */
fun routeDescription(fromName: String, toName: String, itinerary: Itinerary): String {
    val lines = itinerary.rides.mapNotNull { it.routeName }.distinct()
    val by = when (lines.size) {
        0 -> ""
        1 -> " by ${lines[0]}"
        else -> " by " + lines.dropLast(1).joinToString(", ") + " and " + lines.last()
    }
    return "$fromName to $toName$by"
}
