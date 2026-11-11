package app.transittracker.ui.feature.map

import app.transittracker.model.DepartureStatus
import app.transittracker.model.LatLng
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceTime
import app.transittracker.model.ServiceDetail
import app.transittracker.model.Stop
import app.transittracker.model.TrackingState
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.fake.LiveData
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.nav.Route
import app.transittracker.ui.platform.FakePlatformServices
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.platform.PlatformServices
import app.transittracker.ui.sprites.SpriteLook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tracking and Map tab state holders against FakeTransitCore at Wed 11 Nov 07:38 (AppDeps.fake). */
class MapStateHoldersTest {
    private val now = SampleData.NOW_MS

    /** The Home to Work trip's first service already under way, opened as the Home card would. */
    private suspend fun running(core: TransitCore): Route.Tracking {
        for (j in core.upcoming(FakeSetup.TRIP_HOME_WORK, 6).first()) {
            val d = j.departure
            if (d.status == DepartureStatus.CANCELLED) continue
            if (core.service(d.ref).first().calls.first().passed) return Route.Tracking(d.ref, d.originStopId, d.destinationStopId)
        }
        error("no running service at 07:38")
    }

    private fun tracking(
        deps: AppDeps,
        route: Route.Tracking,
        scope: CoroutineScope,
        geometry: MapGeometrySource = CoreMapGeometry(deps.core),
        relabel: (Flow<TrackingState>) -> Flow<TrackingState> = { it },
    ) = TrackingStateHolder(
        deps.core, geometry, deps.clock, route.ref, route.boardStopId, route.alightStopId, scope,
        track = relabel(deps.core.track(route.ref)),
    )

    private suspend fun TrackingStateHolder.ready(): TrackingUiState = state.first { !it.loading }

    @Test
    fun liveTrackingShowsTheVehicleCreepingTowardsYourStop() = runTest {
        val deps = AppDeps.fake()
        val route = running(deps.core)
        val s = tracking(deps, route, backgroundScope).ready()
        assertEquals(TrackPhase.LIVE, s.phase)
        assertIs<TrackChip.Live>(s.chip)
        assertNull(s.notice)
        val v = s.scene.vehicles.single()
        assertIs<VehicleMotion.Reckoned>(v.motion)
        assertEquals(SpriteLook.NORMAL, v.look)
        assertEquals("YOUR TRAIN", v.caption)
        assertTrue(!s.scene.schematic, "fake stations are found with searchStops")
        // Your stops are flagged; the line dims behind the vehicle.
        assertEquals(1, s.scene.stations.count { it.kind == StationKind.BOARD })
        assertEquals(1, s.scene.stations.count { it.kind == StationKind.ALIGHT })
        assertEquals("vehicle", s.scene.lines.single().travelledBy)
        // The footer counts down to the boarding stop (Strathfield).
        val cd = assertNotNull(s.countdown)
        assertTrue(cd.label == "REACHES STRATHFIELD IN" || cd.label == "LEAVES STRATHFIELD IN", cd.label)
        assertTrue(cd.targetMs > now)
        assertEquals("", cd.prefix)
        assertEquals("Strathfield", s.nextStop)
        assertTrue(s.description.contains("Strathfield"))
    }

    @Test
    fun staleDataFadesTheVehicleAndMarksTheCountdown() = runTest {
        val deps = AppDeps.fake()
        val route = running(deps.core)
        val s = tracking(deps, route, backgroundScope, relabel = { f -> f.map { it.copy(detail = it.detail.copy(stale = true, dataAgeMs = 180_000)) } }).ready()
        assertEquals(TrackPhase.STALE, s.phase)
        assertEquals(TrackChip.Stale, s.chip)
        assertEquals(SpriteLook.FADED, s.scene.vehicles.single().look)
        assertEquals("~", s.countdown?.prefix)
        assertEquals("STALE · 3 MIN OLD", s.status.words)
    }

    @Test
    fun aPositionOlderThanTwoMinutesBecomesTheGhost() = runTest {
        val deps = AppDeps.fake()
        val route = running(deps.core)
        val s = tracking(deps, route, backgroundScope, relabel = { f -> f.map { it.copy(vehicle = it.vehicle?.copy(timestampMs = now - 150_000)) } }).ready()
        assertEquals(TrackPhase.POSITION_UNAVAILABLE, s.phase)
        assertTrue(s.notice!!.startsWith("Position unavailable"))
        val v = s.scene.vehicles.single()
        assertIs<VehicleMotion.Timetabled>(v.motion)
        assertEquals(SpriteLook.GHOST, v.look)
        // No position at all: the same.
        val none = tracking(deps, route, backgroundScope, relabel = { f -> f.map { it.copy(vehicle = null) } }).ready()
        assertEquals(TrackPhase.POSITION_UNAVAILABLE, none.phase)
    }

