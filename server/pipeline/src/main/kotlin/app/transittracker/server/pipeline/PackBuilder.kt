package app.transittracker.server.pipeline

import app.transittracker.model.ApiPaths
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.PackInfo
import app.transittracker.timetable.JvmTimetables
import app.transittracker.timetable.ModeBits
import app.transittracker.timetable.PackContent
import app.transittracker.timetable.PackMeta
import app.transittracker.timetable.PackRoute
import app.transittracker.timetable.PackService
import app.transittracker.timetable.PackShape
import app.transittracker.timetable.PackStop
import app.transittracker.timetable.PackTransfer
import app.transittracker.timetable.PackTrip
import app.transittracker.timetable.Pattern
import app.transittracker.timetable.Polyline
import app.transittracker.timetable.TimeProfile
import com.github.luben.zstd.ZstdOutputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * What to build.
 *
 * @property buildDate Sydney date of the build; the service window starts [lookbackDays] before
 *   it (so after-midnight trips of the previous service day are covered) and runs [windowDays]
 *   from it.
 * @property modes modes advertised in [PackInfo]; null derives them from the routes.
 * @property sourceLabels one line per input for the `sources` meta entry; null derives them
 *   from the bundle names and `feed_version`s.
 */
data class PackBuildRequest(
    val name: String,
    val version: String,
    val buildDate: LocalDate,
    val windowDays: Int = 70,
    val lookbackDays: Int = 1,
    val modes: Set<Mode>? = null,
    val shapeToleranceMeters: Double = 10.0,
    val sourceLabels: List<String>? = null,
) {
    init {
        require(windowDays > 0 && lookbackDays >= 0) { "Bad pack window" }
    }
}

/** The result of [PackBuilder.buildContent]: rows to write plus what goes into [PackInfo]. */
class BuiltContent(
    val content: PackContent,
    /** Id maps including any ids assigned by this build; persist them once the pack is published. */
    val idMaps: PackIdMaps,
    val validFrom: LocalDate,
    val validTo: LocalDate,
    val modes: List<Mode>,
    val stats: Map<String, Int>,
)

/** Files of a built pack plus its [PackInfo]. */
class PackArtifacts(val sqlite: Path, val compressed: Path, val info: PackInfo)

/**
 * Turns a validated [GtfsFeed] into pack rows (plan section 8.2). Pure and deterministic: the
 * same feed, request and id maps always give the same content.
 */
object PackBuilder {
    private val basicDate: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    private data class PatternKey(
        val routeId: String,
        val stops: List<String>,
        val sequences: List<Int>,
        val pickups: List<Int>,
        val dropOffs: List<Int>,
    )

    private class ResolvedTrip(val trip: GtfsTrip, val pattern: PatternKey, val start: Int, val profile: List<Int>)

