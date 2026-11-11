package app.transittracker.ui.format

import app.transittracker.ui.settings.ClockFormat
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Clock time of [epochMs] in [zone]: "07:42" (24 h, the default) or "7:42 AM" (12 h).
 * With [withSuffix] false the 12 h form drops " AM"/" PM" (for tight rows that show it elsewhere).
 */
@OptIn(ExperimentalTime::class)
fun formatClock(epochMs: Long, zone: TimeZone, format: ClockFormat = ClockFormat.H24, withSuffix: Boolean = true): String {
    val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
    val mm = t.minute.toString().padStart(2, '0')
    return when (format) {
        ClockFormat.H24 -> t.hour.toString().padStart(2, '0') + ":" + mm
        ClockFormat.H12 -> {
            val h = (t.hour % 12).let { if (it == 0) 12 else it }
            val suffix = if (!withSuffix) "" else if (t.hour < 12) " AM" else " PM"
            "$h:$mm$suffix"
        }
    }
}

/** Header date, e.g. "WED 11 NOV". */
@OptIn(ExperimentalTime::class)
fun formatDayDate(epochMs: Long, zone: TimeZone): String {
    val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
    return "${dayShort(t.dayOfWeek)} ${t.day} ${monthShort(t.month)}"
}

private fun dayShort(d: DayOfWeek) = d.name.take(3)
private fun monthShort(m: Month) = m.name.take(3)

/**
 * A countdown as shown in a card (plan 2.5).
 * [value] goes in the big Doto numerals, [unit] (if any) in Silkscreen beside it.
 */
data class CountdownText(
    val value: String,
    val unit: String?,
    /** For the screen reader: "leaves in 4 minutes", "leaves now", "leaves at 08:42". */
    val spoken: String,
    val kind: Kind,
) {
    enum class Kind { NOW, MINUTES, CLOCK }
}

/**
 * Countdown rules (plan 2.5): whole minutes ("4" MIN, rounded down); "NOW" under one minute
 * (and once the time has passed); the clock time instead when more than 60 minutes away.
 * [prefix] is "~" for stale data (see [StatusPresentation.countdownPrefix]).
 */
fun countdown(
    targetMs: Long,
    nowMs: Long,
    zone: TimeZone,
    format: ClockFormat = ClockFormat.H24,
    prefix: String = "",
    verb: String = "leaves",
): CountdownText {
    val diff = targetMs - nowMs
    val minutes = diff / 60_000
    val approx = if (prefix.isNotEmpty()) "about " else ""
    return when {
        diff < 60_000 -> CountdownText(prefix + "NOW", null, "$verb ${approx}now", CountdownText.Kind.NOW)
        minutes <= 60 -> CountdownText(
            prefix + minutes, "MIN", "$verb in $approx${minutesSpoken(minutes.toInt())}", CountdownText.Kind.MINUTES,
        )
        else -> {
            val clock = formatClock(targetMs, zone, format)
            CountdownText(prefix + clock, null, "$verb at $approx$clock", CountdownText.Kind.CLOCK)
        }
    }
}

/**
 * Minutes-and-seconds countdown for the Service and tracking screens: "04:59", "00:07";
 * "1:02:03" from an hour; "00:00" once passed.
 */
fun countdownMmSs(targetMs: Long, nowMs: Long): String {
    val total = ((targetMs - nowMs).coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val mmss = m.toString().padStart(2, '0') + ":" + s.toString().padStart(2, '0')
    return if (h > 0) "$h:$mmss" else mmss
}
