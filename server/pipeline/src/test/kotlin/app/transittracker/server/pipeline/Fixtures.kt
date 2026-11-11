package app.transittracker.server.pipeline

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.name

/** Access to `testdata/gtfs` and helpers to derive modified GTFS bundles from it. */
object Fixtures {
    val testdata: Path = Paths.get(System.getProperty("tt.testdata") ?: "../../testdata")

    /** The committed synthetic fixture (see testdata/gtfs/README.md). */
    val miniRail: Path = testdata.resolve("gtfs/mini-rail")

    /** Fixture files as name -> text. */
    fun files(dir: Path = miniRail): Map<String, String> =
        Files.list(dir).use { s -> s.filter { it.name.endsWith(".txt") }.sorted().toList() }.associate { it.name to Files.readString(it) }

    /**
     * Zips [files] (default: the fixture) after applying [edit] to each file's text (return
     * null to drop the file). Entries are written in name order with a fixed timestamp.
     */
    fun zipBytes(files: Map<String, String> = files(), edit: (name: String, text: String) -> String? = { _, t -> t }): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in files.toSortedMap()) {
                val edited = edit(name, text) ?: continue
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(edited.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun writeZip(target: Path, files: Map<String, String> = files(), edit: (String, String) -> String? = { _, t -> t }): Path {
        Files.createDirectories(target.toAbsolutePath().parent)
        Files.write(target, zipBytes(files, edit))
        return target
    }

    /** Loads a bundle, returning the feed and the report of loading plus validation. */
    fun load(path: Path, today: LocalDate? = null): Pair<GtfsFeed, ValidationReport> {
        val report = ValidationReport.Builder()
        val feed = GtfsBundle.open(path).use { GtfsLoader.load(it, report) }
        GtfsValidator.validate(feed, report, today)
        return feed to report.build()
    }

    /** A tiny second bundle (one light-rail line, its own agency and calendar) for merge tests. */
    val lightRail: Map<String, String> = mapOf(
        "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\nLR,Mini Light Rail,https://example.invalid/lr,Australia/Sydney\n",
        "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon,location_type,parent_station\n" +
            "LR_CEN,Central Light Rail,-33.8830,151.2060,0,\nLR_HAY,Haymarket Light Rail,-33.8800,151.2040,0,\n",
        "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type\nL1,LR,L1,Dulwich Hill Line,900\n",
        "trips.txt" to "route_id,service_id,trip_id\nL1,LR_DAILY,L1.0800\n",
        "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
            "L1.0800,08:00:00,08:00:00,LR_CEN,1\nL1.0800,08:04:00,08:04:00,LR_HAY,2\n",
        "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
            "LR_DAILY,1,1,1,1,1,1,1,20260101,20271231\n",
    )
}
