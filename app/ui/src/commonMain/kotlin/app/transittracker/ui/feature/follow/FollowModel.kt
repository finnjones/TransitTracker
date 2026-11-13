package app.transittracker.ui.feature.follow

import app.transittracker.model.Departure
import app.transittracker.model.Journey
import app.transittracker.model.LatLng
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopCall
import app.transittracker.model.StopId
import app.transittracker.model.VehiclePosition
import app.transittracker.ui.feature.map.MapPath
import app.transittracker.ui.feature.map.MapProjection
import app.transittracker.ui.feature.map.ServiceGeometry
import app.transittracker.ui.feature.map.followShape
import app.transittracker.ui.platform.LocationFix
import kotlin.math.abs

/*
 * FOLLOW MY JOURNEY: which saved-trip journey the phone is on, from its location alone.
 *
 * The core lists the journeys of each saved trip that are under way (the first leg has left, the
 * last has not arrived: `TransitCore.underway`). For each one this works out where the user
 * should be at the time of a location fix, leg by leg (on the train between two stops, walking
 * to the bus stop, on the bus), and how well the fix fits:
 *
 * - along the service's real line (its timetable shape) when there is one, so the corridor is
 *   tight enough to tell a railway from the road beside it; straight stop to stop otherwise;
 * - against the vehicle's own live position when the feed has a fresh one (carried forward to
 *   the time of the fix), else where the timetable puts it;
 * - with the phone's heading (on the move: not the other way down the line, not a cross street)
 *   and speed (not racing along while the train stands at a platform, not walking at 60 km/h).
 *
 * Each fix gives every journey evidence for or against ([FollowReading]); [FollowMemory] adds it
 * up over fixes, so a strong contradiction drops a journey within two fixes while a tunnel or a
 * vague fix only nudges it. Everything here is pure; [JourneyFollower] fetches the data, asks for
 * the location and keeps the memory.
 */

enum class FollowStage {
    /** On the vehicle of [FollowMatch.legIndex]. */
    RIDING,

    /** Between rides, going to (or waiting for) the vehicle of [FollowMatch.legIndex]. */
    CHANGING,
}

/**
 * A leg of a journey with the stops it rides, from where it is boarded to where it is left;
 * [shape] is the whole service's line (empty when the timetable has none) and [vehicle] its
 * latest live position (null when the feed has none).
 */
data class RiddenLeg(
    val departure: Departure,
    val calls: List<StopCall>,
    val shape: List<LatLng> = emptyList(),
    val vehicle: VehiclePosition? = null,
)

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
    /**
     * Which way of the saved trip ([app.transittracker.model.SavedTrip.ways]) the user is on. Two
     * ways can share the vehicle the user is on (stay on the train, or change to a bus), so the
     * same [key] can be followed on either; null when not known.
     */
    val wayId: String? = null,
)

/**
 * What one fix says about one candidate journey: where it fits ([match], null when it does not)
 * and how much that counts for (positive) or against (negative) the journey ([evidence]).
 */
data class FollowReading(
    val tripId: String,
    val key: ServiceRef,
    val wayId: String?,
    val match: FollowMatch?,
    val evidence: Double,
)

