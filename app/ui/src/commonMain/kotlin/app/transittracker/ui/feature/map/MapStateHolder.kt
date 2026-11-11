package app.transittracker.ui.feature.map

import app.transittracker.model.DataStatus
import app.transittracker.model.Departure
import app.transittracker.model.LatLng
import app.transittracker.model.RouteId
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceDetail
import app.transittracker.model.ServiceRef
import app.transittracker.model.TransitCore
import app.transittracker.model.VehiclePosition
import app.transittracker.model.WallClock
import app.transittracker.ui.places.PlacesStore
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.platform.PlatformServices
import app.transittracker.ui.state.StateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the Map tab (plan 5.5): the lines of the saved trips (from their next departures),
 * every live vehicle on those lines (`core.vehicles`), NEAR ME and the tapped vehicle or station.
 *
 * Each vehicle's service is read once (`service(ref).first()`, refreshed after
 * [detailMaxAgeMs]) for its headsign, status and the times its dead reckoning uses; the
 * reckoning is kept between reports so vehicles never run backwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapStateHolder(
    private val core: TransitCore,
    private val geometry: MapGeometrySource,
    private val platform: PlatformServices,
    places: PlacesStore,
    private val clock: WallClock,
    scope: CoroutineScope,
    private val detailMaxAgeMs: Long = 60_000,
    /** The live vehicles of some lines; `core.vehicles` (screenshots pass a re-labelled feed). */
    private val vehicleFeed: (Set<RouteId>) -> Flow<List<VehiclePosition>> = core::vehicles,
) : StateHolder<MapUiState>(scope) {
    private val nearMe = MutableStateFlow<NearMe>(NearMe.Idle)
    private val selection = MutableStateFlow<MapSelection?>(null)
    private val lineGeometry = MutableStateFlow<List<LineGeometry>>(emptyList())
    private val details = MutableStateFlow<Map<ServiceRef, Pair<ServiceDetail, ServiceGeometry>>>(emptyMap())
    private val fetchedAt = HashMap<ServiceRef, Long>()
    private val reckonings = HashMap<String, Pair<Pair<Long, LatLng>, Reckoning>>()
    private var linesJob: Job? = null
    private var linesKey: Any? = null

    private val trips: Flow<List<SavedTrip>?> = core.trips.map<List<SavedTrip>, List<SavedTrip>?> { it }.onStart { emit(null) }

    /** The departures of every saved trip (all legs), for the lines and sample services. */
    private val departures: Flow<List<Departure>> = core.trips.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList())
        else combine(list.map { t -> core.upcoming(t.id, 3).onStart { emit(emptyList()) } }) { all ->
            all.flatMap { js -> js.flatMap { it.legs } }
        }
    }.distinctUntilChanged()

    private val lineInfo: Flow<Map<RouteId, LineInfo>> = departures.map { deps ->
        deps.groupBy { it.routeId }.mapValues { (_, ds) ->
            val d = ds.first()
            LineInfo(d.routeId, d.routeName, d.routeColor, null, d.mode)
        }
    }.distinctUntilChanged()

    private val vehicles: Flow<List<VehiclePosition>> = lineInfo.map { it.keys }.distinctUntilChanged().flatMapLatest { ids ->
        if (ids.isEmpty()) flowOf(emptyList()) else vehicleFeed(ids).onStart { emit(emptyList()) }
    }

    private val tracked: Flow<List<TrackedVehicle>> = combine(vehicles, details) { list, known ->
        val now = clock.nowMillis()
        list.map { v ->
            val ref = refOf(v)
            if (ref != null) fetchDetail(ref, now)
            val dg = ref?.let { known[it] }
            var r: Reckoning? = null
            if (dg != null && positionFresh(v, now)) {
                val key = v.timestampMs to v.position
                val prev = reckonings[v.vehicleId]
                r = if (prev != null && prev.first == key) prev.second
                else reckon(v, dg.first.calls, dg.second, now, prev?.second).also { reckonings[v.vehicleId] = key to it }
            }
            TrackedVehicle(v, dg?.first, dg?.second, r)
        }
    }

    override val state: StateFlow<MapUiState> = combine(
        combine(trips, lineInfo.onStart { emit(emptyMap()) }, departures.onStart { emit(emptyList()) }) { t, l, d -> Triple(t, l, d) },
        lineGeometry,
        tracked.onStart { emit(emptyList()) },
        core.dataStatus.map<DataStatus, DataStatus?> { it }.onStart { emit(null) },
        combine(nearMe, selection, places.places) { n, s, p -> Triple(n, s, p) },
    ) { (t, lines, deps), geo, vs, status, (near, sel, places) ->
        loadLines(deps)
        buildMapState(MapInputs(t?.size, lines, geo, vs, status, near, sel, places), clock.nowMillis())
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, MapUiState())

    private fun refOf(v: VehiclePosition): ServiceRef? {
        val trip = v.tripId ?: return null
        val date = v.startDate ?: return null
        return ServiceRef(trip, date)
    }

    private fun fetchDetail(ref: ServiceRef, now: Long) {
        val at = fetchedAt[ref]
        if (at != null && now - at < detailMaxAgeMs) return
        fetchedAt[ref] = now
        scope.launch {
            runCatching {
                val d = core.service(ref).first()
                val g = geometry.service(d)
                details.update { it + (ref to (d to g)) }
            }
        }
    }

    /** Draws the lines from two sample services per line (both directions where the trips have them). */
    private fun loadLines(deps: List<Departure>) {
        val samples = deps.groupBy { it.routeId }.values.flatMap { ds -> ds.distinctBy { it.headsign }.take(2) }.map { it.ref }
        val key = samples.toSet()
        if (key == linesKey || samples.isEmpty()) return
        linesKey = key
        linesJob?.cancel()
        linesJob = scope.launch {
            runCatching {
                val ds = samples.map { core.service(it).first() }
                lineGeometry.value = geometry.lines(ds)
            }
        }
    }

    // ---- events

    fun selectVehicle(id: String) {
        selection.value = MapSelection.Vehicle(id)
    }

    fun selectStation(name: String, stopId: String, point: MapPoint) {
        selection.value = MapSelection.Station(name, stopId, point)
        scope.launch {
            val lines = runCatching { core.linesAt(stopId) }.getOrDefault(emptyList())
            selection.update { s -> if (s is MapSelection.Station && s.stopId == stopId) s.copy(lines = lines) else s }
        }
    }

    fun clearSelection() {
        selection.value = null
    }

    /** NEAR ME: asks for location only now (plan 5.5), then shows stations within 1 km. */
    fun nearMe() {
        if (nearMe.value is NearMe.Shown) {
            nearMe.value = NearMe.Idle
            return
        }
        scope.launch {
            var perm = platform.locationPermission.value
            if (perm == PermissionState.NOT_ASKED) perm = platform.requestLocationPermission()
            if (perm != PermissionState.GRANTED) {
                nearMe.value = NearMe.Refused
                return@launch
            }
            nearMe.value = NearMe.Locating
            val here = platform.currentLocation()
            if (here == null) {
                nearMe.value = NearMe.NoFix
                return@launch
            }
            val stops = runCatching { core.stopsNear(here, 1_000.0) }.getOrDefault(emptyList())
            nearMe.value = NearMe.Shown(MapProjection.Sydney.project(here), stops)
        }
    }
}
