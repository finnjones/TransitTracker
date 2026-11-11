package app.transittracker.server.app

import app.transittracker.model.AlertsResponse
import app.transittracker.model.ApiError
import app.transittracker.model.ApiHeaders
import app.transittracker.model.ApiPaths
import app.transittracker.model.ConfigResponse
import app.transittracker.model.DeviceUpdate
import app.transittracker.model.JoinRequest
import app.transittracker.model.JoinResponse
import app.transittracker.model.MediaTypes
import app.transittracker.model.Mode
import app.transittracker.model.PacksResponse
import app.transittracker.model.RealtimeResponse
import app.transittracker.model.RouteId
import app.transittracker.model.ServiceTime
import app.transittracker.model.ShapeResponse
import app.transittracker.model.TestPushResponse
import app.transittracker.model.VehiclesResponse
import app.transittracker.model.WallClock
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchesResponse
import app.transittracker.server.spi.ApiException
import app.transittracker.server.spi.DemandKind
import app.transittracker.server.spi.DeviceRecord
import app.transittracker.server.spi.LiveData
import app.transittracker.server.spi.NotificationLog
import app.transittracker.server.spi.PushResult
import app.transittracker.server.spi.ServerConfig
import app.transittracker.server.spi.TimetableRepository
import app.transittracker.server.spi.TrustedProxy
import app.transittracker.server.spi.WatchService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.acceptItems
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.nio.file.Files

/** Everything the HTTP API needs; built in `Main.kt` (real) or in tests (fakes). */
class ApiDependencies(
    val config: ServerConfig,
    val devices: SqliteDeviceStore,
    val notifications: NotificationLog,
    val timetables: TimetableRepository,
    val live: LiveData,
    val watches: WatchService,
    val clock: WallClock = SystemClock,
    val limits: RateLimits = RateLimits(clock),
)

/** Maximum route ids in one `routes` query. */
const val MAX_ROUTES = 30

/** Maximum watches one device may store. */
const val MAX_WATCHES_PER_DEVICE = 10

private val log = LoggerFactory.getLogger("app.transittracker.server.app.HttpApi")
private val DeviceKey = AttributeKey<DeviceRecord>("device")

/** The authenticated device of this call (only inside [deviceAuthenticated] routes). */
val ApplicationCall.device: DeviceRecord get() = attributes[DeviceKey]

/** `GET /health` body. */
@Serializable
data class HealthResponse(val ok: Boolean, val timetableLoaded: Boolean, val timetableVersion: String? = null)

/** Installs plugins and routes of the tt-server HTTP API. */
fun Application.transitTrackerApi(deps: ApiDependencies) {
    installPlugins(deps)
    routing {
        get(ApiPaths.HEALTH) {
            val timetable = deps.timetables.current.value
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(
                Codecs.json.encodeToString(
                    HealthResponse.serializer(),
                    HealthResponse(ok = true, timetableLoaded = timetable != null, timetableVersion = timetable?.version),
                ),
                ContentType.Application.Json,
            )
        }
        get(ApiPaths.STATUS) { statusPage(call, deps) }
        post(ApiPaths.JOIN) { join(call, deps) }

        deviceAuthenticated(deps.devices) {
            get(ApiPaths.CONFIG) { call.respondPayload(ConfigResponse.serializer(), configResponse(deps)) }
            get(ApiPaths.PACKS) {
                call.respondPayloadWithEtag(PacksResponse.serializer(), PacksResponse(deps.timetables.packs()))
            }
            get(ApiPaths.PACK_FILE) { packFile(call, deps) }
            get(ApiPaths.REALTIME) { realtime(call, deps) }
            get(ApiPaths.REALTIME_TRIP) { realtimeTrip(call, deps) }
            get(ApiPaths.VEHICLES) { vehicles(call, deps) }
            get(ApiPaths.ALERTS) { alerts(call, deps) }
            get(ApiPaths.SHAPE) { shape(call, deps) }
            put(ApiPaths.DEVICE) {
                val update = call.receivePayload(DeviceUpdate.serializer())
                io { deps.devices.update(call.device.deviceId, update) }
                call.respond(HttpStatusCode.NoContent)
            }
            get(ApiPaths.WATCHES) {
                call.rateLimit(deps.limits.watches, call.device.deviceId)
                val watches = io { deps.watches.list(call.device.deviceId) }
                call.respondPayload(WatchesResponse.serializer(), WatchesResponse(watches))
            }
            put(ApiPaths.WATCH) { putWatch(call, deps) }
            delete(ApiPaths.WATCH) {
                call.rateLimit(deps.limits.watches, call.device.deviceId)
                val id = call.pathParam("id")
                if (!io { deps.watches.delete(call.device.deviceId, id) }) {
                    throw ApiException(404, "watch_not_found", "No such watch")
                }
                call.respond(HttpStatusCode.NoContent)
            }
            post(ApiPaths.WATCH_TEST) { testWatch(call, deps) }
        }
    }
}

