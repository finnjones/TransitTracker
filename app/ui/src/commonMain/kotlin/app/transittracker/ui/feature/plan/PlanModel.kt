package app.transittracker.ui.feature.plan

import app.transittracker.model.DepartureStatus
import app.transittracker.model.Itinerary
import app.transittracker.model.ItineraryLeg
import app.transittracker.model.LegKind
import app.transittracker.model.LegPoint
import app.transittracker.model.Mode
import app.transittracker.model.ServiceDetail
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopCall
import app.transittracker.model.arriveMs
import app.transittracker.model.departMs
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.format.delayMinutes
import app.transittracker.ui.format.formatClock
import app.transittracker.ui.format.shortStopName
import app.transittracker.ui.settings.ClockFormat
import app.transittracker.ui.theme.ColorRole
import kotlinx.datetime.TimeZone

/*
 * Pure rules of the journey planner screens (plan 5.6): how live data moves a ride's times, the
 * step-by-step timeline with its walks and changes, the margin at each change, the summary and
 * leg strip of each option, sorting, the live-modes note and countdown words. No coroutines, no
 * Compose: tested directly (PlanRulesTest).
 */

private const val MIN = 60_000L

/** The service of a ride that was matched to the timetable; null when it was not. */
val ItineraryLeg.serviceRef: ServiceRef?
    get() = if (kind == LegKind.RIDE && tripId != null && serviceDate != null) ServiceRef(tripId!!, serviceDate!!) else null

// ---------------------------------------------------------------- ride times

/** One ride's times and status as shown: the planner's, or the phone's own live data. */
data class RideTimes(
    val scheduledDepartMs: Long,
    val departMs: Long,
    val scheduledArriveMs: Long,
    val arriveMs: Long,
    val status: DepartureStatus,
    /** Departure delay at the boarding stop; null without live data. */
    val delaySeconds: Int?,
    val platform: String?,
    val platformChanged: Boolean = false,
    val arrivePlatform: String?,
    val stale: Boolean = false,
    val dataAgeMs: Long? = null,
    /** True when the phone's own live data ([ServiceDetail]) gave these times. */
    val fromPhone: Boolean = false,
) {
    val cancelled: Boolean get() = status == DepartureStatus.CANCELLED
    val late: Boolean get() = status == DepartureStatus.LATE || status == DepartureStatus.EARLY
}

private fun statusForDelay(delaySeconds: Int): DepartureStatus = when {
    delaySeconds >= 60 -> DepartureStatus.LATE
    delaySeconds <= -60 -> DepartureStatus.EARLY
    else -> DepartureStatus.ON_TIME
}

/**
 * The planner's view of a ride: its live estimate when it used live data
 * ([ItineraryLeg.realtime]), else the timetable (TIMETABLE, plan 5.6 "honest about coverage").
 */
fun plannerRideTimes(leg: ItineraryLeg): RideTimes {
    val sd = leg.from.scheduledMs
    val sa = leg.to.scheduledMs
    val live = leg.realtime
    val ed = if (live) leg.from.expectedMs ?: sd else sd
    val ea = if (live) leg.to.expectedMs ?: sa else sa
    val delay = if (live) ((ed - sd) / 1000).toInt() else null
    val status = when {
        leg.cancelled -> DepartureStatus.CANCELLED
        !live -> DepartureStatus.TIMETABLED
        else -> statusForDelay(delay ?: 0)
    }
    return RideTimes(sd, ed, sa, ea, status, delay, leg.from.platform, false, leg.to.platform)
}

/** The call of [detail] at [point]: by stop id, else (platform changed) by the stop's short name. */
private fun findCall(calls: List<StopCall>, point: LegPoint, after: Int = -1): Int {
    val id = point.stopId
    val byId = if (id != null) calls.indexOfFirst { it.stopSequence > after && it.stopId == id } else -1
    if (byId >= 0) return byId
    return calls.indexOfFirst { it.stopSequence > after && shortStopName(it.stopName).equals(point.name, ignoreCase = true) }
}

/**
 * The phone's own live data ([TransitCore.service]) applied to a matched ride (plan 5.6 "live data
 * moves the margins"). Null when the ride is not matched, the stops are not in [detail], or the
 * phone has no live data for it either (then the planner's times stand).
 */
