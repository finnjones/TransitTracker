package app.transittracker.core

import app.transittracker.model.AddedStop
import app.transittracker.model.Alert
import app.transittracker.model.AlertsResponse
import app.transittracker.model.ConfigResponse
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.RealtimeResponse
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopDelta
import app.transittracker.model.TripDelta
import app.transittracker.model.TripLeg
import app.transittracker.model.TripStatus
import app.transittracker.model.WallClock
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchesResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The additions the screens asked for: trip alerts, service shape and stop positions, config in DataStatus, pull to refresh. */
class ScreenAdditionsTest {
    private val stationTrip = SavedTrip("t1", "Alpha", "Bravo", listOf(TripLeg(setOf("A"), setOf("B"))))
    private val platformTrip = SavedTrip("t2", "Alpha", "Charlie", listOf(TripLeg(setOf("A1"), setOf("C1"), routeIds = setOf("R1"))))

    private val alerts = listOf(
        Alert("route-r1", "R1 delays", routeIds = listOf("R1")),
        Alert("route-r2", "R2 delays", routeIds = listOf("R2")),
        Alert("stop-a1", "Lift out at Alpha platform 1", stopIds = listOf("A1")),
        Alert("stop-a2", "Alpha platform 2 closed", stopIds = listOf("A2")),
        Alert("station-a", "Alpha station works", stopIds = listOf("A")),
        Alert("station-b", "Bravo concourse closed", stopIds = listOf("B")),
        Alert("stop-c1", "Charlie platform 1", stopIds = listOf("C1")),
        Alert("mode-train", "All trains: industrial action", modes = listOf(Mode.TRAIN)),
        Alert("mode-bus", "All buses: detours", modes = listOf(Mode.BUS)),
        Alert("trip-only", "One trip changed", tripIds = listOf("R1-04"), modes = listOf(Mode.TRAIN)),
    )

    // ---- alertsFor ----

    @Test
    fun alertMatchingRules() {
        val tt = TestNet.timetable()
        assertEquals(
            listOf("route-r1", "stop-a1", "stop-a2", "station-a", "station-b", "mode-train"),
            alertsForTrip(stationTrip, alerts, tt).map { it.id },
            "a station trip: its route (worked out from the timetable), its stations and their platforms, network-wide train alerts",
        )
        assertEquals(
            listOf("route-r1", "stop-a1", "station-a", "stop-c1", "mode-train"),
            alertsForTrip(platformTrip, alerts, tt).map { it.id },
            "a platform trip: the parent station matches, the other platform does not",
        )
        assertEquals(listOf("route-r1", "stop-a1", "stop-c1"), alertsForTrip(platformTrip, alerts, null).map { it.id }, "no timetable: saved ids only")
        assertTrue(alertsForTrip(stationTrip.copy(legs = emptyList()), alerts, tt).isEmpty())
    }

    @Test
    fun alertsForFollowsAlertsAndTrips() = runTest {
        val h = Harness(this)
        h.join()
        h.preinstall("v1")
        h.serveConfig(h.packInfo("v1"))
        var served = alerts.take(2)
        var etag = "e1"
        h.server.on("GET", "/v1/alerts") { json(AlertsResponse.serializer(), AlertsResponse(h.clock.nowMillis(), served)) }
        h.server.on("GET", "/v1/rt") { realtime(RealtimeResponse(serverTimeMs = h.clock.nowMillis(), alertsEtag = etag)) }
        h.core.saveTrip(stationTrip)
        h.core.start()

        val seen = mutableListOf<List<String>>()
        backgroundScope.launch { h.core.alertsFor("t1").collect { list -> seen += list.map { it.id } } }
        runCurrent()
        assertEquals(listOf("route-r1"), seen.last())

        // New alerts arrive with the next real-time poll's ETag.
        h.core.poller.leaseRoutes().set(setOf("R1"))
        h.core.onForeground()
        runCurrent()
        served = alerts
        etag = "e2"
        advanceTimeBy(31_000)
        assertEquals(listOf("route-r1", "stop-a1", "stop-a2", "station-a", "station-b", "mode-train"), seen.last())

        // Editing the trip re-evaluates.
        h.core.saveTrip(stationTrip.copy(legs = listOf(TripLeg(setOf("B"), setOf("C"), routeIds = setOf("R2")))))
        runCurrent()
        assertEquals(listOf("route-r2", "station-b", "stop-c1"), seen.last())

        h.core.deleteTrip("t1")
        runCurrent()
        assertEquals(emptyList(), seen.last())
        assertTrue(h.core.alertsFor("missing").first().isEmpty())
    }

    // ---- Service shape and stop positions ----

    @Test
    fun serviceDetailHasShapeAndStopPositions() = runTest {
        val h = Harness(this)
        h.join()
        h.preinstall("v1")
        h.serveConfig(h.packInfo("v1"))
        h.core.start()
        val detail = h.core.service(ServiceRef("R1-04", DAY)).first { it.calls.isNotEmpty() }
        assertEquals("shape-R1", detail.shapeId)
        val byId = TestNet.stops.associateBy { it.id }
        assertEquals(listOf("A1", "B1", "C1").map { byId.getValue(it).position }, detail.calls.map { it.position })
        assertNull(h.core.service(ServiceRef("R2-01", DAY)).first { it.calls.isNotEmpty() }.shapeId, "trip without a shape")
    }

