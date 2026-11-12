package app.transittracker.ui.feature.plan

import app.transittracker.model.Itinerary
import app.transittracker.model.ItineraryLeg
import app.transittracker.model.LegKind
import app.transittracker.model.Mode
import app.transittracker.model.SavedTrip
import app.transittracker.model.StopId
import app.transittracker.model.TripLeg
import app.transittracker.model.RouteOption
import app.transittracker.model.rides
import app.transittracker.model.routePattern
import app.transittracker.model.wayIdFor
import app.transittracker.ui.feature.places.routeTitleParts
import app.transittracker.ui.places.SavedPlace
import app.transittracker.ui.places.TripPlaces

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
 * The same route the other way: every way reversed (rides in reverse order, each from where it
 * ended to where it started, on the same lines; the walk at each change stays with that change).
 * A pinned way stays pinned the other way.
 */
fun returnTripOf(trip: SavedTrip): SavedTrip {
    val ways = trip.ways.map { w -> reverseLegs(w.legs).let { RouteOption(wayIdFor(it), it, w.label) } }
    val pinned = trip.ways.indexOfFirst { it.id == trip.pinnedWayId }.takeIf { it >= 0 }?.let { ways[it].id }
    val legs = ways.first().legs
    return SavedTrip(
        id = tripIdFor(legs),
        originName = trip.destinationName,
        destinationName = trip.originName,
        legs = legs,
        pinnedWayId = pinned,
    ).withWays(ways)
}

private fun reverseLegs(forward: List<TripLeg>): List<TripLeg> {
    val n = forward.size
    return (0 until n).map { j ->
        val f = forward[n - 1 - j]
        TripLeg(
            originStopIds = f.destinationStopIds,
            destinationStopIds = f.originStopIds,
            routeIds = f.routeIds,
            minConnectionMinutes = if (j == 0) NO_WALK_CONNECTION_MINUTES else forward[n - j].minConnectionMinutes,
        )
    }
}

/**
 * A stable id for a route pattern, so saving the same route again replaces it instead of adding
 * a copy: "route-213510.200060.T9".
 */
fun tripIdFor(legs: List<TripLeg>): String = "route-" + routePattern(legs)

/**
 * One saved route made of the ways of [trips] (each made by [savedTripOf], in order; duplicates
 * dropped). Its ends are named after the stations when every way starts (or ends) at the same
 * one, else after the journey's ends ([fromName], [toName]: "Home", "12 Smith St").
 */
fun combineWays(trips: List<SavedTrip>, fromName: String, toName: String): SavedTrip {
    require(trips.isNotEmpty()) { "no ways" }
    val first = trips.first()
    if (trips.size == 1) return first
    val ways = trips.flatMap { it.ways }
    val sameStart = ways.map { it.legs.first().originStopIds }.distinct().size == 1
    val sameEnd = ways.map { it.legs.last().destinationStopIds }.distinct().size == 1
    return first.copy(
        originName = if (sameStart) first.originName else fromName,
        destinationName = if (sameEnd) first.destinationName else toName,
    ).withWays(ways)
}

/**
 * The saved route that [trip] would join as another way, or null: the route with the same id
 * (the same pattern saved before), else one between the same ends. A saved place at an end must
 * be the same place; an end that is not a saved place must share a station with one of the
 * route's ways.
 */
fun routeToJoin(
    trips: List<SavedTrip>,
    links: Map<String, TripPlaces>,
    trip: SavedTrip,
    fromPlaceId: String?,
    toPlaceId: String?,
): SavedTrip? {
    trips.firstOrNull { it.id == trip.id }?.let { return it }
    val starts = trip.ways.flatMapTo(HashSet()) { it.legs.first().originStopIds }
    val ends = trip.ways.flatMapTo(HashSet()) { it.legs.last().destinationStopIds }
    return trips.firstOrNull { t ->
        val link = links[t.id]
        val samePlaces = link?.fromPlaceId == fromPlaceId && link?.toPlaceId == toPlaceId
        samePlaces && t.ways.isNotEmpty() &&
            (fromPlaceId != null || t.ways.any { w -> w.legs.first().originStopIds.any { it in starts } }) &&
            (toPlaceId != null || t.ways.any { w -> w.legs.last().destinationStopIds.any { it in ends } })
    }
}

/** True when every way of [trip] is already a way of [route]. */
fun hasAllWays(route: SavedTrip, trip: SavedTrip): Boolean {
    val ids = route.ways.mapTo(HashSet()) { it.id }
    return trip.ways.all { it.id in ids }
}

/** The title of [route] for "Add as another way to Home to Work": place names, else its name or stations. */
fun joinTitle(route: SavedTrip, link: TripPlaces?, places: List<SavedPlace>): String {
    val from = link?.fromPlaceId?.let { id -> places.firstOrNull { it.id == id } }
    val to = link?.toPlaceId?.let { id -> places.firstOrNull { it.id == id } }
    return routeTitleParts(route, from, to).joinToString(" to ")
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