fun phoneRideTimes(leg: ItineraryLeg, detail: ServiceDetail): RideTimes? {
    val ref = leg.serviceRef ?: return null
    if (detail.ref != ref || detail.calls.isEmpty()) return null
    if (detail.status == DepartureStatus.TIMETABLED) return null
    val bi = findCall(detail.calls, leg.from)
    if (bi < 0) return null
    val board = detail.calls[bi]
    val ai = findCall(detail.calls, leg.to, after = board.stopSequence)
    if (ai < 0) return null
    val alight = detail.calls[ai]
    val sd = board.scheduledDepartureMs ?: board.scheduledArrivalMs ?: leg.from.scheduledMs
    val sa = alight.scheduledArrivalMs ?: alight.scheduledDepartureMs ?: leg.to.scheduledMs
    val ed = board.expectedDepartureMs ?: board.expectedArrivalMs ?: sd
    val ea = alight.expectedArrivalMs ?: alight.expectedDepartureMs ?: sa
    val delay = ((ed - sd) / 1000).toInt()
    val status = when {
        detail.status == DepartureStatus.CANCELLED || board.skipped || alight.skipped -> DepartureStatus.CANCELLED
        detail.status == DepartureStatus.ADDED -> DepartureStatus.ADDED
        else -> statusForDelay(delay)
    }
    return RideTimes(
        scheduledDepartMs = sd, departMs = ed, scheduledArriveMs = sa, arriveMs = ea,
        status = status, delaySeconds = delay,
        platform = board.platform ?: leg.from.platform, platformChanged = board.platformChanged,
        arrivePlatform = alight.platform ?: leg.to.platform,
        stale = detail.stale, dataAgeMs = detail.dataAgeMs, fromPhone = true,
    )
}

/** The phone's live data when it has some for this ride, else the planner's. */
fun rideTimes(leg: ItineraryLeg, details: Map<ServiceRef, ServiceDetail>): RideTimes =
    leg.serviceRef?.let { details[it] }?.let { phoneRideTimes(leg, it) } ?: plannerRideTimes(leg)

// ---------------------------------------------------------------- changes

/** How a change looks (plan 5.6): green at three minutes or more, amber under three, red when the walk no longer fits. */
enum class Margin { OK, TIGHT, MISSED }

/** One change between two rides: the walk, and the time left after it. */
data class Connection(
    /** Where the first ride is left. */
    val atName: String,
    val fromLine: String,
    val toLine: String,
    val walkSeconds: Int,
    val walkMeters: Int?,
    /** Where the next ride leaves from ("24", "Stand B"). */
    val toPlatform: String?,
    val arriveMs: Long,
    val nextDepartMs: Long,
    /** Next departure minus (arrival plus the walk). Negative: the walk no longer fits. */
    val spareMs: Long,
    /** Either ride is cancelled. */
    val cancelled: Boolean,
) {
    val margin: Margin get() = margin(spareMs, cancelled)

    /** Whole minutes to spare, rounded down; negative (rounded away from zero) when missed. */
    val spareMinutes: Int get() = spareMinutesOf(spareMs)
}

fun margin(spareMs: Long, cancelled: Boolean = false): Margin = when {
    cancelled || spareMs < 0 -> Margin.MISSED
    spareMs >= 3 * MIN -> Margin.OK
    else -> Margin.TIGHT
}

fun spareMinutesOf(spareMs: Long): Int =
    if (spareMs >= 0) (spareMs / MIN).toInt() else -(((-spareMs) + MIN - 1) / MIN).toInt()

/** "3 MIN TO SPARE", "MISSED BY 2 MIN", "CONNECTION LOST". */
fun marginWords(c: Connection): String = when {
    c.cancelled -> "CONNECTION LOST"
    c.spareMs < 0 -> "MISSED BY ${-c.spareMinutes} MIN"
    else -> "${c.spareMinutes} MIN TO SPARE"
}

fun marginLamp(m: Margin): Lamp = when (m) {
    Margin.OK -> Lamp.CIRCLE
    Margin.TIGHT -> Lamp.TRIANGLE
    Margin.MISSED -> Lamp.CROSS
}

