package app.transittracker.server.pipeline

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Reads a [GtfsBundle] into a [GtfsFeed], streaming each file. File-level and row-level
 * problems (missing required files or columns, unparsable values, duplicate ids) are added to
 * the report; bad rows are skipped so that one pass reports as much as possible.
 * Cross-file checks are done afterwards by [GtfsValidator].
 */
object GtfsLoader {
    private val requiredFiles = listOf("agency.txt", "stops.txt", "routes.txt", "trips.txt", "stop_times.txt")
    private val dateFormat = DateTimeFormatter.BASIC_ISO_DATE
    private val timeRegex = Regex("""^(\d{1,3}):([0-5]\d):([0-5]\d)$""")

    fun load(bundle: GtfsBundle, report: ValidationReport.Builder): GtfsFeed {
        val missing = requiredFiles.filter { it !in bundle.files }
        missing.forEach { report.error("missing_required_file", "Required file $it is missing", file = it) }
        if ("calendar.txt" !in bundle.files && "calendar_dates.txt" !in bundle.files) {
            report.error("missing_calendar", "Neither calendar.txt nor calendar_dates.txt is present")
        }
        val interner = HashMap<String, String>()
        fun intern(s: String) = interner.getOrPut(s) { s }

        val agencies = ArrayList<GtfsAgency>()
        read(bundle, "agency.txt", report, listOf("agency_name", "agency_timezone")) { row ->
            agencies += GtfsAgency(row["agency_id"], row["agency_name"]!!, row["agency_url"], row["agency_timezone"]!!)
        }

        val stops = LinkedHashMap<String, GtfsStop>()
        read(bundle, "stops.txt", report, listOf("stop_id")) { row ->
            val id = intern(row["stop_id"]!!)
            val locationType = row.int("location_type", report) ?: 0
            val needsPosition = locationType in 0..2
            val lat = row.double("stop_lat", report)
            val lon = row.double("stop_lon", report)
            val name = row["stop_name"]
            if (needsPosition && (lat == null || lon == null)) {
                report.error(row, "missing_stop_position", "Stop $id has no stop_lat/stop_lon")
                return@read
            }
            if (needsPosition && name == null) report.warning(row, "missing_stop_name", "Stop $id has no stop_name")
            if (lat != null && lon != null && (lat !in -90.0..90.0 || lon !in -180.0..180.0)) {
                report.error(row, "bad_stop_position", "Stop $id position $lat,$lon is out of range")
                return@read
            }
            val stop = GtfsStop(
                id = id,
                name = name ?: id,
                lat = lat ?: 0.0,
                lon = lon ?: 0.0,
                locationType = locationType,
                parentStation = row["parent_station"]?.let(::intern),
                platformCode = row["platform_code"],
                wheelchairBoarding = row.int("wheelchair_boarding", report) ?: 0,
            )
            if (stops.putIfAbsent(id, stop) != null) report.error(row, "duplicate_stop_id", "Duplicate stop_id $id")
        }

        val routes = LinkedHashMap<String, GtfsRoute>()
        read(bundle, "routes.txt", report, listOf("route_id", "route_type")) { row ->
            val id = intern(row["route_id"]!!)
            val type = row.int("route_type", report) ?: return@read
            val short = row["route_short_name"]
            val long = row["route_long_name"]
            if (short == null && long == null) report.warning(row, "missing_route_name", "Route $id has no name")
            val route = GtfsRoute(
                id = id,
                agencyId = row["agency_id"],
                shortName = short ?: "",
                longName = long ?: "",
                type = type,
                color = row["route_color"]?.uppercase(),
                textColor = row["route_text_color"]?.uppercase(),
                sortOrder = row.int("route_sort_order", report),
            )
            if (routes.putIfAbsent(id, route) != null) report.error(row, "duplicate_route_id", "Duplicate route_id $id")
        }

        val trips = LinkedHashMap<String, GtfsTrip>()
        read(bundle, "trips.txt", report, listOf("route_id", "service_id", "trip_id")) { row ->
            val id = intern(row["trip_id"]!!)
            val trip = GtfsTrip(
                id = id,
                routeId = intern(row["route_id"]!!),
                serviceId = intern(row["service_id"]!!),
                headsign = row["trip_headsign"],
                directionId = row.int("direction_id", report),
                shapeId = row["shape_id"]?.let(::intern),
                wheelchairAccessible = row.int("wheelchair_accessible", report) ?: 0,
                bikesAllowed = row.int("bikes_allowed", report) ?: 0,
            )
            if (trips.putIfAbsent(id, trip) != null) report.error(row, "duplicate_trip_id", "Duplicate trip_id $id")
        }

        val stopTimes = HashMap<String, MutableList<GtfsStopTime>>()
        read(bundle, "stop_times.txt", report, listOf("trip_id", "stop_id", "stop_sequence", "arrival_time", "departure_time")) { row ->
            val tripId = intern(row["trip_id"]!!)
            val seq = row.int("stop_sequence", report) ?: return@read
            val arr = row.time("arrival_time", report)
            val dep = row.time("departure_time", report)
            stopTimes.getOrPut(tripId) { ArrayList() } += GtfsStopTime(
                tripId = tripId,
                stopId = intern(row["stop_id"]!!),
                sequence = seq,
                arrival = arr ?: dep,
                departure = dep ?: arr,
                pickupType = row.int("pickup_type", report) ?: 0,
                dropOffType = row.int("drop_off_type", report) ?: 0,
            )
        }
        stopTimes.values.forEach { list -> list.sortBy { it.sequence } }

        val calendars = ArrayList<GtfsCalendar>()
        val dayColumns = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        read(bundle, "calendar.txt", report, listOf("service_id", "start_date", "end_date") + dayColumns, optional = true) { row ->
            val start = row.date("start_date", report) ?: return@read
            val end = row.date("end_date", report) ?: return@read
            val days = dayColumns.map { col ->
                when (row[col]) {
                    "1" -> true
                    "0" -> false
                    else -> {
                        report.error(row, "bad_calendar_day", "$col must be 0 or 1")
                        return@read
                    }
                }
            }
            calendars += GtfsCalendar(intern(row["service_id"]!!), days, start, end)
        }

        val calendarDates = ArrayList<GtfsCalendarDate>()
        read(bundle, "calendar_dates.txt", report, listOf("service_id", "date", "exception_type"), optional = true) { row ->
            val date = row.date("date", report) ?: return@read
            val type = row.int("exception_type", report) ?: return@read
            calendarDates += GtfsCalendarDate(intern(row["service_id"]!!), date, type)
        }

        val shapes = HashMap<String, MutableList<GtfsShapePoint>>()
        read(bundle, "shapes.txt", report, listOf("shape_id", "shape_pt_lat", "shape_pt_lon", "shape_pt_sequence"), optional = true) { row ->
            val lat = row.double("shape_pt_lat", report) ?: return@read
            val lon = row.double("shape_pt_lon", report) ?: return@read
            val seq = row.int("shape_pt_sequence", report) ?: return@read
            shapes.getOrPut(intern(row["shape_id"]!!)) { ArrayList() } += GtfsShapePoint(lat, lon, seq)
        }
        shapes.values.forEach { list -> list.sortBy { it.sequence } }

        val transfers = ArrayList<GtfsTransfer>()
        read(bundle, "transfers.txt", report, listOf("from_stop_id", "to_stop_id"), optional = true) { row ->
            transfers += GtfsTransfer(
                fromStopId = intern(row["from_stop_id"]!!),
                toStopId = intern(row["to_stop_id"]!!),
                transferType = row.int("transfer_type", report) ?: 0,
                minTransferTime = row.int("min_transfer_time", report),
            )
        }

        var feedInfo: GtfsFeedInfo? = null
        read(bundle, "feed_info.txt", report, emptyList(), optional = true) { row ->
            if (feedInfo == null) {
                feedInfo = GtfsFeedInfo(
                    row["feed_publisher_name"],
                    row["feed_version"],
                    row.date("feed_start_date", report),
                    row.date("feed_end_date", report),
                )
            }
        }

        return GtfsFeed(
            sources = listOf(bundle.name to feedInfo?.version),
            agencies = agencies,
            stops = stops.values.toList(),
            routes = routes.values.toList(),
            trips = trips.values.toList(),
            stopTimes = stopTimes,
            calendars = calendars,
            calendarDates = calendarDates,
            shapes = shapes,
            transfers = transfers,
        )
    }

