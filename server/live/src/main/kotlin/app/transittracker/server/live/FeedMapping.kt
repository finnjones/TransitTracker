package app.transittracker.server.live

import app.transittracker.model.AlertSeverity
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.Occupancy
import app.transittracker.model.Timetable
import app.transittracker.model.TimeRange
import app.transittracker.server.spi.FeedDefinition
import com.google.transit.realtime.CarriageDescriptor
import com.google.transit.realtime.FeedEntity
import com.google.transit.realtime.TranslatedString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import com.google.transit.realtime.Alert as GtfsAlert
import com.google.transit.realtime.VehiclePosition as GtfsVehiclePosition
import app.transittracker.model.Alert as ApiAlert
import app.transittracker.model.VehiclePosition as ApiVehiclePosition

/** Converts GTFS-R occupancy to the API enum. */
internal fun GtfsVehiclePosition.OccupancyStatus?.toOccupancy(): Occupancy = when (this) {
    GtfsVehiclePosition.OccupancyStatus.EMPTY -> Occupancy.EMPTY
    GtfsVehiclePosition.OccupancyStatus.MANY_SEATS_AVAILABLE -> Occupancy.MANY_SEATS_AVAILABLE
    GtfsVehiclePosition.OccupancyStatus.FEW_SEATS_AVAILABLE -> Occupancy.FEW_SEATS_AVAILABLE
    GtfsVehiclePosition.OccupancyStatus.STANDING_ROOM_ONLY -> Occupancy.STANDING_ROOM_ONLY
    GtfsVehiclePosition.OccupancyStatus.CRUSHED_STANDING_ROOM_ONLY -> Occupancy.CRUSHED_STANDING_ROOM_ONLY
    GtfsVehiclePosition.OccupancyStatus.FULL -> Occupancy.FULL
    GtfsVehiclePosition.OccupancyStatus.NOT_ACCEPTING_PASSENGERS -> Occupancy.NOT_ACCEPTING_PASSENGERS
    GtfsVehiclePosition.OccupancyStatus.NO_DATA_AVAILABLE,
    GtfsVehiclePosition.OccupancyStatus.NOT_BOARDABLE,
    null,
    -> Occupancy.UNKNOWN
}

private fun CarriageDescriptor.OccupancyStatus.toOccupancy(): Occupancy = when (this) {
    CarriageDescriptor.OccupancyStatus.EMPTY -> Occupancy.EMPTY
    CarriageDescriptor.OccupancyStatus.MANY_SEATS_AVAILABLE -> Occupancy.MANY_SEATS_AVAILABLE
    CarriageDescriptor.OccupancyStatus.FEW_SEATS_AVAILABLE -> Occupancy.FEW_SEATS_AVAILABLE
    CarriageDescriptor.OccupancyStatus.STANDING_ROOM_ONLY -> Occupancy.STANDING_ROOM_ONLY
    CarriageDescriptor.OccupancyStatus.CRUSHED_STANDING_ROOM_ONLY -> Occupancy.CRUSHED_STANDING_ROOM_ONLY
    CarriageDescriptor.OccupancyStatus.FULL -> Occupancy.FULL
}

/**
 * Maps GTFS-R vehicle positions to the API type. Car count comes from the TfNSW consist extension
 * (or the standard `multi_carriage_details`); occupancy falls back to the median carriage occupancy.
 */
internal object VehicleMapper {
    fun map(entity: FeedEntity, feedTimeMs: Long, timetable: Timetable?): ApiVehiclePosition? {
        val vp = entity.vehicle ?: return null
        val position = vp.position ?: return null
        val tripId = vp.trip?.trip_id
        val carCount = vp.consist.size.takeIf { it > 0 } ?: vp.multi_carriage_details.size.takeIf { it > 0 }
        var occupancy = vp.occupancy_status.toOccupancy()
        if (occupancy == Occupancy.UNKNOWN) {
            val cars = vp.consist.mapNotNull { it.occupancy_status?.toOccupancy() }.ifEmpty {
                vp.multi_carriage_details.map { it.occupancy_status.toOccupancy() }.filter { it != Occupancy.UNKNOWN }
            }
            if (cars.isNotEmpty()) occupancy = cars.sortedBy { it.ordinal }[cars.size / 2]
        }
        return ApiVehiclePosition(
            vehicleId = vp.vehicle?.id ?: entity.id,
            tripId = tripId,
            startDate = vp.trip?.start_date,
            routeId = vp.trip?.route_id ?: tripId?.let { timetable?.trip(it)?.routeId },
            position = LatLng(position.latitude.toDouble(), position.longitude.toDouble()),
            bearing = position.bearing,
            speedMps = position.speed,
            timestampMs = vp.timestamp?.takeIf { it > 0 }?.let { it * 1000 } ?: feedTimeMs,
            occupancy = occupancy,
            carCount = carCount,
            label = vp.vehicle?.label,
        )
    }
}

