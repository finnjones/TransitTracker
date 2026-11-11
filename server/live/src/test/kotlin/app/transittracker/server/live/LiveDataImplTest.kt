package app.transittracker.server.live

import app.transittracker.model.AlertSeverity
import app.transittracker.model.FeedHealth
import app.transittracker.model.Mode
import app.transittracker.model.Occupancy
import app.transittracker.model.Timetable
import app.transittracker.model.TimeRange
import app.transittracker.model.TripStatus
import app.transittracker.server.spi.DemandKind
import app.transittracker.server.spi.ServerConfig
import app.transittracker.server.spi.TripChangeBatch
import com.google.transit.realtime.CarriageDescriptor
import com.google.transit.realtime.EntitySelector
import com.google.transit.realtime.FeedEntity
import com.google.transit.realtime.Position
import com.google.transit.realtime.TranslatedString
import com.google.transit.realtime.TripDescriptor
import com.google.transit.realtime.VehicleDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.google.transit.realtime.Alert as GtfsAlert
import com.google.transit.realtime.VehiclePosition as GtfsVehicle

class LiveDataImplTest {
    private val start = at(DAY, hms(7, 55))
    private val timetable = FakeTimetable()

    /** What the fake TfNSW currently publishes, per feed + kind. */
    private val published = mutableMapOf<Pair<String, FeedKind>, List<FeedEntity>>()

    private fun TestScope.fetcher(): FakeFetcher {
        val clock = virtualClock(start)
        return FakeFetcher { feed, kind ->
            val entities = published[feed.id to kind]
            if (entities == null) FetchResult.HttpError(404) else FetchResult.Success(feedMessage(clock.nowMillis(), entities))
        }
    }

    private fun TestScope.live(
        fetcher: FeedFetcher,
        config: ServerConfig = testConfig(),
        tt: Timetable? = timetable,
        recorder: FeedRecorder? = null,
    ) = LiveDataImpl(
        config = config,
        timetable = MutableStateFlow(tt),
        fetcher = fetcher,
        clock = virtualClock(start),
        recorder = recorder,
        rateLimiter = virtualRateLimiter(100),
    )

    private fun publishTrains(vararg updates: com.google.transit.realtime.TripUpdate) {
        published[TRAINS.id to FeedKind.TRIP_UPDATES] = updates.map { tuEntity(it) }
        published.putIfAbsent(TRAINS.id to FeedKind.VEHICLES, emptyList())
        published.putIfAbsent(TRAINS.id to FeedKind.ALERTS, emptyList())
    }

    private fun vehicle(
        id: String,
        tripId: String?,
        routeId: String? = "T1",
        timestampMs: Long,
        occupancy: GtfsVehicle.OccupancyStatus? = null,
        cars: Int = 0,
        startDate: String? = DAY,
    ) = FeedEntity(
        id = id,
        vehicle = GtfsVehicle(
            trip = TripDescriptor(trip_id = tripId, start_date = startDate, route_id = routeId),
            vehicle = VehicleDescriptor(id = id, label = "Set $id"),
            position = Position(latitude = -33.88f, longitude = 151.2f, bearing = 90f, speed = 12f),
            timestamp = timestampMs / 1000,
            occupancy_status = occupancy,
            consist = List(cars) { CarriageDescriptor(position_in_consist = it, occupancy_status = CarriageDescriptor.OccupancyStatus.FULL) },
        ),
    )

    private fun text(vararg translations: Pair<String, String?>) =
        TranslatedString(translations.map { (text, language) -> TranslatedString.Translation(text = text, language = language) })

    private fun alert(
        id: String,
        headline: String = "Trackwork",
        effect: GtfsAlert.Effect? = GtfsAlert.Effect.REDUCED_SERVICE,
        severity: GtfsAlert.SeverityLevel? = null,
        entities: List<EntitySelector> = listOf(EntitySelector(route_id = "T1")),
    ) = FeedEntity(
        id = id,
        alert = GtfsAlert(
            header_text = text(headline to "en"),
            description_text = text("Buses replace trains" to "en", "Des bus" to "fr"),
            effect = effect,
            cause = GtfsAlert.Cause.MAINTENANCE,
            severity_level = severity,
            informed_entity = entities,
            active_period = listOf(com.google.transit.realtime.TimeRange(start = start / 1000, end = start / 1000 + 3600)),
        ),
    )

