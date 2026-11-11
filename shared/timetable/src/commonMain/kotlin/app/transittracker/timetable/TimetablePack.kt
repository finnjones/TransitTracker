package app.transittracker.timetable

import app.cash.sqldelight.db.SqlDriver
import app.transittracker.model.DepartureQuery
import app.transittracker.model.LatLng
import app.transittracker.model.Route
import app.transittracker.model.RouteId
import app.transittracker.model.ScheduledDeparture
import app.transittracker.model.ScheduledStopTime
import app.transittracker.model.ScheduledTrip
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceTime
import app.transittracker.model.ShapeId
import app.transittracker.model.Stop
import app.transittracker.model.StopId
import app.transittracker.model.Timetable
import app.transittracker.model.Transfer
import app.transittracker.model.TripId
import app.transittracker.timetable.db.PackQueries
import app.transittracker.timetable.db.TimetableDb
import app.transittracker.timetable.db.Trips
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/**
 * [Timetable] over one timetable pack opened through a SQLDelight [driver].
 *
 * Small tables (stop keys, routes, services) are loaded lazily into memory on first use;
 * decoded patterns and time profiles are kept in bounded LRU caches. Safe for concurrent use
 * as long as the driver is (on the JVM, open packs with `JvmTimetables.open`, whose driver
 * lends each statement its own pooled read-only connection).
 *
 * @param closeDriver whether [close] also closes [driver].
 */
