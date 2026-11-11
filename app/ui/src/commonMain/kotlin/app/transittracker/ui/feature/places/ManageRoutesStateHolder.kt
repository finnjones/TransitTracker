package app.transittracker.ui.feature.places

import app.transittracker.model.Route
import app.transittracker.model.SavedTrip
import app.transittracker.model.StopId
import app.transittracker.model.TransitCore
import app.transittracker.ui.places.PlacesStore
import app.transittracker.ui.state.StateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

/** One saved route in the manage list. */
data class RouteRow(
    val id: String,
    /** "Home", "Work" or the station names; one part for a custom name. */
    val titleParts: List<String>,
    /** "STRATHFIELD TO CENTRAL" under a title that is not the stations. */
    val stationsLine: String?,
    val lines: List<LineBadge>,
    /** Stations where the route changes (legs - 1). */
    val changes: Int,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val upWords: String,
    val downWords: String,
) {
    val spokenName: String get() = routeSpokenName(titleParts)
}

/** A folder and its routes, in order; [name] null for routes without one. */
data class FolderGroup(val name: String?, val rows: List<RouteRow>)

/** A folder being renamed: [text] is what has been typed. */
data class FolderRename(val folder: String, val text: String) {
    val canSave: Boolean get() = cleanFolder(text) != null
}

data class ManageRoutesUiState(
    val loading: Boolean = true,
    val folders: List<FolderGroup> = emptyList(),
    val renaming: FolderRename? = null,
    val problem: Problem? = null,
) {
    val count: Int get() = folders.sumOf { it.rows.size }
    val isEmpty: Boolean get() = !loading && count == 0

    /** True when some route has a folder: unfiled routes then get a heading of their own. */
    val hasFolders: Boolean get() = folders.any { it.name != null }
}

/**
 * State for Manage routes (plan 5.2, 5.6): every saved route grouped by folder, with its ends,
 * its lines, move up / down within and between folders, and folder rename. Writes go through the
 * core (`saveTrip`, `reorderTrips`); a failed write is a [Problem] with RETRY.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManageRoutesStateHolder(
    private val core: TransitCore,
    private val places: PlacesStore,
    scope: CoroutineScope,
) : StateHolder<ManageRoutesUiState>(scope) {
    private val renaming = MutableStateFlow<FolderRename?>(null)
    private val problem = MutableStateFlow<Problem?>(null)
    private val lineCache = HashMap<StopId, List<Route>>()

    /** The saved routes as last seen, for writes. */
    private var latest: List<SavedTrip> = emptyList()
    private var retryWith: (() -> Unit)? = null

    private val folders = combine(core.trips, places.places, places.tripPlaces) { t, p, l -> Triple(t, p, l) }
        .flatMapLatest { (trips, placeList, links) ->
            flow {
                latest = trips
                val byId = placeList.associateBy { it.id }
                val entries = entriesOf(trips)
                val tripsById = trips.associateBy { it.id }
                emit(
                    groupEntries(entries).map { (name, group) ->
                        FolderGroup(
                            name,
                            group.map { e ->
                                val trip = tripsById.getValue(e.id)
                                val link = links[trip.id]
                                val from = link?.fromPlaceId?.let { byId[it] }
                                val to = link?.toPlaceId?.let { byId[it] }
                                RouteRow(
                                    id = trip.id,
                                    titleParts = routeTitleParts(trip, from, to),
                                    stationsLine = routeStationsLine(trip, from, to),
                                    lines = linesOfTrip(trip),
                                    changes = (trip.legs.size - 1).coerceAtLeast(0),
                                    canMoveUp = canMove(entries, trip.id, -1),
                                    canMoveDown = canMove(entries, trip.id, 1),
                                    upWords = moveWords(entries, trip.id, -1),
                                    downWords = moveWords(entries, trip.id, 1),
                                )
                            },
                        )
                    },
                )
            }
        }

    override val state: StateFlow<ManageRoutesUiState> = combine(folders, renaming, problem) { f, r, p ->
        ManageRoutesUiState(loading = false, folders = f, renaming = r, problem = p)
    }.stateIn(scope, SharingStarted.Eagerly, ManageRoutesUiState())

    private suspend fun routesAt(stopId: StopId): List<Route> = lineCache.getOrPut(stopId) {
        runCatching { core.linesAt(stopId) }.getOrElse { if (it is CancellationException) throw it else emptyList() }
    }

    private suspend fun linesOfTrip(trip: SavedTrip): List<LineBadge> =
        lineBadges(
            trip.legs.flatMap { leg ->
                legLines(leg, leg.originStopIds.flatMap { routesAt(it) }, leg.destinationStopIds.flatMap { routesAt(it) })
            },
        )

    // ---------------------------------------------------------------- reorder

    /** Moves route [id] one step up (-1) or down (+1), crossing into the next folder at a folder's end. */
    fun move(id: String, delta: Int) {
        val before = entriesOf(latest)
        val after = moveRoute(before, id, delta)
        if (after != before) write(before, after, "Could not move the route")
    }

    // ---------------------------------------------------------------- folder rename

    fun startRename(folder: String) {
        renaming.value = FolderRename(folder, folder)
    }

    fun setRenameText(text: String) = renaming.update { it?.copy(text = text) }

    fun cancelRename() {
        renaming.value = null
    }

    fun confirmRename() {
        val r = renaming.value ?: return
        if (!r.canSave) return
        renaming.value = null
        val before = entriesOf(latest)
        val after = renameFolder(before, r.folder, r.text)
        if (after != before) write(before, after, "Could not rename the folder")
    }

    // ---------------------------------------------------------------- writing

    private fun write(before: List<RouteEntry>, after: List<RouteEntry>, title: String) {
        val attempt = {
            scope.launch {
                try {
                    val changes = folderChanges(before, after)
                    for ((id, folder) in changes) {
                        val trip = latest.firstOrNull { it.id == id } ?: continue
                        core.saveTrip(trip.copy(folder = folder))
                    }
                    val ids = after.map { it.id }
                    if (ids != before.map { it.id }) core.reorderTrips(ids)
                    retryWith = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    problem.value = Problem(title, "The change was not saved. Your routes are as they were. Try again in a moment.")
                }
            }
            Unit
        }
        retryWith = attempt
        attempt()
    }

    /** RETRY in the problem dialog. */
    fun retry() {
        val again = retryWith
        problem.value = null
        again?.invoke()
    }

    fun dismissProblem() {
        problem.value = null
        retryWith = null
    }
}
