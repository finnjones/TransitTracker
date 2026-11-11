package app.transittracker.server.pipeline

import app.transittracker.model.Mode
import app.transittracker.timetable.JvmTimetables
import app.transittracker.timetable.PackMeta
import com.github.luben.zstd.ZstdInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PackBuilderTest {
    private val tmp: Path = Files.createTempDirectory("pack-builder")

    @AfterTest
    fun cleanUp() {
        tmp.toFile().deleteRecursively()
    }

    private val request = PackBuildRequest(
        name = "mini-rail",
        version = "20261111T0300",
        buildDate = LocalDate.of(2026, 11, 11),
        modes = setOf(Mode.TRAIN, Mode.METRO),
    )

    private fun build(feed: GtfsFeed, dir: String, maps: PackIdMaps = PackIdMaps(), req: PackBuildRequest = request): Pair<PackArtifacts, BuiltContent> {
        val built = PackBuilder.buildContent(feed, req, maps)
        return PackBuilder.writeArtifacts(built, req, tmp.resolve(dir)) to built
    }

    @Test
    fun buildingTwiceGivesIdenticalBytes() {
        val (a, _) = build(Fixtures.load(Fixtures.miniRail).first, "a")
        val (b, _) = build(Fixtures.load(Fixtures.miniRail).first, "b")
        assertContentEquals(Files.readAllBytes(a.sqlite), Files.readAllBytes(b.sqlite))
        assertContentEquals(Files.readAllBytes(a.compressed), Files.readAllBytes(b.compressed))
        assertEquals(a.info, b.info)
        println("mini-rail fixture pack: ${a.info.sizeBytes} bytes zstd, ${Files.size(a.sqlite)} bytes SQLite")
    }

    @Test
    fun packInfoAndFilesAgree() {
        val (art, built) = build(Fixtures.load(Fixtures.miniRail).first, "info")
        val info = art.info
        assertEquals("/v1/packs/mini-rail/20261111T0300", info.url)
        assertEquals(listOf(Mode.TRAIN, Mode.METRO), info.modes)
        assertEquals("20261110", info.validFrom)
        assertEquals("20270119", info.validTo)
        assertEquals(Files.size(art.compressed), info.sizeBytes)
        assertEquals(PackBuilder.sha256(art.compressed), info.sha256)
        val unpacked = tmp.resolve("unpacked.sqlite")
        ZstdInputStream(Files.newInputStream(art.compressed)).use { Files.copy(it, unpacked) }
        assertEquals(info.sqliteSha256, PackBuilder.sha256(unpacked))
        assertEquals(4096, Files.readAllBytes(unpacked).let { ((it[16].toInt() and 0xFF) shl 8) or (it[17].toInt() and 0xFF) })
        JvmTimetables.open(unpacked).use { pack ->
            assertEquals("20261111T0300", pack.version)
            assertEquals("mini-rail", pack.name)
            assertEquals("1", pack.meta[PackMeta.SCHEMA_VERSION])
            assertEquals("1200", pack.meta[PackMeta.MAX_TRIP_SECS])
        }
        assertEquals(18, built.stats["trips"])
        assertEquals(22, built.stats["stops"])
        // T1 north, T1 north restricted, T1 south, M1 north, M1 south.
        assertEquals(5, built.stats["patterns"])
        assertEquals(8, built.stats["transfers"])
    }

    @Test
    fun integerIdsAreStableAcrossVersions() {
        val feed = Fixtures.load(Fixtures.miniRail).first
        val (_, first) = build(feed, "v1")
        // Next version adds a stop that sorts first; existing ids must not move.
        val zip = Fixtures.writeZip(tmp.resolve("v2.zip")) { name, text ->
            when (name) {
                "stops.txt" -> text + "AAA_P1,,Aardvark Platform,-33.80,151.18,0,,,\n"
                "stop_times.txt" -> text.replace("T1.S.1730,17:50:00,17:50:00,CEN_P2,5,,,", "T1.S.1730,17:50:00,17:50:00,CEN_P2,5,,,\nT1.S.1730,17:55:00,17:55:00,AAA_P1,6,,,")
                else -> text
            }
        }
        val (_, second) = build(Fixtures.load(zip).first, "v2", maps = first.idMaps, req = request.copy(version = "v2"))
        first.idMaps.stops.forEach { (gtfs, id) -> assertEquals(id, second.idMaps.stops[gtfs], gtfs) }
        assertEquals(first.idMaps.stops.values.max() + 1, second.idMaps.stops["AAA_P1"])
    }

    @Test
    fun separateBundlesMergeIntoOnePack() {
        val lr = Fixtures.writeZip(tmp.resolve("lr.zip"), Fixtures.lightRail)
        val feeds = listOf(Fixtures.load(Fixtures.miniRail).first, Fixtures.load(lr).first)
        val merged = GtfsMerger.merge(feeds)
        val report = ValidationReport.Builder()
        GtfsValidator.validate(merged, report)
        assertTrue(!report.hasErrors, report.build().summary())
        val (art, built) = build(merged, "merged", req = request.copy(modes = null))
        assertEquals(19, built.stats["trips"])
        assertEquals(listOf(Mode.TRAIN, Mode.METRO, Mode.LIGHT_RAIL), art.info.modes)
        JvmTimetables.open(art.sqlite).use { pack ->
            assertEquals(Mode.LIGHT_RAIL, pack.route("L1")?.mode)
            assertTrue(pack.meta.getValue(PackMeta.SOURCES).contains("lr.zip="))
        }
    }

    @Test
    fun identicalDuplicatesMergeButConflictsFailLoudly() {
        val feed = Fixtures.load(Fixtures.miniRail).first
        val copy = Fixtures.load(Fixtures.writeZip(tmp.resolve("copy.zip"))).first
        assertEquals(feed.stops.size, GtfsMerger.merge(listOf(feed, copy)).stops.size)

        val renamed = Fixtures.load(
            Fixtures.writeZip(tmp.resolve("renamed.zip")) { name, text ->
                if (name == "stops.txt") text.replace("Town Hall Station,", "Town Hall,") else text
            },
        ).first
        val e = assertFailsWith<ConflictingIdException> { GtfsMerger.merge(listOf(feed, renamed)) }
        assertEquals("stop", e.kind)
        assertEquals("THL", e.id)
    }

    @Test
    fun windowExcludesOutOfRangeServicesAndFailsWhenEmpty() {
        val feed = Fixtures.load(Fixtures.miniRail).first
        val (_, built) = build(feed, "late", req = request.copy(buildDate = LocalDate.of(2027, 12, 30), version = "late"))
        assertEquals("20271231", built.validTo.toString().replace("-", ""))
        assertNotEquals(0, built.stats["trips"])
        assertFailsWith<PackBuildException> {
            PackBuilder.buildContent(feed, request.copy(buildDate = LocalDate.of(2028, 3, 1)), PackIdMaps())
        }
    }
}
