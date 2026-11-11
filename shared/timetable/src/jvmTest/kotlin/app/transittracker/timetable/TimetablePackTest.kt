package app.transittracker.timetable

import app.transittracker.model.DepartureQuery
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.ServiceTime
import app.transittracker.model.departuresAfter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises [TimetablePack] and [CompositeTimetable] over small hand-built packs. */
class TimetablePackTest {
    private val dir: Path = Files.createTempDirectory("tt-pack-test")
    private val opened = ArrayList<AutoCloseable>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        dir.toFile().deleteRecursively()
    }

    private fun open(content: PackContent, file: String = "pack.sqlite"): TimetablePack {
        val path = dir.resolve(file)
        JvmTimetables.write(path, content)
        return JvmTimetables.open(path).also { opened += it }
    }

    /** Weekdays of the two weeks starting Monday 2026-11-09. */
    private fun weekdays() = BooleanArray(14) { it % 7 < 5 }

    private fun content(version: String = "v1", extraRoute: Boolean = false): PackContent {
        val stops = listOf(
            PackStop(10, "X", null, "Xavier Station", -33.88, 151.20, null, 1, setOf(Mode.TRAIN, Mode.METRO), 1),
            PackStop(11, "X1", 10, "Xavier Station Platform 1", -33.8801, 151.2001, "1", 0, setOf(Mode.TRAIN), 1),
            PackStop(12, "X2", 10, "Xavier Station Platform 2", -33.8802, 151.2002, "2", 0, setOf(Mode.METRO), 1),
            PackStop(20, "Y", null, "Yarra Street", -33.87, 151.21, null, 0, setOf(Mode.TRAIN, Mode.METRO), 0),
            PackStop(30, "Z", null, "Zetland", -33.86, 151.22, null, 0, setOf(Mode.TRAIN, Mode.METRO), 2),
        )
        val routes = buildList {
            add(PackRoute(1, "R1", "A1", "T1", "Train line", 2, Mode.TRAIN, "F99D1C", "000000", 1))
            add(PackRoute(2, "R2", "A2", "M1", "Metro line", 401, Mode.METRO, null, null, 2))
            if (extraRoute) add(PackRoute(3, "R3", "A3", "L1", "Light rail", 900, Mode.LIGHT_RAIL, null, null, 3))
        }
        val patterns = buildList {
            add(Pattern(1, 1, intArrayOf(11, 20, 30)))
            add(Pattern(2, 2, intArrayOf(12, 20, 30), intArrayOf(10, 20, 30), intArrayOf(0, 0, 0), intArrayOf(0, 1, 0)))
            if (extraRoute) add(Pattern(3, 3, intArrayOf(11, 30)))
        }
        val profile = TimeProfile(1, intArrayOf(0, 300, 600), intArrayOf(0, 330, 600))
        val profile2 = TimeProfile(2, intArrayOf(0, 900), intArrayOf(0, 900))
        val trips = buildList {
            add(PackTrip(1, "T1", 1, 1, 8 * 3600, 1, "Zetland", 0, 1, wheelchair = 1))
            add(PackTrip(2, "T2", 1, 1, 9 * 3600, 1, "Zetland", 0, 1))
            add(PackTrip(3, "T3", 2, 1, 8 * 3600 + 60, 1, "Zetland", 0, null))
            add(PackTrip(4, "T4", 1, 1, 24 * 3600 + 1800, 1, "Zetland", 0, 1))
            add(PackTrip(5, "T5", 1, 1, 10 * 3600, 2, "Zetland", 0, 1))
            if (extraRoute) add(PackTrip(6, "L1", 3, 2, 8 * 3600 + 30, 1, "Zetland", null, null))
        }
        val shape = listOf(LatLng(-33.8801, 151.2001), LatLng(-33.87, 151.21), LatLng(-33.86, 151.22))
        return PackContent(
            meta = mapOf(PackMeta.NAME to "test", PackMeta.VERSION to version),
            stops = stops,
            routes = routes,
            patterns = patterns,
            profiles = if (extraRoute) listOf(profile, profile2) else listOf(profile),
            services = listOf(
                PackService(1, "WD", "20261109", weekdays()),
                PackService(2, "SUN", "20261115", BooleanArray(1) { true }),
            ),
            trips = trips,
            transfers = listOf(PackTransfer(11, 12, 2, 180)),
            shapes = listOf(PackShape(1, "SH1", Polyline.encode(shape))),
        )
    }

    private fun query(from: String, to: String, date: String = "20261111", fromSecs: Int = 0, routes: Set<String>? = null) =
        DepartureQuery(setOf(from), setOf(to), date, fromSecs, toSecs = 30 * 3600, routeIds = routes)

    @Test
    fun departuresExpandStationsAndSortByTime() {
        val tt = open(content())
        val deps = tt.departures(query("X", "Z"))
        assertEquals(listOf("T1", "T3", "T2", "T4"), deps.map { it.tripId })
        val first = deps.first()
        assertEquals("X1", first.originStopId)
        assertEquals("Z", first.destinationStopId)
        assertEquals(1, first.originStopSequence)
        assertEquals(3, first.destinationStopSequence)
        assertEquals(8 * 3600, first.departureSecs)
        assertEquals(8 * 3600 + 600, first.arrivalSecs)
        assertEquals(Instant.parse("2026-11-10T21:00:00Z").toEpochMilli(), first.departureMillis)
        assertEquals("R1", first.routeId)
        val metro = deps[1]
        assertEquals(10, metro.originStopSequence)
        assertEquals(30, metro.destinationStopSequence)
    }

    @Test
    fun destinationMustFollowOriginAndAllowDropOff() {
        val tt = open(content())
        assertTrue(tt.departures(query("Z", "X")).isEmpty())
        // T3 does not set down at Y.
        assertEquals(listOf("T1", "T2", "T4"), tt.departures(query("X", "Y")).map { it.tripId })
        assertEquals(listOf("T1", "T3", "T2", "T4"), tt.departures(query("Y", "Z")).map { it.tripId })
    }

    @Test
    fun routeFilterWindowLimitAndCalendar() {
        val tt = open(content())
        assertEquals(listOf("T3"), tt.departures(query("X", "Z", routes = setOf("R2"))).map { it.tripId })
        assertTrue(tt.departures(query("X", "Z", routes = setOf("NOPE"))).isEmpty())
        val window = tt.departures(DepartureQuery(setOf("X"), setOf("Z"), "20261111", 8 * 3600 + 1800, 9 * 3600 + 1800))
        assertEquals(listOf("T2"), window.map { it.tripId })
        assertEquals(2, tt.departures(query("X", "Z").copy(limit = 2)).size)
        assertEquals(listOf("T5"), tt.departures(query("X", "Z", date = "20261115")).map { it.tripId })
        assertTrue(tt.departures(query("X", "Z", date = "20261114")).isEmpty())
        assertTrue(tt.departures(query("X", "Z", date = "20270101")).isEmpty())
    }

    @Test
    fun afterMidnightTripsComeFromThePreviousServiceDay() {
        val tt = open(content())
        val after = Instant.parse("2026-11-11T13:10:00Z").toEpochMilli() // 00:10 Thu 12 Nov in Sydney
        val deps = tt.departuresAfter(setOf("X"), setOf("Z"), after, limit = 1)
        assertEquals("T4", deps.single().tripId)
        assertEquals("20261111", deps.single().serviceDate)
        assertEquals(Instant.parse("2026-11-11T13:30:00Z").toEpochMilli(), deps.single().departureMillis)
        assertEquals(ServiceTime.toEpochMillis("20261111", 24 * 3600 + 2400), deps.single().arrivalMillis)
    }

    @Test
    fun tripLookupAndCalendar() {
        val tt = open(content())
        val t3 = assertNotNull(tt.trip("T3"))
        assertEquals("R2", t3.routeId)
        assertEquals("WD", t3.serviceId)
        assertNull(t3.shapeId)
        assertEquals(listOf(10, 20, 30), t3.stopTimes.map { it.stopSequence })
        assertEquals(listOf("X2", "Y", "Z"), t3.stopTimes.map { it.stopId })
        assertEquals(listOf(true, false, true), t3.stopTimes.map { it.dropOff })
        assertEquals(8 * 3600 + 60 + 330, t3.stopTimes[1].departureSecs)
        val t1 = assertNotNull(tt.trip("T1"))
        assertEquals("SH1", t1.shapeId)
        assertEquals(true, t1.wheelchairAccessible)
        assertNull(tt.trip("missing"))
        assertTrue(tt.runsOn("T1", "20261111"))
        assertFalse(tt.runsOn("T1", "20261115"))
        assertTrue(tt.runsOn("T5", "20261115"))
        assertFalse(tt.runsOn("missing", "20261111"))
    }

    @Test
    fun stopsRoutesShapesAndTransfers() {
        val tt = open(content())
        assertEquals("v1", tt.version)
        val x = assertNotNull(tt.stop("X"))
        assertEquals(setOf(Mode.TRAIN, Mode.METRO), x.modes)
        assertEquals(true, x.wheelchairAccessible)
        assertEquals("X", tt.stop("X1")?.parentId)
        assertEquals(false, tt.stop("Z")?.wheelchairAccessible)
        assertNull(tt.stop("Y")?.wheelchairAccessible)
        assertEquals(listOf("X", "X1", "X2"), tt.stopsAtStation("X").map { it.id })
        assertEquals(listOf("Y"), tt.stopsAtStation("Y").map { it.id })
        assertEquals(listOf("X", "X1", "X2"), tt.searchStops("xavier").map { it.id })
        assertEquals(listOf("X1"), tt.searchStops("platform 1").map { it.id })
        assertTrue(tt.searchStops("100%").isEmpty())
        assertEquals(listOf("X", "X1", "X2"), tt.stopsNear(LatLng(-33.88, 151.20), 100.0).map { it.id })
        assertEquals(listOf("R1", "R2"), tt.routes().map { it.id })
        assertEquals(Mode.METRO, tt.route("R2")?.mode)
        assertEquals(listOf("R1", "R2"), tt.routesAtStop("X").map { it.id })
        assertEquals(listOf("R1"), tt.routesAtStop("X1").map { it.id })
        assertEquals(3, tt.shape("SH1")?.size)
        assertNull(tt.shape("nope"))
        assertEquals(180, tt.transfers("X1").single().minTransferSecs)
        assertEquals("X2", tt.transfers("X1").single().toStopId)
    }

    @Test
    fun writingIsDeterministic() {
        JvmTimetables.write(dir.resolve("a.sqlite"), content())
        JvmTimetables.write(dir.resolve("b.sqlite"), content())
        assertContentEquals(Files.readAllBytes(dir.resolve("a.sqlite")), Files.readAllBytes(dir.resolve("b.sqlite")))
    }

    @Test
    fun compositeMergesPacks() {
        val a = open(content("v1"), "a.sqlite")
        val b = open(content("v2", extraRoute = true), "b.sqlite")
        val composite = CompositeTimetable(listOf(a, b))
        assertEquals("v1+v2", composite.version)
        val deps = composite.departures(query("X", "Z"))
        assertEquals(listOf("T1", "L1", "T3", "T2", "T4"), deps.map { it.tripId })
        assertEquals(listOf("R1", "R2", "R3"), composite.routes().map { it.id })
        assertEquals(listOf("X", "X1", "X2"), composite.stopsAtStation("X").map { it.id })
        assertEquals(listOf("R1", "R2", "R3"), composite.routesAtStop("X").map { it.id })
        assertNotNull(composite.trip("L1"))
        assertTrue(composite.runsOn("L1", "20261111"))
        assertEquals(3, composite.searchStops("xavier").size)
    }

    @Test
    fun concurrentReadersShareOnePack() {
        val tt = open(content())
        val pool = Executors.newFixedThreadPool(8)
        val futures = (1..200).map {
            pool.submit<Int> { tt.departures(query("X", "Z")).size + (tt.trip("T1")?.stopTimes?.size ?: 0) }
        }
        futures.forEach { assertEquals(7, it.get()) }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
    }
}
