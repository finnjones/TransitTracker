@file:OptIn(ExperimentalSerializationApi::class)

package app.transittracker.server.app

import app.transittracker.model.AlertsResponse
import app.transittracker.model.ApiHeaders
import app.transittracker.model.ConfigResponse
import app.transittracker.model.DeviceUpdate
import app.transittracker.model.JoinRequest
import app.transittracker.model.JoinResponse
import app.transittracker.model.LatLng
import app.transittracker.model.MediaTypes
import app.transittracker.model.Mode
import app.transittracker.model.PackInfo
import app.transittracker.model.PacksResponse
import app.transittracker.model.RealtimeResponse
import app.transittracker.model.ShapeResponse
import app.transittracker.model.TestPushResponse
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchesResponse
import app.transittracker.server.spi.PushResult
import app.transittracker.server.spi.ServerConfig
import app.transittracker.server.spi.TrustedProxy
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiTest {
    private val dir: Path = createTempDirectory("tt-api-test")
    private val clock = MutableClock()
    private val db = Database.open(dir.resolve("tt.db"))
    private val devices = SqliteDeviceStore(db, clock)
    private val notifications = SqliteNotificationLog(db)
    private val live = FakeLiveData(clock)
    private val watches = FakeWatchService()
    private val packBytes = ByteArray(10_000) { (it % 251).toByte() }
    private val packPath: Path = dir.resolve("syd-rail-20261111.ttpack.zst").also { Files.write(it, packBytes) }
    private val pack = PackInfo(
        name = "syd-rail", version = "20261111T0300", sizeBytes = packBytes.size.toLong(), sha256 = "ab".repeat(32),
        url = "/v1/packs/syd-rail/20261111T0300", modes = listOf(Mode.TRAIN, Mode.METRO),
    )
    private val shapePoints = listOf(LatLng(38.5, -120.2), LatLng(40.7, -120.95), LatLng(43.252, -126.453))
    private val timetables = FakeTimetableRepository(
        timetable = FakeTimetable(mapOf("shape-1" to shapePoints)),
        packList = listOf(pack),
        files = mapOf(("syd-rail" to "20261111T0300") to packPath),
    )
    private val config = ServerConfig(dataDir = dir, adminToken = ADMIN_TOKEN, pollIntervalS = 25, trackerPollIntervalS = 12)
    private val deps = ApiDependencies(config, devices, notifications, timetables, live, watches, clock)

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { transitTrackerApi(deps) }
        block()
    }

    private fun newDevice(): JoinResponse = devices.join(devices.createInvite().code, "android", "1.0")

    private suspend fun HttpResponse.problemCode(): String {
        assertEquals(MediaTypes.PROBLEM_JSON, contentType()?.withoutParameters().toString())
        val body = Codecs.json.parseToJsonElement(bodyAsText()).jsonObject
        assertEquals(status.value, body["status"]!!.jsonPrimitive.int)
        assertNotNull(body["title"])
        return body["code"]!!.jsonPrimitive.content
    }

    // ---- join ----

    @Test
    fun `join with a valid code returns a working device token`() = api {
        val code = devices.createInvite(expiresInDays = 3).code
        val response = client.post("/v1/join") {
            contentType(ContentType.Application.Json)
            setBody("""{"inviteCode":"${code.lowercase()}","platform":"android","appVersion":"1.2"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val joined = Codecs.json.decodeFromString(JoinResponse.serializer(), response.bodyAsText())
        assertEquals("1.2", devices.get(joined.deviceId)!!.appVersion)

        val config = client.get("/v1/config") { bearerAuth(joined.deviceToken) }
        assertEquals(HttpStatusCode.OK, config.status)
    }

    @Test
    fun `join over protobuf`() = api {
        val code = devices.createInvite().code
        val response = client.post("/v1/join") {
            contentType(ContentType.parse(MediaTypes.PROTOBUF))
            header(HttpHeaders.Accept, MediaTypes.PROTOBUF)
            setBody(ProtoBuf.encodeToByteArray(JoinRequest.serializer(), JoinRequest(code)))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(MediaTypes.PROTOBUF, response.contentType()?.withoutParameters().toString())
        val joined = ProtoBuf.decodeFromByteArray(JoinResponse.serializer(), response.bodyAsBytes())
        assertNotNull(devices.authenticate(joined.deviceToken))
    }

    @Test
    fun `join with used, expired or unknown codes fails with problem json`() = api {
        suspend fun join(code: String) = client.post("/v1/join") {
            contentType(ContentType.Application.Json)
            setBody("""{"inviteCode":"$code"}""")
        }
        val used = devices.createInvite().code
        assertEquals(HttpStatusCode.OK, join(used).status)
        join(used).let {
            assertEquals(HttpStatusCode.Forbidden, it.status)
            assertEquals("invite_used", it.problemCode())
        }
        val expired = devices.createInvite(expiresInDays = 1).code
        clock.advance(2L * 24 * 3600 * 1000)
        assertEquals("invite_expired", join(expired).problemCode())
        assertEquals("invite_invalid", join("ZZZZ-ZZZZ").problemCode())
    }

    @Test
    fun `join is rate limited per client ip`() = api {
        suspend fun join(ip: String) = client.post("/v1/join") {
            header(HttpHeaders.XForwardedFor, ip)
            contentType(ContentType.Application.Json)
            setBody("""{"inviteCode":"ZZZZ-ZZZZ"}""")
        }
        repeat(5) { assertEquals(HttpStatusCode.Forbidden, join("203.0.113.5").status) }
        join("203.0.113.5").let {
            assertEquals(HttpStatusCode.TooManyRequests, it.status)
            assertEquals("rate_limited", it.problemCode())
            assertNotNull(it.headers[HttpHeaders.RetryAfter])
        }
        assertEquals(HttpStatusCode.Forbidden, join("203.0.113.6").status)
    }

    @Test
    fun `behind cloudflare tunnel join is rate limited per CF-Connecting-IP`() = testApplication {
        val cloudflare = config.copy(trustedProxy = TrustedProxy.CLOUDFLARE)
        application { transitTrackerApi(ApiDependencies(cloudflare, devices, notifications, timetables, live, watches, clock)) }
        suspend fun join(ip: String) = client.post("/v1/join") {
            header(CF_CONNECTING_IP, ip)
            contentType(ContentType.Application.Json)
            setBody("""{"inviteCode":"ZZZZ-ZZZZ"}""")
        }
        repeat(5) { assertEquals(HttpStatusCode.Forbidden, join("198.51.100.7").status) }
        assertEquals(HttpStatusCode.TooManyRequests, join("198.51.100.7").status)
        // A different person behind the same tunnel is not affected.
        assertEquals(HttpStatusCode.Forbidden, join("198.51.100.8").status)
    }

    @Test
    fun `CF-Connecting-IP is ignored unless cloudflare is the trusted proxy`() = api {
        suspend fun join(ip: String) = client.post("/v1/join") {
            header(CF_CONNECTING_IP, ip)
            contentType(ContentType.Application.Json)
            setBody("""{"inviteCode":"ZZZZ-ZZZZ"}""")
        }
        repeat(5) { i -> assertEquals(HttpStatusCode.Forbidden, join("198.51.100.${i + 20}").status) }
        // Spoofed addresses don't create fresh buckets: all six requests share the peer's bucket.
        assertEquals(HttpStatusCode.TooManyRequests, join("198.51.100.99").status)
    }

    @Test
    fun `status is not treated as local when it arrives through cloudflare`() = api {
        val response = client.get("/status") { header(CF_CONNECTING_IP, "198.51.100.7") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `malformed bodies are 400 problems`() = api {
        val response = client.post("/v1/join") {
            contentType(ContentType.Application.Json)
            setBody("""{"nope":""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("bad_request", response.problemCode())
    }

    // ---- auth and common headers ----

    @Test
    fun `everything except join and health requires a device token`() = api {
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        for (path in listOf("/v1/config", "/v1/packs", "/v1/rt?routes=T1", "/v1/alerts", "/v1/watches", "/v1/shapes/shape-1")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.Unauthorized, response.status, path)
            assertEquals("unauthorized", response.problemCode())
            assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate])
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/config") { bearerAuth("not-a-token") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/v1/device") { setBody("{}") }.status)
    }

    @Test
    fun `every response carries the server time`() = api {
        clock.now = 1_800_000_000_000
        val token = newDevice().deviceToken
        for (response in listOf(
            client.get("/health"),
            client.get("/v1/config"),
            client.get("/v1/config") { bearerAuth(token) },
            client.get("/nope"),
        )) {
            assertEquals("1800000000000", response.headers[ApiHeaders.SERVER_TIME])
        }
    }

    @Test
    fun `health reports whether a timetable is loaded`() = api {
        assertContains(client.get("/health").bodyAsText(), "\"timetableLoaded\":true")
        timetables.current.value = null
        assertContains(client.get("/health").bodyAsText(), "\"timetableLoaded\":false")
    }

    @Test
    fun `unknown routes and unexpected errors are problem json without details`() = api {
        val token = newDevice().deviceToken
        client.get("/v1/nothing-here") { bearerAuth(token) }.let {
            assertEquals(HttpStatusCode.NotFound, it.status)
            assertEquals("not_found", it.problemCode())
        }
        live.failWith = IllegalStateException("secret internal detail")
        client.get("/v1/rt?routes=T1") { bearerAuth(token) }.let {
            assertEquals(HttpStatusCode.InternalServerError, it.status)
            assertFalse(it.bodyAsText().contains("secret"))
            assertEquals("internal_error", it.problemCode())
        }
    }

    // ---- config and packs ----

    @Test
    fun `config comes from server config and packs`() = api {
        val token = newDevice().deviceToken
        val json = client.get("/v1/config") { bearerAuth(token) }
        assertEquals(ContentType.Application.Json, json.contentType()?.withoutParameters())
        val config = Codecs.json.decodeFromString(ConfigResponse.serializer(), json.bodyAsText())
        assertEquals(25, config.pollIntervalS)
        assertEquals(12, config.trackerPollIntervalS)
        assertEquals("Contains Transport for NSW data", config.attribution)
        assertEquals(listOf(pack), config.packs)

        val proto = client.get("/v1/config") { bearerAuth(token); header(HttpHeaders.Accept, MediaTypes.PROTOBUF) }
        assertEquals(config, ProtoBuf.decodeFromByteArray(ConfigResponse.serializer(), proto.bodyAsBytes()))
    }

    @Test
    fun `packs list has an etag and 304s`() = api {
        val token = newDevice().deviceToken
        val first = client.get("/v1/packs") { bearerAuth(token) }
        assertEquals(listOf(pack), Codecs.json.decodeFromString(PacksResponse.serializer(), first.bodyAsText()).packs)
        val etag = assertNotNull(first.headers[HttpHeaders.ETag])
        val second = client.get("/v1/packs") { bearerAuth(token); header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, second.status)
        timetables.packList = listOf(pack.copy(version = "20261112T0300"))
        assertEquals(HttpStatusCode.OK, client.get("/v1/packs") { bearerAuth(token); header(HttpHeaders.IfNoneMatch, etag) }.status)
    }

    @Test
    fun `pack download streams the file with length and immutable caching`() = api {
        val token = newDevice().deviceToken
        val response = client.get("/v1/packs/syd-rail/20261111T0300") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(packBytes.size.toString(), response.headers[HttpHeaders.ContentLength])
        assertEquals("public, max-age=31536000, immutable", response.headers[HttpHeaders.CacheControl])
        assertContentEquals(packBytes, response.bodyAsBytes())
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/packs/syd-rail/old") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/packs/syd-rail/..%2F..%2Fetc") { bearerAuth(token) }.status)
    }

    // ---- live ----

    @Test
    fun `realtime round trips over protobuf and json with poll headers`() = api {
        val token = newDevice().deviceToken
        val proto = client.get("/v1/rt?routes=T1,T9") { bearerAuth(token); header(HttpHeaders.Accept, MediaTypes.PROTOBUF) }
        assertEquals(HttpStatusCode.OK, proto.status)
        assertEquals(MediaTypes.PROTOBUF, proto.contentType()?.withoutParameters().toString())
        assertEquals("25", proto.headers[ApiHeaders.POLL_INTERVAL])
        assertEquals("12", proto.headers[ApiHeaders.TRACKER_POLL_INTERVAL])
        val decoded = ProtoBuf.decodeFromByteArray(RealtimeResponse.serializer(), proto.bodyAsBytes())
        assertEquals(live.trips, decoded.trips)
        assertEquals(clock.now, decoded.serverTimeMs)
        assertEquals(25, decoded.pollIntervalS)
        assertEquals(setOf("T1", "T9") to app.transittracker.server.spi.DemandKind.SCREEN, live.realtimeCalls.single())

        val json = client.get("/v1/rt?routes=T1") { bearerAuth(token) }
        assertEquals(ContentType.Application.Json, json.contentType()?.withoutParameters())
        assertEquals(decoded, Codecs.json.decodeFromString(RealtimeResponse.serializer(), json.bodyAsText()))

        // q-values: JSON preferred over protobuf.
        val preferJson = client.get("/v1/rt?routes=T1") {
            bearerAuth(token)
            header(HttpHeaders.Accept, "${MediaTypes.PROTOBUF};q=0.5, application/json")
        }
        assertEquals(ContentType.Application.Json, preferJson.contentType()?.withoutParameters())
    }

    @Test
    fun `realtime etag ignores server time and 304s until data changes`() = api {
        val token = newDevice().deviceToken
        val first = client.get("/v1/rt?routes=T1") { bearerAuth(token); header(HttpHeaders.Accept, MediaTypes.PROTOBUF) }
        val etag = assertNotNull(first.headers[HttpHeaders.ETag])
        clock.advance(5_000)
        val second = client.get("/v1/rt?routes=T1") {
            bearerAuth(token); header(HttpHeaders.Accept, MediaTypes.PROTOBUF); header(HttpHeaders.IfNoneMatch, etag)
        }
        assertEquals(HttpStatusCode.NotModified, second.status)
        assertEquals(etag, second.headers[HttpHeaders.ETag])
        assertEquals(clock.now.toString(), second.headers[ApiHeaders.SERVER_TIME])
        assertTrue(second.bodyAsBytes().isEmpty())

        live.trips = live.trips.map { it.copy(updatedMs = 2_000) }
        val third = client.get("/v1/rt?routes=T1") {
            bearerAuth(token); header(HttpHeaders.Accept, MediaTypes.PROTOBUF); header(HttpHeaders.IfNoneMatch, etag)
        }
        assertEquals(HttpStatusCode.OK, third.status)
        assertNotEquals(etag, third.headers[HttpHeaders.ETag])
    }

    @Test
    fun `trip tracking validates the date and supports etags`() = api {
        val token = newDevice().deviceToken
        assertEquals("date_required", client.get("/v1/rt/trip/T1.100") { bearerAuth(token) }.problemCode())
        assertEquals("bad_date", client.get("/v1/rt/trip/T1.100?date=2026-11-11") { bearerAuth(token) }.problemCode())
        val ok = client.get("/v1/rt/trip/T1.100?date=20261111") { bearerAuth(token); header(HttpHeaders.Accept, MediaTypes.PROTOBUF) }
        assertEquals("T1.100", ProtoBuf.decodeFromByteArray(RealtimeResponse.serializer(), ok.bodyAsBytes()).trips.single().tripId)
        val again = client.get("/v1/rt/trip/T1.100?date=20261111") {
            bearerAuth(token); header(HttpHeaders.Accept, MediaTypes.PROTOBUF); header(HttpHeaders.IfNoneMatch, ok.headers[HttpHeaders.ETag]!!)
        }
        assertEquals(HttpStatusCode.NotModified, again.status)
    }

    @Test
    fun `routes are required and capped at 30`() = api {
        val token = newDevice().deviceToken
        assertEquals("routes_required", client.get("/v1/rt") { bearerAuth(token) }.problemCode())
        val tooMany = (1..31).joinToString(",") { "R$it" }
        assertEquals("too_many_routes", client.get("/v1/rt?routes=$tooMany") { bearerAuth(token) }.problemCode())
        assertEquals("too_many_routes", client.get("/v1/vehicles?routes=$tooMany") { bearerAuth(token) }.problemCode())
        val thirty = (1..30).joinToString(",") { "R$it" }
        assertEquals(HttpStatusCode.OK, client.get("/v1/rt?routes=$thirty") { bearerAuth(token) }.status)
    }

    @Test
    fun `alerts use the snapshot etag and filter by mode`() = api {
        val token = newDevice().deviceToken
        val first = client.get("/v1/alerts?modes=TRAIN,metro") { bearerAuth(token); header(HttpHeaders.Accept, MediaTypes.PROTOBUF) }
        assertEquals("\"alerts-v1\"", first.headers[HttpHeaders.ETag])
        assertEquals(setOf(Mode.TRAIN, Mode.METRO), live.lastAlertModes)
        val alerts = ProtoBuf.decodeFromByteArray(AlertsResponse.serializer(), first.bodyAsBytes())
        assertEquals("Trackwork", alerts.alerts.single().headline)
        val second = client.get("/v1/alerts") { bearerAuth(token); header(HttpHeaders.IfNoneMatch, "\"alerts-v1\"") }
        assertEquals(HttpStatusCode.NotModified, second.status)
        assertNull(live.lastAlertModes)
        assertEquals("bad_mode", client.get("/v1/alerts?modes=HOVERCRAFT") { bearerAuth(token) }.problemCode())
    }

    @Test
    fun `live endpoints are rate limited per device`() = api {
        val a = newDevice().deviceToken
        val b = newDevice().deviceToken
        repeat(20) { assertEquals(HttpStatusCode.OK, client.get("/v1/rt?routes=T1") { bearerAuth(a) }.status) }
        val limited = client.get("/v1/vehicles?routes=T1") { bearerAuth(a) }
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("rate_limited", limited.problemCode())
        assertEquals("3", limited.headers[HttpHeaders.RetryAfter])
        // Other devices are unaffected; tokens refill over time.
        assertEquals(HttpStatusCode.OK, client.get("/v1/rt?routes=T1") { bearerAuth(b) }.status)
        clock.advance(3_000)
        assertEquals(HttpStatusCode.OK, client.get("/v1/rt?routes=T1") { bearerAuth(a) }.status)
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/v1/rt?routes=T1") { bearerAuth(a) }.status)
    }

    @Test
    fun `shapes are encoded polylines`() = api {
        val token = newDevice().deviceToken
        val response = client.get("/v1/shapes/shape-1") { bearerAuth(token); header(HttpHeaders.Accept, MediaTypes.PROTOBUF) }
        val shape = ProtoBuf.decodeFromByteArray(ShapeResponse.serializer(), response.bodyAsBytes())
        assertEquals(ShapeResponse("shape-1", "_p~iF~ps|U_ulLnnqC_mqNvxq`@"), shape)
        assertEquals("shape_not_found", client.get("/v1/shapes/nope") { bearerAuth(token) }.problemCode())
        timetables.current.value = null
        assertEquals("timetable_unavailable", client.get("/v1/shapes/shape-1") { bearerAuth(token) }.problemCode())
    }

    // ---- device, revoke ----

    @Test
    fun `device update stores the push token`() = api {
        val joined = newDevice()
        val response = client.put("/v1/device") {
            bearerAuth(joined.deviceToken)
            contentType(ContentType.Application.Json)
            setBody(Codecs.json.encodeToString(DeviceUpdate.serializer(), DeviceUpdate(pushToken = "fcm-abc", appVersion = "2.0")))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("fcm-abc", devices.get(joined.deviceId)!!.pushToken)
        assertEquals("2.0", devices.get(joined.deviceId)!!.appVersion)
    }

    @Test
    fun `revoked devices are rejected`() = api {
        val joined = newDevice()
        assertEquals(HttpStatusCode.OK, client.get("/v1/config") { bearerAuth(joined.deviceToken) }.status)
        devices.revoke(joined.deviceId)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/config") { bearerAuth(joined.deviceToken) }.status)
    }

    // ---- watches ----

    private fun watchJson(id: String) =
        Codecs.json.encodeToString(WatchDto.serializer(), WatchDto(id = id, label = "Work", originStationId = "2135", destinationStationId = "2000"))

    @Test
    fun `watches CRUD delegates to the watch service`() = api {
        val joined = newDevice()
        val token = joined.deviceToken
        val put = client.put("/v1/watches/w1") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(watchJson("w1")) }
        assertEquals(HttpStatusCode.OK, put.status)
        assertEquals(5, Codecs.json.decodeFromString(WatchDto.serializer(), put.bodyAsText()).updatedMs)
        assertEquals("w1", watches.list(joined.deviceId).single().id)

        val list = client.get("/v1/watches") { bearerAuth(token); header(HttpHeaders.Accept, MediaTypes.PROTOBUF) }
        assertEquals(listOf("w1"), ProtoBuf.decodeFromByteArray(WatchesResponse.serializer(), list.bodyAsBytes()).watches.map { it.id })

        val mismatch = client.put("/v1/watches/w2") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(watchJson("w1")) }
        assertEquals("id_mismatch", mismatch.problemCode())

        val test = client.post("/v1/watches/w1/test") { bearerAuth(token) }
        assertEquals(TestPushResponse(sent = true), Codecs.json.decodeFromString(TestPushResponse.serializer(), test.bodyAsText()))
        watches.testResult = PushResult.Failed("no token", retryable = false)
        val failed = Codecs.json.decodeFromString(TestPushResponse.serializer(), client.post("/v1/watches/w1/test") { bearerAuth(token) }.bodyAsText())
        assertEquals(TestPushResponse(sent = false, detail = "no token"), failed)
        assertEquals("watch_not_found", client.post("/v1/watches/nope/test") { bearerAuth(token) }.problemCode())

        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/watches/w1") { bearerAuth(token) }.status)
        assertEquals("watch_not_found", client.delete("/v1/watches/w1") { bearerAuth(token) }.problemCode())
        assertTrue(watches.list(joined.deviceId).isEmpty())
    }

    @Test
    fun `watches are capped per device and rate limited`() = api {
        val joined = newDevice()
        repeat(MAX_WATCHES_PER_DEVICE) { i -> watches.upsert(joined.deviceId, Codecs.json.decodeFromString(WatchDto.serializer(), watchJson("w$i"))) }
        val tooMany = client.put("/v1/watches/extra") {
            bearerAuth(joined.deviceToken); contentType(ContentType.Application.Json); setBody(watchJson("extra"))
        }
        assertEquals("too_many_watches", tooMany.problemCode())
        repeat(9) { assertEquals(HttpStatusCode.OK, client.get("/v1/watches") { bearerAuth(joined.deviceToken) }.status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/v1/watches") { bearerAuth(joined.deviceToken) }.status)
    }

    // ---- /status ----

    @Test
    fun `status is available locally as html and json`() = api {
        newDevice()
        val html = client.get("/status")
        assertEquals(HttpStatusCode.OK, html.status)
        assertEquals(ContentType.Text.Html, html.contentType()?.withoutParameters())
        assertContains(html.bodyAsText(), "42 / 50000")

        val json = client.get("/status") { header(HttpHeaders.Accept, "application/json") }
        val report = Codecs.json.decodeFromString(StatusReport.serializer(), json.bodyAsText())
        assertEquals(1, report.activeDevices)
        assertEquals("test-1", report.timetableVersion)
        assertEquals(listOf(pack), report.packs)
    }

    @Test
    fun `status through the proxy needs the admin token`() = api {
        val proxied = client.get("/status") { header(HttpHeaders.XForwardedFor, "198.51.100.7") }
        assertEquals(HttpStatusCode.Forbidden, proxied.status)
        assertEquals("forbidden", proxied.problemCode())
        // Even without forwarding headers, a public Host means it came through Funnel.
        assertEquals(HttpStatusCode.Forbidden, client.get("/status") { header(HttpHeaders.Host, "tt.example.ts.net") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/status") { header(HttpHeaders.Host, "localhost:8080") }.status)
        val wrong = client.get("/status") { header(HttpHeaders.XForwardedFor, "198.51.100.7"); bearerAuth("wrong") }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        // A device token is not an admin token.
        val device = client.get("/status") { header(HttpHeaders.XForwardedFor, "198.51.100.7"); bearerAuth(newDevice().deviceToken) }
        assertEquals(HttpStatusCode.Unauthorized, device.status)
        val ok = client.get("/status") {
            header(HttpHeaders.XForwardedFor, "198.51.100.7")
            header(HttpHeaders.Accept, "application/json")
            bearerAuth(ADMIN_TOKEN)
        }
        assertEquals(HttpStatusCode.OK, ok.status)
    }

    @Test
    fun `json responses are gzip compressed when asked`() = api {
        val response = client.get("/status") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
    }

    private companion object {
        const val ADMIN_TOKEN = "test-admin-token-not-a-secret"
    }
}
