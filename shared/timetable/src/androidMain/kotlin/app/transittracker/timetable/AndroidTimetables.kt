package app.transittracker.timetable

import java.io.File

/** Android entry point for opening downloaded timetable pack files (the Android twin of `JvmTimetables.open`). */
object AndroidTimetables {
    /**
     * Opens the uncompressed pack at [path] read-only. The file is never modified (see
     * [ReadOnlySqliteDriver]); queries may run from several threads at once.
     */
    fun open(path: String): TimetablePack {
        require(File(path).isFile) { "No timetable pack at $path" }
        val driver = ReadOnlySqliteDriver(path)
        return try {
            driver.verify()
            TimetablePack(driver)
        } catch (e: Throwable) {
            driver.close()
            throw e
        }
    }
}
