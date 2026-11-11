package app.transittracker.server.pipeline

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class HttpGtfsSourceTest {
    private val requests = AtomicInteger()
    private var statuses = ArrayDeque<Int>()
    private val methods = java.util.Collections.synchronizedList(ArrayList<String>())
    private var lastModified = "Wed, 11 Nov 2026 09:52:40 GMT"
    private var headStatus = 200

    /** Like TfNSW's gateway: Last-Modified on every answer, and 502 (not 304) to any conditional GET. */
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            methods += ex.requestMethod
            ex.responseHeaders.add("Last-Modified", lastModified)
            if (ex.requestMethod == "HEAD") {
                ex.sendResponseHeaders(headStatus, -1)
                ex.close()
                return@createContext
            }
            requests.incrementAndGet()
            val conditional = ex.requestHeaders.containsKey("If-Modified-Since") || ex.requestHeaders.containsKey("If-None-Match")
            val status = if (conditional) 502 else statuses.removeFirstOrNull() ?: 200
            val body = if (status == 200) "zip bytes".toByteArray() else "Bad Gateway".toByteArray()
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }
    private val target = Files.createTempFile("gtfs", ".zip")
    private val source = HttpGtfsSource(
        "http://127.0.0.1:${server.address.port}", "key",
        retryDelays = listOf(10.milliseconds, 10.milliseconds),
    )

    @AfterTest
    fun stop() {
        server.stop(0)
        Files.deleteIfExists(target)
    }

    @Test
    fun aTemporaryFailureIsTriedAgain() = runTest {
        statuses = ArrayDeque(listOf(502, 503))
        assertIs<FetchResult.Downloaded>(source.fetch("/v1/gtfs/schedule/sydneytrains", null, target))
        assertEquals("zip bytes", Files.readString(target))
        assertEquals(3, requests.get())
    }

    @Test
    fun itGivesUpAfterTheLastRetry() = runTest {
        statuses = ArrayDeque(listOf(502, 502, 502))
        val e = assertFailsWith<HttpStatusException> { source.fetch("/x", null, target) }
        assertEquals(502, e.status)
        assertEquals(3, requests.get())
    }

    @Test
    fun theErrorSaysWhatTheGatewayAnswered() = runTest {
        statuses = ArrayDeque(listOf(502, 502, 502))
        val e = assertFailsWith<HttpStatusException> { source.fetch("/x", null, target) }
        assertTrue("Bad Gateway" in e.message.orEmpty(), e.message)
        assertTrue("key" !in e.message.orEmpty().replace("Bad Gateway", ""), "the API key never appears")
    }

    @Test
    fun anUnchangedBundleIsNotDownloadedAgain() = runTest {
        val result = source.fetch("/v1/gtfs/schedule/buses", CacheValidators(null, lastModified), target)
        assertIs<FetchResult.NotModified>(result)
        assertEquals(listOf("HEAD"), methods.toList())
    }

    @Test
    fun aChangedBundleIsDownloadedWithAPlainGet() = runTest {
        val result = source.fetch("/v1/gtfs/schedule/buses", CacheValidators(null, "Tue, 10 Nov 2026 09:52:40 GMT"), target)
        assertIs<FetchResult.Downloaded>(result)
        assertEquals(lastModified, result.validators.lastModified)
        assertEquals(listOf("HEAD", "GET"), methods.toList())
        assertEquals(1, requests.get(), "no conditional GET, so no 502 and no retry")
    }

    @Test
    fun aFailedHeadStillDownloads() = runTest {
        headStatus = 502
        assertIs<FetchResult.Downloaded>(source.fetch("/x", CacheValidators(null, lastModified), target))
        assertEquals(listOf("HEAD", "GET"), methods.toList())
    }

    @Test
    fun aRefusalIsNotRetried() = runTest {
        statuses = ArrayDeque(listOf(401))
        assertFailsWith<HttpStatusException> { source.fetch("/x", null, target) }
        assertEquals(1, requests.get())
    }
}
