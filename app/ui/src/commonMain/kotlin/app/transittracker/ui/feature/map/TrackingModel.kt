package app.transittracker.ui.feature.map

import androidx.compose.ui.graphics.Color
import app.transittracker.model.DataStatus
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Mode
import app.transittracker.model.Occupancy
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopCall
import app.transittracker.model.StopId
import app.transittracker.model.TrackingState
import app.transittracker.model.VehiclePosition
import app.transittracker.ui.format.DefaultLineColor
import app.transittracker.ui.format.StatusPresentation
import app.transittracker.ui.format.formatClock
import app.transittracker.ui.format.mapStopName
import app.transittracker.ui.format.parseLineColor
import app.transittracker.ui.format.statusPresentation
import app.transittracker.ui.sprites.SpriteLook
import app.transittracker.ui.theme.ColorRole
import kotlinx.datetime.TimeZone
import app.transittracker.ui.settings.ClockFormat

/** What the tracking screen is showing (plan 5.5, 2.7, 6). */
enum class TrackPhase {
    /** A fresh reported position: the vehicle in colour, creeping between reports. */
    LIVE,
    /** Live service data is stale: the vehicle fades and the countdown gets "~". */
    STALE,
    /** Live service, but no position younger than 2 minutes: the ghost at the timetabled place. */
    POSITION_UNAVAILABLE,
    /** No live data at all for this service: the ghost, "Timetable only". */
    TIMETABLE,
    CANCELLED,
    /** The vehicle has reached your stop (or its last stop). */
    ARRIVED,
    /** The service has no stops (not in the timetable). */
    UNKNOWN,
}

/** The header chip (plan 2.7). */
sealed interface TrackChip {
    data class Live(val ageMs: Long?) : TrackChip
    data object Stale : TrackChip
    data object Timetable : TrackChip
    data object Offline : TrackChip
}

/** The footer's big countdown: "REACHES STRATHFIELD IN" over "04:12". */
data class TrackCountdown(val label: String, val targetMs: Long, val prefix: String = "", val spokenVerb: String)

data class TrackingUiState(
    val loading: Boolean = true,
    val ref: ServiceRef? = null,
    val routeName: String = "",
    val routeColor: String? = null,
    val routeTextColor: String? = null,
    val mode: Mode = Mode.TRAIN,
    val headsign: String = "",
    val phase: TrackPhase = TrackPhase.LIVE,
    val chip: TrackChip? = null,
    val scene: MapScene = MapScene(),
    val fitKey: Any? = null,
    /** A line over the map when the picture needs explaining ("Position unavailable"). */
    val notice: String? = null,
    val noticeRole: ColorRole = ColorRole.SECONDARY,
    val countdown: TrackCountdown? = null,
    /** "ARRIVED AT CENTRAL", "CANCELLED": replaces the countdown. */
    val bigWords: String? = null,
    val platform: String? = null,
    val platformChanged: Boolean = false,
    val nextStop: String? = null,
    val status: StatusPresentation = statusPresentation(DepartureStatus.TIMETABLED),
    val cars: Int? = null,
    val occupancy: Occupancy = Occupancy.UNKNOWN,
    val boardStopId: StopId? = null,
    val alightStopId: StopId? = null,
    val nowMs: Long = 0,
    /** The map for the screen reader: where the vehicle is and where it is going. */
    val description: String = "",
)

private fun StopCall.arrivalMs(): Long? = expectedArrivalMs ?: scheduledArrivalMs ?: expectedDepartureMs ?: scheduledDepartureMs
private fun StopCall.departureMs(): Long? = expectedDepartureMs ?: scheduledDepartureMs ?: expectedArrivalMs ?: scheduledArrivalMs

/** Index of the call for a stop id from the route (the platform the trip uses); null if absent. */
fun callIndexOf(calls: List<StopCall>, stopId: StopId?): Int? =
    stopId?.let { id -> calls.indexOfFirst { it.stopId == id }.takeIf { it >= 0 } }

/** The vehicle word for captions: "YOUR TRAIN", "YOUR BUS". */
fun vehicleWord(mode: Mode): String = when (mode) {
    Mode.TRAIN -> "TRAIN"
    Mode.METRO -> "METRO"
    Mode.LIGHT_RAIL -> "TRAM"
    Mode.BUS -> "BUS"
    Mode.COACH -> "COACH"
    Mode.FERRY -> "FERRY"
    Mode.OTHER -> "SERVICE"
}

