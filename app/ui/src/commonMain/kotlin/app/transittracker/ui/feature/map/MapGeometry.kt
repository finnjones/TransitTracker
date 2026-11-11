package app.transittracker.ui.feature.map

import app.transittracker.model.LatLng
import app.transittracker.model.RouteId
import app.transittracker.model.ServiceDetail
import app.transittracker.model.Stop
import app.transittracker.model.StopCall
import app.transittracker.model.StopId
import app.transittracker.model.TransitCore
import app.transittracker.ui.format.shortStopName
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where a service runs on the map plane.
 *
 * [path] goes through every call in order; [stopDistances] is each call's distance along it
 * (same indices as the service's `calls`). [schematic] means the positions are invented (stops
 * evenly spaced, not to scale) because the real ones could not be found.
 */
data class ServiceGeometry(
    val path: MapPath,
    val stopPoints: List<MapPoint>,
    val stopDistances: List<Double>,
    /** The station each call was matched to (for "lines at this station"); null when not found. */
    val stations: List<Stop?>,
    /** Station names, short ("Strathfield"), one per call; also the keys of shared track. */
    val names: List<String>,
    /** The calls' own stop ids (platforms). */
    val stopIds: List<StopId>,
    val schematic: Boolean,
) {
    /** Key of the track between call [i] and call [i] + 1. */
    fun segmentKeyAt(i: Int): String = segmentKey(names[i], names[(i + 1).coerceAtMost(names.lastIndex)])

    /** Index of the call before [distance] along the path (the segment the distance lies on). */
    fun callIndexAt(distance: Double): Int {
        var i = 0
        while (i + 1 < stopDistances.size && stopDistances[i + 1] <= distance) i++
        return i
    }
}

/** One line on the Map tab: every stretch of track its services run on. */
data class LineGeometry(
    val routeId: RouteId,
    /** One polyline per stretch between two stations, keyed by [segmentKey]. */
    val segments: List<LineSegment>,
    /** Stations on the line, by name. */
    val stations: List<LineStation>,
)

data class LineSegment(val key: String, val path: MapPath)

data class LineStation(val name: String, val point: MapPoint, val stop: Stop?, val stopId: StopId)

/**
 * The seam between the map and the data it needs: "the path and stop positions for this
 * service" and "the paths for these lines". The contract does not carry either yet
 * (`ServiceDetail.shapeId` and `StopCall.position` are being added); [CoreMapGeometry] works with
 * what `TransitCore` offers today and falls back to [schematicGeometry].
 */
interface MapGeometrySource {
    suspend fun service(detail: ServiceDetail): ServiceGeometry

    /** Lines drawn from sample services ([samples], one or more per line, either direction). */
    suspend fun lines(samples: List<ServiceDetail>): List<LineGeometry>
}

/**
 * [MapGeometrySource] over today's `TransitCore`.
 *
 * Stop positions: each call's station is found with `searchStops(call.stopName, near = the
 * previous stop)` and matched by id, else by name; results are cached per stop id. The core
 * names a platform call after its parent station, so the name match works for the real core and
 * the fake. Calls not found between found ones are spaced evenly between them; with fewer than
 * two found the whole service falls back to [schematicGeometry].
 *
 * Paths: straight lines from station to station, because the service's shape id is not in the
 * contract yet.
 *
 * **When the contract grows** (`ServiceDetail.shapeId: ShapeId?`, `StopCall.position: LatLng?`),
 * the change is in [service] only:
 * 1. in [locate], return `call.position` first: `call.position?.let { return Located(it, null) }`;
 * 2. after the stop points are known, use the shape when there is one:
 *    `val shape = detail.shapeId?.let { core.shape(it) }.orEmpty().map(projection::project)`
 *    and build `path = if (shape.size >= 2) MapPath(shape) else MapPath(points)`, with
 *    `stopDistances = points.map { path.snap(it).distance }` made non-decreasing (as below for
 *    straight paths, each snap searched from the previous stop's distance onward).
 * Nothing else (screens, state holders, tests of the fallback) changes.
 */
