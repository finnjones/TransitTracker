package app.transittracker.server.app

import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The tt-server SQLite database: one file, WAL journal, versioned migrations.
 *
 * A single JDBC connection is shared and guarded by a lock: at this scale (a few devices) that is
 * simpler than a pool and plenty fast. WAL mode plus a busy timeout lets `tt-admin` open the same
 * file from another process while the server runs. Write transactions use `BEGIN IMMEDIATE` so
 * two processes never deadlock upgrading a read lock.
 */
class Database private constructor(
    /** Location of the database file. */
    val path: Path,
    private val connection: Connection,
) : AutoCloseable {
    private val lock = ReentrantLock()

    /** Runs [block] with the connection in auto-commit mode (single statements, reads). */
    fun <T> read(block: (Connection) -> T): T = lock.withLock { block(connection) }

    /** Runs [block] in one transaction; rolls back if it throws. */
    fun <T> transaction(block: (Connection) -> T): T = lock.withLock {
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    /** Current schema version (`PRAGMA user_version`). */
    fun schemaVersion(): Int = read { c -> c.queryInt("PRAGMA user_version") }

    /** Current journal mode, `wal` once opened. */
    fun journalMode(): String = read { c ->
        c.createStatement().use { s -> s.executeQuery("PRAGMA journal_mode").use { rs -> rs.next(); rs.getString(1) } }
    }

    /**
     * Writes a consistent copy of the live database to [target] using `VACUUM INTO`, which is
     * safe while other connections (the server) read and write. The copy is written next to the
     * target and then moved into place, so a half-written backup never has the final name.
     */
    fun backupTo(target: Path) {
        val absolute = target.toAbsolutePath()
        absolute.parent?.let { Files.createDirectories(it) }
        val tmp = absolute.resolveSibling(absolute.fileName.toString() + ".partial")
        Files.deleteIfExists(tmp)
        read { c -> c.prepareStatement("VACUUM INTO ?").use { it.setString(1, tmp.toString()); it.execute() } }
        Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun close() = lock.withLock { connection.close() }

    companion object {
        private val log = LoggerFactory.getLogger(Database::class.java)

        /**
         * Schema migrations; index + 1 is the version each one produces. Never edit a released
         * migration: append a new one.
         */
        internal val MIGRATIONS: List<List<String>> = listOf(
            // v1
            listOf(
                """
                CREATE TABLE devices (
                    device_id TEXT PRIMARY KEY,
                    token_hash TEXT UNIQUE,
                    push_token TEXT,
                    platform TEXT NOT NULL,
                    app_version TEXT NOT NULL,
                    locale TEXT,
                    notifications_enabled INTEGER NOT NULL DEFAULT 1,
                    created_ms INTEGER NOT NULL,
                    last_seen_ms INTEGER NOT NULL,
                    revoked INTEGER NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE TABLE invites (
                    code_hash TEXT PRIMARY KEY,
                    note TEXT,
                    created_ms INTEGER NOT NULL,
                    expires_ms INTEGER,
                    used_ms INTEGER,
                    used_by_device TEXT
                )
                """,
                """
                CREATE TABLE watches (
                    device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
                    watch_id TEXT NOT NULL,
                    body TEXT NOT NULL,
                    updated_ms INTEGER NOT NULL,
                    PRIMARY KEY (device_id, watch_id)
                )
                """,
                """
                CREATE TABLE notifications (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    at_ms INTEGER NOT NULL,
                    device_id TEXT NOT NULL,
                    watch_id TEXT,
                    trip_id TEXT,
                    service_date TEXT,
                    kind TEXT NOT NULL,
                    title TEXT NOT NULL,
                    result TEXT NOT NULL
                )
                """,
                "CREATE INDEX notifications_at ON notifications(at_ms)",
                "CREATE INDEX devices_last_seen ON devices(last_seen_ms)",
            ),
            // v2: devices joined by signing in (Firebase Authentication) and who may sign in.
            listOf(
                "ALTER TABLE devices ADD COLUMN user_id TEXT",
                "ALTER TABLE devices ADD COLUMN user_email TEXT",
                "CREATE INDEX devices_user ON devices(user_id)",
                """
                CREATE TABLE allowed_users (
                    pattern TEXT PRIMARY KEY,
                    note TEXT,
                    created_ms INTEGER NOT NULL
                )
                """,
            ),
        )

        /** Opens (creating if needed) the database at [path] and applies pending migrations. */
        fun open(path: Path): Database {
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            val config = SQLiteConfig().apply {
                setJournalMode(SQLiteConfig.JournalMode.WAL)
                setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
                enforceForeignKeys(true)
                setBusyTimeout(10_000)
                setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
            }
            val connection = config.createConnection("jdbc:sqlite:${path.toAbsolutePath()}")
            val db = Database(path, connection)
            try {
                db.migrate()
            } catch (e: Throwable) {
                connection.close()
                throw e
            }
            return db
        }
    }

    private fun migrate() {
        val current = schemaVersion()
        check(current <= MIGRATIONS.size) {
            "Database $path has schema v$current, newer than this build (v${MIGRATIONS.size})"
        }
        for (version in current + 1..MIGRATIONS.size) {
            transaction { c ->
                c.createStatement().use { s ->
                    MIGRATIONS[version - 1].forEach { s.executeUpdate(it.trimIndent()) }
                    s.executeUpdate("PRAGMA user_version = $version")
                }
            }
            log.info("Database migrated to schema v{}", version)
        }
    }
}

// ---- small JDBC helpers ------------------------------------------------------------------------

internal fun Connection.queryInt(sql: String): Int =
    createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getInt(1) else 0 } }

internal fun PreparedStatement.bind(vararg args: Any?): PreparedStatement {
    args.forEachIndexed { i, arg ->
        when (arg) {
            null -> setObject(i + 1, null)
            is String -> setString(i + 1, arg)
            is Int -> setInt(i + 1, arg)
            is Long -> setLong(i + 1, arg)
            is Boolean -> setInt(i + 1, if (arg) 1 else 0)
            else -> error("Unsupported JDBC argument type ${arg::class}")
        }
    }
    return this
}

internal fun Connection.update(sql: String, vararg args: Any?): Int =
    prepareStatement(sql).use { it.bind(*args).executeUpdate() }

internal fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { ps ->
        ps.bind(*args).executeQuery().use { rs ->
            val out = ArrayList<T>()
            while (rs.next()) out += map(rs)
            out
        }
    }

internal fun ResultSet.getLongOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
