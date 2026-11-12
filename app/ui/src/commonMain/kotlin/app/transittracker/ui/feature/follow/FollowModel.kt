package app.transittracker.ui.feature.follow

import app.transittracker.model.Departure
import app.transittracker.model.Journey
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopCall
import app.transittracker.model.StopId
import app.transittracker.ui.feature.map.MapPath
import app.transittracker.ui.feature.map.MapPoint
import app.transittracker.ui.feature.map.MapProjection
import app.transittracker.ui.platform.LocationFix
import kotlin.math.abs

/*
 * FOLLOW MY JOURNEY: which saved-trip journey the phone is on, from its location.
 *
 * The core lists the journeys of each saved trip that are under way (the first leg has left, the
 * last has not arrived: `TransitCore.underway`). For each one this works out where the user
 * should be at the time of a location fix, leg by leg (on the train between two stops, walking
 * to the bus stop, on the bus), and picks the journey the fix fits best. Everything here is pure;
 * [JourneyFollower] fetches the data, asks for the location and keeps the answer steady.
 */

enum class FollowStage {
    /** On the vehicle of [FollowMatch.legIndex]. */
    RIDING,

    /** Between rides, going to (or waiting for) the vehicle of [FollowMatch.legIndex]. */
    CHANGING,
}

/** A leg of a journey with the stops it rides, from where it is boarded to where it is left. */
data class RiddenLeg(val departure: Departure, val calls: List<StopCall>)

/** An underway journey of saved trip [tripId], with the ridden stops of each of its legs. */
data class FollowCandidate(val tripId: String, val journey: Journey, val legs: List<RiddenLeg>)

/** The journey a fix fits: [key] is its first leg's service (stable while the journey runs). */
data class FollowMatch(
    val tripId: String,
    val key: ServiceRef,
    val legIndex: Int,
    val stage: FollowStage,
    /** How far the fix was from where the user should be, metres (lower is a better fit). */
    val offMetres: Double,
)

/** Matching thresholds; the defaults suit Sydney trains, metro, light rail, buses and ferries. */
data class FollowTuning(
    /** Furthest from the line (between its stops) a rider can be. Lines curve between stations. */
    val corridorM: Double = 450.0,
    /** Furthest along the line from where the timetable (with live data) puts the vehicle. */
    val alongM: Double = 1_500.0,
    /** Furthest from the walk between two rides while changing. */
    val changeM: Double = 350.0,
    /** Within this of the first stop the user is waiting there, not riding a service that has left. */
    val atFirstStopM: Double = 200.0,
    /** Times are stretched by this either way (a few minutes off the live estimate still counts). */
    val slackMs: Long = 3 * 60_000L,
)

private fun StopCall.depMs(): Long? = expectedDepartureMs ?: scheduledDepartureMs ?: expectedArrivalMs ?: scheduledArrivalMs
private fun StopCall.arrMs(): Long? = expectedArrivalMs ?: scheduledArrivalMs ?: expectedDepartureMs ?: scheduledDepartureMs

/**
 * The calls of a service from [board] to [alight] (inclusive), matched by stop id; the whole
 * service from the first or to the last call when a stop is not found. Empty when [alight]
 * comes before [board].
 */
fun riddenCalls(calls: List<StopCall>, board: StopId, alight: StopId): List<StopCall> {
    if (calls.isEmpty()) return emptyList()
    val from = calls.indexOfFirst { it.stopId == board }.takeIf { it >= 0 } ?: 0
    val to = calls.indexOfLast { it.stopId == alight }.takeIf { it >= 0 } ?: calls.lastIndex
    return if (to < from) emptyList() else calls.subList(from, to + 1)
}

/** A ridden leg on the map plane: its path through the stops, and each stop's distance along it. */
private class LegLine(val path: MapPath, val calls: List<StopCall>, val distances: List<Double>) {
    /** Where the timetable (with live data) puts the vehicle at [t], as a distance along [path]. */
    fun expectedAlong(t: Long): Double {
        val first = calls.first().depMs() ?: return 0.0
        if (t <= first) return 0.0
        for (i in 0 until calls.size - 1) {
            val dep = calls[i].depMs() ?: continue
            val arr = calls[i + 1].arrMs() ?: continue
            if (t < dep) return distances[i] // standing at stop i
            if (t < arr) {
                val f = if (arr > dep) (t - dep).toDouble() / (arr - dep) else 1.0
                return distances[i] + (distances[i + 1] - distances[i]) * f
            }
        }
        return distances.last()
    }

    companion object {
        /** Null when fewer than two of the ridden stops have a position. */
        fun of(leg: RiddenLeg, projection: MapProjection): LegLine? {
            val located = leg.calls.filter { it.position != null }
            if (located.size < 2) return null
            val path = MapPath(located.map { projection.project(it.position!!) })
            return LegLine(path, located, path.cumulative.toList())
        }
    }
}

