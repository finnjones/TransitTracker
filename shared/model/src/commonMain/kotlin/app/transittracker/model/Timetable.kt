package app.transittracker.model

/**
 * Read access to the scheduled timetable. Implemented by `:shared:timetable` over a timetable
 * pack (SQLite). Used by the phone (`:shared:core`) and by the server (`:server:live` for
 * matching, `:server:watch` for working out which trips a watch covers).
 *
 * All `*Secs` values are GTFS service-day seconds (see [ServiceTime]).
 * Implementations must be safe to call from multiple threads.
 */
interface Timetable : AutoCloseable {
    /** Version string(s) of the loaded pack(s); echoed in real-time responses. */
    val version: String

    fun stop(id: StopId): Stop?

    /** The station plus all its platform-level child stops. Returns just the stop if it has no children. */
    fun stopsAtStation(stationId: StopId): List<Stop>

    fun searchStops(query: String, limit: Int = 20): List<Stop>

    fun stopsNear(position: LatLng, radiusMeters: Double, limit: Int = 20): List<Stop>

    fun route(id: RouteId): Route?

    fun routes(): List<Route>

    fun routesAtStop(stopId: StopId): List<Route>

    fun trip(tripId: TripId): ScheduledTrip?

    /** True if [tripId] runs on [serviceDate]. */
    fun runsOn(tripId: TripId, serviceDate: ServiceDate): Boolean

    /**
     * Trips on [DepartureQuery.serviceDate] that call at an origin stop and *later* at a
     * destination stop, departing the origin within [fromSecs, toSecs], sorted by departure.
     */
    fun departures(query: DepartureQuery): List<ScheduledDeparture>

    /** Decoded shape points, or null if unknown. */
    fun shape(shapeId: ShapeId): List<LatLng>?

    fun transfers(fromStopId: StopId): List<Transfer>
}

data class ScheduledStopTime(
    val stopSequence: Int,
    val stopId: StopId,
    val arrivalSecs: Int,
    val departureSecs: Int,
    val pickup: Boolean = true,
    val dropOff: Boolean = true,
)

data class ScheduledTrip(
    val tripId: TripId,
    val routeId: RouteId,
    val serviceId: ServiceId,
    val headsign: String,
    val directionId: Int? = null,
    val shapeId: ShapeId? = null,
    val wheelchairAccessible: Boolean? = null,
    val stopTimes: List<ScheduledStopTime>,
)

data class DepartureQuery(
    val originStopIds: Set<StopId>,
    val destinationStopIds: Set<StopId>,
    val serviceDate: ServiceDate,
    val fromSecs: Int,
    val toSecs: Int = fromSecs + 6 * 3600,
    val limit: Int = 20,
    /** Restrict to these routes; null = any. */
    val routeIds: Set<RouteId>? = null,
)

data class ScheduledDeparture(
    val tripId: TripId,
    val routeId: RouteId,
    val serviceDate: ServiceDate,
    val headsign: String,
    val originStopId: StopId,
    val originStopSequence: Int,
    val departureSecs: Int,
    val destinationStopId: StopId,
    val destinationStopSequence: Int,
    val arrivalSecs: Int,
    /** Absolute times, computed with [ServiceTime.toEpochMillis]. */
    val departureMillis: Long,
    val arrivalMillis: Long,
)

data class Transfer(val fromStopId: StopId, val toStopId: StopId, val minTransferSecs: Int)

/**
 * Departures leaving at or after [afterMillis], across the previous and current service days
 * (so after-midnight trips are included), merged and sorted by absolute departure time.
 */
fun Timetable.departuresAfter(
    originStopIds: Set<StopId>,
    destinationStopIds: Set<StopId>,
    afterMillis: Long,
    limit: Int = 20,
    routeIds: Set<RouteId>? = null,
    horizonSecs: Int = 6 * 3600,
): List<ScheduledDeparture> {
    val results = ArrayList<ScheduledDeparture>()
    for (date in ServiceTime.candidateServiceDates(afterMillis)) {
        val from = ServiceTime.secondsSinceReference(date, afterMillis)
        if (from > ServiceTime.MAX_SERVICE_SECONDS) continue
        results += departures(
            DepartureQuery(
                originStopIds = originStopIds,
                destinationStopIds = destinationStopIds,
                serviceDate = date,
                fromSecs = maxOf(from, 0),
                toSecs = from + horizonSecs,
                limit = limit,
                routeIds = routeIds,
            ),
        )
    }
    return results.distinctBy { it.tripId to it.serviceDate }.sortedBy { it.departureMillis }.take(limit)
}
