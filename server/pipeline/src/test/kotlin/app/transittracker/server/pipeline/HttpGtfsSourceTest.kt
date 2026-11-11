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
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            requests.incrementAndGet()
            val status = statuses.removeFirstOrNull() ?: 200
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
    fun aRefusalIsNotRetried() = runTest {
        statuses = ArrayDeque(listOf(401))
        assertFailsWith<HttpStatusException> { source.fetch("/x", null, target) }
        assertEquals(1, requests.get())
    }
}
