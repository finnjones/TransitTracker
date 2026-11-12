package app.transittracker.ui.feature.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import app.transittracker.model.Mode
import app.transittracker.model.StopCall
import app.transittracker.model.StopId
import app.transittracker.ui.sprites.SpriteLook
import app.transittracker.ui.theme.ColorRole

/**
 * Everything [PixelMap] draws, on the map plane. Built by the screens' state holders; the map
 * only projects, rasterises and places labels.
 */
@Immutable
data class MapScene(
    val lines: List<SceneLine> = emptyList(),
    val stations: List<SceneStation> = emptyList(),
    val vehicles: List<SceneVehicle> = emptyList(),
    /** The user's position (NEAR ME). */
    val you: MapPoint? = null,
    /** What RECENTRE shows; null fits everything. */
    val focus: MapBox? = null,
    /** Line width in dots. */
    val lineWidthDots: Int = 4,
    /** The positions are invented (schematic fallback): the map says "NOT TO SCALE". */
    val schematic: Boolean = false,
) {
    /** Everything on the map, for fitting and for keeping the camera in reach. */
    val bounds: MapBox? by lazy {
        val pts = lines.flatMap { l -> l.segments.flatMap { it.path.points } } + stations.map { it.point } + listOfNotNull(you)
        MapBox.around(pts)
    }

    /** Line ids by the stretch of track they share (lanes). */
    val lanes: Map<Pair<String, String>, Double> by lazy {
        val shared = HashMap<String, MutableSet<String>>()
        for (l in lines) for (s in l.segments) shared.getOrPut(s.key) { LinkedHashSet() } += l.id
        assignLanes(shared, lines.map { it.id })
    }
}

/**
 * A line: [segments] are its stretches of track. [travelledBy] names the vehicle whose position
 * splits the line into travelled (dimmed) and ahead (tracking: one segment, the service's path).
 */
@Immutable
data class SceneLine(
    val id: String,
    val color: Color,
    val segments: List<LineSegment>,
    val travelledBy: String? = null,
    /** A walk between two rides: drawn dashed in the text colour, not [color]. */
    val walk: Boolean = false,
    /**
     * The route's name ("T2", "333") drawn as a badge on the line, so rides of the same colour
     * (Sydney's trains and buses are both blue) can be told apart and matched to the list.
     */
    val badge: String? = null,
    /** The feed's text colour for [badge]; null picks by contrast. */
    val badgeTextColor: Color? = null,
    /** Ridden already (a finished ride of the journey): drawn dimmed end to end. */
    val done: Boolean = false,
)

enum class StationKind {
    /** Already passed by the tracked vehicle: small, dim. */
    PASSED,
    /** Still ahead: outlined. */
    AHEAD,
    /** Where you get on: large, filled, blinking, with a BOARD HERE flag. */
    BOARD,
    /** Where you get off: large, filled, with a GET OFF flag. */
    ALIGHT,
    /** Where you change between two rides: large, filled (not blinking), with a CHANGE flag. */
    CHANGE,
    /** A station on the Map tab. */
    PLAIN,
    /** A station near you (NEAR ME). */
    NEARBY,
}

@Immutable
data class SceneStation(
    val id: String,
    val name: String,
    val point: MapPoint,
    val kind: StationKind,
    /** A second line under the name (an expected time), in [detailRole]'s colour. */
    val detail: String? = null,
    val detailRole: ColorRole = ColorRole.SECONDARY,
    /** Lower is placed first; labels that do not fit are dropped. */
    val priority: Int = 10,
    val stopId: StopId? = null,
    val selected: Boolean = false,
    /** Words for "skips this stop" and similar, drawn struck through. */
    val skipped: Boolean = false,
    /**
     * A stop the ride only passes through: a small notch in the line, its name shown only once
     * the map is zoomed in close ([MINOR_LABEL_MAX_MPD]), so a bus with forty stops stays a line.
     */
    val minor: Boolean = false,
    /** The words on the flag ("BOARD T2 · 07:26"); null uses the kind's own (BOARD HERE, GET OFF). */
    val flag: String? = null,
    /** The flag's colour role ([ColorRole.CAUTION] for a tight change); null draws it in Text. */
    val flagRole: ColorRole? = null,
)

/** Names of [SceneStation.minor] stops are drawn only at this zoom (metres per dot) or closer. */
const val MINOR_LABEL_MAX_MPD = 12.0

/** How a vehicle's position is worked out at draw time. */
sealed interface VehicleMotion {
    /** Live: dead reckoning along [geometry]'s path from the last report. */
    data class Reckoned(val geometry: ServiceGeometry, val reckoning: Reckoning) : VehicleMotion

    /** No usable position: the ghost at the timetabled place. */
    data class Timetabled(val geometry: ServiceGeometry, val calls: List<StopCall>) : VehicleMotion

    /** Standing at [distance] along the path (arrived, cancelled). */
    data class Parked(val geometry: ServiceGeometry, val distance: Double) : VehicleMotion

    /** A position off any known path (no service geometry): drawn where reported. */
    data class Free(val point: MapPoint, val headingDeg: Double) : VehicleMotion
}

@Immutable
data class SceneVehicle(
    val id: String,
    val mode: Mode,
    val color: Color,
    val motion: VehicleMotion,
    val look: SpriteLook = SpriteLook.NORMAL,
    /** Cars drawn for trains (the tracking map draws three, the overview one). */
    val cars: Int = 3,
    /** "YOUR TRAIN", next to the vehicle, in [captionRole]'s colour. */
    val caption: String? = null,
    val captionRole: ColorRole = ColorRole.CAUTION,
    /** The line it runs on (for lanes of shared track). */
    val routeId: String? = null,
    val cancelled: Boolean = false,
    /** Drawn with corner brackets (its card is open). */
    val selected: Boolean = false,
)

/** Where a vehicle is drawn at one moment. */
data class VehicleSpot(val point: MapPoint, val headingDeg: Double, val distance: Double?, val geometry: ServiceGeometry?)

/** The vehicle's place at [nowMs]; null when it has none (no times, no position). */
fun SceneVehicle.spotAt(nowMs: Long, reduceMotion: Boolean): VehicleSpot? = when (val m = motion) {
    is VehicleMotion.Reckoned -> {
        val d = m.reckoning.distanceAt(nowMs, reduceMotion)
        VehicleSpot(m.geometry.path.pointAt(d), m.geometry.path.headingAt(d), d, m.geometry)
    }
    is VehicleMotion.Timetabled -> timetabledDistance(m.calls, m.geometry, nowMs)?.let { d ->
        VehicleSpot(m.geometry.path.pointAt(d), m.geometry.path.headingAt(d), d, m.geometry)
    }
    is VehicleMotion.Parked -> VehicleSpot(m.geometry.path.pointAt(m.distance), m.geometry.path.headingAt(m.distance), m.distance, m.geometry)
    is VehicleMotion.Free -> VehicleSpot(m.point, m.headingDeg, null, null)
}