/** A stop further than this from a line shape means the shape is not this service's. */
private const val MAX_STOP_OFF_SHAPE_M = 400.0

class CoreMapGeometry(
    private val core: TransitCore,
    private val projection: MapProjection = MapProjection.Sydney,
) : MapGeometrySource {
    private val lock = Mutex()
    private val stopCache = HashMap<StopId, Stop?>()
    private val serviceCache = HashMap<List<StopId>, ServiceGeometry>()

    override suspend fun service(detail: ServiceDetail): ServiceGeometry {
        val key = detail.calls.map { it.stopId } + listOfNotNull(detail.shapeId)
        lock.withLock { serviceCache[key] }?.let { return it }
        val stops = ArrayList<Stop?>(detail.calls.size)
        var near: LatLng? = null
        for (call in detail.calls) {
            val s = locate(call, near)
            stops += s
            if (s != null) near = s.position
        }
        val straight = geometryFromStops(detail.calls, stops.map { it?.position }, projection, stops)
        val geometry = alongShape(straight, detail)
        lock.withLock { serviceCache[key] = geometry }
        return geometry
    }

    /**
     * [straight] drawn along the service's own line shape when the timetable has one. The shape
     * is ignored when it is missing, or when a stop lies far from it (a shape that does not
     * belong to these stops), so the map never shows a line its stops are not on.
     */
    private suspend fun alongShape(straight: ServiceGeometry, detail: ServiceDetail): ServiceGeometry {
        if (straight.schematic) return straight
        val shapeId = detail.shapeId ?: return straight
        val shape = runCatching { core.shape(shapeId) }.getOrDefault(emptyList()).map(projection::project)
        if (shape.size < 2) return straight
        val path = MapPath(shape)
        var previous = 0.0
        val distances = ArrayList<Double>(straight.stopPoints.size)
        for (point in straight.stopPoints) {
            val snap = path.snap(point, from = previous)
            if (snap.offset > MAX_STOP_OFF_SHAPE_M) return straight
            previous = snap.distance
            distances += snap.distance
        }
        return straight.copy(path = path, stopDistances = distances)
    }

    override suspend fun lines(samples: List<ServiceDetail>): List<LineGeometry> {
        val byRoute = LinkedHashMap<RouteId, MutableList<ServiceGeometry>>()
        for (d in samples) {
            val routeId = d.route?.id ?: continue
            val g = service(d)
            if (g.schematic) continue // invented positions do not belong on a shared map
            byRoute.getOrPut(routeId) { ArrayList() } += g
        }
        return byRoute.map { (routeId, gs) -> mergeLine(routeId, gs) }
    }

    private suspend fun locate(call: StopCall, near: LatLng?): Stop? {
        // The core says where the stop is: no search needed.
        call.position?.let { return Stop(id = call.stopId, name = call.stopName, position = it) }
        lock.withLock { if (stopCache.containsKey(call.stopId)) return stopCache[call.stopId] }
        val found = runCatching { core.searchStops(call.stopName, near) }.getOrDefault(emptyList())
        val match = matchStop(call, found, near)
        lock.withLock { stopCache[call.stopId] = match }
        return match
    }
}

/**
 * Picks the station for [call] among [candidates]: the same id (or the platform's station by
 * id prefix), else the same name (full, then short), nearest to [near] when several match.
 */
fun matchStop(call: StopCall, candidates: List<Stop>, near: LatLng?): Stop? {
    if (candidates.isEmpty()) return null
    candidates.firstOrNull { it.id == call.stopId }?.let { return it }
    candidates.firstOrNull { call.stopId.startsWith(it.id + "-") || call.stopId.startsWith(it.id + ":") }?.let { return it }
    val short = shortStopName(call.stopName)
    val named = candidates.filter { it.name.equals(call.stopName, ignoreCase = true) }
        .ifEmpty { candidates.filter { shortStopName(it.name).equals(short, ignoreCase = true) } }
    if (named.isEmpty()) return null
    if (near == null || named.size == 1) return named.first()
    val p = MapProjection.Sydney
    return named.minBy { p.project(it.position).distanceTo(p.project(near)) }
}

