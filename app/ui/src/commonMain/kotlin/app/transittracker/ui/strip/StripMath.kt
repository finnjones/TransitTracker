package app.transittracker.ui.strip

import app.transittracker.model.Mode
import app.transittracker.model.StopCall
import app.transittracker.model.StopId

/**
 * Where a vehicle is along its stop list (plan 3.3), from times alone.
 *
 * [fromIndex] and [toIndex] are indices into the call list passed in (skipped calls are never
 * used). [progress] is 0..1 from the departure at [fromIndex] to the arrival at [toIndex]. When
 * the vehicle stands at a stop, [atStop] is true and fromIndex == toIndex.
 */
data class VehicleProgress(
    val fromIndex: Int,
    val toIndex: Int,
    val progress: Float,
    val atStop: Boolean,
    /** Before its first departure (it has not started its trip yet). */
    val notStarted: Boolean = false,
    /** After its last arrival. */
    val finished: Boolean = false,
) {
    /** Position as a fractional call index (fromIndex + progress × (toIndex − fromIndex)). */
    val position: Float get() = fromIndex + progress * (toIndex - fromIndex)
}

private fun StopCall.arrivalMs(): Long? = expectedArrivalMs ?: scheduledArrivalMs ?: expectedDepartureMs ?: scheduledDepartureMs
private fun StopCall.departureMs(): Long? = expectedDepartureMs ?: scheduledDepartureMs ?: expectedArrivalMs ?: scheduledArrivalMs

/**
 * The vehicle's position at [nowMs] (plan 3.3): between the last stop passed and the next one,
 * in proportion to time:
 *
 *     (now − expected departure from the last stop) ÷ (expected arrival at the next stop − that departure)
 *
 * Expected times are used where present, timetabled times otherwise. At a stop when now is between
 * its arrival and departure. Skipped calls and calls with no times are ignored. Null when no call
 * has a time.
 */
fun vehicleProgress(calls: List<StopCall>, nowMs: Long): VehicleProgress? {
    val timed = calls.indices.filter { !calls[it].skipped && calls[it].departureMs() != null }
    if (timed.isEmpty()) return null
    val first = timed.first()
    val last = timed.last()
    if (nowMs < calls[first].departureMs()!!) {
        val arr = calls[first].arrivalMs()!!
        return VehicleProgress(first, first, 0f, atStop = nowMs >= arr, notStarted = nowMs < arr)
    }
    for (k in timed.indices) {
        val i = timed[k]
        if (k > 0 && nowMs >= calls[i].arrivalMs()!! && nowMs < calls[i].departureMs()!!) {
            return VehicleProgress(i, i, 0f, atStop = true)
        }
        if (k + 1 < timed.size) {
            val j = timed[k + 1]
            val dep = calls[i].departureMs()!!
            val nextArr = calls[j].arrivalMs()!!
            if (nowMs >= dep && nowMs < nextArr) {
                val span = (nextArr - dep).coerceAtLeast(1)
                val p = ((nowMs - dep).toFloat() / span).coerceIn(0f, 1f)
                return VehicleProgress(i, j, p, atStop = false)
            }
        }
    }
    return VehicleProgress(last, last, 0f, atStop = false, finished = true)
}

/** Kinds of stop drawn on a journey strip. */
enum class StripStopKind {
    /** Already passed by the vehicle: filled, dim. */
    PASSED,
    /** Still ahead of the vehicle: outlined. */
    AHEAD,
    /** Where you get on: larger, blinking. */
    YOURS,
    /** Where you get off, at the right end: outlined. */
    DESTINATION,
}

data class StripStop(
    /** Index into the call list. */
    val callIndex: Int,
    val name: String,
    val kind: StripStopKind,
    /** Horizontal centre as a fraction of the strip width. */
    val x: Float,
    /** Whether its name is printed under the strip (the leftmost stop, YOU and the destination). */
    val labelled: Boolean,
)

/** Everything a journey strip draws, in fractions of its width; see [journeyStripModel]. */
data class JourneyStripModel(
    val stops: List<StripStop>,
    val vehicle: VehicleProgress?,
    /** Vehicle centre as a fraction of the strip width, or null when there is no position. */
    val vehicleX: Float?,
    /** For the screen reader (plan 3.6). */
    val sentence: String,
)

/** Layout constants (Main mockup, 334 dp wide strip): first stop centre, your stop, destination. */
object StripLayout {
    const val FIRST_X = 15f / 334f
    const val YOURS_X = 232f / 334f
    const val DESTINATION_X = 321f / 334f
    const val MAX_BEFORE = 3
}

/**
 * Lays out a journey strip (plan 3.3): up to three stops before yours on the left, your stop about
 * two-thirds along, your destination at the right end (stops between yours and the destination
 * are not drawn), and the vehicle placed by [vehicleProgress] between whichever of them it is.
 * A vehicle further back than the first stop shown waits at the left edge.
 *
 * [yourStopId] / [destinationStopId] match a call's stopId (falls back to the first / last call).
 */
