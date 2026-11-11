package app.transittracker.server.app

import app.transittracker.model.LatLng
import kotlin.math.roundToLong

/**
 * Google encoded polyline algorithm format (precision 5), as used by `/v1/shapes/{id}`.
 * See https://developers.google.com/maps/documentation/utilities/polylinealgorithm
 */
object Polyline {
    private const val FACTOR = 1e5

    /** Encodes [points] as a precision-5 encoded polyline. */
    fun encode(points: List<LatLng>): String {
        val out = StringBuilder(points.size * 8)
        var prevLat = 0L
        var prevLon = 0L
        for (p in points) {
            val lat = round(p.lat)
            val lon = round(p.lon)
            encodeValue(lat - prevLat, out)
            encodeValue(lon - prevLon, out)
            prevLat = lat
            prevLon = lon
        }
        return out.toString()
    }

    /** Decodes a precision-5 encoded polyline (the inverse of [encode]). */
    fun decode(encoded: String): List<LatLng> {
        val points = ArrayList<LatLng>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            val (dLat, afterLat) = decodeValue(encoded, index)
            val (dLon, afterLon) = decodeValue(encoded, afterLat)
            index = afterLon
            lat += dLat
            lon += dLon
            points += LatLng(lat / FACTOR, lon / FACTOR)
        }
        return points
    }

    /** Rounds half away from zero, matching Google's reference implementation. */
    private fun round(value: Double): Long {
        val scaled = value * FACTOR
        return if (scaled < 0) -((-scaled).roundToLong()) else scaled.roundToLong()
    }

    private fun encodeValue(value: Long, out: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            out.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        out.append((v.toInt() + 63).toChar())
    }

    private fun decodeValue(encoded: String, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var index = start
        while (true) {
            require(index < encoded.length) { "Truncated polyline" }
            val b = encoded[index++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        val value = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return value to index
    }
}
