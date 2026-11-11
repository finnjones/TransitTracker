package app.transittracker.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceTimeTest {
    private val hour = 3_600_000L

    @Test
    fun parsesAndFormatsTimesPast24h() {
        assertEquals(25 * 3600 + 10 * 60, ServiceTime.parseGtfsTime("25:10:00"))
        assertEquals("25:10:00", ServiceTime.formatGtfsTime(25 * 3600 + 600))
        assertEquals(7 * 3600 + 5, ServiceTime.parseGtfsTime("7:00:05"))
    }

    @Test
    fun ordinaryDayReferenceIsLocalMidnight() {
        // 2026-11-11 is AEDT (UTC+11): local midnight = 2026-11-10T13:00Z
        val ref = ServiceTime.referenceMillis("20261111")
        assertEquals(1_762_779_600_000L + 365L * 24 * hour, ref) // 2025-11-10T13:00Z + 365 days
        assertEquals("20261111", ServiceTime.localDate(ref))
        assertEquals(0, ServiceTime.secondsOfLocalDay(ref))
    }

    @Test
    fun dstStartDayReferenceIsNotMidnight() {
        // DST starts Sunday 2026-10-04 at 02:00 AEST -> 03:00 AEDT. Noon is AEDT (UTC+11),
        // so "noon minus 12h" = 00:00 UTC+11 = 23:00 previous day AEST (local clock).
        val ref = ServiceTime.referenceMillis("20261004")
        val noon = ServiceTime.toEpochMillis("20261004", 12 * 3600)
        assertEquals(12 * hour, noon - ref)
        // Local midnight (AEST, UTC+10) is one hour AFTER the reference.
        assertEquals(3600, ServiceTime.secondsSinceReference("20261004", ref + hour))
        assertEquals(23 * 3600, ServiceTime.secondsOfLocalDay(ref))
    }

    @Test
    fun dstEndDayReferenceIsNotMidnight() {
        // DST ends Sunday 2026-04-05 at 03:00 AEDT -> 02:00 AEST. Noon is AEST (UTC+10),
        // so the reference is 00:00 UTC+10 = 01:00 local AEDT clock.
        val ref = ServiceTime.referenceMillis("20260405")
        assertEquals(3600, ServiceTime.secondsOfLocalDay(ref))
    }

    @Test
    fun afterMidnightTripsBelongToPreviousServiceDay() {
        val at = ServiceTime.toEpochMillis("20261111", 25 * 3600) // 01:00 on the 12th
        assertEquals(listOf("20261111", "20261112"), ServiceTime.candidateServiceDates(at))
        assertEquals(25 * 3600, ServiceTime.secondsSinceReference("20261111", at))
        assertEquals(3600, ServiceTime.secondsSinceReference("20261112", at))
    }

    @Test
    fun negativeOffsetsFloor() {
        val ref = ServiceTime.referenceMillis("20261111")
        assertEquals(-1, ServiceTime.secondsSinceReference("20261111", ref - 1))
    }

    @Test
    fun isoDayOfWeek() {
        assertEquals(3, ServiceTime.isoDayOfWeek("20261111")) // Wednesday
        assertEquals(7, ServiceTime.isoDayOfWeek("20261115")) // Sunday
    }
}
