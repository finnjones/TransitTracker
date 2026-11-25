package app.transittracker.ui.feature.plan

import app.transittracker.model.Itinerary
import app.transittracker.model.SavedTrip
import app.transittracker.model.Ticket
import app.transittracker.model.TicketEnd
import app.transittracker.model.rides
import app.transittracker.model.ticketIdFor
import app.transittracker.ui.nav.JourneyEnd

/*
 * GO (plan 5.6 "Tickets"): a planned journey becomes a ticket, a pattern followed for this one
 * trip and then torn off. Pure; the state holder does the checks (the same as SAVE ROUTE) and the
 * storing.
 */

/** A ticket end from one end of a planned journey. */
fun ticketEndOf(end: JourneyEnd): TicketEnd = TicketEnd(end.name, end.position, end.savedPlaceId)

/** The journey end of a ticket end (to plan from it again, or to save the ticket as a route). */
fun journeyEndOf(end: TicketEnd): JourneyEnd = JourneyEnd(end.name, end.position, savedPlaceId = end.savedPlaceId)

/**
 * The ticket GO issues for [itinerary] between [from] and [to] at [nowMs]. [pattern] is the
 * itinerary as a saved trip ([savedTripOf]); the ticket keeps it under its own id ([ticketIdFor]),
 * so a missed service rolls on to the next run of the same pattern. The run picked is the
 * itinerary's: one [app.transittracker.model.ServiceRef] per ride from the first, as far as the
 * rides are matched to the timetable; its times are the planner's (expected, else scheduled).
 */
fun ticketOf(itinerary: Itinerary, pattern: SavedTrip, from: JourneyEnd, to: JourneyEnd, nowMs: Long): Ticket {
    val rides = itinerary.rides
    require(rides.isNotEmpty()) { "no rides" }
    val first = rides.first().from
    val last = rides.last().to
    return Ticket(
        trip = pattern.copy(id = ticketIdFor(pattern.legs), folder = null, order = 0, customName = null),
        from = ticketEndOf(from),
        to = ticketEndOf(to),
        // One per ride, in order: stop at the first ride that is not matched, so the indices still line up.
        plannedRides = rides.takeWhile { it.serviceRef != null }.map { it.serviceRef!! },
        plannedDepartureMs = first.expectedMs ?: first.scheduledMs,
        plannedArrivalMs = last.expectedMs ?: last.scheduledMs,
        issuedAtMs = nowMs,
    )
}

/**
 * True when GO should not issue a ticket for [trip] because a saved route already has it: [joinTo]
 * (from [routeToJoin]) is the same pattern saved before, or a route between the same ends that
 * already has every way of it. Home then shows that route's card, and Follow my journey follows it.
 */
fun alreadySaved(trip: SavedTrip, joinTo: SavedTrip?): Boolean =
    joinTo != null && (joinTo.id == trip.id || hasAllWays(joinTo, trip))

/** The route KEEP AS ROUTE saves for [ticket]: its pattern under a saved route's id ([tripIdFor]). */
fun routeOfTicket(ticket: Ticket): SavedTrip = ticket.trip.copy(id = tripIdFor(ticket.trip.legs))
