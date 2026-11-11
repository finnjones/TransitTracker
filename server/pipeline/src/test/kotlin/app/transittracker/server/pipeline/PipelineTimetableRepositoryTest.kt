package app.transittracker.server.pipeline

import app.transittracker.model.DepartureQuery
import app.transittracker.model.Mode
import app.transittracker.model.WallClock
import app.transittracker.server.spi.PackDefinition
import app.transittracker.server.spi.ServerConfig
import com.github.luben.zstd.ZstdInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** A [GtfsSource] serving in-memory bundles with ETags, counting requests. */
class FakeGtfsSource : GtfsSource {
    private val bundles = HashMap<String, Pair<ByteArray, String>>()
    var fetches = 0
    var notModified = 0
    var failNext: IOException? = null
    var lastValidators: CacheValidators? = null

    fun put(path: String, bytes: ByteArray, etag: String) {
        bundles[path] = bytes to etag
    }

    override suspend fun fetch(path: String, validators: CacheValidators?, target: Path): FetchResult {
        fetches++
        lastValidators = validators
        failNext?.let {
            failNext = null
            throw it
        }
        val (bytes, etag) = bundles[path] ?: throw IOException("404 $path")
        if (validators?.etag == etag) {
            notModified++
            return FetchResult.NotModified
        }
        Files.write(target, bytes)
        return FetchResult.Downloaded(CacheValidators(etag, "Wed, 11 Nov 2026 02:00:00 GMT"))
    }
}

class PipelineTimetableRepositoryTest {
    private val dataDir: Path = Files.createTempDirectory("pipeline-repo")
    private val path = "/v1/gtfs/schedule/mini"
    private var now = Instant.parse("2026-11-10T16:00:00Z").toEpochMilli() // 03:00 Wed 11 Nov, Sydney
    private val clock = WallClock { now }
    private val source = FakeGtfsSource()
    private val config = ServerConfig(
        dataDir = dataDir,
        tfnswApiKey = "test-key",
        packs = listOf(PackDefinition("mini-rail", setOf(Mode.TRAIN, Mode.METRO), listOf(path))),
    )
    private val options = PipelineOptions(zstdLevel = 3)
    private val repos = ArrayList<PipelineTimetableRepository>()

    private fun repo(src: GtfsSource? = source, cfg: ServerConfig = config) =
        PipelineTimetableRepository(cfg, src, options, clock).also { repos += it }

    @AfterTest
    fun cleanUp() {
        repos.forEach { it.close() }
        dataDir.toFile().deleteRecursively()
    }

    private val fixtureZip = Fixtures.zipBytes()

    /** The fixture plus one extra weekday trip `T1.N.1200`. */
    private val changedZip = Fixtures.zipBytes { name, text ->
        when (name) {
            "trips.txt" -> text + "T1,WD,T1.N.1200,Chatswood,0,SH_T1_N,1\n"
            "stop_times.txt" -> text + "T1.N.1200,12:00:00,12:00:00,CEN_P1,1,,,\nT1.N.1200,12:20:00,12:20:00,CHA_P1,2,,,\n"
            else -> text
        }
    }

    /** Fixture with a stop time at an unknown stop. */
    private val brokenZip = Fixtures.zipBytes { name, text ->
        if (name == "stop_times.txt") text.replace("T1.N.0700,07:03:00,07:03:00,THL_P1", "T1.N.0700,07:03:00,07:03:00,NOPE") else text
    }

    private fun departuresAtNoon(r: PipelineTimetableRepository) = assertNotNull(r.current.value).departures(
        DepartureQuery(setOf("CEN"), setOf("CHA"), "20261111", 11 * 3600, 13 * 3600),
    ).map { it.tripId }

    @Test
    fun noApiKeyFailsGracefully() = runTest {
        val r = repo(src = null, cfg = config.copy(tfnswApiKey = null))
        val result = r.refresh()
        assertEquals(mapOf("mini-rail" to "TfNSW API key not configured"), result.failed)
        assertTrue(result.rebuilt.isEmpty())
        assertNull(r.current.value)
        assertTrue(r.packs().isEmpty())
    }

