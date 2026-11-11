package app.transittracker.server.pipeline

import app.transittracker.model.DepartureQuery
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.departuresAfter
import app.transittracker.timetable.JvmTimetables
import app.transittracker.timetable.TimetablePack
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** End-to-end: the committed fixture, built into a pack, answers departure queries correctly. */
class FixtureDeparturesTest {
    private val tmp: Path = Files.createTempDirectory("fixture-departures")
    private val opened = ArrayList<TimetablePack>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        tmp.toFile().deleteRecursively()
    }

    private fun pack(buildDate: LocalDate = LocalDate.of(2026, 11, 11)): TimetablePack {
        val version = buildDate.toString()
        val request = PackBuildRequest("mini-rail", version, buildDate)
        val built = PackBuilder.buildContent(Fixtures.load(Fixtures.miniRail).first, request, PackIdMaps())
        val art = PackBuilder.writeArtifacts(built, request, tmp.resolve(version))
        return JvmTimetables.open(art.sqlite).also { opened += it }
    }

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun q(from: String, to: String, date: String, fromSecs: Int = 0, toSecs: Int = 30 * 3600, routes: Set<String>? = null) =
        DepartureQuery(setOf(from), setOf(to), date, fromSecs, toSecs, limit = 50, routeIds = routes)

    @Test
    fun weekdayMorningBetweenStations() {
        val tt = pack()
        val deps = tt.departures(q("CEN", "CHA", "20261111", 7 * 3600, 8 * 3600 + 1800))
        assertEquals(listOf("T1.N.0700", "M1.N.0705", "T1.N.0730", "M1.N.0735", "T1.N.0800"), deps.map { it.tripId })
        val first = deps.first()
        assertEquals("CEN_P1", first.originStopId)
        assertEquals("CHA_P1", first.destinationStopId)
        assertEquals("T1", first.routeId)
        assertEquals("Chatswood", first.headsign)
        assertEquals(millis("2026-11-10T20:00:00Z"), first.departureMillis) // 07:00 AEDT
        assertEquals(millis("2026-11-10T20:20:00Z"), first.arrivalMillis)
        val metro = deps[1]
        assertEquals("CEN_M1", metro.originStopId)
        assertEquals(10, metro.originStopSequence)
        assertEquals(30, metro.destinationStopSequence)
    }

    @Test
    fun routeFilter() {
        val tt = pack()
        val metroOnly = tt.departures(q("CEN", "CHA", "20261111", routes = setOf("M1")))
        assertEquals(listOf("M1.N.0705", "M1.N.0735", "M1.N.2440"), metroOnly.map { it.tripId })
        val trainOnly = tt.departures(q("CEN", "CHA", "20261111", routes = setOf("T1")))
        assertTrue(trainOnly.all { it.routeId == "T1" })
        assertEquals(7, trainOnly.size)
    }

    @Test
    fun pickupAndDropOffRestrictions() {
        val tt = pack()
        // T1.N.0800 is pick-up only at Town Hall and set-down only at North Sydney.
        assertFalse("T1.N.0800" in tt.departures(q("CEN", "THL", "20261111")).map { it.tripId })
        assertFalse("T1.N.0800" in tt.departures(q("NSY", "CHA", "20261111")).map { it.tripId })
        assertTrue("T1.N.0800" in tt.departures(q("CEN", "NSY", "20261111")).map { it.tripId })
        assertTrue("T1.N.0800" in tt.departures(q("THL", "CHA", "20261111")).map { it.tripId })
        val stopTimes = assertNotNull(tt.trip("T1.N.0800")).stopTimes
        assertEquals(listOf(true, true, true, false, true), stopTimes.map { it.pickup })
        assertEquals(listOf(true, false, true, true, true), stopTimes.map { it.dropOff })
    }

    @Test
    fun directionMatters() {
        val tt = pack()
        assertEquals(listOf("T1.S.0715", "M1.S.1710", "T1.S.1730"), tt.departures(q("CHA", "CEN", "20261111")).map { it.tripId })
    }

    @Test
    fun afterMidnightViaDeparturesAfter() {
        val tt = pack()
        // 00:20 on Thursday 12 Nov in Sydney (AEDT, UTC+11).
        val deps = tt.departuresAfter(setOf("CEN"), setOf("CHA"), millis("2026-11-11T13:20:00Z"), limit = 3)
        assertEquals(listOf("T1.N.2430", "M1.N.2440", "T1.N.2450"), deps.map { it.tripId })
        assertTrue(deps.all { it.serviceDate == "20261111" })
        assertEquals(24 * 3600 + 1800, deps[0].departureSecs)
        assertEquals(millis("2026-11-11T13:30:00Z"), deps[0].departureMillis)
        assertEquals(millis("2026-11-11T14:10:00Z"), deps[2].arrivalMillis) // 25:10:00 = 01:10 Thursday
    }

    @Test
    fun daylightSavingStartDay() {
        // Sunday 4 Oct 2026: clocks go from 02:00 AEST to 03:00 AEDT. GTFS times count from
        // noon minus 12 h (= 23:00 AEST on the 3rd), so 01:30 is 00:30 AEST and 03:30 is 03:30 AEDT.
        val tt = pack(LocalDate.of(2026, 10, 3))
        val deps = tt.departures(q("CEN", "CHA", "20261004"))
        assertEquals(listOf("T1.N.WE.0130", "T1.N.WE.0330", "T1.N.WE.0900", "M1.N.WE.0930"), deps.map { it.tripId })
        assertEquals(millis("2026-10-03T14:30:00Z"), deps[0].departureMillis)
        assertEquals(millis("2026-10-03T16:30:00Z"), deps[1].departureMillis)
        assertEquals(millis("2026-10-03T22:00:00Z"), deps[2].departureMillis)
    }

    @Test
    fun daylightSavingEndDay() {
        // Sunday 5 Apr 2026: clocks go from 03:00 AEDT back to 02:00 AEST. Noon minus 12 h is
        // 01:00 AEDT, so 01:30 is 02:30 AEDT and 09:00 is 09:00 AEST.
        val tt = pack(LocalDate.of(2026, 4, 4))
        val deps = tt.departures(q("CEN", "CHA", "20260405"))
        assertEquals(listOf("T1.N.WE.0130", "T1.N.WE.0330", "T1.N.WE.0900", "M1.N.WE.0930"), deps.map { it.tripId })
        assertEquals(millis("2026-04-04T15:30:00Z"), deps[0].departureMillis)
        assertEquals(millis("2026-04-04T23:00:00Z"), deps[2].departureMillis)
    }

    @Test
    fun calendarExceptionOnChristmasDay() {
        val tt = pack()
        // Friday 25 Dec 2026 runs the weekend timetable instead of the weekday one.
        val deps = tt.departures(q("CEN", "CHA", "20261225"))
        assertEquals(listOf("T1.N.WE.0130", "T1.N.WE.0330", "T1.N.WE.0900", "M1.N.WE.0930"), deps.map { it.tripId })
        assertFalse(tt.runsOn("T1.N.0700", "20261225"))
        assertTrue(tt.runsOn("T1.N.0700", "20261224"))
        assertTrue(tt.runsOn("T1.N.WE.0900", "20261225"))
    }

    @Test
    fun interpolatedTimesAndTripDetails() {
        val tt = pack()
        val trip = assertNotNull(tt.trip("T1.N.1700"))
        assertEquals(17 * 3600 + 7 * 60, trip.stopTimes[2].arrivalSecs)
        assertEquals("SH_T1_N", trip.shapeId)
        assertEquals(0, trip.directionId)
        assertEquals(true, trip.wheelchairAccessible)
        assertEquals("WD", trip.serviceId)
        assertEquals(listOf("CEN_P1", "THL_P1", "WYN_P1", "NSY_P1", "CHA_P1"), trip.stopTimes.map { it.stopId })
        val south = assertNotNull(tt.trip("T1.S.0715"))
        assertEquals(7 * 3600 + 15 * 60, south.stopTimes.first().departureSecs)
        assertEquals(7 * 3600 + 28 * 60 + 30, south.stopTimes[2].arrivalSecs)
    }

    @Test
    fun stopsRoutesShapesAndTransfers() {
        val tt = pack()
        assertEquals("WYN", tt.searchStops("wynyard").first().id)
        assertEquals("CEN", tt.searchStops("CENTRAL").first().id)
        assertEquals(listOf("CEN", "CEN_P1", "CEN_P2", "CEN_M1", "CEN_M2").sorted(), tt.stopsAtStation("CEN").map { it.id }.sorted())
        assertEquals(setOf(Mode.TRAIN, Mode.METRO), tt.stop("CEN")?.modes)
        assertEquals(setOf(Mode.METRO), tt.stop("MPL")?.modes)
        assertEquals("1", tt.stop("CEN_P1")?.platformCode)
        assertEquals(false, tt.stop("THL_P2")?.wheelchairAccessible)
        assertEquals(true, tt.stop("THL_P1")?.wheelchairAccessible)
        assertEquals(listOf("T1", "M1"), tt.routesAtStop("CEN").map { it.id })
        assertEquals(listOf("M1"), tt.routesAtStop("MPL").map { it.id })
        assertEquals("F99D1C", tt.route("T1")?.color)
        assertEquals("ST", tt.route("T1")?.agencyId)
        val shape = assertNotNull(tt.shape("SH_T1_N"))
        assertTrue(shape.size in 2 until 15, "simplified shape has ${shape.size} points")
        assertEquals(LatLng(-33.8831, 151.2064), shape.first())
        assertEquals(listOf("CEN_M1"), tt.transfers("CEN_P1").map { it.toStopId })
        assertEquals(240, tt.transfers("CEN_P1").single().minTransferSecs)
        val near = tt.stopsNear(LatLng(-33.8832, 151.2063), 50.0)
        assertTrue(near.isNotEmpty() && near.all { it.id.startsWith("CEN") })
    }
}