    fun buildContent(feed: GtfsFeed, request: PackBuildRequest, idMaps: PackIdMaps): BuiltContent {
        val windowStart = request.buildDate.minusDays(request.lookbackDays.toLong())
        val windowLength = request.lookbackDays + request.windowDays
        val serviceDays = serviceBitsets(feed, windowStart, windowLength)

        // Trips that run in the window, in GTFS id order.
        val resolved = feed.trips
            .asSequence()
            .filter { it.serviceId in serviceDays && it.routeId in feed.routesById }
            .filter { (feed.stopTimes[it.id]?.size ?: 0) >= 2 }
            .sortedBy { it.id }
            .map { resolve(it, feed.stopTimes.getValue(it.id)) }
            .toList()
        if (resolved.isEmpty()) throw PackBuildException("No trips run between $windowStart and the end of the pack window")

        val patternIds = LinkedHashMap<PatternKey, Int>()
        val profileIds = LinkedHashMap<List<Int>, Int>()
        for (t in resolved) {
            patternIds.getOrPut(t.pattern) { patternIds.size + 1 }
            profileIds.getOrPut(t.profile) { profileIds.size + 1 }
        }

        // Stops: those called at, their stations, and every platform of those stations.
        val usedStops = patternIds.keys.flatMapTo(LinkedHashSet()) { it.stops }
        val stations = usedStops.mapNotNullTo(HashSet()) { feed.stopsById[it]?.parentStation }
            .filterTo(HashSet()) { feed.stopsById[it]?.locationType == 1 }
        val keptStops = feed.stops.filter {
            it.id in usedStops || it.id in stations || (it.locationType == 0 && it.parentStation in stations)
        }
        val keptStopIds = keptStops.mapTo(HashSet()) { it.id }
        val usedRoutes = patternIds.keys.mapTo(HashSet()) { it.routeId }
        val usedShapes = resolved.mapNotNullTo(HashSet()) { it.trip.shapeId }.filterTo(HashSet()) { it in feed.shapes }
        val maps = idMaps.extend(keptStops.map { it.id }, usedRoutes, usedShapes)

        val routeMode = usedRoutes.associateWith { ModeBits.modeForRouteType(feed.routesById.getValue(it).type) }
        val stopModes = HashMap<String, MutableSet<Mode>>()
        for (key in patternIds.keys) for (s in key.stops) stopModes.getOrPut(s) { HashSet() } += routeMode.getValue(key.routeId)
        for (s in keptStops) {
            val parent = s.parentStation ?: continue
            if (parent in stations) stopModes.getOrPut(parent) { HashSet() } += stopModes[s.id].orEmpty()
        }

        val stops = keptStops.map { s ->
            val parent = s.parentStation?.takeIf { it in keptStopIds }
            val wheelchair = if (s.wheelchairBoarding == 0 && parent != null) feed.stopsById.getValue(parent).wheelchairBoarding else s.wheelchairBoarding
            PackStop(
                id = maps.stops.getValue(s.id),
                gtfsId = s.id,
                parentId = parent?.let { maps.stops.getValue(it) },
                name = s.name,
                lat = s.lat,
                lon = s.lon,
                platformCode = s.platformCode,
                locationType = s.locationType,
                modes = stopModes[s.id].orEmpty().sortedBy { it.ordinal }.toSet(),
                wheelchair = wheelchair.coerceIn(0, 2),
            )
        }.sortedBy { it.id }

        val routes = usedRoutes.map { id ->
            val r = feed.routesById.getValue(id)
            PackRoute(
                id = maps.routes.getValue(id),
                gtfsId = id,
                agencyId = r.agencyId ?: feed.agencies.singleOrNull()?.id,
                shortName = r.shortName,
                longName = r.longName,
                routeType = r.type,
                mode = routeMode.getValue(id),
                color = r.color,
                textColor = r.textColor,
                sortOrder = r.sortOrder,
            )
        }.sortedBy { it.id }

        val patterns = patternIds.map { (key, id) ->
            Pattern(
                id = id,
                routeId = maps.routes.getValue(key.routeId),
                stopIds = key.stops.map { maps.stops.getValue(it) }.toIntArray(),
                stopSequences = key.sequences.toIntArray(),
                pickupTypes = key.pickups.toIntArray(),
                dropOffTypes = key.dropOffs.toIntArray(),
            )
        }
        val profiles = profileIds.map { (key, id) ->
            val n = key.size / 2
            TimeProfile(id, key.subList(0, n).toIntArray(), key.subList(n, key.size).toIntArray())
        }

        val serviceIds = resolved.mapTo(sortedSetOf()) { it.trip.serviceId }.withIndex().associate { it.value to it.index + 1 }
        var lastActive = 0
        val services = serviceIds.map { (gtfsId, id) ->
            val days = serviceDays.getValue(gtfsId)
            val first = days.indexOfFirst { it }
            val last = days.indexOfLast { it }
            lastActive = maxOf(lastActive, last)
            PackService(id, gtfsId, windowStart.plusDays(first.toLong()).format(basicDate), days.copyOfRange(first, last + 1))
        }

        val trips = resolved.mapIndexed { i, t ->
            PackTrip(
                id = i + 1,
                gtfsId = t.trip.id,
                patternId = patternIds.getValue(t.pattern),
                profileId = profileIds.getValue(t.profile),
                startSecs = t.start,
                serviceId = serviceIds.getValue(t.trip.serviceId),
                headsign = t.trip.headsign ?: lastStopName(feed, t.pattern.stops.last()),
                directionId = t.trip.directionId,
                shapeId = t.trip.shapeId?.let { maps.shapes[it] },
                wheelchair = t.trip.wheelchairAccessible.coerceIn(0, 2),
                bikes = t.trip.bikesAllowed.coerceIn(0, 2),
            )
        }

        val transfers = feed.transfers
            .filter { it.transferType in 0..2 && it.fromStopId in keptStopIds && it.toStopId in keptStopIds }
            .distinctBy { it.fromStopId to it.toStopId }
            .map { PackTransfer(maps.stops.getValue(it.fromStopId), maps.stops.getValue(it.toStopId), it.transferType, it.minTransferTime ?: 0) }
            .sortedWith(compareBy({ it.fromStopId }, { it.toStopId }))

        val shapes = usedShapes.map { id ->
            val points = feed.shapes.getValue(id).map { LatLng(it.lat, it.lon) }
            PackShape(maps.shapes.getValue(id), id, Polyline.encode(Polyline.simplify(points, request.shapeToleranceMeters)))
        }.sortedBy { it.id }

        val modes = (request.modes ?: routeMode.values.toSet()).sortedBy { it.ordinal }
        val validTo = windowStart.plusDays(lastActive.toLong())
        val sources = request.sourceLabels ?: feed.sources.map { (name, version) -> "$name=${version ?: ""}" }
        val meta = mapOf(
            PackMeta.NAME to request.name,
            PackMeta.VERSION to request.version,
            PackMeta.BUILD_DATE to request.buildDate.format(basicDate),
            PackMeta.VALID_FROM to windowStart.format(basicDate),
            PackMeta.VALID_TO to validTo.format(basicDate),
            PackMeta.MODES to modes.joinToString(",") { it.name },
            PackMeta.SOURCES to sources.joinToString("\n"),
            PackMeta.TIME_ZONE to GtfsValidator.EXPECTED_TIME_ZONE,
        )
        val content = PackContent(meta, stops, routes, patterns, profiles, services, trips, transfers, shapes)
        val stats = linkedMapOf(
            "stops" to stops.size,
            "routes" to routes.size,
            "trips" to trips.size,
            "patterns" to patterns.size,
            "timeProfiles" to profiles.size,
            "services" to services.size,
            "shapes" to shapes.size,
            "transfers" to transfers.size,
        )
        return BuiltContent(content, maps, windowStart, validTo, modes, stats)
    }

