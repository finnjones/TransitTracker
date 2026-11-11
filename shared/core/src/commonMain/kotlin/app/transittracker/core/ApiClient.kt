package app.transittracker.core

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
import app.transittracker.model.RealtimeResponse
import app.transittracker.model.RouteId
import app.transittracker.model.ServiceDate
import app.transittracker.model.ShapeResponse
import app.transittracker.model.TestPushResponse
import app.transittracker.model.TripId
import app.transittracker.model.VehiclesResponse
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchesResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.serializer

/** Outcome of one API call. */
sealed interface ApiResult<out T> {
    /**
     * @property notModified true when the server answered `304` and [value] is the cached copy.
     * @property serverTimeMs the response's `X-Server-Time`, if present.
     */
    data class Success<T>(
        val value: T,
        val notModified: Boolean = false,
        val serverTimeMs: Long? = null,
    ) : ApiResult<T>

    /** The server answered with a non-success status; [error] is the parsed problem JSON, if any. */
    data class HttpFailure(val status: Int, val error: ApiError?) : ApiResult<Nothing>

    /** The server could not be reached or the response could not be decoded. */
    data class NetworkFailure(val message: String, val cause: Throwable? = null) : ApiResult<Nothing>
}

/** The value of a [ApiResult.Success], or null. */
fun <T> ApiResult<T>.valueOrNull(): T? = (this as? ApiResult.Success)?.value

/** Shared JSON settings for the API (tolerant of new fields). */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * Typed client for the tt-server HTTP API (see [ApiPaths]).
 *
 * - JSON for normal calls; protobuf (`Accept: application/x-protobuf`) for `/v1/rt`,
 *   `/v1/rt/trip/{id}` and `/v1/vehicles`.
 * - Sends `Authorization: Bearer <token>` when [tokenProvider] returns a token.
 * - Sends `If-None-Match` for real-time and alerts; on `304` returns the cached body with
 *   [ApiResult.Success.notModified] set.
 * - Feeds `X-Server-Time` into [timeSync].
 *
 * Works with any Ktor [HttpClient]; it does its own (de)serialisation so no plugins are needed.
 */
