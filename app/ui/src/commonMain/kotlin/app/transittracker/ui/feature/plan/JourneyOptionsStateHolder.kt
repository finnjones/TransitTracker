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
import app.transittracker.model.departMs
import app.transittracker.model.rides
import app.transittracker.ui.nav.JourneyEnd
import app.transittracker.ui.places.PlacesStore
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OptionsPhase { LOADING, FOUND, NOTHING_FOUND, OFFLINE, UNAVAILABLE, FAILED }

/** Everything the Journey options page draws (plan 5.6 step 2, Options mockup). */
data class OptionsUiState(
    val phase: OptionsPhase = OptionsPhase.LOADING,
    val failMessage: String? = null,
    /** In [sort] order; the first is the recommended journey. */
    val options: List<OptionSummary> = emptyList(),
    val sort: OptionSort = OptionSort.ARRIVE_FIRST,
    /** "TRAINS ARE LIVE. BUS TIMES ARE TIMETABLED." */
    val liveNote: String? = null,
    val nowMs: Long = 0,
    /** Nothing found: the stations near each end, so the user can see why (plan 6). */
    val nearFrom: List<String> = emptyList(),
    val nearTo: List<String> = emptyList(),
    /** Offline: the saved routes, which work without a connection (plan 6). */
    val savedRoutes: List<SavedTrip> = emptyList(),
    /** Whether SAVE THESE WAYS is offered (the page knows the journey's ends). */
    val canSaveWays: Boolean = false,
    /** Saving the journeys found as the ways of one route. */
    val saveWays: SaveWaysState = SaveWaysState.Idle,
)

/** One distinct way among the journeys found, for SAVE THESE WAYS. */
data class WayChoice(
    /** The way's id ([SavedTrip.ways]) or, when it cannot be saved, the itinerary's. */
    val id: String,
    /** "T9 from Strathfield" / "333 bus, then T4". */
    val title: String,
    /** The first time it leaves among the journeys found. */
    val leaveMs: Long,
    /** The way as a route of its own; null when it cannot be saved ([blocked] says why). */
    val trip: SavedTrip?,
    val blocked: String? = null,
    /** Already a way of the route it would join. */
    val alreadySaved: Boolean = false,
)

/** SAVE THESE WAYS (plan 5.6 "Saved routes", a route with several ways). */
sealed interface SaveWaysState {
    data object Idle : SaveWaysState

    /** Checking every journey against the phone's timetable. */
    data object Checking : SaveWaysState

    /**
     * The ways to choose from ([selected] ids ticked). [joinTitle] names the saved route between
     * the same ends they can be added to, when there is one.
     */
    data class Choosing(val ways: List<WayChoice>, val selected: Set<String>, val joinTitle: String? = null) : SaveWaysState

    data object Saving : SaveWaysState

    data class Saved(val tripIds: List<String>) : SaveWaysState

    data class Failed(val message: String) : SaveWaysState
}

