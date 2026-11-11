package app.transittracker.ui.feature.plan

import app.transittracker.model.DataStatus
import app.transittracker.model.JourneyPlanner
import app.transittracker.model.Place
import app.transittracker.model.PlaceKind
import app.transittracker.model.PlacesResult
import app.transittracker.model.SavedTrip
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.ui.nav.JourneyEnd
import app.transittracker.ui.nav.Route
import app.transittracker.ui.places.PlaceIcon
import app.transittracker.ui.places.PlacesStore
import app.transittracker.ui.places.SavedPlace
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.platform.PlatformServices
import app.transittracker.ui.state.StateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The two ends of the form. */
enum class Field { FROM, TO }

/** The icon beside a chosen end. */
enum class EndIcon { HOME, WORK, PIN, STOP, ADDRESS, HERE }

/** One chosen end: what is sent to the planner, and how the form shows it. */
data class EndChoice(val end: JourneyEnd, val detail: String, val icon: EndIcon)

/** The address and stop search under a field being edited. */
sealed interface SearchState {
    data object Idle : SearchState
    data object Searching : SearchState
    data class Found(val places: List<Place>) : SearchState
    data object NoResults : SearchState
    data object Offline : SearchState
    data object Unavailable : SearchState
    data class Failed(val message: String) : SearchState
}

/** Everything the Plan a journey form draws (plan 5.6 step 1, NewTrip mockup). */
data class PlanFormUiState(
    val from: EndChoice? = null,
    val to: EndChoice? = null,
    /** The field whose search is open, if any. */
    val editing: Field? = null,
    val query: String = "",
    val search: SearchState = SearchState.Idle,
    val whenKind: WhenKind = WhenKind.NOW,
    /** The LEAVE AT / ARRIVE BY time (epoch ms, on a five-minute mark). */
    val pickedMs: Long = 0,
    val nowMs: Long = 0,
    val places: List<SavedPlace> = emptyList(),
    val recent: List<RecentSearch> = emptyList(),
    val savedRoutes: List<SavedTrip> = emptyList(),
    /** The server cannot be reached: planning is offline (plan 6). */
    val offline: Boolean = false,
    /** A search said the planner is not available right now. */
    val plannerUnavailable: Boolean = false,
    val locating: Boolean = false,
    /** A plain sentence about the last action (location refused, not found). */
    val notice: String? = null,
) {
    /** Where a quick pick goes: the field being edited, else the first empty one, else To. */
    val target: Field get() = editing ?: if (from == null) Field.FROM else Field.TO

    val sameEnds: Boolean get() = from != null && to != null && from.end.position == to.end.position

    val canFind: Boolean get() = from != null && to != null && !sameEnds
}

fun iconFor(icon: PlaceIcon): EndIcon = when (icon) {
    PlaceIcon.HOME -> EndIcon.HOME
    PlaceIcon.WORK -> EndIcon.WORK
    PlaceIcon.PIN -> EndIcon.PIN
}

fun choiceOf(place: SavedPlace): EndChoice =
    EndChoice(JourneyEnd(place.name, place.position, savedPlaceId = place.id), place.address, iconFor(place.icon))

fun choiceOf(place: Place): EndChoice = EndChoice(
    JourneyEnd(place.name, place.position, plannerPlaceId = place.id),
    place.detail,
    when (place.kind) {
        PlaceKind.STOP -> EndIcon.STOP
        PlaceKind.ADDRESS -> EndIcon.ADDRESS
        else -> EndIcon.PIN
    },
)

const val WHERE_I_AM = "Where I am"

/** A recent end shown again: its saved place's icon and address when it was one. */
fun choiceOf(end: JourneyEnd, places: List<SavedPlace>): EndChoice {
    val place = end.savedPlaceId?.let { id -> places.firstOrNull { it.id == id } }
    return when {
        place != null -> EndChoice(end, place.address, iconFor(place.icon))
        end.name == WHERE_I_AM -> EndChoice(end, "Your location then", EndIcon.HERE)
        end.plannerPlaceId != null -> EndChoice(end, "", EndIcon.PIN)
        else -> EndChoice(end, "", EndIcon.ADDRESS)
    }
}

/**
 * State for Plan a journey. Ends come from saved places (one tap), the planner's place search
 * (debounced by [searchDebounceMs]) or WHERE I AM (location permission is asked only then).
 * [find] makes the options route and remembers the search in [recents].
 */
