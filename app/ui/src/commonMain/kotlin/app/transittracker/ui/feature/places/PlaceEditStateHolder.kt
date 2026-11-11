package app.transittracker.ui.feature.places

import app.transittracker.model.JourneyPlanner
import app.transittracker.model.LatLng
import app.transittracker.model.Place
import app.transittracker.model.PlacesResult
import app.transittracker.model.TransitCore
import app.transittracker.ui.places.PlaceIcon
import app.transittracker.ui.places.PlaceStop
import app.transittracker.ui.places.PlacesStore
import app.transittracker.ui.places.SavedPlace
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.platform.PlatformServices
import app.transittracker.ui.state.StateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Wait this long after the last key before asking the address lookup. */
const val SEARCH_DELAY_MS = 350L

/** How many answers the address lookup is asked for. */
const val SEARCH_LIMIT = 8

/** The address lookup as the screen shows it (the planner's own states, plan 6). */
sealed interface AddressSearch {
    /** Nothing typed yet, or an address was picked. */
    data object Idle : AddressSearch

    data object Searching : AddressSearch

    /** Addresses first ([rankPlaces]). */
    data class Results(val places: List<Place>) : AddressSearch

    /** The server could not be reached. */
    data object Offline : AddressSearch

    /** The server has no lookup right now: no key or the day's quota is used up. */
    data object Unavailable : AddressSearch

    data object NothingFound : AddressSearch

    /** The lookup went wrong; the planner's own message is not shown. */
    data object Failed : AddressSearch
}

/** A stop offered for the place, with the lines that call there. */
data class StopRow(val stop: PlaceStop, val lines: List<LineBadge>)

/** Everything the Add or edit a place screen draws (plan 5.7, Add place mockup). */
data class PlaceEditState(
    val loading: Boolean = true,
    /** Editing a place that is gone. */
    val missing: Boolean = false,
    val isNew: Boolean = true,
    val name: String = "",
    val icon: PlaceIcon = PlaceIcon.PIN,
    /** The text in the address box. */
    val query: String = "",
    /** Set once an address is picked or the phone's position is used; typing in the box clears it. */
    val position: LatLng? = null,
    val search: AddressSearch = AddressSearch.Idle,
    val locating: Boolean = false,
    val locationProblem: String? = null,
    val stops: List<StopRow> = emptyList(),
    val stopsLoading: Boolean = false,
    val stopsProblem: String? = null,
    /** The stop whose walk time is open for correction. */
    val editingWalk: String? = null,
    val saving: Boolean = false,
    val deleting: Boolean = false,
    val confirmDelete: Boolean = false,
    /** Saved routes that use this place, counted when the delete question is asked. */
    val routesUsing: Int = 0,
    val problem: Problem? = null,
    /** Saved or deleted: the screen goes back. */
    val finished: Boolean = false,
) {
    val busy: Boolean get() = saving || deleting
    val enabledStops: Int get() = stops.count { it.stop.enabled }

    /** Why SAVE PLACE is off, in a plain sentence; null when the place can be saved. */
    val saveProblem: String? get() = when {
        position != null && name.isNotBlank() && stopsLoading -> "Looking up the stops near here."
        position != null && name.isNotBlank() && stopsProblem != null -> "The stops near here could not be looked up. Try again."
        else -> placeSaveProblem(name, position != null, hasStops = stops.isNotEmpty(), enabledStops = enabledStops)
    }
    val canSave: Boolean get() = !loading && !missing && !busy && !stopsLoading && saveProblem == null
    val canDelete: Boolean get() = !isNew
}

/**
 * State for adding or editing a place (plan 5.7): name and icon, the address (searched through
 * the planner, debounced, or taken from the phone's position) and the stops within walking
 * distance, each with a walk time that can be corrected. Saving and deleting can fail; the reason
 * is a [Problem] shown as a dialog.
 */