    @Test
    fun timetableOnlyAndOffline() = runTest {
        val deps = AppDeps.fake(scenario = CoreScenario(live = LiveData.OFFLINE))
        val route = running(AppDeps.fake().core)
        val s = tracking(deps, route, backgroundScope, relabel = { f ->
            f.map { it.copy(vehicle = null, detail = it.detail.copy(status = DepartureStatus.TIMETABLED)) }
        }).state.first { !it.loading && it.chip == TrackChip.Offline }
        assertEquals(TrackPhase.TIMETABLE, s.phase)
        assertEquals(TrackChip.Offline, s.chip)
        assertEquals("No live data. Showing the timetable.", s.notice)
        assertEquals(SpriteLook.GHOST, s.scene.vehicles.single().look)
    }

    @Test
    fun aCancelledServiceFromTheFake() = runTest {
        val deps = AppDeps.fake()
        val today: ServiceDate = ServiceTime.localDate(now)
        // Any leg of any saved trip in the window the fake has live data for.
        val cancelled = FakeSetup.trips.flatMap { t -> deps.core.timetable(t.id, today).first().flatMap { it.legs } }
            .first { it.status == DepartureStatus.CANCELLED && it.scheduledDepartureMs in now..now + 3 * 3_600_000 }
        val route = Route.Tracking(cancelled.ref, cancelled.originStopId, cancelled.destinationStopId)
        val s = tracking(deps, route, backgroundScope).ready()
        assertEquals(TrackPhase.CANCELLED, s.phase)
        assertEquals("CANCELLED", s.bigWords)
        assertNull(s.countdown)
        val v = s.scene.vehicles.single()
        assertTrue(v.cancelled)
        assertEquals(SpriteLook.CANCELLED, v.look)
        assertNull(s.scene.lines.single().travelledBy)
    }

    @Test
    fun arrivedOnceTheVehicleReachesYourStop() = runTest {
        val base = AppDeps.fake()
        val route = running(base.core)
        val later = AppDeps.fake(clock = WallClock { now + 40 * 60_000 })
        val s = tracking(later, route, backgroundScope).ready()
        assertEquals(TrackPhase.ARRIVED, s.phase)
        assertEquals("ARRIVED AT CENTRAL", s.bigWords)
        assertIs<VehicleMotion.Parked>(s.scene.vehicles.single().motion)
    }

    @Test
    fun noStopPositionsGivesTheSchematicMap() = runTest {
        val deps = AppDeps.fake()
        val route = running(deps.core)
        // A core that finds no stops (searchStops answers nothing).
        val blind = object : TransitCore by deps.core {
            override suspend fun searchStops(query: String, near: LatLng?): List<Stop> = emptyList()
        }
        // ... and a service whose calls carry no positions and no shape (an older timetable pack).
        val inner = CoreMapGeometry(blind)
        val geometry = object : MapGeometrySource by inner {
            override suspend fun service(detail: ServiceDetail) =
                inner.service(detail.copy(shapeId = null, calls = detail.calls.map { it.copy(position = null) }))
        }
        val s = tracking(deps, route, backgroundScope, geometry = geometry).ready()
        assertTrue(s.scene.schematic)
        assertTrue(s.description.contains("not to scale"))
        // The live vehicle is placed by its times, not by a GPS fix on an invented path.
        assertIs<VehicleMotion.Timetabled>(s.scene.vehicles.single().motion)
        assertEquals(SpriteLook.NORMAL, s.scene.vehicles.single().look)
    }

    // ---------------------------------------------------------------- Map tab

    private fun map(deps: AppDeps, scope: CoroutineScope, platform: FakePlatformServices = deps.platform as FakePlatformServices) =
        MapStateHolder(deps.core, CoreMapGeometry(deps.core), platform, deps.places, deps.clock, scope)