/** Inputs to [buildTrackingState]; the holder keeps the [reckoning] between reports. */
data class TrackingInput(
    val tracking: TrackingState,
    val geometry: ServiceGeometry,
    val reckoning: Reckoning?,
    val dataStatus: DataStatus?,
    val boardStopId: StopId?,
    val alightStopId: StopId?,
)

/**
 * Which state the tracking screen is in at [nowMs], in priority order: unknown service,
 * cancelled, arrived at your stop (or the last), timetable only (no live data), no position
 * younger than two minutes, stale, live.
 */
fun trackPhase(t: TrackingState, alightIndex: Int?, nowMs: Long): TrackPhase {
    val d = t.detail
    if (d.calls.isEmpty()) return TrackPhase.UNKNOWN
    if (d.status == DepartureStatus.CANCELLED) return TrackPhase.CANCELLED
    val end = alightIndex ?: d.calls.indices.lastOrNull { !d.calls[it].skipped } ?: d.calls.lastIndex
    val arrive = d.calls[end].arrivalMs()
    if (arrive != null && arrive <= nowMs) return TrackPhase.ARRIVED
    if (d.status == DepartureStatus.TIMETABLED) return TrackPhase.TIMETABLE
    if (!positionFresh(t.vehicle, nowMs)) return TrackPhase.POSITION_UNAVAILABLE
    if (d.stale) return TrackPhase.STALE
    return TrackPhase.LIVE
}

/**
 * The footer countdown (plan 5.4 / 5.5): before your stop, "REACHES <board> IN" its arrival
 * (then "LEAVES <board> IN" while it stands there); after it, "ARRIVES <alight> IN"; without your
 * stops, the next stop. Null when there is nothing ahead.
 */
fun trackCountdown(calls: List<StopCall>, board: Int?, alight: Int?, next: StopCall?, nowMs: Long, prefix: String): TrackCountdown? {
    fun name(i: Int) = shortName(calls[i].stopName).uppercase()
    if (board != null) {
        val arr = calls[board].arrivalMs()
        val dep = calls[board].departureMs()
        if (arr != null && arr > nowMs) return TrackCountdown("REACHES ${name(board)} IN", arr, prefix, "reaches ${shortName(calls[board].stopName)} in")
        if (dep != null && dep > nowMs) return TrackCountdown("LEAVES ${name(board)} IN", dep, prefix, "leaves ${shortName(calls[board].stopName)} in")
    }
    if (alight != null) {
        val arr = calls[alight].arrivalMs()
        if (arr != null && arr > nowMs) return TrackCountdown("ARRIVES ${name(alight)} IN", arr, prefix, "arrives at ${shortName(calls[alight].stopName)} in")
    }
    if (next != null) {
        val arr = next.arrivalMs() ?: return null
        if (arr > nowMs) {
            val n = shortName(next.stopName)
            return TrackCountdown("REACHES ${n.uppercase()} IN", arr, prefix, "reaches $n in")
        }
    }
    return null
}

private fun shortName(s: String) = app.transittracker.ui.format.shortStopName(s)

/** Delay at the next stop, seconds (expected minus timetabled), for the status line. */
fun delayAt(call: StopCall?): Int? {
    call ?: return null
    val exp = call.expectedArrivalMs ?: call.expectedDepartureMs ?: return null
    val sch = call.scheduledArrivalMs ?: call.scheduledDepartureMs ?: return null
    return ((exp - sch) / 1000).toInt()
}

/**
 * Everything the tracking screen draws at [nowMs] (pure). [zone] / [clockFormat] format the next
 * stop's time on the map.
 */
