package app.transittracker.ui.feature.follow

import app.transittracker.model.Departure
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Journey
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopCall
import app.transittracker.model.VehiclePosition
import app.transittracker.ui.platform.LocationFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /**
     * The journey whose train leaves at 07:[offset]; the train runs along [shape] when given, and
     * the feed has it at [vehicle] when given.
     */
    private fun candidate(offset: Double, shape: List<LatLng> = emptyList(), vehicle: VehiclePosition? = null): FollowCandidate {
        val train = dep("train-$offset", Mode.TRAIN, "A", "B", t(offset), t(offset + 20))
        val bus = dep("bus-$offset", Mode.BUS, "B2", "C", t(offset + 30), t(offset + 45))
        return FollowCandidate(
            "trip", Journey(listOf(train, bus)),
            listOf(
                RiddenLeg(train, listOf(call(1, "A", a, null, t(offset)), call(2, "B", b, t(offset + 20), null)), shape, vehicle),
                RiddenLeg(bus, listOf(call(1, "B2", b2, null, t(offset + 30)), call(2, "C", c, t(offset + 45), null))),
            ),
        )
    }

    /** The 07:00 train as the feed reports it: at [at], at 07:[min], going [speed] m/s. */
    private fun train(at: LatLng, min: Double, speed: Float? = null) =
        VehiclePosition("set-1", "train-0.0", "20261111", "R-train-0.0", at, speedMps = speed, timestampMs = t(min))

    private val both = listOf(candidate(0.0), candidate(10.0))

    private fun fix(at: LatLng, min: Double, accuracy: Double? = null, speed: Double? = null, bearing: Double? = null) =
        LocationFix(at, t(min), accuracy, speed, bearing)

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

    // ---------------------------------------------------------------- the real line

    /** The railway bows a kilometre north between A and B; the straight line A-B is a road. */
    private val bow = listOf(a, LatLng(-33.791, 151.03), LatLng(-33.791, 151.07), b)

    @Test
    fun theRealLineIsFollowedWhenThereIsOne() {
        val shaped = listOf(candidate(0.0, shape = bow))
        // Halfway along the bow (07:10), on the railway: riding.
        assertNotNull(matchFix(fix(LatLng(-33.791, 151.05), 10.0, accuracy = 20.0), shaped))
        // On the road between A and B, a kilometre from the railway: not on the train.
        assertNull(matchFix(fix(between(a, b, 0.5), 10.0, accuracy = 20.0), shaped))
        // Without the shape the road would have passed for the railway.
        assertNotNull(matchFix(fix(between(a, b, 0.5), 10.0, accuracy = 20.0), listOf(candidate(0.0))))
    }

    @Test
    fun aRoadBesideTheRailwayIsTooFarAlongAShape() {
        // 300 m north of a straight railway: inside the stop-to-stop corridor, outside the shape's.
        val beside = LatLng(between(a, b, 0.5).lat + 0.0027, between(a, b, 0.5).lon)
        assertNotNull(matchFix(fix(beside, 10.0, accuracy = 20.0), listOf(candidate(0.0))))
        assertNull(matchFix(fix(beside, 10.0, accuracy = 20.0), listOf(candidate(0.0, shape = listOf(a, b)))))
    }

    // ---------------------------------------------------------------- the vehicle itself

    @Test
    fun theLiveVehicleBeatsTheTimetable() {
        // The 07:00 is running late: at 07:10 the feed has it three tenths along, not halfway.
        val late = listOf(candidate(0.0, vehicle = train(between(a, b, 0.3), 10.0)))
        val beside = readFix(fix(between(a, b, 0.3), 10.0, accuracy = 20.0), late).single()
        assertNotNull(beside.match)
        assertEquals(FollowTuning().vehicleFitEvidence, beside.evidence)
        // Where the timetable puts it, 1.8 km ahead of the train: clearly not on it.
        val ahead = readFix(fix(between(a, b, 0.5), 10.0, accuracy = 20.0), late).single()
        assertNull(ahead.match)
        assertEquals(FollowTuning().clearMissEvidence, ahead.evidence)
    }

    @Test
    fun aVehicleReportIsCarriedForwardToTheFix() {
        // Reported a minute before the fix, a quarter along; the timetable moves it on since.
        val v = train(between(a, b, 0.25), 9.0)
        val m = readFix(fix(between(a, b, 0.30), 10.0, accuracy = 20.0), listOf(candidate(0.0, vehicle = v))).single()
        assertNotNull(m.match)
    }

    @Test
    fun anOldVehicleReportIsNotUsed() {
        val stale = train(between(a, b, 0.1), 2.0)
        val m = readFix(fix(between(a, b, 0.5), 10.0, accuracy = 20.0), listOf(candidate(0.0, vehicle = stale))).single()
        assertNotNull(m.match)
        assertEquals(FollowTuning().fitEvidence, m.evidence)
    }

    @Test
    fun aVehicleReportFarFromTheLiveTimetableIsNotBelieved() {
        // The feed puts the 07:00 back at A at 07:15 (a stuck report): the timetable decides.
        val stuck = train(a, 15.0)
        val m = readFix(fix(between(a, b, 0.75), 15.0, accuracy = 20.0), listOf(candidate(0.0, vehicle = stuck))).single()
        assertNotNull(m.match)
        assertEquals(FollowTuning().fitEvidence, m.evidence)
    }

    // ---------------------------------------------------------------- heading and speed

    @Test
    fun goingTheOtherWayDownTheLineIsNotRiding() {
        // The train runs east (090). The phone is on the line, where the train is, going west.
        val west = readFix(fix(between(a, b, 0.5), 10.0, accuracy = 20.0, speed = 20.0, bearing = 270.0), both).first()
        assertNull(west.match)
        assertEquals(FollowTuning().clearMissEvidence, west.evidence)
        assertNotNull(readFix(fix(between(a, b, 0.5), 10.0, accuracy = 20.0, speed = 20.0, bearing = 85.0), both).first().match)
        // Standing still, a bearing means nothing.
        assertNotNull(readFix(fix(between(a, b, 0.5), 10.0, accuracy = 20.0, speed = 0.5, bearing = 270.0), both).first().match)
    }

    @Test
    fun racingPastAStandingTrainCountsAgainstIt() {
        val standing = train(between(a, b, 0.5), 10.0, speed = 0f)
        val passing = readFix(fix(between(a, b, 0.5), 10.0, accuracy = 20.0, speed = 18.0), listOf(candidate(0.0, vehicle = standing))).single()
        assertNotNull(passing.match)
        assertEquals(FollowTuning().mismatchEvidence, passing.evidence)
        val aboard = readFix(fix(between(a, b, 0.5), 10.0, accuracy = 20.0, speed = 0.0), listOf(candidate(0.0, vehicle = standing))).single()
        assertEquals(FollowTuning().vehicleFitEvidence, aboard.evidence)
    }

    @Test
    fun drivingPastTheChangeIsNotChanging() {
        val walking = readFix(fix(b2, 25.0, accuracy = 20.0, speed = 1.4), both).first()
        assertEquals(FollowStage.CHANGING, walking.match?.stage)
        assertEquals(FollowTuning().fitEvidence, walking.evidence)
        val driving = readFix(fix(b2, 25.0, accuracy = 20.0, speed = 15.0), both).first()
        assertEquals(FollowTuning().mismatchEvidence, driving.evidence)
    }

    // ---------------------------------------------------------------- how much a miss counts

    @Test
    fun anAccurateFixFarOffIsAClearMissAndAVagueOneIsNot() {
        val far = LatLng(-33.70, 150.90)
        assertEquals(FollowTuning().clearMissEvidence, readFix(fix(far, 10.0, accuracy = 15.0), both).first().evidence)
        // Accuracy unknown: only a miss.
        assertEquals(FollowTuning().missEvidence, readFix(fix(far, 10.0), both).first().evidence)
        // A vague fix counts half.
        assertEquals(FollowTuning().missEvidence / 2, readFix(fix(far, 10.0, accuracy = 400.0), both).first().evidence)
        // A little off the line is a miss, not a clear one.
        val near = LatLng(between(a, b, 0.5).lat + 0.006, between(a, b, 0.5).lon)
        assertEquals(FollowTuning().missEvidence, readFix(fix(near, 10.0, accuracy = 15.0), both).first().evidence)
    }

    // ---------------------------------------------------------------- keeping it steady

    private val k1 = ServiceRef("one", "d")
    private val k2 = ServiceRef("two", "d")
    private fun m(key: ServiceRef, leg: Int = 0) = FollowMatch("trip", key, leg, FollowStage.RIDING, 10.0)
    private val running = setOf(k1, k2)
    private val tune = FollowTuning()

    /** A fix that clearly contradicts every journey. */
    private fun FollowMemory.clearMiss() = next(null, running, running.associateWith { tune.clearMissEvidence })

    @Test
    fun followedAfterTwoFixesInARow() {
        var mem = FollowMemory().next(m(k1), running)
        assertNull(mem.pinned)
        mem = mem.next(m(k1), running)
        assertEquals(k1, mem.pinned?.key)
        // A new leg updates at once.
        mem = mem.next(m(k1, leg = 1), running)
        assertEquals(1, mem.pinned?.legIndex)
    }

    @Test
    fun twoClearMissesDropAJourney() {
        var mem = FollowMemory()
        repeat(5) { mem = mem.next(m(k1), running) }
        mem = mem.clearMiss()
        assertEquals(k1, mem.pinned?.key)
        mem = mem.clearMiss()
        assertNull(mem.pinned)
    }

    @Test
    fun aTunnelOnlyNudgesIt() {
        var mem = FollowMemory()
        repeat(5) { mem = mem.next(m(k1), running) }
        // Weak misses (a vague fix, a little off the line) take four in a row from a sure journey.
        repeat(3) {
            mem = mem.next(null, running)
            assertEquals(k1, mem.pinned?.key)
        }
        mem = mem.next(null, running)
        assertNull(mem.pinned)
    }

    @Test
    fun oneStrayFixDoesNotSwitchJourneys() {
        var mem = FollowMemory().next(m(k1), running).next(m(k1), running)
        mem = mem.next(m(k2), running)
        assertEquals(k1, mem.pinned?.key)
        mem = mem.next(m(k2), running)
        assertEquals(k1, mem.pinned?.key)
        // The fixes keep fitting the other train: it takes over.
        mem = mem.next(m(k2), running)
        assertEquals(k2, mem.pinned?.key)
    }

    @Test
    fun aJourneyContradictedBeforeNeedsMoreToBeFollowed() {
        var mem = FollowMemory().clearMiss().clearMiss()
        mem = mem.next(m(k1), running).next(m(k1), running)
        assertNull(mem.pinned)
        mem = mem.next(m(k1), running)
        assertEquals(k1, mem.pinned?.key)
    }

    @Test
    fun speedThatDoesNotFitHoldsOffAJourney() {
        // In place but not in speed, fix after fix (a car beside the line): never followed.
        var mem = FollowMemory()
        repeat(6) { mem = mem.next(m(k1), running, mapOf(k1 to tune.mismatchEvidence)) }
        assertNull(mem.pinned)
        assertTrue((mem.scores[k1] ?: 0.0) <= 0.0)
    }

    @Test
    fun arrivedJourneysAreNotFollowed() {
        val mem = FollowMemory().next(m(k1), running).next(m(k1), running)
        assertNull(mem.next(null, setOf(k2)).pinned)
    }

    @Test
    fun switchingWaysIsFollowedAtOnce() {
        val mem = FollowMemory().switchTo(m(k2))
        assertEquals(k2, mem.pinned?.key)
        // and survives a few misses.
        assertEquals(k2, mem.next(null, running).next(null, running).pinned?.key)
    }
}
