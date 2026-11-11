package app.transittracker.server.live

import app.transittracker.model.AddedStop
import app.transittracker.model.StopDelta
import app.transittracker.model.TripDelta
import app.transittracker.model.TripStatus
import com.google.transit.realtime.TripDescriptor.ScheduleRelationship as TripRel
import com.google.transit.realtime.TripUpdate
import com.google.transit.realtime.TripUpdate.StopTimeUpdate.ScheduleRelationship as StopRel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripMatcherTest {
    private val timetable = FakeTimetable()
    private val matcher = TripMatcher(timetable)
    private val morning = at(DAY, hms(7, 55))

    private fun matched(update: TripUpdate, feedTimeMs: Long = morning): TripDelta {
        val result = matcher.match(update, feedTimeMs)
        assertIs<MatchResult.Matched>(result, "expected a match, got $result")
        return result.delta
    }

    @Test
    fun `delay at one stop propagates to every later stop`() {
        val delta = matched(tripUpdate("T1-A", stus = listOf(stu(seq = 2, arrDelay = 120, depDelay = 180))))
        assertEquals(TripStatus.SCHEDULED, delta.status)
        assertEquals("T1", delta.routeId)
        assertEquals(DAY, delta.startDate)
        assertEquals(
            listOf(
                StopDelta(1, "CEN1", null, null),
                StopDelta(2, "RED1", 120, 180),
                StopDelta(3, "STR1", 180, 180),
                StopDelta(4, "HBY1", 180, 180),
            ),
            delta.stops,
        )
    }

    @Test
    fun `later update overrides propagated delay and NO_DATA stops propagation`() {
        val delta = matched(
            tripUpdate(
                "T1-A",
                stus = listOf(
                    stu(seq = 1, depDelay = 60),
                    stu(seq = 3, relationship = StopRel.NO_DATA),
                ),
            ),
        )
        assertEquals(listOf(60 to 60, 60 to 60, null to null, null to null), delta.stops.map { it.arrivalDelayS to it.departureDelayS })
    }

    @Test
    fun `departure is never earlier than arrival`() {
        // RED1 dwell is 60 s: arriving 120 s late, the train cannot leave less than 60 s late.
        val delta = matched(tripUpdate("T1-A", stus = listOf(stu(seq = 2, arrDelay = 120, depDelay = 0))))
        val red = delta.stops.single { it.stopSequence == 2 }
        assertEquals(120, red.arrivalDelayS)
        assertEquals(60, red.departureDelayS)
    }

    @Test
    fun `absolute times are converted to delays against the scheduled epoch`() {
        val delta = matched(
            tripUpdate("T1-A", stus = listOf(stu(seq = 3, arrTimeMs = at(DAY, hms(8, 20)) + 300_000))),
        )
        val str = delta.stops.single { it.stopSequence == 3 }
        assertEquals(300, str.arrivalDelayS)
        assertEquals(300, str.departureDelayS)
        assertEquals(300, delta.stops.single { it.stopSequence == 4 }.arrivalDelayS)
    }

    @Test
    fun `updates keyed by stop_id are matched in order`() {
        val delta = matched(tripUpdate("T1-A", stus = listOf(stu(stopId = "STR1", depDelay = 90))))
        assertEquals(listOf(null, null, 90, 90), delta.stops.map { it.departureDelayS })
    }

    @Test
    fun `platform change at the same station sets actualStopId`() {
        val delta = matched(tripUpdate("T1-A", stus = listOf(stu(seq = 2, stopId = "RED2", depDelay = 0))))
        assertEquals("RED2", delta.stops.single { it.stopSequence == 2 }.actualStopId)
        assertEquals("RED1", delta.stops.single { it.stopSequence == 2 }.stopId)
        assertNull(delta.stops.single { it.stopSequence == 3 }.actualStopId)
    }

    @Test
    fun `platform change keyed by stop_id only resolves via the parent station`() {
        val delta = matched(tripUpdate("T1-A", stus = listOf(stu(stopId = "RED2", depDelay = 30))))
        val red = delta.stops.single { it.stopSequence == 2 }
        assertEquals("RED2", red.actualStopId)
        assertEquals(30, red.departureDelayS)
    }

    @Test
    fun `a stop id at a different station is not a platform change`() {
        val delta = matched(tripUpdate("T1-A", stus = listOf(stu(seq = 2, stopId = "STR1", depDelay = 0))))
        assertNull(delta.stops.single { it.stopSequence == 2 }.actualStopId)
    }

    @Test
    fun `cancelled trip has no stops`() {
        val delta = matched(tripUpdate("T1-A", relationship = TripRel.CANCELED))
        assertEquals(TripStatus.CANCELLED, delta.status)
        assertTrue(delta.stops.isEmpty())
        assertEquals("T1", delta.routeId)
    }

    @Test
    fun `skipped stop is flagged and does not change propagated delay`() {
        val delta = matched(
            tripUpdate(
                "T1-A",
                stus = listOf(stu(seq = 2, depDelay = 60), stu(seq = 3, relationship = StopRel.SKIPPED)),
            ),
        )
        val str = delta.stops.single { it.stopSequence == 3 }
        assertTrue(str.skipped)
        assertNull(str.arrivalDelayS)
        assertEquals(60, delta.stops.single { it.stopSequence == 4 }.arrivalDelayS)
    }

    @Test
    fun `added trip carries absolute stop times and a headsign`() {
        val t0 = at(DAY, hms(8, 5))
        val delta = matched(
            tripUpdate(
                "X-ADDED-1",
                relationship = TripRel.ADDED,
                routeId = "T1",
                stus = listOf(
                    stu(seq = 1, stopId = "CEN1", depTimeMs = t0),
                    stu(seq = 2, stopId = "RED1", arrTimeMs = t0 + 600_000, depTimeMs = t0 + 660_000),
                    stu(seq = 3, stopId = "HBY1", arrTimeMs = t0 + 1_800_000),
                ),
            ),
        )
        assertEquals(TripStatus.ADDED, delta.status)
        assertEquals("X-ADDED-1", delta.tripId)
        assertEquals("T1", delta.routeId)
        assertEquals("Hornsby", delta.headsign)
        assertEquals(
            listOf(
                AddedStop(1, "CEN1", t0, t0),
                AddedStop(2, "RED1", t0 + 600_000, t0 + 660_000),
                AddedStop(3, "HBY1", t0 + 1_800_000, t0 + 1_800_000),
            ),
            delta.addedStops,
        )
        assertTrue(delta.stops.isEmpty())
    }

    @Test
    fun `replacement trip uses scheduled times plus delays when no absolute time is given`() {
        val delta = matched(
            tripUpdate(
                "T1-A",
                relationship = TripRel.REPLACEMENT,
                stus = listOf(stu(seq = 1, depDelay = 60), stu(seq = 4, arrDelay = 120)),
                properties = TripUpdate.TripProperties(trip_headsign = "Hornsby via Strathfield"),
            ),
        )
        assertEquals(TripStatus.REPLACEMENT, delta.status)
        assertEquals("Hornsby via Strathfield", delta.headsign)
        assertEquals(
            listOf(
                AddedStop(1, "CEN1", at(DAY, hms(8, 1)), at(DAY, hms(8, 1))),
                AddedStop(4, "HBY1", at(DAY, hms(8, 32)), at(DAY, hms(8, 32))),
            ),
            delta.addedStops,
        )
    }

    @Test
    fun `missing start_date is inferred from the dates the trip runs and the feed time`() {
        val delta = matched(tripUpdate("T1-A", startDate = null, stus = listOf(stu(seq = 2, depDelay = 0))))
        assertEquals(DAY, delta.startDate)
        // Same trip seen just after 08:00 the next day belongs to the next service date.
        val next = matched(
            tripUpdate("T1-A", startDate = null, stus = listOf(stu(seq = 2, depDelay = 0))),
            feedTimeMs = at(NEXT_DAY, hms(8, 2)),
        )
        assertEquals(NEXT_DAY, next.startDate)
    }

    @Test
    fun `missing start_date uses absolute stop times when present`() {
        // Feed time is late on DAY, but the update's absolute time is the next morning's run.
        val delta = matched(
            tripUpdate("T1-A", startDate = null, stus = listOf(stu(seq = 3, arrTimeMs = at(NEXT_DAY, hms(8, 21))))),
            feedTimeMs = at(DAY, hms(23, 50)),
        )
        assertEquals(NEXT_DAY, delta.startDate)
        assertEquals(60, delta.stops.single { it.stopSequence == 3 }.arrivalDelayS)
    }

    @Test
    fun `after-midnight trip is matched to the previous service date`() {
        val afterMidnight = at(NEXT_DAY, hms(0, 25)) // 00:25 on 12 Nov = 24:25 of service day 11 Nov
        assertEquals(at(DAY, hms(24, 25)), afterMidnight)
        val inferred = matched(
            tripUpdate("T1-LATE", startDate = null, stus = listOf(stu(seq = 2, arrTimeMs = at(NEXT_DAY, hms(0, 41))))),
            feedTimeMs = afterMidnight,
        )
        assertEquals(DAY, inferred.startDate)
        assertEquals(60, inferred.stops.single { it.stopSequence == 2 }.arrivalDelayS)

        val explicit = matched(tripUpdate("T1-LATE", startDate = DAY, stus = listOf(stu(seq = 1, depDelay = 0))), afterMidnight)
        assertEquals(DAY, explicit.startDate)
        assertEquals(3, explicit.stops.size)
    }

    @Test
    fun `start_date on which the trip does not run is not matched`() {
        val result = matcher.match(tripUpdate("T1-B", startDate = PREV_DAY), morning)
        assertIs<MatchResult.Unmatched>(result)
    }

    @Test
    fun `unknown trip is unmatched and never guessed`() {
        assertIs<MatchResult.Unmatched>(matcher.match(tripUpdate("NOPE", stus = listOf(stu(seq = 1, depDelay = 0))), morning))
        assertIs<MatchResult.Unmatched>(matcher.match(tripUpdate(null), morning))
    }

    @Test
    fun `inference fails when no candidate date fits`() {
        val result = matcher.match(tripUpdate("T1-A", startDate = null), at(DAY, hms(15, 0)))
        assertIs<MatchResult.Unmatched>(result)
    }

    @Test
    fun `only remaining stops are included`() {
        // 08:15 feed time: CEN1 (08:00) and RED1 (dep 08:11) are more than 2 min in the past.
        val delta = matched(tripUpdate("T1-A", stus = listOf(stu(seq = 1, depDelay = 0))), at(DAY, hms(8, 15)))
        assertEquals(listOf(3, 4), delta.stops.map { it.stopSequence })
        // A late train keeps its stop until it has actually left.
        val late = matched(tripUpdate("T1-A", stus = listOf(stu(seq = 2, depDelay = 240))), at(DAY, hms(8, 15)))
        assertEquals(listOf(2, 3, 4), late.stops.map { it.stopSequence })
        // Everything in the past: finished, nothing to serve.
        assertIs<MatchResult.Finished>(matcher.match(tripUpdate("T1-A"), at(DAY, hms(9, 0))))
    }

    @Test
    fun `trip-level delay applies when there are no stop updates`() {
        val delta = matched(tripUpdate("T1-A").copy(delay = 45))
        assertTrue(delta.stops.all { it.arrivalDelayS == 45 && it.departureDelayS == 45 })
    }

    @Test
    fun `trip update timestamp and vehicle id are carried`() {
        val delta = matched(tripUpdate("T1-A", timestampMs = morning - 10_000, vehicleId = "set-42"))
        assertEquals(morning - 10_000, delta.updatedMs)
        assertEquals("set-42", delta.vehicleId)
    }
}