/** Matching thresholds and weights; the defaults suit Sydney trains, metro, light rail, buses and ferries. */
data class FollowTuning(
    /** Furthest from a straight stop-to-stop line a rider can be. Lines curve between stations. */
    val corridorM: Double = 450.0,
    /** Furthest from the service's real line (its shape) a rider can be. */
    val shapeCorridorM: Double = 200.0,
    /** Furthest along the line from where the timetable (with live data) puts the vehicle. */
    val alongM: Double = 1_500.0,
    /** Furthest along the line from the vehicle's own live position, carried forward to the fix. */
    val vehicleAlongM: Double = 500.0,
    /**
     * A live vehicle position this far along the line from where the live timetable puts the
     * vehicle is a bad report (a stale position, the depot, the wrong trip) and is not used.
     */
    val vehicleTrustM: Double = 3_000.0,
    /** A live vehicle position older than this (either side of the fix) is not used. */
    val vehicleMaxAgeMs: Long = 2 * 60_000L,
    /** A live vehicle speed older than this is not compared with the phone's. */
    val vehicleSpeedMaxAgeMs: Long = 45_000L,
    /** The phone and its vehicle may differ in speed by this much (they brake and speed up apart). */
    val speedSlackMps: Double = 8.0,
    /** Faster than this the phone is not walking between two rides. */
    val maxChangeSpeedMps: Double = 7.0,
    /** Heading is only judged above this speed (a standing phone has no heading). */
    val minHeadingSpeedMps: Double = 4.0,
    /** Furthest the phone's heading may be from the line's along a shape. */
    val headingToleranceDeg: Double = 75.0,
    /** The same along a straight stop-to-stop line, which cuts corners: only the wrong way counts. */
    val straightHeadingToleranceDeg: Double = 120.0,
    /** Furthest from the walk between two rides while changing. */
    val changeM: Double = 350.0,
    /** Within this of the first stop the user is waiting there, not riding a service that has left. */
    val atFirstStopM: Double = 200.0,
    /** Times are stretched by this either way (a few minutes off the live estimate still counts). */
    val slackMs: Long = 3 * 60_000L,
    /** A fix this accurate (or better) that lies well outside every place the user could be is a clear miss. */
    val clearAccuracyM: Double = 100.0,
    /** A fix vaguer than this counts half, either way. */
    val vagueAccuracyM: Double = 250.0,

    /** Evidence for a journey from a fix that fits it where the timetable puts the vehicle. */
    val fitEvidence: Double = 0.5,
    /** ... that fits it beside the vehicle's own live position. */
    val vehicleFitEvidence: Double = 0.75,
    /** ... that fits it in place, but not in speed (racing past a stopped train; driving, not walking). */
    val mismatchEvidence: Double = -0.25,
    /** ... that does not fit it (a little off, or a fix too vague to be sure). */
    val missEvidence: Double = -0.4,
    /** ... that clearly cannot be on it (an accurate fix far off the line, the vehicle or the walk; the wrong way). */
    val clearMissEvidence: Double = -1.0,
    /** A journey is followed once its score reaches this (two fixes that fit, in a row). */
    val followAt: Double = 1.0,
    /** Scores are kept within these, so no history outweighs a few fresh fixes. */
    val maxScore: Double = 1.5,
    val minScore: Double = -0.5,
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

/** The live position of [ref]'s vehicle among [vehicles], if the feed has one. */
fun vehicleOf(ref: ServiceRef, vehicles: List<VehiclePosition>): VehiclePosition? =
    vehicles.filter { it.tripId == ref.tripId && (it.startDate == null || it.startDate == ref.serviceDate) }.maxByOrNull { it.timestampMs }

/** Smallest angle between two compass headings, 0 to 180. */
internal fun headingGap(a: Double, b: Double): Double {
    val d = abs(a - b) % 360.0
    return if (d > 180.0) 360.0 - d else d
}

/**
 * A ridden leg on the map plane: its path through the stops (along the service's shape when it
 * has one, [shaped]), and each stop's distance along it.
 */
private class LegLine(val path: MapPath, val calls: List<StopCall>, val distances: List<Double>, val shaped: Boolean) {
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

    /**
     * Where [vehicle] is at [t], as a distance along [path]: its live position carried forward
     * (or back) by the timetable's progress between when it was reported and [t]. Null when there
     * is no vehicle, its report is too old, it is not on this stretch of line, or it is too far
     * from where the live timetable puts it to be believed.
     */
    fun vehicleAlong(vehicle: VehiclePosition?, t: Long, tuning: FollowTuning, projection: MapProjection): Double? {
        val v = vehicle ?: return null
        if (abs(t - v.timestampMs) > tuning.vehicleMaxAgeMs) return null
        val s = path.snap(projection.project(v.position))
        if (s.offset > corridor(tuning)) return null
        val expected = expectedAlong(t)
        val along = (s.distance + expected - expectedAlong(v.timestampMs)).coerceIn(0.0, path.length)
        return along.takeIf { abs(it - expected) <= tuning.vehicleTrustM }
    }

    fun corridor(tuning: FollowTuning) = if (shaped) tuning.shapeCorridorM else tuning.corridorM

    /** The line's compass heading (0 = north, clockwise) at [along]. */
    fun compassAt(along: Double): Double = (path.headingAt(along) + 180.0) % 360.0

    companion object {
        /** Null when fewer than two of the ridden stops have a position. */
        fun of(leg: RiddenLeg, projection: MapProjection): LegLine? {
            val located = leg.calls.filter { it.position != null }
            if (located.size < 2) return null
            val points = located.map { projection.project(it.position!!) }
            val straight = MapPath(points)
            if (leg.shape.size >= 2) {
                val g = ServiceGeometry(
                    straight, points, straight.cumulative.toList(), located.map { null },
                    located.map { it.stopName }, located.map { it.stopId }, schematic = false,
                )
                // followShape keeps only the stretch of the shape between the ridden stops.
                val along = followShape(g, MapPath(leg.shape.map(projection::project)))
                if (along !== g) return LegLine(along.path, located, along.stopDistances, shaped = true)
            }
            return LegLine(straight, located, straight.cumulative.toList(), shaped = false)
        }
    }
}

/**
 * The journey among [candidates] that [fix] fits best, or null when it fits none.
 *
 * For each leg: while it should be running (its departure to its arrival, give or take
 * [FollowTuning.slackMs]) the fix must lie near the leg's line (its shape when it has one), near
 * where the vehicle is along it (its live position when fresh, else the timetable), so the 08:02
 * is told from the 08:12 on the same line, and, on the move, heading the line's way. Between two
 * legs the fix must lie near the walk from where the first is left to where the next is boarded.
 * A fix at the first stop of the first leg never counts as riding it: the user is waiting there,
 * for the next service.
 */
fun matchFix(
    fix: LocationFix,
    candidates: List<FollowCandidate>,
    tuning: FollowTuning = FollowTuning(),
    projection: MapProjection = MapProjection.Sydney,
): FollowMatch? = fitsOf(fix, candidates, tuning, projection).minByOrNull { it.offMetres }

/**
 * Every candidate [fix] fits, each at its best fit (see [matchFix]), in candidate order. Two ways
 * sharing the vehicle the user is on both fit, equally.
 */
fun fitsOf(
    fix: LocationFix,
    candidates: List<FollowCandidate>,
    tuning: FollowTuning = FollowTuning(),
    projection: MapProjection = MapProjection.Sydney,
): List<FollowMatch> = readFix(fix, candidates, tuning, projection).mapNotNull { it.match }

/**
 * What [fix] says about each of [candidates] (in candidate order): its best fit, if any (see
 * [matchFix]), and the evidence for or against it (see [FollowTuning]). A fix vaguer than
 * [FollowTuning.vagueAccuracyM] counts half.
 */
fun readFix(
    fix: LocationFix,
    candidates: List<FollowCandidate>,
    tuning: FollowTuning = FollowTuning(),
    projection: MapProjection = MapProjection.Sydney,
): List<FollowReading> {
    val t = fix.timeMs
    val p = projection.project(fix.position)
    val accuracy = (fix.accuracyM ?: 0.0).coerceIn(0.0, 500.0)
    val sharp = fix.accuracyM != null && fix.accuracyM <= tuning.clearAccuracyM
    val weight = if (accuracy > tuning.vagueAccuracyM) 0.5 else 1.0
    // The phone's heading, only on the move (a standing phone's bearing is noise).
    val bearing = fix.bearingDeg?.takeIf { (fix.speedMps ?: 0.0) >= tuning.minHeadingSpeedMps }
    return candidates.map { c ->
        var best: FollowMatch? = null
        var bestEvidence = 0.0
        fun offer(m: FollowMatch, evidence: Double) {
            if (best == null || m.offMetres < best!!.offMetres) {
                best = m
                bestEvidence = evidence
            }
        }
        // Places the user could be now (legs running, changes under way), and how many the fix is clearly not at.
        var open = 0
        var clearlyNot = 0
        val key = c.journey.departure.ref
        val way = c.journey.wayId
        val lines = c.legs.map { LegLine.of(it, projection) }
        for ((k, leg) in c.legs.withIndex()) {
            val d = leg.departure
            // Riding leg k.
            val line = lines[k]
            if (line != null && t >= d.expectedDepartureMs - tuning.slackMs && t <= d.expectedArrivalMs + tuning.slackMs) {
                open++
                val snap = line.path.snap(p)
                val waitingAtStart = k == 0 && p.distanceTo(line.path.points.first()) <= tuning.atFirstStopM
                val corridor = line.corridor(tuning) + accuracy
                val live = line.vehicleAlong(leg.vehicle, t, tuning, projection)
                val alongTolerance = (if (live != null) tuning.vehicleAlongM else tuning.alongM) + accuracy
                val along = abs(snap.distance - (live ?: line.expectedAlong(t)))
                val headingOff = bearing?.let { headingGap(it, line.compassAt(snap.distance)) }
                val wrongWay = headingOff != null &&
                    headingOff > (if (line.shaped) tuning.headingToleranceDeg else tuning.straightHeadingToleranceDeg)
                when {
                    waitingAtStart -> Unit
                    snap.offset <= corridor && along <= alongTolerance && !wrongWay -> {
                        val vehicleSpeed = leg.vehicle?.takeIf { abs(t - it.timestampMs) <= tuning.vehicleSpeedMaxAgeMs }?.speedMps
                        val speedOff = fix.speedMps != null && vehicleSpeed != null && abs(fix.speedMps - vehicleSpeed) > tuning.speedSlackMps
                        val evidence = when {
                            speedOff -> tuning.mismatchEvidence
                            live != null -> tuning.vehicleFitEvidence
                            else -> tuning.fitEvidence
                        }
                        offer(FollowMatch(c.tripId, key, k, FollowStage.RIDING, along + snap.offset, way), evidence)
                    }
                    snap.offset > 2 * corridor || along > 2 * alongTolerance || (headingOff != null && headingOff > 135.0) -> clearlyNot++
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
                    open++
                    val walk = MapPath(listOf(projection.project(from), projection.project(to)))
                    val off = walk.snap(p).offset
                    val reach = tuning.changeM + accuracy
                    if (off <= reach) {
                        // Near the change, but moving like a vehicle: still on one (the last ride, or another way).
                        val driving = (fix.speedMps ?: 0.0) > tuning.maxChangeSpeedMps
                        offer(
                            FollowMatch(c.tripId, key, k, FollowStage.CHANGING, off, way),
                            if (driving) tuning.mismatchEvidence else tuning.fitEvidence,
                        )
                    } else if (off > 2 * reach) {
                        clearlyNot++
                    }
                }
            }
        }
        val evidence = when {
            best != null -> bestEvidence * weight
            sharp && open > 0 && clearlyNot == open -> tuning.clearMissEvidence
            else -> tuning.missEvidence * weight
        }
        FollowReading(c.tripId, key, way, best, evidence)
    }
}

/**
 * The fit to follow: the journey already followed ([held], the same first service and way) when
 * the fix fits it, so a way the user chose stays chosen while they ride a vehicle another way
 * shares; else the fit of the journey with the best [scores] so far, then the closest.
 */
fun preferredFit(fits: List<FollowMatch>, held: FollowMatch?, scores: Map<ServiceRef, Double> = emptyMap()): FollowMatch? {
    if (held != null) {
        fits.firstOrNull { it.tripId == held.tripId && it.key == held.key && it.wayId == held.wayId }?.let { return it }
        // A way chosen that boards this same first vehicle further on cannot fit until the vehicle
        // gets there: while the user still fits riding that first vehicle, the choice stands.
        if (held.stage == FollowStage.RIDING && held.legIndex == 0) {
            fits.firstOrNull { it.tripId == held.tripId && it.key == held.key && it.stage == FollowStage.RIDING && it.legIndex == 0 }
                ?.let { return it.copy(wayId = held.wayId) }
        }
    }
    return fits.minWithOrNull(compareByDescending<FollowMatch> { scores[it.key] ?: 0.0 }.thenBy { it.offMetres })
}

/**
 * The other ways still open from where the user is on [current] ([match]), among the trip's
 * journeys ([underway]: those under way, and those still to come, which may board the vehicle
 * the user is on further along; with live data): those that use the vehicle the user is on, or
 * (while changing) the one they just left at the same stop, and go on differently. Where a way
 * boards the user's vehicle does not matter, only where it leaves it and what comes after. A way
 * that gets off this vehicle further on only counts while it has not reached that stop
 * ([nowMs]); a way with a cancelled ride to come does not count. Soonest arrival first.
 */
fun alternativesOf(current: Journey, match: FollowMatch, underway: List<Journey>, nowMs: Long): List<Journey> {
    val anchor = if (match.stage == FollowStage.RIDING) match.legIndex else match.legIndex - 1
    val on = current.legs.getOrNull(anchor) ?: return emptyList()
    fun rest(j: Journey, from: Int) = j.legs.drop(from).mapIndexed { n, it ->
        Triple(it.ref, if (n == 0) "" else it.originStopId, it.destinationStopId)
    }
    val mine = rest(current, anchor)
    val seen = HashSet<Any>()
    return underway.mapNotNull { j ->
        val i = j.legs.indexOfFirst { it.ref == on.ref }
        if (i < 0) return@mapNotNull null
        val ride = j.legs[i]
        val theirs = rest(j, i)
        val ok = theirs != mine &&
            j.legs.drop(i).none { it.status == app.transittracker.model.DepartureStatus.CANCELLED } &&
            when (match.stage) {
                FollowStage.CHANGING -> ride.destinationStopId == on.destinationStopId
                FollowStage.RIDING -> ride.expectedArrivalMs > nowMs
            }
        if (ok && seen.add(theirs)) j else null
    }.sortedBy { it.arrival.expectedArrivalMs }
}

/**
 * [alternative] as the journey to follow from now on: the same stage, at its leg of the vehicle
 * the user is on (riding) or the one after it (changing).
 */
fun switchedMatch(current: Journey, match: FollowMatch, alternative: Journey): FollowMatch {
    val anchor = if (match.stage == FollowStage.RIDING) match.legIndex else match.legIndex - 1
    val ref = current.legs.getOrNull(anchor)?.ref
    val i = alternative.legs.indexOfFirst { it.ref == ref }.coerceAtLeast(0)
    val leg = if (match.stage == FollowStage.RIDING) i else (i + 1).coerceAtMost(alternative.legs.lastIndex)
    return match.copy(key = alternative.departure.ref, legIndex = leg, offMetres = 0.0, wayId = alternative.wayId)
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
 * Keeps following steady between fixes by adding up the evidence of each ([FollowReading]), per
 * journey (by its first service, [scores]). A journey is followed once its score reaches
 * [FollowTuning.followAt] and beats the one followed; the one followed is dropped when its score
 * falls to zero, or when it arrives. So two fixes that fit pick a journey up, one stray fix does
 * not switch the card, two clear contradictions drop it, and a tunnel or a vague fix only nudges
 * it. Whether the user is on a journey is decided by location alone.
 */
data class FollowMemory(
    val pinned: FollowMatch? = null,
    val scores: Map<ServiceRef, Double> = emptyMap(),
) {
    /**
     * The memory after a fix: [match] is the fit to follow ([preferredFit]), [underway] every
     * journey still under way, [evidence] what the fix says about each (by default: for [match],
     * against the rest).
     */
    fun next(
        match: FollowMatch?,
        underway: Set<ServiceRef>,
        evidence: Map<ServiceRef, Double> = underway.associateWith { if (it == match?.key) FollowTuning().fitEvidence else FollowTuning().missEvidence },
        tuning: FollowTuning = FollowTuning(),
    ): FollowMemory {
        val s = underway.associateWith { k ->
            val old = scores[k] ?: 0.0
            evidence[k]?.let { (old + it).coerceIn(tuning.minScore, tuning.maxScore) } ?: old
        }
        // A followed journey that has arrived (no longer under way), or that the fixes no longer back, is finished.
        val held = pinned?.takeIf { (s[it.key] ?: 0.0) > 0.0 }?.let { h -> if (match != null && match.key == h.key) match else h }
        val heldScore = held?.let { s[it.key] } ?: Double.NEGATIVE_INFINITY
        val switch = match?.takeIf { it.key != held?.key && (s[it.key] ?: 0.0) >= tuning.followAt && s.getValue(it.key) > heldScore }
        return FollowMemory(switch ?: held, s)
    }

    /** The user switched to another way: follow [match] at once, as sure as can be. */
    fun switchTo(match: FollowMatch, tuning: FollowTuning = FollowTuning()): FollowMemory =
        FollowMemory(match, scores + (match.key to tuning.maxScore))
}
