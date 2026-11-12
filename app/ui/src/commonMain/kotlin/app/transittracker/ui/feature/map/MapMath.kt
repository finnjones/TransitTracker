package app.transittracker.ui.feature.map

import app.transittracker.model.LatLng
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The map's own geometry (decision F5: no tiles, no map SDK). Everything here is pure and tested
 * (`MapMathTest`): projection, paths, snapping, rasterising into whole dots, and shared lanes.
 */

/** A point on the map's flat plane, in metres east ([x]) and south ([y]) of the projection origin. */
data class MapPoint(val x: Double, val y: Double) {
    operator fun plus(o: MapPoint) = MapPoint(x + o.x, y + o.y)
    operator fun minus(o: MapPoint) = MapPoint(x - o.x, y - o.y)
    operator fun times(k: Double) = MapPoint(x * k, y * k)
    fun distanceTo(o: MapPoint): Double = hypot(x - o.x, y - o.y)
}

/** An axis-aligned box on the map plane. */
data class MapBox(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
    val center: MapPoint get() = MapPoint((minX + maxX) / 2, (minY + maxY) / 2)

    fun union(o: MapBox) = MapBox(min(minX, o.minX), min(minY, o.minY), max(maxX, o.maxX), max(maxY, o.maxY))
    fun grow(m: Double) = MapBox(minX - m, minY - m, maxX + m, maxY + m)

    companion object {
        /** The box around [points]; null when there are none. */
        fun around(points: Iterable<MapPoint>): MapBox? {
            var box: MapBox? = null
            for (p in points) {
                box = box?.union(MapBox(p.x, p.y, p.x, p.y)) ?: MapBox(p.x, p.y, p.x, p.y)
            }
            return box
        }
    }
}

/**
 * Latitude / longitude to the map plane: an equirectangular projection scaled by the cosine of
 * the origin's latitude, so that a metre east and a metre south are the same length on screen
 * near [origin]. Across greater Sydney (about 100 km) the error is well under 1 %, so the city
 * keeps its shape: the harbour runs east-west, the North Shore line runs north.
 */
class MapProjection(val origin: LatLng = SYDNEY_ORIGIN) {
    private val kx = METRES_PER_DEGREE * cos(origin.lat * PI / 180.0)

    fun project(p: LatLng): MapPoint = MapPoint((p.lon - origin.lon) * kx, (origin.lat - p.lat) * METRES_PER_DEGREE)

    fun unproject(p: MapPoint): LatLng = LatLng(origin.lat - p.y / METRES_PER_DEGREE, origin.lon + p.x / kx)

    companion object {
        /** Mean metres per degree of latitude. */
        const val METRES_PER_DEGREE = 111_195.0

        /** Sydney Town Hall: the origin of every map in the app. */
        val SYDNEY_ORIGIN = LatLng(-33.8732, 151.2061)

        val Sydney = MapProjection()
    }
}

/** Where a point lands on a [MapPath]. */
data class PathSnap(
    /** Distance along the path, in metres from its start. */
    val distance: Double,
    /** The point on the path. */
    val point: MapPoint,
    /** How far the original point was from the path. */
    val offset: Double,
)

/** A polyline on the map plane with distances along it. */
class MapPath(val points: List<MapPoint>) {
    /** Cumulative distance at each point. */
    val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + points[i].distanceTo(points[i - 1])
    }

    val length: Double get() = if (cumulative.isEmpty()) 0.0 else cumulative.last()

    /** The point at [distance] along the path (clamped to its ends). */
    fun pointAt(distance: Double): MapPoint {
        if (points.isEmpty()) return MapPoint(0.0, 0.0)
        if (points.size == 1 || distance <= 0) return points.first()
        if (distance >= length) return points.last()
        val i = segmentAt(distance)
        val a = points[i]
        val b = points[i + 1]
        val seg = cumulative[i + 1] - cumulative[i]
        val t = if (seg <= 0) 0.0 else (distance - cumulative[i]) / seg
        return a + (b - a) * t
    }

    /**
     * Heading at [distance] in degrees clockwise from "down the screen" (south), the way the
     * top-down sprite is drawn: 0 = south, 90 = west, 180 = north, 270 = east.
     */
    fun headingAt(distance: Double): Double {
        if (points.size < 2) return 0.0
        // At a vertex (a stop) keep the heading it arrived with; it turns once it moves on.
        val d = distance.coerceIn(0.0, length)
        var i = segmentAt(if (d > 0.0) d - 1e-6 else d)
        // Skip zero-length segments (repeated points) so the heading is defined.
        while (i < points.size - 2 && points[i].distanceTo(points[i + 1]) == 0.0) i++
        return headingOf(points[i + 1] - points[i])
    }

    /** The stretch from [from] to [to] (metres along, clamped), as a path of its own starting at 0. */
    fun slice(from: Double, to: Double): MapPath {
        if (points.size < 2) return this
        val a = from.coerceIn(0.0, length)
        val b = to.coerceIn(a, length)
        val pts = ArrayList<MapPoint>()
        pts += pointAt(a)
        for (i in points.indices) if (cumulative[i] > a && cumulative[i] < b) pts += points[i]
        pts += pointAt(b)
        return MapPath(pts)
    }

    /** Index of the segment containing [distance] (0 until points.size - 1). */
    fun segmentAt(distance: Double): Int {
        if (points.size < 2) return 0
        var lo = 0
        var hi = points.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cumulative[mid] <= distance) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * Snaps [p] to the nearest point on the path. When [from] / [to] are given only that stretch
     * of the path is searched (a vehicle is snapped near where it was, so a line that doubles
     * back on itself does not catch it on the wrong side).
     */
    fun snap(p: MapPoint, from: Double = 0.0, to: Double = length): PathSnap {
        if (points.isEmpty()) return PathSnap(0.0, p, 0.0)
        if (points.size == 1) return PathSnap(0.0, points[0], p.distanceTo(points[0]))
        var best = PathSnap(0.0, points[0], Double.MAX_VALUE)
        for (i in 0 until points.size - 1) {
            if (cumulative[i + 1] < from || cumulative[i] > to) continue
            val a = points[i]
            val b = points[i + 1]
            val ab = b - a
            val len2 = ab.x * ab.x + ab.y * ab.y
            val t = if (len2 == 0.0) 0.0 else (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0.0, 1.0)
            val q = a + ab * t
            val d = q.distanceTo(p)
            if (d < best.offset) {
                val along = (cumulative[i] + (cumulative[i + 1] - cumulative[i]) * t).coerceIn(from, to)
                best = PathSnap(along, pointAt(along), d)
            }
        }
        return best
    }
}

