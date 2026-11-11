package app.transittracker.timetable

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.transittracker.model.Timetable
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path

/** JVM entry points for opening and writing timetable pack files. */
object JvmTimetables {
    /** SQLite page size of every pack file (fixed so builds are reproducible). */
    const val PAGE_SIZE: Int = 4096

    /** Opens the uncompressed pack at [path] read-only. */
    fun open(path: Path): TimetablePack {
        require(Files.isRegularFile(path)) { "No timetable pack at $path" }
        val config = SQLiteConfig().apply {
            setReadOnly(true)
        }
        val driver = PooledSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}", config.toProperties())
        return try {
            TimetablePack(driver)
        } catch (e: Throwable) {
            driver.close()
            throw e
        }
    }

    /** Opens several packs as one [Timetable]; a single pack is returned as is. */
    fun openAll(paths: List<Path>): Timetable {
        val packs = ArrayList<TimetablePack>()
        try {
            paths.forEach { packs += open(it) }
        } catch (e: Throwable) {
            packs.forEach { runCatching { it.close() } }
            throw e
        }
        return packs.singleOrNull() ?: CompositeTimetable(packs)
    }

    /**
     * Writes [content] to a new SQLite file at [path] (which must not exist) with a fixed page
     * size, then `VACUUM`s it. The same content always produces the same bytes (for the same
     * SQLite library version).
     */
    fun write(path: Path, content: PackContent) {
        require(!Files.exists(path)) { "$path already exists" }
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val config = SQLiteConfig().apply {
            setPageSize(PAGE_SIZE)
            setEncoding(SQLiteConfig.Encoding.UTF8)
            setSynchronous(SQLiteConfig.SynchronousMode.OFF)
            setJournalMode(SQLiteConfig.JournalMode.DELETE)
        }
        JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}", config.toProperties()).use { driver ->
            PackWriter.write(driver, content)
            driver.execute(null, "VACUUM", 0)
        }
    }
}
