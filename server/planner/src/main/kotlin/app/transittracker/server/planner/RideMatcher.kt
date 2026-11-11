package app.transittracker.server.planner

import app.transittracker.model.DepartureQuery
import app.transittracker.model.RouteId
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceTime
import app.transittracker.model.StopId
import app.transittracker.model.Timetable
import app.transittracker.model.TripId
import kotlin.math.abs

/** What the planner said about one ride, in terms the timetable can check. */
data class RideFacts(
    /** What passengers call the line: "T1", "333". */
    val routeName: String?,
    /** The planner's real-time trip ids (`transportation.properties.RealtimeTripId`). */
    val realtimeTripIds: List<String>,
    /** Planner ids of the boarding stop that exist in the timetable (platform, then station). */
    val boardingStopIds: List<StopId>,
    val alightingStopIds: List<StopId>,
    /** Scheduled departure(s) from the boarding stop, epoch ms: planned first, then base timetable. */
    val departureMs: List<Long>,
)

/** A confident tie between a planned ride and one run of a timetabled trip. */
data class RideMatch(
    val tripId: TripId,
    val serviceDate: ServiceDate,
    val routeId: RouteId,
    /** The timetable's boarding and alighting stops of that trip. */
    val boardingStopId: StopId,
    val alightingStopId: StopId?,
    /** Which rule matched: 1 = the planner's real-time trip id, 2 = the only trip of the line at that time. */
    val rule: Int,
)

/**
 * Ties planned rides to trips in the loaded timetable, so the phone can apply its own live data.
 * Never guesses: a ride is matched only when
 *
 * 1. the planner's real-time trip id is a trip in the timetable that runs on the service date and
 *    leaves a stop of the boarding station at the planned time (± [toleranceMs]), and (when the
 *    alighting station is known) calls there later; otherwise
 * 2. exactly one trip of the same line (route short name) leaves the boarding station at the planned
 *    time and calls later at the alighting station ([Timetable.departures]).
 *
 * TfNSW's developer forum reports that the planner's real-time trip ids do not always match the
 * GTFS-Realtime feeds, so rule 2 matters.
 */
class RideMatcher(
    private val timetable: Timetable,
    private val toleranceMs: Long = 60_000,
) {
    private val routesByName: Map<String, Set<RouteId>> by lazy {
        timetable.routes().groupBy({ it.shortName.trim().lowercase() }, { it.id }).mapValues { it.value.toSet() }
    }

    fun match(ride: RideFacts): RideMatch? = byTripId(ride) ?: byDeparture(ride)

    /** The station and all its platforms for the first known stop id, or empty. */
    fun stationStops(ids: List<StopId>): Set<StopId> {
        for (id in ids) {
            val stop = timetable.stop(id) ?: continue
            val station = stop.parentId ?: stop.id
            return (timetable.stopsAtStation(station).map { it.id } + id + station).toSet()
        }
        return emptySet()
    }

    private fun byTripId(ride: RideFacts): RideMatch? {
        val boarding = stationStops(ride.boardingStopIds)
        val alighting = stationStops(ride.alightingStopIds)
        val found = LinkedHashMap<Pair<TripId, ServiceDate>, RideMatch>()
        for (tripId in ride.realtimeTripIds.distinct()) {
            val trip = timetable.trip(tripId) ?: continue
            val stops = trip.stopTimes.sortedBy { it.stopSequence }
            for (departure in ride.departureMs) {
                for (date in ServiceTime.candidateServiceDates(departure)) {
                    if (!timetable.runsOn(tripId, date)) continue
                    val boardIndex = stops.indexOfFirst { st ->
                        (boarding.isEmpty() || st.stopId in boarding) &&
                            abs(ServiceTime.toEpochMillis(date, st.departureSecs) - departure) <= toleranceMs
                    }
                    if (boardIndex < 0) continue
                    val alight = stops.drop(boardIndex + 1).firstOrNull { it.stopId in alighting }
                    if (alighting.isNotEmpty() && alight == null) continue
                    found[tripId to date] = RideMatch(tripId, date, trip.routeId, stops[boardIndex].stopId, alight?.stopId, rule = 1)
                }
                if (found.isNotEmpty()) break
            }
        }
        return found.values.singleOrNull()
    }

    private fun byDeparture(ride: RideFacts): RideMatch? {
        val name = ride.routeName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val routeIds = routesByName[name] ?: return null
        val boarding = stationStops(ride.boardingStopIds)
        val alighting = stationStops(ride.alightingStopIds)
        if (boarding.isEmpty() || alighting.isEmpty()) return null
        val toleranceS = (toleranceMs / 1000).toInt()
        for (departure in ride.departureMs.distinct()) {
            val found = ServiceTime.candidateServiceDates(departure).flatMap { date ->
                val secs = ServiceTime.secondsSinceReference(date, departure)
                timetable.departures(
                    DepartureQuery(
                        originStopIds = boarding,
                        destinationStopIds = alighting,
                        serviceDate = date,
                        fromSecs = secs - toleranceS,
                        toSecs = secs + toleranceS,
                        limit = 10,
                        routeIds = routeIds,
                    ),
                ).filter { abs(it.departureMillis - departure) <= toleranceMs }
            }.distinctBy { it.tripId to it.serviceDate }
            when (found.size) {
                0 -> continue
                1 -> return found.single().let {
                    RideMatch(it.tripId, it.serviceDate, it.routeId, it.originStopId, it.destinationStopId, rule = 2)
                }
                else -> return null // ambiguous: never guess
            }
        }
        return null
    }
}
