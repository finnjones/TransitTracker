package app.transittracker.ui.feature.follow

import app.transittracker.model.Journey
import app.transittracker.model.ServiceRef
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.ui.platform.LocationFix
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.platform.PlatformServices
import app.transittracker.ui.settings.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * Follow my journey: which journey of a saved trip the phone is on, from its location.
 *
 * One per app (`AppDeps.follower`), so what it has worked out survives moving between Home and
 * the journey map. [state] is cold: location is asked for only while a screen collects it, the
 * app is in the [foreground], the setting is on and permission was already given (it never asks
 * for permission itself). While a saved trip has a journey under way it takes a fix every
 * [intervalMs] and matches it ([matchFix]); otherwise it only checks every [idleIntervalMs]
 * whether one has started. [FollowMemory] keeps the answer steady between fixes.
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

    /**
     * Previews and screenshots: follow [match] as if the phone had been at [fix] (shown while the
     * app is hidden, which is how previews run: nothing polls).
     */
    fun showForPreview(match: FollowMatch, fix: LocationFix?) {
        memory.value = FollowMemory(pinned = match)
        lastFix.value = fix
    }

    /** "Not on this one": stop following the journey whose first leg is [key], and never pick it again. */
    fun dismiss(key: ServiceRef) = memory.update { it.dismiss(key) }

    private data class Gate(val on: Boolean, val permission: PermissionState, val foreground: Boolean)

    /** What following is doing; re-emits after every fix and on [dismiss]. */
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

    private fun polling(): Flow<FollowState> = flow {
        while (true) {
            val underway = underwayJourneys()
            if (underway.isEmpty()) {
                memory.update { it.next(null, emptySet()) }
                emit(FollowState(FollowStatus.IDLE))
                delay(idleIntervalMs)
                continue
            }
            val fix = platform.currentFix(clock.nowMillis())
            if (fix == null) {
                // No fix (location off on the phone, or none in time): no news, not a miss.
                emit(FollowState(FollowStatus.LOOKING))
            } else {
                val candidates = underway.map { (tripId, j) -> candidate(tripId, j) }
                val match = matchFix(fix, candidates, tuning)
                memory.updateAndGet { it.next(match, underway.map { it.second.departure.ref }.toSet()) }
                lastFix.value = fix
                emit(FollowState(FollowStatus.LOOKING, fix = fix))
            }
            delay(intervalMs)
        }
    }

    private suspend fun underwayJourneys(): List<Pair<String, Journey>> =
        core.trips.first().flatMap { t -> core.underway(t.id).first().map { t.id to it } }

    private suspend fun candidate(tripId: String, journey: Journey): FollowCandidate =
        FollowCandidate(
            tripId, journey,
            journey.legs.map { d ->
                val calls = runCatching { core.service(d.ref).first().calls }.getOrDefault(emptyList())
                RiddenLeg(d, riddenCalls(calls, d.originStopId, d.destinationStopId))
            },
        )
}
