package app.transittracker.server.pipeline

import kotlinx.coroutines.future.await
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

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
 */
class HttpGtfsSource(
    private val baseUrl: String,
    private val apiKey: String,
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val requestTimeout: Duration = Duration.ofMinutes(10),
) : GtfsSource {
    override suspend fun fetch(path: String, validators: CacheValidators?, target: Path): FetchResult {
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
                throw IOException("HTTP $status fetching GTFS $path")
            }
        }
    }

    override fun toString(): String = "HttpGtfsSource($baseUrl)"
}
