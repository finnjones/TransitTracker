package app.transittracker.server.pipeline

import app.transittracker.model.Mode
import app.transittracker.model.ServiceTime
import app.transittracker.timetable.JvmTimetables
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Dry run of a pack build: loads the given GTFS inputs, merges them, validates and builds the
 * pack in a scratch directory, then reports what happened — without publishing anything.
 * Used by `tt-admin check-gtfs` to test real feeds (e.g. the archived TfNSW zips) on the server.
 */
object GtfsCheck {
    class Result(val ok: Boolean, val lines: List<String>)

    fun run(
        packName: String,
        inputs: List<Path>,
        modes: Set<Mode>?,
        options: PipelineOptions,
        today: LocalDate,
        workDir: Path,
    ): Result {
        val out = ArrayList<String>()
        var ok = true
        val report = ValidationReport.Builder()

        out += "== Inputs"
        val feeds = inputs.map { path ->
            val before = report.build()
            val feed = GtfsBundle.open(path).use { GtfsLoader.load(it, report) }
            val after = report.build()
            val routeTypes = feed.routes.groupingBy { it.type }.eachCount().toSortedMap()
            out += "${path.fileName}: version ${feed.sources.firstOrNull()?.second ?: "-"}; " +
                "${feed.agencies.size} agencies, ${feed.stops.size} stops (${feed.stops.count { it.locationType == 1 }} stations), " +
                "${feed.routes.size} routes (route_type ${routeTypes.entries.joinToString { "${it.key}: ${it.value}" }}), " +
                "${feed.trips.size} trips, ${feed.serviceIds.size} services, ${feed.shapes.size} shapes, ${feed.transfers.size} transfers; " +
                "loader issues: ${after.errorCount - before.errorCount} errors, ${after.warningCount - before.warningCount} warnings"
            feed
        }

        out += ""
        out += "== Merge"
        val merged = try {
            GtfsMerger.mergeWithReport(feeds)
        } catch (e: ConflictingIdException) {
            out += "FAILED: ${e.conflicts.size} conflicting id(s):"
            e.conflicts.forEach { out += "  $it" }
            return Result(false, out)
        }
        if (feeds.size == 1) {
            out += "Single input; nothing to merge."
        } else {
            out += "Ids shared by several inputs: ${merged.report.sharedIds.entries.joinToString { "${it.key} ${it.value}" }.ifEmpty { "none" }}"
            out += "Reconciled: ${merged.report.reconciled.size}, renamed: ${merged.report.renamed.size}"
            merged.report.notes.take(200).forEach { out += "  $it" }
            if (merged.report.notes.size > 200) out += "  ... and ${merged.report.notes.size - 200} more"
        }
        merged.report.recordIn(report)

        out += ""
        out += "== Validation"
        GtfsValidator.validate(merged.feed, report, today)
        val validation = report.build()
        out += validation.summary().ifEmpty { "No issues." }
        if (validation.hasErrors) {
            ok = false
            validation.errors.take(30).forEach { out += "  $it" }
            out += "FAILED: the server would refuse to publish this pack."
            return Result(false, out)
        }

        out += ""
        out += "== Build (not published)"
        val request = PackBuildRequest(
            name = packName,
            version = "check",
            buildDate = today,
            windowDays = options.windowDays,
            lookbackDays = options.lookbackDays,
            modes = modes,
            shapeToleranceMeters = options.shapeToleranceMeters,
        )
        val built = try {
            PackBuilder.buildContent(merged.feed, request, PackIdMaps())
        } catch (e: PackBuildException) {
            out += "FAILED: ${e.message}"
            return Result(false, out)
        }
        Files.createDirectories(workDir)
        val artifacts = PackBuilder.writeArtifacts(built, request, workDir, options.zstdLevel)
        out += "Valid ${built.validFrom} to ${built.validTo}; modes ${built.modes.joinToString()}; ${built.stats.entries.joinToString { "${it.key} ${it.value}" }}"
        out += "Size: ${artifacts.info.sizeBytes} bytes compressed, ${Files.size(artifacts.sqlite)} bytes SQLite"

        val serviceDate = ServiceTime.formatDate(kotlinx.datetime.LocalDate(today.year, today.monthValue, today.dayOfMonth))
        JvmTimetables.open(artifacts.sqlite).use { pack ->
            out += ""
            out += "== Services from each input in the pack (service date $serviceDate)"
            inputs.zip(feeds).forEach { (path, feed) ->
                val inPack = feed.trips.count { pack.trip(it.id) != null }
                val today = feed.trips.count { pack.trip(it.id) != null && pack.runsOn(it.id, serviceDate) }
                val routes = feed.routes.count { pack.route(it.id) != null }
                out += "${path.fileName}: $inPack of ${feed.trips.size} trips in the pack window, $today run on $serviceDate; $routes of ${feed.routes.size} routes"
                if (inPack == 0) {
                    ok = false
                    out += "  FAILED: no trips from this input made it into the pack"
                }
            }
        }
        out += ""
        out += if (ok) "OK: this input would build and publish." else "FAILED."
        return Result(ok, out)
    }
}
