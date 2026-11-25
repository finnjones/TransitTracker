package app.transittracker.core

import app.transittracker.model.AlarmKind
import app.transittracker.model.AlarmSpec
import app.transittracker.model.Alert
import app.transittracker.model.AlertsResponse
import app.transittracker.model.LatLng
import app.transittracker.model.RealtimeResponse
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceRef
import app.transittracker.model.Ticket
import app.transittracker.model.TicketEnd
import app.transittracker.model.TicketStatus
import app.transittracker.model.TripLeg
import app.transittracker.model.ticketIdFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tickets in the real core: stored on the phone, hidden and dropped as they expire, and looked up like saved trips. */
class TicketsTest {
    private val alphaBravo = listOf(TripLeg(setOf("A"), setOf("B")))
    private val bravoCharlie = listOf(TripLeg(setOf("B"), setOf("C"), routeIds = setOf("R2")))

    /** A ticket for [legs], the 07:00 run (arriving 07:20) picked at [issuedAtMs]. */
    private fun ticket(
        legs: List<TripLeg> = alphaBravo,
        issuedAtMs: Long = at(6, 50),
        status: TicketStatus = TicketStatus.WAITING,
        arrivedAtMs: Long? = null,
    ) = Ticket(
        trip = SavedTrip(ticketIdFor(legs), "Alpha", "Bravo", legs),
        from = TicketEnd("Home", LatLng(-33.80, 151.00), savedPlaceId = "home"),
        to = TicketEnd("Bravo", LatLng(-33.85, 151.10)),
        plannedRides = listOf(ServiceRef("R1-04", DAY)),
        plannedDepartureMs = at(7, 0),
        plannedArrivalMs = at(7, 20),
        issuedAtMs = issuedAtMs,
        status = status,
        arrivedAtMs = arrivedAtMs,
    )

    @Test
    fun saveReplaceAndClose() = runTest {
        val h = Harness(this)
        val first = ticket(issuedAtMs = at(6, 48))
        val second = ticket(legs = bravoCharlie, issuedAtMs = at(6, 45))
        h.core.saveTicket(first)
        h.core.saveTicket(second)
        assertEquals(listOf(second.id, first.id), h.core.tickets.first().map { it.id }, "oldest issued first")

        // Status changes replace the ticket with the same id.
        h.core.saveTicket(first.copy(status = TicketStatus.ON_THE_WAY))
        val now = h.core.tickets.first()
        assertEquals(2, now.size)
        assertEquals(TicketStatus.ON_THE_WAY, now.single { it.id == first.id }.status)

        h.core.closeTicket(first.id)
        assertEquals(listOf(second.id), h.core.tickets.first().map { it.id })
        h.core.closeTicket("ticket-unknown")
        assertEquals(listOf(second.id), h.core.tickets.first().map { it.id })
        assertTrue(h.core.trips.first().isEmpty(), "a ticket is not a saved trip")
    }

    @Test
    fun ticketsSurviveANewCoreOnTheSameStorage() = runTest {
        val h = Harness(this)
        h.core.saveTicket(ticket(status = TicketStatus.ON_THE_WAY))
        assertTrue(h.kv.get(KvKeys.TICKETS)!!.contains(ticketIdFor(alphaBravo)))

        val again = Harness(this, kv = h.kv)
        assertEquals(listOf(ticket(status = TicketStatus.ON_THE_WAY)), again.core.tickets.first())
        assertEquals(listOf(ticketIdFor(alphaBravo)), TicketStore(h.kv).tickets.first().map { it.id })
    }

    @Test
    fun undecodableStoredTicketsReadAsNone() = runTest {
        val kv = InMemoryKeyValueStore(mapOf(KvKeys.TICKETS to "{not json"))
        val store = TicketStore(kv)
        assertTrue(store.tickets.first().isEmpty())
        store.save(ticket())
        assertEquals(listOf(ticket()), TicketStore(kv).tickets.first())
        store.delete(ticket().id)
        assertTrue(TicketStore(kv).tickets.first().isEmpty())
    }

