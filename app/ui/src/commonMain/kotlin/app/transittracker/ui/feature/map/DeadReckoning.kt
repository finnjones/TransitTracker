package app.transittracker.ui.feature.map

import app.transittracker.model.StopCall
import app.transittracker.model.VehiclePosition
import app.transittracker.ui.strip.vehicleProgress

/**
 * Where to draw a tracked vehicle between reports (plan 5.5): at each report it moves to the
 * reported position (snapped to the line) over [GLIDE_MS], then creeps along the line at
 * [speedMps] until the next report. It never passes [limitDistance] (the next stop: the vehicle
 * may be held there) and never runs backwards ([fromDistance] is where it was drawn when the
 * report came in, and nothing is drawn behind it).
 *
 * Distances are metres along the service's path. Pure: [distanceAt] is a function of time.
 */
data class Reckoning(
    /** Where the vehicle was drawn when this report was applied (the glide starts here). */
    val fromDistance: Double,
    /** The reported position, snapped to the path; never behind [fromDistance]. */
    val reportDistance: Double,
    /** When the reported position was true (the vehicle's timestamp). */
    val reportMs: Long,
    /** When this report was applied on screen (the glide's start). */
    val appliedMs: Long,
    /** Creeping starts no earlier than this (a vehicle standing at a stop waits for departure). */
    val creepFromMs: Long,
    val speedMps: Double,
    /** The next stop ahead: creeping stops here. */
    val limitDistance: Double,
) {
    /** The distance to draw at [nowMs]; with [reduceMotion] the vehicle stays at the report. */
    fun distanceAt(nowMs: Long, reduceMotion: Boolean = false): Double {
        val ceiling = maxOf(limitDistance, reportDistance)
        if (reduceMotion) return reportDistance
        val creepMs = (nowMs - maxOf(reportMs, creepFromMs)).coerceAtLeast(0)
        val target = (reportDistance + speedMps * creepMs / 1000.0).coerceIn(reportDistance, ceiling)
        val sinceApplied = nowMs - appliedMs
        if (sinceApplied >= GLIDE_MS || fromDistance >= target) return maxOf(target, fromDistance)
        val t = (sinceApplied.coerceAtLeast(0)).toDouble() / GLIDE_MS
        return fromDistance + (target - fromDistance) * t
    }

    companion object {
        /** A new report is reached over one second (plan 3.3: "steps to the new place over one second"). */
        const val GLIDE_MS = 1_000L

        /** Fastest believable creep, about 130 km/h. */
        const val MAX_SPEED_MPS = 36.0
    }
}

/** A reported position older than this is not shown (plan 5.5, backend rule). */
const val POSITION_MAX_AGE_MS = 2 * 60_000L

/** True when [vehicle] is recent enough to draw at [nowMs]. */
fun positionFresh(vehicle: VehiclePosition?, nowMs: Long): Boolean =
    vehicle != null && nowMs - vehicle.timestampMs <= POSITION_MAX_AGE_MS

private fun StopCall.arrivalMs(): Long? = expectedArrivalMs ?: scheduledArrivalMs ?: expectedDepartureMs ?: scheduledDepartureMs
private fun StopCall.departureMs(): Long? = expectedDepartureMs ?: scheduledDepartureMs ?: expectedArrivalMs ?: scheduledArrivalMs

/**
 * The [Reckoning] for a new report.
 *
 * - The position is snapped to [geometry]'s path, searched from where the vehicle was last drawn
 *   ([previous]) so it never jumps to another stretch of a line that doubles back.
 * - The next stop is the first non-skipped call beyond the snapped point.
 * - Speed is the reported speed when there is one, else what it takes to reach the next stop at
 *   its expected arrival; zero when that time has passed (the vehicle waits at the limit).
 * - A vehicle standing at a stop (within [AT_STOP_METRES]) creeps only after that stop's departure.
 * - Never backwards: a report behind the drawn position keeps the drawn position.
 */
fun reckon(
    vehicle: VehiclePosition,
    calls: List<StopCall>,
    geometry: ServiceGeometry,
    nowMs: Long,
    previous: Reckoning? = null,
    projection: MapProjection = MapProjection.Sydney,
): Reckoning {
    val path = geometry.path
    val shownBefore = previous?.distanceAt(nowMs)
    val point = projection.project(vehicle.position)
    val snapped = if (shownBefore != null) {
        // Prefer the stretch ahead of where it was drawn; fall back to the whole path.
        val ahead = path.snap(point, from = shownBefore)
        val anywhere = path.snap(point)
        if (ahead.offset <= anywhere.offset + 50) ahead else anywhere
    } else {
        path.snap(point)
    }
    val from = shownBefore ?: snapped.distance
    val report = maxOf(snapped.distance, from)

    val dists = geometry.stopDistances
    val nextIndex = calls.indices.firstOrNull { i -> !calls[i].skipped && dists[i] > report + AT_STOP_METRES }
    val atIndex = calls.indices.firstOrNull { i -> !calls[i].skipped && kotlin.math.abs(dists[i] - report) <= AT_STOP_METRES }
    val limit = nextIndex?.let { dists[it] } ?: path.length
    val creepFrom = atIndex?.let { calls[it].departureMs() } ?: vehicle.timestampMs
    val speed = when {
        vehicle.speedMps != null && vehicle.speedMps!! > 0f -> vehicle.speedMps!!.toDouble()
        nextIndex == null -> 0.0
        else -> {
            val arrive = calls[nextIndex].arrivalMs()
            val start = maxOf(vehicle.timestampMs, creepFrom)
            if (arrive == null || arrive <= start) 0.0 else (limit - report) / ((arrive - start) / 1000.0)
        }
    }.coerceIn(0.0, Reckoning.MAX_SPEED_MPS)
    return Reckoning(
        fromDistance = from,
        reportDistance = report,
        reportMs = vehicle.timestampMs,
        appliedMs = nowMs,
        creepFromMs = creepFrom,
        speedMps = speed,
        limitDistance = limit,
    )
}

/** Within this distance of a stop the vehicle counts as standing at it. */
const val AT_STOP_METRES = 60.0

/**
 * The timetabled position at [nowMs] (the ghost): [vehicleProgress] from the stop times, laid on
 * the path between the two stops' distances. Null when no call has a time.
 */
fun timetabledDistance(calls: List<StopCall>, geometry: ServiceGeometry, nowMs: Long): Double? {
    val p = vehicleProgress(calls, nowMs) ?: return null
    val d = geometry.stopDistances
    if (p.fromIndex !in d.indices || p.toIndex !in d.indices) return null
    return d[p.fromIndex] + (d[p.toIndex] - d[p.fromIndex]) * p.progress
}
