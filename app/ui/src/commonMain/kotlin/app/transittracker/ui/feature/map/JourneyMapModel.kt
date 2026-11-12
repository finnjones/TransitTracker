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
    class Leg(val input: JourneyLegInput, val calls: List<StopCall>, val b: Int, val a: Int) {
        val dep: Long? get() = calls.getOrNull(b)?.depMs()
        val arr: Long? get() = calls.getOrNull(a)?.arrMs()
        val known: Boolean get() = calls.isNotEmpty()
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
        lines += SceneLine("leg-$k", color, listOf(LineSegment("leg-$k", g.path.slice(from, to), offset = from)), travelledBy = vehicleId)
        legEnds += g.stopPoints[l.b] to g.stopPoints[l.a]
        val departed = l.dep?.let { nowMs >= it } ?: false
        val finished = l.arr?.let { nowMs >= it } ?: false
        for (c in l.b..l.a) {
            val call = l.calls[c]
            val kind = when {
                c == l.b -> if (departed) StationKind.AHEAD else StationKind.BOARD
                c == l.a -> if (finished) StationKind.AHEAD else StationKind.ALIGHT
                call.passed || call.skipped -> StationKind.PASSED
                else -> StationKind.AHEAD
            }
            val detail = when (c) {
                l.b -> l.dep?.let { "DEP " + formatClock(it, zone, clockFormat) }
                l.a -> l.arr?.let { "ARR " + formatClock(it, zone, clockFormat) }
                else -> null
            }
            stations += SceneStation(
                id = "leg$k-call$c",
                name = g.names[c] + if (call.skipped) " · SKIPS" else "",
                point = g.stopPoints[c],
                kind = kind,
                detail = detail,
                detailRole = if ((delayAt(call) ?: 0) >= 60) ColorRole.CAUTION else ColorRole.SECONDARY,
                priority = when {
                    c == l.b || c == l.a -> if (k == current) 0 else 1
                    kind == StationKind.AHEAD -> 6
                    else -> 20
                },
                stopId = call.stopId,
                skipped = call.skipped,
            )
        }
        // The vehicle: while the ride is still to come (within the hour) or under way.
        val soon = l.dep?.let { it - nowMs <= 60 * 60_000L } ?: false
        if (!finished && !cancelled && soon) {
            val v = l.input.tracking.vehicle
            val live = d.status != DepartureStatus.TIMETABLED && positionFresh(v, nowMs)
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
                else -> word + (l.dep?.let { " " + formatClock(it, zone, clockFormat) } ?: "")
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
    val scene = MapScene(lines, stations, vehicles, you = you, focus = null, lineWidthDots = 3)

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
    val changes = (0 until legs.size - 1).map { k ->
        val arr = legs[k].arr
        val dep = legs[k + 1].dep
        val minutes = if (arr != null && dep != null) ((dep - arr) / 60_000).toInt() else 0
        val allowed = i.trip?.legs?.getOrNull(k + 1)?.minConnectionMinutes
        val tight = rides[k + 1].cancelled || (allowed != null && minutes < allowed)
        JourneyChange(minutes, rides[k + 1].fromName, tight)
    }

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
        fitKey = i.legs.map { it.leg.ref } + i.legs.map { it.geometry.schematic },
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
