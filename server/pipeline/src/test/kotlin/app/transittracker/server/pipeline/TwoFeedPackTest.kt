package app.transittracker.server.pipeline

import app.transittracker.model.DepartureQuery
import app.transittracker.model.Mode
import app.transittracker.model.WallClock
import app.transittracker.server.spi.PackDefinition
import app.transittracker.server.spi.ServerConfig
import app.transittracker.timetable.JvmTimetables
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sydney Trains + Metro style inputs, as GTFS text files, through the real loader, merger,
 * validator, pack builder and repository: the shared Epping and Central stations differ the way
 * the real TfNSW feeds do, and both feeds use service id "1" and shape id "1" for different
 * things. The published pack must contain both operators' services on the right days.
 */
class TwoFeedPackTest {
    private val dir: Path = Files.createTempDirectory("two-feed")
    private val now = Instant.parse("2026-11-10T16:00:00Z").toEpochMilli() // 03:00 Wed 11 Nov, Sydney
    private val repos = ArrayList<PipelineTimetableRepository>()

    @AfterTest
    fun cleanUp() {
        repos.forEach { it.close() }
        dir.toFile().deleteRecursively()
    }

    private val stopsHeader = "stop_id,stop_name,stop_lat,stop_lon,location_type,parent_station,wheelchair_boarding,platform_code,stop_timezone\n"
    private val calendarHeader = "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"

    private val trainFiles = mapOf(
        "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\nSydneyTrains,Sydney Trains,https://transportnsw.info,Australia/Sydney\n",
        "stops.txt" to stopsHeader +
            "212110,Epping Station,-33.7728414535,151.082176647,1,,1,,Australia/Sydney\n" +
            "2121131,Epping Station Platform 1,-33.7726,151.0820,0,212110,1,1,\n" +
            "200060,Central Station,-33.884084,151.206292,1,,1,,Australia/Sydney\n" +
            "2000331,Central Station Platform 16,-33.8835,151.2063,0,200060,1,16,\n" +
            "207720,Hornsby Station,-33.7024,151.0990,1,,1,,Australia/Sydney\n" +
            "2077201,Hornsby Station Platform 1,-33.7025,151.0991,0,207720,1,1,\n",
        "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type,route_color\nT9,SydneyTrains,T9,Northern Line,2,D11F2F\n",
        "trips.txt" to "route_id,service_id,trip_id,shape_id,trip_headsign\nT9,1,T9.0800,1,Central\n",
        "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
            "T9.0800,08:00:00,08:00:00,2077201,1\nT9.0800,08:12:00,08:13:00,2121131,2\nT9.0800,08:40:00,08:40:00,2000331,3\n",
        "calendar.txt" to calendarHeader + "1,1,1,1,1,1,0,0,20260101,20271231\n",
        "shapes.txt" to "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n1,-33.7024,151.0990,1\n1,-33.7728,151.0822,2\n1,-33.8840,151.2063,3\n",
    )

    private fun metroFiles(eppingName: String = "Epping Station", centralLat: String = "-33.883848") = mapOf(
        "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\nSMNW,Sydney Metro,http://transportnsw.info,Australia/Sydney\n",
        "stops.txt" to stopsHeader +
            "212110,$eppingName,-33.772841,151.082177,1,,1,,\n" +
            "2121201,Epping Station Platform 3,-33.7731,151.0824,0,212110,1,3,\n" +
            "200060,Central Station,$centralLat,151.205738,1,,0,,\n" +
            "2000601,Central Station Metro Platform 1,-33.8839,151.2058,0,200060,1,1,\n" +
            "2155384,Tallawong Station,-33.69163,150.906022,1,,1,,\n" +
            "21553841,Tallawong Station Platform 1,-33.6917,150.9061,0,2155384,1,1,\n",
        "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type,route_color\nSMNW_M1,SMNW,M1,Metro North West & Bankstown Line,401,168388\n",
        "trips.txt" to "route_id,service_id,trip_id,shape_id,trip_headsign\nSMNW_M1,1,0126-001-101-002:1000,1,Sydenham\n",
        "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
            "0126-001-101-002:1000,09:00:00,09:00:00,21553841,1\n0126-001-101-002:1000,09:20:00,09:21:00,2121201,2\n0126-001-101-002:1000,09:45:00,09:45:00,2000601,3\n",
        // Same service id "1" as the trains feed, but weekends only.
        "calendar.txt" to calendarHeader + "1,0,0,0,0,0,1,1,20260101,20271231\n",
        // Same shape id "1" as the trains feed, different line.
        "shapes.txt" to "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n1,-33.6916,150.9060,0\n1,-33.7728,151.0822,1\n1,-33.8838,151.2057,2\n",
    )

    private fun zips(metro: Map<String, String> = metroFiles()): List<Path> = listOf(
        Fixtures.writeZip(dir.resolve("in/sydneytrains.zip"), trainFiles),
        Fixtures.writeZip(dir.resolve("in/metro.zip"), metro),
    )