    // ---- realtime ----

    @Test
    fun `realtime serves matched trips on the requested routes`() = runTest {
        publishTrains(
            tripUpdate("T1-A", stus = listOf(stu(seq = 2, depDelay = 120)), vehicleId = "A1"),
            tripUpdate("T1-B", relationship = TripDescriptor.ScheduleRelationship.CANCELED),
            tripUpdate("UNKNOWN-TRIP", stus = listOf(stu(seq = 1, depDelay = 0))),
        )
        published[TRAINS.id to FeedKind.VEHICLES] = listOf(
            vehicle("A1", "T1-A", timestampMs = start, occupancy = GtfsVehicle.OccupancyStatus.FEW_SEATS_AVAILABLE, cars = 8),
        )
        val fetcher = fetcher()
        val live = live(fetcher)

        val response = live.realtime(setOf("T1"))
        assertEquals(start, response.serverTimeMs)
        assertEquals(30, response.pollIntervalS)
        assertEquals(15, response.trackerPollIntervalS)
        assertEquals("test-1", response.timetableVersion)
        assertEquals(listOf(FeedHealth("sydneytrains", Mode.TRAIN, dataTimeMs = start, stale = false, ok = true)), response.feeds)
        assertEquals(listOf("T1-A", "T1-B"), response.trips.map { it.tripId })
        val a = response.trips[0]
        assertEquals(Occupancy.FEW_SEATS_AVAILABLE, a.occupancy)
        assertEquals(8, a.carCount)
        assertEquals("A1", a.vehicleId)
        assertEquals(120, a.stops.first { it.stopSequence == 2 }.departureDelayS)
        assertEquals(TripStatus.CANCELLED, response.trips[1].status)
        assertNotNull(response.alertsEtag)
        assertEquals(0, fetcher.calls(METRO, FeedKind.TRIP_UPDATES), "metro feed not needed for T1")

        val status = live.status().feeds.single { it.feedId == "sydneytrains" && it.kind == "tripupdates" }
        assertEquals(2, status.matchedTrips)
        assertEquals(1, status.unmatchedTrips)

        // Unknown routes are ignored.
        assertTrue(live.realtime(setOf("NOT-A-ROUTE")).let { it.trips.isEmpty() && it.feeds.isEmpty() })
    }

    @Test
    fun `no timetable means empty responses and no fetches`() = runTest {
        publishTrains(tripUpdate("T1-A"))
        val fetcher = fetcher()
        val live = live(fetcher, tt = null)
        val response = live.realtime(setOf("T1"))
        assertTrue(response.trips.isEmpty())
        assertEquals("", response.timetableVersion)
        assertTrue(live.trip("T1-A", DAY).trips.isEmpty())
        assertTrue(live.vehicles(setOf("T1")).vehicles.isEmpty())
        assertEquals(0, fetcher.totalCalls())
    }

    @Test
    fun `trip returns one service including added trips`() = runTest {
        publishTrains(
            tripUpdate("T1-A", stus = listOf(stu(seq = 2, depDelay = 60))),
            tripUpdate(
                "ADDED-9", relationship = TripDescriptor.ScheduleRelationship.ADDED, routeId = "T1",
                stus = listOf(stu(seq = 1, stopId = "CEN1", depTimeMs = start + 600_000), stu(seq = 2, stopId = "STR1", arrTimeMs = start + 1_500_000)),
            ),
        )
        val live = live(fetcher())
        val one = live.trip("T1-A", DAY)
        assertEquals(listOf("T1-A"), one.trips.map { it.tripId })
        assertTrue(live.trip("T1-A", NEXT_DAY).trips.isEmpty())

        val added = live.trip("ADDED-9", DAY).trips.single()
        assertEquals(TripStatus.ADDED, added.status)
        assertEquals("Strathfield", added.headsign)
        assertEquals(2, added.addedStops.size)
    }

