package app.transittracker.server.app

import app.transittracker.model.DeviceUpdate
import app.transittracker.model.WatchDto
import app.transittracker.server.spi.ApiException
import app.transittracker.server.spi.NotificationLogEntry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoresTest {
    private val dir: Path = createTempDirectory("tt-store-test")
    private val clock = MutableClock()
    private val db = Database.open(dir.resolve("tt.db"))
    private val devices = SqliteDeviceStore(db, clock)
    private val watches = SqliteWatchRepository(db, clock)
    private val log = SqliteNotificationLog(db)

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun watch(id: String) = WatchDto(id = id, label = "Commute $id", originStationId = "2135", destinationStationId = "2000")

    @Test
    fun `migrations run once, database uses WAL and foreign keys`() {
        assertEquals(Database.MIGRATIONS.size, db.schemaVersion())
        assertEquals("wal", db.journalMode().lowercase())
        db.close()
        // Re-opening is a no-op migration and keeps data.
        Database.open(dir.resolve("tt.db")).use { again ->
            assertEquals(Database.MIGRATIONS.size, again.schemaVersion())
            assertEquals(1, again.read { c -> c.queryInt("PRAGMA foreign_keys") })
        }
    }

    @Test
    fun `refuses a database from a newer build`() {
        val path = dir.resolve("future.db")
        DriverManager.getConnection("jdbc:sqlite:$path").use { it.createStatement().execute("PRAGMA user_version = 999") }
        assertFailsWith<IllegalStateException> { Database.open(path) }
    }

    @Test
    fun `invite codes are typeable, one-time and stored only as hashes`() {
        val invite = devices.createInvite(expiresInDays = 7, note = "for Sam")
        assertTrue(Regex("[A-Z2-9]{4}-[A-Z2-9]{4}").matches(invite.code), invite.code)
        assertTrue(invite.code.none { it in "01OIL" })
        val stored = db.read { c -> c.query("SELECT code_hash FROM invites") { it.getString(1) } }
        assertEquals(listOf(Secrets.sha256Hex(invite.code.replace("-", ""))), stored)

        // Lower case and missing dash are accepted.
        val joined = devices.join(invite.code.lowercase().replace("-", ""), "android", "1.0")
        assertEquals(43, joined.deviceToken.length)
        val e = assertFailsWith<ApiException> { devices.join(invite.code, "android", "1.0") }
        assertEquals("invite_used", e.code)
    }

    @Test
    fun `expired and unknown invites are rejected`() {
        val invite = devices.createInvite(expiresInDays = 1)
        clock.advance(24L * 3600 * 1000)
        assertEquals("invite_expired", assertFailsWith<ApiException> { devices.join(invite.code, "android", "1") }.code)
        assertEquals("invite_invalid", assertFailsWith<ApiException> { devices.join("AAAA-BBBB", "android", "1") }.code)
        assertEquals("invite_invalid", assertFailsWith<ApiException> { devices.join("not a code", "android", "1") }.code)
        assertEquals(0, devices.all().size)
    }

    @Test
    fun `device tokens are stored only as hashes and authenticate`() {
        val joined = devices.join(devices.createInvite().code, "android", "1.0")
        val hashes = db.read { c -> c.query("SELECT token_hash FROM devices") { it.getString(1) } }
        assertEquals(listOf(Secrets.sha256Hex(joined.deviceToken)), hashes)
        assertEquals(joined.deviceId, devices.authenticate(joined.deviceToken)?.deviceId)
        assertNull(devices.authenticate("wrong"))
    }

    @Test
    fun `authenticate updates last seen at most once a minute`() {
        val joined = devices.join(devices.createInvite().code, "android", "1.0")
        val joinedAt = clock.now
        clock.advance(30_000)
        devices.authenticate(joined.deviceToken)
        assertEquals(joinedAt, devices.get(joined.deviceId)!!.lastSeenMs)
        clock.advance(31_000)
        devices.authenticate(joined.deviceToken)
        assertEquals(clock.now, devices.get(joined.deviceId)!!.lastSeenMs)
    }

    @Test
    fun `device update sets push token and settings`() {
        val id = devices.join(devices.createInvite().code, "android", "1.0").deviceId
        devices.update(id, DeviceUpdate(pushToken = "fcm-token", appVersion = "1.1", notificationsEnabled = false))
        devices.get(id)!!.let {
            assertEquals("fcm-token", it.pushToken)
            assertEquals("1.1", it.appVersion)
            assertFalse(it.notificationsEnabled)
        }
        devices.update(id, DeviceUpdate(pushToken = null))
        assertEquals("fcm-token", devices.get(id)!!.pushToken)
        devices.clearPushToken(id)
        assertNull(devices.get(id)!!.pushToken)
    }

    @Test
    fun `revoke deletes token, push token and watches`() {
        val joined = devices.join(devices.createInvite().code, "android", "1.0")
        devices.update(joined.deviceId, DeviceUpdate(pushToken = "fcm"))
        watches.upsert(joined.deviceId, watch("w1"))
        assertTrue(devices.revoke(joined.deviceId))
        assertNull(devices.authenticate(joined.deviceToken))
        devices.get(joined.deviceId)!!.let {
            assertTrue(it.revoked)
            assertNull(it.pushToken)
        }
        assertTrue(watches.list(joined.deviceId).isEmpty())
        assertNull(db.read { c -> c.query("SELECT token_hash FROM devices") { it.getString(1) }.single() })
        assertFalse(devices.revoke("nope"))
        assertEquals(0, devices.activeCount())
    }

    @Test
    fun `prune removes devices unseen for 90 days with their watches`() {
        val old = devices.join(devices.createInvite().code, "android", "1.0")
        watches.upsert(old.deviceId, watch("w1"))
        log.record(NotificationLogEntry(clock.now, old.deviceId, "w1", "T1.1", "20261111", "delay", "Late", "sent"))
        clock.advance(80L * 24 * 3600 * 1000)
        val recent = devices.join(devices.createInvite().code, "android", "1.0")
        clock.advance(11L * 24 * 3600 * 1000)

        val result = devices.prune()
        assertEquals(1, result.devices)
        assertEquals(1, result.notifications)
        assertNull(devices.get(old.deviceId))
        assertNotNull(devices.get(recent.deviceId))
        assertTrue(watches.all().isEmpty())
    }

    @Test
    fun `watch repository CRUD`() {
        val id = devices.join(devices.createInvite().code, "android", "1.0").deviceId
        watches.upsert(id, watch("w1"))
        watches.upsert(id, watch("w2"))
        watches.upsert(id, watch("w1").copy(label = "Renamed"))
        assertEquals(listOf("w1", "w2"), watches.list(id).map { it.id })
        assertEquals("Renamed", watches.get(id, "w1")!!.label)
        assertEquals(2, watches.all().size)
        assertTrue(watches.delete(id, "w1"))
        assertFalse(watches.delete(id, "w1"))
        assertNull(watches.get(id, "w1"))
    }

    @Test
    fun `notification log returns newest first`() {
        repeat(5) { i ->
            log.record(NotificationLogEntry(clock.now + i, "d", null, null, null, "delay", "n$i", "sent"))
        }
        assertEquals(listOf("n4", "n3"), log.recent(2).map { it.title })
    }

    @Test
    fun `backup produces a consistent, openable copy while the database is open`() {
        val joined = devices.join(devices.createInvite().code, "android", "1.0")
        val target = dir.resolve("backups/tt-backup.db")
        db.backupTo(target)
        assertTrue(target.exists())
        assertFalse(Files.exists(target.resolveSibling("tt-backup.db.partial")))
        Database.open(target).use { copy ->
            assertEquals(joined.deviceId, SqliteDeviceStore(copy, clock).all().single().deviceId)
        }
        // Backing up again overwrites.
        db.backupTo(target)
    }

    @Test
    fun `secrets are random`() {
        assertNotEquals(Secrets.newDeviceToken(), Secrets.newDeviceToken())
        assertNotEquals(Secrets.newInviteCode(), Secrets.newInviteCode())
        assertNull(Secrets.normalizeInviteCode("ABCD-EFG0"))
        assertEquals("ABCDEFGH", Secrets.normalizeInviteCode(" abcd-efgh "))
    }
}
