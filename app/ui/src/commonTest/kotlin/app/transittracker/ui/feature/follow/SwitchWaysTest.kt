package app.transittracker.ui.feature.follow

import app.transittracker.model.Departure
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Journey
import app.transittracker.model.Mode
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceRef
import app.transittracker.model.TripLeg
import app.transittracker.ui.feature.home.Followed
import app.transittracker.ui.feature.home.alternativeWay
import app.transittracker.ui.feature.home.onTheWayOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * On the way, the other ways of the trip still open from the vehicle the user is on: listing
 * them, describing them, switching to one, and keeping the one chosen.
 */
class SwitchWaysTest {
    private val min = 60_000L
    private val t0 = 1_800_000_000_000L
    private fun at(m: Int) = t0 + m * min

    private fun dep(trip: String, line: String, from: String, to: String, d: Int, a: Int, status: DepartureStatus = DepartureStatus.ON_TIME) = Departure(
        ServiceRef(trip, "20261111"), "R-$line", line, null, if (line.startsWith("T")) Mode.TRAIN else Mode.BUS, "Somewhere", from,
        null, false, at(d), at(d), to, at(a), at(a), 0, status,
    )

    // One T1 service from Strathfield, running on through Parramatta and Westmead.
    private val toPar = dep("T1-0800", "T1", "STR", "PAR", 0, 20)
    private val toWes = dep("T1-0800", "T1", "STR", "WES", 0, 26)
    private val toEnd = dep("T1-0800", "T1", "STR", "UNI-ST", 0, 45)

    /** The way followed: off at Parramatta for the 600 (arrive +40). */
    private val current = Journey(listOf(toPar, dep("600-0823", "600", "PAR-C", "UNI", 23, 40)), "way-a")

    /** Stay on to Westmead for the 700 (arrive +36). */
    private val viaWestmead = Journey(listOf(toWes, dep("700-0828", "700", "WES-C", "UNI", 28, 36)), "way-b")

    /** Stay on to the end (arrive +45). */
    private val stayOn = Journey(listOf(toEnd), "way-c")

    /** Another train altogether. */
    private val otherTrain = Journey(listOf(dep("T1-0815", "T1", "STR", "PAR", 15, 35), dep("600-0838", "600", "PAR-C", "UNI", 38, 55)), "way-a")

    /** The same rides as the journey followed, reached by another way. */
    private val sameRides = current.copy(wayId = "way-d")

    /** Westmead, but the 700 is cancelled. */
    private val cancelled = Journey(listOf(toWes, dep("700-0828", "700", "WES-C", "UNI", 28, 36, DepartureStatus.CANCELLED)), "way-e")

    /** Off at Parramatta for the 610 instead (arrive +38). */
    private val other610 = Journey(listOf(toPar, dep("610-0825", "610", "PAR-C", "UNI", 25, 38)), "way-f")

    private val underway = listOf(current, viaWestmead, stayOn, otherTrain, sameRides, cancelled, other610)
    private val riding = FollowMatch("trip", toPar.ref, 0, FollowStage.RIDING, 5.0, "way-a")
    private val changing = FollowMatch("trip", toPar.ref, 1, FollowStage.CHANGING, 5.0, "way-a")

    private val names = mapOf("WES-C" to "Westmead", "PAR-C" to "Parramatta", "UNI-ST" to "Uni")

    @Test
    fun onTheTrainTheOtherWaysThatUseItAreOpenSoonestFirst() {
        assertEquals(listOf(viaWestmead, other610, stayOn), alternativesOf(current, riding, underway, at(10)))
    }

    @Test
    fun aWayThatGetsOffAtAStopThisTrainHasPassedIsNotOpen() {
        // At +27 the train has left Westmead (+26): staying on to the end is all that is left.
        assertEquals(listOf(stayOn), alternativesOf(current, riding, underway, at(27)))
    }

    @Test
    fun whileChangingOnlyWaysLeavingThatTrainAtTheSameStopAreOpen() {
        assertEquals(listOf(other610), alternativesOf(current, changing, underway, at(21)))
    }

    @Test
    fun eachWayIsDescribedByWhatToDoDifferentlyAndWhenItArrives() {
        val west = alternativeWay(current, riding, viaWestmead, names)
        assertEquals("CHANGE AT WESTMEAD TO 700", west.words)
        assertEquals(4, west.minutesSooner)
        assertEquals(listOf("700"), west.rides.map { it.routeName })
        val stay = alternativeWay(current, riding, stayOn, names)
        assertEquals("STAY ON TO UNI", stay.words)
        assertEquals(-5, stay.minutesSooner)
        assertTrue(stay.rides.isEmpty())
        assertEquals("CHANGE AT PARRAMATTA TO 610", alternativeWay(current, riding, other610, names).words)
        assertEquals("STAY ON", alternativeWay(current, riding, stayOn, emptyMap()).words)
    }

    @Test
    fun theCardListsTheOtherWays() {
        val trip = SavedTrip("trip", "Strathfield", "Uni", listOf(TripLeg(setOf("STR"), setOf("PAR")), TripLeg(setOf("PAR-C"), setOf("UNI"))))
        val otw = onTheWayOf(trip, Followed(riding, current, alternativesOf(current, riding, underway, at(10))), names)
        assertEquals(listOf("CHANGE AT WESTMEAD TO 700", "CHANGE AT PARRAMATTA TO 610", "STAY ON TO UNI"), otw.alternatives.map { it.words })
    }

    @Test
    fun switchingFollowsTheOtherWayFromTheSamePlace() {
        assertEquals(riding.copy(wayId = "way-b", offMetres = 0.0), switchedMatch(current, riding, viaWestmead))
        // While changing, the way chosen is followed at the ride being walked to.
        val m = switchedMatch(current, changing, other610)
        assertEquals("way-f", m.wayId)
        assertEquals(1, m.legIndex)
        assertEquals(FollowStage.CHANGING, m.stage)
        val memory = FollowMemory(pinned = riding, pending = otherTrain.departure.ref, pendingCount = 1).switchTo(m)
        assertEquals(m, memory.pinned)
        assertNull(memory.pending)
    }

    @Test
    fun theWayChosenIsKeptWhileTheFixFitsItAsWellAsAnother() {
        val a = riding.copy(offMetres = 10.0)
        val b = riding.copy(offMetres = 10.0, wayId = "way-b")
        assertEquals(a, preferredFit(listOf(a, b), null))
        assertEquals(b, preferredFit(listOf(a, b), riding.copy(wayId = "way-b")))
        // Once the fix no longer fits the way chosen (the user got off for the bus), the best fit wins.
        val walking = changing.copy(wayId = "way-a", offMetres = 30.0)
        assertEquals(walking, preferredFit(listOf(walking), riding.copy(wayId = "way-b")))
    }
}