@OptIn(ExperimentalUuidApi::class)
class PlaceEditStateHolder(
    private val core: TransitCore,
    private val planner: JourneyPlanner,
    private val places: PlacesStore,
    private val platform: PlatformServices,
    private val placeId: String?,
    scope: CoroutineScope,
    private val newId: () -> String = { Uuid.random().toString() },
    private val searchDelayMs: Long = SEARCH_DELAY_MS,
) : StateHolder<PlaceEditState>(scope) {
    private val mutable = MutableStateFlow(PlaceEditState(loading = placeId != null))
    override val state: StateFlow<PlaceEditState> = mutable.asStateFlow()

    private var searchJob: Job? = null
    private var stopsJob: Job? = null
    private var pending: Retry? = null

    /** The stops the place had when it was opened: choices about them survive a new address. */
    private var known: List<PlaceStop> = emptyList()

    private enum class Retry { SAVE, DELETE }

    init {
        if (placeId != null) scope.launch { load(placeId) }
    }

    private suspend fun load(id: String) {
        val place = runCatching { places.currentPlaces() }.getOrDefault(emptyList()).firstOrNull { it.id == id }
        if (place == null) {
            mutable.update { it.copy(loading = false, missing = true) }
            return
        }
        known = place.stops
        mutable.value = PlaceEditState(
            loading = false, isNew = false, name = place.name, icon = place.icon, query = place.address,
            position = place.position, stops = place.stops.map { StopRow(it, emptyList()) },
        )
        val lines = place.stops.associate { it.stopId to linesOf(it) }
        // The user may have changed a stop while the lines loaded: only the lines are filled in.
        mutable.update { st -> st.copy(stops = st.stops.map { r -> r.copy(lines = lines[r.stop.stopId] ?: r.lines) }) }
    }

    /** Remembers the choices made about the stops now shown, before they are replaced. */
    private fun rememberStops() {
        val now = mutable.value.stops
        if (now.isNotEmpty()) known = now.map { it.stop }
    }

    private suspend fun linesOf(stop: PlaceStop): List<LineBadge> =
        runCatching { lineBadges(core.linesAt(stop.stopId)) }.getOrElse { if (it is CancellationException) throw it else emptyList() }

    // ---------------------------------------------------------------- name and icon

    fun setName(text: String) = mutable.update { it.copy(name = text) }

    fun setIcon(icon: PlaceIcon) = mutable.update { it.copy(icon = icon) }

    // ---------------------------------------------------------------- address

    /**
     * The address box changed. The position belongs to the old text, so it and the stops are
     * dropped; after [searchDelayMs] without another key the lookup is asked (addresses first).
     */
    fun setQuery(text: String) {
        if (text == mutable.value.query) return
        searchJob?.cancel()
        stopsJob?.cancel()
        rememberStops()
        val q = text.trim()
        mutable.update {
            it.copy(
                query = text, position = null, stops = emptyList(), stopsLoading = false, stopsProblem = null,
                locationProblem = null, editingWalk = null,
                search = if (q.length >= MIN_QUERY_LENGTH) AddressSearch.Searching else AddressSearch.Idle,
            )
        }
        if (q.length >= MIN_QUERY_LENGTH) searchJob = scope.launch {
            delay(searchDelayMs)
            search(q)
        }
    }

    /** Asks the lookup again for what is in the box (after offline or a failure). */
    fun retrySearch() {
        val q = mutable.value.query.trim()
        if (q.length < MIN_QUERY_LENGTH) return
        searchJob?.cancel()
        mutable.update { it.copy(search = AddressSearch.Searching) }
        searchJob = scope.launch { search(q) }
    }

    private suspend fun search(q: String) {
        val answer = try {
            when (val r = planner.searchPlaces(q, near = mutable.value.position, limit = SEARCH_LIMIT)) {
                is PlacesResult.Found -> if (r.places.isEmpty()) AddressSearch.NothingFound else AddressSearch.Results(rankPlaces(r.places))
                PlacesResult.Offline -> AddressSearch.Offline
                PlacesResult.Unavailable -> AddressSearch.Unavailable
                is PlacesResult.Failed -> AddressSearch.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AddressSearch.Failed
        }
        // The user may have typed on while the lookup ran.
        mutable.update { if (it.query.trim() == q && it.position == null) it.copy(search = answer) else it }
    }

    /** An answer was picked: its position is the place's position and the stops near it are looked up. */
    fun pick(place: Place) {
        searchJob?.cancel()
        rememberStops()
        mutable.update {
            it.copy(
                query = addressText(place), position = place.position, search = AddressSearch.Idle,
                locationProblem = null, editingWalk = null, stops = emptyList(),
            )
        }
        loadStops(place.position, fromLocation = false)
    }

    /**
     * USE WHERE I AM NOW. The permission is asked only now, and only if it can still be asked.
     * Refused or no fix: a plain sentence, and typing an address still works.
     */
    fun useMyLocation() {
        if (mutable.value.locating) return
        searchJob?.cancel()
        scope.launch {
            mutable.update { it.copy(locating = true, locationProblem = null, search = AddressSearch.Idle) }
            var permission = platform.locationPermission.value
            val refusedBefore = permission == PermissionState.DENIED
            if (permission == PermissionState.NOT_ASKED) permission = platform.requestLocationPermission()
            if (permission != PermissionState.GRANTED) {
                val words = if (refusedBefore) {
                    "Location is off for TransitTracker. Turn it on in your phone's settings, or type an address."
                } else {
                    "Location was not allowed, so type an address instead."
                }
                mutable.update { it.copy(locating = false, locationProblem = words) }
                return@launch
            }
            val position = try {
                platform.currentLocation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (position == null) {
                mutable.update { it.copy(locating = false, locationProblem = "Could not find where you are. Try again, or type an address.") }
                return@launch
            }
            rememberStops()
            mutable.update {
                it.copy(
                    locating = false, query = HERE_ADDRESS, position = position, stops = emptyList(),
                    stopsProblem = null, editingWalk = null,
                )
            }
            loadStops(position, fromLocation = true)
        }
    }

    private fun loadStops(position: LatLng, fromLocation: Boolean) {
        stopsJob?.cancel()
        // Loading is shown at once, not when the lookup starts: no flash of "no stations".
        mutable.update { it.copy(stopsLoading = true, stopsProblem = null) }
        stopsJob = scope.launch {
            val candidates = try {
                core.stopsNear(position, STATION_RADIUS_M)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update {
                    it.copy(stopsLoading = false, stops = emptyList(), stopsProblem = "Could not look up the stations near here. Try again in a moment.")
                }
                return@launch
            }
            val found = keepChoices(walkingStops(position, candidates), known)
            val rows = found.map { StopRow(it, linesOf(it)) }
            mutable.update {
                it.copy(
                    stopsLoading = false, stops = rows,
                    // Where-I-am has no address text of its own: name it after the nearest stop.
                    query = if (fromLocation && it.query == HERE_ADDRESS && found.isNotEmpty()) "Near ${found.first().name}" else it.query,
                )
            }
        }
    }

    /** Looks the stops up again for the current position (after a failure). */
    fun retryStops() {
        mutable.value.position?.let { loadStops(it, fromLocation = false) }
    }

    // ---------------------------------------------------------------- stops

    fun setStopEnabled(stopId: String, enabled: Boolean) = changeStop(stopId) { it.copy(enabled = enabled) }

    /** Opens (or closes, if already open) the walk time of [stopId] for correction. */
    fun toggleWalk(stopId: String) = mutable.update { it.copy(editingWalk = if (it.editingWalk == stopId) null else stopId) }

    fun closeWalk() = mutable.update { it.copy(editingWalk = null) }

    /** Corrects the walk time of [stopId] by [delta] minutes; it is then the user's figure, not an estimate. */
    fun stepWalk(stopId: String, delta: Int) = changeStop(stopId) { s ->
        val next = steppedWalk(s.walkMinutes, delta) ?: return@changeStop s
        s.copy(walkMinutes = next, estimated = false)
    }

    private fun changeStop(stopId: String, change: (PlaceStop) -> PlaceStop) {
        mutable.update { st ->
            st.copy(stops = st.stops.map { if (it.stop.stopId == stopId) it.copy(stop = change(it.stop)) else it })
        }
    }

    // ---------------------------------------------------------------- save and delete

    fun save() {
        val s = mutable.value
        val position = s.position
        if (!s.canSave || position == null) return
        pending = Retry.SAVE
        mutable.update { it.copy(saving = true, problem = null) }
        scope.launch {
            val place = SavedPlace(
                id = placeId ?: newId(), name = s.name.trim(), icon = s.icon, address = s.query.trim(),
                position = position, stops = s.stops.map { it.stop },
            )
            try {
                places.savePlace(place)
                mutable.update { it.copy(saving = false, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update {
                    it.copy(saving = false, problem = Problem("Could not save the place", "The place was not saved. Try again in a moment."))
                }
            }
        }
    }

    /** Asks "delete this place?", counting the saved routes that use it first. */
    fun askDelete() {
        if (placeId == null) return
        scope.launch {
            val links = runCatching { places.tripPlaces.first().values }.getOrDefault(emptyList())
            mutable.update { it.copy(confirmDelete = true, routesUsing = routesUsingPlace(placeId, links)) }
        }
    }

    fun cancelDelete() = mutable.update { it.copy(confirmDelete = false) }

    fun confirmDelete() {
        if (placeId == null) return
        pending = Retry.DELETE
        mutable.update { it.copy(confirmDelete = false, deleting = true, problem = null) }
        scope.launch {
            try {
                places.deletePlace(placeId)
                mutable.update { it.copy(deleting = false, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update {
                    it.copy(deleting = false, problem = Problem("Could not delete the place", "The place was not deleted. Try again in a moment."))
                }
            }
        }
    }

    /** RETRY in the problem dialog. */
    fun retry() {
        val what = pending
        mutable.update { it.copy(problem = null) }
        when (what) {
            Retry.SAVE -> save()
            Retry.DELETE -> confirmDelete()
            null -> Unit
        }
    }

    fun dismissProblem() = mutable.update { it.copy(problem = null) }

    private companion object {
        /** Stands in the address box until the nearest stop is known. */
        const val HERE_ADDRESS = "Where I am now"
    }
}
