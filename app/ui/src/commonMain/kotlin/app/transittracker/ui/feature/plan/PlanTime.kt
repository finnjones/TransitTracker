package app.transittracker.ui.feature.plan

import app.transittracker.model.PlanTimeKind
import app.transittracker.ui.format.formatClock
import app.transittracker.ui.format.formatDayDate
import app.transittracker.ui.settings.ClockFormat
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/*
 * The When part of Plan a journey (plan 5.6 step 1): LEAVE NOW, LEAVE AT or ARRIVE BY a time.
 * Times move in five-minute steps, never before the current five minutes, at most a week ahead.
 */

enum class WhenKind(val label: String) { NOW("LEAVE NOW"), LEAVE_AT("LEAVE AT"), ARRIVE_BY("ARRIVE BY") }

const val TIME_STEP_MINUTES = 5
private const val MIN = 60_000L
private const val STEP = TIME_STEP_MINUTES * MIN
private const val WEEK = 7 * 24 * 60 * MIN

fun floorToStep(ms: Long): Long = ms - ms.mod(STEP)

/** The next five-minute mark after [nowMs] ("07:38" -> "07:40"): the first LEAVE AT / ARRIVE BY time. */
fun initialPickedTime(nowMs: Long): Long = floorToStep(nowMs) + STEP

/** [pickedMs] moved by [deltaMinutes], kept between the current five minutes and a week ahead. */
fun stepTime(pickedMs: Long, deltaMinutes: Int, nowMs: Long): Long {
    val earliest = floorToStep(nowMs)
    val latest = floorToStep(nowMs + WEEK)
    return (floorToStep(pickedMs) + deltaMinutes * MIN).coerceIn(earliest, latest)
}

fun canStepBack(pickedMs: Long, deltaMinutes: Int, nowMs: Long): Boolean = floorToStep(pickedMs) - deltaMinutes * MIN >= floorToStep(nowMs)

fun canStepForward(pickedMs: Long, deltaMinutes: Int, nowMs: Long): Boolean = floorToStep(pickedMs) + deltaMinutes * MIN <= floorToStep(nowMs + WEEK)

/** The request's time and kind: null (now) for LEAVE NOW. */
fun planTime(kind: WhenKind, pickedMs: Long): Pair<Long?, PlanTimeKind> = when (kind) {
    WhenKind.NOW -> null to PlanTimeKind.DEPART_AT
    WhenKind.LEAVE_AT -> pickedMs to PlanTimeKind.DEPART_AT
    WhenKind.ARRIVE_BY -> pickedMs to PlanTimeKind.ARRIVE_BY
}

/** "TODAY", "TOMORROW", or "FRI 13 NOV". */
@OptIn(ExperimentalTime::class)
fun dayWords(ms: Long, nowMs: Long, zone: TimeZone): String {
    val day = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
    return when (today.daysUntil(day)) {
        0 -> "TODAY"
        1 -> "TOMORROW"
        else -> formatDayDate(ms, zone)
    }
}

/**
 * The options header line (Options mockup): "LEAVING NOW · 07:38", "LEAVE AT 08:15",
 * "ARRIVE BY 09:00 · TOMORROW".
 */
fun whenWords(timeMs: Long?, kind: PlanTimeKind, nowMs: Long, zone: TimeZone, format: ClockFormat): String {
    if (timeMs == null) return "LEAVING NOW · " + formatClock(nowMs, zone, format)
    val head = if (kind == PlanTimeKind.ARRIVE_BY) "ARRIVE BY " else "LEAVE AT "
    val day = dayWords(timeMs, nowMs, zone)
    return head + formatClock(timeMs, zone, format) + if (day == "TODAY") "" else " · $day"
}
