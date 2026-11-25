package app.transittracker.ui.feature.follow

import app.transittracker.model.LatLng
import app.transittracker.model.StopCall
import app.transittracker.model.Ticket
import app.transittracker.model.TicketEnd
import app.transittracker.model.TicketStatus
import app.transittracker.model.WallClock
import app.transittracker.model.ticketIdFor
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.platform.FakePlatformServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tickets with the follower against FakeTransitCore at Wed 11 Nov 07:38: a ticket for the Town
 * Hall to Tallawong pattern rides the same T1 as the saved trip of that name, so the ticket is the
 * one followed, it is listed when the user is asked ON BOARD?, and [TicketKeeper] moves it from
 * waiting to on the way to arrived. An arrived ticket is not followed.
 */
class TicketFollowerTest {
    private val trip = FakeSetup.TRIP_TOWNHALL_TALLAWONG
    private val saved = FakeSetup.trips.first { it.id == trip }
    private val ticketId = ticketIdFor(saved.legs)

    private fun ticket(status: TicketStatus = TicketStatus.WAITING, arrivalMs: Long = SampleData.NOW_MS + 60 * 60_000L) = Ticket(
        trip = saved.copy(id = ticketId, folder = null),
        from = TicketEnd("Town Hall", LatLng(-33.8731, 151.2067)),
        to = TicketEnd("Tallawong", LatLng(-33.6914, 150.9060)),
        plannedDepartureMs = SampleData.NOW_MS - 5 * 60_000L,
        plannedArrivalMs = arrivalMs,
        issuedAtMs = SampleData.NOW_MS - 10 * 60_000L,
        status = status,
        arrivedAtMs = if (status == TicketStatus.ARRIVED) SampleData.NOW_MS else null,
    )

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

    private fun follower(deps: AppDeps, platform: FakePlatformServices) =
        JourneyFollower(deps.core, platform, deps.settings, deps.clock, intervalMs = 1_000, idleIntervalMs = 1_000)

    /** Puts the phone where the saved trip's T1 under way should be now. */
    private suspend fun onTheTrain(deps: AppDeps, platform: FakePlatformServices, nowMs: Long = SampleData.NOW_MS) {
        val journey = deps.core.underway(trip).first().first { nowMs < it.legs[0].expectedArrivalMs }
        val train = journey.legs[0]
        val calls = riddenCalls(deps.core.service(train.ref).first().calls, train.originStopId, train.destinationStopId)
        platform.location.value = assertNotNull(positionAt(calls, nowMs))
    }

    @Test
    fun theTicketIsFollowedOverTheSavedTripOnTheSameTrain() = runTest {
        val platform = FakePlatformServices(location = null)
        val deps = AppDeps.fake(platform = platform)
        deps.core.saveTicket(ticket())
        onTheTrain(deps, platform)

        val s = follower(deps, platform).state.first { it.status == FollowStatus.FOLLOWING }
        val m = assertNotNull(s.match)
        assertEquals(ticketId, m.tripId)
        assertEquals(FollowStage.RIDING, m.stage)
    }

    @Test
    fun theTicketIsListedWhenTheUserIsAsked() = runTest {
        val platform = FakePlatformServices(location = null)
        val deps = AppDeps.fake(platform = platform)
        deps.core.saveTicket(ticket())
        val f = follower(deps, platform)

        val asked = f.state.first { it.unsure.isNotEmpty() }
        // Listed first: the user set off on it.
        assertEquals(ticketId, asked.unsure.keys.first())
        val journey = assertNotNull(asked.unsure[ticketId]).first { SampleData.NOW_MS < it.legs[0].expectedArrivalMs }

        f.onBoard(ticketId, journey)
        val s = f.state.first { it.status == FollowStatus.FOLLOWING }
        assertEquals(ticketId, s.match?.tripId)
        assertTrue(s.told)
    }

    @Test
    fun anArrivedTicketIsNotFollowed() = runTest {
        val platform = FakePlatformServices(location = null)
        val deps = AppDeps.fake(platform = platform)
        deps.core.saveTicket(ticket(TicketStatus.ARRIVED))
        val f = follower(deps, platform)

        val asked = f.state.first { it.unsure.isNotEmpty() }
        assertFalse(ticketId in asked.unsure)
        assertTrue(trip in asked.unsure)

        onTheTrain(deps, platform)
        val s = f.state.first { it.status == FollowStatus.FOLLOWING }
        assertEquals(trip, s.match?.tripId)
    }

    @Test
    fun theKeeperMovesTheTicketOnAsItIsRidden() = runTest {
        var now = SampleData.NOW_MS
        val clock = WallClock { now }
        val platform = FakePlatformServices(location = null)
        val deps = AppDeps.fake(clock = clock, platform = platform)
        val journey = deps.core.underway(trip).first().first { now < it.legs[0].expectedArrivalMs }
        deps.core.saveTicket(ticket(arrivalMs = journey.arrival.expectedArrivalMs))
        onTheTrain(deps, platform)

        val f = follower(deps, platform)
        // Home shows the follower; the keeper only reads what Home saw.
        backgroundScope.launch { f.state.collect {} }
        TicketKeeper(deps.core, f, clock, tickMs = 1_000).start(backgroundScope)

        // Followed: on the way.
        val onTheWay = deps.core.tickets.first { it.single().status == TicketStatus.ON_THE_WAY }.single()
        assertEquals(null, onTheWay.arrivedAtMs)
        advanceTimeBy(2_000)

        // The journey arrives and the phone leaves the line: the follower lets go, the ticket is stamped.
        val arrival = deps.core.underway(ticketId).first().first { it.departure.ref == journey.departure.ref }.arrival.expectedArrivalMs
        platform.location.value = LatLng(-34.20, 150.60)
        now = arrival + 60_000L
        val arrived = deps.core.tickets.first { it.single().status == TicketStatus.ARRIVED }.single()
        assertEquals(arrival, arrived.arrivedAtMs)
        assertTrue(f.latest.value.match?.tripId != ticketId)
    }
}
