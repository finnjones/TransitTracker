package app.transittracker.timetable

import app.cash.sqldelight.db.SqlDriver
import app.transittracker.timetable.db.Patterns
import app.transittracker.timetable.db.Routes
import app.transittracker.timetable.db.Services
import app.transittracker.timetable.db.Shapes
import app.transittracker.timetable.db.Stop_patterns
import app.transittracker.timetable.db.Stops
import app.transittracker.timetable.db.Time_profiles
import app.transittracker.timetable.db.TimetableDb
import app.transittracker.timetable.db.Transfers
import app.transittracker.timetable.db.Trips

/**
 * Writes a [PackContent] into an empty database through [driver]: creates the schema, inserts
 * every row in the given order and derives the `stop_patterns` index. Output depends only on
 * the content, so identical content gives identical files.
 */
object PackWriter {
    fun write(driver: SqlDriver, content: PackContent) {
        TimetableDb.Schema.create(driver)
        val db = TimetableDb(driver)
        val q = db.packQueries
        val meta = content.meta.toMutableMap()
        meta.getOrPut(PackMeta.SCHEMA_VERSION) { PackMeta.CURRENT_SCHEMA_VERSION.toString() }
        meta.getOrPut(PackMeta.MAX_TRIP_SECS) { (content.profiles.maxOfOrNull { it.durationSecs } ?: 0).toString() }
        db.transaction {
            meta.entries.sortedBy { it.key }.forEach { q.insertMeta(it.key, it.value) }
            content.stops.forEach { s ->
                q.insertStop(
                    Stops(
                        id = s.id.toLong(),
                        gtfs_id = s.gtfsId,
                        parent_id = s.parentId?.toLong(),
                        name = s.name,
                        lat_e6 = Geo.toE6(s.lat).toLong(),
                        lon_e6 = Geo.toE6(s.lon).toLong(),
                        platform_code = s.platformCode,
                        location_type = s.locationType.toLong(),
                        modes = ModeBits.encode(s.modes).toLong(),
                        wheelchair = s.wheelchair.toLong(),
                    ),
                )
            }
            content.routes.forEach { r ->
                q.insertRoute(
                    Routes(
                        id = r.id.toLong(),
                        gtfs_id = r.gtfsId,
                        agency_id = r.agencyId,
                        short_name = r.shortName,
                        long_name = r.longName,
                        route_type = r.routeType.toLong(),
                        mode = ModeBits.bit(r.mode).toLong(),
                        color = r.color,
                        text_color = r.textColor,
                        sort_order = r.sortOrder?.toLong(),
                    ),
                )
            }
            content.patterns.forEach { p ->
                q.insertPattern(
                    Patterns(
                        id = p.id.toLong(),
                        route_id = p.routeId.toLong(),
                        stop_count = p.size.toLong(),
                        stops = PackCodec.encodeStops(p.stopIds),
                        sequences = PackCodec.encodeSequences(p.stopSequences),
                        flags = PackCodec.encodeFlags(p.pickupTypes, p.dropOffTypes),
                    ),
                )
            }
            content.profiles.forEach { t ->
                q.insertProfile(Time_profiles(t.id.toLong(), t.size.toLong(), PackCodec.encodeProfile(t.arrivals, t.departures)))
            }
            content.services.forEach { s ->
                q.insertService(Services(s.id.toLong(), s.gtfsId, s.startDate, PackCodec.encodeDays(s.days)))
            }
            content.trips.forEach { t ->
                q.insertTrip(
                    Trips(
                        id = t.id.toLong(),
                        gtfs_id = t.gtfsId,
                        pattern_id = t.patternId.toLong(),
                        profile_id = t.profileId.toLong(),
                        start_secs = t.startSecs.toLong(),
                        service_id = t.serviceId.toLong(),
                        headsign = t.headsign,
                        direction = t.directionId?.toLong(),
                        shape_id = t.shapeId?.toLong(),
                        flags = ((t.wheelchair and 3) or ((t.bikes and 3) shl 2)).toLong(),
                    ),
                )
            }
            content.patterns
                .flatMap { p -> p.stopIds.mapIndexed { pos, stop -> Triple(stop, p.id, pos) } }
                .distinct()
                .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
                .forEach { (stop, pattern, pos) -> q.insertStopPattern(Stop_patterns(stop.toLong(), pattern.toLong(), pos.toLong())) }
            content.transfers.forEach { t ->
                q.insertTransfer(
                    Transfers(t.fromStopId.toLong(), t.toStopId.toLong(), t.transferType.toLong(), t.minTransferSecs.toLong()),
                )
            }
            content.shapes.forEach { s -> q.insertShape(Shapes(s.id.toLong(), s.gtfsId, s.polyline)) }
        }
    }
}
