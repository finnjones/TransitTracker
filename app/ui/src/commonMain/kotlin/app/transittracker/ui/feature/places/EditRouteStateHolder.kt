package app.transittracker.ui.feature.places

import app.transittracker.model.Route
import app.transittracker.model.SavedTrip
import app.transittracker.model.StopId
import app.transittracker.model.TransitCore
import app.transittracker.ui.format.shortStopName
import app.transittracker.ui.places.PlaceIcon
import app.transittracker.ui.places.PlacesStore
import app.transittracker.ui.places.SavedPlace
import app.transittracker.ui.state.StateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One leg of a route, read-only: where it boards, where it alights and the lines that serve it. */
data class LegRow(
    val number: Int,
    val board: String,
    val alight: String,
    val lines: List<LineBadge>,
    /** Minimum time to change onto this leg; null for the first leg. */
    val changeMinutes: Int?,
)

/** A saved place to pick as an end of the route. */
data class PlaceChoice(val id: String, val name: String, val icon: PlaceIcon)

data class EditRouteUiState(
    val loading: Boolean = true,
    /** The route is gone (deleted elsewhere). */
    val missing: Boolean = false,
    val name: String = "",
    val origin: String = "",
    val destination: String = "",
    val folder: String = "",
    /** Folders in use, to pick from. */
    val folders: List<String> = emptyList(),
    val places: List<PlaceChoice> = emptyList(),
    val fromPlaceId: String? = null,
    val toPlaceId: String? = null,
    val legs: List<LegRow> = emptyList(),
    /** Home cannot show leave-by from the chosen From place; says why. */
    val warning: String? = null,
    val saving: Boolean = false,
    val deleting: Boolean = false,
    val confirmDelete: Boolean = false,
    val problem: Problem? = null,
    val finished: Boolean = false,
) {
    /** What the title is when no name is typed: the stations. */
    val defaultName: String get() = "$origin to $destination"
    val busy: Boolean get() = saving || deleting
    val saveProblem: String? get() = routeSaveProblem(fromPlaceId, toPlaceId)
    val canSave: Boolean get() = !loading && !missing && !busy && saveProblem == null

    /** "Home to Work" when linked, else the typed name, else the stations; for dialogs. */
    val title: String get() {
        val from = places.firstOrNull { it.id == fromPlaceId }
        val to = places.firstOrNull { it.id == toPlaceId }
        return when {
            from != null || to != null -> "${from?.name ?: origin} to ${to?.name ?: destination}"
            name.isNotBlank() -> name.trim()
            else -> defaultName
        }
    }
}

/**
 * State for editing one saved route (plan 4.2 "Edit a saved route"): name, folder, the places it
 * runs between, the legs (read-only) and delete. The contract's `SavedTrip` has name and folder;
 * the places link lives in the [PlacesStore]. Saving and deleting can fail, shown as a [Problem].
 */
