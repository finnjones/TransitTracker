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
    /**
     * The live data is stale, so the countdown is an estimate: say "about" beside it (see
     * [CountdownText.approximate]). Never a "~": in the pixel faces it reads as a minus.
     */
    val approximate: Boolean = false,
    /** Words for a screen reader, e.g. "4 minutes late". */
    val spoken: String = words.lowercase(),
)

/**
 * Status mapping (plan 2.7). [stale] (live data too old) overrides the live statuses, but never
 * CANCELLED; TIMETABLED services are never stale. [delaySeconds] gives the minutes for LATE / EARLY
 * (rounded to the nearest minute, at least 1). [dataAgeMs] gives "3 MIN OLD" for stale data
 * (see [staleAgeWords]: under a minute it is left out).
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
        val shownAge = dataAgeMs?.takeIf { staleAgeWords(it) != null }
        val age = shownAge?.let { " · " + staleAgeWords(it) + " OLD" } ?: ""
        val spokenAge = shownAge?.let { ", " + ageSpoken(it) + " old" } ?: ""
        return StatusPresentation(
            Lamp.RING, "STALE$age", ColorRole.SECONDARY,
            approximate = true, spoken = "live data is stale$spokenAge",
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

/** The age of live data in label form: "12S" under a minute, else "3 MIN". */
fun ageWords(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "${s}S" else "${s / 60} MIN"
}

/**
 * The age beside STALE: "3 MIN", or null under a minute. Stale data is minutes old, so a seconds
 * age beside STALE ("STALE · 0S OLD") could only be the wrong clock (when the phone fetched it,
 * not how old the data is); it is left out rather than contradict the word.
 */
fun staleAgeWords(ms: Long): String? = if (ms < 60_000) null else ageWords(ms)

/** [ageWords] for a LIVE chip: "NOW" for the first few seconds (never "0S"), then "12S AGO", "3 MIN AGO". */
fun liveAgeWords(ms: Long): String = if (ms < 5_000) "NOW" else ageWords(ms) + " AGO"

/** The age of live data for a screen reader: "12 seconds", "1 minute". */
fun ageSpoken(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) (if (s == 1L) "1 second" else "$s seconds") else (s / 60).let { if (it == 1L) "1 minute" else "$it minutes" }
}

internal fun minutesSpoken(m: Int) = if (m == 1) "1 minute" else "$m minutes"

/**
 * Platform sign words (plan 2.7): "PLAT 3", "STAND A", "WHARF 3 SIDE A", or "NOW PLAT 6" in a
 * Caution block when changed. [platform] is a platform number ("3") or the stop's own words for
 * where to board ("Stand A", "Wharf 3, Side A": see [app.transittracker.model.Platforms]); the
 * last word is drawn big, the rest small before it.
 */
data class PlatformPresentation(val prefix: String, val platform: String, val changed: Boolean, val spoken: String)

fun platformPresentation(platform: String, changed: Boolean): PlatformPresentation {
    val (prefix, value) = if (isPlatformNumber(platform)) {
        "PLAT" to platform
    } else {
        val words = platform.replace(",", "").uppercase().split(' ').filter { it.isNotEmpty() }
        words.dropLast(1).joinToString(" ") to (words.lastOrNull() ?: platform)
    }
    val spoken = platformSpoken(platform)
    return if (changed) PlatformPresentation("NOW $prefix".trim(), value, true, "platform changed, now $spoken")
    else PlatformPresentation(prefix, value, false, spoken)
}

/** "PLAT 3" for a platform number; the stop's own words otherwise ("STAND B", "WHARF 3, SIDE A"). Never broken across lines. */
fun platformWords(platform: String): String =
    (if (isPlatformNumber(platform)) "PLAT $platform" else platform.uppercase()).replace(' ', '\u00A0')

/** "platform 3", "stand B", "wharf 3, side A": for TalkBack. */
fun platformSpoken(platform: String): String =
    if (isPlatformNumber(platform)) "platform $platform"
    else platform.split(' ').joinToString(" ") { w -> if (w.length > 2 && w.first().isUpperCase()) w.lowercase() else w }

private fun isPlatformNumber(platform: String) = platform.firstOrNull()?.isDigit() == true || platform.length <= 2

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