    @Test
    fun refreshBuildsPublishesAndSkipsUnchangedInput() = runTest {
        source.put(path, fixtureZip, "\"v1\"")
        val r = repo()
        val first = r.refresh()
        assertEquals(listOf("mini-rail"), first.rebuilt, first.failed.toString())
        val info = r.packs().single()
        assertEquals("20261111T0300", info.version)
        assertEquals("/v1/packs/mini-rail/20261111T0300", info.url)
        assertEquals(listOf(Mode.TRAIN, Mode.METRO), info.modes)
        assertEquals(info.version, r.current.value?.version)

        val file = assertNotNull(r.packFile("mini-rail", info.version))
        assertEquals(info.sha256, PackBuilder.sha256(file))
        assertEquals(info.sizeBytes, Files.size(file))
        val unpacked = dataDir.resolve("check.sqlite")
        ZstdInputStream(Files.newInputStream(file)).use { Files.copy(it, unpacked) }
        assertEquals(info.sqliteSha256, PackBuilder.sha256(unpacked))
        assertNull(r.packFile("mini-rail", "../../etc/passwd"))
        assertNull(r.packFile("mini-rail", "20991231T0000"))

        // Archived raw zip and remembered ETag.
        assertEquals(1, Files.list(config.gtfsArchiveDir.resolve("v1_gtfs_schedule_mini")).use { s -> s.filter { it.name.endsWith(".zip") }.count() })

        now += 1.days.inWholeMilliseconds
        val before = r.current.value
        val second = r.refresh()
        assertEquals(listOf("mini-rail"), second.unchanged)
        assertEquals(1, source.notModified)
        assertEquals("\"v1\"", source.lastValidators?.etag)
        assertSame(before, r.current.value)
        assertEquals(info.version, r.packs().single().version)
    }

    @Test
    fun changedInputRebuildsAndSwapsCurrent() = runTest {
        source.put(path, fixtureZip, "\"v1\"")
        val r = repo()
        r.refresh()
        assertEquals(emptyList(), departuresAtNoon(r))
        val oldVersion = r.packs().single().version

        now += 1.days.inWholeMilliseconds
        source.put(path, changedZip, "\"v2\"")
        val result = r.refresh()
        assertEquals(listOf("mini-rail"), result.rebuilt)
        val newVersion = r.packs().single().version
        assertEquals("20261112T0300", newVersion)
        assertNotEquals(oldVersion, newVersion)
        assertEquals(newVersion, r.current.value?.version)
        assertEquals(listOf("T1.N.1200"), departuresAtNoon(r))
        // Both versions stay downloadable.
        assertNotNull(r.packFile("mini-rail", oldVersion))
    }

    @Test
    fun badGtfsKeepsThePreviousPack() = runTest {
        source.put(path, fixtureZip, "\"v1\"")
        val r = repo()
        r.refresh()
        val good = r.packs().single()
        val goodTimetable = r.current.value

        now += 1.days.inWholeMilliseconds
        source.put(path, brokenZip, "\"bad\"")
        val result = r.refresh()
        assertTrue(result.failed.getValue("mini-rail").contains("unknown_stop"), result.failed.toString())
        assertEquals(good, r.packs().single())
        assertSame(goodTimetable, r.current.value)
        assertTrue(departuresAtNoon(r).isEmpty()) // still queryable

        // The same bad input is not rebuilt again.
        val again = r.refresh()
        assertEquals("GTFS unchanged since the last failed build", again.failed["mini-rail"])

        // A network failure is reported, too.
        source.failNext = IOException("connection reset")
        assertEquals("connection reset", r.refresh().failed["mini-rail"])
        assertEquals(good, r.packs().single())
    }