    @Test
    fun theMapShowsTheSavedTripsLinesAndTheirLiveVehicles() = runTest {
        val deps = AppDeps.fake()
        val h = map(deps, backgroundScope)
        val s = h.state.first { !it.loading && it.scene.lines.size == 3 && it.liveCount > 0 }
        assertEquals(setOf("T1", "T9", "M1"), s.scene.lines.map { it.id }.toSet())
        assertTrue(s.scene.stations.any { it.name == "Central" })
        assertIs<TrackChip.Live>(s.chip)
        assertNull(s.banner)
        // Each vehicle in its line colour, and dead-reckoned once its service has loaded.
        val withService = h.state.first { st -> st.scene.vehicles.isNotEmpty() && st.scene.vehicles.all { it.motion is VehicleMotion.Reckoned } }
        assertTrue(withService.scene.vehicles.all { it.cars == 1 && it.routeId in setOf("T1", "T9", "M1") })
        // Tapping a vehicle opens its card with the service to open.
        val id = withService.vehicleWords.first().first
        h.selectVehicle(id)
        val card = h.state.first { it.vehicleCard != null }.vehicleCard!!
        assertNotNull(card.ref)
        assertNotNull(card.line)
        assertNotNull(card.headsign)
        assertTrue(h.state.value.scene.vehicles.single { it.id == id }.selected)
    }

    @Test
    fun nearMeAsksOnlyWhenTappedAndShowsNearbyStations() = runTest {
        val platform = FakePlatformServices(locationPermission = PermissionState.NOT_ASKED)
        val deps = AppDeps.fake(platform = platform)
        val h = map(deps, backgroundScope, platform)
        h.state.first { !it.loading }
        assertEquals(PermissionState.NOT_ASKED, platform.locationPermission.value, "not asked at start-up")
        h.nearMe()
        val s = h.state.first { it.nearMe is NearMe.Shown }
        assertEquals(PermissionState.GRANTED, platform.locationPermission.value)
        val shown = s.nearMe as NearMe.Shown
        assertTrue(shown.stops.any { it.id == "213510" })
        assertNotNull(s.scene.you)
        assertTrue(s.scene.stations.any { it.kind == StationKind.NEARBY && it.name == "Strathfield" })
        // Tapping the station: its lines and the saved place that uses it (Home).
        h.selectStation("Strathfield", "213510", MapPoint(0.0, 0.0))
        val card = h.state.first { it.stationCard?.lines != null }.stationCard!!
        assertEquals(setOf("T1", "T9"), card.lines!!.map { it.id }.toSet())
        assertEquals(FakeSetup.HOME, card.placeId)
    }

    @Test
    fun locationRefused() = runTest {
        val platform = FakePlatformServices(locationPermission = PermissionState.NOT_ASKED).apply { grantOnRequest = false }
        val deps = AppDeps.fake(platform = platform)
        val h = map(deps, backgroundScope, platform)
        h.state.first { !it.loading }
        h.nearMe()
        val s = h.state.first { it.nearMe == NearMe.Refused }
        assertTrue(s.banner!!.startsWith("Location is off"))
        assertNull(s.scene.you)
    }

    @Test
    fun aFailedLocationLookupSaysNoFixInsteadOfStayingOnLocating() = runTest {
        val fake = FakePlatformServices()
        val failing = object : PlatformServices by fake {
            override suspend fun currentLocation(): LatLng? = throw IllegalStateException("provider gone")
        }
        val deps = AppDeps.fake(platform = fake)
        val h = MapStateHolder(deps.core, CoreMapGeometry(deps.core), failing, deps.places, deps.clock, backgroundScope)
        h.state.first { !it.loading }
        h.nearMe()
        val s = h.state.first { it.nearMe == NearMe.NoFix }
        assertNull(s.scene.you)
    }

    @Test
    fun noSavedTripsAndOffline() = runTest {
        val empty = map(AppDeps.fake(trips = emptyList<SavedTrip>()), backgroundScope).state.first { !it.loading }
        assertTrue(empty.noTrips)
        assertTrue(empty.scene.lines.isEmpty())

        val offline = map(AppDeps.fake(scenario = CoreScenario(live = LiveData.OFFLINE)), backgroundScope)
            .state.first { !it.loading && it.scene.lines.isNotEmpty() }
        assertEquals(TrackChip.Offline, offline.chip)
        assertEquals("No live data. Showing the timetable.", offline.banner)
        assertTrue(offline.scene.vehicles.isEmpty())

        val stale = map(AppDeps.fake(scenario = CoreScenario(live = LiveData.STALE)), backgroundScope)
            .state.first { !it.loading && it.scene.vehicles.isNotEmpty() }
        assertEquals(TrackChip.Stale, stale.chip)
        assertTrue(stale.scene.vehicles.all { it.look == SpriteLook.FADED })
    }
}
