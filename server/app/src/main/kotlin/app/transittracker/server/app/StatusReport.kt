package app.transittracker.server.app

import app.transittracker.model.PackInfo
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** `/status` as JSON (also the model for the HTML page). */
@Serializable
data class StatusReport(
    val serverTimeMs: Long,
    val timetableVersion: String?,
    val live: LiveStatusView,
    val watches: WatchStatusView,
    val packs: List<PackInfo>,
    val activeDevices: Int,
    val recentNotifications: List<NotificationView>,
)

/** Serializable copy of [app.transittracker.server.spi.LiveStatus]. */
@Serializable
data class LiveStatusView(val callsToday: Int, val dailyBudget: Int, val feeds: List<FeedStatusView>)

/** Serializable copy of [app.transittracker.server.spi.FeedStatus]. */
@Serializable
data class FeedStatusView(
    val feedId: String,
    val kind: String,
    val lastFetchMs: Long?,
    val lastSuccessMs: Long?,
    val dataTimeMs: Long?,
    val lastHttpStatus: Int?,
    val consecutiveErrors: Int,
    val backoffUntilMs: Long?,
    val matchedTrips: Int,
    val unmatchedTrips: Int,
    val stale: Boolean,
)

/** Serializable copy of [app.transittracker.server.spi.WatchStatus]. */
@Serializable
data class WatchStatusView(val totalWatches: Int, val activeWatches: Int, val sentToday: Int, val failedToday: Int)

/** Serializable copy of [app.transittracker.server.spi.NotificationLogEntry]. */
@Serializable
data class NotificationView(
    val atMs: Long,
    val deviceId: String,
    val watchId: String?,
    val tripId: String?,
    val serviceDate: String?,
    val kind: String,
    val title: String,
    val result: String,
)

internal fun buildStatusReport(deps: ApiDependencies): StatusReport {
    val live = deps.live.status()
    val watch = deps.watches.status()
    return StatusReport(
        serverTimeMs = deps.clock.nowMillis(),
        timetableVersion = deps.timetables.current.value?.version,
        live = LiveStatusView(
            callsToday = live.callsToday,
            dailyBudget = live.dailyBudget,
            feeds = live.feeds.map {
                FeedStatusView(
                    it.feedId, it.kind, it.lastFetchMs, it.lastSuccessMs, it.dataTimeMs, it.lastHttpStatus,
                    it.consecutiveErrors, it.backoffUntilMs, it.matchedTrips, it.unmatchedTrips, it.stale,
                )
            },
        ),
        watches = WatchStatusView(watch.totalWatches, watch.activeWatches, watch.sentToday, watch.failedToday),
        packs = deps.timetables.packs(),
        activeDevices = deps.devices.activeCount(),
        recentNotifications = deps.notifications.recent(25).map {
            NotificationView(it.atMs, it.deviceId, it.watchId, it.tripId, it.serviceDate, it.kind, it.title, it.result)
        },
    )
}

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Australia/Sydney"))

/** Formats epoch millis as Sydney local time, or "-" for null. */
internal fun formatTime(ms: Long?): String = ms?.let { TIME_FORMAT.format(Instant.ofEpochMilli(it)) } ?: "-"

private fun esc(value: Any?): String = (value?.toString() ?: "-")
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

/** A plain, dependency-free HTML rendering of [report] for the owner's browser. */
internal fun renderStatusHtml(report: StatusReport): String = buildString {
    fun table(headers: List<String>, rows: List<List<Any?>>) {
        append("<table><tr>")
        headers.forEach { append("<th>").append(esc(it)).append("</th>") }
        append("</tr>")
        if (rows.isEmpty()) append("<tr><td colspan=\"${headers.size}\">none</td></tr>")
        rows.forEach { row ->
            append("<tr>")
            row.forEach { append("<td>").append(esc(it)).append("</td>") }
            append("</tr>")
        }
        append("</table>")
    }

    append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    append("<meta http-equiv=\"refresh\" content=\"30\"><title>tt-server status</title><style>")
    append("body{font-family:system-ui,sans-serif;margin:16px;color:#111;background:#fff}")
    append("table{border-collapse:collapse;margin-bottom:20px;font-size:14px}")
    append("th,td{border:1px solid #ccc;padding:4px 8px;text-align:left}th{background:#f2f2f2}")
    append(".stale{color:#b00}@media(prefers-color-scheme:dark){body{background:#111;color:#eee}th{background:#222}}")
    append("</style></head><body>")
    append("<h1>tt-server status</h1>")
    append("<p>Server time: ").append(esc(formatTime(report.serverTimeMs)))
    append(" · Timetable: ").append(esc(report.timetableVersion ?: "not loaded"))
    append(" · Active devices: ").append(report.activeDevices).append("</p>")

    append("<h2>Live data</h2><p>TfNSW calls today: ")
        .append(report.live.callsToday).append(" / ").append(report.live.dailyBudget).append("</p>")
    table(
        listOf("Feed", "Kind", "Last fetch", "Last success", "Data time", "HTTP", "Errors", "Backoff until", "Matched", "Unmatched", "Stale"),
        report.live.feeds.map {
            listOf(
                it.feedId, it.kind, formatTime(it.lastFetchMs), formatTime(it.lastSuccessMs), formatTime(it.dataTimeMs),
                it.lastHttpStatus, it.consecutiveErrors, formatTime(it.backoffUntilMs), it.matchedTrips, it.unmatchedTrips,
                if (it.stale) "STALE" else "ok",
            )
        },
    )

    append("<h2>Watches</h2>")
    table(
        listOf("Total", "Active", "Sent today", "Failed today"),
        listOf(report.watches.let { listOf(it.totalWatches, it.activeWatches, it.sentToday, it.failedToday) }),
    )

    append("<h2>Timetable packs</h2>")
    table(
        listOf("Name", "Version", "Size", "Valid", "SHA-256"),
        report.packs.map { listOf(it.name, it.version, it.sizeBytes, "${it.validFrom}–${it.validTo}", it.sha256.take(16)) },
    )

    append("<h2>Recent notifications</h2>")
    table(
        listOf("Time", "Device", "Kind", "Title", "Result"),
        report.recentNotifications.map { listOf(formatTime(it.atMs), it.deviceId.take(8), it.kind, it.title, it.result) },
    )
    append("</body></html>")
}
