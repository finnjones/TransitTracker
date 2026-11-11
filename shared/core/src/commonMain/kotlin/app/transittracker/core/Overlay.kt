package app.transittracker.core

import app.transittracker.model.Departure
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Mode
import app.transittracker.model.RouteId
import app.transittracker.model.ScheduledDeparture
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceDetail
import app.transittracker.model.ServiceRef
import app.transittracker.model.ServiceTime
import app.transittracker.model.StopCall
import app.transittracker.model.StopDelta
import app.transittracker.model.StopId
import app.transittracker.model.Timetable
import app.transittracker.model.TripDelta
import app.transittracker.model.TripId
import app.transittracker.model.TripStatus

/** Key of a live trip: GTFS trip id plus service (start) date. */
data class TripKey(val tripId: TripId, val serviceDate: ServiceDate)

/** One trip's live update as received, with the (server-corrected) time we received it. */
data class LiveTrip(val delta: TripDelta, val receivedMs: Long)

/**
 * Live trip updates currently known to the phone.
 *
 * @property pollIntervalMs the server's current poll interval; data older than twice this is stale.
 */
data class LiveSnapshot(
    val trips: Map<TripKey, LiveTrip> = emptyMap(),
    val pollIntervalMs: Long = 30_000,
    val lastUpdateMs: Long? = null,
    val feedsStale: Boolean = false,
) {
    operator fun get(tripId: TripId, serviceDate: ServiceDate): LiveTrip? = trips[TripKey(tripId, serviceDate)]

    companion object {
        val EMPTY = LiveSnapshot()
    }
}

/** Live delay at or below this magnitude (seconds) counts as on time. */
internal const val ON_TIME_THRESHOLD_S = 60

/** Status for a known delay: |delay| < 60 s is on time. */
fun statusForDelay(delaySeconds: Int): DepartureStatus = when {
    delaySeconds >= ON_TIME_THRESHOLD_S -> DepartureStatus.LATE
    delaySeconds <= -ON_TIME_THRESHOLD_S -> DepartureStatus.EARLY
    else -> DepartureStatus.ON_TIME
}

/**
 * Applies live updates ([LiveSnapshot]) to scheduled timetable results.
 *
 * Rules:
 * - expected departure = scheduled + delay at the origin stop sequence (departure delay, else
 *   arrival delay); expected arrival likewise at the destination (arrival delay first);
 * - stops missing from the delta inherit the delay of the previous listed stop;
 * - a CANCELLED trip, or one that skips the origin or destination, is [DepartureStatus.CANCELLED];
 * - added / replacement trips not in the timetable come from [TripDelta.addedStops];
 * - the platform is that of [StopDelta.actualStopId] when set ([Departure.platformChanged]);
 * - data is stale when the delta says so or when it is older than 2 × the poll interval.
 */
