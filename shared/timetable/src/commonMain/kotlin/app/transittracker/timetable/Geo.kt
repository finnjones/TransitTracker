package app.transittracker.timetable

import app.transittracker.model.LatLng
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Small geodesy helpers shared by the pack builder and reader. */
object Geo {
    const val EARTH_RADIUS_METERS: Double = 6_371_008.8

    private fun rad(deg: Double) = deg * PI / 180.0

    /** Great-circle distance in metres. */
    fun haversineMeters(a: LatLng, b: LatLng): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }

    /** Degrees of latitude spanned by [meters]. */
    fun metersToLatDegrees(meters: Double): Double = meters / (EARTH_RADIUS_METERS * PI / 180.0)

    /** Degrees of longitude spanned by [meters] at latitude [lat]. */
    fun metersToLonDegrees(meters: Double, lat: Double): Double =
        meters / (EARTH_RADIUS_METERS * PI / 180.0 * maxOf(cos(rad(lat)), 1e-6))

    /** Converts degrees to the microdegrees stored in packs. */
    fun toE6(degrees: Double): Int = (degrees * 1_000_000.0).roundToLong().toInt()

    fun fromE6(e6: Long): Double = e6 / 1_000_000.0

    /**
     * Distance in metres from [p] to the segment [a]-[b], using a local equirectangular projection
     * (accurate to well under a metre at the scale of shape simplification).
     */
    fun distanceToSegmentMeters(p: LatLng, a: LatLng, b: LatLng): Double {
        val cosLat = cos(rad((a.lat + b.lat + p.lat) / 3))
        val k = EARTH_RADIUS_METERS * PI / 180.0
        val ax = a.lon * cosLat * k
        val ay = a.lat * k
        val bx = b.lon * cosLat * k - ax
        val by = b.lat * k - ay
        val px = p.lon * cosLat * k - ax
        val py = p.lat * k - ay
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return sqrt(dx * dx + dy * dy)
    }
}

/** Google encoded polyline algorithm (precision 5) and Douglas–Peucker simplification. */
object Polyline {
    private const val FACTOR = 1e5

    /** Encodes [points] as a Google encoded polyline with precision 5. */
    fun encode(points: List<LatLng>): String {
        val sb = StringBuilder(points.size * 6)
        var prevLat = 0L
        var prevLon = 0L
        for (p in points) {
            val lat = (p.lat * FACTOR).roundToLong()
            val lon = (p.lon * FACTOR).roundToLong()
            encodeValue(lat - prevLat, sb)
            encodeValue(lon - prevLon, sb)
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    private fun encodeValue(value: Long, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1F).toInt()) + 63).toChar())
            v = v shr 5
        }
        sb.append((v + 63).toInt().toChar())
    }

    /** Decodes a Google encoded polyline (precision 5). */
    fun decode(encoded: String): List<LatLng> {
        val out = ArrayList<LatLng>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(index < encoded.length) { "Truncated polyline" }
                val b = encoded[index++].code - 63
                result = result or ((b and 0x1F).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (index < encoded.length) {
            lat += next()
            lon += next()
            out += LatLng(lat / FACTOR, lon / FACTOR)
        }
        return out
    }

    /**
     * Douglas–Peucker simplification keeping every point that deviates more than [toleranceMeters]
     * from the simplified line. First and last points are always kept. Iterative (no recursion
     * depth limit for long shapes).
     */
    fun simplify(points: List<LatLng>, toleranceMeters: Double = 10.0): List<LatLng> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast().let { it[0] to it[1] }
            var maxDist = -1.0
            var maxIdx = -1
            for (i in start + 1 until end) {
                val d = Geo.distanceToSegmentMeters(points[i], points[start], points[end])
                if (d > maxDist) {
                    maxDist = d
                    maxIdx = i
                }
            }
            if (maxIdx >= 0 && maxDist > toleranceMeters) {
                keep[maxIdx] = true
                stack.addLast(intArrayOf(start, maxIdx))
                stack.addLast(intArrayOf(maxIdx, end))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}
