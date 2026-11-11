package app.transittracker.ui.feature.notify

import app.transittracker.core.TransitCoreException
import app.transittracker.model.SavedTrip
import app.transittracker.model.StopId
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchKind
import app.transittracker.ui.format.formatDayDate
import kotlinx.datetime.TimeZone

// ---------------------------------------------------------------- limits (backend plan 7.2)

/** The server allows this many watches per device. */
const val WATCH_LIMIT = 10

/** The departure window moves in quarter hours. */
const val WINDOW_STEP_SECS = 15 * 60

/** The latest the window may end (23:45); it may start at most one step before that. */
const val WINDOW_END_MAX_SECS = 23 * 3600 + 45 * 60

const val LEAD_MIN_MINUTES = 15
const val LEAD_MAX_MINUTES = 180
const val LEAD_STEP_MINUTES = 15

/** What "late by" can be: single minutes up to ten, then the round numbers. */
val DELAY_CHOICES: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 30)

/** A pause with no end date (the switch has only ON and OFF): ten years. */
const val PAUSE_FOREVER_MS = 10L * 365 * 24 * 3600 * 1000

// ---------------------------------------------------------------- stepping

private fun snap(value: Int, step: Int, direction: Int): Int =
    if (direction > 0) (value / step + 1) * step else ((value + step - 1) / step - 1) * step

/**
 * The window start after one step ([direction] +1 later, -1 earlier), or null at the limit. The
 * start stays on the quarter hour (a value off the grid snaps onto it), from 00:00, and at least
 * one step before [endSecs].
 */
fun steppedStart(startSecs: Int, endSecs: Int, direction: Int): Int? =
    snap(startSecs, WINDOW_STEP_SECS, direction).takeIf { it >= 0 && it <= endSecs - WINDOW_STEP_SECS }

/** The window end after one step, or null at the limit: at least one step after [startSecs], at most 23:45. */
fun steppedEnd(startSecs: Int, endSecs: Int, direction: Int): Int? =
    snap(endSecs, WINDOW_STEP_SECS, direction).takeIf { it >= startSecs + WINDOW_STEP_SECS && it <= WINDOW_END_MAX_SECS }

/** Lead time after one step, or null at 15 or 180 minutes. */
fun steppedLead(leadMinutes: Int, direction: Int): Int? =
    snap(leadMinutes, LEAD_STEP_MINUTES, direction).takeIf { it in LEAD_MIN_MINUTES..LEAD_MAX_MINUTES }

/** The "late by" threshold after one step through [DELAY_CHOICES], or null at the ends. */
fun steppedThreshold(minutes: Int, direction: Int): Int? =
    if (direction > 0) DELAY_CHOICES.firstOrNull { it > minutes } else DELAY_CHOICES.lastOrNull { it < minutes }

/** The window a new watch starts with: from the next quarter hour, for 45 minutes. */
fun defaultWindow(nowSecsOfDay: Int): Pair<Int, Int> {
    val start = ((nowSecsOfDay + WINDOW_STEP_SECS - 1) / WINDOW_STEP_SECS * WINDOW_STEP_SECS)
        .coerceIn(0, WINDOW_END_MAX_SECS - WINDOW_STEP_SECS)
    return start to minOf(start + 3 * WINDOW_STEP_SECS, WINDOW_END_MAX_SECS)
}

// ---------------------------------------------------------------- wording

/** "07:15" for [secs] after midnight. */
fun clockOfSecs(secs: Int): String =
    (secs / 3600).toString().padStart(2, '0') + ":" + (secs % 3600 / 60).toString().padStart(2, '0')

/** "07:15–08:00". */
fun windowWords(startSecs: Int, endSecs: Int): String = clockOfSecs(startSecs) + "–" + clockOfSecs(endSecs)

/** "late by 5 min". */
fun lateWords(minutes: Int): String = "late by $minutes min"

/** "5 min", "45 min". */
fun minutesWords(minutes: Int): String = "$minutes min"

private val DAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * ISO days (1 = Monday) as words: "Mon–Fri", "Sat–Sun", "Every day", "Mon, Wed, Fri",
 * "Mon–Wed, Sat". A run of three or more is written as a range. Nothing: "No days".
 */
