package app.transittracker.ui.feature.map

import app.transittracker.model.DataStatus
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Mode
import app.transittracker.model.Route
import app.transittracker.model.RouteId
import app.transittracker.model.ServiceDetail
import app.transittracker.model.ServiceRef
import app.transittracker.model.Stop
import app.transittracker.model.StopId
import app.transittracker.model.VehiclePosition
import app.transittracker.ui.format.DefaultLineColor
import app.transittracker.ui.format.StatusPresentation
import app.transittracker.ui.format.parseLineColor
import app.transittracker.ui.format.shortStopName
import app.transittracker.ui.format.statusPresentation
import app.transittracker.ui.places.SavedPlace
import app.transittracker.ui.sprites.SpriteLook
import app.transittracker.ui.theme.ColorRole

/** A line of a saved trip, as the departures describe it. */
data class LineInfo(val routeId: RouteId, val name: String, val color: String?, val textColor: String?, val mode: Mode)

/** NEAR ME (plan 5.5): location is asked for only when the button is tapped. */
sealed interface NearMe {
    data object Idle : NearMe
    data object Locating : NearMe
    data class Shown(val you: MapPoint, val stops: List<Stop>) : NearMe
    /** Permission refused: only the system settings can change it. */
    data object Refused : NearMe
    /** Permission given but no position came back. */
    data object NoFix : NearMe
}

/** What is selected on the map: a vehicle (its card) or a station (its card). */
sealed interface MapSelection {
    data class Vehicle(val id: String) : MapSelection
    data class Station(val name: String, val stopId: StopId, val point: MapPoint, val lines: List<Route>? = null) : MapSelection
}

/** One live vehicle with what is known about its service. */
data class TrackedVehicle(
    val position: VehiclePosition,
    val detail: ServiceDetail? = null,
    val geometry: ServiceGeometry? = null,
    val reckoning: Reckoning? = null,
)

/** The small card for a tapped vehicle. */
data class VehicleCard(
    val id: String,
    val line: LineInfo?,
    val headsign: String?,
    /** Null until the service's details have loaded. */
    val status: StatusPresentation?,
    val ref: ServiceRef?,
    val boardStopId: StopId? = null,
)

/** The card for a tapped station. */
data class StationCard(
    val name: String,
    val stopId: StopId,
    /** Null while loading. */
    val lines: List<Route>?,
    /** A saved place that uses this station (Plan a journey starts there). */
    val placeId: String?,
)

data class MapUiState(
    val loading: Boolean = true,
    val noTrips: Boolean = false,
    val scene: MapScene = MapScene(),
    val chip: TrackChip? = null,
    val banner: String? = null,
    val bannerRole: ColorRole = ColorRole.SECONDARY,
    val vehicleCard: VehicleCard? = null,
    val stationCard: StationCard? = null,
    val nearMe: NearMe = NearMe.Idle,
    val fitKey: Any? = null,
    val description: String = "",
    /** Vehicles for the screen reader's custom actions: id and words. */
    val vehicleWords: List<Pair<String, String>> = emptyList(),
    val liveCount: Int = 0,
)

/** Inputs to [buildMapState]. */
data class MapInputs(
    val tripCount: Int?,
    val lines: Map<RouteId, LineInfo>,
    val geometry: List<LineGeometry>,
    val vehicles: List<TrackedVehicle>,
    val dataStatus: DataStatus?,
    val nearMe: NearMe,
    val selection: MapSelection?,
    val places: List<SavedPlace>,
)

/** Vehicles whose position may be drawn: fresh, on a known line, not cancelled. */
fun drawableVehicles(vehicles: List<TrackedVehicle>, nowMs: Long): List<TrackedVehicle> =
    vehicles.filter { v ->
        positionFresh(v.position, nowMs) && v.detail?.status != DepartureStatus.CANCELLED
    }

/**
 * Everything the Map tab draws (pure): the saved trips' lines, their live vehicles (none when
 * offline; faded when stale), stations, NEAR ME, the selected card, the banner and chip.
 */