    private inline fun read(
        bundle: GtfsBundle,
        file: String,
        report: ValidationReport.Builder,
        requiredColumns: List<String>,
        optional: Boolean = false,
        crossinline onRow: (GtfsRow) -> Unit,
    ) {
        val input = bundle.open(file) ?: return
        GtfsTable(file, InputStreamReader(input, StandardCharsets.UTF_8)).use { table ->
            if (table.columns.isEmpty()) {
                if (!optional) report.error("empty_file", "$file is empty", file = file)
                return
            }
            val missing = requiredColumns.filterNot { table.has(it) }
            if (missing.isNotEmpty()) {
                report.error("missing_required_column", "$file lacks column(s) ${missing.joinToString()}", file = file)
                return
            }
            try {
                table.forEachRow { row ->
                    val absent = requiredColumns.firstOrNull { row[it] == null && it != "arrival_time" && it != "departure_time" }
                    if (absent != null) {
                        report.error(row, "missing_required_field", "Empty $absent")
                    } else {
                        onRow(row)
                    }
                }
            } catch (e: CsvException) {
                report.error("csv_syntax", e.message ?: "CSV syntax error", file = file)
            }
        }
    }

    private fun GtfsRow.int(column: String, report: ValidationReport.Builder): Int? {
        val text = this[column] ?: return null
        return text.toIntOrNull() ?: run {
            report.error(this, "bad_integer", "$column '$text' is not an integer")
            null
        }
    }

    private fun GtfsRow.double(column: String, report: ValidationReport.Builder): Double? {
        val text = this[column] ?: return null
        return text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: run {
            report.error(this, "bad_number", "$column '$text' is not a number")
            null
        }
    }

    private fun GtfsRow.date(column: String, report: ValidationReport.Builder): LocalDate? {
        val text = this[column] ?: return null
        return try {
            LocalDate.parse(text, dateFormat)
        } catch (e: DateTimeParseException) {
            report.error(this, "bad_date", "$column '$text' is not a YYYYMMDD date")
            null
        }
    }

    private fun GtfsRow.time(column: String, report: ValidationReport.Builder): Int? {
        val text = this[column] ?: return null
        val m = timeRegex.matchEntire(text) ?: run {
            report.error(this, "bad_time", "$column '$text' is not an H:MM:SS time")
            return null
        }
        val (h, min, s) = m.destructured
        return h.toInt() * 3600 + min.toInt() * 60 + s.toInt()
    }
}
