package app.transittracker.server.app

import app.transittracker.model.DeviceUpdate
import app.transittracker.model.JoinResponse
import app.transittracker.model.WallClock
import app.transittracker.model.WatchDto
import app.transittracker.server.spi.ApiException
import app.transittracker.server.spi.DeviceRecord
import app.transittracker.server.spi.DeviceRepository
import app.transittracker.server.spi.NotificationLog
import app.transittracker.server.spi.NotificationLogEntry
import app.transittracker.server.spi.StoredWatch
import app.transittracker.server.spi.WatchRepository
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.util.UUID

/** System wall clock. */
val SystemClock: WallClock = WallClock { System.currentTimeMillis() }

private const val DAY_MS = 24L * 3600 * 1000

/** A freshly created invite. [code] is shown once to the admin and never stored. */
data class CreatedInvite(val code: String, val createdMs: Long, val expiresMs: Long?, val note: String?)

/** Summary of a stored invite (without the code, which is not recoverable). */
data class InviteSummary(val note: String?, val createdMs: Long, val expiresMs: Long?, val usedMs: Long?, val usedByDevice: String?)

/** One entry of the sign-in allow list ([AllowedUsers]). */
data class AllowedUser(val pattern: String, val note: String?, val createdMs: Long)

/** What [SqliteDeviceStore.disallowUser] did: whether [pattern] was listed, and the devices it cut off. */
data class DisallowResult(val removed: Boolean, val revokedDevices: List<String>)

/** Counts of rows removed by [SqliteDeviceStore.prune]. */
data class PruneResult(val devices: Int, val notifications: Int, val invites: Int)

/**
 * Devices, device tokens, invite codes and the sign-in allow list in SQLite. Implements
 * [DeviceRepository] for the other modules and adds the join / authenticate / revoke operations
 * used by the HTTP API and tt-admin.
 */