/** "T9 from Strathfield", "T9 from Strathfield, then T4", "333 bus from Ocean St". */
fun wayTitle(itinerary: app.transittracker.model.Itinerary): String {
    val rides = itinerary.rides
    if (rides.isEmpty()) return "Walk"
    val first = rides.first()
    val line = first.routeName ?: "Service"
    val head = if (first.mode == Mode.BUS || first.mode == Mode.COACH) "$line bus" else line
    val rest = rides.drop(1).joinToString("") { ", then ${it.routeName ?: "a service"}" }
    return "$head from ${shortStopName(first.from.name)}$rest"
}

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
    /** The journey's ends and saved places, for SAVE THESE WAYS; without them it is not offered. */
    private val from: JourneyEnd? = null,
    private val to: JourneyEnd? = null,
    places: PlacesStore? = null,
) : StateHolder<OptionsUiState>(scope) {
    private val saver = places?.let { RouteSaver(core, it) }
    private val saveWays = MutableStateFlow<SaveWaysState>(SaveWaysState.Idle)
    private val result = MutableStateFlow<PlanResult?>(null)
    private val sortBy = MutableStateFlow(OptionSort.ARRIVE_FIRST)
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
        combine(near, core.trips.onStart { emit(emptyList()) }, saveWays) { n, t, w -> Triple(n, t, w) },
    ) { r, d, s, now, (n, trips, ways) ->
        optionsState(r, d, s, now).copy(
            nearFrom = n.first, nearTo = n.second, savedRoutes = trips,
            canSaveWays = saver != null && from != null && to != null, saveWays = ways,
        )
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

    /** SAVE THESE WAYS: checks every journey found and offers the distinct ways, all ticked but those already saved. */
    fun startSaveWays() {
        val saver = saver ?: return
        val from = from ?: return
        val to = to ?: return
        val its = (result.value as? PlanResult.Found)?.response?.itineraries.orEmpty()
        if (its.isEmpty() || saveWays.value !is SaveWaysState.Idle && saveWays.value !is SaveWaysState.Failed) return
        saveWays.value = SaveWaysState.Checking
        scope.launch {
            val found = LinkedHashMap<String, WayChoice>()
            for (it in its.sortedBy { i -> i.departMs }) {
                val choice = when (val c = saver.check(it)) {
                    is Savable.Ready -> WayChoice(c.trip.ways.first().id, wayTitle(it), it.departMs, c.trip)
                    is Savable.Blocked -> WayChoice(it.id, wayTitle(it), it.departMs, null, c.reason)
                }
                // The same way at another time is the same way; a way that cannot be saved is listed once by title.
                val key = if (choice.trip != null) choice.id else "blocked:" + choice.title
                if (key !in found) found[key] = choice
            }
            val ready = found.values.mapNotNull { it.trip }
            val plan = if (ready.isEmpty()) null else saver.plan(combineWays(ready, from.name, to.name), from, to)
            val have = plan?.joinTo?.ways?.mapTo(HashSet()) { it.id }.orEmpty()
            val ways = found.values.map { w -> w.copy(alreadySaved = w.trip != null && w.id in have) }
            saveWays.value = SaveWaysState.Choosing(
                ways,
                ways.filter { it.trip != null && !it.alreadySaved }.mapTo(LinkedHashSet()) { it.id },
                plan?.joinTitle,
            )
        }
    }

    /** Ticks or unticks way [id] in SAVE THESE WAYS. */
    fun toggleWay(id: String) {
        saveWays.update { s ->
            if (s !is SaveWaysState.Choosing || s.ways.none { it.id == id && it.trip != null && !it.alreadySaved }) s
            else s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)
        }
    }

    fun cancelSaveWays() {
        saveWays.value = SaveWaysState.Idle
    }

    /**
     * Saves the ticked ways as one route (and its return when [withReturn]); with [join], as more
     * ways of the saved route between the same ends.
     */
    fun confirmSaveWays(withReturn: Boolean, join: Boolean) {
        val saver = saver ?: return
        val from = from ?: return
        val to = to ?: return
        val s = saveWays.value as? SaveWaysState.Choosing ?: return
        val trips = s.ways.filter { it.id in s.selected }.mapNotNull { it.trip }
        if (trips.isEmpty()) return
        saveWays.value = SaveWaysState.Saving
        scope.launch {
            try {
                saveWays.value = SaveWaysState.Saved(saver.save(combineWays(trips, from.name, to.name), from, to, withReturn, join))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                saveWays.value = SaveWaysState.Failed(e.message ?: "The route could not be saved")
            }
        }
    }

    fun sort(by: OptionSort) {
        sortBy.value = by
    }

    /** The vehicle of a journey's first ride, for the vehicle pass into it. */
    fun firstRide(option: OptionSummary): Pair<Mode, String?>? =
        option.itinerary.rides.firstOrNull()?.let { (it.mode ?: Mode.OTHER) to it.routeColor }
}