    @Test
    fun `old trip updates are stale and failing feeds go stale`() = runTest {
        publishTrains(
            tripUpdate("T1-A", timestampMs = start - 4 * 60_000),
            tripUpdate("T1-B", timestampMs = start - 30_000),
        )
        val fetcher = fetcher()
        val live = live(fetcher)
        val first = live.realtime(setOf("T1"))
        assertEquals(mapOf("T1-A" to true, "T1-B" to false), first.trips.associate { it.tripId to it.stale })
        assertFalse(first.feeds.single().stale)

        // The feed starts failing: after 2x the minimum age the cached data is served marked stale.
        published.remove(TRAINS.id to FeedKind.TRIP_UPDATES)
        advanceTimeBy(31_000)
        val failing = live.realtime(setOf("T1"))
        assertEquals(2, fetcher.calls(TRAINS, FeedKind.TRIP_UPDATES))
        assertTrue(failing.feeds.single().stale)
        assertFalse(failing.feeds.single().ok)
        assertEquals(listOf(true, true), failing.trips.map { it.stale })
        assertTrue(live.cachedTrip("T1-B", DAY)!!.stale.not(), "WATCH staleness allows 60 s")
        advanceTimeBy(30_000)
        assertTrue(live.cachedTrip("T1-B", DAY)!!.stale)
    }

    @Test
    fun `unchanged header timestamp skips reprocessing`() = runTest {
        var delay = 60
        val fetcher = FakeFetcher { _, kind ->
            val entities = if (kind == FeedKind.TRIP_UPDATES) listOf(tuEntity(tripUpdate("T1-A", stus = listOf(stu(seq = 2, depDelay = delay))))) else emptyList()
            FetchResult.Success(feedMessage(start, entities)) // header time never advances
        }
        val live = live(fetcher)
        live.realtime(setOf("T1"))
        delay = 300
        advanceTimeBy(16_000)
        val second = live.realtime(setOf("T1"))
        assertEquals(2, fetcher.calls(TRAINS, FeedKind.TRIP_UPDATES))
        assertEquals(60, second.trips.single().stops.first { it.stopSequence == 2 }.departureDelayS)
    }

    // ---- change notifications ----

    @Test
    fun `tripChanges emits only changed trips and trips that became stale`() = runTest {
        val batches = mutableListOf<TripChangeBatch>()
        val live = live(fetcher())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { live.tripChanges.collect { batches += it } }

        publishTrains(
            tripUpdate("T1-A", stus = listOf(stu(seq = 2, depDelay = 60))),
            tripUpdate("T1-B", stus = listOf(stu(seq = 2, depDelay = 0))),
        )
        live.realtime(setOf("T1"))
        assertEquals(1, batches.size)
        assertEquals(setOf("T1-A", "T1-B"), batches[0].changed.map { it.tripId }.toSet())
        assertFalse(batches[0].stale)
        assertEquals("sydneytrains", batches[0].feedId)

        // Same content, newer feed: nothing changed.
        advanceTimeBy(16_000)
        live.realtime(setOf("T1"))
        assertEquals(1, batches.size)

        // One trip's delay changes.
        publishTrains(
            tripUpdate("T1-A", stus = listOf(stu(seq = 2, depDelay = 360))),
            tripUpdate("T1-B", stus = listOf(stu(seq = 2, depDelay = 0))),
        )
        advanceTimeBy(16_000)
        live.realtime(setOf("T1"))
        assertEquals(2, batches.size)
        assertEquals(listOf("T1-A"), batches[1].changed.map { it.tripId })
        assertEquals(360, batches[1].changed.single().stops.first { it.stopSequence == 2 }.departureDelayS)

        // No more fetches: once the data is older than 2x the WATCH minimum age every trip goes stale.
        advanceTimeBy(61_000)
        live.tick()
        assertEquals(3, batches.size)
        assertTrue(batches[2].stale)
        assertEquals(setOf("T1-A", "T1-B"), batches[2].changed.map { it.tripId }.toSet())
        assertTrue(batches[2].changed.all { it.stale })
        live.tick()
        assertEquals(3, batches.size, "staleness is reported once")
    }

