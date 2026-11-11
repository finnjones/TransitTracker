package app.transittracker.server.planner

import app.transittracker.model.LatLng
import app.transittracker.model.WallClock
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

internal object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres. */
    fun distanceMetres(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}

/** Google encoded polyline, precision 5 (the format of `ItineraryLeg.path` and `/v1/shapes`). */
internal object PolylineCodec {
    fun encode(points: List<LatLng>): String {
        val out = StringBuilder(points.size * 8)
        var prevLat = 0L
        var prevLon = 0L
        for (p in points) {
            val lat = (p.lat * 1e5).roundToLong()
            val lon = (p.lon * 1e5).roundToLong()
            value(lat - prevLat, out)
            value(lon - prevLon, out)
            prevLat = lat
            prevLon = lon
        }
        return out.toString()
    }

    private fun value(v: Long, out: StringBuilder) {
        var n = if (v < 0) (v shl 1).inv() else v shl 1
        while (n >= 0x20) {
            out.append(((0x20 or (n and 0x1f).toInt()) + 63).toChar())
            n = n shr 5
        }
        out.append((n.toInt() + 63).toChar())
    }
}

/**
 * A small LRU cache whose entries expire [ttlMs] after they were stored. Bounded to [maxEntries];
 * holds results only in memory (never written anywhere).
 */
internal class TtlCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val ttlMs: Long,
    private val clock: WallClock,
) {
    private class Entry<V>(val value: V, val storedMs: Long)

    private val map = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (clock.nowMillis() - entry.storedMs >= ttlMs) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = Entry(value, clock.nowMillis())
    }

    @get:Synchronized
    val size: Int get() = map.size
}
