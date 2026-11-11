package app.transittracker.server.watch

import app.transittracker.model.RouteId
import app.transittracker.model.ScheduledDeparture
import app.transittracker.model.ScheduledStopTime
import app.transittracker.model.ScheduledTrip
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceTime
import app.transittracker.model.StopId
import app.transittracker.model.Timetable
import app.transittracker.model.TripId
import app.transittracker.model.WatchDto
import app.transittracker.model.departuresAfter
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.time.ExperimentalTime

/**
 * One concrete scheduled trip a watch covers.
 *
 * @property windowStartMillis start of the watch window this trip was found in (for a
 *   single-service watch, the trip's departure). Trips sharing it form one activity period.
 */
data class CoveredTrip(
    val tripId: TripId,
    val serviceDate: ServiceDate,
    val routeId: RouteId,
    val headsign: String,
    val originStopId: StopId,
    val originStopSequence: Int,
    val destinationStopId: StopId,
    val destinationStopSequence: Int,
    val departureMillis: Long,
    val arrivalMillis: Long,
    val windowStartMillis: Long,
)

/**
 * Works out which scheduled trips a watch covers. Pure: everything comes from the [Timetable].
 */
@OptIn(ExperimentalTime::class)
internal class CoverageCalculator(private val settings: WatchEngineSettings) {

    /**
     * Trips a `COMMUTE` watch covers on the local (Sydney) calendar date [localDate]: trips that
     * leave one of [originStops] inside the wall-clock window on that date and later call at one
     * of [destinationStops]. Honours days of week and the route filter. The window is converted
     * to instants with the Sydney zone rules, so daylight-saving change days are handled.
     */
    fun commute(
        timetable: Timetable,
        watch: WatchDto,
        originStops: Set<StopId>,
        destinationStops: Set<StopId>,
        localDate: LocalDate,
    ): List<CoveredTrip> {
        val date = ServiceTime.formatDate(localDate)
        if (ServiceTime.isoDayOfWeek(date) !in watch.daysOfWeek) return emptyList()
        val start = wallClockMillis(localDate, watch.windowStartSecs)
        val end = wallClockMillis(localDate, watch.windowEndSecs)
        if (end <= start) return emptyList()
        val horizonSecs = ((end - start) / 1000L).toInt() + 1
        return timetable.departuresAfter(
            originStopIds = originStops,
            destinationStopIds = destinationStops,
            afterMillis = start,
            limit = settings.maxTripsPerWindow,
            routeIds = watch.routeIds.toSet().ifEmpty { null },
            horizonSecs = horizonSecs,
        )
            .filter { it.departureMillis in start..end }
            .map { it.toCovered(start) }
    }

    /** The trip a `SINGLE_SERVICE` watch covers, or empty if it does not run / serve both stations. */
    fun singleService(
        timetable: Timetable,
        watch: WatchDto,
        originStops: Set<StopId>,
        destinationStops: Set<StopId>,
    ): List<CoveredTrip> {
        val tripId = watch.tripId ?: return emptyList()
        val date = watch.serviceDate ?: return emptyList()
        if (!timetable.runsOn(tripId, date)) return emptyList()
        val trip = timetable.trip(tripId) ?: return emptyList()
        val (origin, destination) = findLeg(trip, originStops, destinationStops) ?: return emptyList()
        val departure = ServiceTime.toEpochMillis(date, origin.departureSecs)
        return listOf(
            CoveredTrip(
                tripId = tripId,
                serviceDate = date,
                routeId = trip.routeId,
                headsign = trip.headsign,
                originStopId = origin.stopId,
                originStopSequence = origin.stopSequence,
                destinationStopId = destination.stopId,
                destinationStopSequence = destination.stopSequence,
                departureMillis = departure,
                arrivalMillis = ServiceTime.toEpochMillis(date, destination.arrivalSecs),
                windowStartMillis = departure,
            ),
        )
    }

    private fun ScheduledDeparture.toCovered(windowStart: Long) = CoveredTrip(
        tripId = tripId,
        serviceDate = serviceDate,
        routeId = routeId,
        headsign = headsign,
        originStopId = originStopId,
        originStopSequence = originStopSequence,
        destinationStopId = destinationStopId,
        destinationStopSequence = destinationStopSequence,
        departureMillis = departureMillis,
        arrivalMillis = arrivalMillis,
        windowStartMillis = windowStart,
    )

    companion object {
        /** The instant of [secondsAfterMidnight] (0..86400) local Sydney time on [date]. */
        fun wallClockMillis(date: LocalDate, secondsAfterMidnight: Int): Long {
            val dateTime = if (secondsAfterMidnight >= 86_400) {
                LocalDateTime(date.plus(1, DateTimeUnit.DAY), LocalTime(0, 0))
            } else {
                LocalDateTime(date, LocalTime.fromSecondOfDay(secondsAfterMidnight))
            }
            return dateTime.toInstant(ServiceTime.SYDNEY).toEpochMilliseconds()
        }

        /** First call at an origin stop followed later by a call at a destination stop. */
        fun findLeg(
            trip: ScheduledTrip,
            originStops: Set<StopId>,
            destinationStops: Set<StopId>,
        ): Pair<ScheduledStopTime, ScheduledStopTime>? {
            val stops = trip.stopTimes.sortedBy { it.stopSequence }
            val originIndex = stops.indexOfFirst { it.stopId in originStops }
            if (originIndex < 0) return null
            val destination = stops.drop(originIndex + 1).firstOrNull { it.stopId in destinationStops } ?: return null
            return stops[originIndex] to destination
        }
    }
}