fun buildMapState(i: MapInputs, nowMs: Long): MapUiState {
    if (i.tripCount == null) return MapUiState(loading = true)
    val status = i.dataStatus
    val offline = status?.serverReachable == false
    val stale = status?.liveStale == true
    val noTrips = i.tripCount == 0

    val lines = i.geometry.map { g ->
        SceneLine(g.routeId, parseLineColor(i.lines[g.routeId]?.color) ?: DefaultLineColor, g.segments)
    }
    val selectedStation = (i.selection as? MapSelection.Station)
    val nearStops = (i.nearMe as? NearMe.Shown)?.stops.orEmpty()
    val nearNames = nearStops.map { shortStopName(it.name) }.toSet()
    val stations = LinkedHashMap<String, SceneStation>()
    for (g in i.geometry) for (s in g.stations) {
        if (s.name in stations) continue
        stations[s.name] = SceneStation(
            id = "st-${s.name}", name = s.name, point = s.point,
            kind = if (s.name in nearNames) StationKind.NEARBY else StationKind.PLAIN,
            priority = if (s.name in nearNames) 3 else 10, stopId = s.stopId,
            selected = selectedStation?.name == s.name,
        )
    }
    for (s in nearStops) {
        val name = shortStopName(s.name)
        if (name in stations) continue
        stations[name] = SceneStation(
            id = "st-$name", name = name, point = MapProjection.Sydney.project(s.position), kind = StationKind.NEARBY,
            priority = 3, stopId = s.id, selected = selectedStation?.name == name,
        )
    }

    val shown = if (offline) emptyList() else drawableVehicles(i.vehicles, nowMs)
    val sceneVehicles = shown.mapNotNull { v ->
        val routeId = v.position.routeId ?: v.detail?.route?.id
        val info = routeId?.let { i.lines[it] }
        val mode = v.detail?.route?.mode ?: info?.mode ?: Mode.OTHER
        val color = parseLineColor(v.detail?.route?.color ?: info?.color) ?: DefaultLineColor
        val motion = when {
            v.geometry != null && v.reckoning != null -> VehicleMotion.Reckoned(v.geometry, v.reckoning)
            else -> VehicleMotion.Free(MapProjection.Sydney.project(v.position.position), ((v.position.bearing ?: 180f).toDouble() + 180.0) % 360.0)
        }
        SceneVehicle(
            v.position.vehicleId, mode, color, motion,
            look = if (stale || v.detail?.stale == true) SpriteLook.FADED else SpriteLook.NORMAL,
            cars = 1, routeId = routeId,
            selected = (i.selection as? MapSelection.Vehicle)?.id == v.position.vehicleId,
        )
    }

    val you = (i.nearMe as? NearMe.Shown)?.you
    val focus = if (you != null) {
        MapBox.around(listOf(you) + nearStops.map { MapProjection.Sydney.project(it.position) })?.grow(500.0)
    } else null
    val scene = MapScene(lines, stations.values.toList(), sceneVehicles, you, focus, lineWidthDots = 3)

    val vehicleCard = (i.selection as? MapSelection.Vehicle)?.let { sel ->
        val v = shown.firstOrNull { it.position.vehicleId == sel.id } ?: return@let null
        vehicleCardOf(v, i.lines)
    }
    val stationCard = selectedStation?.let { s ->
        StationCard(s.name, s.stopId, s.lines, i.places.firstOrNull { p -> p.stops.any { it.stopId == s.stopId } }?.id)
    }

    val (banner, role) = when {
        noTrips -> null to ColorRole.SECONDARY
        offline -> "No live data. Showing the timetable." to ColorRole.INFO
        stale -> "Live data is delayed. Showing the last update." to ColorRole.SECONDARY
        i.nearMe == NearMe.Refused -> "Location is off for this app. Turn it on in Settings to see stations near you." to ColorRole.CAUTION
        i.nearMe == NearMe.NoFix -> "Could not find where you are. Try again outside or in a moment." to ColorRole.CAUTION
        i.geometry.isEmpty() && i.lines.isNotEmpty() -> "Line positions are not available yet." to ColorRole.INFO
        shown.isEmpty() && i.lines.isNotEmpty() -> "No live vehicles on your lines right now." to ColorRole.SECONDARY
        else -> null to ColorRole.SECONDARY
    }
    val chip = when {
        status == null -> null
        offline -> TrackChip.Offline
        stale -> TrackChip.Stale
        else -> TrackChip.Live(status.lastLiveUpdateMs?.let { (nowMs - it).coerceAtLeast(0) })
    }
    val words = shown.map { v ->
        val card = vehicleCardOf(v, i.lines)
        v.position.vehicleId to listOfNotNull(card.line?.name, card.headsign?.let { "to $it" }, card.status?.spoken).joinToString(", ")
    }
    val lineNames = i.lines.values.joinToString(", ") { it.name }
    val description = when {
        noTrips -> "Map. No saved trips, so no lines to show."
        else -> "Line map of $lineNames. ${shown.size} live " + (if (shown.size == 1) "vehicle" else "vehicles") + "." +
            (if (nearStops.isNotEmpty()) " Stations near you: " + nearStops.joinToString(", ") { shortStopName(it.name) } + "." else "")
    }
    return MapUiState(
        loading = false,
        noTrips = noTrips,
        scene = scene,
        chip = chip,
        banner = banner,
        bannerRole = role,
        vehicleCard = vehicleCard,
        stationCard = stationCard,
        nearMe = i.nearMe,
        fitKey = listOf(i.geometry.map { it.routeId }, you),
        description = description,
        vehicleWords = words,
        liveCount = shown.size,
    )
}

private fun vehicleCardOf(v: TrackedVehicle, lines: Map<RouteId, LineInfo>): VehicleCard {
    val routeId = v.position.routeId ?: v.detail?.route?.id
    val d = v.detail
    val info = routeId?.let { lines[it] } ?: d?.route?.let { LineInfo(it.id, it.shortName, it.color, it.textColor, it.mode) }
    val next = d?.calls?.firstOrNull { !it.passed && !it.skipped }
    val status = d?.let { statusPresentation(it.status, delayAt(next), it.stale, it.dataAgeMs) }
    val ref = v.position.tripId?.let { trip -> v.position.startDate?.let { ServiceRef(trip, it) } } ?: d?.ref
    return VehicleCard(v.position.vehicleId, info, d?.headsign ?: v.position.label, status, ref)
}