fun marginRole(m: Margin): ColorRole = when (m) {
    Margin.OK -> ColorRole.GO
    Margin.TIGHT -> ColorRole.CAUTION
    Margin.MISSED -> ColorRole.STOP
}

/**
 * The plain sentence for a change that can no longer be made (plan 5.6: "the journey says so and
 * offers the next one").
 */
fun missedSentence(c: Connection, zone: TimeZone, format: ClockFormat): String {
    if (c.cancelled) {
        return "A service on this journey is cancelled, so the change from the ${c.fromLine} to the ${c.toLine} at " +
            "${c.atName} can no longer be made. Go back for another journey."
    }
    val walk = (c.walkSeconds + 59) / 60
    val walkWords = if (walk > 0) "With the $walk minute walk you" else "You"
    return "The ${c.fromLine} now reaches ${c.atName} at ${formatClock(c.arriveMs, zone, format)}. " +
        "$walkWords would miss the ${c.toLine} at ${formatClock(c.nextDepartMs, zone, format)}. Go back for another journey."
}

// ---------------------------------------------------------------- timeline

/** One row of the step-by-step journey (Journey mockup). */
sealed interface Step {
    /** The journey's start ("Home"), left at [atMs]. */
    data class Start(val name: String, val atMs: Long) : Step

    /** A walk from the door to the first stop ([last] false) or from the last stop to the door. */
    data class Walk(val seconds: Int, val meters: Int?, val last: Boolean) : Step

    data class Board(val rideIndex: Int, val leg: ItineraryLeg, val times: RideTimes) : Step

    data class Alight(val rideIndex: Int, val leg: ItineraryLeg, val times: RideTimes) : Step

    /** A change between rides: its walk and margin. */
    data class Change(val connection: Connection) : Step

    /** The journey's end, reached at [atMs]. */
    data class End(val name: String, val atMs: Long) : Step
}

/** One ride with its live times. */
data class TimedRide(val leg: ItineraryLeg, val times: RideTimes)

/** A journey laid out step by step, with live data applied. */
data class Timeline(
    val steps: List<Step>,
    val rides: List<TimedRide>,
    val connections: List<Connection>,
    /** When to leave the start: the first ride's departure less the walk to it. */
    val leaveMs: Long,
    /** When the end is reached: the last ride's arrival plus the walk from it. */
    val arriveMs: Long,
    /** Seconds before the first ride (walking) and after the last. */
    val firstWalkSeconds: Int,
    val lastWalkSeconds: Int,
    val walkSeconds: Int,
) {
    val minutes: Int get() = ((arriveMs - leaveMs + MIN / 2) / MIN).toInt()
    val changes: Int get() = (rides.size - 1).coerceAtLeast(0)
    val walkMinutes: Int get() = walkMinutesOf(walkSeconds)

    /** The first change that can no longer be made, if any. */
    val missed: Connection? get() = connections.firstOrNull { it.margin == Margin.MISSED }
}

/** Total walking minutes as summed per walk (each walk rounded up to a whole minute). */
private fun walkMinutesOf(seconds: Int): Int = (seconds + 59) / 60

/**
 * Lays [itinerary] out as steps (plan 5.6 step 3): every walk is a step of its own, each change
 * between rides shows its walk and margin, and the leave / arrive times follow live data
 * ([details], the phone's [ServiceDetail] for matched rides; the planner's estimates otherwise).
 * [fromName] / [toName] name the journey's ends as the user chose them.
 */
