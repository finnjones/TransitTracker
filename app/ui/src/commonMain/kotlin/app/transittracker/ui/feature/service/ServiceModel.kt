package app.transittracker.ui.feature.service

import app.transittracker.model.DepartureStatus
import app.transittracker.model.Mode
import app.transittracker.model.Occupancy
import app.transittracker.model.ServiceDetail
import app.transittracker.model.StopCall
import app.transittracker.model.StopId

/** Everything the service screen draws (plan 5.4). Built by [buildServiceState]; times are epoch ms. */
data class ServiceUiState(
    /** True until the service has been read once (the screen shows nothing rather than a spinner). */
    val loading: Boolean = true,
    /** False when the core knows no such service (an old notification, a service that left the feed). */
    val found: Boolean = true,
    val nowMs: Long = 0,
    val routeName: String = "",
    val routeLongName: String? = null,
    val routeColor: String? = null,
    val routeTextColor: String? = null,
    val mode: Mode = Mode.TRAIN,
    val headsign: String = "",
    val status: DepartureStatus = DepartureStatus.TIMETABLED,
    val stale: Boolean = false,
    val dataAgeMs: Long? = null,
    /** Delay at the next stop in seconds; null without live data. */
    val delaySeconds: Int? = null,
    val chip: ServiceChip? = null,
    val calls: List<StopCall> = emptyList(),
    val rows: List<StopRow> = emptyList(),
    /** Stop rows and the vehicle marker in list order. */
    val items: List<ListItem> = emptyList(),
    val marker: VehicleMarker? = null,
    val boardIndex: Int? = null,
    val alightIndex: Int? = null,
    /** The stop ids the strip is drawn for: the user's, or the next stop / last stop when unknown. */
    val stripBoardId: StopId? = null,
    val stripAlightId: StopId? = null,
    val footer: Footer = Footer(FooterKind.DONE),
    val occupancy: Occupancy = Occupancy.UNKNOWN,
    val carCount: Int? = null,
) {
    val cancelled: Boolean get() = status == DepartureStatus.CANCELLED
}

enum class ChipKind { LIVE, STALE, TIMETABLE, OFFLINE }

/** The LIVE chip (plan 2.7): live with the data's age, stale with its age, or timetable only. */
data class ServiceChip(val kind: ChipKind, val ageMs: Long?)

enum class StopRole { PLAIN, BOARD, ALIGHT }

/** One stop of the list. */
data class StopRow(
    val index: Int,
    val call: StopCall,
    val role: StopRole,
    val passed: Boolean,
    val skipped: Boolean,
    val times: StopTimes?,
)

/**
 * The time shown for a stop: [scheduledMs] always, [expectedMs] when live data has one. [differs]
 * (a minute or more apart) strikes the timetabled time and writes the expected one beside it.
 */
data class StopTimes(val scheduledMs: Long, val expectedMs: Long?, val differs: Boolean) {
    val shownMs: Long get() = expectedMs ?: scheduledMs
}

sealed interface ListItem {
    data class Stop(val row: StopRow) : ListItem
    data class Marker(val marker: VehicleMarker) : ListItem
}

enum class MarkerKind { LIVE, STALE, TIMETABLE }

/**
 * Where the vehicle is drawn in the list (plan 5.4): after the row [afterIndex], which is the last
 * stop passed, or the stop it is standing at ([atStop]).
 */
data class VehicleMarker(val afterIndex: Int, val kind: MarkerKind, val atStop: Boolean)

enum class FooterKind {
    /** LEAVES <stop> IN: the vehicle has not left your stop. */
    LEAVES,

    /** ARRIVES <stop> IN: it has left your stop; counting to where you get off. */
    ARRIVES,

    /** NEXT STOP <stop> IN: no stop of yours is known (or it has been passed and there is no destination). */
    NEXT,

    /** Everything has been passed. */
    DONE,

    /** The service does not run. */
    CANCELLED,
}

/** The footer countdown (minutes and seconds): what it counts to, and the platform sign beside it. */
data class Footer(
    val kind: FooterKind,
    val stopName: String? = null,
    val targetMs: Long? = null,
    val platform: String? = null,
    val platformChanged: Boolean = false,
    /** Stale data: the countdown is prefixed with "~". */
    val approximate: Boolean = false,
)

// ---------------------------------------------------------------- pure rules

private fun StopCall.expectedArrival(): Long? = expectedArrivalMs ?: scheduledArrivalMs ?: expectedDepartureMs ?: scheduledDepartureMs
private fun StopCall.expectedDeparture(): Long? = expectedDepartureMs ?: scheduledDepartureMs ?: expectedArrivalMs ?: scheduledArrivalMs

