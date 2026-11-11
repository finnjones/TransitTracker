package app.transittracker.ui.feature.timetable

import app.transittracker.model.Alert
import app.transittracker.model.AlertSeverity
import app.transittracker.model.DataStatus
import app.transittracker.model.Departure
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Journey
import app.transittracker.model.RouteId
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceTime
import app.transittracker.ui.feature.home.DataChip
import app.transittracker.ui.feature.home.HomeBanner
import app.transittracker.ui.feature.home.banners
import app.transittracker.ui.feature.home.dataChip
import app.transittracker.ui.places.SavedPlace
import app.transittracker.ui.places.TripPlaces
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/** Everything the trip timetable draws (plan 5.3). Built by [buildTimetableState]; times are epoch ms. */
data class TimetableUiState(
    /** True until the saved trip has been read once. */
    val loading: Boolean = true,
    /** False when no saved trip has this id (it was deleted): the screen says so. */
    val tripFound: Boolean = true,
    val nowMs: Long = 0,
    /** "Home" > "Work", the stations, or a custom name (one part). */
    val titleParts: List<String> = emptyList(),
    /** "STRATHFIELD TO CENTRAL" under a title made of place names or a custom name. */
    val stationsLine: String? = null,
    /** The day shown, `YYYYMMDD`, and today. */
    val date: ServiceDate = "",
    val today: ServiceDate = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = true,
    val chip: DataChip? = null,
    val banners: List<HomeBanner> = emptyList(),
    /** Alerts on this trip's lines during the shown day. */
    val alerts: List<TripAlert> = emptyList(),
    /** True once the day's services have been read. */
    val rowsLoaded: Boolean = false,
    /** Services that have already left (today only), earliest first; folded above the NOW marker. */
    val earlier: List<TimetableEntry> = emptyList(),
    /** Services still to come, or all of the day's services on another day. */
    val upcoming: List<TimetableEntry> = emptyList(),
    /** No service of the day has live data (a future day, or no connection). */
    val timetableOnly: Boolean = false,
    /** When nothing is left to show: the next service on a later day; null when none was found. */
    val next: NextService? = null,
    /** True once [next] has been looked for. */
    val nextSearched: Boolean = false,
) {
    val isToday: Boolean get() = date == today
    val isEmpty: Boolean get() = rowsLoaded && earlier.isEmpty() && upcoming.isEmpty()
    /** Nothing more to come on this day (empty, or everything has left). */
    val nothingToCome: Boolean get() = rowsLoaded && upcoming.isEmpty()
}

/** One row of the list: a journey (one leg, or several for a trip with changes). */
data class TimetableEntry(val journey: Journey, val durationMinutes: Int) {
    val key: String get() = journey.legs.joinToString("+") { it.ref.tripId + "@" + it.ref.serviceDate }
}

/** The first service of a later day, for "NO SERVICES · NEXT THU 12 NOV 05:00". */
data class NextService(val date: ServiceDate, val departureMs: Long)

data class TripAlert(val id: String, val headline: String, val severity: AlertSeverity)

enum class DayRelation { YESTERDAY, TODAY, TOMORROW, OTHER }

// ---------------------------------------------------------------- days

/** [date] moved by [days] (negative for earlier); across midnight, month and year ends. */
fun stepDay(date: ServiceDate, days: Int): ServiceDate =
    ServiceTime.formatDate(ServiceTime.parseDate(date).plus(days, DateTimeUnit.DAY))

/** Whole days from [today] to [date] (negative for earlier days). */
fun daysFrom(today: ServiceDate, date: ServiceDate): Int =
    ServiceTime.parseDate(today).daysUntil(ServiceTime.parseDate(date))

fun dayRelation(date: ServiceDate, today: ServiceDate): DayRelation = when (daysFrom(today, date)) {
    0 -> DayRelation.TODAY
    1 -> DayRelation.TOMORROW
    -1 -> DayRelation.YESTERDAY
    else -> DayRelation.OTHER
}

/** The previous-day control works back to today only: the timetable is for what is still to come. */
fun canStepBack(date: ServiceDate, today: ServiceDate): Boolean = date > today

/** The next-day control works until the timetable pack ends ([DataStatus.packs] `validTo`). */
fun canStepForward(date: ServiceDate, status: DataStatus?): Boolean {
    val ends = status?.packs?.mapNotNull { it.validTo }?.minOrNull() ?: return true
    return stepDay(date, 1) <= ends
}

// ---------------------------------------------------------------- rows

/** Journeys in departure order as entries, with their duration (expected arrival minus departure). */
fun entriesOf(journeys: List<Journey>): List<TimetableEntry> =
    journeys.sortedBy { it.departure.expectedDepartureMs }.map { j ->
        val minutes = ((j.arrival.expectedArrivalMs - j.departure.expectedDepartureMs) / 60_000L).toInt().coerceAtLeast(0)
        TimetableEntry(j, minutes)
    }

/**
 * Folds the services that have already left (plan 5.3): on [today] those whose first departure is
 * before [nowMs] go above the NOW marker; on any other day nothing is folded.
 */
fun splitEarlier(
    entries: List<TimetableEntry>,
    date: ServiceDate,
    today: ServiceDate,
    nowMs: Long,
): Pair<List<TimetableEntry>, List<TimetableEntry>> =
    if (date != today) emptyList<TimetableEntry>() to entries
    else entries.partition { it.journey.departure.expectedDepartureMs < nowMs }