fun journeyTimeline(
    itinerary: Itinerary,
    details: Map<ServiceRef, ServiceDetail> = emptyMap(),
    fromName: String = itinerary.legs.first().from.name,
    toName: String = itinerary.legs.last().to.name,
): Timeline {
    val legs = itinerary.legs
    val rideIdx = legs.indices.filter { legs[it].kind == LegKind.RIDE }
    val totalWalk = legs.filter { it.kind == LegKind.WALK }.sumOf { walkMinutesOf(it.durationS) * 60 }
    if (rideIdx.isEmpty()) {
        val walk = legs.sumOf { it.durationS }
        val meters = legs.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum()
        return Timeline(
            steps = listOf(Step.Start(fromName, itinerary.departMs), Step.Walk(walk, meters, last = true), Step.End(toName, itinerary.arriveMs)),
            rides = emptyList(), connections = emptyList(),
            leaveMs = itinerary.departMs, arriveMs = itinerary.arriveMs,
            firstWalkSeconds = walk, lastWalkSeconds = 0, walkSeconds = totalWalk,
        )
    }
    fun walksBetween(from: Int, to: Int): List<ItineraryLeg> = (from until to).map { legs[it] }.filter { it.kind == LegKind.WALK }
    val rides = rideIdx.map { TimedRide(legs[it], rideTimes(legs[it], details)) }
    val pre = walksBetween(0, rideIdx.first())
    val post = walksBetween(rideIdx.last() + 1, legs.size)
    val preS = pre.sumOf { it.durationS }
    val postS = post.sumOf { it.durationS }
    val leave = rides.first().times.departMs - preS * 1000L
    val arrive = rides.last().times.arriveMs + postS * 1000L

    val steps = mutableListOf<Step>(Step.Start(fromName, leave))
    if (pre.isNotEmpty()) steps += Step.Walk(preS, pre.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum(), last = false)
    val connections = mutableListOf<Connection>()
    for ((k, ride) in rides.withIndex()) {
        steps += Step.Board(k, ride.leg, ride.times)
        steps += Step.Alight(k, ride.leg, ride.times)
        if (k < rides.lastIndex) {
            val next = rides[k + 1]
            val walks = walksBetween(rideIdx[k] + 1, rideIdx[k + 1])
            val walkS = walks.sumOf { it.durationS }
            val c = Connection(
                atName = ride.leg.to.name,
                fromLine = ride.leg.routeName.orEmpty(),
                toLine = next.leg.routeName.orEmpty(),
                walkSeconds = walkS,
                walkMeters = walks.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum(),
                toPlatform = next.times.platform,
                arriveMs = ride.times.arriveMs,
                nextDepartMs = next.times.departMs,
                spareMs = next.times.departMs - (ride.times.arriveMs + walkS * 1000L),
                cancelled = ride.times.cancelled || next.times.cancelled,
            )
            connections += c
            steps += Step.Change(c)
        }
    }
    if (post.isNotEmpty()) steps += Step.Walk(postS, post.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum(), last = true)
    steps += Step.End(toName, arrive)
    return Timeline(steps, rides, connections, leave, arrive, preS, postS, totalWalk)
}

// ---------------------------------------------------------------- leg strip

/** One part of an option's leg strip (plan 3.3): walking (including waiting at a change) or a ride. */
sealed interface StripPart {
    val minutes: Int

    data class Walk(override val minutes: Int) : StripPart

    data class Ride(
        override val minutes: Int,
        val mode: Mode,
        val line: String,
        val colorHex: String?,
    ) : StripPart
}

private fun roundMinutes(ms: Long): Int = ((ms + MIN / 2) / MIN).toInt()

/**
 * The strip's parts from the timeline's times (Options mockup): the walk to the first ride, each
 * ride, the time between rides (walk and wait together, "change 7 minutes"), the walk at the end.
 * Gaps of under half a minute are left out.
 */
fun stripParts(t: Timeline): List<StripPart> {
    if (t.rides.isEmpty()) return listOf(StripPart.Walk(t.minutes))
    val parts = mutableListOf<StripPart>()
    fun gap(ms: Long) {
        val m = roundMinutes(ms)
        if (m > 0) parts += StripPart.Walk(m)
    }
    gap(t.rides.first().times.departMs - t.leaveMs)
    for ((k, r) in t.rides.withIndex()) {
        if (k > 0) gap(r.times.departMs - t.rides[k - 1].times.arriveMs)
        parts += StripPart.Ride(
            roundMinutes(r.times.arriveMs - r.times.departMs).coerceAtLeast(1),
            r.leg.mode ?: Mode.OTHER, r.leg.routeName.orEmpty(), r.leg.routeColor,
        )
    }
    gap(t.arriveMs - t.rides.last().times.arriveMs)
    return parts
}

// ---------------------------------------------------------------- options