    @Test
    fun `keepWarm refreshes watched feeds at the WATCH minimum age until it expires`() = runTest {
        publishTrains(tripUpdate("T1-A"))
        val fetcher = fetcher()
        val live = live(fetcher)
        live.keepWarm("watch-1", setOf("T1"), DemandKind.WATCH, untilMillis = start + 120_000)
        live.start(backgroundScope)
        runCurrent()
        assertEquals(1, fetcher.calls(TRAINS, FeedKind.TRIP_UPDATES))
        assertEquals(1, fetcher.calls(TRAINS, FeedKind.ALERTS))
        assertEquals(0, fetcher.calls(TRAINS, FeedKind.VEHICLES))
        assertEquals(0, fetcher.calls(METRO, FeedKind.TRIP_UPDATES))

        advanceTimeBy(29_500)
        assertEquals(1, fetcher.calls(TRAINS, FeedKind.TRIP_UPDATES))
        advanceTimeBy(1_000)
        assertEquals(2, fetcher.calls(TRAINS, FeedKind.TRIP_UPDATES))
        advanceTimeBy(60_000)
        assertEquals(4, fetcher.calls(TRAINS, FeedKind.TRIP_UPDATES)) // t = 60, 90
        assertEquals(2, fetcher.calls(TRAINS, FeedKind.ALERTS)) // t = 0, 60

        // Expires at t = 120 s: no more calls.
        advanceTimeBy(300_000)
        assertEquals(4, fetcher.calls(TRAINS, FeedKind.TRIP_UPDATES))

        // Re-registering resumes; a past untilMillis removes the registration.
        live.keepWarm("watch-1", setOf("T1"), DemandKind.WATCH, untilMillis = start + 10_000_000)
        advanceTimeBy(1_000)
        assertEquals(5, fetcher.calls(TRAINS, FeedKind.TRIP_UPDATES))
        live.keepWarm("watch-1", setOf("T1"), DemandKind.WATCH, untilMillis = start)
        advanceTimeBy(120_000)
        assertEquals(5, fetcher.calls(TRAINS, FeedKind.TRIP_UPDATES))
    }

    // ---- vehicles ----

    @Test
    fun `vehicles are filtered by route and old positions hidden`() = runTest {
        published[TRAINS.id to FeedKind.VEHICLES] = listOf(
            vehicle("A1", "T1-A", timestampMs = start - 10_000, occupancy = GtfsVehicle.OccupancyStatus.MANY_SEATS_AVAILABLE, cars = 4),
            vehicle("OLD", "T1-B", timestampMs = start - 3 * 60_000),
            vehicle("X", "OTHER", routeId = "T9", timestampMs = start),
            vehicle("B1", "T1-B", routeId = null, timestampMs = start), // route from the timetable
            vehicle("C1", "T1-A", timestampMs = start, cars = 6, startDate = NEXT_DAY), // no occupancy: median carriage
        )
        val live = live(fetcher())
        val response = live.vehicles(setOf("T1"))
        assertEquals(listOf("A1", "B1", "C1"), response.vehicles.map { it.vehicleId })
        val a1 = response.vehicles[0]
        assertEquals(4, a1.carCount)
        assertEquals(Occupancy.MANY_SEATS_AVAILABLE, a1.occupancy)
        assertEquals(DAY, a1.startDate)
        assertEquals("Set A1", a1.label)
        assertEquals(-33.88, a1.position.lat, 1e-4)
        assertEquals("T1", response.vehicles[1].routeId)
        assertNull(response.vehicles[1].carCount)
        assertEquals(Occupancy.FULL, response.vehicles[2].occupancy)
    }

    // ---- alerts ----

