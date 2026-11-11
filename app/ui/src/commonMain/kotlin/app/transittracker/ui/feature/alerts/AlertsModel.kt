package app.transittracker.ui.feature.alerts

import app.transittracker.model.Alert
import app.transittracker.model.AlertSeverity
import app.transittracker.model.DataStatus
import app.transittracker.model.Journey
import app.transittracker.model.Mode
import app.transittracker.model.RouteId
import app.transittracker.model.SavedTrip
import app.transittracker.model.StopId
import app.transittracker.model.TimeRange
import app.transittracker.model.TripId
import app.transittracker.ui.format.formatClock
import app.transittracker.ui.format.formatDayDate
import app.transittracker.ui.settings.ClockFormat
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The two filters of the Alerts tab (plan 5.8). */
enum class AlertFilter { MY_TRIPS, ALL_LINES }

/** What a line badge on an alert card shows. */
data class LineInfo(val routeId: RouteId, val name: String, val colorHex: String? = null, val textColorHex: String? = null)

/**
 * Everything the Alerts tab draws. [mine] holds the alerts that touch a saved trip, [all] every
 * alert still to come or in force (a superset of [mine]); both are ordered worst first.
 */
data class AlertsUiState(
    /** True until alerts and trips have been read once (nothing is drawn, plan 6). */
    val loading: Boolean = true,
    val nowMs: Long = 0,
    val mine: List<AlertCard> = emptyList(),
    val all: List<AlertCard> = emptyList(),
    /** Saved trips no alert touches: each gets a green ALL CLEAR line. */
    val clearTrips: List<SavedTrip> = emptyList(),
    val tripCount: Int = 0,
    /** No connection to the server: the cards are the last alerts received (plan 6). */
    val offline: Boolean = false,
    /** When the alerts were last refreshed, for "UPDATED 07:37". */
    val updatedMs: Long? = null,
) {
    fun cards(filter: AlertFilter): List<AlertCard> = if (filter == AlertFilter.MY_TRIPS) mine else all
}

/** One alert with the saved trips it touches and the lines / stations it names. */
data class AlertCard(
    val alert: Alert,
    /** Saved trips this alert touches, in trip order (empty: it is only on the network). */
    val trips: List<SavedTrip>,
    val lines: List<LineInfo>,
    /** Names of the stations or stops the alert is about, when they could be told. */
    val stationNames: List<String>,
    /** "STATION" or "STOP" for the tag. */
    val stationWord: String,
) {
    val id: String get() = alert.id
    val expandable: Boolean get() = alert.description.isNotBlank() || !alert.url.isNullOrBlank()
}

// ---------------------------------------------------------------- which alerts touch which trip

/** What a saved trip passes through: its stops as saved, the lines it can use, its services today. */
data class TripFootprint(val stops: Set<StopId>, val routes: Set<RouteId>, val services: Set<TripId>)

/**
 * The footprint of [trip]: every origin and destination stop of its legs as saved, its line
 * filters plus the lines of its upcoming [journeys] (a trip without a line filter still runs on
 * the lines its services run on), and those services' trip ids.
 */
fun tripFootprint(trip: SavedTrip, journeys: List<Journey>): TripFootprint {
    val legs = journeys.flatMap { it.legs }
    return TripFootprint(
        stops = trip.legs.flatMap { it.originStopIds + it.destinationStopIds }.toSet(),
        routes = trip.legs.flatMap { it.routeIds.orEmpty() }.toSet() + legs.map { it.routeId },
        services = legs.map { it.ref.tripId }.toSet(),
    )
}

/** True when [alert] names a stop, a line or a service of [footprint]. */
fun alertTouchesTrip(alert: Alert, footprint: TripFootprint): Boolean =
    alert.stopIds.any { it in footprint.stops } ||
        alert.routeIds.any { it in footprint.routes } ||
        alert.tripIds.any { it in footprint.services }

