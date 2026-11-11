package app.transittracker.ui.feature.plan

import app.transittracker.model.Itinerary
import app.transittracker.model.LegPoint
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceDetail
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopId
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.model.rides
import app.transittracker.ui.format.shortStopName
import app.transittracker.ui.nav.JourneyEnd
import app.transittracker.ui.places.PlacesStore
import app.transittracker.ui.state.StateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether, and how, the journey can be kept as a saved route. */
sealed interface SaveState {
    /** Checking that the phone knows every stop. */
    data object Checking : SaveState

    /** It can be saved: [trip], and [returnTrip] for "also save the return". */
    data class Ready(val trip: SavedTrip, val returnTrip: SavedTrip) : SaveState

    /** It cannot: a plain sentence why (shown instead of saving something misleading). */
    data class Blocked(val reason: String) : SaveState

    data object Saving : SaveState

    /** Saved; the screen goes back to Trips. */
    data class Saved(val tripIds: List<String>) : SaveState

    data class Failed(val message: String, val ready: Ready) : SaveState
}

/** Everything the One journey page draws (plan 5.6 step 3, Journey mockup). */
data class JourneyUiState(
    val timeline: Timeline,
    val nowMs: Long,
    val save: SaveState = SaveState.Checking,
)

/**
 * State for one journey, step by step: the timeline with the phone's own live data applied to
 * every ride matched to the timetable (margins recomputed as it moves), a one-second tick for
 * the countdown to leaving, and SAVE ROUTE.
 */
class JourneyStateHolder(
    private val core: TransitCore,
    private val places: PlacesStore,
    private val clock: WallClock,
    private val itinerary: Itinerary,
    private val from: JourneyEnd,
    private val to: JourneyEnd,
    scope: CoroutineScope,
    private val tickMs: Long = 1_000,
) : StateHolder<JourneyUiState>(scope) {
    private val saveState = MutableStateFlow<SaveState>(SaveState.Checking)

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(tickMs)
        }
    }

    private val details: Flow<Map<ServiceRef, ServiceDetail>> = run {
        val refs = itinerary.rides.mapNotNull { it.serviceRef }.distinct()
        if (refs.isEmpty()) flowOf(emptyMap())
        else combine(refs.map { ref -> core.service(ref).map<ServiceDetail, ServiceDetail?> { it }.onStart { emit(null) } }) { list ->
            list.filterNotNull().associateBy { it.ref }
        }
    }

    private val initial = JourneyUiState(journeyTimeline(itinerary, emptyMap(), from.name, to.name), clock.nowMillis())

    override val state: StateFlow<JourneyUiState> = combine(
        details.map { journeyTimeline(itinerary, it, from.name, to.name) }.distinctUntilChanged(),
        ticker,
        saveState,
    ) { t, now, s -> JourneyUiState(t, now, s) }
        .stateIn(scope, SharingStarted.Eagerly, initial)

    init {
        scope.launch { saveState.value = checkSave() }
    }

    /** Works out [SaveState.Ready] or [SaveState.Blocked]: pure checks, then the phone's timetable. */
    private suspend fun checkSave(): SaveState {
        saveBlocker(itinerary)?.let { return SaveState.Blocked(it) }
        val points: List<LegPoint> = itinerary.rides.flatMap { listOf(it.from, it.to) }
        val ids = points.mapNotNull { it.stopId }.distinct()
        val unknown = ids.filter { id -> runCatching { core.linesAt(id).isEmpty() }.getOrDefault(true) }.toSet()
        unknownStopsReason(itinerary, unknown)?.let { return SaveState.Blocked(it) }
        val stationOf = mutableMapOf<StopId, StopId>()
        for (p in points) {
            val id = p.stopId ?: continue
            if (id in stationOf) continue
            val near = runCatching { core.stopsNear(p.position, 400.0) }.getOrDefault(emptyList())
            val station = near.firstOrNull { it.id == id } ?: near.firstOrNull { shortStopName(it.name).equals(p.name, ignoreCase = true) }
            if (station != null) stationOf[id] = station.id
        }
        val trip = savedTripOf(itinerary, stationOf)
        return SaveState.Ready(trip, returnTripOf(trip))
    }

    /**
     * Saves the route (and its return when [withReturn]) and links it to the saved places it runs
     * between. New routes go at the end of Home.
     */
    fun save(withReturn: Boolean) {
        val ready = when (val s = saveState.value) {
            is SaveState.Ready -> s
            is SaveState.Failed -> s.ready
            else -> return
        }
        saveState.value = SaveState.Saving
        scope.launch {
            try {
                val existing = core.trips.first()
                var order = (existing.maxOfOrNull { it.order } ?: -1) + 1
                val saved = mutableListOf<String>()
                core.saveTrip(ready.trip.copy(order = order++))
                saved += ready.trip.id
                if (from.savedPlaceId != null || to.savedPlaceId != null) places.linkTrip(ready.trip.id, from.savedPlaceId, to.savedPlaceId)
                if (withReturn) {
                    core.saveTrip(ready.returnTrip.copy(order = order))
                    saved += ready.returnTrip.id
                    if (from.savedPlaceId != null || to.savedPlaceId != null) places.linkTrip(ready.returnTrip.id, to.savedPlaceId, from.savedPlaceId)
                }
                saveState.value = SaveState.Saved(saved)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                saveState.update { SaveState.Failed(e.message ?: "The route could not be saved", ready) }
            }
        }
    }
}
