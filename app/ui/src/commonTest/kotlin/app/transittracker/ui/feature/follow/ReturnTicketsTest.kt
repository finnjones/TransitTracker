package app.transittracker.ui.feature.follow

import app.transittracker.core.FakeData
import app.transittracker.model.LatLng
import app.transittracker.model.SavedTrip
import app.transittracker.model.StopCall
import app.transittracker.model.TicketStatus
import app.transittracker.model.impliedReturns
import app.transittracker.model.returnIdOf
import app.transittracker.model.reverseLegs
import app.transittracker.model.reversedTrip
import app.transittracker.model.ticketIdFor
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.feature.home.HomeStateHolder
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.places.TripPlaces
import app.transittracker.ui.platform.FakePlatformServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The way back of a saved route (FakeTransitCore at Wed 11 Nov 07:38): a route saved one way is
 * looked for the other way too, and riding it issues a ticket that Home shows on the way, with
 * the ride the user is on and the one they change to.
 */
class ReturnTicketsTest {
    private val route = FakeSetup.trips.first { it.id == FakeSetup.TRIP_TOWNHALL_TALLAWONG }
    private val homeWork = FakeSetup.trips.first { it.id == FakeSetup.TRIP_HOME_WORK }

    @Test
    fun theWayBackIsTheRidesReversed() {
        val back = impliedReturns(listOf(route)).single()
        assertEquals(returnIdOf(route.id), back.id)
        assertEquals("Tallawong" to "Town Hall", back.originName to back.destinationName)
        assertEquals(
            listOf(setOf(FakeData.TALLAWONG) to setOf(FakeData.CHATSWOOD), setOf(FakeData.CHATSWOOD) to setOf(FakeData.TOWN_HALL)),
            back.legs.map { it.originStopIds to it.destinationStopIds },
        )
        // The walk at the change stays with the change.
        assertEquals(4, back.legs[1].minConnectionMinutes)
    }

    @Test
    fun aWayBackAlreadySavedIsNotLookedFor() {
        // Strathfield to Central and Central to Strathfield are both saved: neither has a way back.
        val ids = impliedReturns(FakeSetup.trips).map { it.id }
        assertEquals(listOf(returnIdOf(FakeSetup.TRIP_CHATSWOOD_TALLAWONG), returnIdOf(FakeSetup.TRIP_TOWNHALL_TALLAWONG)), ids)
        // Saved as a way of another route counts too.
        val other = SavedTrip("route-x", "Tallawong", "Town Hall", reverseLegs(route.legs))
        assertTrue(impliedReturns(listOf(route, other)).none { it.id == returnIdOf(route.id) })
    }

    @Test
    fun theTicketEndsAreTheRouteEndsSwapped() {
        val link = TripPlaces(homeWork.id, FakeSetup.HOME, FakeSetup.WORK)
        val (from, to) = assertNotNull(returnEnds(homeWork, link, FakeSetup.places, null, null))
        assertEquals("Work" to FakeSetup.WORK, from.name to from.savedPlaceId)
        assertEquals("Home" to FakeSetup.HOME, to.name to to.savedPlaceId)

        // Not linked to places: its stations, where the way back is boarded and left.
        val board = LatLng(-33.88, 151.20)
        val alight = LatLng(-33.87, 151.09)
        val (a, b) = assertNotNull(returnEnds(homeWork, null, FakeSetup.places, board, alight))
        assertEquals("Central" to board, a.name to a.position)
        assertEquals("Strathfield" to alight, b.name to b.position)
        assertNull(returnEnds(homeWork, null, FakeSetup.places, null, alight))
    }

    @Test
    fun ridingTheWayBackIsFollowedAsATicket() = runTest {
        val platform = FakePlatformServices(location = null)
        val deps = AppDeps.fake(platform = platform, trips = listOf(route))
        val backId = returnIdOf(route.id)
        // On the metro from Tallawong, the first ride of the way back.
        val journey = deps.core.underway(backId).first().first { SampleData.NOW_MS < it.legs[0].expectedArrivalMs }
        val ride = journey.legs[0]
        val calls = riddenCalls(deps.core.service(ride.ref).first().calls, ride.originStopId, ride.destinationStopId)
        platform.location.value = assertNotNull(positionAt(calls, SampleData.NOW_MS))

        val f = JourneyFollower(deps.core, platform, deps.settings, deps.clock, intervalMs = 1_000, idleIntervalMs = 1_000)
        val home = HomeStateHolder(deps.core, deps.places, deps.settings, deps.clock, backgroundScope, follower = f)
        backgroundScope.launch { home.state.collect {} }
        backgroundScope.launch { ReturnTickets(deps.core, f, deps.places, deps.clock).run() }

        val s = home.state.first { s -> s.tickets.any { it.card.onTheWay != null } }
        val t = s.tickets.single()
        assertEquals(ticketIdFor(reverseLegs(route.legs)), t.ticket.id)
        assertEquals(TicketStatus.ON_THE_WAY, t.ticket.status)
        assertEquals(listOf("Tallawong", "Town Hall"), t.card.titleParts)
        val otw = assertNotNull(t.card.onTheWay)
        assertEquals(FollowStage.RIDING to 0, otw.stage to otw.legIndex)
        // The next ride is the one changed to at Chatswood.
        assertEquals(journey.legs[1].ref, otw.next?.ref)
        assertEquals(t.ticket.id, f.latest.value.match?.tripId)
    }

    @Test
    fun aRouteWithItsWayBackSavedIsFollowedAsThatRoute() = runTest {
        val platform = FakePlatformServices(location = null)
        val saved = reversedTrip(route, "route-back").copy(order = 1)
        val deps = AppDeps.fake(platform = platform, trips = listOf(route, saved))
        val journey = deps.core.underway(saved.id).first().first { SampleData.NOW_MS < it.legs[0].expectedArrivalMs }
        val ride = journey.legs[0]
        val calls = riddenCalls(deps.core.service(ride.ref).first().calls, ride.originStopId, ride.destinationStopId)
        platform.location.value = assertNotNull(positionAt(calls, SampleData.NOW_MS))

        val f = JourneyFollower(deps.core, platform, deps.settings, deps.clock, intervalMs = 1_000, idleIntervalMs = 1_000)
        val s = f.state.first { it.status == FollowStatus.FOLLOWING }
        assertEquals(saved.id, s.match?.tripId)
    }

    private fun positionAt(calls: List<StopCall>, t: Long): LatLng? {
        val located = calls.filter { it.position != null }
        for (i in 0 until located.size - 1) {
            val dep = located[i].expectedDepartureMs ?: located[i].scheduledDepartureMs ?: continue
            val arr = located[i + 1].expectedArrivalMs ?: located[i + 1].scheduledArrivalMs ?: continue
            if (t in dep..arr) {
                val f = (t - dep).toDouble() / (arr - dep).coerceAtLeast(1)
                val p = located[i].position!!
                val q = located[i + 1].position!!
                return LatLng(p.lat + (q.lat - p.lat) * f, p.lon + (q.lon - p.lon) * f)
            }
        }
        return null
    }
}
