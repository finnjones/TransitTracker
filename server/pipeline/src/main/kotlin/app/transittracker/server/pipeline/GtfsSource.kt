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
 * Fetches bundles from the TfNSW Open Data Hub with `Authorization: apikey <key>`. The key is
 * never logged.
 *
 * No conditional GET: TfNSW's gateway answers `502` instead of `304` to `If-Modified-Since` or
 * `If-None-Match` when the bundle has not changed (checked on 11 Nov 2026, every bundle, every
 * time). So with [validators][fetch] a `HEAD` asks for the bundle's `Last-Modified` / `ETag`
 * first: the same as remembered is [FetchResult.NotModified]; anything else, or a failed `HEAD`,
 * downloads the bundle with a plain `GET`.
 *
 * A temporary failure (`5xx`, `429`, or the connection dropping) is tried again after each of
 * [retryDelays]: TfNSW's gateway sometimes answers `502` to one request and serves the next.
 * Other statuses (`401`, `403`, `404`) fail at once.
 */
class HttpGtfsSource(
    private val baseUrl: String,
    private val apiKey: String,
    private val client: HttpClient = HttpClient.newBuilder()
        // HTTP/1.1 like curl: the JDK's HTTP/2 client has been answered 502 by TfNSW's gateway for
        // the large timetable bundles while curl on the same machine got 200.
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val requestTimeout: Duration = Duration.ofMinutes(10),
    private val retryDelays: List<kotlin.time.Duration> = listOf(10.seconds, 30.seconds),
) : GtfsSource {
    private val log = LoggerFactory.getLogger(HttpGtfsSource::class.java)

    override suspend fun fetch(path: String, validators: CacheValidators?, target: Path): FetchResult {
        if (validators != null && unchanged(path, validators)) return FetchResult.NotModified
        var attempt = 0
        while (true) {
            try {
                return fetchOnce(path, target)
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

    /**
     * True when a `HEAD` shows the bundle still carries [validators] (its `ETag`, else its
     * `Last-Modified`). False when it differs, the server gives neither, or the `HEAD` fails.
     */
    private suspend fun unchanged(path: String, validators: CacheValidators): Boolean {
        val response = try {
            client.sendAsync(
                request(path).timeout(Duration.ofSeconds(60)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding(),
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            log.warn("HEAD of GTFS {} failed ({}); downloading it", path, e.message)
            return false
        }
        if (response.statusCode() !in 200..299) {
            log.warn("HEAD of GTFS {} answered HTTP {}; downloading it", path, response.statusCode())
            return false
        }
        val etag = response.headers().firstValue("ETag").orElse(null)
        val lastModified = response.headers().firstValue("Last-Modified").orElse(null)
        return when {
            etag != null && validators.etag != null -> etag == validators.etag
            lastModified != null && validators.lastModified != null -> lastModified == validators.lastModified
            else -> false
        }
    }

    private fun request(path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/" + path.trimStart('/')))
            .header("Authorization", "apikey $apiKey")
            .header("User-Agent", "TransitTracker-server")

    private suspend fun fetchOnce(path: String, target: Path): FetchResult {
        val request = request(path)
            .timeout(requestTimeout)
            .header("Accept", "application/zip, application/octet-stream, */*")
            .GET()
            .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofFile(target)).await()
        return when (val status = response.statusCode()) {
            in 200..299 -> FetchResult.Downloaded(
                CacheValidators(
                    etag = response.headers().firstValue("ETag").orElse(null),
                    lastModified = response.headers().firstValue("Last-Modified").orElse(null),
                ),
            )
            else -> {
                val detail = errorDetail(response.headers(), target)
                Files.deleteIfExists(target)
                throw HttpStatusException(status, "HTTP $status fetching GTFS $path$detail")
            }
        }
    }

    /** What the gateway said (its name and the start of its body), to tell a 502 from TfNSW from one in between. */
    private fun errorDetail(headers: java.net.http.HttpHeaders, target: Path): String {
        val body = runCatching {
            Files.newInputStream(target).use { it.readNBytes(200) }.toString(Charsets.UTF_8)
                .filter { !it.isISOControl() || it == ' ' }.trim()
        }.getOrDefault("")
        val server = headers.firstValue("Server").orElse(null)
        val parts = listOfNotNull(server?.let { "server=$it" }, body.takeIf { it.isNotEmpty() }?.let { "body=\"$it\"" })
        return if (parts.isEmpty()) "" else " (${parts.joinToString(", ")})"
    }

    override fun toString(): String = "HttpGtfsSource($baseUrl)"
}

/** A non-success HTTP status; [temporary] for `5xx` and `429`, which are worth trying again. */
class HttpStatusException(val status: Int, message: String) : IOException(message) {
    val temporary: Boolean get() = status >= 500 || status == 429
}
