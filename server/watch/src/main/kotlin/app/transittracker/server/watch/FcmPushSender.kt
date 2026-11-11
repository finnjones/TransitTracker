package app.transittracker.server.watch

import app.transittracker.model.PushKeys
import app.transittracker.server.spi.PushMessage
import app.transittracker.server.spi.PushResult
import app.transittracker.server.spi.PushSender
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** A plain HTTP reply, as seen by [FcmPushSender]. */
data class HttpReply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

/** The one HTTP call [FcmPushSender] makes; swapped for a fake in tests. */
fun interface FcmTransport {
    suspend fun post(url: String, headers: Map<String, String>, body: String): HttpReply
}

/** Supplies OAuth2 access tokens for FCM; implementations cache and refresh them. */
fun interface AccessTokenSource {
    /** A valid access token; [forceRefresh] fetches a new one even if the cached one looks valid. */
    suspend fun accessToken(forceRefresh: Boolean): String
}

/** [FcmTransport] over the JDK HTTP client. */
class JdkFcmTransport(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val timeout: Duration = Duration.ofSeconds(10),
) : FcmTransport {
    override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpReply {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        return HttpReply(
            status = response.statusCode(),
            body = response.body().orEmpty(),
            headers = response.headers().map().mapValues { it.value.firstOrNull().orEmpty() },
        )
    }
}

/**
 * Access tokens from Google service-account credentials. The library caches the token and
 * [GoogleCredentials.refreshIfExpired] renews it shortly before expiry; calls are serialised
 * and run on the IO dispatcher because refreshing blocks.
 */
class GoogleAccessTokenSource(private val credentials: GoogleCredentials) : AccessTokenSource {
    private val mutex = Mutex()

    override suspend fun accessToken(forceRefresh: Boolean): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (forceRefresh) credentials.refresh() else credentials.refreshIfExpired()
            credentials.accessToken?.tokenValue ?: error("Google credentials returned no access token")
        }
    }

    companion object {
        const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

        /** Loads a service-account JSON file and scopes it for FCM. */
        fun fromServiceAccountFile(path: Path): GoogleAccessTokenSource =
            Files.newInputStream(path).use { GoogleAccessTokenSource(GoogleCredentials.fromStream(it).createScoped(listOf(FCM_SCOPE))) }
    }
}

/**
 * [PushSender] for the FCM HTTP v1 API
 * (`POST https://fcm.googleapis.com/v1/projects/{projectId}/messages:send`).
 *
 * Result mapping: `UNREGISTERED`, HTTP 404, or `INVALID_ARGUMENT` about the token ->
 * [PushResult.InvalidToken]; 429 / 5xx / network errors -> one retry after [retryDelayMs], then
 * `Failed(retryable = true)`; 401 -> one retry with a freshly fetched access token; anything else
 * -> `Failed(retryable = false)`. Push tokens are never logged.
 */
