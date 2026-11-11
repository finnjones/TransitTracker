@file:OptIn(ExperimentalSerializationApi::class)

package app.transittracker.server.app

import app.transittracker.core.CoreConfig
import app.transittracker.core.CoreDependencies
import app.transittracker.core.InMemoryKeyValueStore
import app.transittracker.core.JvmFileStore
import app.transittracker.core.JvmTimetableOpener
import app.transittracker.core.PackUpdateResult
import app.transittracker.core.StaticNetworkStatus
import app.transittracker.core.TransitCoreFactory
import app.transittracker.core.ZstdDecompressor
import app.transittracker.model.ConfigResponse
import app.transittracker.model.DepartureStatus
import app.transittracker.model.DeviceUpdate
import app.transittracker.model.JoinRequest
import app.transittracker.model.JoinResponse
import app.transittracker.model.JoinResult
import app.transittracker.model.LatLng
import app.transittracker.model.LegKind
import app.transittracker.model.PlaceKind
import app.transittracker.model.PlacesResponse
import app.transittracker.model.PlanEndpoint
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanResponse
import app.transittracker.model.changes
import app.transittracker.model.MediaTypes
import app.transittracker.model.Mode
import app.transittracker.model.PackInfo
import app.transittracker.model.PacksResponse
import app.transittracker.model.PushKeys
import app.transittracker.model.RealtimeResponse
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceTime
import app.transittracker.model.TripLeg
import app.transittracker.model.TripStatus
import app.transittracker.model.WallClock
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchKind
import app.transittracker.server.live.FeedFetcher
import app.transittracker.server.live.FeedKind
import app.transittracker.server.live.FetchResult
import app.transittracker.server.pipeline.PipelineOptions
import app.transittracker.server.planner.ReplayPlannerFetcher
import app.transittracker.server.spi.FeedDefinition
import app.transittracker.server.spi.PushMessage
import app.transittracker.server.spi.PushResult
import app.transittracker.server.spi.PushSender
import app.transittracker.server.spi.ServerConfig
import app.transittracker.server.spi.TripChangeBatch
import app.transittracker.timetable.JvmTimetables
import com.google.transit.realtime.FeedEntity
import com.google.transit.realtime.FeedHeader
import com.google.transit.realtime.FeedMessage
import com.google.transit.realtime.TripDescriptor
import com.google.transit.realtime.TripUpdate
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole system end to end: the real pipeline imports the `mini-rail` fixture, the real
 * tt-server wiring (live cache, watch engine, SQLite state) serves it over Ktor's test engine,
 * synthetic GTFS-Realtime comes from a fake [FeedFetcher] and push messages are captured.
 *
 * Time is fixed at Wednesday 11 Nov 2026 06:40 Sydney (AEDT) and moved forward explicitly.
 */
