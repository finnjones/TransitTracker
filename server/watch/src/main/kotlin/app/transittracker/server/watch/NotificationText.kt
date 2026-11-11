package app.transittracker.server.watch

import app.transittracker.model.ServiceTime

/** Notification kinds, as sent in [app.transittracker.model.PushKeys.KIND]. */
object NotificationKind {
    const val DELAY = "delay"
    const val CANCEL = "cancel"
    const val PLATFORM = "platform"
    const val ALERT = "alert"
    const val RECOVERED = "recovered"
    const val TEST = "test"
}

/** Wording of the notifications (plan section 7.3). All times are Sydney local time, e.g. "7:42". */
internal object NotificationText {

    /** "7:42" / "17:05" in Sydney local time. */
    fun time(epochMillis: Long): String {
        val secs = ServiceTime.secondsOfLocalDay(epochMillis)
        return "${secs / 3600}:${((secs % 3600) / 60).toString().padStart(2, '0')}"
    }

    /** Drops a trailing " Station" so bodies stay short ("Strathfield Station" -> "Strathfield"). */
    fun stationName(name: String): String = name.removeSuffix(" Station").ifBlank { name }

    fun cancelled(depart: Long, origin: String, destination: String, next: String) =
        "${time(depart)} $origin → $destination is cancelled. $next"

    fun skipsStop(depart: Long, origin: String, destination: String, skipped: String, next: String) =
        "${time(depart)} $origin → $destination won't stop at $skipped. $next"

    fun next(depart: Long, liveState: String) = "Next: ${time(depart)} ($liveState)"

    const val NO_NEXT = "No later service in your window"

    fun delayed(depart: Long, destination: String, minutes: Int, expected: Long) =
        "${time(depart)} to $destination is running $minutes min late (now ${time(expected)})"

    fun delayGrew(depart: Long, destination: String, minutes: Int, expected: Long) =
        "${time(depart)} to $destination now $minutes min late (${time(expected)})"

    fun platform(depart: Long, destination: String, platform: String) =
        "${time(depart)} to $destination now leaves from platform $platform"

    fun recovered(depart: Long, destination: String) = "${time(depart)} to $destination back on time"

    fun lateBy(minutes: Int) = "$minutes min late"

    const val ON_TIME = "on time"
    const val TIMETABLED = "timetabled"
}