    @Test
    fun forceRebuildsAndOnlyThreeVersionsAreKept() = runTest {
        source.put(path, fixtureZip, "\"v1\"")
        val r = repo()
        repeat(4) {
            assertEquals(listOf("mini-rail"), r.refresh(force = true).rebuilt)
            now += 10.minutes.inWholeMilliseconds
        }
        val dir = config.packsDir.resolve("mini-rail")
        val manifests = Files.list(dir).use { s -> s.filter { it.name.endsWith(".json") }.map { it.name }.sorted().toList() }
        assertEquals(listOf("20261111T0310.json", "20261111T0320.json", "20261111T0330.json"), manifests)
        assertEquals("20261111T0330", r.packs().single().version)
    }

    @Test
    fun sameMinuteBuildsGetDistinctVersions() = runTest {
        source.put(path, fixtureZip, "\"v1\"")
        val r = repo()
        r.refresh(force = true)
        r.refresh(force = true)
        assertEquals("20261111T0300-2", r.packs().single().version)
    }

    @Test
    fun restartLoadsTheNewestPublishedPack() = runTest {
        source.put(path, fixtureZip, "\"v1\"")
        val first = repo()
        first.refresh()
        val version = first.packs().single().version
        first.close()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val second = repo()
            second.start(scope)
            assertEquals(version, second.current.value?.version)
            assertEquals(version, second.packs().single().version)
            // Unchanged input after a restart is still recognised as unchanged.
            assertEquals(listOf("mini-rail"), second.refresh().unchanged)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun importLocalBuildsFromFilesAndRejectsBadInput() {
        val r = repo(src = null)
        val info = r.importLocal("mini-rail", listOf(Fixtures.miniRail))
        assertEquals("mini-rail", info.name)
        assertEquals(info.version, r.current.value?.version)

        val lr = Fixtures.writeZip(dataDir.resolve("in/lr.zip"), Fixtures.lightRail)
        val combined = r.importLocal("dev", listOf(Fixtures.miniRail, lr))
        assertEquals(listOf(Mode.TRAIN, Mode.METRO, Mode.LIGHT_RAIL), combined.modes)
        assertEquals("${info.version}+${combined.version}", r.current.value?.version)
        assertNotNull(r.current.value?.route("L1"))

        val broken = dataDir.resolve("in/broken.zip")
        Files.write(broken, brokenZip)
        val e = assertFailsWith<PackBuildException> { r.importLocal("mini-rail", listOf(broken)) }
        assertTrue(e.report!!.hasErrors)
        assertEquals(info.version, r.packs().first { it.name == "mini-rail" }.version)
    }

    @Test
    fun archivePrunesOldZipsButKeepsTheNewest() = runTest {
        source.put(path, fixtureZip, "\"v1\"")
        val r = repo()
        r.refresh()
        now += 1.days.inWholeMilliseconds
        source.put(path, changedZip, "\"v2\"")
        r.refresh()
        val folder = config.gtfsArchiveDir.resolve("v1_gtfs_schedule_mini")
        fun zips() = Files.list(folder).use { s -> s.filter { it.name.endsWith(".zip") }.count() }
        assertEquals(2, zips())
        now += 40.days.inWholeMilliseconds
        r.refresh()
        assertEquals(1, zips())
    }

    @Test
    fun nightlyRunIsAtTheConfiguredSydneyHour() {
        val r = repo()
        // 02:00 AEDT -> 03:00 the same day; 03:00 exactly -> next day.
        assertEquals(Instant.parse("2026-11-10T16:00:00Z").toEpochMilli(), r.nextNightlyRunMillis(Instant.parse("2026-11-10T15:00:00Z").toEpochMilli()))
        assertEquals(Instant.parse("2026-11-11T16:00:00Z").toEpochMilli(), r.nextNightlyRunMillis(Instant.parse("2026-11-10T16:00:00Z").toEpochMilli()))
        // Winter (AEST, UTC+10).
        assertEquals(Instant.parse("2026-06-30T17:00:00Z").toEpochMilli(), r.nextNightlyRunMillis(Instant.parse("2026-06-30T12:00:00Z").toEpochMilli()))
    }
}