fun daysWords(days: Collection<Int>): String {
    val set = days.filter { it in 1..7 }.toSortedSet()
    if (set.isEmpty()) return "No days"
    if (set.size == 7) return "Every day"
    val parts = mutableListOf<String>()
    var i = 1
    while (i <= 7) {
        if (i !in set) { i++; continue }
        var j = i
        while (j + 1 in set) j++
        when {
            j - i >= 2 -> parts += "${DAY_SHORT[i - 1]}–${DAY_SHORT[j - 1]}"
            j == i + 1 -> { parts += DAY_SHORT[i - 1]; parts += DAY_SHORT[j - 1] }
            else -> parts += DAY_SHORT[i - 1]
        }
        i = j + 1
    }
    // Two neighbours read better as a range: "Sat–Sun".
    return if (parts.size == 2 && set.size == 2 && set.last() - set.first() == 1) "${parts[0]}–${parts[1]}" else parts.joinToString(", ")
}

/** A watch's summary: its title parts and the details after it. [line] is the plan's one-liner. */
data class WatchSummary(val title: List<String>, val details: List<String>) {
    val line: String get() = (listOf(title.joinToString(" ▶ ")) + details).joinToString(" · ")
}

/**
 * "Strathfield ▶ Central · Mon–Fri · 07:15–08:00 · late by 5 min" (plan 5.9). A one-off watch has
 * no days or window: "T9 07:42 Strathfield to Central · late by 5 min". [from] and [to] are the
 * station names when they could be told; without them the server's label stands in.
 * [departure] is the one-off service's departure time ("07:42") when known.
 */
fun watchSummary(watch: WatchDto, from: String?, to: String?, departure: String? = null): WatchSummary {
    val title = if (from != null && to != null && watch.kind == WatchKind.COMMUTE) listOf(from, to)
    else listOf(watch.label.ifBlank { listOfNotNull(from, to).joinToString(" to ").ifBlank { "Watch" } })
    val late = lateWords(watch.rules.delayThresholdMin)
    val details = when (watch.kind) {
        WatchKind.COMMUTE -> listOf(daysWords(watch.daysOfWeek), windowWords(watch.windowStartSecs, watch.windowEndSecs), late)
        WatchKind.SINGLE_SERVICE -> listOfNotNull(departure?.let { "leaves $it" }, late)
    }
    return WatchSummary(title, details)
}

/** True while the watch is paused at [nowMs]. */
fun isPaused(watch: WatchDto, nowMs: Long): Boolean = (watch.pausedUntilMs ?: 0) > nowMs

/** "PAUSED" or "PAUSED UNTIL FRI 13 NOV" (a pause of a year or more has no end date to show). */
fun pausedWords(watch: WatchDto, nowMs: Long, zone: TimeZone): String? {
    val until = watch.pausedUntilMs?.takeIf { it > nowMs } ?: return null
    return if (until - nowMs >= 365L * 24 * 3600 * 1000) "PAUSED" else "PAUSED UNTIL " + formatDayDate(until, zone)
}

/** "Watch 3 of 10" slot words for the editor header, upper case. */
fun slotWords(position: Int, limit: Int = WATCH_LIMIT): String = "WATCH $position OF $limit"

// ---------------------------------------------------------------- names

/** Which station names the saved trips tell us: first leg origin, last leg destination. */
fun tripStationNames(trips: List<SavedTrip>): Map<StopId, String> {
    val names = LinkedHashMap<StopId, String>()
    for (t in trips) {
        t.legs.firstOrNull()?.originStopIds?.forEach { names.getOrPut(it) { t.originName } }
        t.legs.lastOrNull()?.destinationStopIds?.forEach { names.getOrPut(it) { t.destinationName } }
    }
    return names
}

// ---------------------------------------------------------------- failures (plan 6: never an error code alone)

/** What the user is told when a watch cannot be saved, changed or deleted. [verb]: "save", "change", "delete". */
fun failureWords(e: Throwable, verb: String = "save"): String {
    val core = e as? TransitCoreException
    val text = (core?.message.orEmpty() + " " + core?.error?.title.orEmpty()).lowercase()
    return when {
        core == null -> "Could not $verb the watch. Try again in a moment."
        core.status == null -> "No connection, so the watch was not ${verb}d. Check your internet and try again."
        core.status == 409 || core.status == 422 || "limit" in text || "too many" in text ->
            "You already have $WATCH_LIMIT watches. Delete one to add another."
        core.status == 401 || core.status == 403 -> "The server did not recognise this phone. Check Settings and try again."
        else -> "The server could not $verb the watch. Try again in a moment."
    }
}

/** The line for a full watch list, shown instead of calling the server. */
fun limitWords(): String = "You already have $WATCH_LIMIT watches. Delete one to add another."

const val TEST_SENT_WORDS = "Test sent. It should reach this phone in a few seconds."
const val TEST_FAILED_WORDS = "The test did not go out. Check your connection and that notifications are allowed."
const val TEST_NEEDS_SAVE_WORDS = "Save the watch first, then send a test."
