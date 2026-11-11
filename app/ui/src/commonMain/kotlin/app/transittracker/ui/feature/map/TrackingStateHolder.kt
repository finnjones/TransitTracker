package app.transittracker.ui.feature.map

import app.transittracker.model.DataStatus
import app.transittracker.model.LatLng
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopId
import app.transittracker.model.TrackingState
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.ui.settings.ClockFormat
import app.transittracker.ui.state.StateHolder
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * State for live tracking of one service (plan 5.5). Subscribes to `core.track(ref)` (fast
 * updates while the screen is open; the subscription ends with the page) and the data status,
 * ticks every [tickMs] for the countdown, finds the service's geometry once per stop list, and
 * keeps the dead-reckoning between reports so the vehicle never runs backwards.
 *
 * [track] is the tracking stream; by default `core.track(ref)` (screenshots pass a re-labelled
 * one to show the stale and no-position states).
 */
class TrackingStateHolder(
    core: TransitCore,
    private val geometry: MapGeometrySource,
    private val clock: WallClock,
    private val ref: ServiceRef,
    private val boardStopId: StopId?,
    private val alightStopId: StopId?,
    scope: CoroutineScope,
    track: Flow<TrackingState> = core.track(ref),
    private val tickMs: Long = 1_000,
    private val zone: TimeZone = TimeZone.of("Australia/Sydney"),
    private val clockFormat: ClockFormat = ClockFormat.H24,
) : StateHolder<TrackingUiState>(scope) {
    private var reckoning: Reckoning? = null
    private var lastReport: Pair<Long, LatLng>? = null

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(tickMs)
        }
    }

    override val state: StateFlow<TrackingUiState> = combine(
        track,
        core.dataStatus.map<DataStatus, DataStatus?> { it }.onStart { emit(null) },
        ticker,
    ) { t, status, now ->
        val g = geometry.service(t.detail)
        val v = t.vehicle
        if (v != null && positionFresh(v, now) && t.detail.calls.isNotEmpty()) {
            val key = v.timestampMs to v.position
            if (key != lastReport) {
                reckoning = reckon(v, t.detail.calls, g, now, reckoning)
                lastReport = key
            }
        }
        buildTrackingState(TrackingInput(t, g, reckoning, status, boardStopId, alightStopId), now, zone, clockFormat)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, TrackingUiState(ref = ref))
}