    @Test
    fun expiredTicketsAreHiddenAndDropped() = runTest {
        val h = Harness(this)
        val waiting = ticket(legs = listOf(TripLeg(setOf("A"), setOf("C"))), issuedAtMs = at(6, 40)) // until 08:30
        val onTheWay = ticket(legs = bravoCharlie, issuedAtMs = at(6, 41), status = TicketStatus.ON_THE_WAY) // until 08:50
        val arrived = ticket(issuedAtMs = at(6, 42), status = TicketStatus.ARRIVED, arrivedAtMs = at(6, 55)) // until 07:05
        h.core.saveTicket(waiting)
        h.core.saveTicket(onTheWay)
        h.core.saveTicket(arrived)

        val seen = mutableListOf<List<String>>()
        backgroundScope.launch { h.core.tickets.collect { list -> seen += list.map { it.id } } }
        runCurrent()
        assertEquals(listOf(waiting.id, onTheWay.id, arrived.id), seen.last())

        suspend fun stored() = TicketStore(h.kv).tickets.first().map { it.id }

        advanceTimeBy(at(7, 6) - h.clock.nowMillis())
        assertEquals(listOf(waiting.id, onTheWay.id), seen.last(), "the arrived stub goes 10 minutes after arriving")
        assertEquals(listOf(waiting.id, onTheWay.id), stored())

        advanceTimeBy(at(8, 31) - h.clock.nowMillis())
        assertEquals(listOf(onTheWay.id), seen.last(), "a ticket never boarded goes 90 minutes after the planned departure")
        assertEquals(listOf(onTheWay.id), stored())

        advanceTimeBy(at(8, 51) - h.clock.nowMillis())
        assertEquals(emptyList(), seen.last(), "a ticket on the way goes 90 minutes after the planned arrival")
        assertEquals(emptyList(), stored())
    }

    @Test
    fun startDropsTicketsThatExpiredWhileTheAppWasClosed() = runTest {
        val h = Harness(this, startMs = at(9, 0))
        val store = TicketStore(h.kv)
        store.save(ticket())
        store.save(ticket(legs = bravoCharlie).copy(plannedDepartureMs = at(8, 0), plannedArrivalMs = at(8, 15)))
        h.core.start()
        assertEquals(listOf(ticketIdFor(bravoCharlie)), TicketStore(h.kv).tickets.first().map { it.id })
        assertEquals(listOf(ticketIdFor(bravoCharlie)), h.core.tickets.first().map { it.id })
    }

    @Test
    fun departuresAndAlarmsWorkOnATicketId() = runTest {
        val h = Harness(this)
        h.join()
        h.preinstall("v1")
        h.serveConfig(h.packInfo("v1"))
        h.server.on("GET", "/v1/rt") { realtime(RealtimeResponse(serverTimeMs = h.clock.nowMillis())) }
        val t = ticket()
        h.core.saveTicket(t)
        h.core.start()
        h.core.onForeground()

        val journeys = h.core.upcoming(t.id, 3).first { it.isNotEmpty() }
        assertEquals(listOf("R1-04", "R1-05", "R1-06"), journeys.map { it.departure.ref.tripId })
        assertEquals("R1", h.server.requestsTo("/v1/rt").first().query["routes"], "a ticket's routes are polled")
        assertEquals(14, h.core.timetable(t.id, DAY).first().size)
        assertEquals(listOf("R1-02", "R1-03"), h.core.underway(t.id).first().map { it.departure.ref.tripId }, "the runs still riding at 06:50")
        assertEquals(
            listOf(at(6, 55), at(7, 10)),
            h.core.nextAlarmTimes(AlarmSpec("a", t.id, AlarmKind.BEFORE_DEPARTURE, 5), 2),
        )

        h.core.closeTicket(t.id)
        assertTrue(h.core.upcoming(t.id, 3).first().isEmpty())
        assertTrue(h.core.nextAlarmTimes(AlarmSpec("a", t.id, AlarmKind.BEFORE_DEPARTURE, 5)).isEmpty())
    }

    @Test
    fun aTicketStopsResolvingOnceExpired() = runTest {
        val h = Harness(this, startMs = at(8, 29))
        h.join()
        h.preinstall("v1")
        h.serveConfig(h.packInfo("v1"))
        val t = ticket() // waiting: until 08:30
        h.core.saveTicket(t)
        h.core.start()
        val seen = mutableListOf<Int>()
        backgroundScope.launch { h.core.upcoming(t.id, 3).collect { seen += it.size } }
        runCurrent()
        assertEquals(3, seen.last())
        advanceTimeBy(2 * MIN)
        assertEquals(0, seen.last())
        assertTrue(h.core.nextAlarmTimes(AlarmSpec("a", t.id, AlarmKind.BEFORE_DEPARTURE, 5)).isEmpty())
    }

    @Test
    fun alertsForATicketId() = runTest {
        val h = Harness(this)
        h.join()
        h.preinstall("v1")
        h.serveConfig(h.packInfo("v1"))
        val served = listOf(
            Alert("route-r1", "R1 delays", routeIds = listOf("R1")),
            Alert("route-r2", "R2 delays", routeIds = listOf("R2")),
            Alert("station-b", "Bravo concourse closed", stopIds = listOf("B")),
        )
        h.server.on("GET", "/v1/alerts") { json(AlertsResponse.serializer(), AlertsResponse(h.clock.nowMillis(), served)) }
        val t = ticket()
        h.core.saveTicket(t)
        h.core.start()

        assertEquals(listOf("route-r1", "station-b"), h.core.alertsFor(t.id).first { it.isNotEmpty() }.map { it.id })
        h.core.closeTicket(t.id)
        assertTrue(h.core.alertsFor(t.id).first().isEmpty())
        assertNull(h.core.tickets.first().firstOrNull())
    }
}