/**
 * The time of a stop: its departure, or its arrival at the stop you get off at and at the end of
 * the line. Null when the call has no time at all.
 */
fun stopTimes(call: StopCall, useArrival: Boolean): StopTimes? {
    val scheduled = (if (useArrival) call.scheduledArrivalMs ?: call.scheduledDepartureMs else call.scheduledDepartureMs ?: call.scheduledArrivalMs)
    val expected = (if (useArrival) call.expectedArrivalMs ?: call.expectedDepartureMs else call.expectedDepartureMs ?: call.expectedArrivalMs)
    if (scheduled == null && expected == null) return null
    if (scheduled == null) return StopTimes(expected!!, null, false)
    val differs = expected != null && kotlin.math.abs(expected - scheduled) >= 60_000
    return StopTimes(scheduled, expected, differs)
}

/**
 * The delay shown in the status line: that of the next stop still to come, from expected against
 * timetabled time; null when no such stop has both.
 */
fun serviceDelaySeconds(calls: List<StopCall>): Int? {
    val reference = calls.firstOrNull { !it.passed && !it.skipped && it.expectedDepartureMs != null && it.scheduledDepartureMs != null }
        ?: calls.lastOrNull { !it.skipped && it.expectedArrivalMs != null && it.scheduledArrivalMs != null }
        ?: return null
    val exp = reference.expectedDepartureMs ?: reference.expectedArrivalMs ?: return null
    val sched = reference.scheduledDepartureMs ?: reference.scheduledArrivalMs ?: return null
    return ((exp - sched) / 1000).toInt()
}

/** The LIVE / STALE / TIMETABLE chip for a service's data. */
fun serviceChip(status: DepartureStatus, stale: Boolean, dataAgeMs: Long?): ServiceChip? = when {
    status == DepartureStatus.TIMETABLED -> ServiceChip(ChipKind.TIMETABLE, null)
    stale -> ServiceChip(ChipKind.STALE, dataAgeMs)
    status == DepartureStatus.CANCELLED && dataAgeMs == null -> null
    else -> ServiceChip(ChipKind.LIVE, dataAgeMs)
}

/** Indices of the stop you get on and off in a call list; either may be unknown. */
data class ResolvedStops(val boardIndex: Int?, val alightIndex: Int?)

/**
 * Finds the user's stops in [calls]. [boardId] / [alightId] are platform ids from the route; when
 * a platform changes after the page opened the id no longer matches, so the stop sequences
 * remembered from the first match ([boardSeq], [alightSeq]) are tried next. The destination must
 * come after the boarding stop.
 */
fun resolveStops(
    calls: List<StopCall>,
    boardId: StopId?,
    alightId: StopId?,
    boardSeq: Int? = null,
    alightSeq: Int? = null,
): ResolvedStops {
    fun find(id: StopId?, seq: Int?, from: Int, last: Boolean): Int? {
        val range = calls.indices.filter { it >= from }
        val byId = if (id == null) null else if (last) range.lastOrNull { calls[it].stopId == id } else range.firstOrNull { calls[it].stopId == id }
        if (byId != null) return byId
        return if (seq == null) null else range.firstOrNull { calls[it].stopSequence == seq }
    }
    val board = find(boardId, boardSeq, 0, last = false)
    val alight = find(alightId, alightSeq, (board ?: -1) + 1, last = true)
    return ResolvedStops(board, alight)
}

/**
 * Where the vehicle marker sits (plan 5.4): between the last stop passed and the next one, or
 * with the stop it is standing at. Null before the first stop is passed, after the last, and for
 * a cancelled service. The kind follows the data: live, stale (stops blinking) or timetable only.
 */
fun vehicleMarker(calls: List<StopCall>, status: DepartureStatus, stale: Boolean, nowMs: Long): VehicleMarker? {
    if (status == DepartureStatus.CANCELLED) return null
    val lastPassed = calls.indexOfLast { it.passed }
    if (lastPassed < 0 || lastPassed >= calls.lastIndex) return null
    val next = calls[lastPassed + 1]
    val arrival = next.expectedArrival()
    val atStop = !next.skipped && arrival != null && arrival <= nowMs
    val kind = when {
        status == DepartureStatus.TIMETABLED -> MarkerKind.TIMETABLE
        stale -> MarkerKind.STALE
        else -> MarkerKind.LIVE
    }
    return VehicleMarker(if (atStop) lastPassed + 1 else lastPassed, kind, atStop)
}

/**
 * Which countdown the footer shows (plan 5.4): LEAVES <your stop> until the vehicle has left it,
 * then ARRIVES <destination>; without a stop of yours, NEXT STOP. The platform sign is that of the
 * stop counted to.
 */