/** True when every period of [alert] is over. An alert with no period is in force until it is withdrawn. */
fun alertEnded(alert: Alert, nowMs: Long): Boolean =
    alert.activePeriods.isNotEmpty() && alert.activePeriods.none { p -> p.endMs == null || nowMs < p.endMs!! }

/** Worst first: SEVERE, WARNING, INFO, UNKNOWN. */
fun severityRank(s: AlertSeverity): Int = when (s) {
    AlertSeverity.SEVERE -> 0
    AlertSeverity.WARNING -> 1
    AlertSeverity.INFO -> 2
    AlertSeverity.UNKNOWN -> 3
}

/** The word beside the severity icon. */
fun severityWord(s: AlertSeverity): String = when (s) {
    AlertSeverity.SEVERE -> "SEVERE"
    AlertSeverity.WARNING -> "WARNING"
    AlertSeverity.INFO -> "INFO"
    AlertSeverity.UNKNOWN -> "NOTICE"
}

/** Station names the trips tell us: the first leg's origin stops and the last leg's destination stops. */
fun knownStopNames(trips: List<SavedTrip>): Map<StopId, String> {
    val names = LinkedHashMap<StopId, String>()
    for (t in trips) {
        t.legs.firstOrNull()?.originStopIds?.forEach { names.getOrPut(it) { t.originName } }
        t.legs.lastOrNull()?.destinationStopIds?.forEach { names.getOrPut(it) { t.destinationName } }
    }
    return names
}

// ---------------------------------------------------------------- when it applies, how old it is

/**
 * When an alert applies, in the card's top-right corner (plan 5.8):
 * `NOW`, `NOW UNTIL 17:00`, `NOW UNTIL SAT 14 NOV`, `FRI 13 NOV`, `SAT 14 – SUN 15 NOV`,
 * `SAT 28 NOV – TUE 1 DEC`, `FROM FRI 13 NOV`, or `ENDED` when every period is over.
 * An alert with no period is in force now. Of several periods, the one in force, else the next to
 * start. A period that ends at midnight is written as ending the day before.
 */
@OptIn(ExperimentalTime::class)
fun alertWhen(periods: List<TimeRange>, nowMs: Long, zone: TimeZone, format: ClockFormat = ClockFormat.H24): String {
    if (periods.isEmpty()) return "NOW"
    val inForce = periods.firstOrNull { p -> (p.startMs == null || p.startMs!! <= nowMs) && (p.endMs == null || nowMs < p.endMs!!) }
    if (inForce != null) {
        val end = inForce.endMs ?: return "NOW"
        return "NOW UNTIL " + if (sameDay(end, nowMs, zone)) formatClock(end, zone, format) else formatDayDate(displayEnd(end, zone), zone)
    }
    val next = periods.filter { (it.startMs ?: Long.MIN_VALUE) > nowMs }.minByOrNull { it.startMs!! } ?: return "ENDED"
    val start = next.startMs!!
    val end = next.endMs ?: return "FROM " + formatDayDate(start, zone)
    val lastDay = displayEnd(end, zone)
    if (sameDay(start, lastDay, zone)) return formatDayDate(start, zone)
    val a = formatDayDate(start, zone).split(' ')
    val b = formatDayDate(lastDay, zone).split(' ')
    // a, b = [DOW, DAY, MON]
    return if (a[2] == b[2]) "${a[0]} ${a[1]} – ${b[0]} ${b[1]} ${b[2]}" else "${a.joinToString(" ")} – ${b.joinToString(" ")}"
}

/** A period ending exactly at local midnight belongs to the day before. */
@OptIn(ExperimentalTime::class)
private fun displayEnd(endMs: Long, zone: TimeZone): Long {
    val t = Instant.fromEpochMilliseconds(endMs).toLocalDateTime(zone)
    return if (t.hour == 0 && t.minute == 0 && t.second == 0) endMs - 1 else endMs
}

