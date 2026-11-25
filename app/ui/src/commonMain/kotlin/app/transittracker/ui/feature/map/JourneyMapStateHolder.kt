package app.transittracker.ui.feature.map

import app.transittracker.model.DataStatus
import app.transittracker.model.LatLng
import app.transittracker.model.ServiceRef
import app.transittracker.model.TrackingState
import app.transittracker.model.TransitCore
import app.transittracker.model.VehiclePosition
import app.transittracker.model.WallClock
import app.transittracker.ui.feature.follow.FollowStage
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
 *
 * While the follower says the user is riding a leg and the phone's fix lies on that leg's line,
 * the fix is the vehicle's position ([riderReport]): the phone is on board, so it is fresher and
 * closer than the feed's report (often a minute old) or the timetable, and the train is drawn
 * where the user is instead of beside them.
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
    /** Legs whose reckoning comes from the phone's fixes rather than the feed. */
    private val fromRider = HashSet<ServiceRef>()

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(tickMs)
        }
    }

    private val tracked: Flow<List<TrackingState>> =
        if (legs.isEmpty()) flowOf(emptyList()) else combine(legs.map { core.track(it.ref) }) { it.toList() }

    /** The saved trip, else the ticket (GO), with this id. */
    private val trip = combine(core.trips, core.tickets.onStart { emit(emptyList()) }) { list, tickets ->
        list.firstOrNull { it.id == tripId } ?: tickets.firstOrNull { it.id == tripId }?.trip
    }.distinctUntilChanged()

    override val state: StateFlow<JourneyMapUiState> = combine(
        tracked,
        trip.onStart { emit(null) },
        follow.onStart { emit(FollowState()) },
        core.dataStatus.map<DataStatus, DataStatus?> { it }.onStart { emit(null) },
        ticker,
    ) { states, t, f, status, now ->
        val riding = f.match?.takeIf { it.stage == FollowStage.RIDING && it.key == legs.firstOrNull()?.ref }?.legIndex
        val inputs = legs.zip(states).mapIndexed { k, (leg, ts) ->
            val g = geometry.service(ts.detail)
            val rider = if (k == riding) riderReport(f, g, now) else null
            val v = rider ?: ts.vehicle
            if (v != null && positionFresh(v, now) && ts.detail.calls.isNotEmpty()) {
                val key = v.timestampMs to v.position
                if (key != lastReports[leg.ref]) {
                    // Changing between the feed and the phone starts afresh: the reckoning never
                    // runs backwards, and a feed report ahead of the user would hold the train there.
                    val switched = (rider != null) != (leg.ref in fromRider)
                    reckonings[leg.ref] = reckon(v, ts.detail.calls, g, now, if (switched) null else reckonings[leg.ref])
                    lastReports[leg.ref] = key
                    if (rider != null) fromRider += leg.ref else fromRider -= leg.ref
                }
            }
            JourneyLegInput(leg, ts, g, reckonings[leg.ref], fromRider = rider != null && leg.ref in fromRider)
        }
        buildJourneyMapState(JourneyMapInput(t, inputs, f, status), now, zone, clockFormat)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, JourneyMapUiState())
}

/** Fixes vaguer than this do not place the vehicle (the feed or the timetable does). */
internal const val RIDER_MAX_ACCURACY_M = 100.0

/** Furthest from the line a fix can be and still place the vehicle on it. */
internal const val RIDER_MAX_OFF_LINE_M = 200.0

/**
 * The phone's latest fix as a report of the vehicle the user is riding: null when there is no fix,
 * it is too old to draw ([POSITION_MAX_AGE_MS]) or too vague, the line is schematic, or the fix is
 * not on the line (the user said ON BOARD but is somewhere else).
 */
internal fun riderReport(follow: FollowState, geometry: ServiceGeometry, nowMs: Long, projection: MapProjection = MapProjection.Sydney): VehiclePosition? {
    val fix = follow.fix ?: return null
    if (geometry.schematic || nowMs - fix.timeMs > POSITION_MAX_AGE_MS) return null
    val accuracy = fix.accuracyM ?: 0.0
    if (accuracy > RIDER_MAX_ACCURACY_M) return null
    if (geometry.path.snap(projection.project(fix.position)).offset > RIDER_MAX_OFF_LINE_M + accuracy) return null
    return VehiclePosition(
        vehicleId = "rider",
        position = fix.position,
        bearing = fix.bearingDeg?.toFloat(),
        speedMps = fix.speedMps?.toFloat(),
        timestampMs = fix.timeMs,
    )
}
