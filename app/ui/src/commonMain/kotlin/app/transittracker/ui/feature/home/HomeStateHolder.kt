package app.transittracker.ui.feature.home

import app.transittracker.model.Journey
import app.transittracker.model.ServiceDetail
import app.transittracker.model.ServiceRef
import app.transittracker.model.ServiceTime
import app.transittracker.model.StopId
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.ui.feature.follow.FollowStage
import app.transittracker.ui.feature.follow.FollowState
import app.transittracker.ui.feature.follow.alternativesOf
import app.transittracker.ui.feature.follow.isOneOff
import app.transittracker.ui.feature.follow.switchedMatch
import app.transittracker.ui.feature.plan.routeEnds
import app.transittracker.ui.nav.JourneyEnd
import app.transittracker.ui.nav.TakeFrom
import app.transittracker.ui.nav.Route
import app.transittracker.ui.feature.follow.JourneyFollower
import app.transittracker.ui.format.shortStopName
import app.transittracker.ui.places.PlacesStore
import app.transittracker.ui.settings.SettingsStore
import app.transittracker.ui.state.StateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus

/**
 * State for the home screen (plan 5.2). Reads saved trips, their next services, data status,
 * the density setting and saved places; ticks every [tickMs] so countdowns and leave-by move.
 * Transit decisions (status, delays, which services run) come from the core; this only picks the
 * headline (never a cancelled service) and works out leave-by and banners, as the plan assigns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeStateHolder(
    private val core: TransitCore,
    private val places: PlacesStore,
    settings: SettingsStore,
    private val clock: WallClock,
    scope: CoroutineScope,
    private val tickMs: Long = 1_000,
    /** Services fetched per trip (headline, two following, spares for cancellations). */
    private val count: Int = 6,
    /** Follow my journey; null leaves every card on its next departures. */
    private val follower: JourneyFollower? = null,
) : StateHolder<HomeUiState>(scope) {
    private val stopNames = MutableStateFlow<Map<StopId, String>>(emptyMap())
    private val firstNextDay = MutableStateFlow<Map<String, Long?>>(emptyMap())
    private val asked = mutableSetOf<String>()

    private val upcoming: Flow<Map<String, List<Journey>>> = core.trips.flatMapLatest { trips ->
        if (trips.isEmpty()) flowOf(emptyMap())
        else combine(trips.map { t -> core.upcoming(t.id, count).map { t.id to it } }) { it.toMap() }
    }

    /** The journey the user is on, with live times (re-read whenever the core recomputes it). */
    private val followed: Flow<Followed?> = (follower?.state ?: flowOf(FollowState()))
        .map { it.match }
        .distinctUntilChanged()
        .flatMapLatest { m ->
            if (m == null) flowOf(null)
            // Under way, and still to come: a way can board the vehicle the user is on further along.
            else combine(
                core.underway(m.tripId),
                core.upcoming(m.tripId, count),
                follower?.oneOffJourney ?: flowOf(null),
            ) { a, b, taken ->
                val list = a + b
                val way = m.wayId
                val j = taken?.takeIf { way != null && it.wayId == way }
                    ?: (if (way != null && !isOneOff(way)) list.firstOrNull { it.departure.ref == m.key && it.follows(way) } else null)
                    ?: list.firstOrNull { it.departure.ref == m.key }
                j?.let { Followed(m, it, alternativesOf(it, m, list, clock.nowMillis())) }
            }
        }
        .onStart { emit(null) }
        .distinctUntilChanged()

    /** SWITCH: follow [alternative] (another way open from where the user is) instead of the journey on the card. */
    fun switchTo(alternative: Journey) {
        val f = lastFollowed ?: return
        follower?.switchTo(switchedMatch(f.journey, f.match, alternative))
    }

    /** The journey followed in the last state built (for SWITCH and OTHER WAYS FROM THE NEXT STOP). */
    private var lastFollowed: Followed? = null

    /**
     * OTHER WAYS FROM <next stop>: the planner's search from where the user can next get off (the
     * next stop of the ride, at its expected arrival; while changing, the stop being walked to,
     * now) to the trip's end. Browse only: it is not a saved route. Null when either end is not known.
     */
    suspend fun replanFromHere(): Route.JourneyOptions? {
        val followed = lastFollowed ?: return null
        val m = followed.match
        val leg = followed.journey.legs.getOrNull(m.legIndex) ?: return null
        val now = clock.nowMillis()
        val calls = runCatching { core.service(leg.ref).first().calls }.getOrDefault(emptyList())
        val riding = m.stage == FollowStage.RIDING
        val (call, atMs) = if (riding) {
            val c = calls.firstOrNull { !it.passed && it.position != null && (it.expectedArrivalMs ?: it.scheduledArrivalMs ?: Long.MAX_VALUE) > now }
                ?: return null
            c to (c.expectedArrivalMs ?: c.scheduledArrivalMs ?: now)
        } else {
            (calls.firstOrNull { it.stopId == leg.originStopId && it.position != null } ?: return null) to now
        }
        val to = routeEnds(core, places, m.tripId)?.second ?: return null
        // TAKE THIS ONE starts from the ride the user is on, to this stop; while changing, from the
        // ride they just left, to where they got off.
        val rideIndex = if (riding) m.legIndex else m.legIndex - 1
        val ride = followed.journey.legs.getOrNull(rideIndex)
        val takeFrom = ride?.let {
            TakeFrom(
                tripId = m.tripId, wayId = m.wayId, rideIndex = rideIndex, ride = it.ref, boardStopId = it.originStopId,
                nextStopId = if (riding) call.stopId else it.destinationStopId,
                nextStopPosition = call.position!!, changing = !riding,
            )
        }
        return Route.JourneyOptions(
            JourneyEnd(shortStopName(call.stopName), call.position!!), to,
            timeMs = atMs.coerceAtLeast(now), browseOnly = true, takeFrom = takeFrom,
        )
    }

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(tickMs)
        }
    }

    private val inputs: Flow<HomeInputs> = combine(
        core.trips.map<_, List<app.transittracker.model.SavedTrip>?> { it }.onStart { emit(null) },
        upcoming.onStart { emit(emptyMap()) },
        core.dataStatus.map<_, app.transittracker.model.DataStatus?> { it }.onStart { emit(null) },
        settings.settings.map { it.cardDensity }.distinctUntilChanged(),
        combine(places.places, places.tripPlaces) { p, l -> p to l },
    ) { trips, up, status, density, (p, l) -> HomeInputs(trips, up, status, density, p, l) }

    override val state: StateFlow<HomeUiState> = combine(
        inputs, stopNames, firstNextDay, ticker, followed,
    ) { i, names, first, now, f ->
        val full = i.copy(stopNames = names, firstNextDay = first, follow = f)
        lastFollowed = f
        lookUpMissing(full)
        buildHomeState(full, now, ServiceTime.localDate(now))
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, HomeUiState())

    /** The stop list of [ref] for a card's journey strip (subscribe only while the card shows). */
    fun service(ref: ServiceRef): Flow<ServiceDetail> = core.service(ref)

    /** Starts one-off lookups the state needs: change station names, tomorrow's first service. */
    private fun lookUpMissing(i: HomeInputs) {
        val trips = i.trips ?: return
        for ((_, journeys) in i.upcoming) {
            val j = headlineOf(journeys) ?: continue
            for (leg in j.legs.drop(1)) lookUpName(leg.ref, leg.originStopId)
        }
        // The journey the user is on: where each ride starts and ends.
        i.follow?.journey?.legs?.forEach { leg ->
            lookUpName(leg.ref, leg.originStopId)
            lookUpName(leg.ref, leg.destinationStopId)
        }
        // The other ways open from there: where they change or end.
        i.follow?.alternatives?.forEach { j ->
            j.legs.forEach { leg ->
                lookUpName(leg.ref, leg.originStopId)
                lookUpName(leg.ref, leg.destinationStopId)
            }
        }
        for (t in trips) {
            if (i.upcoming[t.id]?.isEmpty() != true) continue
            once("first:${t.id}") {
                val next = ServiceTime.formatDate(ServiceTime.parseDate(ServiceTime.localDate(clock.nowMillis())).plus(1, DateTimeUnit.DAY))
                val first = core.timetable(t.id, next).first().firstOrNull()?.departure?.expectedDepartureMs
                firstNextDay.update { it + (t.id to first) }
            }
        }
    }

    private fun lookUpName(ref: ServiceRef, stopId: StopId) = once("name:$stopId") {
        val call = core.service(ref).first().calls.firstOrNull { it.stopId == stopId }
        if (call != null) stopNames.update { it + (stopId to shortStopName(call.stopName)) }
    }

    private fun once(key: String, block: suspend () -> Unit) {
        if (!asked.add(key)) return
        scope.launch { runCatching { block() } }
    }
}
