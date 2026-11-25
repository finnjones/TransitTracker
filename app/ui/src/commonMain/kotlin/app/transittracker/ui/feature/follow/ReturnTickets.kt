package app.transittracker.ui.feature.follow

import app.transittracker.model.Journey
import app.transittracker.model.LatLng
import app.transittracker.model.SavedTrip
import app.transittracker.model.Ticket
import app.transittracker.model.TicketEnd
import app.transittracker.model.TicketStatus
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.model.impliedReturn
import app.transittracker.model.isReturnId
import app.transittracker.model.routeOfReturn
import app.transittracker.model.ticketIdFor
import app.transittracker.model.wayIdFor
import app.transittracker.ui.places.PlacesStore
import app.transittracker.ui.places.SavedPlace
import app.transittracker.ui.places.TripPlaces
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/*
 * THE WAY BACK, FOLLOWED: Follow my journey also looks for the user on the way back of each saved
 * route ([app.transittracker.model.impliedReturns]). Once it follows one, the user is making that
 * trip now, so it becomes a ticket, as if they had set off on it with GO: Home shows it at the
 * top with the ride they are on and the one they change to, it is stamped when they arrive, and
 * KEEP AS ROUTE saves it. Its ends are the route's ends swapped: its saved places when it has
 * them, else its stations.
 */

/**
 * The ends of the way back of [route] (linked to saved places by [link]): the place the route
 * ends at, else its last station ([boardAt], where the way back is boarded), to the place it
 * starts at, else its first station ([alightAt]). Null when a station end has no position.
 */
fun returnEnds(
    route: SavedTrip,
    link: TripPlaces?,
    places: List<SavedPlace>,
    boardAt: LatLng?,
    alightAt: LatLng?,
): Pair<TicketEnd, TicketEnd>? {
    fun place(id: String?) = id?.let { p -> places.firstOrNull { it.id == p } }?.let { TicketEnd(it.name, it.position, it.id) }
    val from = place(link?.toPlaceId) ?: boardAt?.let { TicketEnd(route.destinationName, it) } ?: return null
    val to = place(link?.fromPlaceId) ?: alightAt?.let { TicketEnd(route.originName, it) } ?: return null
    return from to to
}

/**
 * The ticket for way back [back] that the user was seen on, riding [journey], at [nowMs]: the way
 * of [back] that [journey] follows, between [from] and [to], already on the way, with [journey]'s
 * rides as the run picked.
 */
fun returnTicketOf(back: SavedTrip, journey: Journey, from: TicketEnd, to: TicketEnd, nowMs: Long): Ticket {
    val way = back.way(journey.wayId) ?: back.ways.firstOrNull { it.id in journey.alsoWays } ?: back.ways.first()
    return Ticket(
        trip = SavedTrip(ticketIdFor(way.legs), back.originName, back.destinationName, way.legs),
        from = from,
        to = to,
        plannedRides = journey.legs.map { it.ref },
        plannedDepartureMs = journey.departure.expectedDepartureMs,
        plannedArrivalMs = journey.arrival.expectedArrivalMs,
        issuedAtMs = nowMs,
        status = TicketStatus.ON_THE_WAY,
    )
}

/**
 * Issues a ticket ([returnTicketOf]) whenever [JourneyFollower.latest] follows the way back of a
 * saved route, and has the follower carry on with the ticket. Run with [TicketKeeper] while the
 * app runs; it reads [JourneyFollower.latest], so it never asks for location itself.
 */
class ReturnTickets(
    private val core: TransitCore,
    private val follower: JourneyFollower,
    private val places: PlacesStore,
    private val clock: WallClock,
) {
    /** Issues tickets until cancelled. */
    suspend fun run() {
        follower.latest
            .map { s -> s.match?.takeIf { isReturnId(it.tripId) } }
            .distinctUntilChanged { a, b -> a?.tripId == b?.tripId && a?.key == b?.key && a?.wayId == b?.wayId }
            .collect { m -> if (m != null) runCatching { issue(m) } }
    }

    private suspend fun issue(m: FollowMatch) {
        val trips = core.trips.first()
        val back = impliedReturn(trips, m.tripId) ?: return
        val route = routeOfReturn(trips, m.tripId) ?: return
        val mine = core.underway(m.tripId).first().filter { it.departure.ref == m.key }
        val journey = mine.firstOrNull { m.wayId != null && (it.wayId == m.wayId || m.wayId in it.alsoWays) } ?: mine.firstOrNull() ?: return
        suspend fun at(leg: Int, board: Boolean): LatLng? {
            val d = journey.legs.getOrNull(leg) ?: return null
            val stop = if (board) d.originStopId else d.destinationStopId
            return core.service(d.ref).first().calls.firstOrNull { it.stopId == stop }?.position
        }
        val link = places.tripPlaces.first()[route.id]
        val (from, to) = returnEnds(route, link, places.currentPlaces(), at(0, board = true), at(journey.legs.lastIndex, board = false)) ?: return
        val ticket = returnTicketOf(back, journey, from, to, clock.nowMillis())
        core.saveTicket(ticket)
        // Carry on with the ticket only while the follower is still on this way back.
        val now = follower.latest.value.match
        if (now != null && now.tripId == m.tripId && now.key == m.key) {
            follower.switchTo(now.copy(tripId = ticket.id, wayId = wayIdFor(ticket.trip.legs)))
        }
    }
}
