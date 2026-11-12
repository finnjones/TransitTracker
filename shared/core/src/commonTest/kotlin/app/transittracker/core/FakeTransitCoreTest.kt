package app.transittracker.core

import app.transittracker.model.AlarmKind
import app.transittracker.model.AlarmSpec
import app.transittracker.model.DepartureStatus
import app.transittracker.model.JoinResult
import app.transittracker.model.WallClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeTransitCoreTest {
    private val now = at(8, 3)
    private val core = FakeTransitCore(clock = WallClock { now })

    @Test
    fun upcomingIsSensibleForEverySampleTrip() = runTest {
        for (trip in core.trips.first()) {
            val journeys = core.upcoming(trip.id, 6).first()
            assertEquals(6, journeys.size, trip.id)
            for (j in journeys) {
                assertEquals(trip.legs.size, j.legs.size)
                val first = j.departure
                val t = if (first.status == DepartureStatus.CANCELLED) first.scheduledDepartureMs else first.expectedDepartureMs
                assertTrue(t >= now, "${first.ref} departs after now")
                j.legs.zipWithNext().forEach { (a, b) -> assertTrue(b.expectedDepartureMs >= a.expectedArrivalMs) }
                assertTrue(first.expectedArrivalMs > first.expectedDepartureMs)
            }
            assertEquals(journeys.map { it.departure.expectedDepartureMs }.sorted(), journeys.map { it.departure.expectedDepartureMs })
        }
    }

    @Test
    fun mixOfStatusesOverTheMorning() = runTest {
        val statuses = mutableSetOf<DepartureStatus>()
        var platformChange = false
        for (minute in 0 until 180 step 20) {
            val c = FakeTransitCore(clock = WallClock { now + minute * MIN })
            for (trip in c.trips.first()) {
                c.upcoming(trip.id, 8).first().flatMap { it.legs }.forEach {
                    statuses += it.status
                    platformChange = platformChange || it.platformChanged
                }
            }
        }
        assertTrue(statuses.containsAll(listOf(DepartureStatus.ON_TIME, DepartureStatus.LATE, DepartureStatus.CANCELLED, DepartureStatus.ADDED)), statuses.toString())
        assertTrue(platformChange)
    }

    @Test
    fun serviceTrackAlertsAndWatches() = runTest {
        val departure = core.upcoming("trip-central-parramatta", 3).first().first { it.departure.status != DepartureStatus.ADDED }.departure
        val detail = core.service(departure.ref).first()
        assertTrue(detail.calls.size >= 2)
        assertEquals(departure.ref, detail.ref)
        val tracking = core.track(departure.ref).first()
        assertEquals(detail.ref, tracking.detail.ref)

        assertTrue(core.alerts.first().isNotEmpty())
        val status = core.dataStatus.first()
        assertTrue(status.joined)
        assertEquals("syd-rail", status.packs.single().name)

        val watch = core.watchService(departure.ref)
        assertTrue(core.watches.first().any { it.id == watch.id })
        assertTrue(core.sendTestNotification(watch.id).sent)
        core.deleteWatch(watch.id)
        assertTrue(core.watches.first().none { it.id == watch.id })

        assertEquals(JoinResult.InvalidCode, core.join("bad"))
        assertEquals(JoinResult.Joined("fake-device"), core.join("GOOD-CODE"))
        assertTrue(core.searchStops("central").isNotEmpty())
        assertTrue(core.linesAt(FakeData.CHATSWOOD).map { it.id }.containsAll(listOf("T1", "T9", "M1")))
        assertEquals(3, core.nextAlarmTimes(AlarmSpec("a", "trip-parramatta-central", AlarmKind.BEFORE_DEPARTURE, 5), 3).size)
    }

    @Test
    fun tripsCanBeEdited() = runTest {
        val ids = core.trips.first().map { it.id }
        core.reorderTrips(ids.reversed())
        assertEquals(ids.reversed(), core.trips.first().map { it.id })
        core.deleteTrip(ids.first())
        assertEquals(ids.size - 1, core.trips.first().size)
    }
}