/** How the options are ordered (plan 5.6: three buttons re-sort the same results). */
enum class OptionSort(val label: String, val tag: String) {
    FASTEST("FASTEST", "FASTEST"),
    FEWER_CHANGES("FEWER CHANGES", "FEWEST CHANGES"),
    LESS_WALKING("LESS WALKING", "LEAST WALKING"),
}

/** One journey option as the list shows it. */
data class OptionSummary(
    /** Position in the planner's answer (best first). */
    val rank: Int,
    val itinerary: Itinerary,
    val timeline: Timeline,
    val parts: List<StripPart>,
    val live: LiveState?,
) {
    val leaveMs: Long get() = timeline.leaveMs
    val arriveMs: Long get() = timeline.arriveMs
    val minutes: Int get() = timeline.minutes
    val changes: Int get() = timeline.changes
    val walkMinutes: Int get() = timeline.walkMinutes
}

fun optionSummary(rank: Int, itinerary: Itinerary, details: Map<ServiceRef, ServiceDetail>): OptionSummary {
    val t = journeyTimeline(itinerary, details)
    return OptionSummary(rank, itinerary, t, stripParts(t), liveState(t))
}

/** The same options in [sort] order; ties keep the faster, then the planner's order. */
fun sortOptions(options: List<OptionSummary>, sort: OptionSort): List<OptionSummary> {
    val then = compareBy<OptionSummary> { it.minutes }.thenBy { it.arriveMs }.thenBy { it.rank }
    val primary: Comparator<OptionSummary> = when (sort) {
        OptionSort.FASTEST -> then
        OptionSort.FEWER_CHANGES -> compareBy<OptionSummary> { it.changes }.then(then)
        OptionSort.LESS_WALKING -> compareBy<OptionSummary> { it.timeline.walkSeconds }.then(then)
    }
    return options.sortedWith(primary)
}

/** A journey's live state on the options list: lamp, words, colour and the spoken sentence. */
data class LiveState(val lamp: Lamp, val words: String, val role: ColorRole, val spoken: String)

private fun minutesSpoken(m: Int) = if (m == 1) "1 minute" else "$m minutes"

/**
 * The live state of a journey (plan 5.6 step 2), or null when nothing is worth saying: a cancelled
 * ride, a change that can no longer be made, or a late (or early) ride and whether its change
 * still works ("T9 4 MIN LATE · CHANGE STILL OK"). Tightness the planner chose is not live news.
 */
fun liveState(t: Timeline): LiveState? {
    t.rides.firstOrNull { it.times.cancelled }?.let { r ->
        val line = r.leg.routeName.orEmpty()
        return LiveState(Lamp.CROSS, "$line CANCELLED", ColorRole.STOP, "the $line is cancelled")
    }
    for ((k, r) in t.rides.withIndex()) {
        if (!r.times.late) continue
        val line = r.leg.routeName.orEmpty()
        val m = delayMinutes(r.times.delaySeconds)
        val lateWord = if (r.times.status == DepartureStatus.EARLY) "EARLY" else "LATE"
        val head = "$line $m MIN $lateWord"
        val spokenHead = "the $line is ${minutesSpoken(m)} ${lateWord.lowercase()}"
        val c = t.connections.getOrNull(k)
        return when (c?.margin) {
            null -> LiveState(Lamp.TRIANGLE, head, ColorRole.CAUTION, spokenHead)
            Margin.OK -> LiveState(Lamp.TRIANGLE, "$head · CHANGE STILL OK", ColorRole.CAUTION, "$spokenHead, the change still works")
            Margin.TIGHT -> LiveState(
                Lamp.TRIANGLE, "$head · ${c.spareMinutes} MIN TO CHANGE", ColorRole.CAUTION,
                "$spokenHead, ${minutesSpoken(c.spareMinutes)} to spare at ${c.atName}",
            )
            Margin.MISSED -> LiveState(
                Lamp.CROSS, "$head · MISSES THE ${c.toLine}", ColorRole.STOP,
                "$spokenHead and misses the ${c.toLine} at ${c.atName}",
            )
        }
    }
    t.missed?.let { c ->
        return LiveState(Lamp.CROSS, "MISSES THE ${c.toLine}", ColorRole.STOP, "misses the ${c.toLine} at ${c.atName}")
    }
    return null
}