class SqliteDeviceStore(
    private val db: Database,
    private val clock: WallClock = SystemClock,
) : DeviceRepository {
    private val log = LoggerFactory.getLogger(SqliteDeviceStore::class.java)

    // ---- invites ----

    /** Creates a one-time invite code, optionally expiring after [expiresInDays] days. */
    fun createInvite(expiresInDays: Int? = null, note: String? = null): CreatedInvite {
        require(expiresInDays == null || expiresInDays > 0) { "expiresInDays must be positive" }
        val now = clock.nowMillis()
        val expires = expiresInDays?.let { now + it * DAY_MS }
        val cleanNote = note?.trim()?.take(MAX_NOTE)?.takeIf { it.isNotEmpty() }
        // Retry on the (astronomically unlikely) chance of a hash collision with an existing code.
        repeat(5) {
            val code = Secrets.newInviteCode()
            val hash = Secrets.sha256Hex(Secrets.normalizeInviteCode(code)!!)
            val inserted = db.read { c ->
                c.update(
                    "INSERT OR IGNORE INTO invites(code_hash, note, created_ms, expires_ms) VALUES (?, ?, ?, ?)",
                    hash, cleanNote, now, expires,
                )
            }
            if (inserted == 1) {
                log.info("Invite created (expires={}, note={})", expires, cleanNote != null)
                return CreatedInvite(code, now, expires, cleanNote)
            }
        }
        error("Could not generate a unique invite code")
    }

    /** All invites, newest first. */
    fun invites(): List<InviteSummary> = db.read { c ->
        c.query("SELECT * FROM invites ORDER BY created_ms DESC") { rs ->
            InviteSummary(
                note = rs.getString("note"),
                createdMs = rs.getLong("created_ms"),
                expiresMs = rs.getLongOrNull("expires_ms"),
                usedMs = rs.getLongOrNull("used_ms"),
                usedByDevice = rs.getString("used_by_device"),
            )
        }
    }

    /**
     * Redeems [inviteCode] and registers a new device in one transaction.
     * @throws ApiException 403 `invite_invalid` / `invite_used` / `invite_expired`.
     */
    fun join(inviteCode: String, platform: String, appVersion: String): JoinResponse {
        val normalized = Secrets.normalizeInviteCode(inviteCode)
            ?: throw ApiException(403, "invite_invalid", "Unknown invite code")
        val hash = Secrets.sha256Hex(normalized)
        val now = clock.nowMillis()
        return db.transaction { c ->
            val invite = c.query("SELECT expires_ms, used_ms FROM invites WHERE code_hash = ?", hash) { rs ->
                rs.getLongOrNull("expires_ms") to rs.getLongOrNull("used_ms")
            }.firstOrNull() ?: throw ApiException(403, "invite_invalid", "Unknown invite code")
            val (expiresMs, usedMs) = invite
            if (usedMs != null) throw ApiException(403, "invite_used", "This invite code has already been used")
            if (expiresMs != null && expiresMs <= now) throw ApiException(403, "invite_expired", "This invite code has expired")

            val deviceId = UUID.randomUUID().toString()
            val token = Secrets.newDeviceToken()
            c.update(
                """INSERT INTO devices(device_id, token_hash, platform, app_version, created_ms, last_seen_ms)
                   VALUES (?, ?, ?, ?, ?, ?)""",
                deviceId, Secrets.sha256Hex(token), platform.take(MAX_SHORT).ifBlank { "unknown" },
                appVersion.take(MAX_SHORT), now, now,
            )
            val claimed = c.update(
                "UPDATE invites SET used_ms = ?, used_by_device = ? WHERE code_hash = ? AND used_ms IS NULL",
                now, deviceId, hash,
            )
            if (claimed != 1) throw ApiException(403, "invite_used", "This invite code has already been used")
            log.info("Device {} joined ({})", deviceId, platform.take(MAX_SHORT))
            JoinResponse(deviceId = deviceId, deviceToken = token)
        }
    }

    // ---- sign-in (Firebase Authentication) ----

    /** Lets accounts matching [pattern] ([AllowedUsers]) sign in. False when it was already listed. */
    fun allowUser(pattern: String, note: String? = null): Boolean {
        val p = AllowedUsers.normalize(pattern) ?: throw IllegalArgumentException("Not an email, *@domain or *: $pattern")
        val cleanNote = note?.trim()?.take(MAX_NOTE)?.takeIf { it.isNotEmpty() }
        val added = db.read { c ->
            c.update("INSERT OR IGNORE INTO allowed_users(pattern, note, created_ms) VALUES (?, ?, ?)", p, cleanNote, clock.nowMillis())
        } == 1
        if (added) log.info("Sign-in allowed for {}", p)
        return added
    }

    /**
     * Removes [pattern] from the allow list, then revokes every signed-in device whose account no
     * remaining entry lets in (invite-code devices are left alone).
     */
    fun disallowUser(pattern: String): DisallowResult {
        val p = AllowedUsers.normalize(pattern) ?: pattern.trim().lowercase()
        val (removed, orphans) = db.transaction { c ->
            val removed = c.update("DELETE FROM allowed_users WHERE pattern = ?", p) > 0
            val patterns = c.query("SELECT pattern FROM allowed_users") { it.getString(1) }
            val orphans = c.query("SELECT device_id, user_email FROM devices WHERE revoked = 0 AND user_id IS NOT NULL") {
                it.getString(1) to it.getString(2)
            }.filter { (_, email) -> patterns.none { pat -> AllowedUsers.matches(pat, email, emailVerified = true) } }
                .map { it.first }
            removed to orphans
        }
        orphans.forEach { revoke(it) }
        return DisallowResult(removed, orphans)
    }

    /** The allow list, oldest first. */
    fun allowedUsers(): List<AllowedUser> = db.read { c ->
        c.query("SELECT * FROM allowed_users ORDER BY created_ms, pattern") { rs ->
            AllowedUser(rs.getString("pattern"), rs.getString("note"), rs.getLong("created_ms"))
        }
    }

    /**
     * Registers a new device for [user], who signed in and whose ID token checked out.
     * @throws ApiException 403 `not_allowed` when no allow-list entry lets them in.
     */
    fun joinUser(user: VerifiedUser, platform: String, appVersion: String): JoinResponse {
        val now = clock.nowMillis()
        return db.transaction { c ->
            val patterns = c.query("SELECT pattern FROM allowed_users") { it.getString(1) }
            if (patterns.none { AllowedUsers.matches(it, user) }) {
                log.info("Sign-in refused for {}: not on the allow list", user.email ?: user.uid)
                throw ApiException(
                    403,
                    "not_allowed",
                    "${user.email ?: "This account"} is not allowed on this server. Ask the person who runs it to add you.",
                )
            }
            val deviceId = UUID.randomUUID().toString()
            val token = Secrets.newDeviceToken()
            c.update(
                """INSERT INTO devices(device_id, token_hash, platform, app_version, created_ms, last_seen_ms, user_id, user_email)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                deviceId, Secrets.sha256Hex(token), platform.take(MAX_SHORT).ifBlank { "unknown" },
                appVersion.take(MAX_SHORT), now, now, user.uid, user.email?.take(MAX_EMAIL),
            )
            log.info("Device {} joined by signing in ({})", deviceId, platform.take(MAX_SHORT))
            JoinResponse(deviceId = deviceId, deviceToken = token)
        }
    }

    // ---- devices ----

    /**
     * Returns the active device owning [token], or null. Updates its last-seen time (at most once
     * a minute, to avoid a write per request).
     */
    fun authenticate(token: String): DeviceRecord? {
        if (token.isEmpty() || token.length > 256) return null
        val hash = Secrets.sha256Hex(token)
        val now = clock.nowMillis()
        return db.read { c ->
            val device = c.query("SELECT * FROM devices WHERE token_hash = ? AND revoked = 0", hash, map = ::toRecord)
                .firstOrNull() ?: return@read null
            if (now - device.lastSeenMs >= LAST_SEEN_RESOLUTION_MS) {
                c.update("UPDATE devices SET last_seen_ms = ? WHERE device_id = ?", now, device.deviceId)
                device.copy(lastSeenMs = now)
            } else {
                device
            }
        }
    }

    /**
     * Applies a `PUT /v1/device` update. Null fields are left unchanged; an empty push token clears it.
     */
    fun update(deviceId: String, update: DeviceUpdate) {
        db.transaction { c ->
            update.pushToken?.let { token ->
                if (token.length > MAX_PUSH_TOKEN) throw ApiException(400, "invalid_push_token", "Push token too long")
                c.update("UPDATE devices SET push_token = ? WHERE device_id = ?", token.ifBlank { null }, deviceId)
            }
            update.appVersion?.let { c.update("UPDATE devices SET app_version = ? WHERE device_id = ?", it.take(MAX_SHORT), deviceId) }
            update.locale?.let { c.update("UPDATE devices SET locale = ? WHERE device_id = ?", it.take(MAX_SHORT), deviceId) }
            c.update("UPDATE devices SET notifications_enabled = ? WHERE device_id = ?", update.notificationsEnabled, deviceId)
        }
    }

    /** Revokes a device: deletes its token, push token and watches. Returns false if unknown. */
    fun revoke(deviceId: String): Boolean = db.transaction { c ->
        c.update("DELETE FROM watches WHERE device_id = ?", deviceId)
        val n = c.update(
            "UPDATE devices SET token_hash = NULL, push_token = NULL, revoked = 1 WHERE device_id = ?",
            deviceId,
        )
        if (n > 0) log.info("Device {} revoked", deviceId)
        n > 0
    }

    /**
     * Deletes devices unseen for [inactiveDays] days (with their watches), notification log
     * entries older than that, and invites used or expired before that.
     */
    fun prune(inactiveDays: Int = PRUNE_DAYS): PruneResult {
        val cutoff = clock.nowMillis() - inactiveDays * DAY_MS
        return db.transaction { c ->
            c.update("DELETE FROM watches WHERE device_id IN (SELECT device_id FROM devices WHERE last_seen_ms < ?)", cutoff)
            val devices = c.update("DELETE FROM devices WHERE last_seen_ms < ?", cutoff)
            val notifications = c.update("DELETE FROM notifications WHERE at_ms < ?", cutoff)
            val invites = c.update("DELETE FROM invites WHERE used_ms < ? OR expires_ms < ?", cutoff, cutoff)
            PruneResult(devices, notifications, invites)
        }.also {
            if (it.devices + it.notifications + it.invites > 0) log.info("Pruned {}", it)
        }
    }

    /** Number of active (not revoked) devices. */
    fun activeCount(): Int = db.read { c -> c.queryInt("SELECT COUNT(*) FROM devices WHERE revoked = 0") }

    override fun get(deviceId: String): DeviceRecord? = db.read { c ->
        c.query("SELECT * FROM devices WHERE device_id = ?", deviceId, map = ::toRecord).firstOrNull()
    }

    override fun all(): List<DeviceRecord> = db.read { c ->
        c.query("SELECT * FROM devices ORDER BY created_ms", map = ::toRecord)
    }

    override fun clearPushToken(deviceId: String) {
        db.read { c -> c.update("UPDATE devices SET push_token = NULL WHERE device_id = ?", deviceId) }
    }

    private fun toRecord(rs: ResultSet) = DeviceRecord(
        deviceId = rs.getString("device_id"),
        pushToken = rs.getString("push_token"),
        platform = rs.getString("platform"),
        appVersion = rs.getString("app_version"),
        notificationsEnabled = rs.getInt("notifications_enabled") != 0,
        createdMs = rs.getLong("created_ms"),
        lastSeenMs = rs.getLong("last_seen_ms"),
        revoked = rs.getInt("revoked") != 0,
        userEmail = rs.getString("user_email"),
    )

    companion object {
        /** Devices unseen for this many days are deleted. */
        const val PRUNE_DAYS = 90
        private const val LAST_SEEN_RESOLUTION_MS = 60_000L
        private const val MAX_SHORT = 64
        private const val MAX_NOTE = 200
        private const val MAX_PUSH_TOKEN = 4096
        private const val MAX_EMAIL = 320
    }
}

/** Watches in SQLite, stored as JSON so new [WatchDto] fields need no migration. */
class SqliteWatchRepository(
    private val db: Database,
    private val clock: WallClock = SystemClock,
) : WatchRepository {
    override fun list(deviceId: String): List<WatchDto> = db.read { c ->
        c.query("SELECT body FROM watches WHERE device_id = ? ORDER BY rowid", deviceId) { decode(it.getString(1)) }
    }

    override fun all(): List<StoredWatch> = db.read { c ->
        c.query("SELECT device_id, body FROM watches ORDER BY device_id, rowid") {
            StoredWatch(it.getString(1), decode(it.getString(2)))
        }
    }

    override fun get(deviceId: String, watchId: String): WatchDto? = db.read { c ->
        c.query("SELECT body FROM watches WHERE device_id = ? AND watch_id = ?", deviceId, watchId) { decode(it.getString(1)) }
            .firstOrNull()
    }

    override fun upsert(deviceId: String, watch: WatchDto) {
        db.read { c ->
            c.update(
                """INSERT INTO watches(device_id, watch_id, body, updated_ms) VALUES (?, ?, ?, ?)
                   ON CONFLICT(device_id, watch_id) DO UPDATE SET body = excluded.body, updated_ms = excluded.updated_ms""",
                deviceId, watch.id, Codecs.json.encodeToString(WatchDto.serializer(), watch), clock.nowMillis(),
            )
        }
    }

    override fun delete(deviceId: String, watchId: String): Boolean = db.read { c ->
        c.update("DELETE FROM watches WHERE device_id = ? AND watch_id = ?", deviceId, watchId) > 0
    }

    private fun decode(body: String): WatchDto = Codecs.json.decodeFromString(WatchDto.serializer(), body)
}

/** Push notification history in SQLite (pruned with devices, see [SqliteDeviceStore.prune]). */
class SqliteNotificationLog(private val db: Database) : NotificationLog {
    override fun record(entry: NotificationLogEntry) {
        db.read { c ->
            c.update(
                """INSERT INTO notifications(at_ms, device_id, watch_id, trip_id, service_date, kind, title, result)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                entry.atMs, entry.deviceId, entry.watchId, entry.tripId, entry.serviceDate, entry.kind,
                entry.title, entry.result,
            )
        }
    }

    override fun recent(limit: Int): List<NotificationLogEntry> = db.read { c ->
        c.query("SELECT * FROM notifications ORDER BY at_ms DESC, id DESC LIMIT ?", limit.coerceIn(1, 10_000)) { rs ->
            NotificationLogEntry(
                atMs = rs.getLong("at_ms"),
                deviceId = rs.getString("device_id"),
                watchId = rs.getString("watch_id"),
                tripId = rs.getString("trip_id"),
                serviceDate = rs.getString("service_date"),
                kind = rs.getString("kind"),
                title = rs.getString("title"),
                result = rs.getString("result"),
            )
        }
    }
}
