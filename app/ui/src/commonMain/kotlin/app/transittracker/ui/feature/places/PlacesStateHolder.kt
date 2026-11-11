package app.transittracker.ui.feature.places

import app.transittracker.model.StopId
import app.transittracker.model.TransitCore
import app.transittracker.ui.places.PlaceIcon
import app.transittracker.ui.places.PlacesStore
import app.transittracker.ui.state.StateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A failed action, as a dialog (plan 6): a title and a plain sentence; the dialog offers RETRY and CLOSE. */
data class Problem(val title: String, val words: String)

/** One switched-on stop of a place in the list: its name, the walk and the lines that call there. */
data class PlaceStopLine(
    val stopId: StopId,
    val name: String,
    val walkMinutes: Int,
    val estimated: Boolean,
    val lines: List<LineBadge>,
)

/** One place in the list (Places mockup). */
data class PlaceCard(
    val id: String,
    val name: String,
    val icon: PlaceIcon,
    val address: String,
    /** Switched-on stops only, nearest first. */
    val stops: List<PlaceStopLine>,
    /** Saved routes that start or end here. */
    val usedBy: Int,
) {
    val usedByWords: String get() = usedByWords(usedBy)
}

data class PlacesUiState(
    val loading: Boolean = true,
    val cards: List<PlaceCard> = emptyList(),
    /** The order is being changed: each place shows move buttons instead of its stops. */
    val reordering: Boolean = false,
) {
    val isEmpty: Boolean get() = !loading && cards.isEmpty()
    val canReorder: Boolean get() = cards.size >= 2
}

/**
 * State for the saved places list (plan 5.7). Reads the places and their route links from the
 * [PlacesStore] and the lines at each stop from the core; reordering writes back to the store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlacesStateHolder(
    private val places: PlacesStore,
    private val core: TransitCore,
    scope: CoroutineScope,
) : StateHolder<PlacesUiState>(scope) {
    private val reordering = MutableStateFlow(false)
    private val lineCache = HashMap<StopId, List<LineBadge>>()

    private val cards = combine(places.places, places.tripPlaces) { p, l -> p to l }.flatMapLatest { (list, links) ->
        flow {
            emit(
                list.map { p ->
                    PlaceCard(
                        p.id, p.name, p.icon, p.address,
                        p.enabledStops.map { s -> PlaceStopLine(s.stopId, s.name, s.walkMinutes, s.estimated, linesAt(s.stopId)) },
                        routesUsingPlace(p.id, links.values),
                    )
                },
            )
        }
    }

    override val state: StateFlow<PlacesUiState> = combine(cards, reordering) { c, r ->
        PlacesUiState(loading = false, cards = c, reordering = r && c.size >= 2)
    }.stateIn(scope, SharingStarted.Eagerly, PlacesUiState())

    private suspend fun linesAt(stopId: StopId): List<LineBadge> = lineCache.getOrPut(stopId) {
        runCatching { lineBadges(core.linesAt(stopId)) }.getOrElse { if (it is CancellationException) throw it else emptyList() }
    }

    fun toggleReordering() {
        reordering.update { !it }
    }

    /** Moves place [id] one step up (-1) or down (+1). */
    fun move(id: String, delta: Int) {
        scope.launch {
            val ids = places.currentPlaces().map { it.id }
            val moved = movedIds(ids, id, delta)
            if (moved != ids) runCatching { places.reorderPlaces(moved) }
        }
    }
}
