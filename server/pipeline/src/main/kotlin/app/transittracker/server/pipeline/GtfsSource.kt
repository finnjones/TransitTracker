package app.transittracker.server.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/** HTTP cache validators remembered between fetches of one GTFS bundle. */
data class CacheValidators(val etag: String?, val lastModified: String?)

/** Outcome of [GtfsSource.fetch]. */
sealed interface FetchResult {
    /** The server says the bundle has not changed since [CacheValidators] were issued; nothing was written. */
    data object NotModified : FetchResult

    /** The bundle was written to the target file. */
    data class Downloaded(val validators: CacheValidators) : FetchResult
}

/** Where GTFS static bundles come from. The production one is [HttpGtfsSource]; tests use fakes. */
interface GtfsSource {
    /**
     * Fetches the bundle at [path] (relative to the source's base URL) into [target], sending
     * [validators] as a conditional request when given. Throws [IOException] on failure.
     */
    suspend fun fetch(path: String, validators: CacheValidators?, target: Path): FetchResult
}

/**
 * Fetches bundles from the TfNSW Open Data Hub with `Authorization: apikey <key>` and
 * conditional requests (`If-None-Match` / `If-Modified-Since`). The key is never logged.
 *
 * A temporary failure (`5xx`, `429`, or the connection dropping) is tried again after each of
 * [retryDelays]: TfNSW's gateway sometimes answers `502` to one request and serves the next.
 * Other statuses (`401`, `403`, `404`) fail at once.
 */
class HttpGtfsSource(
    private val baseUrl: String,
    private val apiKey: String,
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val requestTimeout: Duration = Duration.ofMinutes(10),
    private val retryDelays: List<kotlin.time.Duration> = listOf(10.seconds, 30.seconds),
) : GtfsSource {
    private val log = LoggerFactory.getLogger(HttpGtfsSource::class.java)

    override suspend fun fetch(path: String, validators: CacheValidators?, target: Path): FetchResult {
        var attempt = 0
        while (true) {
            try {
                return fetchOnce(path, validators, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                val wait = retryDelays.getOrNull(attempt++)
                if (wait == null || (e is HttpStatusException && !e.temporary)) throw e
                log.warn("Fetching GTFS {} failed ({}); trying again in {}", path, e.message, wait)
                delay(wait)
            }
        }
    }

    private suspend fun fetchOnce(path: String, validators: CacheValidators?, target: Path): FetchResult {
        val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/" + path.trimStart('/')))
            .timeout(requestTimeout)
            .header("Authorization", "apikey $apiKey")
            .header("Accept", "application/zip, application/octet-stream, */*")
            .apply {
                validators?.etag?.let { header("If-None-Match", it) }
                validators?.lastModified?.let { header("If-Modified-Since", it) }
            }
            .GET()
            .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofFile(target)).await()
        return when (val status = response.statusCode()) {
            304 -> {
                Files.deleteIfExists(target)
                FetchResult.NotModified
            }
            in 200..299 -> FetchResult.Downloaded(
                CacheValidators(
                    etag = response.headers().firstValue("ETag").orElse(null),
                    lastModified = response.headers().firstValue("Last-Modified").orElse(null),
                ),
            )
            else -> {
                Files.deleteIfExists(target)
                throw HttpStatusException(status, "HTTP $status fetching GTFS $path")
            }
        }
    }

    override fun toString(): String = "HttpGtfsSource($baseUrl)"
}

/** A non-success HTTP status; [temporary] for `5xx` and `429`, which are worth trying again. */
class HttpStatusException(val status: Int, message: String) : IOException(message) {
    val temporary: Boolean get() = status >= 500 || status == 429
}