/** Heading of a direction vector, as in [MapPath.headingAt]. */
fun headingOf(v: MapPoint): Double {
    if (v.x == 0.0 && v.y == 0.0) return 0.0
    // atan2 of (west, south): 0 for south, 90 for west.
    val deg = atan2(-v.x, v.y) * 180.0 / PI
    return (deg + 360.0) % 360.0
}

/** Rounds a heading to one of [steps] directions; returns the step index (0 = south). */
fun headingStep(headingDeg: Double, steps: Int = 16): Int {
    val per = 360.0 / steps
    return ((headingDeg / per).roundToInt()).mod(steps)
}

// ---------------------------------------------------------------- the camera

/**
 * What part of the map plane is on screen: [center] in the middle of the view, [metresPerDot]
 * the zoom. Screen positions are in dots from the view's top-left corner.
 */
data class MapCamera(val center: MapPoint, val metresPerDot: Double) {
    fun toDots(p: MapPoint, viewW: Int, viewH: Int): DotXY =
        DotXY((p.x - center.x) / metresPerDot + viewW / 2.0, (p.y - center.y) / metresPerDot + viewH / 2.0)

    fun toMap(x: Double, y: Double, viewW: Int, viewH: Int): MapPoint =
        MapPoint(center.x + (x - viewW / 2.0) * metresPerDot, center.y + (y - viewH / 2.0) * metresPerDot)

    /** Zooms by [factor] (> 1 zooms in) keeping the map point under ([x], [y]) where it is. */
    fun zoomAbout(factor: Double, x: Double, y: Double, viewW: Int, viewH: Int, limits: ZoomLimits): MapCamera {
        val anchor = toMap(x, y, viewW, viewH)
        val mpd = limits.clamp(metresPerDot / factor)
        val newCenter = MapPoint(anchor.x - (x - viewW / 2.0) * mpd, anchor.y - (y - viewH / 2.0) * mpd)
        return MapCamera(newCenter, mpd)
    }

    /** Moves by ([dx], [dy]) dots on screen (a drag moves the map with the finger). */
    fun panBy(dx: Double, dy: Double): MapCamera = copy(center = MapPoint(center.x - dx * metresPerDot, center.y - dy * metresPerDot))

    /** Keeps the centre inside [box] so the content can never be dragged out of sight. */
    fun clampTo(box: MapBox?): MapCamera {
        if (box == null) return this
        return copy(center = MapPoint(center.x.coerceIn(box.minX, box.maxX), center.y.coerceIn(box.minY, box.maxY)))
    }

    companion object {
        /**
         * The camera that shows [box] in a view of [viewW] x [viewH] dots with [marginDots] free on
         * every side ([marginTop] / [marginBottom] for overlays), within [limits].
         */
        fun fit(
            box: MapBox,
            viewW: Int,
            viewH: Int,
            limits: ZoomLimits,
            marginDots: Int = 16,
            marginTop: Int = marginDots,
            marginBottom: Int = marginDots,
        ): MapCamera {
            val w = max(1, viewW - 2 * marginDots)
            val h = max(1, viewH - marginTop - marginBottom)
            val mpd = limits.clamp(max(box.width / w, box.height / h))
            // Centre the box in the area between the margins.
            val cx = box.center.x
            val cy = box.center.y - (marginTop - marginBottom) / 2.0 * mpd
            return MapCamera(MapPoint(cx, cy), mpd)
        }
    }
}

