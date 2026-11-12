package app.transittracker.ui.feature.map

import app.transittracker.model.DataStatus
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Mode
import app.transittracker.model.SavedTrip
import app.transittracker.model.StopCall
import app.transittracker.model.TrackingState
import app.transittracker.ui.feature.follow.FollowStage
import app.transittracker.ui.feature.follow.FollowState
import app.transittracker.ui.format.DefaultLineColor
import app.transittracker.ui.format.StatusPresentation
import app.transittracker.ui.format.formatClock
import app.transittracker.ui.format.mapStopName
import app.transittracker.ui.format.parseLineColor
import app.transittracker.ui.format.shortStopName
import app.transittracker.ui.format.statusPresentation
import app.transittracker.ui.nav.MapLeg
import app.transittracker.ui.settings.ClockFormat
import app.transittracker.ui.sprites.SpriteLook
import app.transittracker.ui.theme.ColorRole
import kotlinx.datetime.TimeZone

/*
 * The journey map (Follow my journey): one journey of a saved trip on one map. Each ride is drawn
 * from where it is boarded to where it is left, in its line's colour (the train, then the bus);
 * the walk between two rides is dashed; each ride's vehicle is on it (live where there is a
 * position, the timetable's ghost otherwise); the phone's position is the Locate ring. Pure.
 */

/** One ride's data: the service as tracked, where it runs, and its dead reckoning. */
data class JourneyLegInput(
    val leg: MapLeg,
    val tracking: TrackingState,
    val geometry: ServiceGeometry,
    val reckoning: Reckoning?,
)

data class JourneyMapInput(
    val trip: SavedTrip?,
    val legs: List<JourneyLegInput>,
    val follow: FollowState = FollowState(),
    val dataStatus: DataStatus? = null,
)

/** A ride in the footer list. */
data class JourneyRide(
    val routeName: String,
    val routeColor: String?,
    val routeTextColor: String?,
    val mode: Mode,
    val headsign: String,
    val fromName: String,
    val toName: String,
    /** Expected (or timetabled) times at the boarding and alighting stops. */
    val departMs: Long?,
    val arriveMs: Long?,
    val platform: String?,
    val platformChanged: Boolean,
    val status: StatusPresentation,
    val cancelled: Boolean,
    /** The ride the user is on or going to next. */
    val current: Boolean,
    /** Left already. */
    val done: Boolean,
)

/** The change before a ride: [minutes] from the last arrival to its departure. */
data class JourneyChange(val minutes: Int, val atName: String, val tight: Boolean)

data class JourneyMapUiState(
    val loading: Boolean = true,
    val title: String = "",
    val scene: MapScene = MapScene(),
    val fitKey: Any? = null,
    val rides: List<JourneyRide> = emptyList(),
    /** [changes] k is the change before ride k + 1. */
    val changes: List<JourneyChange> = emptyList(),
    val countdown: TrackCountdown? = null,
    /** "ARRIVED AT CENTRAL": replaces the countdown once the journey is done. */
    val bigWords: String? = null,
    val chip: TrackChip? = null,
    /** The phone's location says the user is on this journey. */
    val following: Boolean = false,
    val notice: String? = null,
    val noticeRole: ColorRole = ColorRole.SECONDARY,
    val currentLeg: Int = 0,
    val nowMs: Long = 0,
    val description: String = "",
)

private fun StopCall.arrMs(): Long? = expectedArrivalMs ?: scheduledArrivalMs ?: expectedDepartureMs ?: scheduledDepartureMs
private fun StopCall.depMs(): Long? = expectedDepartureMs ?: scheduledDepartureMs ?: expectedArrivalMs ?: scheduledArrivalMs

/** Board and alight call indices of a leg (the whole service when a stop is not found). */
private fun ends(calls: List<StopCall>, leg: MapLeg): Pair<Int, Int> {
    val b = callIndexOf(calls, leg.boardStopId) ?: 0
    val a = calls.indexOfLast { it.stopId == leg.alightStopId }.takeIf { it >= b } ?: calls.lastIndex
    return b to a
}