class Overlay(
    private val timetable: Timetable,
    private val live: LiveSnapshot,
    private val nowMs: Long,
) {
    /** Applies live data (if any) to one scheduled departure. */
    fun departure(s: ScheduledDeparture): Departure {
        val route = timetable.route(s.routeId)
        val liveTrip = live[s.tripId, s.serviceDate]
        val delta = liveTrip?.delta
        val base = Departure(
            ref = ServiceRef(s.tripId, s.serviceDate),
            routeId = s.routeId,
            routeName = route?.shortName ?: s.routeId,
            routeColor = route?.color,
            mode = route?.mode ?: Mode.OTHER,
            headsign = s.headsign,
            originStopId = s.originStopId,
            originPlatform = timetable.stop(s.originStopId)?.platformCode,
            platformChanged = false,
            scheduledDepartureMs = s.departureMillis,
            expectedDepartureMs = s.departureMillis,
            destinationStopId = s.destinationStopId,
            scheduledArrivalMs = s.arrivalMillis,
            expectedArrivalMs = s.arrivalMillis,
            delaySeconds = null,
            status = DepartureStatus.TIMETABLED,
        )
        if (liveTrip == null || delta == null) return base
        val age = dataAge(liveTrip)
        val common = base.copy(
            occupancy = delta.occupancy,
            carCount = delta.carCount,
            dataAgeMs = age,
            stale = isStale(liveTrip),
        )
        if (delta.status == TripStatus.CANCELLED) return common.copy(status = DepartureStatus.CANCELLED)

        val originDelta = delta.stops.firstOrNull { it.stopSequence == s.originStopSequence }
        val destDelta = delta.stops.firstOrNull { it.stopSequence == s.destinationStopSequence }
        if (originDelta?.skipped == true || destDelta?.skipped == true) {
            return common.copy(status = DepartureStatus.CANCELLED)
        }
        val depDelay = originDelta?.let { it.departureDelayS ?: it.arrivalDelayS }
            ?: inheritedDelay(delta.stops, s.originStopSequence)
        val arrDelay = destDelta?.let { it.arrivalDelayS ?: it.departureDelayS }
            ?: inheritedDelay(delta.stops, s.destinationStopSequence)
            ?: depDelay

        val actual = originDelta?.actualStopId?.takeIf { it != s.originStopId }
        val platformCommon = if (actual != null) {
            common.copy(
                originStopId = actual,
                originPlatform = timetable.stop(actual)?.platformCode,
                platformChanged = true,
            )
        } else {
            common
        }
        if (depDelay == null) {
            // Live data exists for the trip but says nothing about this stop: show the timetable.
            return platformCommon.copy(status = DepartureStatus.TIMETABLED)
        }
        return platformCommon.copy(
            expectedDepartureMs = s.departureMillis + depDelay * 1000L,
            expectedArrivalMs = s.arrivalMillis + (arrDelay ?: depDelay) * 1000L,
            delaySeconds = depDelay,
            status = statusForDelay(depDelay),
        )
    }

    /**
     * Added / replacement trips (not in the timetable) that call at one of [originStopIds] and
     * later at one of [destinationStopIds], optionally restricted to [routeIds].
     */
    fun addedDepartures(
        originStopIds: Set<StopId>,
        destinationStopIds: Set<StopId>,
        routeIds: Set<RouteId>?,
    ): List<Departure> = live.trips.values.mapNotNull { liveTrip ->
        val d = liveTrip.delta
        if (d.status != TripStatus.ADDED && d.status != TripStatus.REPLACEMENT) return@mapNotNull null
        if (d.addedStops.isEmpty()) return@mapNotNull null
        if (routeIds != null && d.routeId !in routeIds) return@mapNotNull null
        if (timetable.trip(d.tripId) != null) return@mapNotNull null // scheduled; handled by departure()
        val stops = d.addedStops.sortedBy { it.stopSequence }
        val oi = stops.indexOfFirst { it.stopId in originStopIds }
        if (oi < 0) return@mapNotNull null
        val origin = stops[oi]
        val dest = stops.drop(oi + 1).firstOrNull { it.stopId in destinationStopIds } ?: return@mapNotNull null
        val dep = origin.departureMs ?: origin.arrivalMs ?: return@mapNotNull null
        val arr = dest.arrivalMs ?: dest.departureMs ?: return@mapNotNull null
        val route = timetable.route(d.routeId)
        Departure(
            ref = ServiceRef(d.tripId, d.startDate),
            routeId = d.routeId,
            routeName = route?.shortName ?: d.routeId,
            routeColor = route?.color,
            mode = route?.mode ?: Mode.OTHER,
            headsign = d.headsign ?: stopName(stops.last().stopId),
            originStopId = origin.stopId,
            originPlatform = timetable.stop(origin.stopId)?.platformCode,
            platformChanged = false,
            scheduledDepartureMs = dep,
            expectedDepartureMs = dep,
            destinationStopId = dest.stopId,
            scheduledArrivalMs = arr,
            expectedArrivalMs = arr,
            delaySeconds = null,
            status = DepartureStatus.ADDED,
            occupancy = d.occupancy,
            carCount = d.carCount,
            dataAgeMs = dataAge(liveTrip),
            stale = isStale(liveTrip),
        )
    }

    /** All calls of one service with live data applied, or null if the service is unknown. */
    fun serviceDetail(ref: ServiceRef): ServiceDetail? {
        val liveTrip = live[ref.tripId, ref.serviceDate]
        val delta = liveTrip?.delta
        val trip = timetable.trip(ref.tripId)
        if (trip == null) {
            if (delta == null || delta.addedStops.isEmpty()) return null
            return addedServiceDetail(ref, liveTrip)
        }
        val route = timetable.route(trip.routeId)
        val deltas = delta?.stops?.associateBy { it.stopSequence } ?: emptyMap()
        val firstRemaining = delta?.stops?.minOfOrNull { it.stopSequence }
        val cancelled = delta?.status == TripStatus.CANCELLED
        var lastDelay: Int? = null
        val calls = trip.stopTimes.sortedBy { it.stopSequence }.map { st ->
            val schedArr = ServiceTime.toEpochMillis(ref.serviceDate, st.arrivalSecs)
            val schedDep = ServiceTime.toEpochMillis(ref.serviceDate, st.departureSecs)
            val stopDelta = deltas[st.stopSequence]
            val stop = timetable.stop(st.stopId)
            var platform = stop?.platformCode
            var platformChanged = false
            var stopId = st.stopId
            var expArr: Long? = schedArr
            var expDep: Long? = schedDep
            var skipped = false
            var passedByFeed = false
            when {
                delta == null || cancelled -> Unit
                stopDelta != null -> {
                    val arrDelay = stopDelta.arrivalDelayS ?: stopDelta.departureDelayS
                    val depDelay = stopDelta.departureDelayS ?: stopDelta.arrivalDelayS
                    if (depDelay != null) lastDelay = depDelay
                    expArr = arrDelay?.let { schedArr + it * 1000L } ?: lastDelay?.let { schedArr + it * 1000L } ?: schedArr
                    expDep = depDelay?.let { schedDep + it * 1000L } ?: lastDelay?.let { schedDep + it * 1000L } ?: schedDep
                    skipped = stopDelta.skipped
                    val actual = stopDelta.actualStopId?.takeIf { it != st.stopId }
                    if (actual != null) {
                        stopId = actual
                        platform = timetable.stop(actual)?.platformCode
                        platformChanged = true
                    }
                }
                firstRemaining != null && st.stopSequence < firstRemaining -> {
                    // Already passed according to the feed; actual times unknown.
                    passedByFeed = true
                    expArr = null
                    expDep = null
                }
                else -> {
                    val d = lastDelay
                    if (d != null) {
                        expArr = schedArr + d * 1000L
                        expDep = schedDep + d * 1000L
                    }
                }
            }
            StopCall(
                stopSequence = st.stopSequence,
                stopId = stopId,
                stopName = stopName(st.stopId),
                platform = platform,
                platformChanged = platformChanged,
                scheduledArrivalMs = schedArr,
                scheduledDepartureMs = schedDep,
                expectedArrivalMs = expArr,
                expectedDepartureMs = expDep,
                skipped = skipped || cancelled,
                passed = passedByFeed || (expDep ?: schedDep) < nowMs,
            )
        }
        val status = when {
            delta == null -> DepartureStatus.TIMETABLED
            cancelled -> DepartureStatus.CANCELLED
            delta.stops.isEmpty() -> DepartureStatus.TIMETABLED
            else -> {
                val reference = calls.firstOrNull { !it.passed && !it.skipped } ?: calls.lastOrNull { it.expectedDepartureMs != null }
                val delay = reference?.let { c ->
                    c.expectedDepartureMs?.let { exp -> ((exp - c.scheduledDepartureMs!!) / 1000).toInt() }
                }
                delay?.let(::statusForDelay) ?: DepartureStatus.TIMETABLED
            }
        }
        return ServiceDetail(
            ref = ref,
            route = route,
            headsign = trip.headsign,
            status = status,
            calls = calls,
            occupancy = delta?.occupancy ?: app.transittracker.model.Occupancy.UNKNOWN,
            carCount = delta?.carCount,
            dataAgeMs = liveTrip?.let(::dataAge),
            stale = liveTrip?.let(::isStale) ?: false,
        )
    }

    private fun addedServiceDetail(ref: ServiceRef, liveTrip: LiveTrip): ServiceDetail {
        val d = liveTrip.delta
        val stops = d.addedStops.sortedBy { it.stopSequence }
        val calls = stops.map { s ->
            val stop = timetable.stop(s.stopId)
            StopCall(
                stopSequence = s.stopSequence,
                stopId = s.stopId,
                stopName = stopName(s.stopId),
                platform = stop?.platformCode,
                platformChanged = false,
                scheduledArrivalMs = s.arrivalMs,
                scheduledDepartureMs = s.departureMs,
                expectedArrivalMs = s.arrivalMs,
                expectedDepartureMs = s.departureMs,
                skipped = false,
                passed = (s.departureMs ?: s.arrivalMs ?: Long.MAX_VALUE) < nowMs,
            )
        }
        return ServiceDetail(
            ref = ref,
            route = timetable.route(d.routeId),
            headsign = d.headsign ?: calls.lastOrNull()?.stopName ?: "",
            status = if (d.status == TripStatus.CANCELLED) DepartureStatus.CANCELLED else DepartureStatus.ADDED,
            calls = calls,
            occupancy = d.occupancy,
            carCount = d.carCount,
            dataAgeMs = dataAge(liveTrip),
            stale = isStale(liveTrip),
        )
    }

    /** Station name for a platform stop (the platform is shown separately), else the stop's own name. */
    private fun stopName(id: StopId): String {
        val stop = timetable.stop(id) ?: return id
        return stop.parentId?.let { timetable.stop(it)?.name } ?: stop.name
    }

    private fun dataAge(t: LiveTrip): Long = (nowMs - t.receivedMs).coerceAtLeast(0)

    private fun isStale(t: LiveTrip): Boolean = t.delta.stale || dataAge(t) > 2 * live.pollIntervalMs

    /** Delay of the closest listed stop before [sequence] (GTFS-R propagation), if any. */
    private fun inheritedDelay(stops: List<StopDelta>, sequence: Int): Int? =
        stops.filter { it.stopSequence < sequence }
            .maxByOrNull { it.stopSequence }
            ?.let { it.departureDelayS ?: it.arrivalDelayS }
}
