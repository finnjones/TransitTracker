package app.transittracker.core

import app.transittracker.model.Alert
import app.transittracker.model.AlertsResponse
import app.transittracker.model.RealtimeResponse
import app.transittracker.model.StopDelta
import app.transittracker.model.VehiclesResponse
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PollingTest {
    private val start = at(7, 0)

    private class Rig(val server: FakeServer, val poller: LivePoller, val timeSync: TimeSync)

    private fun TestScope.rig(): Rig {
        val clock = virtualClock(start)
        val server = FakeServer(this) { clock.nowMillis() }
        val timeSync = TimeSync(clock)
        val api = ApiClient(server.client, "https://tt.test/", timeSync) { "tok" }
        val poller = LivePoller(api, timeSync, backgroundScope)
        poller.start()
        return Rig(server, poller, timeSync)
    }

    private fun TestScope.rtTimes(r: Rig) = r.server.requestsTo("/v1/rt").map { it.timeMs }

    @Test
    fun pollingFollowsServerInterval() = runTest {
        val r = rig()
        var interval = 20
        r.server.on("GET", "/v1/rt") { realtime(RealtimeResponse(serverTimeMs = start, pollIntervalS = interval)) }
        val lease = r.poller.leaseRoutes()
        lease.set(setOf("R1", "R2"))
        runCurrent()
        assertEquals(emptyList(), rtTimes(r), "no polling before onForeground")

        r.poller.onForeground()
        runCurrent()
        advanceTimeBy(40_001)
        assertEquals(listOf(0L, 20_000L, 40_000L), rtTimes(r))
        val first = r.server.requestsTo("/v1/rt").first()
        assertEquals("R1,R2", first.query["routes"])
        assertEquals("application/x-protobuf", first.headers["Accept"])
        assertEquals("Bearer tok", first.headers["Authorization"])

        interval = 45 // the 60 s response tells us to slow down
        advanceTimeBy(20_000)
        advanceTimeBy(45_000)
        advanceTimeBy(45_000)
        assertEquals(listOf(0L, 20_000L, 40_000L, 60_000L, 105_000L, 150_000L), rtTimes(r))
        lease.close()
    }

    @Test
    fun noPollingWhenNothingObserved() = runTest {
        val r = rig()
        r.server.on("GET", "/v1/rt") { realtime(RealtimeResponse(serverTimeMs = start)) }
        r.poller.onForeground()
        advanceTimeBy(600_000)
        assertEquals(emptyList(), rtTimes(r))

        val lease = r.poller.leaseRoutes()
        lease.set(setOf("R1"))
        runCurrent()
        assertEquals(listOf(0L), rtTimes(r).map { it - 600_000 })
        lease.close()
        advanceTimeBy(600_000)
        assertEquals(1, rtTimes(r).size, "stops once the last observer goes")
    }

    @Test
    fun referenceCountingAndNarrowingDoesNotRefetch() = runTest {
        val r = rig()
        r.server.on("GET", "/v1/rt") { realtime(RealtimeResponse(serverTimeMs = start)) }
        r.poller.onForeground()
        val a = r.poller.leaseRoutes().also { it.set(setOf("R1")) }
        val b = r.poller.leaseRoutes().also { it.set(setOf("R1", "R2")) }
        runCurrent()
        assertEquals("R1,R2", r.server.requestsTo("/v1/rt").last().query["routes"])
        val count = rtTimes(r).size
        b.close() // R1 still observed by a: no immediate refetch, next poll on schedule
        runCurrent()
        assertEquals(count, rtTimes(r).size)
        advanceTimeBy(30_001)
        assertEquals("R1", r.server.requestsTo("/v1/rt").last().query["routes"])
        a.close()
    }

    @Test
    fun pausesInBackgroundAndResumesImmediately() = runTest {
        val r = rig()
        r.server.on("GET", "/v1/rt") { realtime(RealtimeResponse(serverTimeMs = start, pollIntervalS = 30)) }
        val lease = r.poller.leaseRoutes().also { it.set(setOf("R1")) }
        r.poller.onForeground()
        runCurrent()
        advanceTimeBy(10_000)
        r.poller.onBackground()
        advanceTimeBy(300_000)
        assertEquals(listOf(0L), rtTimes(r))
        r.poller.onForeground()
        runCurrent()
        advanceTimeBy(30_001)
        assertEquals(listOf(0L, 310_000L, 340_000L), rtTimes(r))
        lease.close()
    }

    @Test
    fun backsOffExponentiallyOnErrorsCappedAtFiveMinutes() = runTest {
        val r = rig()
        var fail = true
        r.server.on("GET", "/v1/rt") {
            if (fail) respond("""{"code":"upstream","title":"Upstream down","status":503}""", HttpStatusCode.ServiceUnavailable, r.server.jsonHeaders())
            else realtime(RealtimeResponse(serverTimeMs = start))
        }
        val lease = r.poller.leaseRoutes().also { it.set(setOf("R1")) }
        r.poller.onForeground()
        runCurrent()
        advanceTimeBy(720_001)
        // 30 s interval: waits 60, 120, 240, then 300 (cap), 300.
        assertEquals(listOf(0L, 60_000L, 180_000L, 420_000L, 720_000L), rtTimes(r))
        fail = false
        advanceTimeBy(300_000) // next attempt at 1 020 000 succeeds
        advanceTimeBy(30_000)
        assertEquals(listOf(1_020_000L, 1_050_000L), rtTimes(r).drop(5))
        assertEquals(60_000, LivePoller.backoffMs(30_000, 1))
        assertEquals(300_000, LivePoller.backoffMs(30_000, 10))
        lease.close()
    }

    @Test
    fun notModifiedKeepsCachedDeltas() = runTest {
        val r = rig()
        var calls = 0
        r.server.on("GET", "/v1/rt") { req ->
            calls++
            if (req.headers["If-None-Match"] == "\"rt1\"") {
                respond(ByteArray(0), HttpStatusCode.NotModified, r.server.protoHeaders())
            } else {
                realtime(
                    RealtimeResponse(serverTimeMs = start, trips = listOf(delta("R1-04", DAY, "R1", StopDelta(1, "A1", 120, 120)))),
                    etag = "\"rt1\"",
                )
            }
        }
        val lease = r.poller.leaseRoutes().also { it.set(setOf("R1")) }
        r.poller.onForeground()
        runCurrent()
        assertEquals(1, r.poller.snapshot.value.trips.size)
        advanceTimeBy(30_001)
        assertEquals(2, calls)
        assertEquals("\"rt1\"", r.server.requestsTo("/v1/rt").last().headers["If-None-Match"])
        val snap = r.poller.snapshot.value
        assertEquals(120, snap.trips.values.single().delta.stops.single().departureDelayS)
        assertEquals(start + 30_000, snap.lastUpdateMs, "a 304 refreshes the data time")
        lease.close()
    }

    @Test
    fun alertsRefetchedOnlyWhenEtagChanges() = runTest {
        val r = rig()
        val etags = ArrayDeque(listOf("a1", "a1", "a1", "a2", "a2"))
        r.server.on("GET", "/v1/rt") { realtime(RealtimeResponse(serverTimeMs = start, alertsEtag = etags.removeFirstOrNull() ?: "a2")) }
        var alertVersion = 0
        r.server.on("GET", "/v1/alerts") {
            alertVersion++
            json(AlertsResponse.serializer(), AlertsResponse(start, listOf(Alert("x$alertVersion", "Alert $alertVersion"))))
        }
        val lease = r.poller.leaseRoutes().also { it.set(setOf("R1")) }
        r.poller.onForeground()
        runCurrent()
        advanceTimeBy(4 * 30_000 + 1)
        assertEquals(5, rtTimes(r).size)
        assertEquals(listOf(0L, 90_000L), r.server.requestsTo("/v1/alerts").map { it.timeMs })
        assertEquals("x2", r.poller.alerts.value.single().id)
        lease.close()
    }

    @Test
    fun trackerPollsTripAtTrackerInterval() = runTest {
        val r = rig()
        r.server.on("GET", "/v1/rt/trip/R1-04") {
            realtime(RealtimeResponse(serverTimeMs = start, trackerPollIntervalS = 10, trips = listOf(delta("R1-04", DAY, "R1", StopDelta(1, "A1", 60, 60)))))
        }
        val lease = r.poller.leaseTrip().also { it.set(setOf(TripKey("R1-04", DAY))) }
        r.poller.onForeground()
        runCurrent()
        advanceTimeBy(20_001)
        val trips = r.server.requestsTo("/v1/rt/trip/R1-04")
        assertEquals(listOf(0L, 10_000L, 20_000L), trips.map { it.timeMs })
        assertEquals(DAY, trips.first().query["date"])
        assertNotNull(r.poller.snapshot.value["R1-04", DAY])
        lease.close()
    }

    @Test
    fun vehiclesPolledForObservedRoutes() = runTest {
        val r = rig()
        r.server.on("GET", "/v1/vehicles") {
            val body = VehiclesResponse(
                start,
                vehicles = listOf(
                    app.transittracker.model.VehiclePosition("v1", "R1-04", DAY, "R1", app.transittracker.model.LatLng(-33.8, 151.0), timestampMs = start),
                ),
            )
            respond(ProtoBuf.encodeToByteArray(VehiclesResponse.serializer(), body), HttpStatusCode.OK, r.server.protoHeaders())
        }
        val lease = r.poller.leaseVehicles().also { it.set(setOf("R1")) }
        r.poller.onForeground()
        runCurrent()
        assertEquals("v1", r.poller.vehicles.value["R1"]?.single()?.vehicleId)
        lease.close()
    }

    @Test
    fun serverTimeCorrectsClock() = runTest {
        val r = rig()
        r.server.serverTimeMs = { start + testScheduler.currentTime + 5_000 }
        r.server.on("GET", "/v1/rt") { realtime(RealtimeResponse(serverTimeMs = start)) }
        val lease = r.poller.leaseRoutes().also { it.set(setOf("R1")) }
        r.poller.onForeground()
        runCurrent()
        assertEquals(5_000, r.timeSync.offsetMillis)
        assertTrue(r.timeSync.nowMillis() - (start + testScheduler.currentTime) == 5_000L)
        lease.close()
    }
}