// ---- plugins ---------------------------------------------------------------------------------

private fun Application.installPlugins(deps: ApiDependencies) {
    install(createApplicationPlugin("ServerTime") {
        onCall { call -> call.response.header(ApiHeaders.SERVER_TIME, deps.clock.nowMillis().toString()) }
    })
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path() != ApiPaths.HEALTH }
        // Path only: no query strings or headers (tokens) in the log.
        format { call -> "${call.response.status()?.value} ${call.request.httpMethod.value} ${call.request.path()}" }
    }
    install(Compression) {
        gzip {
            matchContentType(
                ContentType.Application.Json,
                ContentType.parse(MediaTypes.PROBLEM_JSON),
                ContentType.Text.Html,
            )
            minimumSize(512)
        }
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respondProblem(e.toApiError()) }
        exception<BadRequestException> { call, _ ->
            call.respondProblem(ApiError("bad_request", "Bad Request", "Malformed request", 400))
        }
        exception<NotFoundException> { call, _ ->
            call.respondProblem(ApiError("not_found", "Not Found", null, 404))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error on {} {}", call.request.httpMethod.value, call.request.path(), e)
            call.respondProblem(ApiError("internal_error", "Internal Server Error", null, 500))
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondProblem(ApiError("not_found", "Not Found", null, 404))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.respondProblem(ApiError("method_not_allowed", "Method Not Allowed", null, 405))
        }
    }
}

private class DeviceAuthConfig {
    lateinit var store: SqliteDeviceStore
}

/** Requires `Authorization: Bearer <deviceToken>`; puts the device into the call attributes. */
private val DeviceAuth = createRouteScopedPlugin("DeviceAuth", ::DeviceAuthConfig) {
    val store = pluginConfig.store
    onCall { call ->
        val token = call.bearerToken()
        val device = token?.let { io { store.authenticate(it) } }
        if (device == null) {
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
            throw ApiException(
                401,
                "unauthorized",
                if (token == null) "Missing bearer token" else "Unknown or revoked device token",
            )
        }
        call.attributes.put(DeviceKey, device)
    }
}

/** A routing node that matches transparently, so plugins installed on it cover only its children. */
private object DeviceAuthSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(device auth)"
}

private fun Route.deviceAuthenticated(store: SqliteDeviceStore, build: Route.() -> Unit): Route {
    val child = createChild(DeviceAuthSelector)
    child.install(DeviceAuth) { this.store = store }
    child.build()
    return child
}

private fun ApplicationCall.bearerToken(): String? {
    val header = request.header(HttpHeaders.Authorization) ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return header.substring(7).trim().takeIf { it.isNotEmpty() }
}

// ---- helpers ---------------------------------------------------------------------------------

/** Runs blocking work (JDBC, module calls that may block) off the request thread. */
private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private fun ApplicationCall.rateLimit(limiter: TokenBucketLimiter, key: String) {
    val retryAfter = limiter.tryAcquire(key)
    if (retryAfter > 0) {
        response.header(HttpHeaders.RetryAfter, retryAfter.toString())
        throw ApiException(429, "rate_limited", "Too many requests; retry in $retryAfter s")
    }
}