fun footerFor(
    calls: List<StopCall>,
    boardIndex: Int?,
    alightIndex: Int?,
    status: DepartureStatus,
    stale: Boolean,
    shortName: (String) -> String = { it },
): Footer {
    if (status == DepartureStatus.CANCELLED) return Footer(FooterKind.CANCELLED)
    fun at(kind: FooterKind, i: Int, target: Long?) = Footer(
        kind, shortName(calls[i].stopName), target, calls[i].platform, calls[i].platformChanged, approximate = stale,
    )
    if (boardIndex != null && !calls[boardIndex].passed) {
        return at(FooterKind.LEAVES, boardIndex, calls[boardIndex].expectedDeparture())
    }
    if (alightIndex != null) {
        return if (calls[alightIndex].passed) Footer(FooterKind.DONE) else at(FooterKind.ARRIVES, alightIndex, calls[alightIndex].expectedArrival())
    }
    val next = calls.indices.firstOrNull { !calls[it].passed && !calls[it].skipped } ?: return Footer(FooterKind.DONE)
    return at(FooterKind.NEXT, next, calls[next].expectedArrival())
}

/** The stop rows with the marker placed after its row. */
fun listItems(rows: List<StopRow>, marker: VehicleMarker?): List<ListItem> = buildList {
    for (row in rows) {
        add(ListItem.Stop(row))
        if (marker != null && marker.afterIndex == row.index) add(ListItem.Marker(marker))
    }
}

/** The mode's noun for "TRAIN IS HERE". */
fun modeNoun(mode: Mode): String = when (mode) {
    Mode.TRAIN -> "TRAIN"
    Mode.METRO -> "METRO"
    Mode.LIGHT_RAIL -> "TRAM"
    Mode.BUS -> "BUS"
    Mode.COACH -> "COACH"
    Mode.FERRY -> "FERRY"
    Mode.OTHER -> "SERVICE"
}

/**
 * Pure: the service screen for [detail] at [nowMs]. [boardSeq] / [alightSeq] are the stop sequences
 * remembered from an earlier match (see [resolveStops]); [shortName] shortens station names.
 */
fun buildServiceState(
    detail: ServiceDetail,
    boardId: StopId?,
    alightId: StopId?,
    nowMs: Long,
    boardSeq: Int? = null,
    alightSeq: Int? = null,
    shortName: (String) -> String = { it },
): ServiceUiState {
    val calls = detail.calls
    if (calls.isEmpty()) return ServiceUiState(loading = false, found = false, nowMs = nowMs)
    val (board, alight) = resolveStops(calls, boardId, alightId, boardSeq, alightSeq)
    val cancelled = detail.status == DepartureStatus.CANCELLED
    val rows = calls.mapIndexed { i, call ->
        val role = when (i) {
            board -> StopRole.BOARD
            alight -> StopRole.ALIGHT
            else -> StopRole.PLAIN
        }
        StopRow(
            index = i,
            call = call,
            role = role,
            passed = call.passed && !cancelled,
            // A cancelled service skips nothing by itself: the whole run is struck instead.
            skipped = call.skipped && !cancelled,
            times = stopTimes(call, useArrival = role == StopRole.ALIGHT || i == calls.lastIndex),
        )
    }
    val marker = vehicleMarker(calls, detail.status, detail.stale, nowMs)
    val footer = footerFor(calls, board, alight, detail.status, detail.stale, shortName)
    val nextIndex = calls.indices.firstOrNull { !calls[it].passed && !calls[it].skipped } ?: calls.lastIndex
    return ServiceUiState(
        loading = false,
        found = true,
        nowMs = nowMs,
        routeName = detail.route?.shortName.orEmpty(),
        routeLongName = detail.route?.longName?.takeIf { it.isNotBlank() },
        routeColor = detail.route?.color,
        routeTextColor = detail.route?.textColor,
        mode = detail.route?.mode ?: Mode.OTHER,
        headsign = detail.headsign,
        status = detail.status,
        stale = detail.stale,
        dataAgeMs = detail.dataAgeMs,
        delaySeconds = serviceDelaySeconds(calls),
        chip = serviceChip(detail.status, detail.stale, detail.dataAgeMs),
        calls = calls,
        rows = rows,
        items = listItems(rows, marker),
        marker = marker,
        boardIndex = board,
        alightIndex = alight,
        stripBoardId = board?.let { calls[it].stopId } ?: calls[nextIndex].stopId,
        stripAlightId = alight?.let { calls[it].stopId } ?: calls.last().stopId,
        footer = footer,
        occupancy = detail.occupancy,
        carCount = detail.carCount,
    )
}