    /** Active-day bitsets over the window for every service with at least one active day. */
    private fun serviceBitsets(feed: GtfsFeed, start: LocalDate, length: Int): Map<String, BooleanArray> {
        val days = HashMap<String, BooleanArray>()
        for (c in feed.calendars) {
            val bits = days.getOrPut(c.serviceId) { BooleanArray(length) }
            for (i in 0 until length) {
                val d = start.plusDays(i.toLong())
                if (d >= c.startDate && d <= c.endDate && c.days[d.dayOfWeek.value - 1]) bits[i] = true
            }
        }
        for (cd in feed.calendarDates) {
            val i = ChronoUnit.DAYS.between(start, cd.date)
            if (i < 0 || i >= length) {
                days.getOrPut(cd.serviceId) { BooleanArray(length) }
                continue
            }
            days.getOrPut(cd.serviceId) { BooleanArray(length) }[i.toInt()] = cd.exceptionType == 1
        }
        return days.filterValues { bits -> bits.any { it } }
    }

    /** Fills missing (non-timepoint) times by linear interpolation between known neighbours. */
    private fun resolve(trip: GtfsTrip, times: List<GtfsStopTime>): ResolvedTrip {
        val n = times.size
        val arr = IntArray(n) { times[it].arrival ?: -1 }
        val dep = IntArray(n) { times[it].departure ?: -1 }
        var lastKnown = 0
        for (i in 1 until n) {
            if (arr[i] < 0) continue
            if (i - lastKnown > 1) {
                val from = dep[lastKnown]
                val to = arr[i]
                for (k in lastKnown + 1 until i) {
                    val t = from + ((to - from).toLong() * (k - lastKnown) / (i - lastKnown)).toInt()
                    arr[k] = t
                    dep[k] = t
                }
            }
            lastKnown = i
        }
        val start = arr[0]
        val key = PatternKey(
            routeId = trip.routeId,
            stops = times.map { it.stopId },
            sequences = times.map { it.sequence },
            pickups = times.map { it.pickupType },
            dropOffs = times.map { it.dropOffType },
        )
        val profile = ArrayList<Int>(2 * n)
        for (i in 0 until n) profile += arr[i] - start
        for (i in 0 until n) profile += dep[i] - start
        return ResolvedTrip(trip, key, start, profile)
    }

    private fun lastStopName(feed: GtfsFeed, stopId: String): String {
        val stop = feed.stopsById[stopId] ?: return ""
        return stop.parentStation?.let { feed.stopsById[it]?.name } ?: stop.name
    }

    /**
     * Writes the SQLite pack and its zstd-compressed copy into [dir] (named after the version)
     * and computes their SHA-256s.
     */
    fun writeArtifacts(built: BuiltContent, request: PackBuildRequest, dir: Path, zstdLevel: Int = 19): PackArtifacts {
        Files.createDirectories(dir)
        val sqlite = dir.resolve("${request.version}.sqlite")
        val compressed = dir.resolve("${request.version}.sqlite.zst")
        JvmTimetables.write(sqlite, built.content)
        Files.newInputStream(sqlite).use { input ->
            ZstdOutputStream(BufferedOutputStream(Files.newOutputStream(compressed), 1 shl 16), zstdLevel).use { out ->
                out.setChecksum(true)
                input.copyTo(out, 1 shl 16)
            }
        }
        val info = PackInfo(
            name = request.name,
            version = request.version,
            sizeBytes = Files.size(compressed),
            sha256 = sha256(compressed),
            url = ApiPaths.PACK_FILE.replace("{name}", request.name).replace("{version}", request.version),
            modes = built.modes,
            validFrom = built.validFrom.format(basicDate),
            validTo = built.validTo.format(basicDate),
            sqliteSha256 = sha256(sqlite),
        )
        return PackArtifacts(sqlite, compressed, info)
    }

    /** Lower-case hex SHA-256 of a file. */
    fun sha256(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
