package app.transittracker.ui.feature.home

import app.transittracker.model.Departure
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Journey
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceRef
import app.transittracker.model.TICKET_ARRIVED_SHOWN_MS
import app.transittracker.model.TICKET_GRACE_MS
import app.transittracker.model.Ticket
import app.transittracker.model.TicketEnd
import app.transittracker.model.TicketStatus
import app.transittracker.model.TripLeg
import app.transittracker.model.ticketIdFor
import app.transittracker.ui.feature.follow.FollowMatch
import app.transittracker.ui.feature.follow.FollowStage
import app.transittracker.ui.places.PlaceIcon
import app.transittracker.ui.places.PlaceStop
import app.transittracker.ui.places.SavedPlace
import app.transittracker.ui.settings.CardDensity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Tickets on Home (plan 5.6 "Tickets"): the headline run, the card above the folders, the arrived stub. */
class TicketCardTest {
    private val min = 60_000L
    private val now = 1_000_000_000L
    private val today = "20261111"

    private val legs = listOf(TripLeg(setOf("S"), setOf("C"), routeIds = setOf("T9")))
    private val id = ticketIdFor(legs)

    private fun dep(trip: String, atMin: Int, status: DepartureStatus = DepartureStatus.ON_TIME) = Departure(
        ref = ServiceRef(trip, today), routeId = "T9", routeName = "T9", routeColor = "D11F2F", mode = Mode.TRAIN,
        headsign = "Gordon", originStopId = "S-P3", originPlatform = "3", platformChanged = false,
        scheduledDepartureMs = now + atMin * min, expectedDepartureMs = now + atMin * min,
        destinationStopId = "C-P3", scheduledArrivalMs = now + (atMin + 14) * min, expectedArrivalMs = now + (atMin + 14) * min,
        delaySeconds = 0, status = status,
    )

    private fun ticket(picked: String?, status: TicketStatus = TicketStatus.WAITING, arrivedAtMs: Long? = null) = Ticket(
        trip = SavedTrip(id, "Strathfield", "Central", legs),
        from = TicketEnd("Home", LatLng(0.0, 0.0), savedPlaceId = "home"),
        to = TicketEnd("Pitt St", LatLng(1.0, 1.0)),
        plannedRides = listOfNotNull(picked?.let { ServiceRef(it, today) }),
        plannedDepartureMs = now + 6 * min,
        plannedArrivalMs = now + 20 * min,
        issuedAtMs = now - min,
        status = status,
        arrivedAtMs = arrivedAtMs,
    )

    private val home = SavedPlace("home", "Home", PlaceIcon.HOME, "Albert Rd", LatLng(0.0, 0.0), listOf(PlaceStop("S", "Strathfield", 3, 250)))

    private fun inputs(tickets: List<Ticket>, upcoming: Map<String, List<Journey>>, trips: List<SavedTrip> = emptyList(), follow: Followed? = null) =
        HomeInputs(trips, upcoming, null, CardDensity.COMFORTABLE, listOf(home), emptyMap(), follow = follow, tickets = tickets)

    @Test
    fun thePickedRunHeadlinesUntilItLeavesThenTheNextOne() {
        val a = Journey(listOf(dep("a", 2)))
        val b = Journey(listOf(dep("b", 6)))
        val c = Journey(listOf(dep("c", 12)))
        val list = listOf(a, b, c)
        // GO picked the 6-minute run: it headlines although an earlier one leaves first.
        assertSame(b, ticketHeadline(ticket("b"), list, now))
        // Once it has left, the next running one.
        assertSame(c, ticketHeadline(ticket("b"), listOf(c), now + 7 * min))
        assertSame(c, ticketHeadline(ticket("b"), list, now + 7 * min))
        // Cancelled: the next running one, never the cancelled run.
        val bCancelled = Journey(listOf(dep("b", 6, DepartureStatus.CANCELLED)))
        assertSame(a, ticketHeadline(ticket("b"), listOf(a, bCancelled, c), now))
        // No run picked (the planner's ride was not matched): the next one.
        assertSame(a, ticketHeadline(ticket(null), list, now))
        assertNull(ticketHeadline(ticket("b"), emptyList(), now))
    }

