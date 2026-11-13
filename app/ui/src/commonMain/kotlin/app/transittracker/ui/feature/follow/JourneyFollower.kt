package app.transittracker.ui.feature.follow

import app.transittracker.model.Journey
import app.transittracker.model.LatLng
import app.transittracker.model.RouteId
import app.transittracker.model.ServiceDetail
import app.transittracker.model.ShapeId
import app.transittracker.model.TransitCore
import app.transittracker.model.VehiclePosition
import app.transittracker.model.WallClock
import app.transittracker.ui.platform.LocationFix
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.platform.PlatformServices
import app.transittracker.ui.settings.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Follow my journey: which journey of a saved trip the phone is on, from its location.
 *
 * One per app (`AppDeps.follower`), so what it has worked out survives moving between Home and
 * the journey map. [state] is cold: location is asked for only while a screen collects it, the
 * app is in the [foreground], the setting is on and permission was already given (it never asks
 * for permission itself). While a saved trip has a journey under way it takes a fix every
 * [intervalMs] and reads it against each journey ([readFix]): along each service's line shape,
 * against its vehicle's live position (it watches the live vehicles of the lines under way while
 * it polls), with the phone's heading and speed. Otherwise it only checks every
 * [idleIntervalMs] whether one has started. [FollowMemory] adds up the evidence between fixes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JourneyFollower(
    private val core: TransitCore,
    private val platform: PlatformServices,
    private val settings: SettingsStore,
    private val clock: WallClock,
    private val intervalMs: Long = 15_000,
    private val idleIntervalMs: Long = 60_000,
    private val tuning: FollowTuning = FollowTuning(),
) {
    /** False while the app is hidden (set by `AppController`): no location is asked for then. */
    val foreground = MutableStateFlow(true)

    private val memory = MutableStateFlow(FollowMemory())
    private val lastFix = MutableStateFlow<LocationFix?>(null)

    /** Line shapes already read, by id (a shape does not change while the app runs). */
    private val shapes = HashMap<ShapeId, List<LatLng>>()
    private val shapesLock = Mutex()

    /**
     * Previews and screenshots: follow [match] as if the phone had been at [fix] (shown while the
     * app is hidden, which is how previews run: nothing polls).
     */
    fun showForPreview(match: FollowMatch, fix: LocationFix?) {
        memory.value = FollowMemory().switchTo(match)
        lastFix.value = fix
    }

    /** The user switched to another way of the trip (see [switchedMatch]): follow [match] from now on. */
    fun switchTo(match: FollowMatch) {
        if (!isOneOff(match.wayId)) oneOff.value = null
        memory.update { it.switchTo(match) }
    }

    /** TAKE THIS ONE: follow [journey] (found by the planner on the way) for its trip, until it arrives. */
    fun take(journey: OneOffJourney) {
        oneOff.value = journey
        memory.update { it.switchTo(journey.match) }
    }

    /** The journey taken with TAKE THIS ONE, if any. */
    private val oneOff = MutableStateFlow<OneOffJourney?>(null)

    /** The way id of the journey taken with TAKE THIS ONE, if any. */
    val oneOffWayId: String? get() = oneOff.value?.wayId

    /** The journey taken with TAKE THIS ONE, with the phone's live times (null when none). */
    val oneOffJourney: Flow<Journey?> = oneOff.flatMapLatest { o ->
        if (o == null) flowOf(null)
        else {
            val refs = o.rides.map { it.ref }.distinct()
            combine(refs.map { ref -> core.service(ref).map<ServiceDetail, ServiceDetail?> { it }.onStart { emit(null) } }) { list ->
                o.journeyFrom(list.filterNotNull().associateBy { it.ref })
            }
        }
    }

    /** The journey taken, with the latest times, or null; forgotten once it has arrived. */
    private suspend fun liveOneOff(): Journey? {
        val o = oneOff.value ?: return null
        val details = o.rides.map { it.ref }.distinct().mapNotNull { ref -> runCatching { core.service(ref).first() }.getOrNull() }
        val j = o.journeyFrom(details.associateBy { it.ref }) ?: return null
        if (j.arrival.expectedArrivalMs + ONE_OFF_GRACE_MS < clock.nowMillis()) {
            oneOff.compareAndSet(o, null)
            return null
        }
        return j
    }

    private data class Gate(val on: Boolean, val permission: PermissionState, val foreground: Boolean)

    /** What following is doing; re-emits after every fix. */
    val state: Flow<FollowState> = combine(
        settings.settings.map { it.followJourney }.distinctUntilChanged(),
        platform.locationPermission,
        foreground,
    ) { on, permission, fg -> Gate(on, permission, fg) }
        .distinctUntilChanged()
        .flatMapLatest { g ->
            when {
                !g.on -> flowOf(FollowState(FollowStatus.OFF))
                g.permission != PermissionState.GRANTED -> flowOf(FollowState(FollowStatus.NO_LOCATION))
                // Hidden: keep showing what was known, ask for nothing.
                !g.foreground -> combine(memory, lastFix) { m, fix ->
                    FollowState(if (m.pinned != null) FollowStatus.FOLLOWING else FollowStatus.IDLE, m.pinned, fix)
                }
                else -> combine(polling(), memory) { s, m ->
                    if (m.pinned != null) s.copy(status = FollowStatus.FOLLOWING, match = m.pinned)
                    else s.copy(status = if (s.status == FollowStatus.FOLLOWING) FollowStatus.LOOKING else s.status, match = null)
                }
            }
        }
        .distinctUntilChanged()

    private fun polling(): Flow<FollowState> = channelFlow {
        // The live vehicles of every line under way, kept while polling (the core polls them).
        val routes = MutableStateFlow<Set<RouteId>>(emptySet())
        val vehicles = MutableStateFlow<List<VehiclePosition>>(emptyList())
        launch {
            routes.flatMapLatest { if (it.isEmpty()) flowOf(emptyList()) else core.vehicles(it) }
                .collect { vehicles.value = it }
        }
        while (true) {
            val taken = liveOneOff()
            val underway = underwayJourneys() + listOfNotNull(taken?.let { oneOff.value?.tripId?.to(it) })
            if (underway.isEmpty()) {
                routes.value = emptySet()
                memory.update { it.next(null, emptySet()) }
                send(FollowState(FollowStatus.IDLE))
                delay(idleIntervalMs)
                continue
            }
            routes.value = underway.flatMap { (_, j) -> j.legs.map { it.routeId } }.toSet()
            val fix = platform.currentFix(clock.nowMillis())
            if (fix == null) {
                // No fix (location off on the phone, or none in time): no news, not a miss.
                send(FollowState(FollowStatus.LOOKING))
            } else {
                val live = vehicles.value
                val candidates = underway.map { (tripId, j) -> candidate(tripId, j, live) }
                val readings = readFix(fix, candidates, tuning)
                // Two ways sharing a vehicle read alike; per journey, its best reading counts.
                val evidence = readings.groupBy { it.key }.mapValues { (_, r) -> r.maxOf { it.evidence } }
                val keys = underway.map { it.second.departure.ref }.toSet()
                memory.update { m ->
                    val match = preferredFit(readings.mapNotNull { it.match }, m.pinned, m.scores)
                    m.next(match, keys, evidence, tuning)
                }
                lastFix.value = fix
                send(FollowState(FollowStatus.LOOKING, fix = fix))
            }
            delay(intervalMs)
        }
    }

    private suspend fun underwayJourneys(): List<Pair<String, Journey>> =
        core.trips.first().flatMap { t -> core.underway(t.id).first().map { t.id to it } }

    private suspend fun candidate(tripId: String, journey: Journey, vehicles: List<VehiclePosition>): FollowCandidate =
        FollowCandidate(
            tripId, journey,
            journey.legs.map { d ->
                val detail = runCatching { core.service(d.ref).first() }.getOrNull()
                RiddenLeg(
                    d,
                    riddenCalls(detail?.calls.orEmpty(), d.originStopId, d.destinationStopId),
                    shape = detail?.shapeId?.let { shape(it) }.orEmpty(),
                    vehicle = vehicleOf(d.ref, vehicles),
                )
            },
        )

    private suspend fun shape(id: ShapeId): List<LatLng> {
        shapesLock.withLock { shapes[id] }?.let { return it }
        val points = runCatching { core.shape(id) }.getOrDefault(emptyList())
        // An empty answer (offline, not in the pack) is asked again next time.
        if (points.isNotEmpty()) shapesLock.withLock { shapes[id] = points }
        return points
    }

    private companion object {
        /** A journey taken is forgotten this long after it arrives. */
        const val ONE_OFF_GRACE_MS = 2 * 60_000L
    }
}
