package app.transittracker.server.live

import app.transittracker.model.AddedStop
import app.transittracker.model.ScheduledStopTime
import app.transittracker.model.ScheduledTrip
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceTime
import app.transittracker.model.StopDelta
import app.transittracker.model.StopId
import app.transittracker.model.Timetable
import app.transittracker.model.TripDelta
import app.transittracker.model.TripId
import app.transittracker.model.TripStatus
import com.google.transit.realtime.TripDescriptor
import com.google.transit.realtime.TripUpdate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Identifies one run of a trip: GTFS `trip_id` plus service (start) date. */
data class TripKey(val tripId: TripId, val startDate: ServiceDate)

/** Result of normalising one GTFS-R TripUpdate against the timetable. */
sealed interface MatchResult {
    /** Matched (or a valid added trip); [delta] has `stale = false` and no vehicle enrichment yet. */
    data class Matched(val delta: TripDelta) : MatchResult

    /** Matched, but every stop is already behind the feed time: nothing to serve. */
    data class Finished(val key: TripKey) : MatchResult

    /** Could not be tied to a timetabled trip; never guessed. */
    data class Unmatched(val tripId: String?, val reason: String) : MatchResult
}

/**
 * Turns GTFS-R TripUpdates into [TripDelta]s (plan section 4.1): matches on `trip_id` + `start_date`
 * (inferring a missing date), converts absolute times to delays via [ServiceTime], propagates delays to
 * every remaining scheduled stop, and detects platform changes, skipped stops, cancellations and
 * added / replacement trips.
 *
 * Propagation follows the GTFS-R rules: a stop without an update inherits the departure delay of the
 * previous updated stop; `SKIPPED` stops do not change the propagated delay; `NO_DATA` clears it until
 * the next stop with data. A missing arrival takes the propagated (else the departure) delay, a missing
 * departure takes the arrival delay.
 */
