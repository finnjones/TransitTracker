package app.transittracker.server.planner

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The TfNSW call itself, against a local HTTP server standing in for api.transport.nsw.gov.au. */
class HttpPlannerFetcherTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val seen = AtomicReference<Map<String, String?>>()
    @Volatile private var status = 200

    init {
        server.createContext("/") { exchange ->
            seen.set(
                mapOf(
                    "path" to exchange.requestURI.rawPath,
                    "query" to exchange.requestURI.rawQuery,
                    "authorization" to exchange.requestHeaders.getFirst("Authorization"),
                    "cookie" to exchange.requestHeaders.getFirst("Cookie"),
                    "forwarded" to exchange.requestHeaders.getFirst("X-Forwarded-For"),
                ),
            )
            val body = """{"version":"10.6.21.17","journeys":[]}""".toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() = server.stop(0)

    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Test
    fun `sends the key as apikey and the parameters encoded`() = runBlocking {
        val fetcher = HttpPlannerFetcher(base, "test-key")
        val result = fetcher.fetch(PlannerEndpoint.TRIP, listOf("name_origin" to "151.206840:-33.883970:EPSG:4326", "name_sf" to "Smith St"))
        assertIs<PlannerFetch.Success>(result)
        val request = seen.get()
        assertEquals("/v1/tp/trip", request["path"])
        assertEquals("name_origin=151.206840%3A-33.883970%3AEPSG%3A4326&name_sf=Smith%20St", request["query"])
        assertEquals("apikey test-key", request["authorization"])
        assertNull(request["cookie"])
        assertNull(request["forwarded"])
    }

    @Test
    fun `non-200 answers are http errors and no key means no call`() = runBlocking {
        status = 429
        assertEquals(429, (HttpPlannerFetcher(base, "test-key").fetch(PlannerEndpoint.STOP_FINDER, emptyList()) as PlannerFetch.HttpError).status)
        seen.set(null)
        assertIs<PlannerFetch.NotConfigured>(HttpPlannerFetcher(base, null).fetch(PlannerEndpoint.STOP_FINDER, emptyList()))
        assertNull(seen.get())
    }
}
