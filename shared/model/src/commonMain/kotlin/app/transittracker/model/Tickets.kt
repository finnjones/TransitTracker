package app.transittracker.model

import kotlinx.serialization.Serializable

/*
 * TICKETS (front-end plan 5.6 "Tickets"): a journey the user set off on with GO, followed for this
 * one trip and then gone. A saved route is like an Opal card (kept, used every day); a ticket is
 * issued when the user sets off, stamped when they arrive, and torn off a few minutes later.
 *
 * A ticket holds a [SavedTrip] (the pattern: which stops, which lines, where to change), so every
 * departure, live and alert lookup that takes a trip id works on a ticket's id too, and a missed
 * service rolls on to the next run of the same pattern. Tickets live on the phone only: they are
 * never synced to the user's account.
 */

/** Where a ticket's journey starts or ends: a saved place, an address, a stop, or where the user was. */
@Serializable
data class TicketEnd(
    val name: String,
    val position: LatLng,
    /** The saved place this end is, when it is one (for leave-by from the place). */
    val savedPlaceId: String? = null,
)

@Serializable
enum class TicketStatus {
    /** Issued, the first ride not yet boarded. */
    WAITING,
    /** Follow my journey has the user on one of its rides. */
    ON_THE_WAY,
    /** The last ride arrived: shown as a stamped stub (KEEP AS ROUTE / DONE) for [TICKET_ARRIVED_SHOWN_MS]. */
    ARRIVED,
}

@Serializable
data class Ticket(
    /** The pattern followed; its id is [ticketIdFor] its legs (it starts with [TICKET_ID_PREFIX]). */
    val trip: SavedTrip,
    val from: TicketEnd,
    val to: TicketEnd,
    /** The services of the run the user picked, one per ride; Home headlines that run until it leaves. */
    val plannedRides: List<ServiceRef> = emptyList(),
    /** When the picked run's first ride leaves (expected, as planned). */
    val plannedDepartureMs: Long,
    /** When the picked run's last ride arrives (expected, as planned). */
    val plannedArrivalMs: Long,
    val issuedAtMs: Long,
    val status: TicketStatus = TicketStatus.WAITING,
    /** When the last ride arrived; set with [TicketStatus.ARRIVED]. */
    val arrivedAtMs: Long? = null,
) {
    val id: String get() = trip.id
}

const val TICKET_ID_PREFIX = "ticket-"

/** The arrived stub stays this long on Home. */
const val TICKET_ARRIVED_SHOWN_MS = 10 * 60_000L

/** A ticket not yet arrived goes this long after the planned departure (waiting) or arrival (on the way). */
const val TICKET_GRACE_MS = 90 * 60_000L

/** True when [tripId] is a ticket's, not a saved route's. */
fun isTicketId(tripId: String?): Boolean = tripId?.startsWith(TICKET_ID_PREFIX) == true

/** The id of a ticket for [legs]: "ticket-" and their [routePattern]. GO on the same pattern again replaces it. */
fun ticketIdFor(legs: List<TripLeg>): String = TICKET_ID_PREFIX + routePattern(legs)

/** When this ticket is torn off and forgotten. */
fun Ticket.expiresAtMs(): Long = when (status) {
    TicketStatus.ARRIVED -> (arrivedAtMs ?: plannedArrivalMs) + TICKET_ARRIVED_SHOWN_MS
    TicketStatus.ON_THE_WAY -> plannedArrivalMs + TICKET_GRACE_MS
    TicketStatus.WAITING -> plannedDepartureMs + TICKET_GRACE_MS
}

/** True when this ticket has expired at [nowMs]. */
fun Ticket.isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs()
