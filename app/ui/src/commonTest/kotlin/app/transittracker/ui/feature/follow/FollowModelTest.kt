package app.transittracker.ui.feature.follow

import app.transittracker.model.Departure
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Journey
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopCall
import app.transittracker.ui.platform.LocationFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A train from A to B (07:00 → 07:20, about 9 km east), then a bus from the stop beside B down to
 * C (07:30 → 07:45, about 5.5 km south). A second journey runs ten minutes later on the same
 * lines. Times are minutes past 07:00 in a made-up epoch.
 */
class FollowModelTest {
    private fun t(min: Double): Long = (7 * 60 + min).times(60_000).toLong()

    private val a = LatLng(-33.80, 151.00)
    private val b = LatLng(-33.80, 151.10)
    private val b2 = LatLng(-33.801, 151.101)
    private val c = LatLng(-33.85, 151.101)

    private fun call(seq: Int, id: String, at: LatLng, arr: Long?, dep: Long?) =
        StopCall(seq, id, id, null, false, arr, dep, arr, dep, skipped = false, passed = false, position = at)

    private fun dep(trip: String, mode: Mode, from: String, to: String, depMs: Long, arrMs: Long) = Departure(
        ServiceRef(trip, "20261111"), "R-$trip", trip, null, mode, "Somewhere", from, null, false,
        depMs, depMs, to, arrMs, arrMs, null, DepartureStatus.TIMETABLED,
    )

    /** The journey whose train leaves at 07:[offset]. */
    private fun candidate(offset: Double): FollowCandidate {
        val train = dep("train-$offset", Mode.TRAIN, "A", "B", t(offset), t(offset + 20))
        val bus = dep("bus-$offset", Mode.BUS, "B2", "C", t(offset + 30), t(offset + 45))
        return FollowCandidate(
            "trip", Journey(listOf(train, bus)),
            listOf(
                RiddenLeg(train, listOf(call(1, "A", a, null, t(offset)), call(2, "B", b, t(offset + 20), null))),
                RiddenLeg(bus, listOf(call(1, "B2", b2, null, t(offset + 30)), call(2, "C", c, t(offset + 45), null))),
            ),
        )
    }

    private val both = listOf(candidate(0.0), candidate(10.0))

    private fun fix(at: LatLng, min: Double, accuracy: Double? = null) = LocationFix(at, t(min), accuracy)

    private fun between(p: LatLng, q: LatLng, f: Double) = LatLng(p.lat + (q.lat - p.lat) * f, p.lon + (q.lon - p.lon) * f)

    @Test
    fun onTheTrainWhereTheTimetablePutsIt() {
        val m = assertNotNull(matchFix(fix(between(a, b, 0.5), 10.0), both))
        assertEquals("train-0.0", m.key.tripId)
        assertEquals(0, m.legIndex)
        assertEquals(FollowStage.RIDING, m.stage)
    }

    @Test
    fun theLaterTrainOnTheSameLineIsToldApart() {
        // At 07:15 the 07:00 is three quarters along and the 07:10 a quarter: a fix a quarter along is the 07:10.
        val m = assertNotNull(matchFix(fix(between(a, b, 0.25), 15.0), both))
        assertEquals("train-10.0", m.key.tripId)
    }

    @Test
    fun standingAtTheFirstStopIsWaitingNotRiding() {
        assertNull(matchFix(fix(a, 10.5), both))
    }

    @Test
    fun walkingToTheBusIsChanging() {
        val m = assertNotNull(matchFix(fix(b2, 25.0), both))
        assertEquals("train-0.0", m.key.tripId)
        assertEquals(1, m.legIndex)
        assertEquals(FollowStage.CHANGING, m.stage)
    }

    @Test
    fun onTheBus() {
        val m = assertNotNull(matchFix(fix(between(b2, c, 0.5), 37.5), both))
        assertEquals("train-0.0", m.key.tripId)
        assertEquals(1, m.legIndex)
        assertEquals(FollowStage.RIDING, m.stage)
    }

    @Test
    fun offTheRouteIsNothing() {
        assertNull(matchFix(fix(LatLng(-33.70, 150.90), 10.0), both))
        // Beside the line but far behind where any train is.
        assertNull(matchFix(fix(between(a, b, 0.9), 2.0), both))
    }

    @Test
    fun riddenCallsAreBoardToAlight() {
        val calls = listOf("X", "A", "M", "B", "Y").mapIndexed { i, id -> call(i, id, a, null, null) }
        assertEquals(listOf("A", "M", "B"), riddenCalls(calls, "A", "B").map { it.stopId })
        assertEquals(listOf("X", "A", "M", "B"), riddenCalls(calls, "nope", "B").map { it.stopId })
        assertEquals(emptyList(), riddenCalls(calls, "B", "A"))
    }

    // ---------------------------------------------------------------- keeping it steady

    private val k1 = ServiceRef("one", "d")
    private val k2 = ServiceRef("two", "d")
    private fun m(key: ServiceRef, leg: Int = 0) = FollowMatch("trip", key, leg, FollowStage.RIDING, 10.0)
    private val running = setOf(k1, k2)

    @Test
    fun followedAfterTwoFixesInARowAndDroppedAfterThreeMisses() {
        var mem = FollowMemory().next(m(k1), running)
        assertNull(mem.pinned)
        mem = mem.next(m(k1), running)
        assertEquals(k1, mem.pinned?.key)
        // A new leg updates at once.
        mem = mem.next(m(k1, leg = 1), running)
        assertEquals(1, mem.pinned?.legIndex)
        mem = mem.next(null, running).next(null, running)
        assertEquals(k1, mem.pinned?.key)
        mem = mem.next(null, running)
        assertNull(mem.pinned)
    }

    @Test
    fun oneStrayFixDoesNotSwitchJourneys() {
        var mem = FollowMemory().next(m(k1), running).next(m(k1), running)
        mem = mem.next(m(k2), running)
        assertEquals(k1, mem.pinned?.key)
        mem = mem.next(m(k2), running)
        assertEquals(k2, mem.pinned?.key)
    }

    @Test
    fun arrivedOrDismissedJourneysAreNotFollowed() {
        val mem = FollowMemory().next(m(k1), running).next(m(k1), running)
        assertNull(mem.next(null, setOf(k2)).pinned)
        val dismissed = mem.dismiss(k1)
        assertNull(dismissed.pinned)
        assertNull(dismissed.next(m(k1), running).next(m(k1), running).pinned)
    }
}