fun journeyStripModel(
    calls: List<StopCall>,
    yourStopId: StopId,
    destinationStopId: StopId,
    nowMs: Long,
    mode: Mode = Mode.TRAIN,
    ghost: Boolean = false,
): JourneyStripModel {
    if (calls.isEmpty()) return JourneyStripModel(emptyList(), null, null, "")
    // A cancelled run has every call skipped: draw the run as timetabled rather than nothing.
    val live = calls.indices.filter { !calls[it].skipped }.ifEmpty { calls.indices.toList() }
    val yours = live.firstOrNull { calls[it].stopId == yourStopId } ?: live.first()
    val dest = live.lastOrNull { calls[it].stopId == destinationStopId && it > yours } ?: live.last()
    val progress = vehicleProgress(calls, nowMs)
    val position = progress?.position

    val before = live.filter { it < yours }.takeLast(StripLayout.MAX_BEFORE)
    fun kindOf(i: Int) = if (position != null && (i < position || (progress?.atStop == true && i.toFloat() == position && i != yours))) StripStopKind.PASSED else StripStopKind.AHEAD
    val stops = buildList {
        before.forEachIndexed { k, i ->
            val x = StripLayout.FIRST_X + k * (StripLayout.YOURS_X - StripLayout.FIRST_X) / before.size
            add(StripStop(i, calls[i].stopName, kindOf(i), x, labelled = k == 0))
        }
        add(StripStop(yours, calls[yours].stopName, StripStopKind.YOURS, StripLayout.YOURS_X, labelled = true))
        if (dest != yours) add(StripStop(dest, calls[dest].stopName, StripStopKind.DESTINATION, StripLayout.DESTINATION_X, labelled = true))
    }
    val vehicleX = position?.let { stripX(stops, it) }
    return JourneyStripModel(stops, progress, vehicleX, stripSentence(calls, progress, yours, mode, ghost))
}

/** Piecewise-linear map from a fractional call index to a strip fraction through the drawn stops. */
internal fun stripX(stops: List<StripStop>, position: Float): Float {
    val first = stops.first()
    if (position <= first.callIndex) {
        // Further back than the first stop shown: wait at the left edge, creeping up only on the
        // final segment into the first stop.
        val gap = position - (first.callIndex - 1)
        return if (gap <= 0f) 0f else gap * first.x
    }
    for (k in 0 until stops.size - 1) {
        val a = stops[k]
        val b = stops[k + 1]
        if (position <= b.callIndex) {
            val f = (position - a.callIndex) / (b.callIndex - a.callIndex)
            return a.x + f * (b.x - a.x)
        }
    }
    return stops.last().x
}

private fun modeNoun(mode: Mode) = when (mode) {
    Mode.TRAIN -> "train"
    Mode.METRO -> "metro"
    Mode.LIGHT_RAIL -> "tram"
    Mode.BUS -> "bus"
    Mode.COACH -> "coach"
    Mode.FERRY -> "ferry"
    Mode.OTHER -> "service"
}

private fun stopsWord(n: Int) = when (n) {
    1 -> "one stop"
    2 -> "two stops"
    3 -> "three stops"
    else -> "$n stops"
}

/**
 * "The train has left Rhodes and is one stop from Strathfield." (plan 3.6). Counts the stops
 * still to reach before yours; for a timetable estimate it says so.
 */
internal fun stripSentence(calls: List<StopCall>, p: VehicleProgress?, yours: Int, mode: Mode, ghost: Boolean): String {
    val noun = modeNoun(mode)
    val yourName = calls[yours].stopName
    fun live(range: IntRange) = range.count { it in calls.indices && !calls[it].skipped }
    val core = when {
        p == null -> "No position for the $noun."
        p.notStarted -> "The $noun has not left ${calls[p.fromIndex].stopName} yet."
        p.finished -> "The $noun has finished its trip at ${calls[p.fromIndex].stopName}."
        p.atStop && p.fromIndex == yours -> "The $noun is at $yourName."
        p.atStop && p.fromIndex > yours -> "The $noun is at ${calls[p.fromIndex].stopName}, past $yourName."
        p.atStop -> "The $noun is at ${calls[p.fromIndex].stopName}, ${stopsWord(live(p.fromIndex + 1..yours))} before $yourName."
        p.fromIndex >= yours -> "The $noun has left $yourName."
        p.toIndex == yours -> "The $noun has left ${calls[p.fromIndex].stopName} and is approaching $yourName."
        else -> "The $noun has left ${calls[p.fromIndex].stopName} and is ${stopsWord(live(p.toIndex until yours))} from $yourName."
    }
    return if (ghost) "Timetable estimate. $core" else core
}
