package app.transittracker.timetable

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VarintTest {
    @Test
    fun zigzagRoundTrips() {
        assertEquals(0, Varint.zigzag(0))
        assertEquals(1, Varint.zigzag(-1))
        assertEquals(2, Varint.zigzag(1))
        assertEquals(3, Varint.zigzag(-2))
        for (v in listOf(0, 1, -1, 63, -64, 1_000_000, -1_000_000, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(v, Varint.unzigzag(Varint.zigzag(v)))
        }
    }

    @Test
    fun varintEncodingMatchesLeb128() {
        val w = ByteWriter()
        w.writeVarint(0)
        w.writeVarint(127)
        w.writeVarint(128)
        w.writeVarint(300)
        assertContentEquals(byteArrayOf(0, 0x7F, 0x80.toByte(), 0x01, 0xAC.toByte(), 0x02), w.toByteArray())
        assertEquals(1, Varint.size(127))
        assertEquals(2, Varint.size(128))
        assertEquals(5, Varint.size(-1))
    }

    @Test
    fun varintRoundTripsIncludingNegatives() {
        val values = intArrayOf(0, 1, 127, 128, 16_383, 16_384, 2_097_151, Int.MAX_VALUE, -1, Int.MIN_VALUE)
        assertContentEquals(values, Varint.decodeAll(Varint.encodeAll(values), values.size))
    }

    @Test
    fun deltasRoundTripAndStaySmall() {
        val stops = intArrayOf(1000, 1001, 1003, 998, 2000, 5)
        val bytes = Varint.encodeDeltas(stops)
        assertContentEquals(stops, Varint.decodeDeltas(bytes, stops.size))
        assertTrue(bytes.size < stops.size * 2 + 2)
    }

    @Test
    fun truncatedOrTrailingDataIsRejected() {
        assertFailsWith<IllegalArgumentException> { ByteReader(byteArrayOf(0x80.toByte())).readVarint() }
        assertFailsWith<IllegalArgumentException> { Varint.decodeAll(byteArrayOf(1, 2), 1) }
    }

    @Test
    fun profilesRoundTrip() {
        val arr = intArrayOf(0, 180, 300, 600)
        val dep = intArrayOf(0, 180, 330, 600)
        val (a, d) = PackCodec.decodeProfile(PackCodec.encodeProfile(arr, dep), 4)
        assertContentEquals(arr, a)
        assertContentEquals(dep, d)
        assertFailsWith<IllegalArgumentException> { PackCodec.encodeProfile(intArrayOf(0, 100), intArrayOf(200, 150)) }
    }

    @Test
    fun sequencesAndFlagsUseNullForTheCommonCase() {
        assertNull(PackCodec.encodeSequences(intArrayOf(1, 2, 3)))
        assertContentEquals(intArrayOf(1, 2, 3), PackCodec.decodeSequences(null, 3))
        val seq = intArrayOf(10, 20, 35)
        assertContentEquals(seq, PackCodec.decodeSequences(PackCodec.encodeSequences(seq), 3))
        assertNull(PackCodec.encodeFlags(IntArray(3), IntArray(3)))
        val (p, d) = PackCodec.decodeFlags(PackCodec.encodeFlags(intArrayOf(0, 1, 2), intArrayOf(3, 0, 1)), 3)
        assertContentEquals(intArrayOf(0, 1, 2), p)
        assertContentEquals(intArrayOf(3, 0, 1), d)
    }

    @Test
    fun dayBitsets() {
        val days = BooleanArray(20) { it == 0 || it == 9 || it == 10 }
        val bytes = PackCodec.encodeDays(days)
        assertEquals(2, bytes.size)
        assertTrue(PackCodec.isDayActive(bytes, 0))
        assertFalse(PackCodec.isDayActive(bytes, 1))
        assertTrue(PackCodec.isDayActive(bytes, 10))
        assertFalse(PackCodec.isDayActive(bytes, 19))
        assertFalse(PackCodec.isDayActive(bytes, -1))
        assertFalse(PackCodec.isDayActive(bytes, 1000))
        assertEquals(11, PackCodec.dayCount(bytes))
        assertEquals(0, PackCodec.encodeDays(BooleanArray(5)).size)
    }
}
