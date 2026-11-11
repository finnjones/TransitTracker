package app.transittracker.ui.feature.timetable

import app.transittracker.model.Alert
import app.transittracker.model.DataStatus
import app.transittracker.model.Journey
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceTime
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.ui.places.PlacesStore
import app.transittracker.ui.places.SavedPlace
import app.transittracker.ui.places.TripPlaces
import app.transittracker.ui.state.StateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the trip timetable (plan 5.3). Reads the saved trip, its places, the services of the
 * chosen day (`core.timetable`), the data status and alerts; ticks every [tickMs] so the NOW
 * marker moves. The day is the holder's to change ([moveDays], [goToToday], [setDate]); [date]
 * is what the screen keeps across being covered. Which services run, their status and times come
 * from the core; this only sorts, folds the ones that have left and works out the banners.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimetableStateHolder(
    private val core: TransitCore,
    places: PlacesStore,
    private val clock: WallClock,
    private val tripId: String,
    initialDate: ServiceDate?,
    scope: CoroutineScope,
    private val tickMs: Long = 15_000,
    /** How many days ahead to look for the next service when a day has none. */
    private val lookAheadDays: Int = 14,
) : StateHolder<TimetableUiState>(scope) {
    private val dateFlow = MutableStateFlow(initialDate ?: ServiceTime.localDate(clock.nowMillis()))
    private val next = MutableStateFlow<Map<ServiceDate, NextService?>>(emptyMap())
    private val asked = mutableSetOf<ServiceDate>()

    /** The day shown. */
    val date: StateFlow<ServiceDate> get() = dateFlow

    /** Moves the day by [days] (the screen disables the previous-day control before today). */
    fun moveDays(days: Int) {
        dateFlow.update { stepDay(it, days) }
    }

    fun setDate(date: ServiceDate) {
        dateFlow.value = date
    }

    fun goToToday() {
        dateFlow.value = ServiceTime.localDate(clock.nowMillis())
    }

    private val trip: Flow<Pair<Boolean, SavedTrip?>> = core.trips
        .map { list -> true to list.firstOrNull { it.id == tripId } }
        .onStart { emit(false to null) }

    private val rows: Flow<Pair<ServiceDate, List<Journey>?>> = dateFlow.flatMapLatest { d ->
        core.timetable(tripId, d).map<List<Journey>, Pair<ServiceDate, List<Journey>?>> { d to it }.onStart { emit(d to null) }
    }

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(tickMs)
        }
    }

    private class Aux(
        val places: List<SavedPlace>,
        val links: Map<String, TripPlaces>,
        val status: DataStatus?,
        val alerts: List<Alert>,
        val next: Map<ServiceDate, NextService?>,
        val nowMs: Long,
    )

    private val aux: Flow<Aux> = combine(
        combine(places.places, places.tripPlaces) { p, l -> p to l },
        combine(
            core.dataStatus.map<DataStatus, DataStatus?> { it }.onStart { emit(null) },
            core.alerts.onStart { emit(emptyList()) },
        ) { s, a -> s to a },
        next,
        ticker,
    ) { (p, l), (s, a), n, now -> Aux(p, l, s, a, n, now) }

    override val state: StateFlow<TimetableUiState> = combine(trip, rows, aux, dateFlow) { (read, t), (rowsDate, journeys), x, date ->
        val inputs = TimetableInputs(
            tripsRead = read,
            trip = t,
            places = x.places,
            links = x.links,
            date = date,
            journeys = if (rowsDate == date) journeys else null,
            status = x.status,
            alerts = x.alerts,
            next = x.next,
        )
        val s = buildTimetableState(inputs, x.nowMs)
        if (s.tripFound && s.nothingToCome) lookUpNext(date)
        s
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, TimetableUiState())

    /** Looks once per day for the next day with a service (for "NEXT THU 12 NOV 05:00"). */
    private fun lookUpNext(from: ServiceDate) {
        if (!asked.add(from)) return
        scope.launch {
            var found: NextService? = null
            runCatching {
                var d = from
                for (i in 1..lookAheadDays) {
                    d = stepDay(d, 1)
                    val first = core.timetable(tripId, d).first().minByOrNull { it.departure.expectedDepartureMs }
                    if (first != null) {
                        found = NextService(d, first.departure.expectedDepartureMs)
                        break
                    }
                }
            }
            next.update { it + (from to found) }
        }
    }
}
