package app.transittracker.timetable

/**
 * Blob formats used in pack tables. Every encoder here has a matching decoder; both sides
 * (server builder and phone reader) use this one object.
 */
object PackCodec {
    /** `patterns.stops`: zigzag-delta varints of stop ids. */
    fun encodeStops(stopIds: IntArray): ByteArray = Varint.encodeDeltas(stopIds)

    fun decodeStops(bytes: ByteArray, count: Int): IntArray = Varint.decodeDeltas(bytes, count)

    /** `patterns.sequences`: null when sequences are exactly 1..n, else zigzag-delta varints. */
    fun encodeSequences(sequences: IntArray): ByteArray? =
        if (sequences.indices.all { sequences[it] == it + 1 }) null else Varint.encodeDeltas(sequences)

    fun decodeSequences(bytes: ByteArray?, count: Int): IntArray =
        bytes?.let { Varint.decodeDeltas(it, count) } ?: IntArray(count) { it + 1 }

    /** `patterns.flags`: one byte per stop (`pickup | dropOff << 2`), null when all are regular (0). */
    fun encodeFlags(pickupTypes: IntArray, dropOffTypes: IntArray): ByteArray? {
        if (pickupTypes.all { it == 0 } && dropOffTypes.all { it == 0 }) return null
        return ByteArray(pickupTypes.size) { ((pickupTypes[it] and 3) or ((dropOffTypes[it] and 3) shl 2)).toByte() }
    }

    /** Returns (pickupTypes, dropOffTypes). */
    fun decodeFlags(bytes: ByteArray?, count: Int): Pair<IntArray, IntArray> {
        if (bytes == null) return IntArray(count) to IntArray(count)
        require(bytes.size == count) { "Flags blob has ${bytes.size} bytes for $count stops" }
        return IntArray(count) { bytes[it].toInt() and 3 } to IntArray(count) { (bytes[it].toInt() shr 2) and 3 }
    }

    /**
     * `time_profiles.times`: dwell at stop 0, then for each later stop the run time from the
     * previous departure and the dwell. All values are non-negative varints. Requires
     * `arrivals[0] == 0` and non-decreasing times.
     */
    fun encodeProfile(arrivals: IntArray, departures: IntArray): ByteArray {
        require(arrivals.size == departures.size && arrivals.isNotEmpty()) { "Bad profile size" }
        require(arrivals[0] == 0) { "Profile must start at 0" }
        val out = ByteWriter(arrivals.size * 3)
        for (i in arrivals.indices) {
            if (i > 0) {
                val run = arrivals[i] - departures[i - 1]
                require(run >= 0) { "Times go backwards at position $i" }
                out.writeVarint(run)
            }
            val dwell = departures[i] - arrivals[i]
            require(dwell >= 0) { "Departure before arrival at position $i" }
            out.writeVarint(dwell)
        }
        return out.toByteArray()
    }

    /** Returns (arrivals, departures) offsets. */
    fun decodeProfile(bytes: ByteArray, count: Int): Pair<IntArray, IntArray> {
        val reader = ByteReader(bytes)
        val arr = IntArray(count)
        val dep = IntArray(count)
        for (i in 0 until count) {
            arr[i] = if (i == 0) 0 else dep[i - 1] + reader.readVarint()
            dep[i] = arr[i] + reader.readVarint()
        }
        require(!reader.hasMore) { "Trailing bytes in profile" }
        return arr to dep
    }

    /** `services.days`: little-endian bitset, trailing zero bytes trimmed. */
    fun encodeDays(days: BooleanArray): ByteArray {
        val last = days.indexOfLast { it }
        val bytes = ByteArray(if (last < 0) 0 else last / 8 + 1)
        for (i in 0..last) if (days[i]) bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
        return bytes
    }

    /** True if bit [dayIndex] is set (false outside the bitset). */
    fun isDayActive(bytes: ByteArray, dayIndex: Int): Boolean {
        if (dayIndex < 0 || dayIndex / 8 >= bytes.size) return false
        return (bytes[dayIndex / 8].toInt() shr (dayIndex % 8)) and 1 == 1
    }

    /** Number of days covered by the bitset (index of the last active day + 1). */
    fun dayCount(bytes: ByteArray): Int {
        for (i in bytes.size * 8 - 1 downTo 0) if (isDayActive(bytes, i)) return i + 1
        return 0
    }
}
