package app.transittracker.ui.feature.follow

import app.transittracker.model.Journey
import app.transittracker.model.LatLng
import app.transittracker.model.RouteId
import app.transittracker.model.ServiceDetail
import app.transittracker.model.ShapeId
import app.transittracker.model.TicketStatus
import app.transittracker.model.TransitCore
import app.transittracker.model.VehiclePosition
import app.transittracker.model.WallClock
import app.transittracker.model.impliedReturns
import app.transittracker.model.isReturnId
import app.transittracker.ui.platform.LocationFix
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.platform.PlatformServices
import app.transittracker.ui.settings.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Follow my journey: which journey of a saved trip or a ticket the phone is on, from its location.
 *
 * One per app (`AppDeps.follower`), so what it has worked out survives moving between Home and
 * the journey map. [state] is cold: location is asked for only while a screen collects it, the
 * app is in the [foreground], the setting is on and permission was already given (it never asks
 * for permission itself). While a saved trip has a journey under way it takes a fix every
 * [intervalMs] and reads it against each journey ([readFix]): along each service's line shape,
 * against its vehicle's live position (it watches the live vehicles of the lines under way while
 * it polls), with the phone's heading and speed. Otherwise it only checks every
 * [idleIntervalMs] whether one has started. [FollowMemory] adds up the evidence between fixes.
 *
 * Where the location cannot tell (no fix, or a vague one near a trip: [unsureTrips]) the state
 * lists the trips and their journeys under way ([FollowState.unsure]) for Home to ask ON BOARD?;
 * one tap ([onBoard]) follows that journey until it arrives or the user takes it back
 * ([notOnThis]), moved along by the fixes that fit it, else by the clock ([progressByClock]).
 *
 * The way back of each saved trip is looked for too ([impliedReturns]), after the saved trips:
 * a route saved one way is ridden home again. [ReturnTickets] issues a ticket for a way back once
 * it is followed, so Home has a card for it.
 *
 * Tickets not yet arrived are followed like saved trips, by the ticket's id, listed first. Where a
 * ticket's journey and a saved trip's fit alike (they share the vehicle the user is on), the
 * ticket is followed ([isChosen], [preferredFit]): the user said they are making that trip.
 * [latest] is the last state a screen saw, for [TicketKeeper], which must not ask for location.
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
    /** A fix that takes longer than this (a tunnel, indoors) leaves every trip unsure while it is waited for. */
    private val slowFixMs: Long = 5_000,
    /** A trip stays unsure this long after the last fix that could not rule it out, so the question does not flicker. */
    private val unsureHoldMs: Long = 60_000,
    /** How often [position] is read again. */
    private val positionIntervalMs: Long = 60_000,
    /** A fix this old or newer is reused for [position] instead of asking again. */
    private val positionMaxAgeMs: Long = 2 * 60_000,
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
     * app is hidden, which is how previews run: nothing polls); [told] as if the user had said so.
     */
    fun showForPreview(match: FollowMatch, fix: LocationFix?, told: Boolean = false) {
        memory.value = if (told) FollowMemory().tell(match) else FollowMemory().switchTo(match)
        lastFix.value = fix
    }

    /** Previews and screenshots: ask ON BOARD? about [unsure] (trip id to its journeys under way), as in a tunnel. */
    fun showAskingForPreview(unsure: Map<String, List<Journey>>) {
        memory.value = FollowMemory()
        previewUnsure.value = unsure
    }

    private val previewUnsure = MutableStateFlow<Map<String, List<Journey>>>(emptyMap())

    /** The user switched to another way of the trip (see [switchedMatch]): follow [match] from now on. */
    fun switchTo(match: FollowMatch) {
        if (!isOneOff(match.wayId)) oneOff.value = null
        memory.update { it.switchTo(match) }
    }

    /**
     * ON BOARD: the user says they are on [journey] of saved trip [tripId] (the location could
     * not tell). Followed from now, at the ride the clock puts it on, until it arrives or
     * [notOnThis].
     */
    fun onBoard(tripId: String, journey: Journey) {
        oneOff.value = null
        val start = FollowMatch(tripId, journey.departure.ref, 0, FollowStage.RIDING, 0.0, journey.wayId)
        memory.update { it.tell(progressByClock(start, journey, clock.nowMillis()), tuning) }
    }

    /** NOT ON THIS: the user takes back what they said with [onBoard]; nothing is followed. */
    fun notOnThis() {
        oneOff.value = null
        memory.update { it.letGo(tuning) }
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

    /**
     * Where the phone is, for Home to put the trips that start here first: the last fix while it is
     * under [positionMaxAgeMs] old (following takes them often while a journey is under way), else
     * a new one, every [positionIntervalMs]. Only while the app is in the [foreground] and location
     * permission was already given (it never asks); null otherwise, or while no fix has come.
     * Independent of the Follow my journey setting.
     */
    val position: Flow<LatLng?> = combine(platform.locationPermission, foreground) { p, fg -> p == PermissionState.GRANTED && fg }
        .distinctUntilChanged()
        .flatMapLatest { ok ->
            if (!ok) flowOf<LatLng?>(null)
            else channelFlow<LatLng?> {
                while (true) {
                    val now = clock.nowMillis()
                    val recent = lastFix.value?.takeIf { now - it.timeMs in 0..positionMaxAgeMs }
                    send((recent ?: platform.currentFix(now))?.position)
                    delay(positionIntervalMs)
                }
            }
        }
        .distinctUntilChanged()

    private val latestState = MutableStateFlow(FollowState())

    /**
     * The last [state] a screen collected (IDLE before any). Hot, and asks for nothing: for
     * watchers that should not start location themselves ([TicketKeeper]).
     */
    val latest: StateFlow<FollowState> = latestState.asStateFlow()

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
                !g.foreground -> combine(memory, lastFix, previewUnsure) { m, fix, ask ->
                    when {
                        m.pinned != null -> FollowState(FollowStatus.FOLLOWING, m.pinned, fix, told = m.told)
                        ask.isNotEmpty() -> FollowState(FollowStatus.LOOKING, fix = fix, unsure = ask)
                        else -> FollowState(FollowStatus.IDLE, fix = fix)
                    }
                }
                else -> combine(polling(), memory) { s, m ->
                    // On a journey, nothing is asked.
                    if (m.pinned != null) s.copy(status = FollowStatus.FOLLOWING, match = m.pinned, told = m.told, unsure = emptyMap())
                    else s.copy(status = if (s.status == FollowStatus.FOLLOWING) FollowStatus.LOOKING else s.status, match = null)
                }
            }
        }
        .distinctUntilChanged()
        .onEach { latestState.value = it }

    private fun polling(): Flow<FollowState> = channelFlow {
        // The live vehicles of every line under way, kept while polling (the core polls them).
        val routes = MutableStateFlow<Set<RouteId>>(emptySet())
        val vehicles = MutableStateFlow<List<VehiclePosition>>(emptyList())
        launch {
            routes.flatMapLatest { if (it.isEmpty()) flowOf(emptyList()) else core.vehicles(it) }
                .collect { vehicles.value = it }
        }
        // Until when each trip stays unsure (ON BOARD? stays up between fixes that cannot tell).
        val unsureUntil = HashMap<String, Long>()
        while (true) {
            val taken = liveOneOff()
            val underway = underwayJourneys() + listOfNotNull(taken?.let { oneOff.value?.tripId?.to(it) })
            if (underway.isEmpty()) {
                routes.value = emptySet()
                unsureUntil.clear()
                memory.update { it.next(null, emptySet()) }
                send(FollowState(FollowStatus.IDLE))
                delay(idleIntervalMs)
                continue
            }
            routes.value = underway.flatMap { (_, j) -> j.legs.map { it.routeId } }.toSet()
            val keys = underway.map { it.second.departure.ref }.toSet()
            val trips = underway.map { it.first }.toSet()
            // The saved journeys under way, by trip: what the user can say they are on. A way back
            // has no card of its own to ask on until it is followed (as a ticket).
            val askable = underway.filterNot { isOneOff(it.second.wayId) || isReturnId(it.first) }
            // The trips still unsure at [now], each with its journeys under way.
            fun unsure(now: Long): Map<String, List<Journey>> {
                unsureUntil.entries.removeAll { it.value <= now || it.key !in trips }
                return askable.filter { it.first in unsureUntil }.groupBy({ it.first }, { it.second })
            }
            // A fix that is slow to come (a tunnel, indoors): unsure while it is waited for.
            val asking = async { platform.currentFix(clock.nowMillis()) }
            val quick = withTimeoutOrNull(slowFixMs) { Asked(asking.await()) }
            if (quick == null) {
                send(FollowState(FollowStatus.LOOKING, unsure = askable.groupBy({ it.first }, { it.second })))
            }
            val fix = asking.await()
            val now = clock.nowMillis()
            if (fix == null) {
                // No fix (location off on the phone, or none in time): no news, not a miss. Journeys
                // that have arrived still end, and the user is asked instead.
                memory.update { m -> byClock(m.next(null, keys, emptyMap(), tuning), underway, now) }
                for (id in trips) unsureUntil[id] = now + unsureHoldMs
                send(FollowState(FollowStatus.LOOKING, unsure = unsure(now)))
            } else {
                val live = vehicles.value
                val candidates = underway.map { (tripId, j) -> candidate(tripId, j, live) }
                val readings = readFix(fix, candidates, tuning)
                // Two ways sharing a vehicle read alike; per journey, its best reading counts.
                val evidence = readings.groupBy { it.key }.mapValues { (_, r) -> r.maxOf { it.evidence } }
                memory.update { m ->
                    val match = preferredFit(readings.mapNotNull { it.match }, m.pinned, m.scores, tuning)
                    val n = m.next(match, keys, evidence, tuning)
                    // A fix that fits the journey the user said they are on says where on it; else the clock.
                    if (match != null && match.key == n.pinned?.key) n else byClock(n, underway, now)
                }
                lastFix.value = fix
                for (id in unsureTrips(fix, candidates, tuning)) unsureUntil[id] = now + unsureHoldMs
                send(FollowState(FollowStatus.LOOKING, fix = fix, unsure = unsure(now)))
            }
            delay(intervalMs)
        }
    }

    /** The answer of [PlatformServices.currentFix], which may be null (told from "still asking"). */
    private class Asked(val fix: LocationFix?)

    /**
     * [m] with the journey the user said they are on moved along by the clock: with no fix that
     * fits it, the live times of its rides say which one the user is on, or changing to.
     */
    private fun byClock(m: FollowMemory, underway: List<Pair<String, Journey>>, nowMs: Long): FollowMemory {
        val p = m.pinned?.takeIf { m.told } ?: return m
        val mine = underway.filter { (id, j) -> id == p.tripId && j.departure.ref == p.key }
        val way = p.wayId
        val j = (mine.firstOrNull { (_, j) -> way != null && (j.wayId == way || way in j.alsoWays) } ?: mine.firstOrNull())?.second ?: return m
        return m.copy(pinned = progressByClock(p, j, nowMs))
    }

    /**
     * The journeys under way of every ticket not yet arrived (first: the user set off on them),
     * then of every saved trip, then of the way back of every saved trip ([impliedReturns]: the
     * user rides home on a route saved one way only), each with its trip, ticket or way-back id.
     */
    private suspend fun underwayJourneys(): List<Pair<String, Journey>> {
        val tickets = runCatching { core.tickets.first() }.getOrDefault(emptyList())
            .filter { it.status != TicketStatus.ARRIVED }.map { it.id }
        val saved = core.trips.first()
        val trips = saved.map { it.id }
        val returns = impliedReturns(saved).map { it.id }
        return (tickets + trips + returns).distinct().flatMap { id -> core.underway(id).first().map { id to it } }
    }

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