/** The change after ride [k] of [legs]: minutes from its arrival to the next departure; null after the last. */
private fun changeAt(legs: List<JourneyLegTimes>, k: Int, trip: SavedTrip?): JourneyChange? {
    val next = legs.getOrNull(k + 1) ?: return null
    val arr = legs[k].arr
    val dep = next.dep
    val minutes = if (arr != null && dep != null) ((dep - arr) / 60_000).toInt() else 0
    val allowed = trip?.legs?.getOrNull(k + 1)?.minConnectionMinutes
    val tight = next.cancelled || (allowed != null && minutes < allowed)
    return JourneyChange(minutes, next.boardName, tight)
}

/** A later ride boarded further than this from where the ride before ends gets its own BOARD flag. */
private const val SEPARATE_BOARD_M = 150.0

/** What [changeAt] needs of a ride. */
private interface JourneyLegTimes {
    val dep: Long?
    val arr: Long?
    val cancelled: Boolean
    val boardName: String
}

/**
 * What RECENTRE (and opening the map) shows: what is happening now, not the whole journey.
 * Riding ride k: the rest of it, from the stop before the next one to where you get off, and the
 * start of the ride after (the change). Waiting for ride k, or changing onto it: where the ride
 * before ends, the walk, and the start of ride k. Arrived: the last stop. You are always in it.
 * [legs] are each ride's geometry with its board and alight call indices; [drawn] says which are
 * on the map; [nextStop] is ride k's next call index. Null when nothing is placed.
 */
internal fun nowFocus(
    legs: List<Triple<ServiceGeometry, Int, Int>>,
    drawn: List<Boolean>,
    current: Int,
    stage: FollowStage,
    arrived: Boolean,
    you: MapPoint?,
    nextStop: (Int) -> Int,
): MapBox? {
    val pts = ArrayList<MapPoint>()
    fun stretch(k: Int, from: Int, to: Int) {
        if (drawn.getOrNull(k) != true) return
        val (g, b, a) = legs[k]
        val f = from.coerceIn(b, a)
        val t = to.coerceIn(f, a)
        pts += g.path.slice(g.stopDistances[f], g.stopDistances[t]).points
        // The stops' own markers too: a platform can sit a little off its line's shape.
        pts += g.stopPoints[f]
        pts += g.stopPoints[t]
    }
    // The start of a ride: its first two stretches, enough to show which way it goes.
    fun start(k: Int) = legs.getOrNull(k)?.let { (_, b, _) -> stretch(k, b, b + 2) }
    when {
        arrived -> legs.lastIndex.let { k -> legs.getOrNull(k)?.let { (_, _, a) -> stretch(k, a, a) } }
        stage == FollowStage.RIDING -> {
            val (_, _, a) = legs[current]
            stretch(current, nextStop(current) - 1, a)
            start(current + 1)
        }
        else -> {
            if (current > 0) legs[current - 1].let { (_, _, a) -> stretch(current - 1, a, a) }
            start(current)
        }
    }
    if (you != null) pts += you
    // Room for the vehicles' sprites and the flags around the edge.
    return MapBox.around(pts)?.grow(150.0)
}

