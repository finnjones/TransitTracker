package app.transittracker.model

/*
 * THE WAY BACK: every saved route can be ridden the other way. Saving a route with its return is
 * a choice the user makes once; a route saved without one is still ridden home again. So Follow
 * my journey also looks for the user on the way back of each saved route ([impliedReturns]): the
 * same rides in reverse order, each from where it ended to where it started, on the same lines.
 *
 * A way back has its own id ([returnIdOf]: "return-" and the route's id), and every lookup that
 * takes a trip id ([TransitCore.upcoming], [TransitCore.underway], ...) also takes it, as it takes
 * a ticket's. It is never stored: the front end issues a ticket for it once the user is seen on it.
 */

const val RETURN_ID_PREFIX = "return-"

/** Minimum connection when nothing else is known (same platform or stop). Ignored for a first ride. */
const val DEFAULT_CONNECTION_MINUTES = 3

/** True when [tripId] is the way back of a saved route ([returnIdOf]), not a saved route or a ticket. */
fun isReturnId(tripId: String?): Boolean = tripId?.startsWith(RETURN_ID_PREFIX) == true

/** The id of the way back of saved route [tripId]. */
fun returnIdOf(tripId: String): String = RETURN_ID_PREFIX + tripId

/**
 * [legs] the other way: rides in reverse order, each from where it ended to where it started, on
 * the same lines. The walk at each change stays with that change.
 */
fun reverseLegs(legs: List<TripLeg>): List<TripLeg> {
    val n = legs.size
    return (0 until n).map { j ->
        val f = legs[n - 1 - j]
        TripLeg(
            originStopIds = f.destinationStopIds,
            destinationStopIds = f.originStopIds,
            routeIds = f.routeIds,
            minConnectionMinutes = if (j == 0) DEFAULT_CONNECTION_MINUTES else legs[n - j].minConnectionMinutes,
        )
    }
}

/**
 * [trip] the other way, under [id]: every way reversed ([reverseLegs]) with its label, the ends
 * swapped. A pinned way stays pinned the other way. Not in a folder, no custom name.
 */
fun reversedTrip(trip: SavedTrip, id: String): SavedTrip {
    val ways = trip.ways.map { w -> reverseLegs(w.legs).let { RouteOption(wayIdFor(it), it, w.label) } }
    require(ways.isNotEmpty()) { "a trip with no ways has no way back" }
    val pinned = trip.ways.indexOfFirst { it.id == trip.pinnedWayId }.takeIf { it >= 0 }?.let { ways[it].id }
    return SavedTrip(
        id = id,
        originName = trip.destinationName,
        destinationName = trip.originName,
        legs = ways.first().legs,
        pinnedWayId = pinned,
    ).withWays(ways)
}

/**
 * The way back ([returnIdOf]) of every saved route in [trips], with only the ways not already
 * saved: a way back already saved (as a route of its own, or as a way of one) is followed as that
 * route. Routes whose every way back is saved have none. Not pinned: the user is followed on
 * whichever way they take.
 */
fun impliedReturns(trips: List<SavedTrip>): List<SavedTrip> {
    val saved = trips.flatMapTo(HashSet()) { t -> t.ways.map { it.id } }
    return trips.mapNotNull { t ->
        if (t.ways.isEmpty() || isTicketId(t.id) || isReturnId(t.id)) return@mapNotNull null
        val back = reversedTrip(t, returnIdOf(t.id))
        val open = back.ways.filter { it.id !in saved }
        if (open.isEmpty()) null else back.copy(pinnedWayId = null).withWays(open)
    }
}

/** The way back [tripId] of one of [trips] ([impliedReturns]), or null. */
fun impliedReturn(trips: List<SavedTrip>, tripId: String): SavedTrip? =
    if (!isReturnId(tripId)) null else impliedReturns(trips).firstOrNull { it.id == tripId }

/** The saved route among [trips] whose way back is [returnId], or null. */
fun routeOfReturn(trips: List<SavedTrip>, returnId: String): SavedTrip? =
    if (!isReturnId(returnId)) null else trips.firstOrNull { returnIdOf(it.id) == returnId }
