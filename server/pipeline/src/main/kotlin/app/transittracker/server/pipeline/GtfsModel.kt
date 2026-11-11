package app.transittracker.server.pipeline

import java.time.LocalDate

/*
 * In-memory GTFS static model: just the fields the pack builder and validator need.
 * Times are GTFS seconds (may exceed 24h); null means "not given".
 */

data class GtfsAgency(val id: String?, val name: String, val url: String?, val timezone: String)

data class GtfsStop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** 0 stop/platform, 1 station, 2 entrance, 3 generic node, 4 boarding area. */
    val locationType: Int,
    val parentStation: String?,
    val platformCode: String?,
    /** 0 unknown/inherit, 1 accessible, 2 not accessible. */
    val wheelchairBoarding: Int,
)

data class GtfsRoute(
    val id: String,
    val agencyId: String?,
    val shortName: String,
    val longName: String,
    val type: Int,
    val color: String?,
    val textColor: String?,
    val sortOrder: Int?,
)

data class GtfsTrip(
    val id: String,
    val routeId: String,
    val serviceId: String,
    val headsign: String?,
    val directionId: Int?,
    val shapeId: String?,
    val wheelchairAccessible: Int,
    val bikesAllowed: Int,
)

data class GtfsStopTime(
    val tripId: String,
    val stopId: String,
    val sequence: Int,
    val arrival: Int?,
    val departure: Int?,
    val pickupType: Int,
    val dropOffType: Int,
)

data class GtfsCalendar(
    val serviceId: String,
    /** Monday..Sunday. */
    val days: List<Boolean>,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

data class GtfsCalendarDate(val serviceId: String, val date: LocalDate, val exceptionType: Int)

data class GtfsShapePoint(val lat: Double, val lon: Double, val sequence: Int)

data class GtfsTransfer(val fromStopId: String, val toStopId: String, val transferType: Int, val minTransferTime: Int?)

data class GtfsFeedInfo(val publisherName: String?, val version: String?, val startDate: LocalDate?, val endDate: LocalDate?)

/**
 * One loaded (or merged) GTFS feed.
 *
 * @property sources names of the input bundles with their `feed_version` (or null).
 * @property stopTimes per trip id, sorted by stop sequence.
 * @property shapes per shape id, sorted by point sequence.
 */
class GtfsFeed(
    val sources: List<Pair<String, String?>>,
    val agencies: List<GtfsAgency>,
    val stops: List<GtfsStop>,
    val routes: List<GtfsRoute>,
    val trips: List<GtfsTrip>,
    val stopTimes: Map<String, List<GtfsStopTime>>,
    val calendars: List<GtfsCalendar>,
    val calendarDates: List<GtfsCalendarDate>,
    val shapes: Map<String, List<GtfsShapePoint>>,
    val transfers: List<GtfsTransfer>,
) {
    val stopsById: Map<String, GtfsStop> by lazy { stops.associateBy { it.id } }
    val routesById: Map<String, GtfsRoute> by lazy { routes.associateBy { it.id } }
    val tripsById: Map<String, GtfsTrip> by lazy { trips.associateBy { it.id } }
    val serviceIds: Set<String> by lazy { calendars.mapTo(HashSet()) { it.serviceId } + calendarDates.map { it.serviceId } }
}
