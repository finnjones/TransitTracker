package app.transittracker.ui.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.transittracker.core.FakeData
import app.transittracker.model.DepartureStatus
import app.transittracker.model.LatLng
import app.transittracker.model.RouteId
import app.transittracker.model.ServiceRef
import app.transittracker.model.TrackingState
import app.transittracker.model.VehiclePosition
import app.transittracker.ui.strip.vehicleProgress
import kotlinx.coroutines.flow.Flow
import app.transittracker.model.ServiceDetail
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.app.BottomBar
import app.transittracker.ui.app.PagePreview
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.fake.LiveData
import app.transittracker.ui.gallery.AppShot
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.gallery.Shot
import app.transittracker.ui.motion.PageTransition
import app.transittracker.ui.nav.Navigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.nav.Tab
import app.transittracker.ui.platform.FakePlatformServices
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.settings.ThemePreference
import app.transittracker.ui.settings.UiSettings
import app.transittracker.ui.theme.DayColors
import app.transittracker.ui.theme.TtTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Screenshots of the `map` feature: live tracking (Track mockup) and the Map tab (no mockup;
 * built from plan 5.5 in the same visual language). All on `AppDeps.fake()` at Wed 11 Nov 07:38.
 */
object MapShots {
    private const val MIN = 60_000L

    /**
     * The Home to Work trip's first service that is already running (a vehicle on the line before
     * Strathfield), as the Home card would open it: the user's stops are its platforms.
     */
    private suspend fun runningService(core: TransitCore): Route.Tracking? {
        val journeys = core.upcoming(FakeSetup.TRIP_HOME_WORK, 6).first()
        for (j in journeys) {
            val d = j.departure
            if (d.status == DepartureStatus.CANCELLED) continue
            val detail = core.service(d.ref).first()
            if (detail.calls.firstOrNull()?.passed == true) return Route.Tracking(d.ref, d.originStopId, d.destinationStopId)
        }
        return journeys.firstOrNull()?.departure?.let { Route.Tracking(it.ref, it.originStopId, it.destinationStopId) }
    }

    /** Resolves the service at 07:38 with [lookup], then shows [content] for it. */
    @Composable
    private fun WithService(lookup: AppDeps, content: @Composable (Route.Tracking) -> Unit) {
        var route by remember { mutableStateOf<Route.Tracking?>(null) }
        LaunchedEffect(Unit) { route = runningService(lookup.core) }
        route?.let { content(it) }
    }

    private fun deps(
        clock: WallClock = SampleData.clock,
        settings: UiSettings = UiSettings(),
        scenario: CoreScenario = CoreScenario(),
        platform: FakePlatformServices = FakePlatformServices(),
        trips: List<app.transittracker.model.SavedTrip> = FakeSetup.trips,
    ) = AppDeps.fake(clock = clock, settings = settings, scenario = scenario, platform = platform, trips = trips)

    /** Live tracking in the whole app (the page as the doors open on it). */
    @Composable
    private fun TrackingApp(deps: AppDeps) {
        WithService(deps) { route ->
            AppShot(deps, remember { Navigator().apply { push(route, PageTransition.Cut) } })
        }
    }

    /** One tracking page with preview hooks; [later] moves the clock on from 07:38 (arrived). */
    @Composable
    private fun TrackingHook(
        later: Long = 0,
        settings: UiSettings? = null,
        geometry: ((TransitCore) -> MapGeometrySource)? = null,
        relabel: ((Flow<TrackingState>) -> Flow<TrackingState>)? = gpsTrack,
    ) {
        val base = remember { deps() }
        val shown = remember { if (later == 0L && settings == null) base else deps(clock = WallClock { SampleData.NOW_MS + later }, settings = settings ?: UiSettings()) }
        WithService(base) { route ->
            PagePreview(shown) {
                Box(Modifier.fillMaxSize().background(TtTheme.colors.screen)) {
                    TrackingPage(route, geometry?.invoke(shown.core), relabel)
                }
            }
        }
    }

    /** The Map tab with its bottom bar and preview hooks. */
    @Composable
    private fun MapTab(deps: AppDeps, vehicle: Boolean = false, nearMe: Boolean = false, station: String? = null, gps: Boolean = true) {
        PagePreview(deps) {
            Column(Modifier.fillMaxSize().background(TtTheme.colors.screen)) {
                Box(Modifier.weight(1f).fillMaxWidth()) { MapTabPage(vehicle, nearMe, station, if (gps) gpsFeed else null) }
                BottomBar(Tab.MAP, 0, onSelect = {})
            }
        }
    }

