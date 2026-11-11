package app.transittracker.server.live

import com.google.transit.realtime.FeedMessage
import com.google.transit.realtime.VehiclePosition
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TfnswWireFixesTest {
    /** Header + 3 trip updates cut unchanged from `/v2/gtfs/realtime/sydneytrains` on 11 Nov 2026. */
    private val excerpt: ByteArray = Files.readAllBytes(
        Paths.get(System.getProperty("tt.testdata") ?: error("tt.testdata not set"), "realtime", "sydneytrains-tripupdates-excerpt.pb"),
    )

    @Test
    fun `the real Sydney Trains feed does not decode with the standard schema as sent`() {
        assertFailsWith<IllegalStateException> { FeedMessage.ADAPTER.decode(excerpt) }
    }

    @Test
    fun `after the fix the real feed decodes, keeping trips, stops and TfNSW's occupancy`() {
        val feed = FeedMessage.ADAPTER.decode(TfnswWireFixes.normalise(excerpt))
        assertEquals(
            listOf("18-U.1421.148.16.B.8.92129007", "95-E.1421.148.16.A.8.92129253", "618H.1421.148.60.T.8.92127589"),
            feed.entity.map { it.trip_update?.trip?.trip_id },
        )
        val stus = feed.entity.flatMap { it.trip_update!!.stop_time_update }
        assertTrue(stus.all { it.stop_id != null })
        val occupancies = stus.mapNotNull { it.departure_occupancy_status }
        assertEquals(10, occupancies.size)
        assertTrue(occupancies.all { it in setOf(VehiclePosition.OccupancyStatus.MANY_SEATS_AVAILABLE, VehiclePosition.OccupancyStatus.FEW_SEATS_AVAILABLE, VehiclePosition.OccupancyStatus.STANDING_ROOM_ONLY) })
        assertTrue(stus.all { it.stop_time_properties == null })
        // TfNSW's 1007 extensions are kept as unknown fields, untouched.
        assertTrue(feed.entity.all { it.trip_update!!.unknownFields.size > 0 })
    }

    @Test
    fun `a standard feed is returned as the same array`() {
        val bytes = feedMessage(1_794_383_000_000, listOf(tuEntity(tripUpdate("T1", stus = listOf(stu(seq = 1, stopId = "200060", depDelay = 60))))))
        assertSame(bytes, TfnswWireFixes.normalise(bytes))
    }

    @Test
    fun `the TfNSW field is dropped when the standard field 7 is also present`() {
        // stop_id "S" (field 4), TfNSW field 6 = 2, standard field 7 = 1.
        val stuBytes = byteArrayOf(0x22, 1, 'S'.code.toByte(), 0x30, 2, 0x38, 1)
        val tu = byteArrayOf(0x0a, 0) + byteArrayOf(0x12, stuBytes.size.toByte()) + stuBytes // empty trip, the update
        val entity = byteArrayOf(0x0a, 1, 'e'.code.toByte(), 0x1a, tu.size.toByte()) + tu
        val header = byteArrayOf(0x0a, 3, '2'.code.toByte(), '.'.code.toByte(), '0'.code.toByte())
        val feed = byteArrayOf(0x0a, header.size.toByte()) + header + byteArrayOf(0x12, entity.size.toByte()) + entity
        val fixed = FeedMessage.ADAPTER.decode(TfnswWireFixes.normalise(feed))
        val update = fixed.entity.single().trip_update!!.stop_time_update.single()
        assertEquals("S", update.stop_id)
        assertEquals(VehiclePosition.OccupancyStatus.MANY_SEATS_AVAILABLE, update.departure_occupancy_status)
        assertEquals(null, update.stop_time_properties)
    }

    @Test
    fun `malformed input is returned unchanged`() {
        val truncated = excerpt.copyOf(excerpt.size - 7)
        assertSame(truncated, TfnswWireFixes.normalise(truncated))
    }
}
