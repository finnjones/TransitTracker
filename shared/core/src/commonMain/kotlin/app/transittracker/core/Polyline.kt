package app.transittracker.core

import app.transittracker.model.LatLng

/** Decodes a Google encoded polyline (precision 5, as served by `/v1/shapes/{id}`). */
fun decodePolyline(encoded: String, precision: Int = 5): List<LatLng> {
    var factor = 1.0
    repeat(precision) { factor *= 10 }
    val points = ArrayList<LatLng>()
    var index = 0
    var lat = 0L
    var lon = 0L
    fun next(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(index < encoded.length) { "Truncated polyline" }
            val b = encoded[index++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
    }
    while (index < encoded.length) {
        lat += next()
        lon += next()
        points += LatLng(lat / factor, lon / factor)
    }
    return points
}

/** Encodes [points] as a Google encoded polyline (the inverse of [decodePolyline]). */
fun encodePolyline(points: List<LatLng>, precision: Int = 5): String {
    var factor = 1.0
    repeat(precision) { factor *= 10 }
    val out = StringBuilder()
    var lastLat = 0L
    var lastLon = 0L
    fun put(value: Long) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            out.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        out.append((v.toInt() + 63).toChar())
    }
    for (p in points) {
        val lat = kotlin.math.round(p.lat * factor).toLong()
        val lon = kotlin.math.round(p.lon * factor).toLong()
        put(lat - lastLat)
        put(lon - lastLon)
        lastLat = lat
        lastLon = lon
    }
    return out.toString()
}