private fun liveName(mode: Mode) = when (mode) {
    Mode.TRAIN -> "TRAINS"
    Mode.METRO -> "METRO"
    Mode.LIGHT_RAIL -> "LIGHT RAIL"
    Mode.BUS -> "BUSES"
    Mode.COACH -> "COACHES"
    Mode.FERRY -> "FERRIES"
    Mode.OTHER -> "OTHER SERVICES"
}

private fun timesName(mode: Mode) = when (mode) {
    Mode.TRAIN -> "TRAIN"
    Mode.METRO -> "METRO"
    Mode.LIGHT_RAIL -> "LIGHT RAIL"
    Mode.BUS -> "BUS"
    Mode.COACH -> "COACH"
    Mode.FERRY -> "FERRY"
    Mode.OTHER -> "OTHER"
}

private fun andList(words: List<String>): String = when (words.size) {
    0 -> ""
    1 -> words[0]
    else -> words.dropLast(1).joinToString(", ") + " AND " + words.last()
}

/**
 * Which modes of these results are live (Options mockup: "TRAINS ARE LIVE. BUS TIMES ARE
 * TIMETABLED."), from [PlanResponse.liveModes] and the modes [used]. Null when nothing is ridden.
 */
fun liveModesNote(liveModes: Collection<Mode>, used: Collection<Mode>): String? {
    val modes = Mode.entries.filter { it in used }
    if (modes.isEmpty()) return null
    val live = modes.filter { it in liveModes }
    val timetabled = modes.filter { it !in liveModes }
    val parts = buildList {
        if (live.isNotEmpty()) {
            val singular = live.size == 1 && live[0] in setOf(Mode.METRO, Mode.LIGHT_RAIL)
            add(andList(live.map(::liveName)) + if (singular) " IS LIVE." else " ARE LIVE.")
        }
        if (timetabled.isNotEmpty()) add(andList(timetabled.map(::timesName)) + " TIMES ARE TIMETABLED.")
    }
    return parts.joinToString(" ")
}

// ---------------------------------------------------------------- words

/** "PLAT 3" for a platform number, the planner's own words otherwise ("STAND B", "WHARF 3"). */
fun platformWords(platform: String): String =
    (if (platform.firstOrNull()?.isDigit() == true) "PLAT $platform" else platform.uppercase()).replace(' ', ' ')

/** "2 CHANGES · 12 MIN WALK" (no walk: "NO WALKING"). */
fun summaryWords(changes: Int, walkMinutes: Int): String {
    val c = when (changes) {
        0 -> "NO CHANGES"
        1 -> "1 CHANGE"
        else -> "$changes CHANGES"
    }
    val w = if (walkMinutes <= 0) "NO WALKING" else "$walkMinutes MIN WALK"
    return "$c · $w"
}

/** "Walk 3 minutes" style: "2 changes, 12 minutes walking". */
fun summarySpoken(changes: Int, walkMinutes: Int): String {
    val c = when (changes) {
        0 -> "no changes"
        1 -> "1 change"
        else -> "$changes changes"
    }
    return "$c, ${minutesSpoken(walkMinutes)} walking"
}

/**
 * The leave countdown in minutes and seconds (Journey mockup "1:10"): "m:ss" under an hour,
 * "h:mm:ss" from an hour, "0:00" once passed.
 */
fun leaveClock(leaveMs: Long, nowMs: Long): String {
    val total = ((leaveMs - nowMs).coerceAtLeast(0) + 999) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val ss = s.toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
}

/** "1 minute 10 seconds" for the screen reader. */
fun leaveClockSpoken(leaveMs: Long, nowMs: Long): String {
    val total = ((leaveMs - nowMs).coerceAtLeast(0) + 999) / 1000
    if (total == 0L) return "now"
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return buildList {
        if (h > 0) add(if (h == 1L) "1 hour" else "$h hours")
        if (m > 0) add(minutesSpoken(m.toInt()))
        if (s > 0 && h == 0L) add(if (s == 1L) "1 second" else "$s seconds")
    }.joinToString(" ")
}