class EndToEndTest {
    private val dir: Path = createTempDirectory("tt-e2e")
    private val clock = MutableClock(Instant.parse("2026-11-10T19:40:00Z").toEpochMilli())
    private val config = ServerConfig(
        dataDir = dir.resolve("data"),
        recordIntervalMinutes = 0,
        adminToken = ADMIN_TOKEN,
        pollIntervalS = 30,
        trackerPollIntervalS = 15,
    )
    private val feeds = FakeFeedFetcher(clock)
    private val push = CapturingPushSender()
    private val scopes = ArrayList<CoroutineScope>()
    private var server: TtServer? = null

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        server?.close()
        dir.toFile().deleteRecursively()
    }

    private fun admin(vararg args: String): String {
        val out = ByteArrayOutputStream()
        val code = runAdmin(args.toList(), config, PrintStream(out, true), PrintStream(out, true), clock)
        assertEquals(0, code, "tt-admin ${args.joinToString(" ")} failed:\n$out")
        return out.toString()
    }

    private fun newInvite(): String =
        assertNotNull(Regex("Invite code: (\\S+)").find(admin("invite", "--note", "e2e"))).groupValues[1]

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    /** Imports the fixture with tt-admin, then starts the real server modules. */
    private fun startServer(version: String): TtServer {
        val output = admin("import-gtfs", "syd-rail", Fixtures.miniRail.toString())
        assertTrue("Published timetable pack syd-rail/$version" in output, output)
        val tt = TtServer(
            config, clock, fetcher = feeds, push = push, pipelineOptions = PipelineOptions(zstdLevel = 3),
            // Hand-made Trip Planner answers using the mini-rail stations and trips (testdata/planner/README.md).
            plannerFetcher = ReplayPlannerFetcher(Fixtures.testdata.resolve("planner/replay/planner")),
        )
        server = tt
        tt.start(newScope())
        assertEquals(version, tt.services.timetables.current.value?.version)
        return tt
    }

    private fun e2e(version: String = "20261111T0640", block: suspend ApplicationTestBuilder.(TtServer) -> Unit) {
        val tt = startServer(version)
        testApplication {
            application { transitTrackerApi(tt.api) }
            block(tt)
        }
    }

    private suspend fun ApplicationTestBuilder.join(code: String): JoinResponse {
        val response = client.post("/v1/join") {
            contentType(ContentType.Application.Json)
            setBody(Codecs.json.encodeToString(JoinRequest.serializer(), JoinRequest(code, "android", "1.0")))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Codecs.json.decodeFromString(JoinResponse.serializer(), response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.getAuthed(path: String, token: String, accept: String = MediaTypes.JSON): HttpResponse =
        client.get(path) {
            bearerAuth(token)
            header(HttpHeaders.Accept, accept)
        }

    @Test
    fun `server, watch engine and phone engine work together on the mini-rail fixture`() = e2e { tt ->
        // ---- join with an invite from the real admin code path ----
        val device = join(newInvite())
        val token = device.deviceToken

        // ---- config, packs, pack download ----
        val config = getAuthed("/v1/config", token).let {
            assertEquals(HttpStatusCode.OK, it.status)
            Codecs.json.decodeFromString(ConfigResponse.serializer(), it.bodyAsText())
        }
        val pack = config.packs.single()
        assertEquals("syd-rail", pack.name)
        assertEquals("20261111T0640", pack.version)
        assertEquals(setOf(Mode.TRAIN, Mode.METRO, Mode.LIGHT_RAIL), pack.modes.toSet())
        assertEquals("/v1/packs/syd-rail/20261111T0640", pack.url)
        val packs = Codecs.json.decodeFromString(PacksResponse.serializer(), getAuthed("/v1/packs", token).bodyAsText())
        assertEquals(listOf(pack), packs.packs)
        verifyPackDownload(pack, getAuthed(pack.url, token, accept = "*/*"))

        // ---- a commute watch: Central -> Chatswood, T1, departing 07:00-07:40 on weekdays ----
        assertEquals(HttpStatusCode.NoContent, client.put("/v1/device") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(Codecs.json.encodeToString(DeviceUpdate.serializer(), DeviceUpdate(pushToken = "fcm-token-1")))
        }.status)
        val watch = WatchDto(
            id = "commute-1",
            kind = WatchKind.COMMUTE,
            originStationId = "CEN",
            destinationStationId = "CHA",
            routeIds = listOf("T1"),
            daysOfWeek = listOf(1, 2, 3, 4, 5),
            windowStartSecs = 7 * 3600,
            windowEndSecs = 7 * 3600 + 40 * 60,
        )
        val put = client.put("/v1/watches/${watch.id}") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(Codecs.json.encodeToString(WatchDto.serializer(), watch))
        }
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        val covered = tt.services.watches.coveredTrips(device.deviceId, watch.id)
            .filter { it.serviceDate == DAY }.map { it.tripId }
        assertEquals(listOf("T1.N.0700", "T1.N.0730"), covered)

        // ---- live: T1.N.0700 runs 6 minutes late and leaves Central from platform 2 ----
        feeds.trains = listOf(delayed0700)
        clock.advance(31_000) // past the watch feeds' 30 s minimum age
        tt.services.watches.tick() // activation: keeps the T1 feed warm for the active watch
        tt.services.live.tick() // keep-warm refresh: fetch, match, emit trip changes to the engine

        val first = push.next()
        assertEquals("fcm-token-1", first.first)
        val message = first.second
        assertEquals("Central → Chatswood", message.title)
        assertEquals(
            "7:00 to Chatswood now leaves from platform 2. 7:00 to Chatswood is running 6 min late (now 7:06)",
            message.body,
        )
        assertEquals("T1.N.0700", message.data[PushKeys.TRIP_ID])
        assertEquals(DAY, message.data[PushKeys.SERVICE_DATE])
        assertEquals(watch.id, message.data[PushKeys.WATCH_ID])
        assertEquals("T1.N.0700:$DAY", message.collapseKey)
        push.assertNoMore()

        // ---- /v1/rt (protobuf) carries the matched delta ----
        val rt = getAuthed("/v1/rt?routes=T1", token, accept = MediaTypes.PROTOBUF)
        assertEquals(HttpStatusCode.OK, rt.status)
        assertEquals(MediaTypes.PROTOBUF, rt.headers[HttpHeaders.ContentType]?.substringBefore(';'))
        val realtime = ProtoBuf.decodeFromByteArray(RealtimeResponse.serializer(), rt.bodyAsBytes())
        assertEquals("20261111T0640", realtime.timetableVersion)
        val delta = realtime.trips.single()
        assertEquals("T1.N.0700", delta.tripId)
        assertEquals(DAY, delta.startDate)
        assertEquals("T1", delta.routeId)
        assertEquals(TripStatus.SCHEDULED, delta.status)
        assertEquals(false, delta.stale)
        assertEquals(listOf(1, 2, 3, 4, 5), delta.stops.map { it.stopSequence })
        assertEquals(listOf("CEN_P1", "THL_P1", "WYN_P1", "NSY_P1", "CHA_P1"), delta.stops.map { it.stopId })
        assertTrue(delta.stops.all { it.departureDelayS == 360 && it.arrivalDelayS == 360 }, delta.stops.toString())
        assertEquals("CEN_P2", delta.stops.first().actualStopId)
        assertTrue(delta.stops.drop(1).all { it.actualStopId == null })

        // ---- the phone engine against the real server ----
        phoneEngineShowsTheDelay(this)

        // ---- the trip is cancelled ----
        feeds.trains = listOf(cancelled0700)
        clock.advance(31_000)
        tt.services.watches.tick()
        tt.services.live.tick()
        val cancel = push.next().second
        assertEquals("cancel", cancel.data[PushKeys.KIND])
        assertEquals("7:00 Central → Chatswood is cancelled. Next: 7:30 (timetabled)", cancel.body)
        assertEquals("T1.N.0700:$DAY", cancel.collapseKey)
        push.assertNoMore()

        // ---- the same data again: no new trip changes, and the engine itself stays quiet ----
        clock.advance(31_000)
        tt.services.watches.tick()
        tt.services.live.tick()
        val cached = assertNotNull(tt.services.live.cachedTrip("T1.N.0700", DAY))
        assertEquals(TripStatus.CANCELLED, cached.status)
        tt.services.watches.onTripChanges(TripChangeBatch("sydneytrains", clock.now, false, listOf(cached)))
        push.assertNoMore()

        val log = tt.notificationLog.recent(10)
        assertEquals(listOf("cancel", "platform"), log.map { it.kind })
        assertTrue(log.all { it.result == "sent" && it.deviceId == device.deviceId && it.tripId == "T1.N.0700" })
        assertEquals(2, tt.services.watches.status().sentToday)
        assertEquals(0, feeds.unexpectedCalls.get(), "only the sydneytrains and metro feeds are known")
    }

    @Test
    fun `phone join maps the server's invite errors to InvalidCode`() = e2e {
        val core = phoneCore(this)
        core.start()
        assertEquals(JoinResult.InvalidCode, core.join("NOT-A-REAL-CODE"))
        val code = newInvite()
        assertIs<JoinResult.Joined>(core.join(code))
        // A second phone cannot reuse it (403 invite_used).
        val other = phoneCore(this)
        other.start()
        assertEquals(JoinResult.InvalidCode, other.join(code))
        // Expired invites (403 invite_expired).
        val expiring = assertNotNull(Regex("Invite code: (\\S+)").find(admin("invite", "--expires-days", "1"))).groupValues[1]
        clock.advance(2 * 24 * 3600_000L)
        assertEquals(JoinResult.InvalidCode, other.join(expiring))
    }

    @Test
    fun `alerts, vehicles, metro updates, tracking, shapes and status work end to end`() = e2e { tt ->
        val health = client.get("/health").bodyAsText()
        assertEquals(HealthResponse(ok = true, timetableLoaded = true, timetableVersion = "20261111T0640"),
            Codecs.json.decodeFromString(HealthResponse.serializer(), health))

        val device = join(newInvite())
        val token = device.deviceToken
        client.put("/v1/device") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(Codecs.json.encodeToString(DeviceUpdate.serializer(), DeviceUpdate(pushToken = "fcm-token-2")))
        }
        val watch = WatchDto(
            id = "metro-commute", originStationId = "CEN", destinationStationId = "CHA", label = "To work",
            windowStartSecs = 7 * 3600, windowEndSecs = 7 * 3600 + 10 * 60,
        )
        assertEquals(HttpStatusCode.OK, client.put("/v1/watches/${watch.id}") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(Codecs.json.encodeToString(WatchDto.serializer(), watch))
        }.status)
        // No route filter: both the 07:00 train and the 07:05 metro are covered.
        assertEquals(
            listOf("T1.N.0700", "M1.N.0705"),
            tt.services.watches.coveredTrips(device.deviceId, watch.id).filter { it.serviceDate == DAY }.map { it.tripId },
        )

        // Metro trips use stop_sequence 10/20/30; a 2-minute delay from Martin Place on.
        feeds.metro = listOf(
            TripUpdate(
                trip = TripDescriptor(trip_id = "M1.N.0705", start_date = DAY),
                stop_time_update = listOf(
                    TripUpdate.StopTimeUpdate(stop_sequence = 20, arrival = TripUpdate.StopTimeEvent(delay = 120)),
                ),
            ),
        )
        feeds.trainAlerts = listOf(
            FeedEntity(
                id = "alert-trackwork",
                alert = com.google.transit.realtime.Alert(
                    informed_entity = listOf(com.google.transit.realtime.EntitySelector(route_id = "T1")),
                    effect = com.google.transit.realtime.Alert.Effect.SIGNIFICANT_DELAYS,
                    header_text = com.google.transit.realtime.TranslatedString(
                        translation = listOf(
                            com.google.transit.realtime.TranslatedString.Translation(text = "T1: delays at Central", language = "en"),
                        ),
                    ),
                ),
            ),
        )
        feeds.trainVehicles = listOf(
            FeedEntity(
                id = "v1",
                vehicle = com.google.transit.realtime.VehiclePosition(
                    trip = TripDescriptor(trip_id = "T1.N.0700", start_date = DAY),
                    vehicle = com.google.transit.realtime.VehicleDescriptor(id = "set-A1", label = "A1"),
                    position = com.google.transit.realtime.Position(latitude = -33.8830f, longitude = 151.2063f),
                    timestamp = clock.now / 1000,
                ),
            ),
        )
        clock.advance(61_000) // past the alerts' 60 s minimum age
        tt.services.watches.tick()
        tt.services.live.tick()

        // The watch engine tells the friend about the alert on their line (once).
        val alert = push.next().second
        assertEquals("alert", alert.data[PushKeys.KIND])
        assertEquals("To work", alert.title)
        assertEquals("T1: delays at Central", alert.body)
        assertEquals("alert-trackwork", alert.data[PushKeys.ALERT_ID])
        // A 2-minute metro delay is below the 5-minute threshold: nothing more.
        push.assertNoMore()

        val metro = ProtoBuf.decodeFromByteArray(
            RealtimeResponse.serializer(),
            getAuthed("/v1/rt?routes=M1", token, accept = MediaTypes.PROTOBUF).bodyAsBytes(),
        ).trips.single()
        assertEquals("M1.N.0705", metro.tripId)
        assertEquals(listOf(10, 20, 30), metro.stops.map { it.stopSequence })
        assertEquals(listOf(null, 120, 120), metro.stops.map { it.departureDelayS })

        val tracked = ProtoBuf.decodeFromByteArray(
            RealtimeResponse.serializer(),
            getAuthed("/v1/rt/trip/M1.N.0705?date=$DAY", token, accept = MediaTypes.PROTOBUF).bodyAsBytes(),
        )
        assertEquals(listOf(metro), tracked.trips)

        val vehicles = Codecs.json.decodeFromString(
            app.transittracker.model.VehiclesResponse.serializer(),
            getAuthed("/v1/vehicles?routes=T1", token).bodyAsText(),
        ).vehicles
        assertEquals(listOf("set-A1"), vehicles.map { it.vehicleId })
        assertEquals("T1", vehicles.single().routeId)

        val alerts = Codecs.json.decodeFromString(
            app.transittracker.model.AlertsResponse.serializer(),
            getAuthed("/v1/alerts?modes=TRAIN", token).bodyAsText(),
        ).alerts
        assertEquals(listOf("alert-trackwork"), alerts.map { it.id })
        assertEquals(listOf("T1"), alerts.single().routeIds)

        val shape = Codecs.json.decodeFromString(
            app.transittracker.model.ShapeResponse.serializer(),
            getAuthed("/v1/shapes/SH_T1_N", token).bodyAsText(),
        )
        assertTrue(shape.encodedPolyline.isNotEmpty())

        val status = client.get("/status") {
            bearerAuth(ADMIN_TOKEN)
            header(HttpHeaders.Accept, MediaTypes.JSON)
        }
        assertEquals(HttpStatusCode.OK, status.status)
        val report = Codecs.json.decodeFromString(StatusReport.serializer(), status.bodyAsText())
        assertEquals("20261111T0640", report.timetableVersion)
        assertEquals(1, report.watches.totalWatches)
        assertEquals(1, report.watches.activeWatches)
        assertEquals(1, report.activeDevices)
        val metroFeed = report.live.feeds.single { it.feedId == "metro" && it.kind == "tripupdates" }
        assertEquals(1, metroFeed.matchedTrips)
        assertEquals(false, metroFeed.stale)

        // ---- phone: service detail with the metro delay; packs survive an app restart ----
        val kv = InMemoryKeyValueStore()
        val files = Files.createTempDirectory(dir, "phone-restart")
        val core = phoneCore(this, kv, files)
        core.start()
        assertIs<JoinResult.Joined>(core.join(newInvite()))
        assertNotNull(core.updatePacks())
        core.onForeground()
        val detail = withTimeout(10_000) {
            core.service(app.transittracker.model.ServiceRef("M1.N.0705", DAY)).first { d -> d.calls.any { it.expectedArrivalMs != it.scheduledArrivalMs } }
        }
        assertEquals(listOf("CEN_M1", "MPL_P1", "CHA_M1"), detail.calls.map { it.stopId })
        assertEquals(
            ServiceTime.toEpochMillis(DAY, 7 * 3600 + 11 * 60 + 120),
            detail.calls[1].expectedArrivalMs,
        )
        assertEquals("M1", detail.route?.id)
        assertEquals(listOf("alert-trackwork"), withTimeout(10_000) { core.alerts.first { it.isNotEmpty() } }.map { it.id })

        // A one-off watch created on the phone is accepted by the server's validator and covered.
        core.updatePushToken("fcm-phone")
        val single = core.watchService(app.transittracker.model.ServiceRef("T1.N.0730", DAY))
        assertEquals(WatchKind.SINGLE_SERVICE, single.kind)
        assertEquals("CEN" to "CHA", single.originStationId to single.destinationStationId)
        assertEquals(listOf(single.id), withTimeout(10_000) { core.watches.first { it.isNotEmpty() } }.map { it.id })
        val phoneDevice = tt.devices.all().single { it.pushToken == "fcm-phone" }
        assertEquals(
            listOf("T1.N.0730"),
            tt.services.watches.coveredTrips(phoneDevice.deviceId, single.id).map { it.tripId },
        )
        assertTrue(core.sendTestNotification(single.id))
        val test = push.next()
        assertEquals("fcm-phone", test.first)
        assertEquals("test", test.second.data[PushKeys.KIND])

        val restarted = phoneCore(this, kv, files)
        restarted.start()
        assertEquals("20261111T0640", restarted.dataStatus.first().packs.single().version)
        assertEquals(listOf("Chatswood Station"), restarted.searchStops("Chatswood").filter { it.parentId == null }.map { it.name })
    }

    @Test
    fun `after-midnight trips match their previous service day on server, watch engine and phone`() {
        // Thursday 12 Nov 2026 00:20 AEDT. T1.N.2430 belongs to Wednesday's service (24:30:00).
        clock.now = Instant.parse("2026-11-11T13:20:00Z").toEpochMilli()
        e2e(version = "20261112T0020") { tt ->
            val device = join(newInvite())
            client.put("/v1/device") {
                bearerAuth(device.deviceToken)
                contentType(ContentType.Application.Json)
                setBody(Codecs.json.encodeToString(DeviceUpdate.serializer(), DeviceUpdate(pushToken = "fcm-late")))
            }
            val watch = WatchDto(
                id = "late-night", originStationId = "CEN", destinationStationId = "CHA", routeIds = listOf("T1"),
                daysOfWeek = listOf(4), windowStartSecs = 20 * 60, windowEndSecs = 45 * 60, leadMinutes = 30,
            )
            assertEquals(HttpStatusCode.OK, client.put("/v1/watches/${watch.id}") {
                bearerAuth(device.deviceToken)
                contentType(ContentType.Application.Json)
                setBody(Codecs.json.encodeToString(WatchDto.serializer(), watch))
            }.status)
            val covered = tt.services.watches.coveredTrips(device.deviceId, watch.id).map { it.tripId to it.serviceDate }
            assertEquals(listOf("T1.N.2430" to DAY), covered)

            feeds.trains = listOf(
                TripUpdate(
                    trip = TripDescriptor(trip_id = "T1.N.2430", start_date = DAY),
                    stop_time_update = listOf(TripUpdate.StopTimeUpdate(stop_sequence = 1, departure = TripUpdate.StopTimeEvent(delay = 420))),
                ),
            )
            clock.advance(31_000)
            tt.services.watches.tick()
            tt.services.live.tick()
            assertEquals("0:30 to Chatswood is running 7 min late (now 0:37)", push.next().second.body)

            val core = phoneCore(this)
            core.start()
            assertIs<JoinResult.Joined>(core.join(newInvite()))
            assertNotNull(core.updatePacks())
            core.saveTrip(
                SavedTrip("late", "Central", "Chatswood", listOf(TripLeg(setOf("CEN_P1"), setOf("CHA_P1"), routeIds = setOf("T1")))),
            )
            core.onForeground()
            val departure = withTimeout(10_000) {
                core.upcoming("late").first { it.firstOrNull()?.departure?.status == DepartureStatus.LATE }
            }.first().departure
            assertEquals(app.transittracker.model.ServiceRef("T1.N.2430", DAY), departure.ref)
            assertEquals(Instant.parse("2026-11-11T13:37:00Z").toEpochMilli(), departure.expectedDepartureMs)
        }
    }

    @Test
    fun `a planned journey on the mini-rail fixture is matched to its trips`() = e2e { tt ->
        val token = join(newInvite()).deviceToken
        val config = Codecs.json.decodeFromString(ConfigResponse.serializer(), getAuthed("/v1/config", token).bodyAsText())
        assertEquals(true, config.features["planner"])

        // ---- find the start: a place search ----
        val places = Codecs.json.decodeFromString(
            PlacesResponse.serializer(),
            getAuthed("/v1/places?q=central&lat=-33.884&lon=151.207", token).bodyAsText(),
        ).places
        val central = places.first()
        assertEquals("Central Station", central.name)
        assertEquals(PlaceKind.STOP, central.kind)
        assertEquals("CEN", central.stopId, "the planner's id is a station in the loaded timetable")

        // ---- plan: Eddy Ave -> Cammeray, leaving now (06:40), with the phone's HTTP client ----
        val request = PlanRequest(
            from = PlanEndpoint(LatLng(-33.884300, 151.207300)),
            to = PlanEndpoint(LatLng(-33.822000, 151.211000)),
            maxItineraries = 3,
        )
        val response = client.post("/v1/plan") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(Codecs.json.encodeToString(PlanRequest.serializer(), request))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val plan = Codecs.json.decodeFromString(PlanResponse.serializer(), response.bodyAsText())
        assertEquals("20261111T0640", plan.timetableVersion)
        assertEquals(setOf(Mode.TRAIN, Mode.METRO), plan.liveModes.toSet())
        assertEquals(2, plan.itineraries.size)

        val first = plan.itineraries.first()
        assertEquals(listOf(LegKind.WALK, LegKind.RIDE, LegKind.WALK, LegKind.RIDE, LegKind.WALK), first.legs.map { it.kind })
        assertEquals(1, first.changes)
        val train = first.legs[1]
        assertEquals("T1", train.routeName)
        assertEquals("T1.N.0700", train.tripId, "matched by the planner's real-time trip id")
        assertEquals(DAY, train.serviceDate)
        assertEquals("T1", train.routeId)
        assertEquals("F99D1C", train.routeColor)
        assertEquals("Central", train.from.name)
        assertEquals("CEN_P1", train.from.stopId)
        assertEquals("1", train.from.platform)
        assertEquals("NSY_P1", train.to.stopId)
        assertEquals(ServiceTime.toEpochMillis(DAY, 7 * 3600), train.from.scheduledMs)
        assertEquals(ServiceTime.toEpochMillis(DAY, 7 * 3600 + 120), train.from.expectedMs)
        val change = first.legs[2]
        assertEquals(180, change.durationS, "the change walk from the planner's foot-path information")
        assertEquals("North Sydney", change.from.name)
        assertEquals("Stand D", change.to.platform)
        val bus = first.legs[3]
        assertEquals(Mode.BUS, bus.mode)
        assertEquals("202", bus.routeName)
        assertEquals(null, bus.tripId, "buses are not in the rail timetable")

        // The second journey's train has a real-time id the timetable does not know: matched by
        // line, time and stops instead.
        val second = plan.itineraries[1].legs[1]
        assertEquals("T1.N.0730", second.tripId)
        assertEquals(DAY, second.serviceDate)

        // ---- the matched ride works with the live endpoints, as the phone would use it ----
        feeds.trains = listOf(delayed0700)
        val tracked = ProtoBuf.decodeFromByteArray(
            RealtimeResponse.serializer(),
            getAuthed("/v1/rt/trip/${train.tripId}?date=${train.serviceDate}", token, accept = MediaTypes.PROTOBUF).bodyAsBytes(),
        )
        assertEquals(listOf("T1.N.0700"), tracked.trips.map { it.tripId })

        val status = Codecs.json.decodeFromString(
            StatusReport.serializer(),
            client.get("/status") { bearerAuth(ADMIN_TOKEN); header(HttpHeaders.Accept, MediaTypes.JSON) }.bodyAsText(),
        )
        assertEquals("replay", status.planner.source)
        assertEquals(2, status.planner.matchedRides)
        assertEquals(2, status.planner.unmatchedRides)
        assertEquals(0, status.planner.upstreamCalls, "replay never calls TfNSW")
        assertEquals(0, tt.services.quota.callsToday)
    }

    // ---- phone side ---------------------------------------------------------------------------

    private fun phoneCore(
        app: ApplicationTestBuilder,
        kv: InMemoryKeyValueStore = InMemoryKeyValueStore(),
        files: Path = Files.createTempDirectory(dir, "phone"),
    ) = TransitCoreFactory.create(
        CoreConfig(baseUrl = "http://localhost", appVersion = "1.0.0", platform = "android"),
        CoreDependencies(
            httpClient = app.client,
            kv = kv,
            files = JvmFileStore(files),
            opener = JvmTimetableOpener,
            decompressor = ZstdDecompressor,
            network = StaticNetworkStatus(unmetered = true),
            clock = clock,
            scope = newScope(),
            ioContext = Dispatchers.IO,
        ),
    )

    private suspend fun phoneEngineShowsTheDelay(app: ApplicationTestBuilder) {
        val core = phoneCore(app)
        core.start()
        assertIs<JoinResult.Joined>(core.join(newInvite()))
        val report = assertNotNull(core.updatePacks())
        assertTrue(
            report.single().let { it is PackUpdateResult.Updated || it is PackUpdateResult.UpToDate },
            report.toString(),
        )
        assertEquals("20261111T0640", core.dataStatus.first().packs.single().version)

        val central = core.searchStops("Central").first { it.parentId == null }
        val chatswood = core.searchStops("Chatswood").first { it.parentId == null }
        assertEquals("CEN" to "CHA", central.id to chatswood.id)
        core.saveTrip(
            SavedTrip(
                id = "to-work",
                originName = "Central",
                destinationName = "Chatswood",
                legs = listOf(TripLeg(setOf("CEN_P1", "CEN_P2"), setOf("CHA_P1", "CHA_P2"), routeIds = setOf("T1"))),
            ),
        )
        core.onForeground()
        val journeys = withTimeout(10_000) {
            core.upcoming("to-work").first { list -> list.firstOrNull()?.departure?.status == DepartureStatus.LATE }
        }
        core.onBackground()
        val departure = journeys.first().departure
        val scheduled = ServiceTime.toEpochMillis(DAY, 7 * 3600)
        assertEquals("T1.N.0700", departure.ref.tripId)
        assertEquals(DAY, departure.ref.serviceDate)
        assertEquals(scheduled, departure.scheduledDepartureMs)
        assertEquals(scheduled + 6 * 60_000, departure.expectedDepartureMs)
        assertEquals(360, departure.delaySeconds)
        assertEquals(true, departure.platformChanged)
        assertEquals("CEN_P2", departure.originStopId)
        assertEquals("2", departure.originPlatform)
        assertEquals(false, departure.stale)
        // The next service has no live data: timetabled.
        assertEquals("T1.N.0730", journeys[1].departure.ref.tripId)
        assertEquals(DepartureStatus.TIMETABLED, journeys[1].departure.status)
    }

    private suspend fun verifyPackDownload(pack: PackInfo, response: HttpResponse) {
        assertEquals(HttpStatusCode.OK, response.status)
        val compressed = response.bodyAsBytes()
        assertEquals(pack.sizeBytes, compressed.size.toLong())
        assertEquals(pack.sha256, sha256(compressed))
        val sqlite = ZstdDecompressor.decompress(compressed)
        assertEquals(pack.sqliteSha256, sha256(sqlite))
        val file = dir.resolve("downloaded.sqlite")
        Files.write(file, sqlite)
        JvmTimetables.open(file).use { timetable ->
            assertEquals(pack.version, timetable.version)
            assertEquals("syd-rail", timetable.name)
            assertNotNull(timetable.trip("T1.N.0700"))
            assertTrue(timetable.runsOn("T1.N.0700", DAY))
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- synthetic GTFS-Realtime --------------------------------------------------------------

    private val delayed0700 = TripUpdate(
        trip = TripDescriptor(
            trip_id = "T1.N.0700",
            start_date = DAY,
            route_id = "T1",
            schedule_relationship = TripDescriptor.ScheduleRelationship.SCHEDULED,
        ),
        stop_time_update = listOf(
            // TfNSW reports a platform change as the live platform's stop id.
            TripUpdate.StopTimeUpdate(
                stop_sequence = 1,
                stop_id = "CEN_P2",
                departure = TripUpdate.StopTimeEvent(delay = 360),
            ),
        ),
    )

    private val cancelled0700 = TripUpdate(
        trip = TripDescriptor(
            trip_id = "T1.N.0700",
            start_date = DAY,
            route_id = "T1",
            schedule_relationship = TripDescriptor.ScheduleRelationship.CANCELED,
        ),
    )

    private companion object {
        const val DAY = "20261111"
        const val ADMIN_TOKEN = "e2e-admin-token"
    }
}

/**
 * Serves synthetic GTFS-R: [trains] / [metro] trip updates, [trainAlerts] and [trainVehicles];
 * every other feed is empty. Each response is stamped with the current (test) time.
 */
class FakeFeedFetcher(private val clock: WallClock) : FeedFetcher {
    @Volatile var trains: List<TripUpdate> = emptyList()
    @Volatile var metro: List<TripUpdate> = emptyList()
    @Volatile var trainAlerts: List<FeedEntity> = emptyList()
    @Volatile var trainVehicles: List<FeedEntity> = emptyList()
    val unexpectedCalls = AtomicInteger()

    override val isRemote: Boolean get() = false

    override suspend fun fetch(feed: FeedDefinition, kind: FeedKind): FetchResult {
        val entities = when (feed.id to kind) {
            "sydneytrains" to FeedKind.TRIP_UPDATES -> trains.map(::entity)
            "metro" to FeedKind.TRIP_UPDATES -> metro.map(::entity)
            "sydneytrains" to FeedKind.ALERTS -> trainAlerts
            "sydneytrains" to FeedKind.VEHICLES -> trainVehicles
            else -> {
                if (feed.id !in setOf("sydneytrains", "metro")) unexpectedCalls.incrementAndGet()
                emptyList()
            }
        }
        val header = FeedHeader(gtfs_realtime_version = "2.0", timestamp = clock.nowMillis() / 1000)
        return FetchResult.Success(FeedMessage(header_ = header, entity = entities).encode())
    }

    private fun entity(update: TripUpdate) = FeedEntity(id = update.trip.trip_id ?: "e", trip_update = update)
}

/** Captures every push message and reports it as sent. */
class CapturingPushSender : PushSender {
    private val sent = Channel<Pair<String, PushMessage>>(Channel.UNLIMITED)
    private val count = AtomicInteger()

    override suspend fun send(token: String, message: PushMessage): PushResult {
        sent.send(token to message)
        return PushResult.Sent("msg-${count.incrementAndGet()}")
    }

    /** The next message sent, waiting up to 5 s for it. */
    suspend fun next(): Pair<String, PushMessage> = withTimeout(5_000) { sent.receive() }

    /** Asserts nothing more is sent within a short grace period. */
    suspend fun assertNoMore() {
        delay(300)
        assertNull(sent.tryReceive().getOrNull(), "unexpected push message")
    }
}