class TripMatcher(
    private val timetable: Timetable,
    /** Stops whose expected departure is more than this before the feed time are dropped. */
    private val pastStopGraceMs: Long = 120_000,
    /** Largest gap between feed time and a trip's scheduled span accepted when inferring a start date. */
    private val maxInferenceGapMs: Long = 3 * 3600_000L,
) {
    /** Normalises [update]; [feedTimeMs] is the feed header time, used for remaining stops and date inference. */
    @Suppress("DEPRECATION") // TripDescriptor.ScheduleRelationship.ADDED is deprecated upstream but still published
    fun match(update: TripUpdate, feedTimeMs: Long): MatchResult {
        val descriptor = update.trip
        val updatedMs = update.timestamp?.takeIf { it > 0 }?.let { it * 1000 } ?: feedTimeMs
        return when (descriptor.schedule_relationship ?: TripDescriptor.ScheduleRelationship.SCHEDULED) {
            TripDescriptor.ScheduleRelationship.SCHEDULED -> matchScheduled(update, feedTimeMs, updatedMs)
            TripDescriptor.ScheduleRelationship.CANCELED,
            TripDescriptor.ScheduleRelationship.DELETED,
            -> matchCancelled(update, feedTimeMs, updatedMs)
            TripDescriptor.ScheduleRelationship.REPLACEMENT -> matchAdded(update, TripStatus.REPLACEMENT, feedTimeMs, updatedMs)
            TripDescriptor.ScheduleRelationship.ADDED,
            TripDescriptor.ScheduleRelationship.UNSCHEDULED,
            TripDescriptor.ScheduleRelationship.NEW,
            TripDescriptor.ScheduleRelationship.DUPLICATED,
            -> matchAdded(update, TripStatus.ADDED, feedTimeMs, updatedMs)
        }
    }

    // ---- scheduled trips ----

    private fun matchScheduled(update: TripUpdate, feedTimeMs: Long, updatedMs: Long): MatchResult {
        val tripId = update.trip.trip_id ?: return MatchResult.Unmatched(null, "no trip_id")
        val trip = timetable.trip(tripId) ?: return MatchResult.Unmatched(tripId, "trip_id not in timetable")
        val stops = trip.stopTimes.sortedBy { it.stopSequence }
        if (stops.isEmpty()) return MatchResult.Unmatched(tripId, "trip has no stop times")
        val resolved = resolveUpdates(update, stops)
        val startDate = resolveStartDate(update, trip, stops, resolved, feedTimeMs)
            ?: return MatchResult.Unmatched(tripId, "start_date missing/invalid or trip does not run on it")
        val reference = ServiceTime.referenceMillis(startDate)

        var propagated: Int? = if (update.stop_time_update.isEmpty()) update.delay else null
        val deltas = ArrayList<StopDelta>(stops.size)
        val expectedDepartures = LongArray(stops.size)
        for ((i, stop) in stops.withIndex()) {
            val stu = resolved[i]
            var arrival: Int? = propagated
            var departure: Int? = propagated
            var skipped = false
            var actualStopId: StopId? = null
            if (stu != null) {
                when (stu.schedule_relationship ?: TripUpdate.StopTimeUpdate.ScheduleRelationship.SCHEDULED) {
                    TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED -> {
                        skipped = true
                        arrival = null
                        departure = null
                    }
                    TripUpdate.StopTimeUpdate.ScheduleRelationship.NO_DATA,
                    TripUpdate.StopTimeUpdate.ScheduleRelationship.UNSCHEDULED,
                    -> {
                        arrival = null
                        departure = null
                        propagated = null
                    }
                    TripUpdate.StopTimeUpdate.ScheduleRelationship.SCHEDULED -> {
                        val arr = eventDelay(stu.arrival, reference, stop.arrivalSecs)
                        val dep = eventDelay(stu.departure, reference, stop.departureSecs)
                        arrival = arr ?: propagated ?: dep
                        departure = dep ?: arrival
                        // Never depart before arriving.
                        if (arrival != null && departure != null) {
                            val minDeparture = arrival + stop.arrivalSecs - stop.departureSecs
                            if (departure < minDeparture) departure = minDeparture
                        }
                        propagated = departure
                        actualStopId = liveStopId(stu)?.takeIf { it != stop.stopId && sameStation(it, stop.stopId) }
                    }
                }
            }
            deltas += StopDelta(
                stopSequence = stop.stopSequence,
                stopId = stop.stopId,
                arrivalDelayS = arrival,
                departureDelayS = departure,
                actualStopId = actualStopId,
                skipped = skipped,
            )
            expectedDepartures[i] = reference + stop.departureSecs * 1000L + (departure ?: 0) * 1000L
        }

        val cutoff = feedTimeMs - pastStopGraceMs
        val firstRemaining = expectedDepartures.indexOfFirst { it >= cutoff }
        if (firstRemaining < 0) return MatchResult.Finished(TripKey(tripId, startDate))
        return MatchResult.Matched(
            TripDelta(
                tripId = tripId,
                startDate = startDate,
                routeId = trip.routeId,
                status = TripStatus.SCHEDULED,
                stops = deltas.subList(firstRemaining, deltas.size).toList(),
                vehicleId = update.vehicle?.id,
                updatedMs = updatedMs,
            ),
        )
    }

    private fun matchCancelled(update: TripUpdate, feedTimeMs: Long, updatedMs: Long): MatchResult {
        val tripId = update.trip.trip_id ?: return MatchResult.Unmatched(null, "no trip_id")
        val trip = timetable.trip(tripId) ?: return MatchResult.Unmatched(tripId, "cancelled trip_id not in timetable")
        val stops = trip.stopTimes.sortedBy { it.stopSequence }
        val startDate = resolveStartDate(update, trip, stops, arrayOfNulls(stops.size), feedTimeMs)
            ?: return MatchResult.Unmatched(tripId, "start_date missing/invalid or trip does not run on it")
        return MatchResult.Matched(
            TripDelta(
                tripId = tripId,
                startDate = startDate,
                routeId = trip.routeId,
                status = TripStatus.CANCELLED,
                vehicleId = update.vehicle?.id,
                updatedMs = updatedMs,
            ),
        )
    }

    // ---- added / replacement trips ----

    private fun matchAdded(update: TripUpdate, status: TripStatus, feedTimeMs: Long, updatedMs: Long): MatchResult {
        val descriptor = update.trip
        val properties = update.trip_properties
        val tripId = properties?.trip_id ?: descriptor.trip_id
            ?: return MatchResult.Unmatched(null, "added trip without trip_id")
        // For REPLACEMENT (and DUPLICATED) the descriptor names a timetabled trip whose times we can use.
        val base = descriptor.trip_id?.let { timetable.trip(it) }
        val baseStops = base?.stopTimes?.sortedBy { it.stopSequence }.orEmpty()

        val firstAbsoluteMs = update.stop_time_update.firstNotNullOfOrNull { stu ->
            (stu.arrival?.time ?: stu.departure?.time)?.takeIf { it > 0 }?.let { it * 1000 }
        }
        val startDate = (properties?.start_date ?: descriptor.start_date)?.takeIf { isServiceDate(it) }
            ?: base?.let { resolveStartDate(update, it, baseStops, resolveUpdates(update, baseStops), feedTimeMs) }
            ?: ServiceTime.localDate(firstAbsoluteMs ?: feedTimeMs)
        val reference = ServiceTime.referenceMillis(startDate)

        val added = ArrayList<AddedStop>()
        for ((index, stu) in update.stop_time_update.withIndex()) {
            if (stu.schedule_relationship == TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED) continue
            val sequence = stu.stop_sequence ?: (index + 1)
            val scheduled = baseStops.firstOrNull { it.stopSequence == sequence }
            val stopId = stu.stop_time_properties?.assigned_stop_id ?: stu.stop_id ?: scheduled?.stopId ?: continue
            val arrival = absoluteMs(stu.arrival, reference, scheduled?.arrivalSecs)
            val departure = absoluteMs(stu.departure, reference, scheduled?.departureSecs)
            added += AddedStop(sequence, stopId, arrival ?: departure, departure ?: arrival)
        }
        if (added.isEmpty()) return MatchResult.Unmatched(tripId, "added trip without usable stops")
        val cutoff = feedTimeMs - pastStopGraceMs
        val firstRemaining = added.indexOfFirst { (it.departureMs ?: it.arrivalMs ?: Long.MAX_VALUE) >= cutoff }
        if (firstRemaining < 0) return MatchResult.Finished(TripKey(tripId, startDate))

        val headsign = properties?.trip_headsign
            ?: base?.headsign
            ?: update.stop_time_update.lastOrNull()?.stop_time_properties?.stop_headsign
            ?: timetable.stop(added.last().stopId)?.let { stop -> stop.parentId?.let { timetable.stop(it) } ?: stop }?.name
        return MatchResult.Matched(
            TripDelta(
                tripId = tripId,
                startDate = startDate,
                routeId = descriptor.route_id ?: base?.routeId ?: "",
                status = status,
                addedStops = added.subList(firstRemaining, added.size).toList(),
                headsign = headsign,
                vehicleId = update.vehicle?.id,
                updatedMs = updatedMs,
            ),
        )
    }

    // ---- helpers ----

    /** Maps each scheduled stop index to its StopTimeUpdate, keyed by stop_sequence or (in order) by stop_id. */
    private fun resolveUpdates(update: TripUpdate, stops: List<ScheduledStopTime>): Array<TripUpdate.StopTimeUpdate?> {
        val resolved = arrayOfNulls<TripUpdate.StopTimeUpdate>(stops.size)
        var cursor = 0
        for (stu in update.stop_time_update) {
            val index = when {
                stu.stop_sequence != null -> stops.indexOfFirst { it.stopSequence == stu.stop_sequence }
                stu.stop_id != null -> indexOfStop(stops, stu.stop_id, cursor)
                else -> -1
            }
            if (index < 0) continue
            resolved[index] = stu
            cursor = index + 1
        }
        return resolved
    }

    private fun indexOfStop(stops: List<ScheduledStopTime>, stopId: StopId, from: Int): Int {
        for (i in from until stops.size) if (stops[i].stopId == stopId) return i
        for (i in from until stops.size) if (sameStation(stopId, stops[i].stopId)) return i
        for (i in 0 until minOf(from, stops.size)) if (stops[i].stopId == stopId) return i
        return -1
    }

    private fun resolveStartDate(
        update: TripUpdate,
        trip: ScheduledTrip,
        stops: List<ScheduledStopTime>,
        resolved: Array<TripUpdate.StopTimeUpdate?>,
        feedTimeMs: Long,
    ): ServiceDate? {
        val given = update.trip.start_date
        if (given != null) {
            return given.takeIf { isServiceDate(it) && timetable.runsOn(trip.tripId, it) }
        }
        return inferStartDate(trip, stops, resolved, feedTimeMs)
    }

    /**
     * Picks the service date (yesterday, today or tomorrow in Sydney) on which the trip runs and whose
     * scheduled times fit the update: the closest absolute stop time if the update has one, else the feed
     * time against the trip's scheduled span. Ties or poor fits give null.
     */
    private fun inferStartDate(
        trip: ScheduledTrip,
        stops: List<ScheduledStopTime>,
        resolved: Array<TripUpdate.StopTimeUpdate?>,
        feedTimeMs: Long,
    ): ServiceDate? {
        if (stops.isEmpty()) return null
        val today = LocalDate.parse(ServiceTime.localDate(feedTimeMs), DateTimeFormatter.BASIC_ISO_DATE)
        val candidates = listOf(today.minusDays(1), today, today.plusDays(1))
            .map { it.format(DateTimeFormatter.BASIC_ISO_DATE) }
            .filter { timetable.runsOn(trip.tripId, it) }
        if (candidates.isEmpty()) return null

        var anchor: Pair<Int, Long>? = null // scheduled secs, observed epoch ms
        for ((i, stu) in resolved.withIndex()) {
            stu ?: continue
            val arrivalTime = stu.arrival?.time?.takeIf { it > 0 }
            val departureTime = stu.departure?.time?.takeIf { it > 0 }
            if (arrivalTime != null) { anchor = stops[i].arrivalSecs to arrivalTime * 1000; break }
            if (departureTime != null) { anchor = stops[i].departureSecs to departureTime * 1000; break }
        }
        val scored = candidates.map { date ->
            val reference = ServiceTime.referenceMillis(date)
            val score = if (anchor != null) {
                abs(anchor.second - (reference + anchor.first * 1000L))
            } else {
                val start = reference + stops.first().departureSecs * 1000L
                val end = reference + stops.last().arrivalSecs * 1000L
                when {
                    feedTimeMs < start -> start - feedTimeMs
                    feedTimeMs > end -> feedTimeMs - end
                    else -> 0L
                }
            }
            date to score
        }.sortedBy { it.second }
        val best = scored.first()
        if (best.second > maxInferenceGapMs) return null
        if (scored.size > 1 && scored[1].second == best.second) return null
        return best.first
    }

    private fun eventDelay(event: TripUpdate.StopTimeEvent?, referenceMs: Long, scheduledSecs: Int): Int? {
        event ?: return null
        val time = event.time
        if (time != null && time > 0) return ((time * 1000 - (referenceMs + scheduledSecs * 1000L)) / 1000).toInt()
        return event.delay
    }

    private fun absoluteMs(event: TripUpdate.StopTimeEvent?, referenceMs: Long, scheduledSecs: Int?): Long? {
        event ?: return null
        event.time?.takeIf { it > 0 }?.let { return it * 1000 }
        val delay = event.delay ?: return null
        scheduledSecs ?: return null
        return referenceMs + (scheduledSecs + delay) * 1000L
    }

    private fun liveStopId(stu: TripUpdate.StopTimeUpdate): StopId? =
        stu.stop_time_properties?.assigned_stop_id ?: stu.stop_id

    /** True if both stops are platforms of the same station, or one is the other's station. */
    private fun sameStation(a: StopId, b: StopId): Boolean {
        val parentA = timetable.stop(a)?.parentId
        val parentB = timetable.stop(b)?.parentId
        return (parentA != null && parentA == parentB) || parentA == b || parentB == a
    }

    private fun isServiceDate(text: String): Boolean = text.length == 8 && text.all { it.isDigit() }
}
