@file:OptIn(ExperimentalSerializationApi::class)

package app.transittracker.server.app

import app.transittracker.model.ApiError
import app.transittracker.model.MediaTypes
import app.transittracker.server.spi.ApiException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.acceptItems
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.protobuf.ProtoBuf

/** The serializers used on the wire and in the database. */
object Codecs {
    /** JSON: defaults written out (clients see every field), nulls omitted, unknown keys ignored. */
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /** Protobuf via kotlinx `ProtoBuf`; field numbers come from `@ProtoNumber` in the contracts. */
    val protobuf: ProtoBuf = ProtoBuf

    /** Pretty JSON for the human-facing /status page. */
    val prettyJson: Json = Json(json) { prettyPrint = true }
}

/** Body encodings the API speaks. */
enum class WireFormat(val contentType: ContentType) {
    JSON(ContentType.Application.Json),
    PROTOBUF(ContentType.parse(MediaTypes.PROTOBUF)),
    ;

    /** Serializes [value] in this format. */
    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray = when (this) {
        JSON -> Codecs.json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        PROTOBUF -> Codecs.protobuf.encodeToByteArray(serializer, value)
    }

    /** Deserializes [bytes] in this format. */
    fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T = when (this) {
        JSON -> Codecs.json.decodeFromString(serializer, bytes.toString(Charsets.UTF_8))
        PROTOBUF -> Codecs.protobuf.decodeFromByteArray(serializer, bytes)
    }
}

private val PROBLEM_JSON = ContentType.parse(MediaTypes.PROBLEM_JSON)

/** Largest request body accepted (join, device update, watch upsert are all tiny). */
private const val MAX_BODY_BYTES = 64 * 1024L

/**
 * The response format the client asked for in `Accept`: protobuf only if it ranks
 * `application/x-protobuf` above JSON; JSON otherwise (including when `Accept` is absent).
 */
fun ApplicationCall.responseFormat(): WireFormat {
    for (item in request.acceptItems()) {
        if (item.quality <= 0.0) continue
        val type = runCatching { ContentType.parse(item.value) }.getOrNull() ?: continue
        val bare = type.withoutParameters()
        when {
            bare.match(WireFormat.PROTOBUF.contentType) -> return WireFormat.PROTOBUF
            // */*, application/*, application/json, application/problem+json, ...
            bare.contentType == "*" || bare.contentType == "application" -> return WireFormat.JSON
        }
    }
    return WireFormat.JSON
}

/** The format of the request body, from `Content-Type` (JSON when absent). */
fun ApplicationCall.requestFormat(): WireFormat {
    val type = runCatching { request.contentType().withoutParameters() }.getOrDefault(ContentType.Any)
    return if (type.match(WireFormat.PROTOBUF.contentType)) WireFormat.PROTOBUF else WireFormat.JSON
}

/** Reads and decodes the request body as [serializer]; malformed bodies become 400 problems. */
suspend fun <T> ApplicationCall.receivePayload(serializer: KSerializer<T>): T {
    val declared = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (declared != null && declared > MAX_BODY_BYTES) {
        throw ApiException(413, "body_too_large", "Request body too large")
    }
    val bytes = receiveChannel().readRemaining(MAX_BODY_BYTES + 1).readByteArray()
    if (bytes.size > MAX_BODY_BYTES) throw ApiException(413, "body_too_large", "Request body too large")
    return try {
        requestFormat().decode(serializer, bytes)
    } catch (e: SerializationException) {
        throw ApiException(400, "bad_request", "Malformed request body")
    } catch (e: IllegalArgumentException) {
        throw ApiException(400, "bad_request", "Malformed request body")
    }
}

/** Encodes [value] in the negotiated format and responds with it. */
suspend fun <T> ApplicationCall.respondPayload(
    serializer: KSerializer<T>,
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    val format = responseFormat()
    response.header(HttpHeaders.Vary, HttpHeaders.Accept)
    respondBytes(format.encode(serializer, value), format.contentType, status)
}

/**
 * Responds with [value] tagged with an ETag, or `304 Not Modified` if the client's
 * `If-None-Match` already names it. [etag] is the unquoted tag; when null, the tag is a hash of
 * the encoded bytes of [etagSource] (defaults to [value]), so fields that change on every call
 * (such as the server time) can be excluded from it.
 */
suspend fun <T> ApplicationCall.respondPayloadWithEtag(
    serializer: KSerializer<T>,
    value: T,
    etag: String? = null,
    etagSource: T = value,
) {
    val format = responseFormat()
    val tag = etag ?: Secrets.sha256Hex(format.encode(serializer, etagSource)).take(32)
    val quoted = "\"$tag\""
    response.header(HttpHeaders.ETag, quoted)
    response.header(HttpHeaders.Vary, HttpHeaders.Accept)
    if (ifNoneMatchMatches(quoted)) {
        respond(HttpStatusCode.NotModified)
    } else {
        respondBytes(format.encode(serializer, value), format.contentType, HttpStatusCode.OK)
    }
}

/** True if the request's `If-None-Match` contains [quotedEtag] (weak comparison) or `*`. */
fun ApplicationCall.ifNoneMatchMatches(quotedEtag: String): Boolean {
    val header = request.headers.getAll(HttpHeaders.IfNoneMatch)?.joinToString(",") ?: return false
    val wanted = quotedEtag.removePrefix("W/")
    return header.split(',').map { it.trim() }.any { it == "*" || it.removePrefix("W/") == wanted }
}

/** Responds with an RFC 9457 problem document built from [error]. */
suspend fun ApplicationCall.respondProblem(error: ApiError) {
    val base = Codecs.json.encodeToJsonElement(ApiError.serializer(), error).jsonObject
    val body = buildJsonObject {
        put("type", JsonPrimitive("about:blank"))
        base.forEach { (k, v) -> put(k, v) }
    }
    val text = Codecs.json.encodeToString(JsonObject.serializer(), body)
    respond(TextContent(text, PROBLEM_JSON, HttpStatusCode.fromValue(error.status)))
}

/** Problem document for an [ApiException]. */
fun ApiException.toApiError(): ApiError =
    ApiError(code = code, title = HttpStatusCode.fromValue(status).description, detail = message, status = status)