/** Zoom range in metres per dot: [min] is the closest view, [max] the widest. */
data class ZoomLimits(val min: Double, val max: Double) {
    fun clamp(mpd: Double): Double = mpd.coerceIn(min, max)

    companion object {
        /** From a street (about 3 m a dot) to all of Sydney and beyond (about 1.2 km a dot). */
        val Default = ZoomLimits(3.0, 1_200.0)
    }
}

/** A position on screen in (fractional) dots. */
data class DotXY(val x: Double, val y: Double) {
    val ix: Int get() = floor(x).toInt()
    val iy: Int get() = floor(y).toInt()
}

// ---------------------------------------------------------------- rasterising

/**
 * The whole dots of a line from ([x0], [y0]) to ([x1], [y1]) (Bresenham), both ends included.
 * Each step moves one dot horizontally, vertically or diagonally, so a path drawn with a square
 * brush on these cells has no gaps and no half dots at any zoom or density.
 */
fun rasterLine(x0: Int, y0: Int, x1: Int, y1: Int): List<Pair<Int, Int>> {
    val out = ArrayList<Pair<Int, Int>>(max(abs(x1 - x0), abs(y1 - y0)) + 1)
    var x = x0
    var y = y0
    val dx = abs(x1 - x0)
    val dy = -abs(y1 - y0)
    val sx = (x1 - x0).sign
    val sy = (y1 - y0).sign
    var err = dx + dy
    while (true) {
        out += x to y
        if (x == x1 && y == y1) break
        val e2 = 2 * err
        if (e2 >= dy) {
            err += dy
            x += sx
        }
        if (e2 <= dx) {
            err += dx
            y += sy
        }
    }
    return out
}

/** One cell of a rasterised path, with the path distance (metres) it stands for. */
data class PathCell(val x: Int, val y: Int, val distance: Double)

/**
 * Rasterises [path] with [camera] into whole-dot cells, each tagged with its distance along the
 * path (for dimming the travelled part). [shiftX] / [shiftY] move every cell (a shared lane).
 * Consecutive duplicates are dropped.
 */
fun rasterPath(path: MapPath, camera: MapCamera, viewW: Int, viewH: Int, shiftX: Int = 0, shiftY: Int = 0): List<PathCell> {
    val pts = path.points
    if (pts.isEmpty()) return emptyList()
    val out = ArrayList<PathCell>()
    val dots = pts.map { camera.toDots(it, viewW, viewH) }
    if (pts.size == 1) return listOf(PathCell(dots[0].ix + shiftX, dots[0].iy + shiftY, 0.0))
    for (i in 0 until pts.size - 1) {
        val a = dots[i]
        val b = dots[i + 1]
        val cells = rasterLine(a.ix, a.iy, b.ix, b.iy)
        val d0 = path.cumulative[i]
        val d1 = path.cumulative[i + 1]
        for ((k, c) in cells.withIndex()) {
            if (k == 0 && out.isNotEmpty()) continue
            val t = if (cells.size <= 1) 0.0 else k.toDouble() / (cells.size - 1)
            out += PathCell(c.first + shiftX, c.second + shiftY, d0 + (d1 - d0) * t)
        }
    }
    return out
}

/**
 * The perpendicular step, in whole dots, of a segment running from [a] to [b]: the segment's
 * direction turned a quarter to the right and rounded to one of eight directions. Lanes of
 * shared track are this step times the lane number times the line width.
 */
fun laneStep(a: MapPoint, b: MapPoint): Pair<Int, Int> {
    val d = b - a
    if (d.x == 0.0 && d.y == 0.0) return 1 to 0
    // Perpendicular (to the right of travel on screen, y down): (-dy, dx).
    val px = -d.y
    val py = d.x
    val len = hypot(px, py)
    val sx = (px / len).let { if (abs(it) < 0.383) 0 else it.sign.toInt() }
    val sy = (py / len).let { if (abs(it) < 0.383) 0 else it.sign.toInt() }
    return sx to sy
}

/**
 * Lanes on shared track: lines that run between the same two stations are drawn side by side,
 * centred on the real alignment. For each segment key, the lines on it in [order] get lane
 * offsets -1/2 .. +1/2 times (count - 1), in units of one line width.
 */
fun assignLanes(segments: Map<String, Set<String>>, order: List<String>): Map<Pair<String, String>, Double> {
    val out = HashMap<Pair<String, String>, Double>()
    for ((key, lines) in segments) {
        val sorted = order.filter { it in lines } + lines.filter { it !in order }.sorted()
        val n = sorted.size
        for ((i, line) in sorted.withIndex()) out[key to line] = i - (n - 1) / 2.0
    }
    return out
}

/** Key of the track between two stations, the same in both directions. */
fun segmentKey(a: String, b: String): String = if (a <= b) "$a|$b" else "$b|$a"
