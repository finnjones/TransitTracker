package app.transittracker.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.transittracker.model.DepartureQuery
import app.transittracker.model.Mode
import app.transittracker.timetable.PackContent
import app.transittracker.timetable.PackMeta
import app.transittracker.timetable.PackRoute
import app.transittracker.timetable.PackService
import app.transittracker.timetable.PackStop
import app.transittracker.timetable.PackTrip
import app.transittracker.timetable.PackWriter
import app.transittracker.timetable.Pattern
import app.transittracker.timetable.TimeProfile
import com.github.luben.zstd.Zstd
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Android platform glue the core runs on (Robolectric, so framework SQLite is real). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidPlatformTest {
    private val dir: File = Files.createTempDirectory("tt-android-core").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun keyValueStorePersistsAcrossInstances() = runBlocking {
        val file = File(dir, "kv/store.properties")
        val kv = PropertiesKeyValueStore(file)
        assertNull(kv.get("a"))
        kv.put("a", "1")
        kv.put("b", "two words = and: colons")
        kv.put("a", null)
        val again = PropertiesKeyValueStore(file)
        assertNull(again.get("a"))
        assertEquals("two words = and: colons", again.get("b"))
        // Only the store file is left behind (temp files are renamed over it).
        assertEquals(listOf("store.properties"), file.parentFile!!.list()!!.sorted())
    }

    @Test
    fun splitStoreRoutesKeys() = runBlocking {
        val backedUp = PropertiesKeyValueStore(File(dir, "backup.properties"))
        val local = PropertiesKeyValueStore(File(dir, "local.properties"))
        val kv = SplitKeyValueStore(backedUp, local) { it.startsWith("ui.") }
        kv.put("ui.theme", "DAY")
        kv.put(KvKeys.DEVICE_TOKEN, "secret")
        assertEquals("DAY", kv.get("ui.theme"))
        assertEquals("secret", kv.get(KvKeys.DEVICE_TOKEN))
        assertEquals("DAY", backedUp.get("ui.theme"))
        assertNull(backedUp.get(KvKeys.DEVICE_TOKEN))
        assertFalse(File(dir, "backup.properties").readText().contains("secret"))
    }

    @Test
    fun fileStoreReplacesAtomicallyAndDeletes() = runBlocking {
        val store = AndroidFileStore(File(dir, "packs"))
        val first = store.writeTemp(byteArrayOf(1, 2, 3))
        assertTrue(File(first).isFile)
        val path = store.replace(first, "syd.sqlite")
        assertEquals(store.resolve("syd.sqlite"), path)
        assertFalse(File(first).exists())
        assertContentEquals(byteArrayOf(1, 2, 3), File(path).readBytes())
        val second = store.writeTemp(byteArrayOf(4, 5))
        store.replace(second, "syd.sqlite")
        assertContentEquals(byteArrayOf(4, 5), File(path).readBytes())
        assertTrue(store.exists("syd.sqlite"))
        val temp = store.writeTemp(byteArrayOf(9))
        store.delete(temp)
        assertFalse(File(temp).exists())
        store.delete("syd.sqlite")
        assertFalse(store.exists("syd.sqlite"))
    }

    @Test
    fun zstdRoundTrip() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        assertContentEquals(data, AndroidZstdDecompressor.decompress(Zstd.compress(data)))
    }

    @Test
    fun openerReadsAPackFromTheFileStore() = runBlocking {
        val store = AndroidFileStore(File(dir, "packs"))
        val source = File(dir, "built.sqlite")
        JdbcSqliteDriver("jdbc:sqlite:${source.absolutePath}").use { driver ->
            PackWriter.write(driver, content())
            driver.execute(null, "VACUUM", 0)
        }
        val path = store.replace(store.writeTemp(source.readBytes()), "test.sqlite")
        val bytes = File(path).readBytes()
        val tt = AndroidTimetableOpener.open(path)
        try {
            assertEquals("v9", tt.version)
            val deps = tt.departures(DepartureQuery(setOf("A"), setOf("B"), "20261111", 0, 30 * 3600))
            assertEquals(listOf("T1"), deps.map { it.tripId })
        } finally {
            tt.close()
        }
        assertContentEquals(bytes, File(path).readBytes())
    }

    private fun content() = PackContent(
        meta = mapOf(PackMeta.NAME to "test", PackMeta.VERSION to "v9"),
        stops = listOf(
            PackStop(1, "A", null, "Alpha", -33.88, 151.20, null, 0, setOf(Mode.TRAIN), 0),
            PackStop(2, "B", null, "Beta", -33.87, 151.21, null, 0, setOf(Mode.TRAIN), 0),
        ),
        routes = listOf(PackRoute(1, "R1", "A1", "T1", "Train line", 2, Mode.TRAIN, null, null, 1)),
        patterns = listOf(Pattern(1, 1, intArrayOf(1, 2))),
        profiles = listOf(TimeProfile(1, intArrayOf(0, 600), intArrayOf(0, 600))),
        services = listOf(PackService(1, "WD", "20261109", BooleanArray(7) { it < 5 })),
        trips = listOf(PackTrip(1, "T1", 1, 1, 8 * 3600, 1, "Beta", 0, null)),
        transfers = emptyList(),
        shapes = emptyList(),
    )
}
