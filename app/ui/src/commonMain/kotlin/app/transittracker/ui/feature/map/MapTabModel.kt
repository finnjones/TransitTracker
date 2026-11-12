package app.transittracker.ui.feature.map

import app.transittracker.model.LatLng
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
    /** Where the station is, so Plan a journey can start from it when no saved place does. */
    val position: LatLng? = null,
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

/**
 * A stretch of a line that a saved trip rides: the line's stretches between stations from where
 * the leg is boarded to where it is left (keys as [segmentKey]), and those two stations.
 */
data class RiddenStretch(val routeId: RouteId, val segmentKeys: Set<String>, val from: String, val to: String)

/** Past this zoom the Map tab draws vehicles as tokens ([MapScene.compactVehiclesAboveMpd]). */
const val MAP_TAB_TOKENS_ABOVE_MPD = 15.0

/** A vehicle this far from your stretches is drawn faded: it is on the line, not on your way. */
private const val OFF_STRETCH_M = 300.0

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
    /** The stretches the saved trips ride; empty until known (every line is then drawn whole). */
    val ridden: List<RiddenStretch> = emptyList(),
)

/** Vehicles whose position may be drawn: fresh, on a known line, not cancelled. */
fun drawableVehicles(vehicles: List<TrackedVehicle>, nowMs: Long): List<TrackedVehicle> =
    vehicles.filter { v ->
        positionFresh(v.position, nowMs) && v.detail?.status != DepartureStatus.CANCELLED
    }

/**
 * Everything the Map tab draws (pure): the saved trips' lines, their live vehicles (none when
 * offline; faded when stale), stations, NEAR ME, the selected card, the banner and chip.
 *
 * The stretches your trips ride ([MapInputs.ridden]) are the map: drawn full width with the
 * route's badge, named at their ends (where you board, change and get off), and what the map
 * fits. The rest of each line is thin and dim context, its stations notches named only when
 * zoomed in, and vehicles away from your stretches are faded.
 */
fun buildMapState(i: MapInputs, nowMs: Long): MapUiState {
    if (i.tripCount == null) return MapUiState(loading = true)
    val status = i.dataStatus
    val offline = status?.serverReachable == false
    val stale = status?.liveStale == true
    val noTrips = i.tripCount == 0

    val mine: Map<RouteId, Set<String>> = i.ridden.groupBy { it.routeId }.mapValues { (_, s) -> s.flatMap { it.segmentKeys }.toSet() }
    val focusLine = i.ridden.isNotEmpty()
    val yours = ArrayList<LineSegment>()
    val lines = i.geometry.flatMap { g ->
        val info = i.lines[g.routeId]
        val color = parseLineColor(info?.color) ?: DefaultLineColor
        val keys = mine[g.routeId]
        if (keys == null) return@flatMap listOf(SceneLine(g.routeId, color, g.segments))
        val (ridden, rest) = g.segments.partition { it.key in keys }
        yours += ridden
        listOfNotNull(
            rest.takeIf { it.isNotEmpty() }?.let { SceneLine("${g.routeId}~rest", color, it, muted = true) },
            ridden.takeIf { it.isNotEmpty() }?.let {
                SceneLine(g.routeId, color, it, badge = info?.name?.takeIf { n -> n.isNotEmpty() }, badgeTextColor = parseLineColor(info?.textColor))
            },
        )
    }
    // Stations where you board, change and get off are marked and named; the rest are notches.
    val ends = i.ridden.flatMap { listOf(it.from, it.to) }.toSet()
    val onYours = mine.values.flatten().flatMap { it.split('|') }.toSet()
    val selectedStation = (i.selection as? MapSelection.Station)
    val nearStops = (i.nearMe as? NearMe.Shown)?.stops.orEmpty()
    val nearNames = nearStops.map { shortStopName(it.name) }.toSet()
    val stations = LinkedHashMap<String, SceneStation>()
    for (g in i.geometry) for (s in g.stations) {
        if (s.name in stations) continue
        val near = s.name in nearNames
        val selected = selectedStation?.name == s.name
        val minor = focusLine && !near && !selected && s.name !in ends
        stations[s.name] = SceneStation(
            id = "st-${s.name}", name = s.name, point = s.point,
            kind = if (near) StationKind.NEARBY else StationKind.PLAIN,
            priority = when {
                near -> 3
                !minor && focusLine -> 2
                !minor -> 10
                s.name in onYours -> 15
                else -> 30
            },
            stopId = s.stopId, selected = selected, minor = minor,
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
        val here = MapProjection.Sydney.project(v.position.position)
        val away = focusLine && routeId != null && routeId in mine && yours.none { it.path.snap(here).offset <= OFF_STRETCH_M }
        SceneVehicle(
            v.position.vehicleId, mode, color, motion,
            look = if (stale || v.detail?.stale == true || away) SpriteLook.FADED else SpriteLook.NORMAL,
            cars = 1, routeId = routeId,
            selected = (i.selection as? MapSelection.Vehicle)?.id == v.position.vehicleId,
        )
    }

    val you = (i.nearMe as? NearMe.Shown)?.you
    val focus = if (you != null) {
        MapBox.around(listOf(you) + nearStops.map { MapProjection.Sydney.project(it.position) })?.grow(500.0)
    } else {
        // Your stretches, not the whole of every line they are on.
        MapBox.around(yours.flatMap { it.path.points })?.grow(400.0)
    }
    val scene = MapScene(
        lines, stations.values.toList(), sceneVehicles, you, focus, lineWidthDots = 4,
        compactVehiclesAboveMpd = MAP_TAB_TOKENS_ABOVE_MPD,
    )

    val vehicleCard = (i.selection as? MapSelection.Vehicle)?.let { sel ->
        val v = shown.firstOrNull { it.position.vehicleId == sel.id } ?: return@let null
        vehicleCardOf(v, i.lines)
    }
    val stationCard = selectedStation?.let { s ->
        StationCard(
            s.name, s.stopId, s.lines, i.places.firstOrNull { p -> p.stops.any { it.stopId == s.stopId } }?.id,
            MapProjection.Sydney.unproject(s.point),
        )
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
        fitKey = listOf(i.geometry.map { it.routeId }, you, i.ridden.map { it.routeId to it.from }),
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