/** Translates GTFS-R alerts to the API type and computes ETags. */
internal object AlertTranslator {
    private val json = Json { encodeDefaults = true }

    fun translate(entity: FeedEntity, feed: FeedDefinition, timetable: Timetable?, updatedMs: Long): ApiAlert? {
        val alert = entity.alert ?: return null
        if (entity.is_deleted == true) return null
        val routeIds = sortedSetOf<String>()
        val stopIds = sortedSetOf<String>()
        val tripIds = sortedSetOf<String>()
        val modes = sortedSetOf(feed.mode)
        for (selector in alert.informed_entity) {
            selector.route_id?.let { routeId ->
                routeIds += routeId
                timetable?.route(routeId)?.mode?.let { modes += it }
            }
            selector.stop_id?.let { stopIds += it }
            selector.trip?.trip_id?.let { tripIds += it }
            selector.trip?.route_id?.let { routeIds += it }
            selector.route_type?.let { routeType -> modeOfRouteType(routeType)?.let { modes += it } }
        }
        @Suppress("DEPRECATION")
        val periods = alert.active_period.ifEmpty { alert.impact_period }.map { range ->
            TimeRange(
                startMs = range.start?.takeIf { it > 0 }?.let { it * 1000 },
                endMs = range.end?.takeIf { it > 0 }?.let { it * 1000 },
            )
        }
        return ApiAlert(
            id = entity.id,
            headline = pickText(alert.header_text).orEmpty(),
            description = pickText(alert.description_text).orEmpty(),
            url = pickText(alert.url),
            severity = severityOf(alert),
            cause = alert.cause?.name,
            effect = alert.effect?.name,
            activePeriods = periods,
            routeIds = routeIds.toList(),
            stopIds = stopIds.toList(),
            tripIds = tripIds.toList(),
            modes = modes.toList(),
            updatedMs = updatedMs,
        )
    }

    /** English text preferred: exact `en`, then unspecified language, then any other English variant (such as `en-AU`), then the first. */
    fun pickText(text: TranslatedString?): String? {
        val translations = text?.translation?.takeIf { it.isNotEmpty() } ?: return null
        return (
            translations.firstOrNull { it.language.equals("en", ignoreCase = true) }
                ?: translations.firstOrNull { it.language.isNullOrBlank() }
                ?: translations.firstOrNull { it.language?.lowercase()?.startsWith("en") == true }
                ?: translations.first()
            ).text
    }

    /** Uses `severity_level` when given, else infers it from the effect. */
    fun severityOf(alert: GtfsAlert): AlertSeverity {
        when (alert.severity_level) {
            GtfsAlert.SeverityLevel.INFO -> return AlertSeverity.INFO
            GtfsAlert.SeverityLevel.WARNING -> return AlertSeverity.WARNING
            GtfsAlert.SeverityLevel.SEVERE -> return AlertSeverity.SEVERE
            GtfsAlert.SeverityLevel.UNKNOWN_SEVERITY, null -> Unit
        }
        return when (alert.effect) {
            GtfsAlert.Effect.NO_SERVICE -> AlertSeverity.SEVERE
            GtfsAlert.Effect.REDUCED_SERVICE,
            GtfsAlert.Effect.SIGNIFICANT_DELAYS,
            GtfsAlert.Effect.DETOUR,
            GtfsAlert.Effect.MODIFIED_SERVICE,
            GtfsAlert.Effect.STOP_MOVED,
            GtfsAlert.Effect.ACCESSIBILITY_ISSUE,
            -> AlertSeverity.WARNING
            GtfsAlert.Effect.ADDITIONAL_SERVICE,
            GtfsAlert.Effect.OTHER_EFFECT,
            GtfsAlert.Effect.NO_EFFECT,
            -> AlertSeverity.INFO
            GtfsAlert.Effect.UNKNOWN_EFFECT, null -> AlertSeverity.UNKNOWN
        }
    }

    /** GTFS (and extended) route types to modes. */
    fun modeOfRouteType(routeType: Int): Mode? = when (routeType) {
        0, 900 -> Mode.LIGHT_RAIL
        1, 401 -> Mode.METRO
        2 -> Mode.TRAIN
        3, 700, 712, 714 -> Mode.BUS
        4, 1000 -> Mode.FERRY
        in 100..199 -> Mode.TRAIN
        in 200..299 -> Mode.COACH
        in 400..499 -> Mode.METRO
        in 700..799 -> Mode.BUS
        in 900..999 -> Mode.LIGHT_RAIL
        else -> null
    }

    /** Stable hash of the alerts after sorting by id: equal content always gives an equal ETag. */
    fun etag(alerts: List<ApiAlert>): String {
        val canonical = json.encodeToString(ListSerializer(ApiAlert.serializer()), alerts.sortedBy { it.id })
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
