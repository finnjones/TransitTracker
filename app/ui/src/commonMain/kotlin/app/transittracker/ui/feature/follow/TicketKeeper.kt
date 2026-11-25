package app.transittracker.ui.feature.follow

import app.transittracker.model.Journey
import app.transittracker.model.ServiceRef
import app.transittracker.model.Ticket
import app.transittracker.model.TicketStatus
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.model.isTicketId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.launch

/*
 * TICKETS ON THE WAY: Follow my journey moves a ticket along. It is WAITING until the follower has
 * the user on one of its journeys (ON_THE_WAY), and ARRIVED once the journey followed for it has
 * arrived. The arrival time of that journey is kept per ticket, so the ticket is stamped even
 * after the follower lets go of it (it does once the journey is no longer under way). Tickets are
 * never closed here: they expire in the core, and KEEP AS ROUTE and DONE are Home's.
 */

/** A followed journey that stops being followed this long before it arrives was not the user's: its arrival is forgotten. */
const val TICKET_LET_GO_SLACK_MS = 2 * 60_000L

/**
 * The arrival times to keep, by ticket id, after the follower says [follow] at [nowMs]: the
 * arrival of [followed] (the journey [FollowState.match] names, with live times; null when not
 * known) for the ticket followed, the others as they were. A ticket's arrival is forgotten when
 * the follower is looking and no longer follows it more than [TICKET_LET_GO_SLACK_MS] before
 * that arrival: the user is not on it after all. With following off or no location there is no
 * news, and every arrival is kept.
 */
fun keptArrivals(
    arrivals: Map<String, Long>,
    follow: FollowState,
    followed: Journey?,
    nowMs: Long,
): Map<String, Long> {
    val on = follow.match?.tripId?.takeIf { isTicketId(it) }
    val looking = follow.status == FollowStatus.IDLE || follow.status == FollowStatus.LOOKING || follow.status == FollowStatus.FOLLOWING
    val kept = if (!looking) arrivals else arrivals.filter { (id, arrival) -> id == on || arrival <= nowMs + TICKET_LET_GO_SLACK_MS }
    return if (on != null && followed != null) kept + (on to followed.arrival.expectedArrivalMs) else kept
}

/**
 * The tickets among [tickets] whose status moves on at [nowMs], as they should be saved (the
 * others are left out): WAITING to ON_THE_WAY when [follow] follows the ticket's journey, and
 * ON_THE_WAY to ARRIVED once the journey followed for it has arrived (its arrival in
 * [arrivals], see [keptArrivals], has passed), stamped with that arrival. ARRIVED tickets do not
 * change.
 */
fun ticketChanges(
    tickets: List<Ticket>,
    follow: FollowState,
    arrivals: Map<String, Long>,
    nowMs: Long,
): List<Ticket> = tickets.mapNotNull { t ->
    var status = t.status
    if (status == TicketStatus.WAITING && follow.match?.tripId == t.id) status = TicketStatus.ON_THE_WAY
    val arrival = arrivals[t.id]
    if (status == TicketStatus.ON_THE_WAY && arrival != null && arrival <= nowMs) {
        return@mapNotNull t.copy(status = TicketStatus.ARRIVED, arrivedAtMs = arrival)
    }
    if (status != t.status) t.copy(status = status) else null
}

/**
 * Moves tickets from WAITING to ON_THE_WAY to ARRIVED as Follow my journey sees the user ride
 * them ([ticketChanges]). One per app (`AppDeps.ticketKeeper`), run while the app runs
 * ([run], launched by `TransitTrackerApp`). It reads [JourneyFollower.latest], so it never asks
 * for location itself: the follower moves on only while a screen shows it, and the clock stamps
 * a ticket ARRIVED when its journey's arrival passes, whether or not a screen is open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TicketKeeper(
    private val core: TransitCore,
    private val follower: JourneyFollower,
    private val clock: WallClock,
    /** How often the clock is checked while a ticket on the way waits for its journey's arrival. */
    private val tickMs: Long = 30_000,
) {
    /** Starts [run] in [scope]. */
    fun start(scope: CoroutineScope): Job = scope.launch { run() }

    /** Keeps tickets up to date until cancelled. */
    suspend fun run(): Unit = coroutineScope {
        val arrivals = MutableStateFlow<Map<String, Long>>(emptyMap())

        // The journey followed for a ticket, with live times, while the follower names one (looked
        // up again only when the journey followed changes, not at every fix).
        val followed = follower.latest
            .map { s -> s.match?.let { Followed(it) }?.takeIf { isTicketId(it.tripId) } }
            .distinctUntilChanged()
            .flatMapLatest { f -> if (f == null) flowOf(null) else followedJourney(f).map { j -> f to j } }
        launch {
            combine(follower.latest, followed) { s, f ->
                // Until the lookup catches up with a new match, its journey is not known.
                s to f?.takeIf { (which, _) -> s.match?.let { Followed(it) } == which }?.second
            }.collect { (s, j) -> arrivals.update { keptArrivals(it, s, j, clock.nowMillis()) } }
        }

        combine(core.tickets, follower.latest, arrivals, ticks(arrivals)) { tickets, follow, known, _ ->
            ticketChanges(tickets, follow, known, clock.nowMillis())
        }.collect { changes ->
            if (changes.isEmpty()) return@collect
            // Save only over the ticket as it is now: Home may have closed it meanwhile.
            val current = core.tickets.first().associateBy { it.id }
            for (t in changes) {
                val now = current[t.id] ?: continue
                if (now.status.ordinal < t.status.ordinal) core.saveTicket(now.copy(status = t.status, arrivedAtMs = t.arrivedAtMs ?: now.arrivedAtMs))
            }
        }
    }

    /** Which journey a match follows: where on it the user is left out. */
    private data class Followed(val tripId: String, val key: ServiceRef, val wayId: String?) {
        constructor(m: FollowMatch) : this(m.tripId, m.key, m.wayId)
    }

    /** The journey [m] follows (null while not known), with live times. */
    private fun followedJourney(m: Followed): Flow<Journey?> {
        if (isOneOff(m.wayId)) return follower.oneOffJourney
        return core.underway(m.tripId).map { list ->
            val mine = list.filter { it.departure.ref == m.key }
            mine.firstOrNull { m.wayId != null && (it.wayId == m.wayId || m.wayId in it.alsoWays) } ?: mine.firstOrNull()
        }.distinctUntilChanged()
    }

    /** Ticks every [tickMs] while a ticket on the way has an arrival to wait for; once otherwise. */
    private fun ticks(arrivals: Flow<Map<String, Long>>): Flow<Unit> =
        combine(core.tickets, arrivals) { tickets, known ->
            tickets.any { it.status == TicketStatus.ON_THE_WAY && it.id in known }
        }.distinctUntilChanged().flatMapLatest { waiting ->
            if (!waiting) flowOf(Unit)
            else flow {
                while (true) {
                    emit(Unit)
                    delay(tickMs)
                }
            }
        }
}