/**
 * The leg whose status the row shows: a cancelled leg first (the journey cannot be done), then a
 * late or early one, else the first leg.
 */
fun headlineLeg(journey: Journey): Departure =
    journey.legs.firstOrNull { it.status == DepartureStatus.CANCELLED }
        ?: journey.legs.firstOrNull { it.status == DepartureStatus.LATE || it.status == DepartureStatus.EARLY }
        ?: journey.legs.first()

/** True when no leg of any journey has live data. */
fun isTimetableOnly(journeys: List<Journey>): Boolean =
    journeys.isNotEmpty() && journeys.all { j -> j.legs.all { it.status == DepartureStatus.TIMETABLED } }

// ---------------------------------------------------------------- alerts

/**
 * Alerts that touch this trip's lines on the day shown: the alert names a line the trip is limited
 * to or one the day's services run on, and one of its active periods overlaps the day (an alert
 * with no period is always active). Most severe first.
 */
fun alertsForTrip(
    alerts: List<Alert>,
    trip: SavedTrip,
    journeys: List<Journey>,
    date: ServiceDate,
): List<TripAlert> {
    val routes: Set<RouteId> = trip.legs.flatMap { it.routeIds.orEmpty() }.toSet() +
        journeys.flatMap { j -> j.legs.map { it.routeId } }
    val dayStart = ServiceTime.referenceMillis(date)
    val dayEnd = ServiceTime.referenceMillis(stepDay(date, 1))
    return alerts
        .filter { a ->
            a.routeIds.any { it in routes } && (
                a.activePeriods.isEmpty() ||
                    a.activePeriods.any { p -> (p.startMs ?: Long.MIN_VALUE) < dayEnd && (p.endMs ?: Long.MAX_VALUE) > dayStart }
                )
        }
        .sortedBy { severityRank(it.severity) }
        .map { TripAlert(it.id, it.headline, it.severity) }
}

private fun severityRank(s: AlertSeverity): Int = when (s) {
    AlertSeverity.SEVERE -> 0
    AlertSeverity.WARNING -> 1
    AlertSeverity.INFO -> 2
    else -> 3
}

// ---------------------------------------------------------------- the whole screen

/** Title parts for a trip as Home shows them: the linked places, a custom name, or the stations. */
fun tripTitleParts(trip: SavedTrip, from: SavedPlace?, to: SavedPlace?): List<String> = when {
    from != null || to != null -> listOf(from?.name ?: trip.originName, to?.name ?: trip.destinationName)
    trip.customName != null -> listOf(trip.customName!!)
    else -> listOf(trip.originName, trip.destinationName)
}

/** What the holder gathers; [buildTimetableState] turns it into [TimetableUiState]. */
data class TimetableInputs(
    /** Null until the saved trips were read; then the trip, or null if it does not exist. */
    val tripsRead: Boolean,
    val trip: SavedTrip?,
    val places: List<SavedPlace> = emptyList(),
    val links: Map<String, TripPlaces> = emptyMap(),
    val date: ServiceDate,
    /** The day's journeys; null until read. */
    val journeys: List<Journey>?,
    val status: DataStatus? = null,
    val alerts: List<Alert> = emptyList(),
    /** Next service after the shown day: absent until searched. */
    val next: Map<ServiceDate, NextService?> = emptyMap(),
)

/** Pure: the timetable for [inputs] at [nowMs]. */
fun buildTimetableState(inputs: TimetableInputs, nowMs: Long): TimetableUiState {
    val today = ServiceTime.localDate(nowMs)
    if (!inputs.tripsRead) return TimetableUiState(loading = true, nowMs = nowMs, date = inputs.date, today = today)
    val trip = inputs.trip ?: return TimetableUiState(loading = false, tripFound = false, nowMs = nowMs, date = inputs.date, today = today)
    val placesById = inputs.places.associateBy { it.id }
    val link = inputs.links[trip.id]
    val from = link?.fromPlaceId?.let { placesById[it] }
    val to = link?.toPlaceId?.let { placesById[it] }
    val linked = from != null || to != null
    val journeys = inputs.journeys
    val entries = journeys?.let(::entriesOf).orEmpty()
    val (earlier, upcoming) = splitEarlier(entries, inputs.date, today, nowMs)
    val nothingToCome = journeys != null && upcoming.isEmpty()
    return TimetableUiState(
        loading = false,
        tripFound = true,
        nowMs = nowMs,
        titleParts = tripTitleParts(trip, from, to),
        stationsLine = if (linked || trip.customName != null) "${trip.originName} to ${trip.destinationName}".uppercase() else null,
        date = inputs.date,
        today = today,
        canGoBack = canStepBack(inputs.date, today),
        canGoForward = canStepForward(inputs.date, inputs.status),
        chip = dataChip(inputs.status, nowMs),
        banners = banners(inputs.status, today),
        alerts = journeys?.let { alertsForTrip(inputs.alerts, trip, it, inputs.date) }
            ?: alertsForTrip(inputs.alerts, trip, emptyList(), inputs.date),
        rowsLoaded = journeys != null,
        earlier = earlier,
        upcoming = upcoming,
        timetableOnly = journeys != null && isTimetableOnly(journeys),
        next = if (nothingToCome) inputs.next[inputs.date] else null,
        nextSearched = nothingToCome && inputs.date in inputs.next,
    )
}
