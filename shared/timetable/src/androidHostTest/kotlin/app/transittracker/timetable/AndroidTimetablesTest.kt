package app.transittracker.timetable

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.transittracker.model.DepartureQuery
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.sqlite.SQLiteConfig
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Android pack opener over Robolectric's framework SQLite: a pack written the way the
 * pipeline writes it (JDBC SQLite + [PackWriter] + VACUUM) is opened with
 * [AndroidTimetables.open], queried (also from several threads at once), and must be byte for
 * byte unchanged afterwards, with no journal or other side files next to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidTimetablesTest {
    private val dir: File = Files.createTempDirectory("tt-android-pack").toFile()
    private val opened = ArrayList<TimetablePack>()

    @After
    fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    @Test
    fun readerQueriesWork() {
        val tt = open()
        assertEquals("v1", tt.version)
        assertEquals("test", tt.name)
        val deps = tt.departures(DepartureQuery(setOf("X"), setOf("Z"), "20261111", 0, 30 * 3600))
        assertEquals(listOf("T1", "T3", "T2"), deps.map { it.tripId })
        assertEquals("X1", deps.first().originStopId)
        assertEquals(8 * 3600, deps.first().departureSecs)
        // Typed arguments: a route filter (text), a time window (integers) and a limit.
        val metro = tt.departures(DepartureQuery(setOf("X"), setOf("Z"), "20261111", 0, 30 * 3600, routeIds = setOf("R2")))
        assertEquals(listOf("T3"), metro.map { it.tripId })
        val window = tt.departures(DepartureQuery(setOf("X"), setOf("Z"), "20261111", 8 * 3600 + 1800, 9 * 3600 + 1800))
        assertEquals(listOf("T2"), window.map { it.tripId })

        val t1 = assertNotNull(tt.trip("T1"))
        assertEquals("SH1", t1.shapeId)
        assertEquals(listOf("X1", "Y", "Z"), t1.stopTimes.map { it.stopId })
        assertNull(tt.trip("missing"))
        assertEquals(setOf(Mode.TRAIN, Mode.METRO), tt.stop("X")?.modes)
        assertEquals(listOf("X", "X1", "X2"), tt.searchStops("xavier").map { it.id })
        assertEquals(listOf("R1", "R2"), tt.routes().map { it.id })
        assertEquals(3, tt.shape("SH1")?.size)
        assertEquals(180, tt.transfers("X1").single().minTransferSecs)
        assertEquals(listOf("X", "X1", "X2"), tt.stopsNear(LatLng(-33.88, 151.20), 100.0).map { it.id })
    }

    @Test
    fun concurrentQueriesFromManyThreads() {
        val tt = open()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..64).map { i ->
                Callable {
                    if (i % 2 == 0) {
                        tt.departures(DepartureQuery(setOf("X"), setOf("Z"), "20261111", 0, 30 * 3600)).map { it.tripId }
                    } else {
                        tt.trip("T${i % 3 + 1}")!!.stopTimes.map { it.stopId }
                    }
                }
            }
            val results = pool.invokeAll(tasks, 60, TimeUnit.SECONDS).map { it.get() }
            results.forEachIndexed { index, r ->
                val i = index + 1
                if (i % 2 == 0) assertEquals(listOf("T1", "T3", "T2"), r) else assertEquals(3, r.size)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun theFileIsNeverModified() {
        val file = File(dir, "pack.sqlite")
        write(file)
        val before = sha256(file)
        val modified = file.lastModified()
        val tt = AndroidTimetables.open(file.path)
        tt.departures(DepartureQuery(setOf("X"), setOf("Z"), "20261111", 0, 30 * 3600))
        tt.trip("T1")
        tt.searchStops("platform")
        tt.close()
        assertEquals(before, sha256(file))
        assertEquals(modified, file.lastModified())
        assertEquals(listOf("pack.sqlite"), dir.list()!!.sorted())
    }

    @Test
    fun writesAreRefused() {
        val file = File(dir, "pack.sqlite")
        write(file)
        val driver = ReadOnlySqliteDriver(file.path)
        try {
            assertFailsWith<UnsupportedOperationException> { driver.execute(null, "DELETE FROM meta", 0) }
            assertFailsWith<UnsupportedOperationException> { driver.newTransaction() }
        } finally {
            driver.close()
        }
    }

    @Test
    fun missingFileFailsAtOpen() {
        assertFailsWith<IllegalArgumentException> { AndroidTimetables.open(File(dir, "none.sqlite").path) }
        assertTrue(dir.list()!!.isEmpty())
    }

    private fun open(): TimetablePack {
        val file = File(dir, "pack.sqlite")
        write(file)
        return AndroidTimetables.open(file.path).also { opened += it }
    }

    /** As `JvmTimetables.write` does it (jvmMain is not visible to Android tests). */
    private fun write(file: File) {
        val config = SQLiteConfig().apply {
            setPageSize(4096)
            setEncoding(SQLiteConfig.Encoding.UTF8)
            setSynchronous(SQLiteConfig.SynchronousMode.OFF)
            setJournalMode(SQLiteConfig.JournalMode.DELETE)
        }
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", config.toProperties()).use { driver ->
            PackWriter.write(driver, content())
            driver.execute(null, "VACUUM", 0)
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun content(): PackContent {
        val weekdays = BooleanArray(14) { it % 7 < 5 }
        val stops = listOf(
            PackStop(10, "X", null, "Xavier Station", -33.88, 151.20, null, 1, setOf(Mode.TRAIN, Mode.METRO), 1),
            PackStop(11, "X1", 10, "Xavier Station Platform 1", -33.8801, 151.2001, "1", 0, setOf(Mode.TRAIN), 1),
            PackStop(12, "X2", 10, "Xavier Station Platform 2", -33.8802, 151.2002, "2", 0, setOf(Mode.METRO), 1),
            PackStop(20, "Y", null, "Yarra Street", -33.87, 151.21, null, 0, setOf(Mode.TRAIN, Mode.METRO), 0),
            PackStop(30, "Z", null, "Zetland", -33.86, 151.22, null, 0, setOf(Mode.TRAIN, Mode.METRO), 2),
        )
        val routes = listOf(
            PackRoute(1, "R1", "A1", "T1", "Train line", 2, Mode.TRAIN, "F99D1C", "000000", 1),
            PackRoute(2, "R2", "A2", "M1", "Metro line", 401, Mode.METRO, null, null, 2),
        )
        val patterns = listOf(
            Pattern(1, 1, intArrayOf(11, 20, 30)),
            Pattern(2, 2, intArrayOf(12, 20, 30), intArrayOf(10, 20, 30), intArrayOf(0, 0, 0), intArrayOf(0, 1, 0)),
        )
        val trips = listOf(
            PackTrip(1, "T1", 1, 1, 8 * 3600, 1, "Zetland", 0, 1, wheelchair = 1),
            PackTrip(2, "T2", 1, 1, 9 * 3600, 1, "Zetland", 0, 1),
            PackTrip(3, "T3", 2, 1, 8 * 3600 + 60, 1, "Zetland", 0, null),
        )
        val shape = listOf(LatLng(-33.8801, 151.2001), LatLng(-33.87, 151.21), LatLng(-33.86, 151.22))
        return PackContent(
            meta = mapOf(PackMeta.NAME to "test", PackMeta.VERSION to "v1"),
            stops = stops,
            routes = routes,
            patterns = patterns,
            profiles = listOf(TimeProfile(1, intArrayOf(0, 300, 600), intArrayOf(0, 330, 600))),
            services = listOf(PackService(1, "WD", "20261109", weekdays)),
            trips = trips,
            transfers = listOf(PackTransfer(11, 12, 2, 180)),
            shapes = listOf(PackShape(1, "SH1", Polyline.encode(shape))),
        )
    }
}
