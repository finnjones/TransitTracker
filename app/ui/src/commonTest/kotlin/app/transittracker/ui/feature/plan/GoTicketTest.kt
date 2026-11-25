package app.transittracker.ui.feature.plan

import app.transittracker.model.Itinerary
import app.transittracker.model.ItineraryLeg
import app.transittracker.model.LatLng
import app.transittracker.model.LegKind
import app.transittracker.model.LegPoint
import app.transittracker.model.Mode
import app.transittracker.model.PlanEndpoint
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanResult
import app.transittracker.model.RouteOption
import app.transittracker.model.ServiceRef
import app.transittracker.model.TicketStatus
import app.transittracker.model.isTicketId
import app.transittracker.model.rides
import app.transittracker.model.ticketIdFor
import app.transittracker.model.wayIdFor
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.feature.home.HomeStateHolder
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.nav.JourneyEnd
import app.transittracker.ui.nav.TakeFrom
import app.transittracker.ui.planning.FakeJourneyPlanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GO (plan 5.6 "Tickets"): a ticket from a planned journey, issued, kept as a route or torn off. */
class GoTicketTest {
    private val home = JourneyEnd("Home", FakeJourneyPlanner.HOME, savedPlaceId = FakeSetup.HOME)
    private val work = JourneyEnd("Work", FakeJourneyPlanner.PITT_ST, savedPlaceId = FakeSetup.WORK)
    private val campbell = JourneyEnd("Campbell Pde", FakeJourneyPlanner.CAMPBELL_PDE, plannerPlaceId = "fake:campbell-pde")

    private val min = 60_000L
    private val t0 = 1_000_000_000L

    private fun point(name: String, stop: String?, at: Long, expected: Long? = null) = LegPoint(name, LatLng(0.0, 0.0), stop, scheduledMs = at, expectedMs = expected)

    private fun ride(from: String, to: String, at: Long, mins: Int, trip: String?, delay: Long = 0) = ItineraryLeg(
        LegKind.RIDE, point(from, from, at, at + delay), point(to, to, at + mins * min, at + mins * min + delay), mins * 60,
        mode = Mode.TRAIN, routeName = "T9", routeId = "T9", tripId = trip, serviceDate = trip?.let { "20261111" },
    )

    private fun walk(at: Long, mins: Int) = ItineraryLeg(LegKind.WALK, point("a", null, at), point("b", null, at + mins * min), mins * 60)

    @Test
    fun aTicketKeepsThePatternTheEndsAndTheRunPicked() {
        val it = Itinerary(
            "i",
            listOf(walk(t0, 3), ride("S", "C", t0 + 3 * min, 14, "t1", delay = 2 * min), walk(t0 + 17 * min, 4), ride("C", "B", t0 + 21 * min, 10, "t2"), walk(t0 + 31 * min, 2)),
        )
        val pattern = savedTripOf(it)
        val t = ticketOf(it, pattern, home, campbell, nowMs = t0 - min)
        assertEquals(ticketIdFor(pattern.legs), t.id)
        assertTrue(isTicketId(t.id))
        assertEquals(pattern.legs, t.trip.legs)
        assertEquals("Home", t.from.name)
        assertEquals(FakeSetup.HOME, t.from.savedPlaceId)
        assertEquals("Campbell Pde", t.to.name)
        assertNull(t.to.savedPlaceId)
        assertEquals(listOf(ServiceRef("t1", "20261111"), ServiceRef("t2", "20261111")), t.plannedRides)
        // The first ride's expected departure (2 min late), the last ride's arrival; not the walks.
        assertEquals(t0 + 5 * min, t.plannedDepartureMs)
        assertEquals(t0 + 31 * min, t.plannedArrivalMs)
        assertEquals(t0 - min, t.issuedAtMs)
        assertEquals(TicketStatus.WAITING, t.status)
        // Kept as a route, it is the same pattern under a saved route's id.
        assertEquals(tripIdFor(pattern.legs), routeOfTicket(t).id)
        assertEquals(pattern.legs, routeOfTicket(t).legs)
    }

    @Test
    fun ridesNotMatchedToTheTimetableEndThePickedRun() {
        val it = Itinerary("i", listOf(ride("S", "C", t0, 14, "t1"), ride("C", "B", t0 + 20 * min, 10, null), ride("B", "D", t0 + 40 * min, 5, "t3")))
        val t = ticketOf(it, savedTripOf(it), home, work, t0)
        assertEquals(listOf(ServiceRef("t1", "20261111")), t.plannedRides, "one per ride from the first, so indices line up")
    }

    @Test
    fun aRouteThatAlreadyHasThePatternIsNotTicketed() {
        val it = Itinerary("i", listOf(ride("S", "C", t0, 14, "t1")))
        val trip = savedTripOf(it)
        assertFalse(alreadySaved(trip, null))
        assertTrue(alreadySaved(trip, trip.copy(customName = "Mine")), "the same pattern saved before")
        val other = trip.copy(id = "route-other", legs = listOf(trip.legs[0].copy(routeIds = setOf("T1"))))
        assertFalse(alreadySaved(trip, other), "a route between the same ends without this way")
        assertTrue(alreadySaved(trip, other.withWays(other.ways + RouteOption(wayIdFor(trip.legs), trip.legs))), "it has this way")
    }

    // ------------------------------------------------------------ the Journey page and Home

    private suspend fun homeWorkItinerary(deps: AppDeps) =
        (deps.planner.plan(PlanRequest(PlanEndpoint(home.position), PlanEndpoint(work.position))) as PlanResult.Found)
            .response.itineraries.first()