class EditRouteStateHolder(
    private val core: TransitCore,
    private val places: PlacesStore,
    private val tripId: String,
    scope: CoroutineScope,
) : StateHolder<EditRouteUiState>(scope) {
    private val mutable = MutableStateFlow(EditRouteUiState())
    override val state: StateFlow<EditRouteUiState> = mutable.asStateFlow()

    private var trip: SavedTrip? = null
    private var saved: List<SavedPlace> = emptyList()
    private val routeCache = HashMap<StopId, List<Route>>()

    private enum class Retry { SAVE, DELETE }

    private var pending: Retry? = null

    init {
        scope.launch { load() }
    }

    private suspend fun load() {
        val trips = runCatching { core.trips.first() }.getOrDefault(emptyList())
        val t = trips.firstOrNull { it.id == tripId }
        if (t == null) {
            mutable.update { it.copy(loading = false, missing = true) }
            return
        }
        trip = t
        saved = runCatching { places.currentPlaces() }.getOrDefault(emptyList())
        val link = runCatching { places.tripPlaces.first()[tripId] }.getOrNull()
        val from = link?.fromPlaceId?.takeIf { id -> saved.any { it.id == id } }
        val to = link?.toPlaceId?.takeIf { id -> saved.any { it.id == id } }
        mutable.value = EditRouteUiState(
            loading = false,
            name = t.customName.orEmpty(),
            origin = t.originName,
            destination = t.destinationName,
            folder = cleanFolder(t.folder).orEmpty(),
            folders = folderNames(trips),
            places = saved.map { PlaceChoice(it.id, it.name, it.icon) },
            fromPlaceId = from,
            toPlaceId = to,
            legs = legRows(t, emptyMap()),
            warning = warningFor(from),
        )
        // The names of change stations and the lines come from the timetable; fill them in when known.
        val names = changeStationNames(t)
        mutable.update { it.copy(legs = legRows(t, names, lines = linesOfLegs(t))) }
    }

    private fun warningFor(fromId: String?): String? =
        leaveByWarning(saved.firstOrNull { it.id == fromId }, trip?.legs?.firstOrNull(), trip?.originName.orEmpty())

    // ---------------------------------------------------------------- legs

    private suspend fun routesAt(stopId: StopId): List<Route> = routeCache.getOrPut(stopId) {
        runCatching { core.linesAt(stopId) }.getOrElse { if (it is CancellationException) throw it else emptyList() }
    }

    private suspend fun linesOfLegs(t: SavedTrip): List<List<LineBadge>> = t.legs.map { leg ->
        lineBadges(legLines(leg, leg.originStopIds.flatMap { routesAt(it) }, leg.destinationStopIds.flatMap { routesAt(it) }))
    }

    /** Station names of the next service: its board and alight stop of each leg, keyed by "leg:board" / "leg:alight". */
    private suspend fun changeStationNames(t: SavedTrip): Map<String, String> {
        val journey = try {
            core.upcoming(t.id, 1).first().firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return emptyMap()
        val names = HashMap<String, String>()
        for ((i, dep) in journey.legs.withIndex()) {
            val calls = try {
                core.service(dep.ref).first().calls
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            calls.firstOrNull { it.stopId == dep.originStopId }?.let { names["$i:board"] = shortStopName(it.stopName) }
            calls.firstOrNull { it.stopId == dep.destinationStopId }?.let { names["$i:alight"] = shortStopName(it.stopName) }
        }
        return names
    }

    private fun legRows(t: SavedTrip, names: Map<String, String>, lines: List<List<LineBadge>> = emptyList()): List<LegRow> =
        t.legs.mapIndexed { i, leg ->
            val board = names["$i:board"] ?: if (i == 0) t.originName else names["${i - 1}:alight"] ?: CHANGE_STATION
            val alight = names["$i:alight"] ?: if (i == t.legs.lastIndex) t.destinationName else CHANGE_STATION
            LegRow(i + 1, board, alight, lines.getOrNull(i).orEmpty(), if (i == 0) null else leg.minConnectionMinutes)
        }

    // ---------------------------------------------------------------- edits

    fun setName(text: String) = mutable.update { it.copy(name = text) }

    fun setFolder(text: String) = mutable.update { it.copy(folder = text) }

    fun setFrom(placeId: String?) = mutable.update { it.copy(fromPlaceId = placeId, warning = warningFor(placeId)) }

    fun setTo(placeId: String?) = mutable.update { it.copy(toPlaceId = placeId) }

    // ---------------------------------------------------------------- save and delete

    fun save() {
        val s = mutable.value
        val t = trip ?: return
        if (!s.canSave) return
        pending = Retry.SAVE
        mutable.update { it.copy(saving = true, problem = null) }
        scope.launch {
            var linked = false
            try {
                core.saveTrip(t.copy(customName = s.name.trim().takeIf { it.isNotEmpty() }, folder = cleanFolder(s.folder)))
                linked = true
                places.linkTrip(t.id, s.fromPlaceId, s.toPlaceId)
                mutable.update { it.copy(saving = false, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val problem = if (!linked) {
                    Problem("Could not save the route", "The route was not saved. Try again in a moment.")
                } else {
                    Problem("Could not link the places", "The route was saved, but its places were not linked. Try again in a moment.")
                }
                mutable.update { it.copy(saving = false, problem = problem) }
            }
        }
    }

    fun askDelete() = mutable.update { it.copy(confirmDelete = true) }

    fun cancelDelete() = mutable.update { it.copy(confirmDelete = false) }

    fun confirmDelete() {
        pending = Retry.DELETE
        mutable.update { it.copy(confirmDelete = false, deleting = true, problem = null) }
        scope.launch {
            try {
                core.deleteTrip(tripId)
                places.unlinkTrip(tripId)
                mutable.update { it.copy(deleting = false, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update {
                    it.copy(deleting = false, problem = Problem("Could not delete the route", "The route was not deleted. Try again in a moment."))
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
        const val CHANGE_STATION = "Change station"
    }
}
