package app.transittracker.server.watch

import app.transittracker.model.PushKeys
import app.transittracker.server.spi.PushMessage
import app.transittracker.server.spi.PushResult
import app.transittracker.server.spi.ServerConfig
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Paths
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FcmPushSenderTest {

    private val message = PushMessage(
        title = "Work",
        body = "7:42 to Central is running 6 min late (now 7:48)",
        data = mapOf(PushKeys.KIND to "delay", PushKeys.TRIP_ID to "T1-0742", PushKeys.SERVICE_DATE to "20261111", PushKeys.WATCH_ID to "w1"),
        collapseKey = "T1-0742:20261111",
        ttlSeconds = 720,
    )

    private class Call(val url: String, val headers: Map<String, String>, val body: String)

    /** Replies in order; a null entry throws an IOException. */
    private class FakeTransport(vararg replies: HttpReply?) : FcmTransport {
        val queue = ArrayDeque(replies.toList())
        val calls = ArrayList<Call>()
        override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpReply {
            calls += Call(url, headers, body)
            return queue.removeFirst() ?: throw IOException("connection reset")
        }
    }

    private class FakeTokens : AccessTokenSource {
        val requests = ArrayList<Boolean>()
        override suspend fun accessToken(forceRefresh: Boolean): String {
            requests += forceRefresh
            return if (forceRefresh) "fresh-access" else "cached-access"
        }
    }

    private fun sender(transport: FcmTransport, tokens: AccessTokenSource = FakeTokens()) =
        FcmPushSender("my-project", tokens, transport, retryDelayMs = 10)

    private fun error(status: Int, code: String, message: String, fcmErrorCode: String? = null) = HttpReply(
        status,
        """{"error":{"code":$status,"message":"$message","status":"$code","details":[""" +
            (fcmErrorCode?.let { """{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"$it"}""" } ?: "") +
            "]}}",
    )

    private val ok = HttpReply(200, """{"name":"projects/my-project/messages/0:123"}""")

    @Test
    fun `request has the FCM v1 shape`() = runTest {
        val transport = FakeTransport(ok)
        val result = sender(transport).send("device-token", message)
        assertEquals(PushResult.Sent("projects/my-project/messages/0:123"), result)

        val call = transport.calls.single()
        assertEquals("https://fcm.googleapis.com/v1/projects/my-project/messages:send", call.url)
        assertEquals("Bearer cached-access", call.headers["Authorization"])
        val expected = Json.parseToJsonElement(
            """
            {"message":{
              "token":"device-token",
              "notification":{"title":"Work","body":"7:42 to Central is running 6 min late (now 7:48)"},
              "data":{"kind":"delay","tripId":"T1-0742","serviceDate":"20261111","watchId":"w1"},
              "android":{
                "priority":"HIGH",
                "collapse_key":"T1-0742:20261111",
                "ttl":"720s",
                "notification":{"channel_id":"delays","tag":"T1-0742:20261111"}
              }
            }}
            """.trimIndent(),
        )
        assertEquals(expected, Json.parseToJsonElement(call.body))
    }

    @Test
    fun `channel depends on kind`() {
        fun channel(kind: String) = FcmPushSender.requestJson("t", message.copy(data = mapOf(PushKeys.KIND to kind)))
            .let { it["message"]!!.jsonObject["android"]!!.jsonObject["notification"]!!.jsonObject["channel_id"]!!.jsonPrimitive.content }
        assertEquals("delays", channel("delay"))
        assertEquals("delays", channel("recovered"))
        assertEquals("cancellations", channel("cancel"))
        assertEquals("alerts", channel("alert"))
        assertEquals("general", channel("platform"))
        assertEquals("general", channel("test"))
        val normal = FcmPushSender.requestJson("t", message.copy(highPriority = false))
        assertEquals("NORMAL", (normal["message"] as JsonObject)["android"]!!.jsonObject["priority"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unregistered and not found tokens are invalid`() = runTest {
        assertEquals(PushResult.InvalidToken, sender(FakeTransport(error(404, "NOT_FOUND", "Requested entity was not found.", "UNREGISTERED"))).send("t", message))
        assertEquals(PushResult.InvalidToken, sender(FakeTransport(HttpReply(404, ""))).send("t", message))
        assertEquals(PushResult.InvalidToken, sender(FakeTransport(error(400, "INVALID_ARGUMENT", "x", "UNREGISTERED"))).send("t", message))
    }

    @Test
    fun `invalid argument about the token is an invalid token, otherwise permanent failure`() = runTest {
        val badToken = error(400, "INVALID_ARGUMENT", "The registration token is not a valid FCM registration token", "INVALID_ARGUMENT")
        assertEquals(PushResult.InvalidToken, sender(FakeTransport(badToken)).send("t", message))

        val transport = FakeTransport(error(400, "INVALID_ARGUMENT", "Invalid value at 'message.android.ttl'", "INVALID_ARGUMENT"))
        val result = sender(transport).send("t", message)
        assertIs<PushResult.Failed>(result)
        assertEquals(false, result.retryable)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `server errors and throttling retry once then fail retryably`() = runTest {
        val recovered = FakeTransport(error(503, "UNAVAILABLE", "busy"), ok)
        assertIs<PushResult.Sent>(sender(recovered).send("t", message))
        assertEquals(2, recovered.calls.size)

        val throttled = FakeTransport(error(429, "RESOURCE_EXHAUSTED", "quota", "QUOTA_EXCEEDED"), error(429, "RESOURCE_EXHAUSTED", "quota"))
        val result = sender(throttled).send("t", message)
        assertIs<PushResult.Failed>(result)
        assertTrue(result.retryable)
        assertEquals(2, throttled.calls.size)

        val network = FakeTransport(null, null)
        val failed = sender(network).send("t", message)
        assertIs<PushResult.Failed>(failed)
        assertTrue(failed.retryable)
        assertEquals(2, network.calls.size)
    }

    @Test
    fun `other errors fail permanently without retry`() = runTest {
        val transport = FakeTransport(error(403, "PERMISSION_DENIED", "SenderId mismatch", "SENDER_ID_MISMATCH"))
        val result = sender(transport).send("t", message)
        assertIs<PushResult.Failed>(result)
        assertEquals(false, result.retryable)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `unauthenticated refreshes the access token once`() = runTest {
        val tokens = FakeTokens()
        val transport = FakeTransport(error(401, "UNAUTHENTICATED", "expired"), ok)
        assertIs<PushResult.Sent>(sender(transport, tokens).send("t", message))
        assertEquals(listOf(false, true), tokens.requests)
        assertEquals("Bearer fresh-access", transport.calls[1].headers["Authorization"])

        val stillBad = FakeTransport(error(401, "UNAUTHENTICATED", "no"), error(401, "UNAUTHENTICATED", "no"))
        val result = sender(stillBad).send("t", message)
        assertIs<PushResult.Failed>(result)
        assertEquals(false, result.retryable)
    }

    @Test
    fun `access token failure is a retryable failure`() = runTest {
        val transport = FakeTransport(ok)
        val result = sender(transport) { error("no network") }.send("t", message)
        assertIs<PushResult.Failed>(result)
        assertTrue(result.retryable)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `google token source returns the cached token`() = runTest {
        val credentials = GoogleCredentials.create(AccessToken("abc", Date(System.currentTimeMillis() + 3_600_000)))
        assertEquals("abc", GoogleAccessTokenSource(credentials).accessToken(forceRefresh = false))
    }

    @Test
    fun `factory falls back to logging without Firebase config`() = runTest {
        assertIs<LoggingPushSender>(PushSenders.fromConfig(ServerConfig()))
        assertIs<LoggingPushSender>(PushSenders.fromConfig(ServerConfig(firebaseServiceAccountPath = Paths.get("/nonexistent/sa.json"))))
        assertIs<PushResult.Sent>(LoggingPushSender().send("t", message))
    }
}
