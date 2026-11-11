package app.transittracker.server.watch

import app.transittracker.model.WatchRules
import app.transittracker.server.spi.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WatchValidationTest {

    private fun Harness.rejects(code: String, watch: app.transittracker.model.WatchDto) {
        val e = assertFailsWith<ApiException> { engine.upsert(DEVICE, watch) }
        assertEquals(400, e.status)
        assertEquals(code, e.code, e.message)
    }

    @Test
    fun `valid commute is stored with created and updated times`() {
        val h = Harness()
        val created = h.clock.now
        val stored = h.engine.upsert(DEVICE, commute(days = listOf(5, 1, 1)))
        assertEquals(created, stored.createdMs)
        assertEquals(created, stored.updatedMs)
        assertEquals(listOf(1, 5), stored.daysOfWeek)
        assertEquals(listOf(stored), h.engine.list(DEVICE))

        h.clock.now += 60_000
        val updated = h.engine.upsert(DEVICE, commute(label = "Work"))
        assertEquals(created, updated.createdMs)
        assertEquals(created + 60_000, updated.updatedMs)
    }

    @Test
    fun `stations must exist and differ`() {
        val h = Harness()
        h.rejects("unknown_station", commute(origin = "NOPE"))
        h.rejects("unknown_station", commute(destination = "NOPE"))
        h.rejects("same_station", commute(destination = "STR"))
        h.rejects("same_station", commute(destination = "STR3")) // a platform of the origin station
    }

    @Test
    fun `a route must connect the stations`() {
        val h = Harness()
        h.rejects("no_route", commute(destination = "LID"))
        h.rejects("no_route", commute(routes = listOf("T3")))
        h.rejects("unknown_route", commute(routes = listOf("T99")))
        h.engine.upsert(DEVICE, commute(routes = listOf("T9")))
    }

    @Test
    fun `window must be within one day`() {
        val h = Harness()
        h.rejects("invalid_window", commute(from = 3600, to = 3600))
        h.rejects("invalid_window", commute(from = 7200, to = 3600))
        h.rejects("invalid_window", commute(from = -1, to = 3600))
        h.rejects("invalid_window", commute(from = 82_800, to = 86_401))
        h.engine.upsert(DEVICE, commute(from = 0, to = 86_400))
    }

    @Test
    fun `days lead and rules are checked`() {
        val h = Harness()
        h.rejects("invalid_days", commute(days = emptyList()))
        h.rejects("invalid_days", commute(days = listOf(0, 1)))
        h.rejects("invalid_days", commute(days = listOf(8)))
        h.rejects("invalid_lead", commute(lead = -1))
        h.rejects("invalid_lead", commute(lead = 181))
        h.rejects("invalid_rules", commute(rules = WatchRules(delayThresholdMin = 0)))
        h.rejects("invalid_rules", commute(rules = WatchRules(delayStepMin = 500)))
        h.rejects("invalid_id", commute(id = " "))
        h.engine.upsert(DEVICE, commute(lead = 180, days = listOf(7)))
    }

    @Test
    fun `at most ten watches per device`() {
        val h = Harness()
        repeat(10) { h.engine.upsert(DEVICE, commute(id = "w$it")) }
        h.rejects("too_many_watches", commute(id = "w10"))
        // Updating an existing watch is still allowed.
        h.engine.upsert(DEVICE, commute(id = "w3", label = "renamed"))
        // Another device has its own allowance.
        h.devices.add("dev2")
        h.engine.upsert("dev2", commute(id = "w10"))
    }

    @Test
    fun `single service needs a trip that runs that date and serves both stations`() {
        val h = Harness()
        h.rejects("missing_trip", single(tripId = null))
        h.rejects("missing_trip", single(date = null))
        h.rejects("invalid_service_date", single(date = "2026-11-11"))
        h.rejects("unknown_trip", single(tripId = "NOPE"))
        h.rejects("trip_not_running", single(tripId = "T1-WKDY-0820", date = "20261114")) // Saturday
        h.rejects("trip_not_serving_stations", single(tripId = "R1-0745")) // runs CEN -> STR
        h.rejects("trip_not_serving_stations", single(tripId = "T3-0730"))
        val stored = h.engine.upsert(DEVICE, single(tripId = "T1-WKDY-0820"))
        assertEquals("T1-WKDY-0820", stored.tripId)
    }

    @Test
    fun `timetable checks are skipped while no timetable is loaded`() {
        val h = Harness(timetable = null)
        h.engine.upsert(DEVICE, commute(origin = "X", destination = "Y"))
        h.engine.upsert(DEVICE, single(tripId = "anything"))
        h.rejects("same_station", commute(origin = "X", destination = "X"))
        h.rejects("invalid_window", commute(origin = "X", destination = "Y", from = 10, to = 5))
        assertTrue(h.engine.coveredTrips(DEVICE, "w1").isEmpty())
    }
}
