package app.transittracker.ui.feature.plan

import app.transittracker.model.JourneyPlanner
import app.transittracker.model.Mode
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanResult
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceDetail
import app.transittracker.model.ServiceRef
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.model.rides
import app.transittracker.ui.format.shortStopName
import app.transittracker.ui.state.StateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class OptionsPhase { LOADING, FOUND, NOTHING_FOUND, OFFLINE, UNAVAILABLE, FAILED }

/** Everything the Journey options page draws (plan 5.6 step 2, Options mockup). */
data class OptionsUiState(
    val phase: OptionsPhase = OptionsPhase.LOADING,
    val failMessage: String? = null,
    /** In [sort] order; the first is the recommended journey. */
    val options: List<OptionSummary> = emptyList(),
    val sort: OptionSort = OptionSort.FASTEST,
    /** "TRAINS ARE LIVE. BUS TIMES ARE TIMETABLED." */
    val liveNote: String? = null,
    val nowMs: Long = 0,
    /** Nothing found: the stations near each end, so the user can see why (plan 6). */
    val nearFrom: List<String> = emptyList(),
    val nearTo: List<String> = emptyList(),
    /** Offline: the saved routes, which work without a connection (plan 6). */
    val savedRoutes: List<SavedTrip> = emptyList(),
)

/** Pure: the options state from the planner's answer, the phone's live data and the chosen sort. */
fun optionsState(
    result: PlanResult?,
    details: Map<ServiceRef, ServiceDetail>,
    sort: OptionSort,
    nowMs: Long,
): OptionsUiState = when (result) {
    null -> OptionsUiState(OptionsPhase.LOADING, sort = sort, nowMs = nowMs)
    PlanResult.Offline -> OptionsUiState(OptionsPhase.OFFLINE, sort = sort, nowMs = nowMs)
    PlanResult.Unavailable -> OptionsUiState(OptionsPhase.UNAVAILABLE, sort = sort, nowMs = nowMs)
    is PlanResult.Failed -> OptionsUiState(OptionsPhase.FAILED, failMessage = result.message, sort = sort, nowMs = nowMs)
    is PlanResult.Found -> {
        val its = result.response.itineraries
        if (its.isEmpty()) {
            OptionsUiState(OptionsPhase.NOTHING_FOUND, sort = sort, nowMs = nowMs)
        } else {
            val options = its.mapIndexed { i, it -> optionSummary(i, it, details) }
            val used = its.flatMap { it.rides }.mapNotNull { it.mode }.toSet()
            OptionsUiState(
                OptionsPhase.FOUND,
                options = sortOptions(options, sort),
                sort = sort,
                liveNote = liveModesNote(result.response.liveModes, used),
                nowMs = nowMs,
            )
        }
    }
}

/**
 * State for Journey options: asks the planner once (again on [retry]), follows the phone's own
 * live data for every ride that was matched to the timetable, re-sorts on [sort], and ticks every
 * [tickMs] so "LEAVE IN" counts down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JourneyOptionsStateHolder(
    private val planner: JourneyPlanner,
    private val core: TransitCore,
    private val clock: WallClock,
    private val request: PlanRequest,
    scope: CoroutineScope,
    private val tickMs: Long = 1_000,
) : StateHolder<OptionsUiState>(scope) {
    private val result = MutableStateFlow<PlanResult?>(null)
    private val sortBy = MutableStateFlow(OptionSort.FASTEST)
    private val near = MutableStateFlow<Pair<List<String>, List<String>>>(emptyList<String>() to emptyList())
    private var job: Job? = null

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(tickMs)
        }
    }

    private val details: Flow<Map<ServiceRef, ServiceDetail>> = result.flatMapLatest { r ->
        val refs = (r as? PlanResult.Found)?.response?.itineraries.orEmpty()
            .flatMap { it.rides }.mapNotNull { it.serviceRef }.distinct()
        if (refs.isEmpty()) flowOf(emptyMap())
        else combine(refs.map { ref -> core.service(ref).map<ServiceDetail, ServiceDetail?> { it }.onStart { emit(null) } }) { list ->
            list.filterNotNull().associateBy { it.ref }
        }
    }

    override val state: StateFlow<OptionsUiState> = combine(
        result, details.onStart { emit(emptyMap()) }, sortBy, ticker,
        combine(near, core.trips.onStart { emit(emptyList()) }) { n, t -> n to t },
    ) { r, d, s, now, (n, trips) ->
        optionsState(r, d, s, now).copy(nearFrom = n.first, nearTo = n.second, savedRoutes = trips)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, OptionsUiState(nowMs = clock.nowMillis()))

    init {
        load()
    }

    private fun load() {
        job?.cancel()
        result.value = null
        job = scope.launch {
            val r = runCatching { planner.plan(request) }.getOrElse { PlanResult.Failed(it.message ?: "The planner did not answer") }
            result.value = r
            if (r is PlanResult.Found && r.response.itineraries.isEmpty()) lookUpNearStops()
        }
    }

    private suspend fun lookUpNearStops() {
        suspend fun names(p: app.transittracker.model.LatLng) = runCatching {
            core.stopsNear(p, 1_500.0).map { shortStopName(it.name) }.distinct().take(3)
        }.getOrDefault(emptyList())
        near.value = names(request.from.position) to names(request.to.position)
    }

    fun retry() = load()

    fun sort(by: OptionSort) {
        sortBy.value = by
    }

    /** The vehicle of a journey's first ride, for the vehicle pass into it. */
    fun firstRide(option: OptionSummary): Pair<Mode, String?>? =
        option.itinerary.rides.firstOrNull()?.let { (it.mode ?: Mode.OTHER) to it.routeColor }
}
