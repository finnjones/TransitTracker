package app.transittracker.timetable

/**
 * Unsigned LEB128 varints and zigzag encoding, the building blocks of the packed pattern,
 * time-profile and bitset blobs in a timetable pack.
 */
object Varint {
    /** Maps signed to unsigned so small negative numbers stay small: 0, -1, 1, -2 -> 0, 1, 2, 3. */
    fun zigzag(value: Int): Int = (value shl 1) xor (value shr 31)

    /** Inverse of [zigzag]. */
    fun unzigzag(value: Int): Int = (value ushr 1) xor -(value and 1)

    /** Number of bytes [value] takes as an unsigned varint (negative values take 5). */
    fun size(value: Int): Int {
        var v = value
        var n = 1
        while (v and 0x7F.inv() != 0) {
            v = v ushr 7
            n++
        }
        return n
    }

    /** Encodes [values] as consecutive unsigned varints. */
    fun encodeAll(values: IntArray): ByteArray {
        val out = ByteWriter(values.size + 4)
        for (v in values) out.writeVarint(v)
        return out.toByteArray()
    }

    /** Decodes exactly [count] unsigned varints from [bytes]. */
    fun decodeAll(bytes: ByteArray, count: Int): IntArray {
        val reader = ByteReader(bytes)
        return IntArray(count) { reader.readVarint() }.also {
            require(!reader.hasMore) { "Trailing bytes after $count varints" }
        }
    }

    /** Encodes [values] as zigzag varints of the difference to the previous value (first vs 0). */
    fun encodeDeltas(values: IntArray): ByteArray {
        val out = ByteWriter(values.size + 4)
        var prev = 0
        for (v in values) {
            out.writeVarint(zigzag(v - prev))
            prev = v
        }
        return out.toByteArray()
    }

    /** Inverse of [encodeDeltas]. */
    fun decodeDeltas(bytes: ByteArray, count: Int): IntArray {
        val reader = ByteReader(bytes)
        var prev = 0
        return IntArray(count) {
            prev += unzigzag(reader.readVarint())
            prev
        }.also { require(!reader.hasMore) { "Trailing bytes after $count deltas" } }
    }
}

/** Growable byte buffer for building packed blobs. */
class ByteWriter(initialCapacity: Int = 32) {
    private var buf = ByteArray(maxOf(initialCapacity, 8))

    /** Number of bytes written so far. */
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    fun writeByte(value: Int) {
        ensure(1)
        buf[size++] = value.toByte()
    }

    /** Writes [value] as an unsigned LEB128 varint (negative values use 5 bytes). */
    fun writeVarint(value: Int) {
        ensure(5)
        var v = value
        while (v and 0x7F.inv() != 0) {
            buf[size++] = ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
        buf[size++] = v.toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Sequential reader over a packed blob. */
class ByteReader(private val bytes: ByteArray, private var pos: Int = 0) {
    val hasMore: Boolean get() = pos < bytes.size

    val position: Int get() = pos

    fun readByte(): Int {
        if (pos >= bytes.size) throw IllegalArgumentException("Unexpected end of data")
        return bytes[pos++].toInt() and 0xFF
    }

    /** Reads an unsigned LEB128 varint written by [ByteWriter.writeVarint]. */
    fun readVarint(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = readByte()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 35) { "Varint too long" }
        }
    }
}