    private fun repo(): PipelineTimetableRepository {
        val config = ServerConfig(
            dataDir = dir.resolve("data"),
            packs = listOf(PackDefinition("syd-rail", setOf(Mode.TRAIN, Mode.METRO), listOf("/trains", "/metro"))),
        )
        return PipelineTimetableRepository(config, source = null, options = PipelineOptions(zstdLevel = 3), clock = WallClock { now }).also { repos += it }
    }

    @Test
    fun publishedPackHasBothOperatorsOnTheirOwnDays() {
        val info = repo().let { r -> r.importLocal("syd-rail", zips()).also { assertNotNull(r.current.value) } }
        assertEquals(listOf(Mode.TRAIN, Mode.METRO), info.modes)

        val sqlite = dir.resolve("data/packs").toFile().walk().first { it.name.endsWith(".sqlite") }.toPath()
        JvmTimetables.open(sqlite).use { pack ->
            assertEquals("Epping Station", pack.stop("212110")?.name)
            assertEquals(-33.772841, pack.stop("212110")!!.position.lat, 1e-6) // packs store 6 decimal places
            assertNotNull(pack.route("T9"))
            assertNotNull(pack.route("SMNW_M1"))

            // Wednesday: trains (weekday service "1") run, Metro (its own "1", weekends) doesn't.
            assertTrue(pack.runsOn("T9.0800", "20261111"))
            assertFalse(pack.runsOn("0126-001-101-002:1000", "20261111"))
            // Saturday: the other way round.
            assertFalse(pack.runsOn("T9.0800", "20261114"))
            assertTrue(pack.runsOn("0126-001-101-002:1000", "20261114"))

            fun departures(from: String, to: String, date: String) =
                pack.departures(DepartureQuery(setOf(from), setOf(to), date, fromSecs = 0, toSecs = 30 * 3600)).map { it.tripId }
            assertEquals(listOf("T9.0800"), departures("207720", "212110", "20261111"))
            assertEquals(listOf("0126-001-101-002:1000"), departures("2155384", "212110", "20261114"))
            // Both operators serve the merged Epping and Central stations.
            assertEquals(listOf("T9.0800"), departures("212110", "200060", "20261111"))
            assertEquals(listOf("0126-001-101-002:1000"), departures("212110", "200060", "20261114"))

            // Each operator keeps its own route line.
            assertEquals(-33.7024, pack.shape(pack.trip("T9.0800")!!.shapeId!!)!!.first().lat, 1e-3)
            assertEquals(-33.6916, pack.shape(pack.trip("0126-001-101-002:1000")!!.shapeId!!)!!.first().lat, 1e-3)
        }
    }

    @Test
    fun buildingTheMergedPackTwiceGivesIdenticalBytes() {
        fun build(out: String): Path {
            val feeds = zips().map { p -> GtfsBundle.open(p).use { GtfsLoader.load(it, ValidationReport.Builder()) } }
            val request = PackBuildRequest("syd-rail", "v1", LocalDate.of(2026, 11, 11), modes = setOf(Mode.TRAIN, Mode.METRO))
            val built = PackBuilder.buildContent(GtfsMerger.merge(feeds), request, PackIdMaps())
            return PackBuilder.writeArtifacts(built, request, dir.resolve(out), 3).compressed
        }
        assertContentEquals(Files.readAllBytes(build("a")), Files.readAllBytes(build("b")))
    }

    @Test
    fun checkCommandReportsReconciliationAndServicesFromBothInputs() {
        val result = GtfsCheck.run("syd-rail", zips(), setOf(Mode.TRAIN, Mode.METRO), PipelineOptions(zstdLevel = 3), LocalDate.of(2026, 11, 11), dir.resolve("work"))
        val text = result.lines.joinToString("\n")
        assertTrue(result.ok, text)
        assertTrue("reconciled stop '212110'" in text, text)
        assertTrue("reconciled stop '200060'" in text, text)
        assertTrue("renamed service '1'" in text, text)
        assertTrue("renamed shape '1'" in text, text)
        assertTrue("sydneytrains.zip: 1 of 1 trips in the pack window, 1 run on 20261111" in text, text)
        assertTrue("metro.zip: 1 of 1 trips in the pack window, 0 run on 20261111" in text, text)
        assertTrue(text.endsWith("OK: this input would build and publish."), text)
    }

    @Test
    fun genuineConflictsAreAllListedAndNothingIsPublished() {
        val bad = zips(metroFiles(eppingName = "Epping Interchange", centralLat = "-33.70"))
        val check = GtfsCheck.run("syd-rail", bad, null, PipelineOptions(zstdLevel = 3), LocalDate.of(2026, 11, 11), dir.resolve("work"))
        val text = check.lines.joinToString("\n")
        assertFalse(check.ok)
        assertTrue("FAILED: 2 conflicting id(s)" in text, text)
        assertTrue("stop '212110' (sydneytrains.zip vs metro.zip): name 'Epping Station' vs 'Epping Interchange'" in text, text)
        assertTrue("stop '200060'" in text && "apart (limit 300 m)" in text, text)

        val r = repo()
        val e = runCatching { r.importLocal("syd-rail", bad) }.exceptionOrNull()
        assertTrue(e is PackBuildException, e.toString())
        assertEquals(2, e.report!!.errors.count { it.code == "merge_conflict" })
        assertTrue(r.packs().isEmpty())
    }
}