class FcmPushSender(
    private val projectId: String,
    private val tokens: AccessTokenSource,
    private val transport: FcmTransport = JdkFcmTransport(),
    private val retryDelayMs: Long = 1_000L,
    private val baseUrl: String = "https://fcm.googleapis.com",
) : PushSender {

    private val logger = LoggerFactory.getLogger(FcmPushSender::class.java)
    private val url get() = "$baseUrl/v1/projects/$projectId/messages:send"

    override suspend fun send(token: String, message: PushMessage): PushResult {
        val body = requestJson(token, message).toString()
        var retriedTransient = false
        var retriedAuth = false
        var forceRefresh = false
        while (true) {
            val accessToken = try {
                tokens.accessToken(forceRefresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("FCM: could not get an access token: {}", e.toString())
                return PushResult.Failed("access token: ${e.message ?: e.javaClass.simpleName}", retryable = true)
            }
            val headers = mapOf(
                "Authorization" to "Bearer $accessToken",
                "Content-Type" to "application/json; charset=UTF-8",
            )
            val reply = try {
                transport.post(url, headers, body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("FCM: request failed: {}", e.toString())
                if (!retriedTransient) {
                    retriedTransient = true
                    delay(retryDelayMs)
                    continue
                }
                return PushResult.Failed("network: ${e.message ?: e.javaClass.simpleName}", retryable = true)
            }
            when (val outcome = classify(reply)) {
                is Outcome.Done -> return outcome.result
                is Outcome.Unauthenticated -> {
                    if (retriedAuth) return PushResult.Failed(outcome.reason, retryable = false)
                    retriedAuth = true
                    forceRefresh = true
                }
                is Outcome.Transient -> {
                    if (retriedTransient) return PushResult.Failed(outcome.reason, retryable = true)
                    retriedTransient = true
                    delay(retryAfterMs(reply) ?: retryDelayMs)
                }
            }
        }
    }

    private fun retryAfterMs(reply: HttpReply): Long? =
        reply.headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.trim()?.toLongOrNull()?.takeIf { it in 0..MAX_RETRY_AFTER_S }?.times(1000L)

    private sealed interface Outcome {
        data class Done(val result: PushResult) : Outcome
        data class Transient(val reason: String) : Outcome
        data class Unauthenticated(val reason: String) : Outcome
    }

    private fun classify(reply: HttpReply): Outcome {
        if (reply.status in 200..299) {
            val name = parse(reply.body)?.string("name") ?: ""
            return Outcome.Done(PushResult.Sent(name))
        }
        val error = parse(reply.body)?.get("error") as? JsonObject
        val status = error?.string("status")
        val message = error?.string("message").orEmpty()
        val errorCodes = (error?.get("details") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("errorCode") }
        val fieldViolations = (error?.get("details") as? JsonArray).orEmpty()
            .flatMap { ((it as? JsonObject)?.get("fieldViolations") as? JsonArray).orEmpty() }
            .mapNotNull { (it as? JsonObject)?.string("field") }
        val reason = "HTTP ${reply.status} ${(errorCodes + listOfNotNull(status)).distinct().joinToString("/")}".trim() +
            (if (message.isNotBlank()) ": $message" else "")
        logger.info("FCM rejected message: {}", reason)

        val invalidArgument = status == "INVALID_ARGUMENT" || "INVALID_ARGUMENT" in errorCodes
        val aboutToken = message.contains("token", ignoreCase = true) || fieldViolations.any { it.contains("token", ignoreCase = true) }
        return when {
            "UNREGISTERED" in errorCodes || reply.status == 404 -> Outcome.Done(PushResult.InvalidToken)
            invalidArgument && aboutToken -> Outcome.Done(PushResult.InvalidToken)
            reply.status == 401 -> Outcome.Unauthenticated(reason)
            reply.status == 429 || reply.status in 500..599 -> Outcome.Transient(reason)
            else -> Outcome.Done(PushResult.Failed(reason, retryable = false))
        }
    }

    private fun parse(body: String): JsonObject? = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    companion object {
        private const val MAX_RETRY_AFTER_S = 10L

        /** Android notification channel for a [PushKeys.KIND] value. */
        fun channelFor(kind: String?): String = when (kind) {
            NotificationKind.DELAY, NotificationKind.RECOVERED -> "delays"
            NotificationKind.CANCEL -> "cancellations"
            NotificationKind.ALERT -> "alerts"
            else -> "general"
        }

        /** The FCM HTTP v1 request body for [message] to [token]. */
        fun requestJson(token: String, message: PushMessage): JsonObject = buildJsonObject {
            putJsonObject("message") {
                put("token", token)
                putJsonObject("notification") {
                    put("title", message.title)
                    put("body", message.body)
                }
                putJsonObject("data") { message.data.forEach { (key, value) -> put(key, value) } }
                putJsonObject("android") {
                    put("priority", if (message.highPriority) "HIGH" else "NORMAL")
                    put("collapse_key", message.collapseKey)
                    put("ttl", "${maxOf(message.ttlSeconds, 0L)}s")
                    putJsonObject("notification") {
                        put("channel_id", channelFor(message.data[PushKeys.KIND]))
                        put("tag", message.collapseKey)
                    }
                }
            }
        }
    }
}