    @Test
    fun goIssuesATicketForTheRunOnThePage() = runTest {
        val deps = AppDeps.fake(trips = emptyList())
        val it = homeWorkItinerary(deps)
        val h = JourneyStateHolder(deps.core, deps.places, deps.clock, it, home, work, backgroundScope)
        val ready = h.state.first { s -> s.save is SaveState.Ready }
        assertTrue(ready.canGo)
        h.go()
        val gone = assertIs<GoState.Gone>(h.state.first { s -> s.go is GoState.Gone }.go)
        val ticket = deps.core.tickets.first { k -> k.isNotEmpty() }.single()
        assertEquals(ticket.id, gone.ticketId)
        assertEquals(ticketIdFor((ready.save as SaveState.Ready).trip.legs), ticket.id)
        assertEquals(it.rides.mapNotNull { r -> r.serviceRef }, ticket.plannedRides)
        assertEquals(FakeSetup.WORK, ticket.to.savedPlaceId)
        assertEquals(SampleData.NOW_MS, ticket.issuedAtMs)
        assertTrue(deps.core.trips.first().isEmpty(), "nothing saved as a route")
        // The ticket's id works like a trip's: Home finds its next services.
        assertTrue(deps.core.upcoming(ticket.id, 3).first { j -> j.isNotEmpty() }.isNotEmpty())
    }

    @Test
    fun goOnASavedRoutesPatternIssuesNothing() = runTest {
        val deps = AppDeps.fake(trips = emptyList())
        val it = homeWorkItinerary(deps)
        val first = JourneyStateHolder(deps.core, deps.places, deps.clock, it, home, work, backgroundScope)
        first.state.first { s -> s.save is SaveState.Ready }
        first.save(withReturn = false)
        first.state.first { s -> s.save is SaveState.Saved }
        val h = JourneyStateHolder(deps.core, deps.places, deps.clock, it, home, work, backgroundScope)
        h.state.first { s -> s.save is SaveState.Ready }
        h.go()
        val gone = assertIs<GoState.Gone>(h.state.first { s -> s.go is GoState.Gone }.go)
        assertNull(gone.ticketId, "Home shows the route's card instead")
        assertTrue(deps.core.tickets.first().isEmpty())
    }

    @Test
    fun aJourneyThatCannotBeSavedCannotGo() = runTest {
        val deps = AppDeps.fake()
        val it = FakeJourneyPlanner.itineraries(SampleData.NOW_MS / 60_000 * 60_000).first()
        val h = JourneyStateHolder(deps.core, deps.places, deps.clock, it, home, campbell, backgroundScope)
        h.state.first { s -> s.save is SaveState.Blocked }
        assertFalse(h.state.value.canGo)
        h.go()
        assertEquals(GoState.Idle, h.state.value.go)
        assertTrue(deps.core.tickets.first().isEmpty())
    }

    @Test
    fun homeShowsTheTicketAndKeepAsRouteSavesItThenTearsItOff() = runTest {
        val deps = AppDeps.fake(trips = emptyList())
        val it = homeWorkItinerary(deps)
        val j = JourneyStateHolder(deps.core, deps.places, deps.clock, it, home, work, backgroundScope)
        j.state.first { s -> s.save is SaveState.Ready }
        j.go()
        val gone = assertIs<GoState.Gone>(j.state.first { s -> s.go is GoState.Gone }.go)
        val id = assertNotNull(gone.ticketId)
        val home = HomeStateHolder(deps.core, deps.places, deps.settings, deps.clock, backgroundScope)
        val s = home.state.first { st -> !st.loading && st.tickets.any { t -> t.card.headline != null } }
        assertFalse(s.isEmpty)
        val card = s.tickets.single()
        assertEquals(listOf("Home", "Work"), card.card.titleParts)
        assertTrue(card.pickedRun)
        // Other ways from the next stop plan to the ticket's own end.
        assertEquals("Work", routeEnds(deps.core, deps.places, id)?.second?.name)
        home.keepTicket(id)
        val trips = deps.core.trips.first { t -> t.isNotEmpty() }
        assertEquals(listOf(routeOfTicket(card.ticket).id), trips.map { t -> t.id })
        assertEquals(FakeSetup.HOME, deps.places.tripPlaces.first { l -> trips[0].id in l }.getValue(trips[0].id).fromPlaceId)
        assertTrue(deps.core.tickets.first { k -> k.isEmpty() }.isEmpty())
        val after = home.state.first { st -> st.tickets.isEmpty() && st.folders.isNotEmpty() }
        assertEquals(trips[0].id, after.folders.single().cards.single().trip.id)
    }

    @Test
    fun doneTearsTheTicketOffAndATicketHasNoWayToAddTo() = runTest {
        val deps = AppDeps.fake(trips = emptyList())
        val it = homeWorkItinerary(deps)
        val pattern = savedTripOf(it)
        val ticket = ticketOf(it, pattern, home, work, SampleData.NOW_MS)
        deps.core.saveTicket(ticket)
        val saver = RouteSaver(deps.core, deps.places)
        val ride = assertNotNull(it.rides.first().serviceRef)
        val from = TakeFrom(ticket.id, null, 0, ride, "S", "C", LatLng(0.0, 0.0), changing = false)
        assertNull(saver.wayFromOrigin(from, it))
        val h = HomeStateHolder(deps.core, deps.places, deps.settings, deps.clock, backgroundScope)
        h.state.first { st -> st.tickets.isNotEmpty() }
        h.closeTicket(ticket.id)
        h.state.first { st -> !st.loading && st.tickets.isEmpty() }
        assertTrue(deps.core.trips.first().isEmpty())
    }
}