    /**
     * The fake reports each vehicle at the last stop it passed. A real feed reports GPS, so the
     * live shots move each report to where the timetable puts the vehicle [AGO_MS] before now,
     * between its platforms' positions (the way a GPS fix 20 s old would read).
     */
    private fun gpsLike(v: VehiclePosition, detail: ServiceDetail): VehiclePosition {
        val at = SampleData.NOW_MS - AGO_MS
        val p = vehicleProgress(detail.calls, at) ?: return v
        val a = FakeData.stops.firstOrNull { it.id == detail.calls[p.fromIndex].stopId }?.position ?: return v
        val b = FakeData.stops.firstOrNull { it.id == detail.calls[p.toIndex].stopId }?.position ?: return v
        val t = p.progress.toDouble()
        return v.copy(position = LatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t), timestampMs = at)
    }

    private const val AGO_MS = 20_000L

    private val gpsTrack: (Flow<TrackingState>) -> Flow<TrackingState> = { f ->
        f.map { t -> t.copy(vehicle = t.vehicle?.let { gpsLike(it, t.detail) }) }
    }

    private val gpsFeed: (TransitCore) -> (Set<RouteId>) -> Flow<List<VehiclePosition>> = { core ->
        { ids ->
            core.vehicles(ids).map { list ->
                list.map { v ->
                    val trip = v.tripId
                    val date = v.startDate
                    if (trip == null || date == null) v else gpsLike(v, core.service(ServiceRef(trip, date)).first())
                }
            }
        }
    }

    /** Geometry that never finds a stop: the schematic fallback. */
    private object NoPositions : MapGeometrySource {
        override suspend fun service(detail: ServiceDetail) = schematicGeometry(detail.calls)
        override suspend fun lines(samples: List<ServiceDetail>) = emptyList<LineGeometry>()
    }

    /** The train-then-bus sample journey ([JourneyMapSamples]) seen [later] after 07:38. */
    @Composable
    private fun JourneySample(later: Long = 0, riding: Boolean = true) {
        val now = SampleData.NOW_MS + later
        val state = remember { buildJourneyMapState(JourneyMapSamples.input(SampleData.NOW_MS, now, riding), now) }
        JourneyMapContent(state, WallClock { now }, JourneyMapActions())
    }

    val shots: List<Shot> = listOf(
        Shot("map-journey-train-bus", tick = 1) { JourneySample() },
        Shot("map-journey-train-bus-changing", tick = 1) { JourneySample(later = 12 * MIN, riding = false) },
        Shot("map-journey-train-bus-day", DayColors, tick = 1) { JourneySample() },
        Shot("map-tracking", tick = 1) { TrackingHook() },
        Shot("map-tracking-app", tick = 1) { TrackingApp(remember { deps() }) },
        Shot("map-tracking-ghost") {
            // The last report is 3 minutes old: hidden (plan 5.5), the ghost at the timetabled place.
            TrackingHook(relabel = { f -> f.map { t -> t.copy(vehicle = t.vehicle?.copy(timestampMs = SampleData.NOW_MS - 3 * MIN)) } })
        },
        Shot("map-tracking-timetable") {
            TrackingHook(
                relabel = { f ->
                    f.map { t ->
                        t.copy(
                            vehicle = null,
                            detail = t.detail.copy(
                                status = DepartureStatus.TIMETABLED, dataAgeMs = null,
                                calls = t.detail.calls.map { it.copy(expectedArrivalMs = null, expectedDepartureMs = null) },
                            ),
                        )
                    }
                },
            )
        },
        Shot("map-tracking-schematic", tick = 1) { TrackingHook(geometry = { NoPositions }) },
        Shot("map-tracking-stale", tick = 1) {
            TrackingHook(relabel = { f -> gpsTrack(f).map { t -> t.copy(detail = t.detail.copy(stale = true, dataAgeMs = 3 * MIN)) } })
        },
        Shot("map-tracking-arrived") { TrackingHook(later = 25 * MIN) },
        Shot("map-tracking-cancelled") {
            TrackingHook(relabel = { f -> f.map { t -> t.copy(vehicle = null, detail = t.detail.copy(status = DepartureStatus.CANCELLED)) } })
        },
        Shot("map-tracking-day", DayColors, tick = 1) { TrackingHook(settings = UiSettings(theme = ThemePreference.DAY)) },
        Shot("map-tab", tick = 1) { MapTab(remember { deps() }) },
        Shot("map-tab-vehicle", tick = 1) { MapTab(remember { deps() }, vehicle = true) },
        Shot("map-tab-near", tick = 1) { MapTab(remember { deps() }, nearMe = true, station = "Strathfield") },
        Shot("map-tab-refused") {
            MapTab(remember { deps(platform = FakePlatformServices(locationPermission = PermissionState.DENIED)) }, nearMe = true)
        },
        Shot("map-tab-offline") { MapTab(remember { deps(scenario = CoreScenario(live = LiveData.OFFLINE)) }) },
        Shot("map-tab-empty") { MapTab(remember { deps(trips = emptyList()) }) },
        Shot("map-tab-fake-feed", tick = 1) { MapTab(remember { deps() }, gps = false) },
        Shot("map-tab-day", DayColors, tick = 1) { MapTab(remember { deps(settings = UiSettings(theme = ThemePreference.DAY)) }, vehicle = true) },
    )
}
