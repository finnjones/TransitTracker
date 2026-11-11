package app.transittracker.server.planner

import app.transittracker.server.spi.ServerConfig
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletionException

/**
 * The Trip Planner endpoints tt-server uses, under `/v1/tp/` (paths from the Trip Planner
 * Swagger `tripplanner_v1_swag_efa11_20251002.yml`, `basePath: /v1/tp`).
 */
enum class PlannerEndpoint(val path: String, val fileStem: String) {
    STOP_FINDER("/v1/tp/stop_finder", "stop_finder"),
    TRIP("/v1/tp/trip", "trip"),
    COORD("/v1/tp/coord", "coord"),
}

/** Outcome of one Trip Planner call. */
sealed interface PlannerFetch {
    /** The raw JSON body of a 200 answer. */
    class Success(val body: String) : PlannerFetch

    /** TfNSW answered with a non-200 status. */
    data class HttpError(val status: Int, val detail: String = "") : PlannerFetch

    /** Connection failure or timeout; no HTTP status. */
    data class NetworkError(val detail: String, val timeout: Boolean = false) : PlannerFetch

    /** This fetcher cannot answer at all (no API key, no replay file). */
    data class NotConfigured(val reason: String) : PlannerFetch
}

/**
 * Makes one Trip Planner call. Implementations do exactly one attempt: the quota, backoff and
 * caching are applied by [TfnswPlanner]. Keeping the HTTP call behind this interface lets tests and
 * replay ([ReplayPlannerFetcher]) stand in for TfNSW.
 */
interface PlannerFetcher {
    /** True if calls go to TfNSW (they need the key and count towards the shared daily budget). */
    val isRemote: Boolean get() = true

    /** True if this fetcher can answer at all (a key, or replay files). */
    val configured: Boolean

    /** Calls [endpoint] with the query [params] (in order, unencoded). */
    suspend fun fetch(endpoint: PlannerEndpoint, params: List<Pair<String, String>>): PlannerFetch
}

/**
 * Calls TfNSW's Trip Planner with `Authorization: apikey <key>` (the Swagger's `securityDefinitions`:
 * "Expected Format: apikey [TOKEN]") and a 10 s timeout, like the live feeds' `HttpFeedFetcher`.
 * Sends nothing that identifies a device: no cookies, no forwarded headers, a generic user agent.
 * With no API key it never makes a request.
 */
class HttpPlannerFetcher(
    private val baseUrl: String,
    private val apiKey: String?,
    private val timeout: Duration = Duration.ofSeconds(10),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : PlannerFetcher {

    /** Uses [ServerConfig.tfnswBaseUrl] and [ServerConfig.tfnswApiKey]. */
    constructor(config: ServerConfig) : this(config.tfnswBaseUrl, config.tfnswApiKey)

    override val configured: Boolean get() = !apiKey.isNullOrBlank()

    override suspend fun fetch(endpoint: PlannerEndpoint, params: List<Pair<String, String>>): PlannerFetch {
        if (apiKey.isNullOrBlank()) return PlannerFetch.NotConfigured("TFNSW_API_KEY is not set")
        val request = HttpRequest.newBuilder(URI.create(url(baseUrl, endpoint, params)))
            .timeout(timeout)
            .header("Authorization", "apikey $apiKey")
            .header("Accept", "application/json")
            .header("User-Agent", "tt-server")
            .GET()
            .build()
        return try {
            val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)).await()
            if (response.statusCode() == 200) {
                PlannerFetch.Success(response.body())
            } else {
                PlannerFetch.HttpError(response.statusCode(), response.body().take(200))
            }
        } catch (e: HttpTimeoutException) {
            PlannerFetch.NetworkError("timeout", timeout = true)
        } catch (e: IOException) {
            PlannerFetch.NetworkError(e.javaClass.simpleName)
        } catch (e: CompletionException) {
            val cause = e.cause
            if (cause is HttpTimeoutException) {
                PlannerFetch.NetworkError("timeout", timeout = true)
            } else {
                // The URL holds the user's places: never log the exception message at INFO or above.
                log.debug("Trip planner call to {} failed: {}", endpoint.path, cause?.javaClass?.simpleName)
                PlannerFetch.NetworkError(cause?.javaClass?.simpleName ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(HttpPlannerFetcher::class.java)

        /** `<base><path>?a=1&b=2`, each name and value form-encoded. */
        fun url(baseUrl: String, endpoint: PlannerEndpoint, params: List<Pair<String, String>>): String =
            baseUrl.trimEnd('/') + endpoint.path + "?" + params.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v, Charsets.UTF_8).replace("+", "%20")
            }
    }
}