fun buildTrackingState(
    i: TrackingInput,
    nowMs: Long,
    zone: TimeZone = TimeZone.of("Australia/Sydney"),
    clockFormat: ClockFormat = ClockFormat.H24,
): TrackingUiState {
    val t = i.tracking
    val d = t.detail
    val calls = d.calls
    val g = i.geometry
    val route = d.route
    val mode = route?.mode ?: Mode.TRAIN
    val color = parseLineColor(route?.color) ?: DefaultLineColor
    val board = callIndexOf(calls, i.boardStopId)
    val alight = callIndexOf(calls, i.alightStopId)
    val phase = trackPhase(t, alight, nowMs)
    val base = TrackingUiState(
        loading = false,
        ref = d.ref,
        routeName = route?.shortName ?: "",
        routeColor = route?.color,
        routeTextColor = route?.textColor,
        mode = mode,
        headsign = d.headsign,
        phase = phase,
        boardStopId = i.boardStopId,
        alightStopId = i.alightStopId,
        nowMs = nowMs,
    )
    if (phase == TrackPhase.UNKNOWN) {
        return base.copy(notice = "This service is not in the timetable.", noticeRole = ColorRole.INFO, description = "No map: the service is not in the timetable.")
    }
    val next = t.nextCall ?: calls.firstOrNull { !it.passed && !it.skipped }
    val nextIndex = next?.let { n -> calls.indexOfFirst { it.stopSequence == n.stopSequence } }?.takeIf { it >= 0 }
    val delay = delayAt(next)
    val status = statusPresentation(d.status, delay, d.stale, d.dataAgeMs)

    // ---- the vehicle
    val vehicleId = "vehicle"
    val vehicle: VehiclePosition? = t.vehicle
    val endIndex = alight ?: calls.lastIndex
    val motion: VehicleMotion? = when (phase) {
        // On the schematic fallback a reported position means nothing (the path is invented), so
        // the live vehicle is placed by its expected times instead.
        TrackPhase.LIVE, TrackPhase.STALE ->
            i.reckoning?.takeIf { !g.schematic }?.let { VehicleMotion.Reckoned(g, it) } ?: VehicleMotion.Timetabled(g, calls)
        TrackPhase.POSITION_UNAVAILABLE, TrackPhase.TIMETABLE -> VehicleMotion.Timetabled(g, calls)
        TrackPhase.ARRIVED -> VehicleMotion.Parked(g, g.stopDistances[endIndex])
        TrackPhase.CANCELLED -> VehicleMotion.Parked(g, timetabledDistance(calls, g, nowMs) ?: 0.0)
        TrackPhase.UNKNOWN -> null
    }
    val look = when (phase) {
        TrackPhase.LIVE, TrackPhase.ARRIVED -> SpriteLook.NORMAL
        TrackPhase.STALE -> SpriteLook.FADED
        TrackPhase.POSITION_UNAVAILABLE, TrackPhase.TIMETABLE -> SpriteLook.GHOST
        TrackPhase.CANCELLED, TrackPhase.UNKNOWN -> SpriteLook.CANCELLED
    }
    val caption = when (phase) {
        TrackPhase.LIVE, TrackPhase.STALE -> (if (board != null) "YOUR " else "") + vehicleWord(mode)
        TrackPhase.POSITION_UNAVAILABLE, TrackPhase.TIMETABLE -> "TIMETABLED"
        else -> null
    }
    val captionRole = if (look == SpriteLook.GHOST) ColorRole.INFO else ColorRole.CAUTION
    val vehicles = listOfNotNull(
        motion?.let { SceneVehicle(vehicleId, mode, color, it, look, cars = 3, caption = caption, captionRole = captionRole, cancelled = phase == TrackPhase.CANCELLED) },
    )
    val spotNow = vehicles.firstOrNull()?.spotAt(nowMs, reduceMotion = false)

    // ---- stations
    val stations = calls.mapIndexed { k, call ->
        val kind = when {
            k == board -> StationKind.BOARD
            k == alight -> StationKind.ALIGHT
            call.passed || call.skipped -> StationKind.PASSED
            else -> StationKind.AHEAD
        }
        val isNext = k == nextIndex && kind != StationKind.BOARD && kind != StationKind.ALIGHT
        val detail = if (isNext && phase != TrackPhase.CANCELLED && phase != TrackPhase.ARRIVED) call.arrivalMs()?.let { formatClock(it, zone, clockFormat) } else null
        val late = (delayAt(call) ?: 0) >= 60
        SceneStation(
            id = "call-$k",
            name = mapStopName(g.names[k]) + if (call.skipped) " · SKIPS" else "",
            point = g.stopPoints[k],
            kind = kind,
            detail = detail,
            detailRole = if (late) ColorRole.CAUTION else ColorRole.SECONDARY,
            priority = when {
                kind == StationKind.ALIGHT -> 0
                kind == StationKind.BOARD -> 1
                isNext -> 2
                kind == StationKind.AHEAD -> 5
                else -> 20
            },
            stopId = call.stopId,
            skipped = call.skipped,
        )
    }

    val line = SceneLine(
        "service", if (phase == TrackPhase.CANCELLED) DefaultLineColor else color,
        listOf(LineSegment("service", g.path)),
        travelledBy = if (phase == TrackPhase.CANCELLED) null else vehicleId,
    )
    // RECENTRE: from the last stop passed to your stop (or the next ones): stable between stops.
    val lastPassed = calls.indices.lastOrNull { calls[it].passed && !calls[it].skipped }
    val focusIdx = buildList {
        lastPassed?.let { add(it) }
        nextIndex?.let { add(it) }
        if (board != null && !calls[board].passed) add(board)
        if (alight != null) add(alight)
        if (phase == TrackPhase.ARRIVED) {
            add(endIndex)
            if (endIndex > 0) add(endIndex - 1)
        }
        if (isEmpty()) addAll(calls.indices)
    }
    val focus = MapBox.around(focusIdx.map { g.stopPoints[it] })
    val scene = MapScene(listOf(line), stations, vehicles, focus = focus, lineWidthDots = 4, schematic = g.schematic)

    // ---- footer
    val prefix = if (phase == TrackPhase.STALE || phase == TrackPhase.POSITION_UNAVAILABLE) "~" else status.countdownPrefix
    val countdown = if (phase == TrackPhase.ARRIVED || phase == TrackPhase.CANCELLED) null else trackCountdown(calls, board, alight, next, nowMs, prefix)
    val bigWords = when (phase) {
        TrackPhase.ARRIVED -> "ARRIVED AT " + shortName(calls[endIndex].stopName).uppercase()
        TrackPhase.CANCELLED -> "CANCELLED"
        else -> null
    }
    val platformCall = when {
        board != null && calls[board].departureMs()?.let { it > nowMs } == true -> calls[board]
        alight != null -> calls[alight]
        else -> next
    }
    val chip = when (phase) {
        TrackPhase.TIMETABLE -> if (i.dataStatus?.serverReachable == false) TrackChip.Offline else TrackChip.Timetable
        TrackPhase.STALE -> TrackChip.Stale
        TrackPhase.CANCELLED, TrackPhase.ARRIVED, TrackPhase.POSITION_UNAVAILABLE, TrackPhase.LIVE ->
            if (d.status == DepartureStatus.TIMETABLED) TrackChip.Timetable
            else TrackChip.Live(vehicle?.takeIf { positionFresh(it, nowMs) }?.let { (nowMs - it.timestampMs).coerceAtLeast(0) } ?: d.dataAgeMs)
        TrackPhase.UNKNOWN -> null
    }
    val notice = when (phase) {
        TrackPhase.POSITION_UNAVAILABLE -> "Position unavailable. Showing where the timetable puts it."
        TrackPhase.TIMETABLE -> if (i.dataStatus?.serverReachable == false) "No live data. Showing the timetable." else "Timetable only: no live data for this service."
        TrackPhase.STALE -> "Live data is delayed. Showing the last update."
        TrackPhase.CANCELLED -> "This service is cancelled."
        else -> null
    }
    val noticeRole = when (phase) {
        TrackPhase.CANCELLED -> ColorRole.STOP
        TrackPhase.TIMETABLE -> ColorRole.INFO
        else -> ColorRole.SECONDARY
    }

    // ---- screen reader
    val word = vehicleWord(mode).lowercase()
    val where = when {
        phase == TrackPhase.CANCELLED -> "The service is cancelled."
        phase == TrackPhase.ARRIVED -> "The $word has arrived at ${shortName(calls[endIndex].stopName)}."
        spotNow?.distance != null -> {
            val k = g.callIndexAt(spotNow.distance)
            val from = g.names[k]
            val to = g.names.getOrNull(k + 1)
            val est = if (look == SpriteLook.GHOST) "By the timetable, the $word" else "The $word"
            if (to == null || to == from) "$est is at $from." else "$est is between $from and $to."
        }
        else -> "Position unavailable."
    }
    val heading = (alight ?: board)?.let { " You get " + (if (it == board) "on at " else "off at ") + shortName(calls[it].stopName) + "." } ?: ""
    val description = "Line map, ${d.route?.shortName ?: ""} to ${d.headsign}. $where$heading" + if (g.schematic) " Schematic, not to scale." else ""

    return base.copy(
        chip = chip,
        scene = scene,
        fitKey = listOf(d.ref, g.schematic),
        notice = notice,
        noticeRole = noticeRole,
        countdown = countdown,
        bigWords = bigWords,
        platform = platformCall?.platform,
        platformChanged = platformCall?.platformChanged ?: false,
        nextStop = if (phase == TrackPhase.ARRIVED || phase == TrackPhase.CANCELLED) null else next?.let { shortName(it.stopName) },
        status = status,
        cars = if (phase == TrackPhase.CANCELLED) null else d.carCount ?: vehicle?.carCount,
        occupancy = if (phase == TrackPhase.CANCELLED) Occupancy.UNKNOWN
        else d.occupancy.takeIf { it != Occupancy.UNKNOWN } ?: (vehicle?.occupancy ?: Occupancy.UNKNOWN),
        description = description,
    )
}

/** The line colour of [state] (route colour or the default). */
fun TrackingUiState.lineColor(): Color = parseLineColor(routeColor) ?: DefaultLineColor
