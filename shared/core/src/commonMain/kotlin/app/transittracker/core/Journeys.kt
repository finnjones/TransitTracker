package app.transittracker.core

import app.transittracker.model.Departure
import app.transittracker.model.DepartureQuery
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Journey
import app.transittracker.model.RouteId
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceTime
import app.transittracker.model.StopId
import app.transittracker.model.Timetable
import app.transittracker.model.TripLeg
import app.transittracker.model.departuresAfter

/**
 * Builds [Journey]s for a [SavedTrip] from the timetable plus live data.
 *
 * - Single leg: the leg's departures.
 * - Multi-leg: for each departure of the first leg, each next leg is the first non-cancelled
 *   departure leaving at or after the previous leg's expected arrival + [TripLeg.minConnectionMinutes].
 *   Cancelled first-leg services are only listed for single-leg trips.
 */
class JourneyPlanner(private val timetable: Timetable, private val overlay: Overlay) {

    /** The next [count] journeys whose (expected) first departure is at or after [nowMs]. */
    fun upcoming(trip: SavedTrip, nowMs: Long, count: Int): List<Journey> {
        if (trip.legs.isEmpty() || count <= 0) return emptyList()
        val first = trip.legs.first()
        val multi = trip.legs.size > 1
        val result = ArrayList<Journey>(count)
        var limit = count * 2 + 40
        // Widen the search when chaining fails for many candidates (e.g. last connection of the night).
        repeat(3) {
            result.clear()
            val firstLegs = legDepartures(first, nowMs, limit)
            for (dep in firstLegs) {
                if (multi && dep.status == DepartureStatus.CANCELLED) continue
                val journey = chain(dep, trip.legs) ?: continue
                result += journey
                if (result.size == count) return result
            }
            if (firstLegs.size < limit) return result
            limit *= 3
        }
        return result
    }

    /**
     * Journeys already under way at [nowMs]: the first leg has left (expected time) within the
     * last [lookbackMs], it is not cancelled, and the last leg has not yet arrived, give or take
     * [arrivedGraceMs]. Each is chained as in [upcoming], with live data, so a late first leg
     * moves the connection on to the next one that can be made. Earliest first.
     */
    fun underway(
        trip: SavedTrip,
        nowMs: Long,
        lookbackMs: Long = UNDERWAY_LOOKBACK_MS,
        arrivedGraceMs: Long = ARRIVED_GRACE_MS,
    ): List<Journey> {
        if (trip.legs.isEmpty()) return emptyList()
        return legDepartures(trip.legs.first(), nowMs - lookbackMs, UNDERWAY_LIMIT)
            .filter { it.status != DepartureStatus.CANCELLED && it.expectedDepartureMs < nowMs }
            .mapNotNull { chain(it, trip.legs) }
            .filter { j -> j.arrival.expectedArrivalMs > nowMs - arrivedGraceMs }
    }

    /** Every journey whose first leg leaves on [serviceDate] (a full service day, including after midnight). */
    fun forServiceDay(trip: SavedTrip, serviceDate: ServiceDate): List<Journey> {
        if (trip.legs.isEmpty()) return emptyList()
        val first = trip.legs.first()
        val origins = expand(first.originStopIds)
        val dests = expand(first.destinationStopIds)
        val scheduled = timetable.departures(
            DepartureQuery(
                originStopIds = origins,
                destinationStopIds = dests,
                serviceDate = serviceDate,
                fromSecs = 0,
                toSecs = ServiceTime.MAX_SERVICE_SECONDS,
                limit = 5_000,
                routeIds = first.routeIds,
            ),
        ).map(overlay::departure)
        val added = overlay.addedDepartures(origins, dests, first.routeIds).filter { it.ref.serviceDate == serviceDate }
        val firstLegs = (scheduled + added).sortedBy { it.scheduledDepartureMs }
        val multi = trip.legs.size > 1
        return firstLegs.mapNotNull { dep ->
            if (multi && dep.status == DepartureStatus.CANCELLED) null else chain(dep, trip.legs)
        }
    }

    /** Route ids involved in a list of journeys (what live polling should watch). */
    fun routesOf(trip: SavedTrip, journeys: List<Journey>): Set<RouteId> =
        journeys.flatMap { j -> j.legs.map { it.routeId } }.toSet() +
            trip.legs.flatMap { it.routeIds.orEmpty() }

    private fun chain(first: Departure, legs: List<TripLeg>): Journey? {
        if (legs.size == 1) return Journey(listOf(first))
        val chosen = ArrayList<Departure>(legs.size)
        chosen += first
        for (leg in legs.drop(1)) {
            val prev = chosen.last()
            val earliest = prev.expectedArrivalMs + leg.minConnectionMinutes * 60_000L
            val next = legDepartures(leg, earliest, LEG_LOOKAHEAD)
                .firstOrNull { it.status != DepartureStatus.CANCELLED }
                ?: return null
            chosen += next
        }
        return Journey(chosen)
    }

    /**
     * Overlaid departures for [leg] whose expected departure (scheduled, if cancelled) is at or
     * after [afterMs], including added services, sorted by expected departure.
     */
    internal fun legDepartures(leg: TripLeg, afterMs: Long, limit: Int): List<Departure> {
        val origins = expand(leg.originStopIds)
        val dests = expand(leg.destinationStopIds)
        // Look back so services scheduled earlier but running late are still found.
        val scheduled = timetable.departuresAfter(
            originStopIds = origins,
            destinationStopIds = dests,
            afterMillis = afterMs - LATE_LOOKBACK_MS,
            limit = limit + LOOKBACK_EXTRA,
            routeIds = leg.routeIds,
            horizonSecs = 12 * 3600,
        ).map(overlay::departure)
        val added = overlay.addedDepartures(origins, dests, leg.routeIds)
        return (scheduled + added)
            .filter { d ->
                val t = if (d.status == DepartureStatus.CANCELLED) d.scheduledDepartureMs else d.expectedDepartureMs
                t >= afterMs
            }
            .distinctBy { it.ref }
            .sortedWith(compareBy<Departure> { it.expectedDepartureMs }.thenBy { it.scheduledDepartureMs })
            .take(limit)
    }

    /** Expands station ids to their platform stops (and keeps the ids themselves). */
    private fun expand(ids: Set<StopId>): Set<StopId> =
        ids.flatMapTo(LinkedHashSet()) { id -> timetable.stopsAtStation(id).map { it.id } + id }

    companion object {
        /** How far back [underway] looks for a first leg that has left: longer than any saved trip. */
        const val UNDERWAY_LOOKBACK_MS = 3 * 60 * 60_000L
        /** A journey still counts as under way this long after its last leg's expected arrival. */
        const val ARRIVED_GRACE_MS = 2 * 60_000L
        private const val UNDERWAY_LIMIT = 80
        private const val LATE_LOOKBACK_MS = 90 * 60_000L
        private const val LOOKBACK_EXTRA = 60
        private const val LEG_LOOKAHEAD = 8
    }
}