/**
 * Builds a service's geometry from the positions found for its calls ([positions], null where
 * not found). Gaps between found calls are filled evenly; missing calls at either end are
 * extended along the nearest found stretch. Fewer than two found: [schematicGeometry].
 */
fun geometryFromStops(
    calls: List<StopCall>,
    positions: List<LatLng?>,
    projection: MapProjection = MapProjection.Sydney,
    stations: List<Stop?> = positions.map { null },
): ServiceGeometry {
    val known = positions.indices.filter { positions[it] != null }
    if (known.size < 2 || known.map { positions[it] }.distinct().size < 2) return schematicGeometry(calls)
    val pts = arrayOfNulls<MapPoint>(calls.size)
    for (i in known) pts[i] = projection.project(positions[i]!!)
    // Fill interior gaps evenly.
    for (k in 0 until known.size - 1) {
        val a = known[k]
        val b = known[k + 1]
        for (i in a + 1 until b) {
            val t = (i - a).toDouble() / (b - a)
            pts[i] = pts[a]!! + (pts[b]!! - pts[a]!!) * t
        }
    }
    // Extend the ends with the spacing of the nearest found stretch.
    val first = known.first()
    val last = known.last()
    if (first > 0) {
        val step = (pts[first]!! - pts[known[1]]!!) * (1.0 / (known[1] - first))
        for (i in first - 1 downTo 0) pts[i] = pts[i + 1]!! + step
    }
    if (last < calls.size - 1) {
        val prev = known[known.size - 2]
        val step = (pts[last]!! - pts[prev]!!) * (1.0 / (last - prev))
        for (i in last + 1 until calls.size) pts[i] = pts[i - 1]!! + step
    }
    val points = pts.map { it!! }
    val path = MapPath(points)
    return ServiceGeometry(path, points, path.cumulative.toList(), stations, calls.map { shortStopName(it.stopName) }, calls.map { it.stopId }, schematic = false)
}

/**
 * The fallback when stop positions are unknown: a clearly schematic line, stops evenly spaced
 * [spacing] metres apart running east, stepping down half a spacing after every third stop so it
 * reads as a diagram, not a street map. Not to scale; the screen says so.
 */
fun schematicGeometry(calls: List<StopCall>, spacing: Double = SCHEMATIC_SPACING): ServiceGeometry {
    val points = ArrayList<MapPoint>(calls.size)
    var y = 0.0
    for (i in calls.indices) {
        if (i > 0 && i % 3 == 0) y += spacing / 2
        points += MapPoint(i * spacing, y)
    }
    val path = MapPath(points)
    return ServiceGeometry(
        path, points, path.cumulative.toList(), calls.map { null }, calls.map { shortStopName(it.stopName) }, calls.map { it.stopId }, schematic = true,
    )
}

const val SCHEMATIC_SPACING = 1_000.0

/** Merges the services of one line into stretches between stations (each stretch once). */
fun mergeLine(routeId: RouteId, services: List<ServiceGeometry>): LineGeometry {
    val segments = LinkedHashMap<String, LineSegment>()
    val stations = LinkedHashMap<String, LineStation>()
    for (g in services) {
        for (i in g.stopPoints.indices) {
            val name = g.names[i]
            if (name !in stations) {
                val stop = g.stations[i]
                stations[name] = LineStation(name, g.stopPoints[i], stop, stop?.id ?: g.stopIds[i])
            }
            if (i + 1 < g.stopPoints.size) {
                val key = g.segmentKeyAt(i)
                if (key !in segments && g.names[i] != g.names[i + 1]) {
                    // Store every stretch in its canonical direction so lanes agree both ways.
                    val forward = g.names[i] <= g.names[i + 1]
                    val pts = if (forward) listOf(g.stopPoints[i], g.stopPoints[i + 1]) else listOf(g.stopPoints[i + 1], g.stopPoints[i])
                    segments[key] = LineSegment(key, MapPath(pts))
                }
            }
        }
    }
    return LineGeometry(routeId, segments.values.toList(), stations.values.toList())
}
