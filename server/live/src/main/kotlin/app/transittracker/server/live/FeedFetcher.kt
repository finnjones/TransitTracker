package app.transittracker.server.live

import app.transittracker.server.spi.FeedDefinition
import app.transittracker.server.spi.ServerConfig
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/** The three GTFS-R feed kinds a [FeedDefinition] can publish. [id] is the name used in status output. */
enum class FeedKind(val id: String) {
    TRIP_UPDATES("tripupdates"),
    VEHICLES("vehicles"),
    ALERTS("alerts"),
    ;

    /** API path of this kind for [feed], or null if the feed does not publish it. */
    fun pathOf(feed: FeedDefinition): String? = when (this) {
        TRIP_UPDATES -> feed.tripUpdatesPath
        VEHICLES -> feed.vehiclePositionsPath
        ALERTS -> feed.alertsPath
    }

    companion object {
        fun fromId(id: String): FeedKind? = entries.firstOrNull { it.id == id }
    }
}

/** Outcome of one attempt to download a feed. */
sealed interface FetchResult {
    /** Raw GTFS-R protobuf bytes. */
    class Success(val bytes: ByteArray) : FetchResult

    /** The server answered with a non-200 status. */
    data class HttpError(val status: Int, val detail: String = "") : FetchResult

    /** Connection failure or timeout; no HTTP status. */
    data class NetworkError(val detail: String, val timeout: Boolean = false) : FetchResult

    /** The fetcher cannot serve this feed at all (no API key, no path, no recording). Never retried. */
    data class NotConfigured(val reason: String) : FetchResult
}

/**
 * Downloads raw GTFS-R feeds. Implementations do one attempt per call: retries, backoff, rate
 * limiting and the daily budget are applied by [LiveDataImpl].
 */
interface FeedFetcher {
    /**
     * True if calls go to TfNSW: they need an API key, count towards the daily budget and pass
     * through the rate limiter. Replay fetchers return false.
     */
    val isRemote: Boolean get() = true

    suspend fun fetch(feed: FeedDefinition, kind: FeedKind): FetchResult
}

/**
 * Fetches feeds from the TfNSW Open Data Hub with `Authorization: apikey <key>` and a 10 s timeout.
 * With no API key it never makes a request and answers [FetchResult.NotConfigured].
 */
class HttpFeedFetcher(
    private val baseUrl: String,
    private val apiKey: String?,
    private val timeout: Duration = Duration.ofSeconds(10),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : FeedFetcher {

    /** Uses [ServerConfig.tfnswBaseUrl] and [ServerConfig.tfnswApiKey]. */
    constructor(config: ServerConfig) : this(config.tfnswBaseUrl, config.tfnswApiKey)

    override suspend fun fetch(feed: FeedDefinition, kind: FeedKind): FetchResult {
        if (apiKey.isNullOrBlank()) return FetchResult.NotConfigured("TFNSW_API_KEY is not set")
        val path = kind.pathOf(feed) ?: return FetchResult.NotConfigured("feed ${feed.id} has no ${kind.id} path")
        val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path))
            .timeout(timeout)
            .header("Authorization", "apikey $apiKey")
            .header("Accept", "application/x-google-protobuf, application/octet-stream")
            .GET()
            .build()
        return try {
            val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).await()
            if (response.statusCode() == 200) {
                FetchResult.Success(response.body())
            } else {
                val detail = response.body().take(200).toByteArray().toString(Charsets.UTF_8)
                FetchResult.HttpError(response.statusCode(), detail)
            }
        } catch (e: HttpTimeoutException) {
            FetchResult.NetworkError("timeout: ${e.message}", timeout = true)
        } catch (e: IOException) {
            FetchResult.NetworkError(e.toString())
        } catch (e: java.util.concurrent.CompletionException) {
            val cause = e.cause
            if (cause is HttpTimeoutException) {
                FetchResult.NetworkError("timeout: ${cause.message}", timeout = true)
            } else {
                log.debug("Fetch of {} {} failed", feed.id, kind.id, e)
                FetchResult.NetworkError(cause?.toString() ?: e.toString())
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(HttpFeedFetcher::class.java)
    }
}
