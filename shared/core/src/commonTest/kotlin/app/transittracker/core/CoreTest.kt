package app.transittracker.core

import app.transittracker.model.AlarmKind
import app.transittracker.model.AlarmSpec
import app.transittracker.model.DepartureStatus
import app.transittracker.model.DeviceUpdate
import app.transittracker.model.JoinRequest
import app.transittracker.model.JoinResponse
import app.transittracker.model.JoinResult
import app.transittracker.model.PushKeys
import app.transittracker.model.RealtimeResponse
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopDelta
import app.transittracker.model.TestPushResponse
import app.transittracker.model.TripLeg
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchKind
import app.transittracker.model.WatchesResponse
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private val single = SavedTrip("t1", "Alpha", "Bravo", listOf(TripLeg(setOf("A"), setOf("B"))))

    @Test
    fun joinStoresTokenAndUsesIt() = runTest {
        val h = Harness(this)
        h.server.on("POST", "/v1/join") { req ->
            val body = ApiJson.decodeFromString(JoinRequest.serializer(), req.body!!)
            assertEquals("ABCD-1234", body.inviteCode)
            assertEquals("android", body.platform)
            assertEquals("1.2.3", body.appVersion)
            with(h.server) { json(JoinResponse.serializer(), JoinResponse("dev-1", "secret-token")) }
        }
        h.serveConfig(h.packInfo("v1"))
        h.core.start()
        assertFalse(h.core.joined.first())

        assertEquals(JoinResult.Joined("dev-1"), h.core.join(" ABCD-1234 "))
        assertNull(h.server.requestsTo("/v1/join").single().headers["Authorization"])
        assertTrue(h.core.joined.first())
        assertEquals("secret-token", h.kv.get(KvKeys.DEVICE_TOKEN))
        assertEquals("dev-1", h.kv.get(KvKeys.DEVICE_ID))
        runCurrent()
        // Joining triggers the config / pack check with the new token.
        assertEquals("Bearer secret-token", h.server.requestsTo("/v1/config").single().headers["Authorization"])
        assertEquals(listOf<PackUpdateResult>(PackUpdateResult.Updated("syd-rail", null, "v1")), h.core.lastPackReport.first())
    }

    @Test
    fun joinInvalidCodeAndServerFailure() = runTest {
        val h = Harness(this)
        var status = HttpStatusCode.BadRequest
        h.server.on("POST", "/v1/join") {
            respond("""{"code":"invalid_invite","title":"Invite code not valid","status":${status.value}}""", status, h.server.jsonHeaders())
        }
        assertEquals(JoinResult.InvalidCode, h.core.join("nope"))
        status = HttpStatusCode.ServiceUnavailable
        assertIs<JoinResult.Failed>(h.core.join("nope"))
        assertFalse(h.core.joined.first())
        assertNull(h.kv.get(KvKeys.DEVICE_TOKEN))
    }

    @Test
    fun pushTokenSentAfterJoin() = runTest {
        val h = Harness(this)
        h.server.on("PUT", "/v1/device") { respond("", HttpStatusCode.NoContent) }
        h.server.on("POST", "/v1/join") { json(JoinResponse.serializer(), JoinResponse("dev-1", "tok-1")) }
        h.core.updatePushToken("fcm-1")
        assertTrue(h.server.requestsTo("/v1/device").isEmpty(), "not sent before joining")
        assertEquals("fcm-1", h.kv.get(KvKeys.PUSH_TOKEN_PENDING))

        h.core.join("CODE")
        runCurrent()
        val first = h.server.requestsTo("/v1/device").single()
        assertEquals("Bearer tok-1", first.headers["Authorization"])
        assertEquals("fcm-1", ApiJson.decodeFromString(DeviceUpdate.serializer(), first.body!!).pushToken)
        assertNull(h.kv.get(KvKeys.PUSH_TOKEN_PENDING))

        h.core.updatePushToken("fcm-2")
        assertEquals("fcm-2", ApiJson.decodeFromString(DeviceUpdate.serializer(), h.server.requestsTo("/v1/device").last().body!!).pushToken)
        assertEquals("1.2.3", ApiJson.decodeFromString(DeviceUpdate.serializer(), h.server.requestsTo("/v1/device").last().body!!).appVersion)
    }

    @Test
    fun tripsPersistAndReorder() = runTest {
        val kv = InMemoryKeyValueStore()
        val store = TripStore(kv)
        store.save(single)
        store.save(single.copy(id = "t2", originName = "Bravo"))
        store.save(single.copy(id = "t3", originName = "Charlie"))
        store.save(single.copy(id = "t2", originName = "Bravo!")) // update keeps position
        assertEquals(listOf("t1", "t2", "t3"), store.trips.first().map { it.id })
        assertEquals("Bravo!", store.get("t2")?.originName)

        store.reorder(listOf("t3", "t1"))
        assertEquals(listOf("t3" to 0, "t1" to 1, "t2" to 2), store.trips.first().map { it.id to it.order })

        store.delete("t1")
        val reloaded = TripStore(kv).trips.first()
        assertEquals(listOf("t3" to 0, "t2" to 1), reloaded.map { it.id to it.order })
    }

    @Test
    fun tripsFlowEmitsUpdates() = runTest {
        val h = Harness(this)
        val seen = mutableListOf<List<String>>()
        val job = launch { h.core.trips.take(3).toList().mapTo(seen) { list -> list.map { it.id } } }
        runCurrent()
        h.core.saveTrip(single)
        runCurrent()
        h.core.saveTrip(single.copy(id = "t2"))
        job.join()
        assertEquals(listOf(emptyList(), listOf("t1"), listOf("t1", "t2")), seen)
    }

    @Test
    fun upcomingAppliesLiveDataAndStopsPollingWhenClosed() = runTest {
        val h = Harness(this)
        h.join()
        h.preinstall("v1")
        h.serveConfig(h.packInfo("v1"))
        h.server.on("GET", "/v1/rt") {
            with(h.server) {
                realtime(RealtimeResponse(serverTimeMs = h.clock.nowMillis(), trips = listOf(delta("R1-04", DAY, "R1", StopDelta(1, "A1", 240, 240)))))
            }
        }
        h.core.saveTrip(single)
        h.core.start()
        h.core.onForeground()

        val journeys = h.core.upcoming("t1", 3).first { it.firstOrNull()?.departure?.status == DepartureStatus.LATE }
        assertEquals(listOf("R1-04", "R1-05", "R1-06"), journeys.map { it.departure.ref.tripId })
        assertEquals(240, journeys[0].departure.delaySeconds)
        assertEquals(DepartureStatus.TIMETABLED, journeys[1].departure.status)
        assertEquals("R1", h.server.requestsTo("/v1/rt").first().query["routes"])

        runCurrent()
        val count = h.server.requestsTo("/v1/rt").size
        advanceTimeBy(600_000)
        assertEquals(count, h.server.requestsTo("/v1/rt").size, "no polling once the flow is gone")
    }

    @Test
    fun timetableForServiceDay() = runTest {
        val h = Harness(this)
        h.join()
        h.preinstall("v1")
        h.serveConfig(h.packInfo("v1"))
        h.core.saveTrip(single)
        h.core.start()
        val day = h.core.timetable("t1", DAY).first()
        assertEquals(14, day.size)
        assertTrue(h.core.timetable("missing", DAY).first().isEmpty())
    }

    @Test
    fun trackCombinesServiceAndVehicle() = runTest {
        val h = Harness(this, startMs = at(6, 52))
        h.join()
        h.preinstall("v1")
        h.serveConfig(h.packInfo("v1"))
        h.server.on("GET", "/v1/rt/trip/R1-02") {
            with(h.server) {
                realtime(RealtimeResponse(serverTimeMs = h.clock.nowMillis(), trips = listOf(delta("R1-02", DAY, "R1", StopDelta(2, "B1", 120, 120)))))
            }
        }
        h.server.on("GET", "/v1/rt") { with(h.server) { realtime(RealtimeResponse(serverTimeMs = h.clock.nowMillis())) } }
        h.server.on("GET", "/v1/vehicles") {
            val body = app.transittracker.model.VehiclesResponse(
                h.clock.nowMillis(),
                vehicles = listOf(app.transittracker.model.VehiclePosition("v9", "R1-02", DAY, "R1", app.transittracker.model.LatLng(-33.84, 151.08), timestampMs = h.clock.nowMillis())),
            )
            respond(kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(app.transittracker.model.VehiclesResponse.serializer(), body), HttpStatusCode.OK, h.server.protoHeaders())
        }
        h.core.start()
        h.core.onForeground()
        val state = h.core.track(ServiceRef("R1-02", DAY)).first { it.vehicle != null && it.detail.status == DepartureStatus.LATE }
        assertEquals("v9", state.vehicle!!.vehicleId)
        assertEquals("B1", state.nextCall!!.stopId)
        assertEquals(at(6, 53), state.nextCall!!.expectedDepartureMs)
        assertTrue(h.server.requestsTo("/v1/rt/trip/R1-02").isNotEmpty())
    }

    @Test
    fun watchesCrudAndCache() = runTest {
        val h = Harness(this)
        h.join()
        h.preinstall("v1")
        h.serveConfig(h.packInfo("v1"))
        val stored = mutableMapOf<String, WatchDto>()
        h.server.on("GET", "/v1/watches") { with(h.server) { json(WatchesResponse.serializer(), WatchesResponse(stored.values.toList())) } }
        h.server.on("PUT", "/v1/watches/*") { req ->
            val w = ApiJson.decodeFromString(WatchDto.serializer(), req.body!!)
            stored[w.id] = w
            with(h.server) { json(WatchDto.serializer(), w) }
        }
        h.server.on("DELETE", "/v1/watches/*") { req ->
            stored.remove(req.path.substringAfterLast('/'))
            respond("", HttpStatusCode.NoContent, h.server.jsonHeaders())
        }
        h.server.on("POST", "/v1/watches/*") { with(h.server) { json(TestPushResponse.serializer(), TestPushResponse(sent = true)) } }
        h.core.start()

        val watch = h.core.watchService(ServiceRef("R1-04", DAY))
        assertEquals(WatchKind.SINGLE_SERVICE, watch.kind)
        assertEquals("A", watch.originStationId)
        assertEquals("C", watch.destinationStationId)
        assertEquals("R1-04", watch.tripId)
        assertEquals(DAY, watch.serviceDate)
        assertEquals(listOf("R1"), watch.routeIds)
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(watch.id), watch.id)
        assertEquals("T1 07:00 Alpha to Charlie", watch.label)
        assertTrue(watch.createdMs > 0)

        val partial = h.core.watchService(ServiceRef("R1-04", DAY), originStationId = "A", destinationStationId = "B", rules = app.transittracker.model.WatchRules())
        assertEquals("B", partial.destinationStationId)

        assertEquals(2, h.core.watches.first().size)
        assertTrue(h.kv.get(KvKeys.WATCHES)!!.contains(watch.id), "cached for offline display")
        assertTrue(h.core.sendTestNotification(watch.id))

        h.core.deleteWatch(watch.id)
        assertEquals(listOf(partial.id), h.core.watches.first().map { it.id })
        assertEquals(setOf(partial.id), stored.keys)

        assertFailsWith<IllegalArgumentException> { h.core.watchService(ServiceRef("nope", DAY)) }
    }

    @Test
    fun saveWatchFailureThrows() = runTest {
        val h = Harness(this)
        h.join()
        h.server.on("PUT", "/v1/watches/*") {
            respond("""{"code":"too_many_watches","title":"Too many watches","status":409}""", HttpStatusCode.Conflict, h.server.jsonHeaders())
        }
        val e = assertFailsWith<TransitCoreException> {
            h.core.saveWatch(WatchDto(id = "w", originStationId = "A", destinationStationId = "B"))
        }
        assertEquals(409, e.status)
        assertEquals("too_many_watches", e.error?.code)
    }

    @Test
    fun notificationParsing() = runTest {
        val core = Harness(this).core
        assertEquals(
            ServiceRef("R1-04", DAY),
            core.openFromNotification(mapOf(PushKeys.KIND to "delay", PushKeys.TRIP_ID to "R1-04", PushKeys.SERVICE_DATE to DAY, PushKeys.WATCH_ID to "w")),
        )
        assertNull(core.openFromNotification(mapOf(PushKeys.KIND to "alert", PushKeys.ALERT_ID to "a1")))
        assertNull(core.openFromNotification(mapOf(PushKeys.TRIP_ID to "R1-04")))
        assertNull(core.openFromNotification(mapOf(PushKeys.TRIP_ID to "R1-04", PushKeys.SERVICE_DATE to "2026-11-11")))
        assertNull(core.openFromNotification(mapOf(PushKeys.TRIP_ID to "R1-04", PushKeys.SERVICE_DATE to "20261341")))
        assertNull(core.openFromNotification(mapOf(PushKeys.TRIP_ID to " ", PushKeys.SERVICE_DATE to DAY)))
    }

    @Test
    fun nextAlarmTimesThroughCore() = runTest {
        val h = Harness(this)
        h.join()
        h.preinstall("v1")
        h.serveConfig(h.packInfo("v1"))
        h.core.saveTrip(single)
        h.core.start()
        val times = h.core.nextAlarmTimes(AlarmSpec("a", "t1", AlarmKind.BEFORE_DEPARTURE, 10, daysOfWeek = listOf(3)), 2)
        assertEquals(listOf(at(7, 5), at(7, 20)), times)
        assertTrue(h.core.nextAlarmTimes(AlarmSpec("a", "missing", AlarmKind.BEFORE_DEPARTURE, 10)).isEmpty())
    }

    @Test
    fun dataStatusReflectsJoinAndLiveData() = runTest {
        val h = Harness(this)
        h.core.start()
        val before = h.core.dataStatus.first()
        assertFalse(before.joined)
        assertEquals(DefaultTransitCore.DEFAULT_ATTRIBUTION, before.attribution)
        assertEquals(listOf("syd-rail"), before.packs.map { it.name })
        assertNull(before.packs.single().version)
        assertNotNull(before)
    }

    @Test
    fun dataStatusSaysWhyTheTimetableDidNotArrive() = runTest {
        val h = Harness(this)
        h.join()
        h.core.start()

        // The server answers but offers no timetable (its first build has not finished).
        h.serveConfig()
        h.core.checkForUpdates()
        assertEquals(TimetableProblems.NOT_OFFERED, h.core.dataStatus.first().timetableProblem)

        // It offers one, but the download fails.
        h.serveConfig(h.packInfo("v1"))
        h.server.on("GET", "/v1/packs/syd-rail/*") { respond("", HttpStatusCode.NotFound) }
        h.core.checkForUpdates()
        assertEquals("The timetable download failed (HTTP 404). Try again.", h.core.dataStatus.first().timetableProblem)

        // The server no longer knows this phone.
        h.server.on("GET", "/v1/config") { respond("", HttpStatusCode.Unauthorized) }
        h.core.checkForUpdates()
        assertEquals(TimetableProblems.config(401), h.core.dataStatus.first().timetableProblem)

        // All well: no problem.
        h.serveConfig(h.packInfo("v1"))
        h.core.checkForUpdates()
        val ok = h.core.dataStatus.first()
        assertEquals("v1", ok.packs.single().version)
        assertNull(ok.timetableProblem)
    }
}