private fun ApplicationCall.pathParam(name: String): String =
    parameters[name]?.takeIf { it.isNotBlank() } ?: throw ApiException(400, "bad_request", "Missing $name")

private fun ApplicationCall.routesParam(): Set<RouteId> {
    val routes = request.queryParameters.getAll("routes").orEmpty()
        .flatMap { it.split(',') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
    if (routes.isEmpty()) throw ApiException(400, "routes_required", "Query parameter 'routes' is required")
    if (routes.size > MAX_ROUTES) throw ApiException(400, "too_many_routes", "At most $MAX_ROUTES routes per request")
    if (routes.any { it.length > 64 }) throw ApiException(400, "bad_request", "Route id too long")
    return routes
}

private fun ApplicationCall.livePollHeaders(config: ServerConfig) {
    response.header(ApiHeaders.POLL_INTERVAL, config.pollIntervalS.toString())
    response.header(ApiHeaders.TRACKER_POLL_INTERVAL, config.trackerPollIntervalS.toString())
    response.header(HttpHeaders.CacheControl, "no-cache")
}

/**
 * The client IP for per-IP limits.
 *
 * - Behind Cloudflare Tunnel (`TT_TRUSTED_PROXY=cloudflare`) every request reaches tt-server from
 *   the cloudflared container, and Cloudflare sets `CF-Connecting-IP` to the real client address.
 *   This is only safe because tt-server publishes no ports: the tunnel is the only way in.
 * - Behind a reverse proxy on loopback (e.g. Tailscale Funnel in the same network namespace),
 *   `X-Forwarded-For` is trusted only when the direct peer is loopback.
 */
fun ApplicationCall.clientIp(trustedProxy: TrustedProxy = TrustedProxy.NONE): String {
    val peer = request.local.remoteAddress
    if (trustedProxy == TrustedProxy.CLOUDFLARE) {
        request.header(CF_CONNECTING_IP)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    if (isLoopback(peer)) {
        request.header(HttpHeaders.XForwardedFor)?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }
    }
    return peer
}

const val CF_CONNECTING_IP = "CF-Connecting-IP"

/**
 * True if the request came straight from this machine, not through a proxy such as Tailscale
 * Funnel. In the Docker setup Tailscale proxies from loopback, so a loopback peer alone is not
 * enough: the request must also carry no forwarding headers (Tailscale sets `X-Forwarded-For`)
 * and must address the server as localhost (Tailscale keeps the public `Host`).
 */
fun ApplicationCall.isDirectLocalRequest(): Boolean {
    if (!isLoopback(request.local.remoteAddress)) return false
    val proxied = listOf(
        HttpHeaders.XForwardedFor, HttpHeaders.XForwardedHost, HttpHeaders.Forwarded, "X-Real-IP",
        "Tailscale-Funnel-Request", CF_CONNECTING_IP, "CF-Ray",
    ).any { request.header(it) != null }
    if (proxied) return false
    val host = request.header(HttpHeaders.Host)?.substringBeforeLast(':')?.removeSurrounding("[", "]") ?: return true
    return isLoopback(host)
}

private fun isLoopback(address: String): Boolean =
    address == "localhost" || address == "::1" || address == "0:0:0:0:0:0:0:1" || address.startsWith("127.")

// ---- handlers --------------------------------------------------------------------------------

private suspend fun join(call: ApplicationCall, deps: ApiDependencies) {
    call.rateLimit(deps.limits.join, call.clientIp(deps.config.trustedProxy))
    val request = call.receivePayload(JoinRequest.serializer())
    val response = io { deps.devices.join(request.inviteCode, request.platform, request.appVersion) }
    call.respondPayload(JoinResponse.serializer(), response)
}

private fun configResponse(deps: ApiDependencies): ConfigResponse {
    val c = deps.config
    // Live data comes from TfNSW (API key) or, in development, from replayed recordings.
    val liveAvailable = c.tfnswApiKey != null || c.replayDir != null
    return ConfigResponse(
        pollIntervalS = c.pollIntervalS,
        trackerPollIntervalS = c.trackerPollIntervalS,
        minAppVersion = c.minAppVersion,
        attribution = c.attribution,
        statusMessage = c.statusMessage,
        features = mapOf(
            "push" to (c.firebaseServiceAccountPath != null),
            "realtime" to liveAvailable,
            "vehicles" to liveAvailable,
            "alerts" to liveAvailable,
        ),
        packs = deps.timetables.packs(),
    )
}

private val SAFE_SEGMENT = Regex("[A-Za-z0-9._-]{1,100}")

private suspend fun packFile(call: ApplicationCall, deps: ApiDependencies) {
    val name = call.pathParam("name")
    val version = call.pathParam("version")
    if (!SAFE_SEGMENT.matches(name) || !SAFE_SEGMENT.matches(version) || name.startsWith('.') || version.startsWith('.')) {
        throw ApiException(404, "pack_not_found", "No such pack")
    }
    val path = deps.timetables.packFile(name, version)
        ?.takeIf { Files.isRegularFile(it) }
        ?: throw ApiException(404, "pack_not_found", "No such pack")
    val etag = "\"$name-$version\""
    call.response.header(HttpHeaders.ETag, etag)
    call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
    if (call.ifNoneMatchMatches(etag)) {
        call.respond(HttpStatusCode.NotModified)
        return
    }
    call.respond(LocalPathContent(path, ContentType.Application.OctetStream))
}

private suspend fun realtime(call: ApplicationCall, deps: ApiDependencies) {
    call.rateLimit(deps.limits.live, call.device.deviceId)
    val routes = call.routesParam()
    val response = deps.live.realtime(routes, DemandKind.SCREEN)
    respondRealtime(call, deps, response)
}

private suspend fun realtimeTrip(call: ApplicationCall, deps: ApiDependencies) {
    call.rateLimit(deps.limits.live, call.device.deviceId)
    val tripId = call.pathParam("tripId")
    if (tripId.length > 128) throw ApiException(400, "bad_request", "Trip id too long")
    val date = call.request.queryParameters["date"]
        ?: throw ApiException(400, "date_required", "Query parameter 'date' (YYYYMMDD) is required")
    runCatching { ServiceTime.parseDate(date) }.getOrElse {
        throw ApiException(400, "bad_date", "Query parameter 'date' must be YYYYMMDD")
    }
    val response = deps.live.trip(tripId, date, DemandKind.TRACKING)
    respondRealtime(call, deps, response)
}

private suspend fun respondRealtime(call: ApplicationCall, deps: ApiDependencies, response: RealtimeResponse) {
    val payload = response.copy(
        serverTimeMs = deps.clock.nowMillis(),
        pollIntervalS = deps.config.pollIntervalS,
        trackerPollIntervalS = deps.config.trackerPollIntervalS,
    )
    call.livePollHeaders(deps.config)
    // The ETag ignores the server time, so it only changes when the live data does.
    call.respondPayloadWithEtag(RealtimeResponse.serializer(), payload, etagSource = payload.copy(serverTimeMs = 0))
}

private suspend fun vehicles(call: ApplicationCall, deps: ApiDependencies) {
    call.rateLimit(deps.limits.live, call.device.deviceId)
    val routes = call.routesParam()
    val response = deps.live.vehicles(routes)
        .copy(serverTimeMs = deps.clock.nowMillis(), pollIntervalS = deps.config.pollIntervalS)
    call.livePollHeaders(deps.config)
    call.respondPayload(VehiclesResponse.serializer(), response)
}

private suspend fun alerts(call: ApplicationCall, deps: ApiDependencies) {
    call.rateLimit(deps.limits.live, call.device.deviceId)
    val modes = call.request.queryParameters.getAll("modes").orEmpty()
        .flatMap { it.split(',') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { name ->
            Mode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw ApiException(400, "bad_mode", "Unknown mode '$name'")
        }
        .toSet()
        .takeIf { it.isNotEmpty() }
    val snapshot = deps.live.alerts(modes)
    call.livePollHeaders(deps.config)
    call.respondPayloadWithEtag(
        AlertsResponse.serializer(),
        snapshot.response.copy(serverTimeMs = deps.clock.nowMillis()),
        etag = snapshot.etag.removeSurrounding("\""),
    )
}

private suspend fun shape(call: ApplicationCall, deps: ApiDependencies) {
    val shapeId = call.pathParam("shapeId")
    val timetable = deps.timetables.current.value
        ?: throw ApiException(503, "timetable_unavailable", "No timetable loaded yet")
    val points = io { timetable.shape(shapeId) } ?: throw ApiException(404, "shape_not_found", "No such shape")
    call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
    call.respondPayloadWithEtag(ShapeResponse.serializer(), ShapeResponse(shapeId, Polyline.encode(points)))
}

private suspend fun putWatch(call: ApplicationCall, deps: ApiDependencies) {
    val deviceId = call.device.deviceId
    call.rateLimit(deps.limits.watches, deviceId)
    val id = call.pathParam("id")
    if (id.length > 64) throw ApiException(400, "bad_request", "Watch id too long")
    val body = call.receivePayload(WatchDto.serializer())
    if (body.id.isNotEmpty() && body.id != id) {
        throw ApiException(400, "id_mismatch", "Watch id in body does not match the URL")
    }
    val saved = io {
        val existing = deps.watches.list(deviceId)
        if (existing.none { it.id == id } && existing.size >= MAX_WATCHES_PER_DEVICE) {
            throw ApiException(409, "too_many_watches", "At most $MAX_WATCHES_PER_DEVICE watches per device")
        }
        deps.watches.upsert(deviceId, body.copy(id = id))
    }
    call.respondPayload(WatchDto.serializer(), saved)
}

private suspend fun testWatch(call: ApplicationCall, deps: ApiDependencies) {
    val deviceId = call.device.deviceId
    call.rateLimit(deps.limits.watches, deviceId)
    val id = call.pathParam("id")
    if (io { deps.watches.list(deviceId) }.none { it.id == id }) {
        throw ApiException(404, "watch_not_found", "No such watch")
    }
    val response = when (val result = deps.watches.sendTest(deviceId, id)) {
        is PushResult.Sent -> TestPushResponse(sent = true)
        PushResult.InvalidToken -> TestPushResponse(sent = false, detail = "Push token no longer valid; reopen the app")
        is PushResult.Failed -> TestPushResponse(sent = false, detail = result.reason)
    }
    call.respondPayload(TestPushResponse.serializer(), response)
}

// ---- /status -----------------------------------------------------------------------------------

private suspend fun statusPage(call: ApplicationCall, deps: ApiDependencies) {
    val adminToken = deps.config.adminToken
    val presented = call.bearerToken()
    val allowed = when {
        presented != null -> adminToken != null && Secrets.constantTimeEquals(presented, adminToken)
        else -> call.isDirectLocalRequest()
    }
    if (!allowed) {
        if (presented != null) throw ApiException(401, "unauthorized", "Invalid admin token")
        throw ApiException(403, "forbidden", "/status is only available locally or with the admin token")
    }
    val report = io { buildStatusReport(deps) }
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.response.header(HttpHeaders.Vary, HttpHeaders.Accept)
    val wantsJson = call.request.acceptItems().firstOrNull { it.quality > 0 }?.value
        ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
        ?.let { it.match(ContentType.Application.Json) } == true
    if (wantsJson) {
        call.respondText(
            Codecs.prettyJson.encodeToString(StatusReport.serializer(), report),
            ContentType.Application.Json,
        )
    } else {
        call.respondText(renderStatusHtml(report), ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }
}
