package app.transittracker.ui.feature.follow

import app.transittracker.model.DepartureStatus
import app.transittracker.model.Itinerary
import app.transittracker.model.ItineraryLeg
import app.transittracker.model.LatLng
import app.transittracker.model.LegKind
import app.transittracker.model.LegPoint
import app.transittracker.model.Mode
import app.transittracker.model.Route
import app.transittracker.model.ServiceDetail
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopCall
import app.transittracker.ui.nav.TakeFrom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TAKE THIS ONE: a journey the planner found on the way, followed for this trip only. */
class OneOffTest {
    private val min = 60_000L
    private val t0 = 1_800_000_000_000L
    private fun at(m: Int) = t0 + m * min
    private val here = LatLng(-33.8, 151.0)

    private val train = ServiceRef("T1-0800", "20261111")
    private val from = TakeFrom("trip", "way-a", 0, train, "STR", "PAR", here, changing = false)

    private fun point(name: String, stop: String?, m: Int) = LegPoint(name, here, stop, scheduledMs = at(m))
    private fun ride(trip: String?, line: String, a: String?, b: String, d: Int, e: Int) = ItineraryLeg(
        LegKind.RIDE, point(a ?: "Somewhere", a, d), point(b, b, e), (e - d) * 60,
        mode = Mode.BUS, routeName = line, tripId = trip, serviceDate = trip?.let { "20261111" },
    )
    private fun walk(a: String, b: String, d: Int, e: Int) = ItineraryLeg(LegKind.WALK, point(a, null, d), point(b, null, e), (e - d) * 60)

    @Test
    fun aJourneyFromTheNextStopStartsWithTheRideTheUserIsOn() {
        val it = Itinerary("it-1", listOf(walk("PAR", "PAR-C", 20, 22), ride("700-0825", "700", "PAR-C", "UNI", 25, 38)))
        assertNull(takeBlocker(it))
        val o = oneOffOf(from, it)
        assertEquals(listOf(train, ServiceRef("700-0825", "20261111")), o.rides.map { it.ref })
        assertEquals("STR" to "PAR", o.rides[0].board to o.rides[0].alight)
        assertEquals("one-off:it-1", o.wayId)
        assertEquals(FollowMatch("trip", train, 0, FollowStage.RIDING, 0.0, "one-off:it-1"), o.match)
    }

    @Test
    fun stayingOnTheSameServiceIsOneRide() {
        val it = Itinerary("it-2", listOf(ride("T1-0800", "T1", "PAR", "WES", 20, 26), ride("700-0828", "700", "WES-C", "UNI", 28, 36)))
        val o = oneOffOf(from, it)
        assertEquals(2, o.rides.size)
        assertEquals("STR" to "WES", o.rides[0].board to o.rides[0].alight)
    }

    @Test
    fun takenWhileChangingItIsFollowedAtTheRideWalkedTo() {
        val it = Itinerary("it-3", listOf(ride("610-0825", "610", "PAR-C", "UNI", 25, 38)))
        val o = oneOffOf(from.copy(changing = true), it)
        assertEquals(FollowStage.CHANGING, o.match.stage)
        assertEquals(1, o.match.legIndex)
    }

    @Test
    fun aRideTheTimetableDoesNotHaveCannotBeFollowed() {
        val it = Itinerary("it-4", listOf(ride(null, "M20", "PAR-C", "UNI", 25, 38)))
        assertTrue(takeBlocker(it)!!.startsWith("The M20 from PAR-C"))
        assertTrue(takeBlocker(Itinerary("it-5", listOf(walk("PAR", "UNI", 20, 50))))!!.contains("walk"))
    }

    @Test
    fun timesComeFromThePhonesLiveDataElseThePlanner() {
        val bus = ServiceRef("700-0825", "20261111")
        val it = Itinerary("it-1", listOf(ride("700-0825", "700", "PAR-C", "UNI", 25, 38)))
        val o = oneOffOf(from, it)
        fun call(seq: Int, stop: String, sched: Int, exp: Int) = StopCall(seq, stop, stop, null, false, at(sched), at(sched), at(exp), at(exp), false, false)
        val trainDetail = ServiceDetail(
            train, Route("R-T1", "T1", "Line", Mode.TRAIN), "Emu Plains", DepartureStatus.LATE,
            listOf(call(1, "STR", 0, 4), call(2, "PAR", 20, 24)),
        )
        // The bus has no live view: the planner's times, timetabled.
        val j = assertNotNull(o.journeyFrom(mapOf(train to trainDetail)))
        assertEquals(at(24), j.legs[0].expectedArrivalMs)
        assertEquals(240, j.legs[0].delaySeconds)
        assertEquals("T1", j.legs[0].routeName)
        assertEquals(bus, j.legs[1].ref)
        assertEquals(DepartureStatus.TIMETABLED, j.legs[1].status)
        assertEquals(at(38), j.arrival.expectedArrivalMs)
        assertEquals("one-off:it-1", j.wayId)
        // Without the train's stops there is no journey.
        assertNull(o.journeyFrom(emptyMap()))
    }
}
