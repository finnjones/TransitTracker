package app.transittracker.ui.feature.follow

import app.transittracker.model.Departure
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Journey
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceRef
import app.transittracker.model.Ticket
import app.transittracker.model.TicketEnd
import app.transittracker.model.TicketStatus
import app.transittracker.model.TripLeg
import app.transittracker.model.ticketIdFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tickets in Follow my journey, without the follower: a ticket wins over a saved trip that fits
 * alike, a journey taken with TAKE THIS ONE keeps winning, and a ticket moves from waiting to on
 * the way to arrived.
 */
class TicketFollowTest {
    private val min = 60_000L
    private val t0 = 1_800_000_000_000L
    private fun at(m: Int) = t0 + m * min

    private val saved = "trip-home-work"
    private val ticketId = ticketIdFor(listOf(TripLeg(setOf("STR"), setOf("CEN"))))
    private val train = ServiceRef("T1-0800", "20261111")
    private val bus = ServiceRef("400-0750", "20261111")

    private fun fit(trip: String, key: ServiceRef = train, off: Double = 10.0, leg: Int = 0, way: String? = null) =
        FollowMatch(trip, key, leg, FollowStage.RIDING, off, way)

    @Test
    fun aTicketOnTheSameVehicleBeatsASavedTrip() {
        // The saved trip's fit is a little closer, but it is the same train: the user set off on the ticket.
        val mine = fit(ticketId, off = 30.0)
        val route = fit(saved, off = 10.0)
        assertEquals(mine, preferredFit(listOf(route, mine), null))
        assertEquals(mine, preferredFit(listOf(route, mine), null, mapOf(train to 1.0)))
    }

    @Test
    fun aTicketTakesOverFromASavedTripFollowedOnTheSameVehicle() {
        val route = fit(saved)
        val mine = fit(ticketId, off = 40.0)
        assertEquals(mine, preferredFit(listOf(route, mine), held = route, scores = mapOf(train to 1.5)))
        // Once the ticket is followed, a saved trip on its vehicle does not take it back.
        assertEquals(mine, preferredFit(listOf(fit(saved, off = 5.0), mine), held = mine, scores = mapOf(train to 1.5)))
    }

    @Test
    fun aJourneyTakenWithTakeThisOneKeepsWinning() {
        val oneOff = fit(saved, way = oneOffWayId("plan-1"))
        val mine = fit(ticketId)
        assertEquals(oneOff, preferredFit(listOf(mine, oneOff), held = oneOff, scores = mapOf(train to 1.5)))
        // Ways sharing the vehicle still read alike: the one taken stays chosen.
        val savedWay = fit(saved, way = "way-a", off = 1.0)
        assertEquals(oneOff, preferredFit(listOf(savedWay, oneOff), held = oneOff, scores = mapOf(train to 1.5)))
    }

    @Test
    fun aTicketBoardingTheSameTrainLaterIsFollowedOnceItsFixesAddUp() {
        // The saved trip boards the train first; the ticket rides a bus to it (its first service is the bus).
        var mem = FollowMemory()
        val keys = setOf(train, bus)
        repeat(4) {
            val fits = listOf(fit(saved, key = train), fit(ticketId, key = bus, leg = 1))
            val evidence = mapOf(train to 0.5, bus to 0.5)
            mem = mem.next(preferredFit(fits, mem.pinned, mem.scores), keys, evidence)
        }
        assertEquals(ticketId, mem.pinned?.tripId)
        assertEquals(bus, mem.pinned?.key)

        // A saved trip followed for a while gives way once the ticket's fixes add up as well.
        mem = FollowMemory(fit(saved), mapOf(train to 1.5, bus to 0.0))
        repeat(3) {
            val fits = listOf(fit(saved, key = train), fit(ticketId, key = bus, leg = 1))
            mem = mem.next(preferredFit(fits, mem.pinned, mem.scores), keys, mapOf(train to 0.5, bus to 0.5))
        }
        // Not until the ticket is as sure as the saved trip.
        assertEquals(saved, mem.pinned?.tripId)
        repeat(1) {
            val fits = listOf(fit(saved, key = train), fit(ticketId, key = bus, leg = 1))
            mem = mem.next(preferredFit(fits, mem.pinned, mem.scores), keys, mapOf(train to 0.5, bus to 0.5))
        }
        assertEquals(ticketId, mem.pinned?.tripId)
    }