class ApiClient(
    private val http: HttpClient,
    baseUrl: String,
    private val timeSync: TimeSync,
    private val tokenProvider: suspend () -> String?,
) {
    private val base = baseUrl.trimEnd('/')
    private val proto = ProtoBuf
    private val etagMutex = Mutex()
    private val etagCache = mutableMapOf<String, CachedBody>()

    private val _reachable = MutableStateFlow<Boolean?>(null)

    /** Whether the last call reached the server (null before the first call). */
    val reachable: StateFlow<Boolean?> = _reachable.asStateFlow()

    private class CachedBody(val etag: String, val bytes: ByteArray, val contentType: String?)

    suspend fun join(request: JoinRequest): ApiResult<JoinResponse> =
        call(HttpMethod.Post, ApiPaths.JOIN, body = ApiJson.encodeToString(JoinRequest.serializer(), request), auth = false)

    suspend fun config(): ApiResult<ConfigResponse> = call(HttpMethod.Get, ApiPaths.CONFIG)

    /** `GET /v1/rt?routes=…` (protobuf, ETag). */
    suspend fun realtime(routes: Set<RouteId>): ApiResult<RealtimeResponse> = call(
        HttpMethod.Get, ApiPaths.REALTIME,
        query = listOf("routes" to routes.sorted().joinToString(",")),
        protobuf = true, conditional = true,
    )

    /** `GET /v1/rt/trip/{tripId}?date=…` (protobuf). */
    suspend fun realtimeTrip(tripId: TripId, serviceDate: ServiceDate): ApiResult<RealtimeResponse> = call(
        HttpMethod.Get, ApiPaths.REALTIME_TRIP.replace("{tripId}", tripId.encodeURLPathPart()),
        query = listOf("date" to serviceDate),
        protobuf = true,
    )

    /** `GET /v1/vehicles?routes=…` (protobuf). */
    suspend fun vehicles(routes: Set<RouteId>): ApiResult<VehiclesResponse> = call(
        HttpMethod.Get, ApiPaths.VEHICLES,
        query = listOf("routes" to routes.sorted().joinToString(",")),
        protobuf = true,
    )

    /** `GET /v1/alerts?modes=…` (JSON, ETag). */
    suspend fun alerts(modes: Set<Mode>): ApiResult<AlertsResponse> = call(
        HttpMethod.Get, ApiPaths.ALERTS,
        query = if (modes.isEmpty()) emptyList() else listOf("modes" to modes.map { it.name }.sorted().joinToString(",")),
        conditional = true,
    )

    suspend fun shape(shapeId: String): ApiResult<ShapeResponse> =
        call(HttpMethod.Get, ApiPaths.SHAPE.replace("{shapeId}", shapeId.encodeURLPathPart()))

    suspend fun updateDevice(update: DeviceUpdate): ApiResult<Unit> =
        call(HttpMethod.Put, ApiPaths.DEVICE, body = ApiJson.encodeToString(DeviceUpdate.serializer(), update))

    suspend fun watches(): ApiResult<WatchesResponse> = call(HttpMethod.Get, ApiPaths.WATCHES)

    /** Upserts a watch; returns the server's copy, or [watch] if the server sent no body. */
    suspend fun putWatch(watch: WatchDto): ApiResult<WatchDto> {
        val result = call<String>(
            HttpMethod.Put, watchPath(ApiPaths.WATCH, watch.id),
            body = ApiJson.encodeToString(WatchDto.serializer(), watch),
        )
        return when (result) {
            is ApiResult.Success -> {
                val parsed = result.value.takeIf { it.isNotBlank() }?.let {
                    runCatching { ApiJson.decodeFromString(WatchDto.serializer(), it) }.getOrNull()
                }
                ApiResult.Success(parsed ?: watch, serverTimeMs = result.serverTimeMs)
            }
            is ApiResult.HttpFailure -> result
            is ApiResult.NetworkFailure -> result
        }
    }

    suspend fun deleteWatch(id: String): ApiResult<Unit> = call(HttpMethod.Delete, watchPath(ApiPaths.WATCH, id))

    suspend fun testWatch(id: String): ApiResult<TestPushResponse> =
        call(HttpMethod.Post, watchPath(ApiPaths.WATCH_TEST, id))

    /** Downloads raw bytes from a server-relative [path] (e.g. a pack URL). */
    suspend fun download(path: String): ApiResult<ByteArray> = call(HttpMethod.Get, path, accept = "*/*")

    private fun watchPath(template: String, id: String) = template.replace("{id}", id.encodeURLPathPart())

    private suspend inline fun <reified T> call(
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: String? = null,
        auth: Boolean = true,
        protobuf: Boolean = false,
        conditional: Boolean = false,
        accept: String? = null,
    ): ApiResult<T> = execute(method, path, query, body, auth, protobuf, conditional, accept, serializer<T>())

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> execute(
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String>>,
        body: String?,
        auth: Boolean,
        protobuf: Boolean,
        conditional: Boolean,
        accept: String?,
        deserializer: DeserializationStrategy<T>,
    ): ApiResult<T> {
        val url = if (path.startsWith("http://") || path.startsWith("https://")) path else base + path
        val cacheKey = url + query.joinToString("&", prefix = "?") { "${it.first}=${it.second}" }
        val cached = if (conditional) etagMutex.withLock { etagCache[cacheKey] } else null
        val token = if (auth) tokenProvider() else null
        val sent = timeSync.localMillis()
        val response: HttpResponse
        val bytes: ByteArray
        try {
            response = http.request(url) {
                this.method = method
                query.forEach { (k, v) -> parameter(k, v) }
                header(HttpHeaders.Accept, accept ?: if (protobuf) MediaTypes.PROTOBUF else MediaTypes.JSON)
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                if (cached != null) header(HttpHeaders.IfNoneMatch, cached.etag)
                if (body != null) setBody(TextContent(body, ContentType.Application.Json))
            }
            bytes = response.bodyAsBytes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _reachable.value = false
            return ApiResult.NetworkFailure(e.message ?: e.toString(), e)
        }
        _reachable.value = true
        val received = timeSync.localMillis()
        val serverTime = response.headers[ApiHeaders.SERVER_TIME]?.toLongOrNull()
        if (serverTime != null) timeSync.addSample(serverTime, sent, received)

        val status = response.status.value
        if (status == 304 && cached != null) {
            return decode(cached.bytes, cached.contentType, deserializer, serverTime, notModified = true)
        }
        if (status !in 200..299) {
            val error = runCatching { ApiJson.decodeFromString(ApiError.serializer(), bytes.decodeToString()) }.getOrNull()
            return ApiResult.HttpFailure(status, error)
        }
        val contentType = response.headers[HttpHeaders.ContentType]
        val etag = response.headers[HttpHeaders.ETag]
        if (conditional && etag != null) {
            etagMutex.withLock { etagCache[cacheKey] = CachedBody(etag, bytes, contentType) }
        }
        return decode(bytes, contentType, deserializer, serverTime, notModified = false)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> decode(
        bytes: ByteArray,
        contentType: String?,
        deserializer: DeserializationStrategy<T>,
        serverTime: Long?,
        notModified: Boolean,
    ): ApiResult<T> = try {
        val value: T = when (deserializer.descriptor.serialName) {
            "kotlin.Unit" -> Unit as T
            "kotlin.String" -> bytes.decodeToString() as T
            "kotlin.ByteArray" -> bytes as T
            else -> if (contentType?.startsWith(MediaTypes.PROTOBUF) == true) {
                proto.decodeFromByteArray(deserializer, bytes)
            } else {
                ApiJson.decodeFromString(deserializer, bytes.decodeToString())
            }
        }
        ApiResult.Success(value, notModified, serverTime)
    } catch (e: SerializationException) {
        ApiResult.NetworkFailure("Bad response: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        ApiResult.NetworkFailure("Bad response: ${e.message}", e)
    }
}