    @Test
    fun `alerts are translated, filtered by mode and have a stable etag`() = runTest {
        published[TRAINS.id to FeedKind.ALERTS] = listOf(
            alert("a2", headline = "Lift outage", effect = GtfsAlert.Effect.ACCESSIBILITY_ISSUE, entities = listOf(EntitySelector(stop_id = "RED1"))),
            alert("a1", severity = GtfsAlert.SeverityLevel.SEVERE),
        )
        published[METRO.id to FeedKind.ALERTS] = listOf(
            FeedEntity(
                id = "m1",
                alert = GtfsAlert(
                    header_text = text("Service métro" to "fr", "Metro running" to null),
                    effect = GtfsAlert.Effect.ADDITIONAL_SERVICE,
                    informed_entity = listOf(EntitySelector(route_type = 401, trip = TripDescriptor(trip_id = "M1-A"))),
                ),
            ),
        )
        val fetcher = fetcher()
        val live = live(fetcher)
        val changes = mutableListOf<List<app.transittracker.model.Alert>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { live.alertChanges.collect { changes += it } }

        val all = live.alerts(null)
        assertEquals(listOf("a1", "a2", "m1"), all.response.alerts.map { it.id })
        val a1 = all.response.alerts[0]
        assertEquals("Trackwork", a1.headline)
        assertEquals("Buses replace trains", a1.description)
        assertEquals(AlertSeverity.SEVERE, a1.severity)
        assertEquals("MAINTENANCE", a1.cause)
        assertEquals("REDUCED_SERVICE", a1.effect)
        assertEquals(listOf("T1"), a1.routeIds)
        assertEquals(listOf(Mode.TRAIN), a1.modes)
        assertEquals(listOf(TimeRange(start, start + 3_600_000)), a1.activePeriods)
        val a2 = all.response.alerts[1]
        assertEquals(AlertSeverity.WARNING, a2.severity, "inferred from effect")
        assertEquals(listOf("RED1"), a2.stopIds)
        val m1 = all.response.alerts[2]
        assertEquals("Metro running", m1.headline, "unspecified language preferred over non-English")
        assertEquals(AlertSeverity.INFO, m1.severity)
        assertEquals(listOf("M1-A"), m1.tripIds)
        assertEquals(listOf(Mode.METRO), m1.modes)

        val trainsOnly = live.alerts(setOf(Mode.TRAIN))
        assertEquals(listOf("a1", "a2"), trainsOnly.response.alerts.map { it.id })
        assertNotEquals(all.etag, trainsOnly.etag)

        // Same content later (newer header time, different entity order): same ETag, no change event.
        assertEquals(1, changes.size)
        published[TRAINS.id to FeedKind.ALERTS] = published.getValue(TRAINS.id to FeedKind.ALERTS).reversed()
        advanceTimeBy(61_000)
        val again = live.alerts(null)
        assertEquals(2, fetcher.calls(TRAINS, FeedKind.ALERTS))
        assertEquals(all.etag, again.etag)
        assertEquals(all.response.alerts, again.response.alerts)
        assertEquals(1, changes.size)

        // Content changes: new ETag and a change event.
        published[TRAINS.id to FeedKind.ALERTS] = listOf(alert("a1", severity = GtfsAlert.SeverityLevel.SEVERE))
        advanceTimeBy(61_000)
        val changed = live.alerts(null)
        assertNotEquals(all.etag, changed.etag)
        assertEquals(listOf("a1", "m1"), changed.response.alerts.map { it.id })
        assertEquals(2, changes.size)

        // realtime carries the ETag of the alerts for the modes it served.
        published[TRAINS.id to FeedKind.TRIP_UPDATES] = emptyList()
        published[TRAINS.id to FeedKind.VEHICLES] = emptyList()
        assertEquals(live.alerts(setOf(Mode.TRAIN)).etag, live.realtime(setOf("T1")).alertsEtag)
    }

    @Test
    fun `etag ignores order and changes with content`() {
        val a = app.transittracker.model.Alert(id = "1", headline = "x")
        val b = app.transittracker.model.Alert(id = "2", headline = "y")
        assertEquals(AlertTranslator.etag(listOf(a, b)), AlertTranslator.etag(listOf(b, a)))
        assertNotEquals(AlertTranslator.etag(listOf(a, b)), AlertTranslator.etag(listOf(a, b.copy(headline = "z"))))
    }

    // ---- recording ----

    @Test
    fun `fetched feeds are recorded at most once a minute`() = runTest {
        publishTrains(tripUpdate("T1-A"))
        val dir = Files.createTempDirectory("tt-rec")
        val live = live(fetcher(), recorder = FeedRecorder(dir, virtualClock(start)))
        live.realtime(setOf("T1"))
        advanceTimeBy(16_000)
        live.realtime(setOf("T1"))
        val files = dir.resolve(DAY).listDirectoryEntries("sydneytrains-tripupdates-*")
        assertEquals(1, files.size)
        advanceTimeBy(50_000)
        live.realtime(setOf("T1"))
        assertEquals(2, dir.resolve(DAY).listDirectoryEntries("sydneytrains-tripupdates-*").size)
    }
}
