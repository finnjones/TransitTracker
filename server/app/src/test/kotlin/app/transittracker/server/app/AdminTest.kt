package app.transittracker.server.app

import app.transittracker.model.WatchDto
import app.transittracker.server.spi.ServerConfig
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminTest {
    private val dir: Path = createTempDirectory("tt-admin-test")
    private val config = ServerConfig(dataDir = dir)
    private val clock = MutableClock()

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runAdmin(args.toList(), config, PrintStream(out, true), PrintStream(err, true), clock)
        return code to (out.toString() + err.toString())
    }

    @Test
    fun `invite, devices, watches, revoke and backup work against the live database file`() {
        val (code, output) = run("invite", "--expires-days", "7", "--note", "Sam's phone")
        assertEquals(0, code)
        val invite = assertNotNull(Regex("Invite code: (\\S+)").find(output)).groupValues[1]
        assertContains(output, "Sam's phone")

        // The "server" has the database open at the same time.
        Database.open(config.dbPath).use { db ->
            val devices = SqliteDeviceStore(db, clock)
            val joined = devices.join(invite, "android", "1.0")
            SqliteWatchRepository(db, clock).upsert(
                joined.deviceId,
                WatchDto(id = "w1", label = "Work", originStationId = "2135", destinationStationId = "2000"),
            )

            assertContains(run("devices").second, joined.deviceId)
            assertContains(run("invites").second, "Sam's phone")
            assertContains(run("watches").second, "Work")
            assertEquals(0, run("notifications", "--limit", "5").first)

            val backup = dir.resolve("backup/tt.db")
            assertEquals(0, run("backup", backup.toString()).first)
            assertTrue(backup.exists())

            assertEquals(0, run("revoke", joined.deviceId).first)
            assertNull(devices.authenticate(joined.deviceToken))
            assertEquals(1, run("revoke", "unknown").first)
        }
    }

    @Test
    fun `bad usage exits with 2`() {
        assertEquals(2, run().first)
        assertEquals(2, run("frobnicate").first)
        assertEquals(2, run("invite", "--expires-days", "soon").first)
        assertEquals(2, run("import-gtfs").first)
        assertEquals(2, run("import-gtfs", "../evil", "a.zip").first)
        assertEquals(1, run("import-gtfs", "syd-rail", dir.resolve("missing.zip").toString()).first)
    }

    @Test
    fun `import-gtfs builds and publishes a pack from a GTFS directory`() {
        clock.now = java.time.Instant.parse("2026-11-10T16:00:00Z").toEpochMilli() // 03:00 Wed 11 Nov, Sydney
        val (code, output) = run("import-gtfs", "syd-rail", Fixtures.miniRail.toString())
        assertEquals(0, code, output)
        assertContains(output, "Published timetable pack syd-rail/20261111T0300")
        assertTrue(config.packsDir.resolve("syd-rail").exists())

        // Importing the same input again publishes a new version next to it.
        val (again, output2) = run("import-gtfs", "syd-rail", Fixtures.miniRail.toString())
        assertEquals(0, again, output2)
        assertContains(output2, "syd-rail/20261111T0300-2")
    }

    @Test
    fun `import-gtfs reports invalid GTFS and publishes nothing`() {
        val broken = createTempDirectory("tt-broken-gtfs")
        try {
            java.nio.file.Files.writeString(broken.resolve("agency.txt"), "agency_id,agency_name\nX,Broken\n")
            val (code, output) = run("import-gtfs", "syd-rail", broken.toString())
            assertEquals(1, code, output)
            assertContains(output, "syd-rail")
        } finally {
            broken.toFile().deleteRecursively()
        }
    }
}
