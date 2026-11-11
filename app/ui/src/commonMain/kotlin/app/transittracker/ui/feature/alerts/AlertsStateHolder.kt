package app.transittracker.ui.feature.alerts

import app.transittracker.model.Alert
import app.transittracker.model.DataStatus
import app.transittracker.model.Journey
import app.transittracker.model.RouteId
import app.transittracker.model.SavedTrip
import app.transittracker.model.StopId
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.ui.alerts.tripLines
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the Alerts tab (plan 5.8): the core's alerts, the saved trips and the lines their
 * next services run on, so each alert can say which trip it touches. The filter and the expanded
 * card are the screen's own (they survive in the page's saved state).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertsStateHolder(
    private val core: TransitCore,
    private val clock: WallClock,
    scope: CoroutineScope,
    private val tickMs: Long = 15_000,
) : StateHolder<AlertsUiState>(scope) {
    private val lines = MutableStateFlow<Map<RouteId, LineInfo>>(emptyMap())
    private val asked = mutableSetOf<StopId>()

    private val upcoming: Flow<Map<String, List<Journey>>> = core.trips.flatMapLatest { trips ->
        if (trips.isEmpty()) flowOf(emptyMap())
        else combine(trips.map { t -> core.upcoming(t.id, 6).map { t.id to it } }) { it.toMap() }
    }

    /** Trip id -> the lines it can use, looked up once per change of the saved trips. */
    private val linesByTrip: Flow<Map<String, Set<RouteId>>> = core.trips.mapLatest { trips ->
        trips.associate { it.id to tripLines(core, it) }
    }

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(tickMs)
        }
    }

    private val inputs: Flow<AlertsInputs> = combine(
        core.alerts.map<_, List<Alert>?> { it }.onStart { emit(null) },
        core.trips.map<_, List<SavedTrip>?> { it }.onStart { emit(null) },
        upcoming.onStart { emit(emptyMap()) },
        core.dataStatus.map<_, DataStatus?> { it }.onStart { emit(null) },
        lines,
    ) { alerts, trips, up, status, known ->
        // Lines the trips run on are known from their departures; the rest are looked up by stop.
        val fromServices = up.values.flatten().flatMap { it.legs }.associate {
            it.routeId to LineInfo(it.routeId, it.routeName, it.routeColor, null)
        }
        alerts?.let(::lookUpLines)
        AlertsInputs(alerts, trips, up, status, fromServices + known)
    }.combine(linesByTrip.onStart { emit(emptyMap()) }) { i, lines -> i.copy(tripLines = lines) }

    override val state: StateFlow<AlertsUiState> = combine(inputs, ticker) { i, now -> buildAlertsState(i, now) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, AlertsUiState())

    /** Looks up the lines at the stops an alert names (once per stop), so its badges have names and colours. */
    private fun lookUpLines(alerts: List<Alert>) {
        for (stop in alerts.filter { it.routeIds.isNotEmpty() }.flatMap { it.stopIds }) {
            if (!asked.add(stop)) continue
            scope.launch {
                val routes = runCatching { core.linesAt(stop) }.getOrDefault(emptyList())
                lines.update { it + routes.associate { r -> r.id to LineInfo(r.id, r.shortName, r.color, r.textColor) } }
            }
        }
    }
}