    @Test
    fun positionFollowsPlatformChangeAndAddedServices() {
        val moved = LatLng(-33.81, 151.01)
        val tt = InMemoryTimetable(TestNet.stops.map { if (it.id == "A2") it.copy(position = moved) else it }, TestNet.routes, TestNet.trips, "v1")
        val added = TripDelta(
            tripId = "EXTRA", startDate = DAY, routeId = "R1", status = TripStatus.ADDED,
            addedStops = listOf(AddedStop(1, "A1", departureMs = at(7, 0)), AddedStop(2, "C1", arrivalMs = at(7, 30))),
        )
        val snapshot = snapshotOf(
            delta("R1-04", DAY, "R1", StopDelta(1, "A1", 60, 60, actualStopId = "A2")),
            added,
            receivedMs = at(6, 50),
        )
        val overlay = Overlay(tt, snapshot, at(6, 50))
        val detail = overlay.serviceDetail(ServiceRef("R1-04", DAY))!!
        assertEquals(moved, detail.calls.first().position)
        assertEquals("A2", detail.calls.first().stopId)
        val extra = overlay.serviceDetail(ServiceRef("EXTRA", DAY))!!
        assertNull(extra.shapeId)
        assertEquals(listOf(tt.stop("A1")!!.position, tt.stop("C1")!!.position), extra.calls.map { it.position })
    }

    // ---- DataStatus from /v1/config ----

    @Test
    fun dataStatusCarriesConfig() = runTest {
        val h = Harness(this)
        h.join()
        h.server.on("GET", "/v1/config") {
            json(
                ConfigResponse.serializer(),
                ConfigResponse(attribution = "A", minAppVersion = "1.4.0", features = mapOf("planner" to true, "push" to false), maxWatches = 4),
            )
        }
        val before = h.core.dataStatus.first()
        assertEquals(emptyMap(), before.features)
        assertEquals("0", before.minAppVersion)
        assertEquals(10, before.maxWatches)

        assertTrue(h.core.checkForUpdates())
        val after = h.core.dataStatus.first { it.maxWatches == 4 }
        assertEquals(mapOf("planner" to true, "push" to false), after.features)
        assertEquals("1.4.0", after.minAppVersion)
    }

    // ---- Pull to refresh ----

    @Test
    fun pullToRefreshWhenNotJoined() = runTest {
        val h = Harness(this)
        assertFalse(h.core.checkForUpdates())
        assertFalse(h.core.refreshWatches())
        assertTrue(h.server.requests.isEmpty(), "nothing is asked without a device token")
    }

    @Test
    fun checkForUpdatesReportsWhetherTheServerAnswered() = runTest {
        val h = Harness(this)
        h.join()
        h.serveConfig(h.packInfo("v1"))
        assertTrue(h.core.checkForUpdates())
        assertEquals(listOf<PackUpdateResult>(PackUpdateResult.Updated("syd-rail", null, "v1")), h.core.lastPackReport.first())
        assertTrue(h.core.checkForUpdates(), "true even when there is nothing new")

        h.server.on("GET", "/v1/config") { throw RuntimeException("offline") }
        assertFalse(h.core.checkForUpdates())
        h.server.on("GET", "/v1/config") { respond("", HttpStatusCode.InternalServerError) }
        assertFalse(h.core.checkForUpdates())
        assertEquals(4, h.server.requestsTo("/v1/config").size)
    }

    @Test
    fun refreshWatchesReloadsTheCache() = runTest {
        val h = Harness(this)
        h.join()
        val watch = WatchDto(id = "w1", originStationId = "A", destinationStationId = "B")
        h.server.on("GET", "/v1/watches") { json(WatchesResponse.serializer(), WatchesResponse(listOf(watch))) }
        assertTrue(h.core.refreshWatches())
        assertEquals(listOf(watch), h.core.watches.first())
        assertTrue(h.kv.get(KvKeys.WATCHES)!!.contains("w1"))

        h.server.on("GET", "/v1/watches") { respond("", HttpStatusCode.ServiceUnavailable) }
        assertFalse(h.core.refreshWatches())
        assertEquals(listOf(watch), h.core.watches.first(), "the cached copy stays")
    }

    // ---- The fake core ----

    @Test
    fun fakeCoreAdditions() = runTest {
        val now = at(8, 3)
        val core = FakeTransitCore(clock = WallClock { now })
        assertEquals(setOf("fake-alert-trackwork", "fake-alert-lift"), core.alertsFor("trip-townhall-tallawong").first().map { it.id }.toSet())
        assertTrue(core.alertsFor("trip-parramatta-central").first().isEmpty())
        assertTrue(core.alertsFor("missing").first().isEmpty())
        core.saveTrip(FakeTransitCore.SAMPLE_TRIPS[0].copy(id = "new", legs = listOf(TripLeg(setOf(FakeData.CENTRAL), setOf(FakeData.TOWN_HALL)))))
        assertEquals(setOf("fake-alert-trackwork", "fake-alert-lift"), core.alertsFor("new").first().map { it.id }.toSet())

        val status = core.dataStatus.first()
        assertEquals(true, status.features["planner"])
        assertEquals("0", status.minAppVersion)
        assertEquals(10, status.maxWatches)
        core.minAppVersion.value = "9.0"
        core.features.value = mapOf("planner" to false)
        assertEquals("9.0", core.dataStatus.first().minAppVersion)
        assertEquals(false, core.dataStatus.first().features["planner"])

        assertTrue(core.checkForUpdates())
        assertTrue(core.refreshWatches())
        val notJoined = FakeTransitCore(clock = WallClock { now }, joined = false)
        assertFalse(notJoined.checkForUpdates())
        assertFalse(notJoined.refreshWatches())

        val departure = core.upcoming("trip-parramatta-central", 1).first().first().departure
        val detail = core.service(departure.ref).first()
        if (detail.status != app.transittracker.model.DepartureStatus.ADDED) assertEquals("shape-T1E", detail.shapeId)
        assertTrue(detail.calls.all { it.position != null })
    }
}