@OptIn(FlowPreview::class)
class PlanJourneyStateHolder(
    private val planner: JourneyPlanner,
    core: TransitCore,
    private val placesStore: PlacesStore,
    private val platform: PlatformServices,
    private val clock: WallClock,
    private val recents: RecentSearchStore,
    initialFromPlaceId: String?,
    initialToPlaceId: String?,
    scope: CoroutineScope,
    private val searchDebounceMs: Long = 300,
    private val tickMs: Long = 30_000,
    /** A start that is not a saved place (a station tapped on the map); a saved place given for From wins. */
    initialFrom: JourneyEnd? = null,
) : StateHolder<PlanFormUiState>(scope) {
    private val form = MutableStateFlow(
        clock.nowMillis().let { PlanFormUiState(pickedMs = initialPickedTime(it), nowMs = it) },
    )
    private val query = MutableStateFlow("")

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(tickMs)
        }
    }

    override val state: StateFlow<PlanFormUiState> = combine(
        form,
        combine(placesStore.places, recents.recent) { p, r -> p to r },
        core.trips.onStart { emit(emptyList()) },
        core.dataStatus.map<DataStatus, Boolean> { !it.serverReachable }.onStart { emit(false) }.distinctUntilChanged(),
        ticker,
    ) { f, (places, recent), trips, offline, now ->
        f.copy(places = places, recent = recent, savedRoutes = trips, offline = offline, nowMs = now)
    }.stateIn(scope, SharingStarted.Eagerly, form.value)

    init {
        if (initialFromPlaceId != null || initialToPlaceId != null || initialFrom != null) {
            scope.launch {
                val places = placesStore.currentPlaces()
                val from = places.firstOrNull { it.id == initialFromPlaceId }?.let(::choiceOf)
                    ?: initialFrom?.let { EndChoice(it, "", EndIcon.STOP) }
                val to = places.firstOrNull { it.id == initialToPlaceId }?.let(::choiceOf)
                form.update { it.copy(from = it.from ?: from, to = it.to ?: to) }
            }
        }
        scope.launch {
            query
                .debounce { if (it.isBlank()) 0L else searchDebounceMs }
                .distinctUntilChanged()
                .collectLatest { q -> search(q) }
        }
    }

    private suspend fun search(q: String) {
        if (q.isBlank()) {
            form.update { it.copy(search = SearchState.Idle) }
            return
        }
        form.update { it.copy(search = SearchState.Searching) }
        val f = form.value
        val near = (if (f.editing == Field.FROM) f.to else f.from)?.end?.position
        val result = runCatching { planner.searchPlaces(q.trim(), near, limit = 8) }
            .getOrElse { PlacesResult.Failed(it.message ?: "The search failed") }
        val s = when (result) {
            is PlacesResult.Found -> if (result.places.isEmpty()) SearchState.NoResults else SearchState.Found(result.places)
            PlacesResult.Offline -> SearchState.Offline
            PlacesResult.Unavailable -> SearchState.Unavailable
            is PlacesResult.Failed -> SearchState.Failed(result.message)
        }
        form.update {
            if (it.query != q) it
            else it.copy(search = s, plannerUnavailable = it.plannerUnavailable || s == SearchState.Unavailable)
        }
    }

    /** Opens the search for [field]. */
    fun edit(field: Field) = form.update { it.copy(editing = field, query = "", search = SearchState.Idle, notice = null) }.also { query.value = "" }

    /** Closes the search without changing anything. */
    fun closeSearch() = form.update { it.copy(editing = null, query = "", search = SearchState.Idle) }.also { query.value = "" }

    fun setQuery(text: String) {
        form.update { it.copy(query = text, search = if (text.isBlank()) SearchState.Idle else SearchState.Searching) }
        query.value = text
    }

    /** Runs the current search again (after it failed). */
    fun retrySearch() {
        val q = form.value.query
        scope.launch { search(q) }
    }

    private fun set(field: Field, choice: EndChoice) {
        form.update {
            val f = if (field == Field.FROM) it.copy(from = choice) else it.copy(to = choice)
            f.copy(editing = null, query = "", search = SearchState.Idle, notice = null)
        }
        query.value = ""
    }

    /** A saved place, into the field being edited (or the first empty one, else To). */
    fun choosePlace(place: SavedPlace, field: Field = form.value.target) = set(field, choiceOf(place))

    /** A search result. */
    fun chooseResult(place: Place, field: Field = form.value.target) = set(field, choiceOf(place))

    /** WHERE I AM: asks for location permission now (only when tapped), then takes one fix. */
    fun useLocation(field: Field = form.value.target) {
        if (form.value.locating) return
        form.update { it.copy(locating = true, notice = null) }
        scope.launch {
            var permission = platform.locationPermission.value
            if (permission != PermissionState.GRANTED) permission = platform.requestLocationPermission()
            if (permission != PermissionState.GRANTED) {
                form.update { it.copy(locating = false, notice = "Location is off for TransitTracker. Pick a place or search instead.") }
                return@launch
            }
            val here = runCatching { platform.currentLocation() }.getOrNull()
            if (here == null) {
                form.update { it.copy(locating = false, notice = "Could not find where you are. Try again, or search instead.") }
                return@launch
            }
            form.update { it.copy(locating = false) }
            set(field, EndChoice(JourneyEnd(WHERE_I_AM, here), "Your location now", EndIcon.HERE))
        }
    }

    fun swap() = form.update { it.copy(from = it.to, to = it.from) }

    fun setWhen(kind: WhenKind) = form.update {
        val now = clock.nowMillis()
        val picked = if (it.pickedMs < floorToStep(now)) initialPickedTime(now) else it.pickedMs
        it.copy(whenKind = kind, pickedMs = picked)
    }

    /** Moves the LEAVE AT / ARRIVE BY time by [deltaMinutes] (five-minute steps, a day for the day stepper). */
    fun stepTime(deltaMinutes: Int) = form.update { it.copy(pickedMs = stepTime(it.pickedMs, deltaMinutes, clock.nowMillis())) }

    /** Puts a recent search back in the form. */
    fun useRecent(search: RecentSearch) {
        scope.launch {
            val places = placesStore.places.first()
            form.update { it.copy(from = choiceOf(search.from, places), to = choiceOf(search.to, places), editing = null, notice = null) }
        }
    }

    fun dismissNotice() = form.update { it.copy(notice = null) }

    /** The options page for this search (null until both ends are set); remembers the search. */
    fun find(fromOnboarding: Boolean = false): Route.JourneyOptions? {
        val s = form.value
        if (!s.canFind) return null
        val from = s.from!!.end
        val to = s.to!!.end
        recents.add(RecentSearch(from, to))
        val (timeMs, kind) = planTime(s.whenKind, s.pickedMs)
        return Route.JourneyOptions(from, to, timeMs, kind, fromOnboarding)
    }
}