@OptIn(ExperimentalTime::class)
private fun sameDay(a: Long, b: Long, zone: TimeZone): Boolean =
    Instant.fromEpochMilliseconds(a).toLocalDateTime(zone).date == Instant.fromEpochMilliseconds(b).toLocalDateTime(zone).date

/** "JUST NOW", "5 MIN AGO", "1 H AGO", "2 D AGO"; null when the alert has no update time. */
fun agoWords(updatedMs: Long, nowMs: Long): String? {
    if (updatedMs <= 0) return null
    val minutes = ((nowMs - updatedMs).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 1 -> "JUST NOW"
        minutes < 60 -> "$minutes MIN AGO"
        minutes < 24 * 60 -> "${minutes / 60} H AGO"
        else -> "${minutes / (24 * 60)} D AGO"
    }
}

/** "STRATHFIELD TO CENTRAL" for the line under an alert and beside ALL CLEAR. */
fun tripWords(trip: SavedTrip): String = "${trip.originName} to ${trip.destinationName}".uppercase()

/** "ON YOUR TRIP · STRATHFIELD TO CENTRAL" (or TRIPS, joined with commas). */
fun onYourTripWords(trips: List<SavedTrip>): String =
    (if (trips.size == 1) "ON YOUR TRIP · " else "ON YOUR TRIPS · ") + trips.joinToString(", ") { tripWords(it) }

// ---------------------------------------------------------------- the state

/** What the holder gathers; [buildAlertsState] turns it into [AlertsUiState]. */
data class AlertsInputs(
    val alerts: List<Alert>?,
    val trips: List<SavedTrip>?,
    /** Trip id -> its upcoming journeys (for the lines it runs on). */
    val upcoming: Map<String, List<Journey>> = emptyMap(),
    val status: DataStatus? = null,
    /** Everything known about lines, by route id. */
    val lines: Map<RouteId, LineInfo> = emptyMap(),
)

/** Pure: the Alerts tab for [inputs] at [nowMs]. */
fun buildAlertsState(inputs: AlertsInputs, nowMs: Long): AlertsUiState {
    val alerts = inputs.alerts
    val trips = inputs.trips
    if (alerts == null || trips == null) return AlertsUiState(loading = true, nowMs = nowMs)
    val sortedTrips = trips.sortedBy { it.order }
    val prints = sortedTrips.associateWith { tripFootprint(it, inputs.upcoming[it.id].orEmpty()) }
    val names = knownStopNames(sortedTrips)
    val shown = alerts.filter { !alertEnded(it, nowMs) }
    val cards = shown.map { a ->
        val touched = sortedTrips.filter { alertTouchesTrip(a, prints.getValue(it)) }
        AlertCard(
            alert = a,
            trips = touched,
            lines = a.routeIds.map { id -> inputs.lines[id] ?: LineInfo(id, id) },
            stationNames = a.stopIds.mapNotNull { names[it] }.distinct(),
            stationWord = if (a.modes.isEmpty() || a.modes.any { it == Mode.TRAIN || it == Mode.METRO || it == Mode.LIGHT_RAIL }) "STATION" else "STOP",
        )
    }
    // Your trips first, then the worst, then the soonest.
    val ordered = cards.sortedWith(
        compareBy<AlertCard> { if (it.trips.isEmpty()) 1 else 0 }
            .thenBy { severityRank(it.alert.severity) }
            .thenBy { it.alert.activePeriods.minOfOrNull { p -> p.startMs ?: Long.MIN_VALUE } ?: Long.MIN_VALUE },
    )
    val touchedIds = ordered.flatMap { c -> c.trips.map { it.id } }.toSet()
    return AlertsUiState(
        loading = false,
        nowMs = nowMs,
        mine = ordered.filter { it.trips.isNotEmpty() },
        all = ordered,
        clearTrips = sortedTrips.filter { it.id !in touchedIds },
        tripCount = sortedTrips.size,
        offline = inputs.status?.serverReachable == false,
        updatedMs = inputs.status?.lastLiveUpdateMs,
    )
}