    @Test
    fun aTicketThatDoesNotFitDoesNotWin() {
        // The fixes back the saved trip's journey; the ticket's (another train) fits in place but not in speed.
        var mem = FollowMemory(fit(saved), mapOf(train to 1.5))
        val other = ServiceRef("T1-0812", "20261111")
        repeat(4) {
            val fits = listOf(fit(saved), fit(ticketId, key = other))
            mem = mem.next(preferredFit(fits, mem.pinned, mem.scores), setOf(train, other), mapOf(train to 0.5, other to -0.25))
        }
        assertEquals(saved, mem.pinned?.tripId)
    }

    // ---- the ticket's lifecycle ----

    private fun ticket(status: TicketStatus = TicketStatus.WAITING) = Ticket(
        trip = SavedTrip(ticketId, "Strathfield", "Central", listOf(TripLeg(setOf("STR"), setOf("CEN")))),
        from = TicketEnd("Strathfield", LatLng(-33.87, 151.09)),
        to = TicketEnd("Central", LatLng(-33.88, 151.21)),
        plannedDepartureMs = at(0),
        plannedArrivalMs = at(20),
        issuedAtMs = at(-5),
        status = status,
    )

    private val journey = Journey(
        listOf(
            Departure(
                train, "R-T1", "T1", null, Mode.TRAIN, "Central", "STR", null, false,
                at(0), at(1), "CEN", at(20), at(22), 60, DepartureStatus.LATE,
            ),
        ),
        "way-a",
    )

    private fun following(trip: String) = FollowState(FollowStatus.FOLLOWING, fit(trip))
    private val looking = FollowState(FollowStatus.LOOKING)

    @Test
    fun aTicketGoesOnTheWayWhenFollowedThenArrives() {
        val waiting = ticket()
        // Nothing followed, or a saved trip: still waiting.
        assertTrue(ticketChanges(listOf(waiting), looking, emptyMap(), at(2)).isEmpty())
        assertTrue(ticketChanges(listOf(waiting), following(saved), emptyMap(), at(2)).isEmpty())

        // Followed: on the way, and the journey's arrival is kept.
        val onTheWay = ticketChanges(listOf(waiting), following(ticketId), emptyMap(), at(2)).single()
        assertEquals(TicketStatus.ON_THE_WAY, onTheWay.status)
        var arrivals = keptArrivals(emptyMap(), following(ticketId), journey, at(2))
        assertEquals(mapOf(ticketId to at(22)), arrivals)

        // Before it arrives, nothing changes.
        assertTrue(ticketChanges(listOf(onTheWay), following(ticketId), arrivals, at(21)).isEmpty())

        // The follower lets go as the journey stops being under way; the kept arrival stamps the ticket.
        arrivals = keptArrivals(arrivals, looking, null, at(22))
        val arrived = ticketChanges(listOf(onTheWay), looking, arrivals, at(22)).single()
        assertEquals(TicketStatus.ARRIVED, arrived.status)
        assertEquals(at(22), arrived.arrivedAtMs)

        // Arrived tickets do not change again.
        assertTrue(ticketChanges(listOf(arrived), following(ticketId), arrivals, at(30)).isEmpty())
    }

    @Test
    fun aTicketFollowedAsItsJourneyArrivesGoesStraightThrough() {
        // While the app was hidden the follower kept the journey; the clock says it has arrived.
        val arrivals = mapOf(ticketId to at(22))
        val arrived = ticketChanges(listOf(ticket()), following(ticketId), arrivals, at(23)).single()
        assertEquals(TicketStatus.ARRIVED, arrived.status)
        assertEquals(at(22), arrived.arrivedAtMs)
    }

    @Test
    fun aJourneyLetGoLongBeforeItArrivesIsForgotten() {
        val arrivals = mapOf(ticketId to at(22))
        // Dropped at 07:05 (the fixes no longer fit): the user is not on it, so it is not stamped later.
        val forgotten = keptArrivals(arrivals, looking, null, at(5))
        assertTrue(forgotten.isEmpty())
        assertTrue(ticketChanges(listOf(ticket(TicketStatus.ON_THE_WAY)), looking, forgotten, at(30)).isEmpty())
        // Let go just before it arrives (the follower polls every few seconds): kept.
        assertEquals(arrivals, keptArrivals(arrivals, looking, null, at(21)))
        // Following switched off, or no location: no news, kept.
        assertEquals(arrivals, keptArrivals(arrivals, FollowState(FollowStatus.OFF), null, at(5)))
        assertEquals(arrivals, keptArrivals(arrivals, FollowState(FollowStatus.NO_LOCATION), null, at(5)))
    }
}