/**
 * The journey among [candidates] that [fix] fits best, or null when it fits none.
 *
 * For each leg: while it should be running (its departure to its arrival, give or take
 * [FollowTuning.slackMs]) the fix must lie within [FollowTuning.corridorM] of the leg's line and
 * within [FollowTuning.alongM] along it of where the vehicle should be, so the 08:02 is told from
 * the 08:12 on the same line. Between two legs the fix must lie near the walk from where the
 * first is left to where the next is boarded. A fix at the first stop of the first leg never
 * counts as riding it: the user is waiting there, for the next service.
 */
fun matchFix(
    fix: LocationFix,
    candidates: List<FollowCandidate>,
    tuning: FollowTuning = FollowTuning(),
    projection: MapProjection = MapProjection.Sydney,
): FollowMatch? {
    val t = fix.timeMs
    val p = projection.project(fix.position)
    val accuracy = (fix.accuracyM ?: 0.0).coerceIn(0.0, 500.0)
    var best: FollowMatch? = null
    fun offer(m: FollowMatch) {
        if (best == null || m.offMetres < best!!.offMetres) best = m
    }
    for (c in candidates) {
        val key = c.journey.departure.ref
        val lines = c.legs.map { LegLine.of(it, projection) }
        for ((k, leg) in c.legs.withIndex()) {
            val d = leg.departure
            // Riding leg k.
            val line = lines[k]
            if (line != null && t >= d.expectedDepartureMs - tuning.slackMs && t <= d.expectedArrivalMs + tuning.slackMs) {
                val snap = line.path.snap(p)
                val waitingAtStart = k == 0 && p.distanceTo(line.path.points.first()) <= tuning.atFirstStopM
                val along = abs(snap.distance - line.expectedAlong(t))
                if (!waitingAtStart && snap.offset <= tuning.corridorM + accuracy && along <= tuning.alongM + accuracy) {
                    offer(FollowMatch(c.tripId, key, k, FollowStage.RIDING, along + snap.offset))
                }
            }
            // Changing onto leg k.
            if (k > 0) {
                val prev = c.legs[k - 1]
                val from = prev.calls.lastOrNull { it.position != null }?.position
                val to = leg.calls.firstOrNull { it.position != null }?.position
                if (from != null && to != null &&
                    t >= prev.departure.expectedArrivalMs - tuning.slackMs && t <= d.expectedDepartureMs + tuning.slackMs
                ) {
                    val walk = MapPath(listOf(projection.project(from), projection.project(to)))
                    val off = walk.snap(p).offset
                    if (off <= tuning.changeM + accuracy) offer(FollowMatch(c.tripId, key, k, FollowStage.CHANGING, off))
                }
            }
        }
    }
    return best
}

/** What following is doing, for the card and the map. */
enum class FollowStatus {
    /** Switched off in Settings. */
    OFF,

    /** Location permission not given (or location off on the phone). */
    NO_LOCATION,

    /** No saved trip has a journey under way: nothing to follow. */
    IDLE,

    /** Journeys are under way, but the phone is not on one (or not sure yet). */
    LOOKING,

    /** On [FollowState.match]. */
    FOLLOWING,
}

data class FollowState(
    val status: FollowStatus = FollowStatus.IDLE,
    val match: FollowMatch? = null,
    /** The last location used. */
    val fix: LocationFix? = null,
)

/**
 * Keeps following steady between fixes: a journey is followed after [pinAfter] fixes in a row fit
 * it (one stray fix does not switch the card), and dropped after [dropAfter] fixes in a row fit
 * nothing (a tunnel or a bad fix does not either). A journey the user said they are not on
 * ([dismissed]) is never followed.
 */
data class FollowMemory(
    val pinned: FollowMatch? = null,
    val pending: ServiceRef? = null,
    val pendingCount: Int = 0,
    val misses: Int = 0,
    val dismissed: Set<ServiceRef> = emptySet(),
) {
    fun next(match: FollowMatch?, underway: Set<ServiceRef>, pinAfter: Int = 2, dropAfter: Int = 3): FollowMemory {
        // A followed journey that has arrived (no longer under way) is finished.
        val held = pinned?.takeIf { it.key in underway }
        val m = match?.takeIf { it.key !in dismissed }
        if (m != null && held != null && m.key == held.key) return copy(pinned = m, pending = null, pendingCount = 0, misses = 0)
        // Anything else is a miss for the followed journey.
        val missed = if (held != null) misses + 1 else 0
        val keep = held?.takeIf { missed < dropAfter }
        val keptMisses = if (keep != null) missed else 0
        if (m == null) return copy(pinned = keep, pending = null, pendingCount = 0, misses = keptMisses)
        val count = if (pending == m.key) pendingCount + 1 else 1
        return if (count >= pinAfter) copy(pinned = m, pending = null, pendingCount = 0, misses = 0)
        else copy(pinned = keep, pending = m.key, pendingCount = count, misses = keptMisses)
    }

    fun dismiss(key: ServiceRef): FollowMemory =
        copy(pinned = pinned?.takeIf { it.key != key }, pending = pending?.takeIf { it != key }, dismissed = dismissed + key)
}
