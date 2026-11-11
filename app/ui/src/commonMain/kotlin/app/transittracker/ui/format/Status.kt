package app.transittracker.ui.format

import app.transittracker.model.Departure
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Occupancy
import app.transittracker.ui.theme.ColorRole
import kotlin.math.abs

/** Status lamp shapes (plan 2.6), drawn on a 7 x 7 grid. */
enum class Lamp { CIRCLE, TRIANGLE, CROSS, PLUS, RING }

/**
 * How one service's status is shown (plan 2.7): lamp, words and colour, plus the extra
 * treatments that go with them.
 */
data class StatusPresentation(
    val lamp: Lamp,
    /** Upper-case status words, e.g. "4 MIN LATE". */
    val words: String,
    val role: ColorRole,
    /** Late or early: strike the timetabled time and show the expected time beside it. */
    val strikeScheduledTime: Boolean = false,
    /** Cancelled: strike the whole row. */
    val strikeRow: Boolean = false,
    /** "~" when the live data is stale, prefixed to the countdown. */
    val countdownPrefix: String = "",
    /** Words for a screen reader, e.g. "4 minutes late". */
    val spoken: String = words.lowercase(),
)

/**
 * Status mapping (plan 2.7). [stale] (live data too old) overrides the live statuses, but never
 * CANCELLED; TIMETABLED services are never stale. [delaySeconds] gives the minutes for LATE / EARLY
 * (rounded to the nearest minute, at least 1). [dataAgeMs] gives "3 MIN OLD" for stale data.
 */
fun statusPresentation(
    status: DepartureStatus,
    delaySeconds: Int? = null,
    stale: Boolean = false,
    dataAgeMs: Long? = null,
): StatusPresentation {
    if (status == DepartureStatus.CANCELLED) {
        return StatusPresentation(Lamp.CROSS, "CANCELLED", ColorRole.STOP, strikeRow = true, spoken = "cancelled")
    }
    if (stale && status != DepartureStatus.TIMETABLED) {
        val age = dataAgeMs?.let { " · " + ageWords(it) + " OLD" } ?: ""
        val spokenAge = dataAgeMs?.let { ", " + ageSpoken(it) + " old" } ?: ""
        return StatusPresentation(
            Lamp.RING, "STALE$age", ColorRole.SECONDARY,
            countdownPrefix = "~", spoken = "live data is stale$spokenAge",
        )
    }
    return when (status) {
        DepartureStatus.ON_TIME -> StatusPresentation(Lamp.CIRCLE, "ON TIME", ColorRole.GO, spoken = "on time")
        DepartureStatus.LATE -> {
            val m = delayMinutes(delaySeconds)
            StatusPresentation(
                Lamp.TRIANGLE, "$m MIN LATE", ColorRole.CAUTION, strikeScheduledTime = true,
                spoken = "${minutesSpoken(m)} late",
            )
        }
        DepartureStatus.EARLY -> {
            val m = delayMinutes(delaySeconds)
            StatusPresentation(
                Lamp.TRIANGLE, "$m MIN EARLY", ColorRole.CAUTION, strikeScheduledTime = true,
                spoken = "${minutesSpoken(m)} early",
            )
        }
        DepartureStatus.ADDED -> StatusPresentation(Lamp.PLUS, "EXTRA SERVICE", ColorRole.GO, spoken = "extra service")
        DepartureStatus.TIMETABLED -> StatusPresentation(
            Lamp.RING, "TIMETABLE", ColorRole.INFO, spoken = "timetable only, no live data",
        )
        DepartureStatus.CANCELLED -> error("handled above")
    }
}

fun statusPresentation(d: Departure): StatusPresentation =
    statusPresentation(d.status, d.delaySeconds, d.stale, d.dataAgeMs)

/** Whole minutes of a delay, rounded to nearest, at least 1. */
fun delayMinutes(delaySeconds: Int?): Int = maxOf(1, (abs(delaySeconds ?: 0) + 30) / 60)

private fun ageWords(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "${s}S" else "${s / 60} MIN"
}

private fun ageSpoken(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) (if (s == 1L) "1 second" else "$s seconds") else minutesSpoken((s / 60).toInt())
}

internal fun minutesSpoken(m: Int) = if (m == 1) "1 minute" else "$m minutes"

/** Platform sign words (plan 2.7): "PLAT 3", or "NOW PLAT 6" in a Caution block when changed. */
data class PlatformPresentation(val prefix: String, val platform: String, val changed: Boolean, val spoken: String)

fun platformPresentation(platform: String, changed: Boolean): PlatformPresentation =
    if (changed) PlatformPresentation("NOW PLAT", platform, true, "platform changed, now platform $platform")
    else PlatformPresentation("PLAT", platform, false, "platform $platform")

/** Seat pips (plan 2.7): [filled] of four pips, and the words beside them. */
data class OccupancyPresentation(val filled: Int, val words: String) {
    val spoken: String get() = words.lowercase()
}

/** Null for [Occupancy.UNKNOWN]: nothing is shown. */
fun occupancyPresentation(occupancy: Occupancy): OccupancyPresentation? = when (occupancy) {
    Occupancy.UNKNOWN -> null
    Occupancy.EMPTY, Occupancy.MANY_SEATS_AVAILABLE -> OccupancyPresentation(1, "MANY SEATS")
    Occupancy.FEW_SEATS_AVAILABLE -> OccupancyPresentation(3, "FEW SEATS")
    Occupancy.STANDING_ROOM_ONLY, Occupancy.CRUSHED_STANDING_ROOM_ONLY -> OccupancyPresentation(4, "STANDING ONLY")
    Occupancy.FULL, Occupancy.NOT_ACCEPTING_PASSENGERS -> OccupancyPresentation(4, "FULL")
}

/** "8 CARS" / "1 CAR". */
fun carCountWords(cars: Int): String = if (cars == 1) "1 CAR" else "$cars CARS"