class TimetablePack(
    private val driver: SqlDriver,
    private val closeDriver: Boolean = true,
    patternCacheSize: Int = 4_096,
    profileCacheSize: Int = 16_384,
) : Timetable {
    private val q: PackQueries = TimetableDb(driver).packQueries

    /** Contents of the `meta` table. */
    val meta: Map<String, String> = q.selectMeta { key, value -> key to value }.executeAsList().toMap()

    override val version: String = meta[PackMeta.VERSION].orEmpty()

    /** Pack name, e.g. `syd-rail`. */
    val name: String = meta[PackMeta.NAME].orEmpty()

    private val maxTripSecs: Int = meta[PackMeta.MAX_TRIP_SECS]?.toIntOrNull() ?: ServiceTime.MAX_SERVICE_SECONDS

    init {
        val schema = meta[PackMeta.SCHEMA_VERSION]?.toIntOrNull()
        require(schema == PackMeta.CURRENT_SCHEMA_VERSION) {
            "Unsupported pack schema version $schema (expected ${PackMeta.CURRENT_SCHEMA_VERSION})"
        }
    }

    private class StopKeys(
        val gtfsById: Map<Int, String>,
        val idByGtfs: Map<String, Int>,
        val childrenById: Map<Int, List<Int>>,
    )

    private val stopKeys: StopKeys by lazy {
        val gtfsById = HashMap<Int, String>()
        val idByGtfs = HashMap<String, Int>()
        val children = HashMap<Int, MutableList<Int>>()
        q.stopKeys { id, gtfsId, parentId ->
            gtfsById[id.toInt()] = gtfsId
            idByGtfs[gtfsId] = id.toInt()
            if (parentId != null) children.getOrPut(parentId.toInt()) { ArrayList() } += id.toInt()
        }.executeAsList()
        StopKeys(gtfsById, idByGtfs, children)
    }

    private class RouteIndex(val byId: Map<Int, Route>, val idByGtfs: Map<String, Int>, val ordered: List<Route>)

    private val routeIndex: RouteIndex by lazy {
        val rows = q.allRoutes { id, gtfsId, agencyId, shortName, longName, _, mode, color, textColor, sortOrder ->
            Triple(
                id.toInt(),
                sortOrder,
                Route(
                    id = gtfsId,
                    shortName = shortName,
                    longName = longName,
                    mode = ModeBits.single(mode.toInt()),
                    color = color,
                    textColor = textColor,
                    agencyId = agencyId,
                ),
            )
        }.executeAsList()
        val ordered = rows.sortedWith(
            compareBy<Triple<Int, Long?, Route>>({ it.second ?: Long.MAX_VALUE }, { it.third.shortName }, { it.third.id }),
        )
        RouteIndex(
            byId = rows.associate { it.first to it.third },
            idByGtfs = rows.associate { it.third.id to it.first },
            ordered = ordered.map { it.third },
        )
    }

    private class ServiceEntry(val gtfsId: String, val startDate: LocalDate, val days: ByteArray)

    private val services: Map<Int, ServiceEntry> by lazy {
        q.allServices { id, gtfsId, startDate, days ->
            id.toInt() to ServiceEntry(gtfsId, ServiceTime.parseDate(startDate), days)
        }.executeAsList().toMap()
    }

    private val patternCache = concurrentLruCache<Int, Pattern>(patternCacheSize)
    private val profileCache = concurrentLruCache<Int, TimeProfile>(profileCacheSize)
    private val activeServiceCache = concurrentLruCache<ServiceDate, Set<Int>>(16)

    // ---- Decoding helpers ----

    private fun pattern(id: Int): Pattern = patternCache.getOrPut(id) {
        q.patternById(id.toLong()) { pid, routeId, count, stops, sequences, flags ->
            val n = count.toInt()
            val (pickup, dropOff) = PackCodec.decodeFlags(flags, n)
            Pattern(
                id = pid.toInt(),
                routeId = routeId.toInt(),
                stopIds = PackCodec.decodeStops(stops, n),
                stopSequences = PackCodec.decodeSequences(sequences, n),
                pickupTypes = pickup,
                dropOffTypes = dropOff,
            )
        }.executeAsOne()
    }

    private fun profile(id: Int): TimeProfile = profileCache.getOrPut(id) {
        q.profileById(id.toLong()) { pid, count, times ->
            val (arr, dep) = PackCodec.decodeProfile(times, count.toInt())
            TimeProfile(pid.toInt(), arr, dep)
        }.executeAsOne()
    }

    /** Ids of services active on [serviceDate]. */
    private fun activeServices(serviceDate: ServiceDate): Set<Int> = activeServiceCache.getOrPut(serviceDate) {
        val date = ServiceTime.parseDate(serviceDate)
        services.filter { (_, s) -> PackCodec.isDayActive(s.days, s.startDate.daysUntil(date)) }.keys
    }

    private fun serviceRunsOn(serviceId: Int, serviceDate: ServiceDate): Boolean = serviceId in activeServices(serviceDate)

    @Suppress("LongParameterList")
    private fun stopRow(
        id: Long,
        gtfsId: String,
        parentId: Long?,
        name: String,
        latE6: Long,
        lonE6: Long,
        platformCode: String?,
        locationType: Long,
        modes: Long,
        wheelchair: Long,
        parentGtfsId: String?,
    ): StopRow = StopRow(
        id = id.toInt(),
        locationType = locationType.toInt(),
        stop = Stop(
            id = gtfsId,
            name = name,
            parentId = parentGtfsId,
            position = LatLng(Geo.fromE6(latE6), Geo.fromE6(lonE6)),
            platformCode = platformCode,
            modes = ModeBits.decode(modes.toInt()),
            wheelchairAccessible = when (wheelchair) {
                1L -> true
                2L -> false
                else -> null
            },
        ),
    )

    private class StopRow(val id: Int, val locationType: Int, val stop: Stop)

    /** Internal ids for [stopIds] plus, for stations, all their child stops. */
    private fun expandStops(stopIds: Collection<StopId>): Set<Int> {
        val keys = stopKeys
        val out = LinkedHashSet<Int>()
        for (gtfs in stopIds) {
            val id = keys.idByGtfs[gtfs] ?: continue
            out += id
            keys.childrenById[id]?.let { out += it }
        }
        return out
    }

    // ---- Timetable ----

    override fun stop(id: StopId): Stop? = q.stopByGtfsId(id, ::stopRow).executeAsOneOrNull()?.stop

    override fun stopsAtStation(stationId: StopId): List<Stop> {
        val station = q.stopByGtfsId(stationId, ::stopRow).executeAsOneOrNull() ?: return emptyList()
        return listOf(station.stop) + q.stopsByParent(station.id.toLong(), ::stopRow).executeAsList().map { it.stop }
    }

    override fun searchStops(query: String, limit: Int): List<Stop> {
        val words = StopSearch.words(query)
        if (words.isEmpty() || limit <= 0) return emptyList()
        val longest = words.maxBy { it.length }
        return q.stopsByNameLike("%" + StopSearch.escapeLike(longest) + "%", ::stopRow).executeAsList()
            .map { it.stop }
            .filter { StopSearch.matches(it, words) }
            .sortedWith(StopSearch.comparator(query))
            .take(limit)
    }

    override fun stopsNear(position: LatLng, radiusMeters: Double, limit: Int): List<Stop> {
        if (limit <= 0 || radiusMeters < 0) return emptyList()
        val dLat = Geo.metersToLatDegrees(radiusMeters)
        val dLon = Geo.metersToLonDegrees(radiusMeters, position.lat)
        return q.stopsInBox(
            Geo.toE6(position.lat - dLat).toLong(),
            Geo.toE6(position.lat + dLat).toLong(),
            Geo.toE6(position.lon - dLon).toLong(),
            Geo.toE6(position.lon + dLon).toLong(),
            ::stopRow,
        ).executeAsList()
            .map { it.stop to Geo.haversineMeters(position, it.stop.position) }
            .filter { it.second <= radiusMeters }
            .sortedWith(compareBy({ it.second }, { it.first.id }))
            .take(limit)
            .map { it.first }
    }

    override fun route(id: RouteId): Route? = routeIndex.idByGtfs[id]?.let { routeIndex.byId[it] }

    override fun routes(): List<Route> = routeIndex.ordered

    override fun routesAtStop(stopId: StopId): List<Route> {
        val ids = expandStops(listOf(stopId))
        if (ids.isEmpty()) return emptyList()
        val routeIds = q.patternsAtStops(ids.map { it.toLong() }) { _, patternId, _ -> patternId.toInt() }
            .executeAsList()
            .mapTo(HashSet()) { pattern(it).routeId }
        val index = routeIndex
        return index.ordered.filter { index.idByGtfs[it.id] in routeIds }
    }

    override fun trip(tripId: TripId): ScheduledTrip? {
        val row = q.tripByGtfsId(tripId).executeAsOneOrNull() ?: return null
        val pattern = pattern(row.pattern_id.toInt())
        val profile = profile(row.profile_id.toInt())
        val start = row.start_secs.toInt()
        val keys = stopKeys
        val stopTimes = (0 until pattern.size).map { i ->
            ScheduledStopTime(
                stopSequence = pattern.stopSequences[i],
                stopId = keys.gtfsById.getValue(pattern.stopIds[i]),
                arrivalSecs = start + profile.arrivals[i],
                departureSecs = start + profile.departures[i],
                pickup = pattern.pickupAllowed(i),
                dropOff = pattern.dropOffAllowed(i),
            )
        }
        return ScheduledTrip(
            tripId = row.gtfs_id,
            routeId = routeIndex.byId[pattern.routeId]?.id.orEmpty(),
            serviceId = services[row.service_id.toInt()]?.gtfsId.orEmpty(),
            headsign = row.headsign,
            directionId = row.direction?.toInt(),
            shapeId = row.shape_id?.let { q.shapeGtfsIdById(it).executeAsOneOrNull() },
            wheelchairAccessible = when ((row.flags and 3L).toInt()) {
                1 -> true
                2 -> false
                else -> null
            },
            stopTimes = stopTimes,
        )
    }

    override fun runsOn(tripId: TripId, serviceDate: ServiceDate): Boolean {
        val row = q.tripByGtfsId(tripId).executeAsOneOrNull() ?: return false
        return serviceRunsOn(row.service_id.toInt(), serviceDate)
    }

    /**
     * Next departures (plan section 10.4): patterns calling at an origin stop before a
     * destination stop are found with the `stop_patterns` index, then their trips running on
     * the service date are filtered to the departure window. Station ids are expanded to their
     * platforms. The origin must allow pickup and the destination drop-off; for a pattern
     * visiting the origin more than once, each visit is paired with the next usable destination
     * and the earliest matching departure per trip is kept.
     */
    override fun departures(query: DepartureQuery): List<ScheduledDeparture> {
        if (query.limit <= 0 || query.fromSecs > query.toSecs) return emptyList()
        val origins = expandStops(query.originStopIds)
        val destinations = expandStops(query.destinationStopIds)
        if (origins.isEmpty() || destinations.isEmpty()) return emptyList()
        val routeFilter: Set<Int>? = query.routeIds?.let { ids -> ids.mapNotNullTo(HashSet()) { routeIndex.idByGtfs[it] } }
        if (routeFilter != null && routeFilter.isEmpty()) return emptyList()
        val active = activeServices(query.serviceDate)
        if (active.isEmpty()) return emptyList()

        val destPositions = HashMap<Int, MutableList<Int>>()
        q.patternsAtStops(destinations.map { it.toLong() }) { _, p, pos -> p.toInt() to pos.toInt() }
            .executeAsList()
            .forEach { (p, pos) -> destPositions.getOrPut(p) { ArrayList() } += pos }
        val originRefs = q.patternsAtStops(origins.map { it.toLong() }) { _, p, pos -> p.toInt() to pos.toInt() }
            .executeAsList()
            .filter { it.first in destPositions }
            .groupBy({ it.first }, { it.second })

        val keys = stopKeys
        val best = HashMap<Long, ScheduledDeparture>()
        for ((patternId, originPositions) in originRefs.entries.sortedBy { it.key }) {
            val pattern = pattern(patternId)
            if (routeFilter != null && pattern.routeId !in routeFilter) continue
            val dests = destPositions.getValue(patternId).filter { pattern.dropOffAllowed(it) }.sorted()
            val pairs = originPositions.filter { pattern.pickupAllowed(it) }.sorted().mapNotNull { o ->
                dests.firstOrNull { it > o }?.let { d -> o to d }
            }
            if (pairs.isEmpty()) continue
            val route = routeIndex.byId[pattern.routeId]?.id.orEmpty()
            val trips = q.tripsForPattern(
                patternId.toLong(),
                (query.fromSecs - maxTripSecs).toLong(),
                query.toSecs.toLong(),
            ).executeAsList()
            for (trip in trips) {
                if (trip.service_id.toInt() !in active) continue
                val profile = profile(trip.profile_id.toInt())
                val start = trip.start_secs.toInt()
                for ((o, d) in pairs) {
                    val dep = start + profile.departures[o]
                    if (dep < query.fromSecs || dep > query.toSecs) continue
                    val previous = best[trip.id]
                    if (previous != null && previous.departureSecs <= dep) continue
                    best[trip.id] = makeDeparture(query.serviceDate, trip, route, pattern, o, d, dep, start + profile.arrivals[d], keys)
                }
            }
        }
        return best.values
            .sortedWith(compareBy({ it.departureSecs }, { it.arrivalSecs }, { it.tripId }))
            .take(query.limit)
    }

    @Suppress("LongParameterList")
    private fun makeDeparture(
        serviceDate: ServiceDate,
        trip: Trips,
        routeId: RouteId,
        pattern: Pattern,
        originPos: Int,
        destPos: Int,
        departureSecs: Int,
        arrivalSecs: Int,
        keys: StopKeys,
    ) = ScheduledDeparture(
        tripId = trip.gtfs_id,
        routeId = routeId,
        serviceDate = serviceDate,
        headsign = trip.headsign,
        originStopId = keys.gtfsById.getValue(pattern.stopIds[originPos]),
        originStopSequence = pattern.stopSequences[originPos],
        departureSecs = departureSecs,
        destinationStopId = keys.gtfsById.getValue(pattern.stopIds[destPos]),
        destinationStopSequence = pattern.stopSequences[destPos],
        arrivalSecs = arrivalSecs,
        departureMillis = ServiceTime.toEpochMillis(serviceDate, departureSecs),
        arrivalMillis = ServiceTime.toEpochMillis(serviceDate, arrivalSecs),
    )

    override fun shape(shapeId: ShapeId): List<LatLng>? =
        q.shapeByGtfsId(shapeId).executeAsOneOrNull()?.let { Polyline.decode(it) }

    /** The stored encoded polyline for [shapeId] (precision 5), or null if unknown. */
    fun encodedShape(shapeId: ShapeId): String? = q.shapeByGtfsId(shapeId).executeAsOneOrNull()

    override fun transfers(fromStopId: StopId): List<Transfer> {
        val id = stopKeys.idByGtfs[fromStopId] ?: return emptyList()
        return q.transfersFrom(id.toLong()) { _, _, _, minSecs, toGtfsId ->
            Transfer(fromStopId, toGtfsId, minSecs.toInt())
        }.executeAsList()
    }

    override fun close() {
        patternCache.clear()
        profileCache.clear()
        if (closeDriver) driver.close()
    }

    override fun toString(): String = "TimetablePack($name@$version)"
}