/** Everything the journey map draws at [nowMs] (pure). */
fun buildJourneyMapState(
    i: JourneyMapInput,
    nowMs: Long,
    zone: TimeZone = TimeZone.of("Australia/Sydney"),
    clockFormat: ClockFormat = ClockFormat.H24,
): JourneyMapUiState {
    if (i.legs.isEmpty()) return JourneyMapUiState(loading = false, nowMs = nowMs, notice = "This journey has no rides.", description = "No map.")
    val firstRef = i.legs.first().leg.ref
    val followed = i.follow.match?.takeIf { it.key == firstRef }

    // ---- each ride's times and names
    class Leg(val input: JourneyLegInput, val calls: List<StopCall>, val b: Int, val a: Int) : JourneyLegTimes {
        override val dep: Long? get() = calls.getOrNull(b)?.depMs()
        override val arr: Long? get() = calls.getOrNull(a)?.arrMs()
        val known: Boolean get() = calls.isNotEmpty()
        override val cancelled: Boolean get() = input.tracking.detail.status == DepartureStatus.CANCELLED
        override val boardName: String get() = if (known) name(b) else ""
        fun name(k: Int) = shortStopName(calls[k].stopName)
    }
    val legs = i.legs.map { li ->
        val calls = li.tracking.detail.calls
        val (b, a) = if (calls.isEmpty()) 0 to 0 else ends(calls, li.leg)
        Leg(li, calls, b, a)
    }

    // ---- where the user is in the journey: the follower's answer, else the clock's. The
    // follower's answer is stale once the ride it names has arrived (it checks every few seconds).
    val follow = followed?.takeIf { m ->
        val l = legs.getOrNull(m.legIndex)
        val done = if (m.stage == FollowStage.RIDING) l?.arr else l?.dep
        done == null || nowMs < done + 60_000L
    }
    val (current, stage) = follow?.let { it.legIndex.coerceIn(0, legs.lastIndex) to it.stage } ?: run {
        val k = legs.indexOfFirst { l -> (l.arr ?: Long.MAX_VALUE) > nowMs }.takeIf { it >= 0 } ?: legs.lastIndex
        val dep = legs[k].dep
        k to if (dep != null && nowMs < dep) FollowStage.CHANGING else FollowStage.RIDING
    }
    val lastArr = legs.last().arr
    val arrived = follow == null && lastArr != null && nowMs >= lastArr

    // ---- scene
    val lines = ArrayList<SceneLine>()
    val stations = ArrayList<SceneStation>()
    val vehicles = ArrayList<SceneVehicle>()
    var schematicSkipped = false
    val legEnds = ArrayList<Pair<MapPoint?, MapPoint?>>() // boarding and alighting points, for the walks
    for ((k, l) in legs.withIndex()) {
        val g = l.input.geometry
        val d = l.input.tracking.detail
        if (!l.known || g.schematic || g.stopPoints.size != l.calls.size) {
            if (l.known && g.schematic) schematicSkipped = true
            legEnds += null to null
            continue
        }
        val route = d.route
        val mode = route?.mode ?: Mode.OTHER
        val cancelled = d.status == DepartureStatus.CANCELLED
        val color = if (cancelled) DefaultLineColor else parseLineColor(route?.color) ?: DefaultLineColor
        val from = g.stopDistances[l.b]
        val to = g.stopDistances[l.a]
        val vehicleId = "vehicle-$k"
        lines += SceneLine(
            "leg-$k", color, listOf(LineSegment("leg-$k", g.path.slice(from, to), offset = from)), travelledBy = vehicleId,
            badge = route?.shortName?.takeIf { it.isNotEmpty() }, badgeTextColor = parseLineColor(route?.textColor),
            done = l.arr?.let { nowMs >= it } ?: false,
        )
        legEnds += g.stopPoints[l.b] to g.stopPoints[l.a]
        val departed = l.dep?.let { nowMs >= it } ?: false
        val finished = l.arr?.let { nowMs >= it } ?: false
        val last = k == legs.lastIndex
        val first = k == 0
        // The next ride, for the CHANGE flag where this one ends.
        val next = legs.getOrNull(k + 1)
        val nextName = next?.input?.tracking?.detail?.route?.shortName.orEmpty()
        val nextDeparted = next?.dep?.let { nowMs >= it } ?: false
        val change = changeAt(legs, k, i.trip)
        val boardsApart = legEnds.getOrNull(k - 1)?.second?.let { it.distanceTo(g.stopPoints[l.b]) > SEPARATE_BOARD_M } ?: false
        for (c in l.b..l.a) {
            val call = l.calls[c]
            val board = c == l.b
            val alight = c == l.a
            val time = (if (board) l.dep else l.arr)?.let { formatClock(it, zone, clockFormat) }
            // The stops in between, and where a later ride is boarded next to where the ride
            // before ends (its CHANGE flag speaks for both), are notches in the line, named when
            // zoomed in. A later ride boarded further away (a walk to another street) gets its own
            // BOARD flag: it is where you are walking to.
            val ownBoard = board && (first || boardsApart)
            val minor = !ownBoard && !alight
            val kind = when {
                ownBoard && !departed -> StationKind.BOARD
                alight && last && !finished -> StationKind.ALIGHT
                alight && !last && !nextDeparted -> StationKind.CHANGE
                minor && (call.passed || call.skipped) -> StationKind.PASSED
                else -> StationKind.AHEAD
            }
            val flag = when (kind) {
                StationKind.BOARD -> "BOARD ${route?.shortName.orEmpty()}".trim() + (time?.let { " · $it" } ?: "")
                StationKind.ALIGHT -> "GET OFF" + (time?.let { " · $it" } ?: "")
                StationKind.CHANGE -> (if (change?.tight == true) "TIGHT CHANGE" else "CHANGE") + (change?.let { " · ${it.minutes.coerceAtLeast(0)} MIN" } ?: "")
                else -> null
            }
            val detail = when {
                kind == StationKind.CHANGE -> listOfNotNull(nextName.takeIf { it.isNotEmpty() }?.let { "THEN $it" }, next?.dep?.let { formatClock(it, zone, clockFormat) }).joinToString(" · ").ifEmpty { null }
                minor || flag != null -> null // BOARD and GET OFF carry their time in the flag
                else -> time?.let { (if (board) "DEP " else "ARR ") + it }
            }
            val ours = (kind == StationKind.CHANGE && (k == current || k + 1 == current)) || k == current
            stations += SceneStation(
                id = "leg$k-call$c",
                name = mapStopName(g.names[c]) + if (call.skipped) " · SKIPS" else "",
                point = g.stopPoints[c],
                kind = kind,
                detail = detail,
                detailRole = if (kind != StationKind.CHANGE && (delayAt(call) ?: 0) >= 60) ColorRole.CAUTION else ColorRole.SECONDARY,
                priority = when {
                    flag != null -> if (ours) 0 else 1
                    !minor -> 3
                    else -> 20
                },
                stopId = call.stopId,
                skipped = call.skipped,
                minor = minor,
                flag = flag,
                flagRole = if (kind == StationKind.CHANGE && change?.tight == true) ColorRole.CAUTION else null,
            )
        }
        // The vehicle: under way, or (live) on its way to you within the hour. A timetabled ride
        // not yet left is not drawn: its ghost would only stand on the stop and hide its flag.
        val soon = l.dep?.let { it - nowMs <= 60 * 60_000L } ?: false
        val v = l.input.tracking.vehicle
        val live = d.status != DepartureStatus.TIMETABLED && positionFresh(v, nowMs)
        if (!finished && !cancelled && soon && (departed || live)) {
            val reck = l.input.reckoning
            val motion = if (live && reck != null) VehicleMotion.Reckoned(g, reck) else VehicleMotion.Timetabled(g, l.calls)
            val look = when {
                !live -> SpriteLook.GHOST
                d.stale -> SpriteLook.FADED
                else -> SpriteLook.NORMAL
            }
            val word = vehicleWord(mode)
            val caption = when {
                k == current && stage == FollowStage.RIDING && follow != null -> "YOU · $word"
                k == current -> "YOUR $word"
                else -> (route?.shortName?.takeIf { it.isNotEmpty() } ?: word) + (l.dep?.let { " · " + formatClock(it, zone, clockFormat) } ?: "")
            }
            vehicles += SceneVehicle(
                vehicleId, mode, color, motion, look,
                cars = if (mode == Mode.TRAIN || mode == Mode.METRO) 3 else 1,
                caption = caption,
                captionRole = if (look == SpriteLook.GHOST) ColorRole.INFO else ColorRole.CAUTION,
                routeId = route?.id,
            )
        }
    }
    // Walks between rides.
    for (k in 0 until legEnds.size - 1) {
        val a = legEnds[k].second ?: continue
        val b = legEnds[k + 1].first ?: continue
        if (a.distanceTo(b) < 1.0) continue
        lines += SceneLine("walk-$k", DefaultLineColor, listOf(LineSegment("walk-$k", MapPath(listOf(a, b)))), walk = true)
    }
    val you = i.follow.fix?.let { MapProjection.Sydney.project(it.position) }
    val focus = nowFocus(legs.map { Triple(it.input.geometry, it.b, it.a) }, legEnds.map { it.first != null }, current, stage, arrived, you) { k ->
        legs[k].calls.withIndex().firstOrNull { (c, call) -> c > legs[k].b && c <= legs[k].a && !call.passed }?.index ?: legs[k].a
    }
    val scene = MapScene(lines, stations, vehicles, you = you, focus = focus, lineWidthDots = 4)

    // ---- footer: the rides and the changes between them
    val rides = legs.mapIndexed { k, l ->
        val d = l.input.tracking.detail
        val route = d.route
        val next = l.calls.drop(l.b).firstOrNull { !it.passed && !it.skipped }
        JourneyRide(
            routeName = route?.shortName ?: "",
            routeColor = route?.color,
            routeTextColor = route?.textColor,
            mode = route?.mode ?: Mode.OTHER,
            headsign = d.headsign,
            fromName = if (l.known) l.name(l.b) else "",
            toName = if (l.known) l.name(l.a) else "",
            departMs = l.dep,
            arriveMs = l.arr,
            platform = l.calls.getOrNull(l.b)?.platform,
            platformChanged = l.calls.getOrNull(l.b)?.platformChanged ?: false,
            status = statusPresentation(d.status, delayAt(next), d.stale, d.dataAgeMs),
            cancelled = d.status == DepartureStatus.CANCELLED,
            current = k == current && !arrived,
            done = l.arr?.let { nowMs >= it } ?: false,
        )
    }
    val changes = (0 until legs.size - 1).mapNotNull { k -> changeAt(legs, k, i.trip) }

    // ---- countdown to the next thing that happens
    val cur = legs[current]
    val word = vehicleWord(rides[current].mode)
    val prefix = if (rides[current].status.countdownPrefix.isNotEmpty()) rides[current].status.countdownPrefix else ""
    val countdown = when {
        arrived || !cur.known -> null
        stage == FollowStage.CHANGING || (cur.dep != null && nowMs < cur.dep!!) -> cur.dep?.takeIf { it > nowMs }?.let {
            TrackCountdown("$word LEAVES ${cur.name(cur.b).uppercase()} IN", it, prefix, "the ${word.lowercase()} leaves ${cur.name(cur.b)} in")
        }
        else -> cur.arr?.takeIf { it > nowMs }?.let {
            TrackCountdown("ARRIVES ${cur.name(cur.a).uppercase()} IN", it, prefix, "arrives at ${cur.name(cur.a)} in")
        }
    }
    val bigWords = when {
        arrived -> "ARRIVED AT " + rides.last().toName.uppercase()
        rides[current].cancelled -> "${rides[current].routeName} CANCELLED"
        else -> null
    }

    val ahead = legs.getOrNull(current)?.input?.tracking?.detail
    val chip = when {
        ahead == null -> null
        ahead.status == DepartureStatus.TIMETABLED -> if (i.dataStatus?.serverReachable == false) TrackChip.Offline else TrackChip.Timetable
        ahead.stale -> TrackChip.Stale
        else -> TrackChip.Live(ahead.dataAgeMs)
    }
    val notice = when {
        schematicSkipped -> "Part of this journey could not be placed on the map."
        changes.any { it.tight } && !arrived -> "Tight change: you may miss the connection."
        else -> null
    }

    val title = i.trip?.let { it.customName ?: "${it.originName} to ${it.destinationName}" }
        ?: (rides.first().fromName + " to " + rides.last().toName)
    val description = buildString {
        append("Map of your journey. ")
        for ((k, r) in rides.withIndex()) {
            if (k > 0) {
                val ch = changes[k - 1]
                append("Change at ${ch.atName}, ${ch.minutes} minutes. ")
            }
            append("${r.routeName} ${vehicleWord(r.mode).lowercase()} from ${r.fromName} to ${r.toName}. ")
        }
        if (follow != null) {
            append(if (stage == FollowStage.RIDING) "You are on the ${rides[current].routeName}." else "You are changing to the ${rides[current].routeName}.")
        }
    }

    return JourneyMapUiState(
        loading = false,
        title = title,
        scene = scene,
        // A new stage of the journey (on to the next ride, off to change) recentres the map.
        fitKey = i.legs.map { it.leg.ref } + i.legs.map { it.geometry.schematic } + listOf(current, stage, arrived),
        rides = rides,
        changes = changes,
        countdown = countdown,
        bigWords = bigWords,
        chip = chip,
        following = followed != null,
        notice = notice,
        noticeRole = if (notice != null && !schematicSkipped) ColorRole.CAUTION else ColorRole.SECONDARY,
        currentLeg = current,
        nowMs = nowMs,
        description = description.trim(),
    )
}
