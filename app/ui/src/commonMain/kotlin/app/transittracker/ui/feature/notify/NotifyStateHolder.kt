package app.transittracker.ui.feature.notify

import app.transittracker.model.DataStatus
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceRef
import app.transittracker.model.ServiceTime
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchKind
import app.transittracker.ui.format.formatClock
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.platform.PlatformServices
import app.transittracker.ui.settings.ClockFormat
import app.transittracker.ui.state.StateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/** A watch as the list draws it. */
data class WatchCard(
    val watch: WatchDto,
    val summary: WatchSummary,
    /** The pause switch: on = the watch is working. */
    val active: Boolean,
    /** "PAUSED" / "PAUSED UNTIL FRI 13 NOV" while paused. */
    val pausedWords: String?,
) {
    val id: String get() = watch.id
}

/** A change that did not go through, for the RETRY / CLOSE dialog (plan 6). */
data class NotifyProblem(val words: String, val retry: PendingChange)

/** What to do again when the user taps RETRY. */
data class PendingChange(val watchId: String, val paused: Boolean)

/** Everything the Notify tab draws (plan 5.9). */
data class NotifyUiState(
    /** True until the watch list has been read once. */
    val loading: Boolean = true,
    /** Commute watches, earliest window first. */
    val commutes: List<WatchCard> = emptyList(),
    /** One-off watches for services today. */
    val today: List<WatchCard> = emptyList(),
    /** One-off watches for services on later days. */
    val later: List<WatchCard> = emptyList(),
    /** How many of the [limit] slots are used. */
    val count: Int = 0,
    val limit: Int = WATCH_LIMIT,
    /** Notifications are not allowed on this phone: the ALLOW banner. */
    val notificationsBlocked: Boolean = false,
    /** Battery saver is on: the tip says so. */
    val batterySaver: Boolean = false,
    /** No connection: the list is the last one received and cannot be changed (plan 6). */
    val readOnly: Boolean = false,
    /** Saved trips, for adding a watch. */
    val trips: List<SavedTrip> = emptyList(),
    val problem: NotifyProblem? = null,
) {
    val isEmpty: Boolean get() = !loading && count == 0
    val full: Boolean get() = count >= limit
    val canAdd: Boolean get() = !readOnly && trips.isNotEmpty() && !full
}

/**
 * State for the Notify tab: the server's watches (through the core), saved trips, notification
 * permission and battery saver. Pausing is a save of the watch with a pause time; a failed change
 * becomes [NotifyUiState.problem] with RETRY / CLOSE.
 */
class NotifyStateHolder(
    private val core: TransitCore,
    private val platform: PlatformServices,
    private val clock: WallClock,
    scope: CoroutineScope,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val format: ClockFormat = ClockFormat.H24,
    private val tickMs: Long = 30_000,
) : StateHolder<NotifyUiState>(scope) {
    /** Watch id -> departure time of a one-off watch's service, "07:42". */
    private val departures = MutableStateFlow<Map<String, String>>(emptyMap())
    private val asked = mutableSetOf<String>()
    private val problem = MutableStateFlow<NotifyProblem?>(null)

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(tickMs)
        }
    }

    private val inputs = combine(
        core.watches.map<_, List<WatchDto>?> { it }.onStart { emit(null) },
        core.trips,
        core.dataStatus.map<_, DataStatus?> { it }.onStart { emit(null) },
        platform.notificationPermission,
        platform.batterySaver,
    ) { watches, trips, status, permission, saver -> Inputs(watches, trips, status, permission, saver) }

    private class Inputs(
        val watches: List<WatchDto>?,
        val trips: List<SavedTrip>,
        val status: DataStatus?,
        val permission: PermissionState,
        val saver: Boolean,
    )

    override val state: StateFlow<NotifyUiState> = combine(inputs, departures, problem, ticker) { i, deps, prob, now ->
        val watches = i.watches
        if (watches == null) return@combine NotifyUiState()
        lookUpDepartures(watches)
        val names = tripStationNames(i.trips)
        val cards = watches.map { w ->
            val paused = isPaused(w, now)
            WatchCard(
                w, watchSummary(w, names[w.originStationId], names[w.destinationStationId], deps[w.id]),
                active = !paused, pausedWords = pausedWords(w, now, zone),
            )
        }
        val today = ServiceTime.localDate(now, zone)
        val singles = cards.filter { it.watch.kind == WatchKind.SINGLE_SERVICE }
        NotifyUiState(
            loading = false,
            commutes = cards.filter { it.watch.kind == WatchKind.COMMUTE }.sortedBy { it.watch.windowStartSecs },
            today = singles.filter { it.watch.serviceDate == null || it.watch.serviceDate == today },
            later = singles.filter { it.watch.serviceDate != null && it.watch.serviceDate != today }.sortedBy { it.watch.serviceDate },
            count = watches.size,
            notificationsBlocked = i.permission != PermissionState.GRANTED,
            batterySaver = i.saver,
            readOnly = i.status?.serverReachable == false,
            trips = i.trips.sortedBy { it.order },
            problem = prob,
        )
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, NotifyUiState())

    /** The pause switch: [paused] true stops the watch's notifications, false resumes them. */
    fun setPaused(id: String, paused: Boolean) {
        val watch = state.value.let { s -> (s.commutes + s.today + s.later).firstOrNull { it.id == id }?.watch } ?: return
        if (state.value.readOnly) return
        scope.launch {
            val now = clock.nowMillis()
            val changed = watch.copy(pausedUntilMs = if (paused) now + PAUSE_FOREVER_MS else null)
            try {
                core.saveWatch(changed)
                problem.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                problem.value = NotifyProblem(failureWords(e, "change"), PendingChange(id, paused))
            }
        }
    }

    /** RETRY in the problem dialog. */
    fun retry() {
        val p = problem.value ?: return
        problem.value = null
        setPaused(p.retry.watchId, p.retry.paused)
    }

    /** CLOSE in the problem dialog. */
    fun dismissProblem() {
        problem.value = null
    }

    /**
     * ALLOW on the banner: asks for the permission; once it was refused the system will not ask
     * again, so the phone's notification settings open instead.
     */
    fun allowNotifications() {
        scope.launch {
            if (platform.notificationPermission.value == PermissionState.DENIED) platform.openNotificationSettings()
            else platform.requestNotificationPermission()
        }
    }

    /** The phone's notification settings (the battery-saver tip). */
    fun openPhoneSettings() = platform.openNotificationSettings()

    private fun lookUpDepartures(watches: List<WatchDto>) {
        for (w in watches) {
            val trip = w.tripId ?: continue
            val date = w.serviceDate ?: continue
            if (!asked.add(w.id)) continue
            scope.launch {
                val detail = runCatching { core.service(ServiceRef(trip, date)).first() }.getOrNull() ?: return@launch
                val call = detail.calls.firstOrNull { it.stopId == w.originStationId } ?: return@launch
                val at = call.expectedDepartureMs ?: call.scheduledDepartureMs ?: return@launch
                departures.update { it + (w.id to formatClock(at, zone, format)) }
            }
        }
    }
}
