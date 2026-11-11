package app.transittracker.server.pipeline

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Merge policy tests. Station values are modelled on the real TfNSW Sydney Trains and Metro
 * static feeds (Epping 212110 with different coordinate precision; Central 200060 placed about
 * 60 m apart; Metro's numeric service and shape ids).
 */
class GtfsMergerTest {
    // ---- tiny feed builder ---------------------------------------------------------------------

    private val start = LocalDate.of(2026, 1, 1)
    private val end = LocalDate.of(2027, 12, 31)
    private val weekdays = listOf(true, true, true, true, true, false, false)
    private val weekends = listOf(false, false, false, false, false, true, true)

    private fun station(id: String, name: String, lat: Double, lon: Double, wheelchair: Int = 1) =
        GtfsStop(id, name, lat, lon, locationType = 1, parentStation = null, platformCode = null, wheelchairBoarding = wheelchair)

    private fun platform(id: String, name: String, lat: Double, lon: Double, parent: String?, code: String? = null) =
        GtfsStop(id, name, lat, lon, locationType = 0, parentStation = parent, platformCode = code, wheelchairBoarding = 0)

    private fun trip(id: String, route: String, service: String, shape: String? = null) =
        GtfsTrip(id, route, service, headsign = "Somewhere", directionId = 0, shapeId = shape, wheelchairAccessible = 1, bikesAllowed = 0)

    private fun times(tripId: String, vararg stops: Pair<String, Int>) =
        tripId to stops.mapIndexed { i, (stop, t) -> GtfsStopTime(tripId, stop, i + 1, t, t, 0, 0) }

    private fun feed(
        name: String,
        agencies: List<GtfsAgency> = emptyList(),
        stops: List<GtfsStop> = emptyList(),
        routes: List<GtfsRoute> = emptyList(),
        trips: List<GtfsTrip> = emptyList(),
        stopTimes: Map<String, List<GtfsStopTime>> = emptyMap(),
        calendars: List<GtfsCalendar> = emptyList(),
        calendarDates: List<GtfsCalendarDate> = emptyList(),
        shapes: Map<String, List<GtfsShapePoint>> = emptyMap(),
        transfers: List<GtfsTransfer> = emptyList(),
    ) = GtfsFeed(listOf(name to null), agencies, stops, routes, trips, stopTimes, calendars, calendarDates, shapes, transfers)

    // Real values from the two TfNSW feeds (issue report).
    private val eppingTrains = station("212110", "Epping Station", -33.7728414535, 151.082176647)
    private val eppingMetro = station("212110", "Epping Station", -33.772841, 151.082177)

    private fun trains(vararg extraStops: GtfsStop, service: GtfsCalendar = GtfsCalendar("1", weekdays, start, end)) = feed(
        "sydneytrains.zip",
        agencies = listOf(GtfsAgency("SydneyTrains", "Sydney Trains", "https://transportnsw.info", "Australia/Sydney")),
        stops = listOf(eppingTrains, platform("2121131", "Epping Station Platform 1", -33.7726, 151.0820, "212110", "1")) + extraStops,
        routes = listOf(GtfsRoute("T9", "SydneyTrains", "T9", "Northern Line", 2, "D11F2F", "FFFFFF", null)),
        trips = listOf(trip("T9.1", "T9", service.serviceId, "3722")),
        stopTimes = mapOf(times("T9.1", "2121131" to 8 * 3600, "2121131" to 8 * 3600 + 60)),
        calendars = listOf(service),
        shapes = mapOf("3722" to listOf(GtfsShapePoint(-33.77, 151.08, 1), GtfsShapePoint(-33.78, 151.09, 2))),
    )

    private fun metro(vararg extraStops: GtfsStop, service: GtfsCalendar = GtfsCalendar("1", weekends, start, end)) = feed(
        "metro.zip",
        agencies = listOf(GtfsAgency("SMNW", "Sydney Metro", "http://transportnsw.info", "Australia/Sydney")),
        stops = listOf(eppingMetro, platform("2121201", "Epping Station Platform 3", -33.7731, 151.0824, "212110", "3")) + extraStops,
        routes = listOf(GtfsRoute("SMNW_M1", "SMNW", "M1", "Metro North West & Bankstown Line", 401, "168388", "FFFFFF", null)),
        trips = listOf(trip("0126-001-101-002:1000", "SMNW_M1", service.serviceId, "3722")),
        stopTimes = mapOf(times("0126-001-101-002:1000", "2121201" to 9 * 3600, "2121201" to 9 * 3600 + 120)),
        calendars = listOf(service),
        shapes = mapOf("3722" to listOf(GtfsShapePoint(-33.69, 150.90, 0), GtfsShapePoint(-33.77, 151.08, 1))),
    )

    // ---- stops ---------------------------------------------------------------------------------

    @Test
    fun `real shared records of Sydney Trains v1 and Metro v2 (11 Nov 2026) reconcile`() {
        // Rows as published on 11 Nov 2026: Sydney Trains' stops.txt has no platform_code column and
        // writes "Belmore Station Platform 1"; Metro v2 writes "Belmore Station, Platform 1" with code 1
        // and wheelchair_boarding 1. Central is 9 m apart. The real run had 33 such stops, all reconciled.
        val trainsCentral = GtfsStop("200060", "Central Station", -33.883881632, 151.205829312, 1, null, null, 1)
        val metroCentral = GtfsStop("200060", "Central Station", -33.883848, 151.205738, 1, null, null, 0)
        val trainsBelmore = GtfsStop("219281", "Belmore Station Platform 1", -33.917167, 151.088567, 0, "219210", null, 0)
        val metroBelmore = GtfsStop("219281", "Belmore Station, Platform 1", -33.917167, 151.088567, 0, "219210", "1", 1)
        val belmore = GtfsStop("219210", "Belmore Station", -33.9172179801, 151.088733212, 1, null, null, 1)
        val result = GtfsMerger.mergeWithReport(
            listOf(trains(trainsCentral, belmore, trainsBelmore), metro(metroCentral, belmore.copy(lat = -33.917218, lon = 151.088733, wheelchairBoarding = 0), metroBelmore)),
        )
        val stops = result.feed.stops.associateBy { it.id }
        assertEquals(trainsCentral, stops.getValue("200060"), "the first feed's position and accessibility are kept")
        val platform = stops.getValue("219281")
        assertEquals("Belmore Station Platform 1", platform.name)
        assertEquals("1", platform.platformCode)
        assertEquals(1, platform.wheelchairBoarding)
        assertEquals(setOf("212110", "200060", "219210", "219281"), result.report.reconciled.map { it.id }.toSet())
        assertTrue(result.report.renamed.none { it.kind == "stop" })
    }

    @Test
    fun eppingWithDifferentCoordinatePrecisionMergesIntoOneStation() {
        val result = GtfsMerger.mergeWithReport(listOf(trains(), metro()))
        val epping = result.feed.stops.filter { it.id == "212110" }
        assertEquals(1, epping.size)
        // First feed (pack definition order: Sydney Trains) wins the position.
        assertEquals(-33.7728414535, epping.single().lat)
        assertEquals(151.082176647, epping.single().lon)
        assertEquals("Epping Station", epping.single().name)
        assertEquals(1, epping.single().wheelchairBoarding)
        val note = result.report.reconciled.single { it.kind == "stop" }
        assertEquals("212110", note.id)
        assertTrue("apart" in note.detail, note.detail)
        // Both operators' platforms survive, both pointing at the merged station.
        assertEquals(setOf("2121131", "2121201"), result.feed.stops.filter { it.parentStation == "212110" }.map { it.id }.toSet())
    }

    @Test
    fun inputOrderDecidesWhichPositionIsKept() {
        val result = GtfsMerger.merge(listOf(metro(), trains()))
        assertEquals(-33.772841, result.stops.single { it.id == "212110" }.lat)
    }

    @Test
    fun stationsDescribedAtDifferentPointsOfTheComplexMerge() {
        // Central 200060: Sydney Trains vs Metro coordinates are about 60 m apart.
        val a = trains(station("200060", "Central Station", -33.884084, 151.206292))
        val b = metro(station("200060", "Central Station", -33.883848, 151.205738))
        val central = GtfsMerger.merge(listOf(a, b)).stops.single { it.id == "200060" }
        assertEquals(-33.884084, central.lat)
    }

    @Test
    fun genuinelyDifferentStopsWithTheSameIdFailClearly() {
        val far = GtfsMerger.runCatching {
            merge(listOf(trains(station("200060", "Central Station", -33.884, 151.206)), metro(station("200060", "Central Station", -33.80, 151.18))))
        }.exceptionOrNull() as ConflictingIdException
        assertEquals("stop", far.kind)
        assertEquals("200060", far.id)
        assertTrue("apart (limit 300 m)" in far.conflicts.single().reason, far.message)

        val renamed = assertFailsWith<ConflictingIdException> {
            GtfsMerger.merge(listOf(trains(), metro().let { m -> m.copyStops(m.stops.map { if (it.id == "212110") it.copy(name = "Epping Interchange") else it }) }))
        }
        assertTrue("name 'Epping Station' vs 'Epping Interchange'" in renamed.message!!, renamed.message)

        val typed = assertFailsWith<ConflictingIdException> {
            GtfsMerger.merge(listOf(trains(), metro().let { m -> m.copyStops(m.stops.map { if (it.id == "212110") it.copy(locationType = 0) else it }) }))
        }
        assertTrue("location_type 1 vs 0" in typed.message!!, typed.message)
    }

    @Test
    fun platformsUseATighterDistanceLimitThanStations() {
        val a = trains(platform("P1", "Shared Platform", -33.8000, 151.1000, null))
        val near = metro(platform("P1", "Shared Platform", -33.8003, 151.1000, null)) // ~33 m
        val far = metro(platform("P1", "Shared Platform", -33.8008, 151.1000, null)) // ~89 m
        GtfsMerger.merge(listOf(a, near))
        val e = assertFailsWith<ConflictingIdException> { GtfsMerger.merge(listOf(a, far)) }
        assertTrue("limit 50 m" in e.message!!, e.message)
    }

    @Test
    fun namesMatchIgnoringCaseSpacingAndPunctuation() {
        val a = trains(station("204420", "Sydenham Station", -33.9145, 151.1665))
        val b = metro(station("204420", "SYDENHAM  station.", -33.914339, 151.166555))
        val result = GtfsMerger.mergeWithReport(listOf(a, b))
        assertEquals("Sydenham Station", result.feed.stops.single { it.id == "204420" }.name)
        assertTrue(result.report.reconciled.any { it.id == "204420" && "spelling" in it.detail })
    }

    @Test
    fun optionalFieldsAreFilledAndContradictionsHandled() {
        // wheelchair: unknown yields to known; 1 vs 2 becomes unknown.
        val known = GtfsMerger.merge(listOf(trains(station("206710", "Chatswood Station", -33.797, 151.1808, wheelchair = 0)), metro(station("206710", "Chatswood Station", -33.797134, 151.180821, wheelchair = 1))))
        assertEquals(1, known.stops.single { it.id == "206710" }.wheelchairBoarding)
        val contradictory = GtfsMerger.mergeWithReport(listOf(trains(station("206710", "Chatswood Station", -33.797, 151.1808, wheelchair = 1)), metro(station("206710", "Chatswood Station", -33.797134, 151.180821, wheelchair = 2))))
        assertEquals(0, contradictory.feed.stops.single { it.id == "206710" }.wheelchairBoarding)
        assertTrue(contradictory.report.reconciled.any { "set to unknown" in it.detail })

        // parent_station / platform_code: missing in one feed is filled from the other...
        val filled = GtfsMerger.merge(listOf(trains(platform("P9", "Shared Platform", -33.8, 151.1, null, null)), metro(platform("P9", "Shared Platform", -33.8, 151.1, "212110", "9"))))
        assertEquals("212110", filled.stops.single { it.id == "P9" }.parentStation)
        assertEquals("9", filled.stops.single { it.id == "P9" }.platformCode)
        // ...but two different values conflict.
        val e = assertFailsWith<ConflictingIdException> {
            GtfsMerger.merge(listOf(trains(platform("P9", "Shared Platform", -33.8, 151.1, "212110", "1")), metro(platform("P9", "Shared Platform", -33.8, 151.1, "212110", "2"))))
        }
        assertTrue("platform_code '1' vs '2'" in e.message!!, e.message)
    }

    // ---- feed-internal ids -----------------------------------------------------------------------

    @Test
    fun collidingServiceAndShapeIdsAreRenamedInTheLaterFeed() {
        // Both feeds use service "1" and shape "3722" for different things.
        val result = GtfsMerger.mergeWithReport(listOf(trains(), metro()))
        val feed = result.feed
        val metroTrip = feed.tripsById.getValue("0126-001-101-002:1000")
        assertEquals("1~2", metroTrip.serviceId)
        assertEquals("3722~2", metroTrip.shapeId)
        assertEquals("1", feed.tripsById.getValue("T9.1").serviceId)
        assertEquals("3722", feed.tripsById.getValue("T9.1").shapeId)
        assertEquals(weekdays, feed.calendars.single { it.serviceId == "1" }.days)
        assertEquals(weekends, feed.calendars.single { it.serviceId == "1~2" }.days)
        assertEquals(setOf("3722", "3722~2"), feed.shapes.keys)
        assertEquals(-33.69, feed.shapes.getValue("3722~2").first().lat)
        assertEquals(setOf("service", "shape"), result.report.renamed.map { it.kind }.toSet())
        // Referential integrity holds after the renames.
        val report = ValidationReport.Builder()
        GtfsValidator.validate(feed, report, LocalDate.of(2026, 11, 11))
        assertTrue(!report.build().hasErrors, report.build().summary())
    }

    @Test
    fun identicalServicesAndShapesAreShared() {
        val shared = GtfsCalendar("1", weekdays, start, end)
        val metro = metro(service = shared).let { m -> m.copyShapes(trains().shapes) }
        val result = GtfsMerger.mergeWithReport(listOf(trains(service = shared), metro))
        assertEquals(listOf("1"), result.feed.calendars.map { it.serviceId })
        assertEquals("1", result.feed.tripsById.getValue("0126-001-101-002:1000").serviceId)
        assertEquals("3722", result.feed.tripsById.getValue("0126-001-101-002:1000").shapeId)
        assertEquals(1, result.report.sharedIds["service"])
        assertEquals(1, result.report.sharedIds["shape"])
        assertTrue(result.report.renamed.isEmpty())
    }

    @Test
    fun servicesDifferingOnlyByExceptionsAreDifferent() {
        val cal = GtfsCalendar("1", weekdays, start, end)
        val a = trains(service = cal).let { it.copyCalendarDates(listOf(GtfsCalendarDate("1", LocalDate.of(2026, 12, 25), 2))) }
        val b = metro(service = cal)
        val feed = GtfsMerger.merge(listOf(a, b))
        assertEquals("1~2", feed.tripsById.getValue("0126-001-101-002:1000").serviceId)
        assertEquals(listOf("1"), feed.calendarDates.map { it.serviceId })
    }

    @Test
    fun agenciesWithTheSameIdAreReconciledOrRenamed() {
        val sameAgency = metro().let { m ->
            m.copyAgencies(listOf(GtfsAgency("SydneyTrains", "Sydney Trains", "http://example.invalid", "Australia/Sydney")))
        }
        val reconciled = GtfsMerger.mergeWithReport(listOf(trains(), sameAgency))
        assertEquals(1, reconciled.feed.agencies.size)
        assertEquals("https://transportnsw.info", reconciled.feed.agencies.single().url)

        val otherAgency = metro().let { m ->
            m.copyAgencies(listOf(GtfsAgency("SydneyTrains", "Sydney Metro", "http://transportnsw.info", "Australia/Sydney")))
                .copyRoutes(m.routes.map { it.copy(agencyId = "SydneyTrains") })
        }
        val renamed = GtfsMerger.merge(listOf(trains(), otherAgency))
        assertEquals(listOf("SydneyTrains", "SydneyTrains~2"), renamed.agencies.map { it.id })
        assertEquals("SydneyTrains~2", renamed.routesById.getValue("SMNW_M1").agencyId)
    }

    // ---- routes, trips, transfers ----------------------------------------------------------------

    @Test
    fun routesMustAgreeOnTypeAndNameButNotPresentation() {
        val sameRoute = metro().let { m -> m.copyRoutes(m.routes + trains().routes.single().copy(color = "000000", longName = "North Shore")) }
        val result = GtfsMerger.mergeWithReport(listOf(trains(), sameRoute))
        assertEquals("D11F2F", result.feed.routesById.getValue("T9").color)
        assertTrue(result.report.reconciled.any { it.kind == "route" && "route_color" in it.detail })

        val otherType = metro().let { m -> m.copyRoutes(m.routes + trains().routes.single().copy(type = 401)) }
        val e = assertFailsWith<ConflictingIdException> { GtfsMerger.merge(listOf(trains(), otherType)) }
        assertTrue("route 'T9'" in e.message!! && "route_type 2 vs 401" in e.message!!, e.message)
    }

    @Test
    fun tripsMustBeIdenticalIncludingStopTimes() {
        val t = trains()
        val identical = metro().let { m ->
            m.copyTrips(m.trips + t.trips, m.stopTimes + t.stopTimes).copyRoutes(m.routes + t.routes)
                .copyAgencies(m.agencies + t.agencies)
        }
        // T9.1 uses service "1" and shape "3722", which mean different things in the metro feed,
        // so after renaming the metro copy of T9.1 runs on other days: a real conflict.
        val e = assertFailsWith<ConflictingIdException> { GtfsMerger.merge(listOf(t, identical)) }
        assertEquals("trip", e.kind)
        assertEquals("T9.1", e.id)

        // With the same calendar and shape, an identical trip is shared once.
        val shared = GtfsCalendar("1", weekdays, start, end)
        val a = trains(service = shared)
        val b = metro(service = shared).let { m ->
            m.copyShapes(a.shapes).copyTrips(m.trips + a.trips, m.stopTimes + a.stopTimes).copyRoutes(m.routes + a.routes).copyAgencies(m.agencies + a.agencies)
        }
        val merged = GtfsMerger.mergeWithReport(listOf(a, b))
        assertEquals(1, merged.feed.trips.count { it.id == "T9.1" })
        assertEquals(1, merged.report.sharedIds["trip"])

        // Same trip id, different stop times: conflict.
        val retimed = b.copyTrips(b.trips, b.stopTimes + times("T9.1", "2121131" to 8 * 3600 + 30, "2121131" to 8 * 3600 + 90))
        val e2 = assertFailsWith<ConflictingIdException> { GtfsMerger.merge(listOf(a, retimed)) }
        assertTrue("stop times differ" in e2.message!!, e2.message)
    }

    @Test
    fun conflictingTransfersKeepTheMoreRestrictiveRecord() {
        val a = trains().copyTransfers(listOf(GtfsTransfer("2121131", "2121201", 2, 180)))
        val b = metro().copyTransfers(listOf(GtfsTransfer("2121131", "2121201", 2, 300)))
        assertEquals(300, GtfsMerger.merge(listOf(a, b)).transfers.single().minTransferTime)
        val c = metro().copyTransfers(listOf(GtfsTransfer("2121131", "2121201", 3, null)))
        assertEquals(3, GtfsMerger.merge(listOf(a, c)).transfers.single().transferType)
    }

    // ---- reporting and determinism -------------------------------------------------------------------

    @Test
    fun everyConflictIsReportedAtOnce() {
        val a = trains(station("200060", "Central Station", -33.884, 151.206))
        val b = metro(station("200060", "Central Station", -33.70, 151.00)).let { m ->
            m.copyRoutes(m.routes + trains().routes.single().copy(type = 3))
                .copyStops(m.stops.map { if (it.id == "212110") it.copy(locationType = 0) else it })
        }
        val e = assertFailsWith<ConflictingIdException> { GtfsMerger.merge(listOf(a, b)) }
        assertEquals(setOf("stop 212110", "stop 200060", "route T9"), e.conflicts.map { "${it.kind} ${it.id}" }.toSet())
        assertTrue(e.conflicts.all { it.sources == listOf("sydneytrains.zip", "metro.zip") })
    }

    @Test
    fun mergingIsDeterministic() {
        val first = GtfsMerger.mergeWithReport(listOf(trains(), metro()))
        val second = GtfsMerger.mergeWithReport(listOf(trains(), metro()))
        assertEquals(first.report, second.report)
        assertEquals(first.feed.stops, second.feed.stops)
        assertEquals(first.feed.trips, second.feed.trips)
        assertEquals(first.feed.calendars, second.feed.calendars)
        assertEquals(first.feed.shapes, second.feed.shapes)
        assertEquals(first.feed.agencies, second.feed.agencies)
        assertNotNull(first.feed.stopTimes["0126-001-101-002:1000"])
    }

    // ---- helpers to vary one part of a feed ----------------------------------------------------------

    private fun GtfsFeed.copy(
        agencies: List<GtfsAgency> = this.agencies,
        stops: List<GtfsStop> = this.stops,
        routes: List<GtfsRoute> = this.routes,
        trips: List<GtfsTrip> = this.trips,
        stopTimes: Map<String, List<GtfsStopTime>> = this.stopTimes,
        calendarDates: List<GtfsCalendarDate> = this.calendarDates,
        shapes: Map<String, List<GtfsShapePoint>> = this.shapes,
        transfers: List<GtfsTransfer> = this.transfers,
    ) = GtfsFeed(sources, agencies, stops, routes, trips, stopTimes, calendars, calendarDates, shapes, transfers)

    private fun GtfsFeed.copyStops(stops: List<GtfsStop>) = copy(stops = stops)
    private fun GtfsFeed.copyRoutes(routes: List<GtfsRoute>) = copy(routes = routes)
    private fun GtfsFeed.copyAgencies(agencies: List<GtfsAgency>) = copy(agencies = agencies)
    private fun GtfsFeed.copyTrips(trips: List<GtfsTrip>, stopTimes: Map<String, List<GtfsStopTime>>) = copy(trips = trips, stopTimes = stopTimes)
    private fun GtfsFeed.copyCalendarDates(dates: List<GtfsCalendarDate>) = copy(calendarDates = dates)
    private fun GtfsFeed.copyShapes(shapes: Map<String, List<GtfsShapePoint>>) = copy(shapes = shapes)
    private fun GtfsFeed.copyTransfers(transfers: List<GtfsTransfer>) = copy(transfers = transfers)
}
