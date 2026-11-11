package app.transittracker.server.live

import java.io.ByteArrayOutputStream

/**
 * Byte-level repair of TfNSW GTFS-Realtime feeds before they are decoded with the standard schema.
 *
 * Sydney Trains' v2 trip updates (`/v2/gtfs/realtime/sydneytrains`, checked on 11 Nov 2026) send
 * `StopTimeUpdate` field 6 as a varint with values 1..3: TfNSW's pre-standard
 * `departure_occupancy_status`. The official GTFS-R schema has since given field 6 to the
 * `stop_time_properties` message (and moved `departure_occupancy_status` to 7), so Wire rejects
 * the whole feed ("Unexpected call to beginMessage()"). Every such field is renumbered to 7 (the
 * same `OccupancyStatus` enum), or dropped when the update already has a field 7. Nothing else is
 * touched; a feed without such fields is returned as the same array. Malformed input is returned
 * unchanged so the decoder reports it.
 *
 * TfNSW's extension fields (1007) on `TripUpdate` and `StopTimeUpdate` are left alone: they are
 * length-delimited and Wire skips unknown fields.
 */
object TfnswWireFixes {
    private const val VARINT = 0
    private const val FIXED64 = 1
    private const val LENGTH_DELIMITED = 2
    private const val FIXED32 = 5

    /** Field numbers on the path FeedMessage.entity -> FeedEntity.trip_update -> TripUpdate.stop_time_update. */
    private val PATH = intArrayOf(2, 3, 2)
    private const val TFNSW_DEPARTURE_OCCUPANCY = 6
    private const val DEPARTURE_OCCUPANCY = 7

    fun normalise(feed: ByteArray): ByteArray = try {
        rewrite(feed, 0, feed.size, 0) ?: feed
    } catch (e: MalformedException) {
        feed
    }

    private class MalformedException : Exception()

    private class Field(val number: Int, val wireType: Int, val start: Int, val valueStart: Int, val end: Int)

    /** The rewritten bytes of the message in [from, to) at [depth] of [PATH], or null if unchanged. */
    private fun rewrite(b: ByteArray, from: Int, to: Int, depth: Int): ByteArray? {
        val fields = fields(b, from, to)
        if (depth == PATH.size) return fixStopTimeUpdate(b, fields)
        var out: ByteArrayOutputStream? = null
        for ((i, f) in fields.withIndex()) {
            val child = if (f.number == PATH[depth] && f.wireType == LENGTH_DELIMITED) rewrite(b, f.valueStart, f.end, depth + 1) else null
            if (child != null && out == null) {
                out = ByteArrayOutputStream(to - from + 64)
                out.write(b, from, fields[i].start - from) // the unchanged fields before this one
            }
            if (out == null) continue
            if (child != null) {
                writeVarint(out, (f.number.toLong() shl 3) or LENGTH_DELIMITED.toLong())
                writeVarint(out, child.size.toLong())
                out.write(child)
            } else {
                out.write(b, f.start, f.end - f.start)
            }
        }
        return out?.toByteArray()
    }

    private fun fixStopTimeUpdate(b: ByteArray, fields: List<Field>): ByteArray? {
        if (fields.none { it.number == TFNSW_DEPARTURE_OCCUPANCY && it.wireType == VARINT }) return null
        val hasStandard = fields.any { it.number == DEPARTURE_OCCUPANCY }
        val out = ByteArrayOutputStream()
        for (f in fields) {
            if (f.number == TFNSW_DEPARTURE_OCCUPANCY && f.wireType == VARINT) {
                if (hasStandard) continue
                writeVarint(out, (DEPARTURE_OCCUPANCY.toLong() shl 3) or VARINT.toLong())
                out.write(b, f.valueStart, f.end - f.valueStart)
            } else {
                out.write(b, f.start, f.end - f.start)
            }
        }
        return out.toByteArray()
    }

    private fun fields(b: ByteArray, from: Int, to: Int): List<Field> {
        val out = ArrayList<Field>()
        var pos = from
        while (pos < to) {
            val start = pos
            val (key, afterKey) = readVarint(b, pos, to)
            val number = (key ushr 3).toInt()
            val wireType = (key and 7).toInt()
            if (number <= 0) throw MalformedException()
            var valueStart = afterKey
            val end = when (wireType) {
                VARINT -> readVarint(b, afterKey, to).second
                FIXED64 -> afterKey + 8
                FIXED32 -> afterKey + 4
                LENGTH_DELIMITED -> {
                    val (length, afterLength) = readVarint(b, afterKey, to)
                    if (length < 0 || length > to - afterLength) throw MalformedException()
                    valueStart = afterLength
                    afterLength + length.toInt()
                }
                else -> throw MalformedException() // groups are not used by GTFS-R
            }
            if (end > to) throw MalformedException()
            out += Field(number, wireType, start, valueStart, end)
            pos = end
        }
        return out
    }

    private fun readVarint(b: ByteArray, at: Int, to: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = at
        while (true) {
            if (pos >= to || shift > 63) throw MalformedException()
            val byte = b[pos++].toInt()
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result to pos
            shift += 7
        }
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}
