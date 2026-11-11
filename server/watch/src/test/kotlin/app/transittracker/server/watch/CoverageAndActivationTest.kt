package app.transittracker.server.watch

import app.transittracker.server.spi.DemandKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoverageAndActivationTest {

    private fun Harness.covered(watchId: String = "w1", date: String? = null) =
        engine.coveredTrips(DEVICE, watchId).filter { date == null || it.serviceDate == date }

    private fun Harness.coveredIds(watchId: String = "w1", date: String? = null) =
        covered(watchId, date).map { "${it.tripId}/${it.serviceDate}" }

    // ---- coverage ----

    @Test
    fun `normal Wednesday covers trips leaving the origin inside the window`() {
        val h = Harness(now = sydney(WED, "05:00"))
        h.engine.upsert(DEVICE, commute())
        val today = h.covered(date = TODAY)
        assertEquals(listOf("T1-0742", "T9-0751"), today.map { it.tripId })
        assertEquals(sydney(WED, "07:42"), today[0].departureMillis)
        assertEquals(sydney(WED, "07:57"), today[0].arrivalMillis)
        assertEquals("STR3", today[0].originStopId)
        assertEquals(sydney(WED, "07:15"), today[0].windowStartMillis)
        // Yesterday and tomorrow are weekdays too; the reverse-direction trip is never covered.
        assertEquals(setOf("20261110", TODAY, "20261112"), h.covered().map { it.serviceDate }.toSet())
        assertTrue(h.covered().none { it.tripId == "R1-0745" })
    }

    @Test
    fun `route filter limits coverage`() {
        val h = Harness(now = sydney(WED, "05:00"))
        h.engine.upsert(DEVICE, commute(routes = listOf("T9")))
        assertEquals(listOf("T9-0751/$TODAY"), h.coveredIds(date = TODAY))
    }

    @Test
    fun `days of week are honoured`() {
        val h = Harness(now = sydney(WED, "05:00"))
        h.engine.upsert(DEVICE, commute(days = listOf(2, 4))) // Tuesday and Thursday only
        assertEquals(emptyList(), h.coveredIds(date = TODAY))
        assertEquals(setOf("20261110", "20261112"), h.covered().map { it.serviceDate }.toSet())

        // Saturday: weekday watch covers only Friday (yesterday's) trips, none for Sat/Sun.
        val sat = Harness(now = sydney("2026-11-14", "05:00"))
        sat.engine.upsert(DEVICE, commute())
        assertEquals(setOf("20261113"), sat.covered().map { it.serviceDate }.toSet())
        // A trip that only runs on weekdays is not covered on a weekend window.
        sat.engine.upsert(DEVICE, commute(id = "w2", days = listOf(6), from = TestNetwork.secs("08:00"), to = TestNetwork.secs("09:00")))
        assertEquals(listOf("T1-0812/20261114"), sat.coveredIds("w2"))
    }

    @Test
    fun `daylight saving starts 2026-10-04`() {
        val h = Harness(now = sydney("2026-10-04", "06:00"))
        // 00:15-00:45 wall clock. GTFS 01:30 on the 4th is 00:30 local (noon-minus-12h is 23:00 on
        // the 3rd), as is GTFS 24:30 of the 3rd. GTFS 00:30 on the 4th is 23:30 on the 3rd.
        h.engine.upsert(DEVICE, commute(id = "night", days = listOf(7), from = TestNetwork.secs("00:15"), to = TestNetwork.secs("00:45")))
        assertEquals(setOf("T1-G2430/20261003", "T1-G0130/20261004"), h.coveredIds("night").toSet())
        assertTrue(h.covered("night").all { it.departureMillis == sydney("2026-10-04", "00:30") })

        h.engine.upsert(DEVICE, commute(id = "early", days = listOf(7), from = TestNetwork.secs("01:15"), to = TestNetwork.secs("01:45")))
        assertEquals(listOf("T1-G0230/20261004"), h.coveredIds("early"))
        assertEquals(sydney("2026-10-04", "01:30"), h.covered("early").single().departureMillis)

        h.engine.upsert(DEVICE, commute(id = "morning", days = listOf(7), from = TestNetwork.secs("07:00"), to = TestNetwork.secs("08:00")))
        assertEquals(listOf("T1-0705", "T1-0742", "T9-0751"), h.covered("morning").map { it.tripId })
        assertEquals(sydney("2026-10-04", "07:42"), h.covered("morning")[1].departureMillis)
        // The window is 1 hour of real time.
        assertEquals(3_600_000L, sydney("2026-10-04", "08:00") - h.covered("morning")[0].windowStartMillis)
    }

    @Test
    fun `daylight saving ends 2026-04-05`() {
        val h = Harness(now = sydney("2026-04-05", "06:00"))
        // Noon-minus-12h on the 5th is 01:00 local: GTFS 00:30 is 01:30 local, GTFS 01:30 is the
        // first 02:30, GTFS 02:30 is the second 02:30. GTFS 24:30 of the 4th is 00:30 on the 5th.
        h.engine.upsert(DEVICE, commute(id = "night", days = listOf(7), from = TestNetwork.secs("00:15"), to = TestNetwork.secs("00:45")))
        assertEquals(listOf("T1-G2430/20260404"), h.coveredIds("night"))

        h.engine.upsert(DEVICE, commute(id = "one", days = listOf(7), from = TestNetwork.secs("01:15"), to = TestNetwork.secs("01:45")))
        assertEquals(listOf("T1-G0030/20260405"), h.coveredIds("one"))
        assertEquals(sydney("2026-04-05", "01:30"), h.covered("one").single().departureMillis)

        // An ambiguous wall-clock window resolves to the first (daylight-time) occurrence.
        h.engine.upsert(DEVICE, commute(id = "two", days = listOf(7), from = TestNetwork.secs("02:15"), to = TestNetwork.secs("02:45")))
        assertEquals(listOf("T1-G0130/20260405"), h.coveredIds("two"))

        h.engine.upsert(DEVICE, commute(id = "morning", days = listOf(7), from = TestNetwork.secs("07:40"), to = TestNetwork.secs("07:45")))
        assertEquals(sydney("2026-04-05", "07:42"), h.covered("morning").single().departureMillis)
    }

    @Test
    fun `single service covers its one trip`() {
        val h = Harness()
        h.engine.upsert(DEVICE, single())
        val trip = h.engine.coveredTrips(DEVICE, "s1").single()
        assertEquals("T1-0742", trip.tripId)
        assertEquals(sydney(WED, "07:42"), trip.departureMillis)
        assertEquals(sydney(WED, "07:57"), trip.arrivalMillis)
    }

    @Test
    fun `coverage is recomputed at 03_30 each day`() = runTest {
        val h = Harness(now = sydney("2026-11-10", "23:00"))
        h.engine.upsert(DEVICE, commute())
        assertEquals(setOf("20261109", "20261110", TODAY), h.covered().map { it.serviceDate }.toSet())
        h.at(WED, "03:00")
        h.engine.tick()
        assertTrue(h.covered(date = "20261112").isEmpty())
        h.at(WED, "03:31")
        h.engine.tick()
        assertEquals(setOf("20261110", TODAY, "20261112"), h.covered().map { it.serviceDate }.toSet())
    }

    @Test
    fun `coverage is recomputed when the timetable changes`() = runTest {
        val h = Harness(now = sydney(WED, "05:00"), timetable = null)
        h.engine.upsert(DEVICE, commute())
        h.engine.start(backgroundScope)
        runCurrent()
        assertTrue(h.covered().isEmpty())
        h.timetableFlow.value = TestNetwork.timetable()
        runCurrent()
        assertEquals(listOf("T1-0742", "T9-0751"), h.covered(date = TODAY).map { it.tripId })
        h.timetableFlow.value = TestNetwork.timetable(listOf(TestNetwork.strToCen("T1-0730", "T1", "07:30")))
        runCurrent()
        assertEquals(listOf("T1-0730", "T1-0742", "T9-0751"), h.covered(date = TODAY).map { it.tripId })
    }

    // ---- activation ----

    private fun Harness.warmCalls() = live.keepWarmCalls.filter { it.key == "watch:$DEVICE:w1" }

    @Test
    fun `active watches keep their routes warm until the last trip departs`() = runTest {
        val h = Harness(now = sydney(WED, "06:20"))
        h.engine.upsert(DEVICE, commute())
        h.live.keepWarmCalls.clear()
        h.engine.tick()
        assertEquals(emptyList(), h.warmCalls(), "not active before window start - lead (06:30)")
        assertEquals(0, h.engine.status().activeWatches)

        h.at(WED, "06:40")
        h.engine.tick()
        assertEquals(
            listOf(KeepWarmCall("watch:$DEVICE:w1", setOf("T1", "T9"), DemandKind.WATCH, sydney(WED, "07:53"))),
            h.warmCalls(),
        )
        assertEquals(1, h.engine.status().activeWatches)

        // A delayed last trip extends the activity.
        h.live.cached["T9-0751" to TODAY] = h.delta("T9-0751", delayS = 300)
        h.engine.tick()
        assertEquals(sydney(WED, "07:58"), h.warmCalls().last().untilMillis)

        h.live.cached.clear()
        h.live.keepWarmCalls.clear()
        h.at(WED, "07:54")
        h.engine.tick()
        assertEquals(emptyList(), h.warmCalls())
        assertEquals(1, h.engine.status().totalWatches)
        assertEquals(0, h.engine.status().activeWatches)
    }

    @Test
    fun `keepWarm uses only the filtered routes and starts on upsert`() {
        val h = Harness(now = sydney(WED, "07:00"))
        h.engine.upsert(DEVICE, commute(routes = listOf("T1")))
        assertEquals(
            listOf(KeepWarmCall("watch:$DEVICE:w1", setOf("T1"), DemandKind.WATCH, sydney(WED, "07:44"))),
            h.warmCalls(),
        )
    }

    @Test
    fun `deleting a watch releases its demand and coverage`() {
        val h = Harness(now = sydney(WED, "07:00"))
        h.engine.upsert(DEVICE, commute())
        assertTrue(h.engine.delete(DEVICE, "w1"))
        assertEquals(KeepWarmCall("watch:$DEVICE:w1", emptySet(), DemandKind.WATCH, h.clock.now), h.warmCalls().last())
        assertTrue(h.covered().isEmpty())
        assertEquals(emptyList(), h.engine.list(DEVICE))
    }

    @Test
    fun `two devices with the same watch id keep separate keep-warm registrations`() {
        val h = Harness(now = sydney(WED, "07:00"))
        h.devices.add("dev2")
        h.engine.upsert(DEVICE, commute(routes = listOf("T1")))
        h.engine.upsert("dev2", commute(routes = listOf("T9")))
        assertTrue(h.engine.delete("dev2", "w1"))
        val latest = h.live.keepWarmCalls.groupBy { it.key }.mapValues { it.value.last() }
        assertEquals(setOf("T1"), latest.getValue("watch:$DEVICE:w1").routeIds, "dev1's demand survives dev2's delete")
        assertEquals(emptySet(), latest.getValue("watch:dev2:w1").routeIds)
    }
}
