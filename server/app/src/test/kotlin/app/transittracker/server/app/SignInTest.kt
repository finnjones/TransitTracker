package app.transittracker.server.app

import app.transittracker.model.JoinResponse
import app.transittracker.model.MediaTypes
import app.transittracker.server.spi.ServerConfig
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Joining by signing in (Firebase ID tokens), the allow list and its tt-admin commands. */
class SignInTest {
    private val dir: Path = createTempDirectory("tt-signin-test")
    private val clock = MutableClock()
    private val config = ServerConfig(dataDir = dir)
    private val db = Database.open(config.dbPath)
    private val devices = SqliteDeviceStore(db, clock)

    /** Tokens are "uid|email|verified"; anything else is refused. */
    private val verifier = IdTokenVerifier { token ->
        token.split('|').takeIf { it.size == 3 }?.let { (uid, email, verified) ->
            VerifiedUser(uid, email.ifEmpty { null }, verified.toBoolean())
        }
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun deps(idTokens: IdTokenVerifier? = verifier) = ApiDependencies(
        config, devices, SqliteNotificationLog(db), FakeTimetableRepository(FakeTimetable(emptyMap()), emptyList(), emptyMap()),
        FakeLiveData(clock), FakeWatchService(), clock, idTokens = idTokens,
    )

    private suspend fun HttpResponse.problemCode(): String {
        assertEquals(MediaTypes.PROBLEM_JSON, contentType()?.withoutParameters().toString())
        return Codecs.json.parseToJsonElement(bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
    }

    @Test
    fun `the Firebase verifier refuses tokens that are not for this project`() {
        val firebase = FirebaseIdTokenVerifier("transittracker-test")
        listOf("", "garbage", "a.b.c", "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0.").forEach { assertNull(firebase.verify(it), it) }
    }

    @Test
    fun `allow list patterns`() {
        assertEquals("sam@example.com", AllowedUsers.normalize("  Sam@Example.COM "))
        assertEquals("*@example.com", AllowedUsers.normalize("*@example.com"))
        assertEquals("*", AllowedUsers.normalize("*"))
        assertNull(AllowedUsers.normalize("sam"))
        assertNull(AllowedUsers.normalize("*@*"))
        assertNull(AllowedUsers.normalize("a@b@c.com"))

        assertTrue(AllowedUsers.matches("sam@example.com", "sam@example.com", emailVerified = true))
        assertFalse(AllowedUsers.matches("sam@example.com", "sam@example.com", emailVerified = false))
        assertTrue(AllowedUsers.matches("*@example.com", "kim@example.com", emailVerified = true))
        assertFalse(AllowedUsers.matches("*@example.com", "kim@badexample.com", emailVerified = true))
        assertFalse(AllowedUsers.matches("*@example.com", null, emailVerified = true))
        assertTrue(AllowedUsers.matches("*", null, emailVerified = false))
    }

    @Test
    fun `join by signing in needs an allowed account and returns a working device token`() = testApplication {
        application { transitTrackerApi(deps()) }
        suspend fun join(token: String) = client.post("/v1/join") {
            contentType(ContentType.Application.Json)
            setBody("""{"idToken":"$token","platform":"android","appVersion":"2.0"}""")
        }

        join("u1|sam@example.com|true").let {
            assertEquals(HttpStatusCode.Forbidden, it.status)
            assertEquals("not_allowed", it.problemCode())
        }
        devices.allowUser("Sam@example.com")
        val response = join("u1|sam@example.com|true")
        assertEquals(HttpStatusCode.OK, response.status)
        val joined = Codecs.json.decodeFromString(JoinResponse.serializer(), response.bodyAsText())
        val device = assertNotNull(devices.get(joined.deviceId))
        assertEquals("sam@example.com", device.userEmail)
        assertEquals("2.0", device.appVersion)
        assertEquals(HttpStatusCode.OK, client.get("/v1/config") { bearerAuth(joined.deviceToken) }.status)

        // Unverified email, or a token that does not check out.
        assertEquals("not_allowed", join("u2|sam@example.com|false").problemCode())
        join("garbage").let {
            assertEquals(HttpStatusCode.Unauthorized, it.status)
            assertEquals("sign_in_invalid", it.problemCode())
        }
    }

    @Test
    fun `a server without a Firebase project refuses sign-in but still takes invite codes`() = testApplication {
        application { transitTrackerApi(deps(idTokens = null)) }
        client.post("/v1/join") {
            contentType(ContentType.Application.Json)
            setBody("""{"idToken":"u1|sam@example.com|true"}""")
        }.let {
            assertEquals(HttpStatusCode.ServiceUnavailable, it.status)
            assertEquals("sign_in_unavailable", it.problemCode())
        }
        val code = devices.createInvite().code
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v1/join") {
                contentType(ContentType.Application.Json)
                setBody("""{"inviteCode":"$code"}""")
            }.status,
        )
    }

    @Test
    fun `disallowing revokes the signed-in devices the list no longer lets in`() {
        devices.allowUser("*@example.com")
        devices.allowUser("kim@example.com")
        val sam = devices.joinUser(VerifiedUser("u1", "sam@example.com", true), "android", "1")
        val kim = devices.joinUser(VerifiedUser("u2", "kim@example.com", true), "android", "1")
        val invited = devices.join(devices.createInvite().code, "android", "1")

        val result = devices.disallowUser("*@example.com")
        assertTrue(result.removed)
        assertEquals(listOf(sam.deviceId), result.revokedDevices)
        assertNull(devices.authenticate(sam.deviceToken))
        assertNotNull(devices.authenticate(kim.deviceToken))
        assertNotNull(devices.authenticate(invited.deviceToken))
        assertEquals(listOf("kim@example.com"), devices.allowedUsers().map { it.pattern })
        assertFailsWith<IllegalArgumentException> { devices.allowUser("not an email") }
    }

    @Test
    fun `tt-admin allow, allowed, devices and disallow`() {
        fun run(vararg args: String): Pair<Int, String> {
            val out = ByteArrayOutputStream()
            val code = runAdmin(args.toList(), config, PrintStream(out, true), PrintStream(out, true), clock)
            return code to out.toString()
        }
        assertContains(run("allowed").second, "Nobody can sign in yet")
        run("allow", "Sam@Example.com", "--note", "Sam").let { (code, out) ->
            assertEquals(0, code)
            assertContains(out, "Allowed sam@example.com")
        }
        assertContains(run("allow", "sam@example.com").second, "already allowed")
        assertEquals(2, run("allow", "sam").first)
        assertContains(run("allowed").second, "sam@example.com")

        val joined = devices.joinUser(VerifiedUser("u1", "sam@example.com", true), "android", "1")
        assertContains(run("devices").second, "sam@example.com")
        run("disallow", "sam@example.com").let { (code, out) ->
            assertEquals(0, code)
            assertContains(out, "Revoked ${joined.deviceId}")
        }
        assertEquals(1, run("disallow", "sam@example.com").first)
    }
}