    @Test
    fun aTicketIsCardedAboveTheFoldersWithItsEndsAsTitle() {
        val b = Journey(listOf(dep("b", 6)))
        val c = Journey(listOf(dep("c", 12)))
        val s = buildHomeState(inputs(listOf(ticket("b")), mapOf(id to listOf(b, c))), now, today)
        assertFalse(s.isEmpty, "a ticket alone is not the empty Home")
        assertTrue(s.folders.isEmpty())
        val t = s.tickets.single()
        assertFalse(t.arrived)
        assertTrue(t.pickedRun)
        assertEquals(listOf("Home", "Pitt St"), t.card.titleParts)
        assertEquals("STRATHFIELD TO CENTRAL", t.card.stationsLine)
        assertSame(b, t.card.headline)
        assertEquals(listOf(c), t.card.following)
        // Leave-by from the saved place the ticket starts at: 3 minutes' walk to Strathfield.
        val lb = assertNotNull(t.card.leaveBy)
        assertEquals(now + 3 * min, lb.leaveAtMs)
        assertNull(t.card.onTheWay)
    }

    @Test
    fun aMissedRunShowsTheNextOne() {
        val c = Journey(listOf(dep("c", 12)))
        val t = buildHomeState(inputs(listOf(ticket("b")), mapOf(id to listOf(c))), now + 7 * min, today).tickets.single()
        assertSame(c, t.card.headline)
        assertFalse(t.pickedRun)
    }

    @Test
    fun followedTicketsShowTheOnTheWayCard() {
        val b = Journey(listOf(dep("b", -2)))
        val follow = Followed(FollowMatch(id, b.departure.ref, 0, FollowStage.RIDING, 0.0), b)
        val trip = SavedTrip("trip-x", "Central", "Strathfield", listOf(TripLeg(setOf("C"), setOf("S"))))
        val s = buildHomeState(
            inputs(listOf(ticket("b", TicketStatus.ON_THE_WAY)), mapOf(id to emptyList()), trips = listOf(trip), follow = follow),
            now, today,
        )
        val otw = assertNotNull(s.tickets.single().card.onTheWay)
        assertSame(b, otw.journey)
        // The ticket is not a saved trip: the folders are as they were, none of them on the way.
        assertEquals(listOf("trip-x"), s.folders.flatMap { f -> f.cards }.map { it.trip.id })
        assertTrue(s.folders.flatMap { f -> f.cards }.all { it.onTheWay == null })
    }

    @Test
    fun anArrivedTicketIsAStubUntilItExpires() {
        val arrived = ticket("b", TicketStatus.ARRIVED, arrivedAtMs = now - 3 * min)
        val s = buildHomeState(inputs(listOf(arrived), emptyMap()), now, today)
        val t = s.tickets.single()
        assertTrue(t.arrived)
        assertEquals(now - 3 * min, t.arrivedAtMs)
        // Not arrived yet as recorded: the planned arrival stamps it.
        assertEquals(now + 20 * min, TicketCard(arrived.copy(arrivedAtMs = null), t.card, false).arrivedAtMs)
        // Ten minutes after arriving it is torn off, even before the core drops it.
        val later = buildHomeState(inputs(listOf(arrived), emptyMap()), now - 3 * min + TICKET_ARRIVED_SHOWN_MS, today)
        assertTrue(later.tickets.isEmpty())
        assertTrue(later.isEmpty)
    }

    @Test
    fun anUnusedTicketGoesNinetyMinutesAfterItsPlannedDeparture() {
        val t = ticket("b")
        assertEquals(1, buildHomeState(inputs(listOf(t), emptyMap()), t.plannedDepartureMs + TICKET_GRACE_MS - 1, today).tickets.size)
        assertTrue(buildHomeState(inputs(listOf(t), emptyMap()), t.plannedDepartureMs + TICKET_GRACE_MS, today).tickets.isEmpty())
    }
}
