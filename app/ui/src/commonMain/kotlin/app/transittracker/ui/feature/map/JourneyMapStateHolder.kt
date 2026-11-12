package app.transittracker.ui.feature.map

import app.transittracker.model.DataStatus
import app.transittracker.model.LatLng
import app.transittracker.model.ServiceRef
import app.transittracker.model.TrackingState
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.ui.feature.follow.FollowState
import app.transittracker.ui.nav.MapLeg
import app.transittracker.ui.settings.ClockFormat
import app.transittracker.ui.state.StateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone

/**
 * State for the journey map ([buildJourneyMapState]): tracks every ride of the journey at once
 * (`core.track` per leg, fast updates while the page is open), finds each ride's geometry, keeps
 * each vehicle's dead reckoning between reports, and follows the phone's location through
 * [follow] (the app's `JourneyFollower`; nothing is asked for when it is not given).
 */
class JourneyMapStateHolder(
    core: TransitCore,
    private val geometry: MapGeometrySource,
    private val clock: WallClock,
    tripId: String,
    private val legs: List<MapLeg>,
    scope: CoroutineScope,
    follow: Flow<FollowState> = flowOf(FollowState()),
    private val tickMs: Long = 1_000,
    private val zone: TimeZone = TimeZone.of("Australia/Sydney"),
    private val clockFormat: ClockFormat = ClockFormat.H24,
) : StateHolder<JourneyMapUiState>(scope) {
    private val reckonings = HashMap<ServiceRef, Reckoning>()
    private val lastReports = HashMap<ServiceRef, Pair<Long, LatLng>>()

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(tickMs)
        }
    }

    private val tracked: Flow<List<TrackingState>> =
        if (legs.isEmpty()) flowOf(emptyList()) else combine(legs.map { core.track(it.ref) }) { it.toList() }

    private val trip = core.trips.map { list -> list.firstOrNull { it.id == tripId } }.distinctUntilChanged()

    override val state: StateFlow<JourneyMapUiState> = combine(
        tracked,
        trip.onStart { emit(null) },
        follow.onStart { emit(FollowState()) },
        core.dataStatus.map<DataStatus, DataStatus?> { it }.onStart { emit(null) },
        ticker,
    ) { states, t, f, status, now ->
        val inputs = legs.zip(states).map { (leg, ts) ->
            val g = geometry.service(ts.detail)
            val v = ts.vehicle
            if (v != null && positionFresh(v, now) && ts.detail.calls.isNotEmpty()) {
                val key = v.timestampMs to v.position
                if (key != lastReports[leg.ref]) {
                    reckonings[leg.ref] = reckon(v, ts.detail.calls, g, now, reckonings[leg.ref])
                    lastReports[leg.ref] = key
                }
            }
            JourneyLegInput(leg, ts, g, reckonings[leg.ref])
        }
        buildJourneyMapState(JourneyMapInput(t, inputs, f, status), now, zone, clockFormat)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, JourneyMapUiState())
}
