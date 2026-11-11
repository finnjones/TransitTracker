package app.transittracker.ui.feature.notify

import app.transittracker.model.ServiceDetail
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopCall
import app.transittracker.model.StopId
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchKind
import app.transittracker.model.WatchRules
import app.transittracker.ui.format.formatClock
import app.transittracker.ui.format.shortStopName
import app.transittracker.ui.nav.Route
import app.transittracker.ui.settings.ClockFormat
import app.transittracker.ui.state.StateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class EditorMode { NEW_COMMUTE, NEW_ONE_OFF, EXISTING }

/** The trip as a ticket: [to] is null when only the server's label is known. */
data class WatchTicket(val from: String, val to: String?, val detail: String)

enum class ResultKind { OK, INFO, ERROR }

/** The line under SEND TEST / SAVE WATCH (plan 6: a result line, a plain sentence). */
data class ResultLine(val text: String, val kind: ResultKind)

/** Everything the watch editor draws (plan 5.9, Notify mockup). */
data class WatchEditorState(
    val loading: Boolean = true,
    /** The watch to edit is gone, or there is nothing to build a new one from. */
    val missing: Boolean = false,
    val mode: EditorMode = EditorMode.NEW_COMMUTE,
    val kind: WatchKind = WatchKind.COMMUTE,
    val ticket: WatchTicket? = null,
    /** "WATCH 3 OF 10". */
    val slot: String = "",
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
    val startSecs: Int = 0,
    val endSecs: Int = 0,
    val leadMinutes: Int = 45,
    val thresholdMinutes: Int = 5,
    val notifyCancel: Boolean = true,
    val notifyPlatform: Boolean = true,
    val notifyAlerts: Boolean = true,
    val notifyRecovered: Boolean = false,
    val saving: Boolean = false,
    val testing: Boolean = false,
    val deleting: Boolean = false,
    val result: ResultLine? = null,
    val confirmDelete: Boolean = false,
    /** Saved or deleted: the screen goes back. */
    val finished: Boolean = false,
) {
    val isCommute: Boolean get() = kind == WatchKind.COMMUTE
    val canDelete: Boolean get() = mode == EditorMode.EXISTING
    val busy: Boolean get() = saving || testing || deleting

    /** Why SAVE WATCH is off, in a plain sentence; null when the watch can be saved. */
    val problem: String? get() = when {
        isCommute && days.isEmpty() -> "Pick at least one day."
        else -> null
    }
    val canSave: Boolean get() = !loading && !missing && !busy && problem == null

    val canStartEarlier: Boolean get() = steppedStart(startSecs, endSecs, -1) != null
    val canStartLater: Boolean get() = steppedStart(startSecs, endSecs, 1) != null
    val canEndEarlier: Boolean get() = steppedEnd(startSecs, endSecs, -1) != null
    val canEndLater: Boolean get() = steppedEnd(startSecs, endSecs, 1) != null
    val canLeadLess: Boolean get() = steppedLead(leadMinutes, -1) != null
    val canLeadMore: Boolean get() = steppedLead(leadMinutes, 1) != null
    val canThresholdLower: Boolean get() = steppedThreshold(thresholdMinutes, -1) != null
    val canThresholdRaise: Boolean get() = steppedThreshold(thresholdMinutes, 1) != null
}

/**
 * State for the watch editor. It starts from one of three things (the route says which): an
 * existing watch, a saved trip (a new commute watch) or one service (a new one-off watch).
 * Saving can fail; the reason is a plain sentence in [WatchEditorState.result].
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
class WatchEditorStateHolder(
    private val core: TransitCore,
    private val route: Route.WatchEditor,
    private val clock: WallClock,
    scope: CoroutineScope,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val format: ClockFormat = ClockFormat.H24,
    private val newId: () -> String = { Uuid.random().toString() },
) : StateHolder<WatchEditorState>(scope) {
    private val mutable = MutableStateFlow(WatchEditorState())
    override val state: StateFlow<WatchEditorState> = mutable.asStateFlow()

    /** The watch being edited, or the commute watch a new one starts from (no id yet used). */
    private var base: WatchDto? = null

    init {
        scope.launch { load() }
    }

    // ---------------------------------------------------------------- loading

    private suspend fun load() {
        val watches = runCatching { core.watches.first() }.getOrDefault(emptyList())
        val trips = runCatching { core.trips.first() }.getOrDefault(emptyList())
        val names = tripStationNames(trips)
        when {
            route.watchId != null -> {
                val w = watches.firstOrNull { it.id == route.watchId } ?: return missing()
                base = w
                val ticket = if (w.kind == WatchKind.SINGLE_SERVICE && w.tripId != null && w.serviceDate != null) {
                    oneOffTicket(ServiceRef(w.tripId!!, w.serviceDate!!), w.originStationId, w.destinationStationId)
                } else null
                mutable.value = fromWatch(w, EditorMode.EXISTING, ticket ?: ticketOf(w, names), slot = watches.indexOfFirst { it.id == w.id } + 1)
            }
            route.fromService != null -> {
                val ticket = oneOffTicket(route.fromService, route.boardStopId, route.alightStopId) ?: return missing()
                mutable.value = WatchEditorState(
                    loading = false, mode = EditorMode.NEW_ONE_OFF, kind = WatchKind.SINGLE_SERVICE, ticket = ticket,
                    slot = slotWords(minOf(watches.size + 1, WATCH_LIMIT)),
                    thresholdMinutes = WatchRules().delayThresholdMin,
                )
            }
            route.fromTripId != null -> {
                val trip = trips.firstOrNull { it.id == route.fromTripId } ?: return missing()
                val first = trip.legs.firstOrNull() ?: return missing()
                val last = trip.legs.last()
                val origin = resolveStation(first.originStopIds, trip.originName)
                val destination = resolveStation(last.destinationStopIds, trip.destinationName)
                val routeIds = if (trip.legs.size == 1) first.routeIds.orEmpty().toList() else emptyList()
                val (start, end) = defaultWindow(secondsOfDay(clock.nowMillis()))
                val draft = WatchDto(
                    id = "", kind = WatchKind.COMMUTE, label = "${trip.originName} to ${trip.destinationName}",
                    originStationId = origin, destinationStationId = destination, routeIds = routeIds,
                    windowStartSecs = start, windowEndSecs = end,
                )
                base = draft
                mutable.value = fromWatch(
                    draft, EditorMode.NEW_COMMUTE,
                    WatchTicket(trip.originName, trip.destinationName, lineWords(routeIds, origin)),
                    slot = minOf(watches.size + 1, WATCH_LIMIT),
                )
            }
            else -> missing()
        }
    }

    private fun missing() {
        mutable.value = WatchEditorState(loading = false, missing = true)
    }

    private fun fromWatch(w: WatchDto, mode: EditorMode, ticket: WatchTicket?, slot: Int) = WatchEditorState(
        loading = false, mode = mode, kind = w.kind, ticket = ticket, slot = slotWords(slot),
        days = w.daysOfWeek.toSet(), startSecs = w.windowStartSecs, endSecs = w.windowEndSecs, leadMinutes = w.leadMinutes,
        thresholdMinutes = w.rules.delayThresholdMin, notifyCancel = w.rules.notifyCancel, notifyPlatform = w.rules.notifyPlatform,
        notifyAlerts = w.rules.notifyAlerts, notifyRecovered = w.rules.notifyRecovered,
    )

    private suspend fun ticketOf(w: WatchDto, names: Map<StopId, String>): WatchTicket {
        val from = names[w.originStationId]
        val to = names[w.destinationStationId]
        return if (from != null && to != null) WatchTicket(from, to, lineWords(w.routeIds, w.originStationId))
        else WatchTicket(w.label.ifBlank { "Watch" }, null, lineWords(w.routeIds, w.originStationId))
    }

    /** "ANY LINE", or the lines' short names ("T1, T9"; ids when a name cannot be found). */
    private suspend fun lineWords(routeIds: List<String>, originStationId: StopId): String {
        if (routeIds.isEmpty()) return "ANY LINE"
        val known = runCatching { core.linesAt(originStationId) }.getOrDefault(emptyList()).associate { it.id to it.shortName }
        return routeIds.joinToString(", ") { known[it] ?: it }.uppercase()
    }

    /** The ticket for one service: its stops and times, from the service's own stop list. */
    private suspend fun oneOffTicket(ref: ServiceRef, boardId: StopId?, alightId: StopId?): WatchTicket? {
        val detail: ServiceDetail = runCatching { core.service(ref).first() }.getOrNull() ?: return null
        if (detail.calls.isEmpty()) return null
        val board: StopCall = detail.calls.firstOrNull { it.stopId == boardId } ?: detail.calls.first()
        val alight: StopCall = detail.calls.lastOrNull { it.stopId == alightId } ?: detail.calls.last()
        val at = (board.expectedDepartureMs ?: board.scheduledDepartureMs)?.let { formatClock(it, zone, format) }
        val detailWords = listOfNotNull(
            detail.route?.shortName?.takeIf { it.isNotBlank() }, at?.let { "LEAVES $it" }, board.platform?.let { "PLAT $it" },
        ).joinToString(" · ").ifBlank { "THIS SERVICE ONLY" }
        return WatchTicket(shortStopName(board.stopName), shortStopName(alight.stopName), detailWords.uppercase())
    }

    private suspend fun resolveStation(ids: Set<StopId>, name: String): StopId {
        if (ids.size == 1) return ids.first()
        val found = runCatching { core.searchStops(name) }.getOrDefault(emptyList())
        return found.firstOrNull { it.id in ids }?.id
            ?: found.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
            ?: ids.firstOrNull().orEmpty()
    }

    private fun secondsOfDay(ms: Long): Int {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
        return t.hour * 3600 + t.minute * 60
    }

    // ---------------------------------------------------------------- editing

    private fun edit(change: (WatchEditorState) -> WatchEditorState) = mutable.update { if (it.loading || it.missing) it else change(it) }

    fun setDays(days: Set<Int>) = edit { it.copy(days = days) }
    fun stepStart(direction: Int) = edit { s -> steppedStart(s.startSecs, s.endSecs, direction)?.let { s.copy(startSecs = it) } ?: s }
    fun stepEnd(direction: Int) = edit { s -> steppedEnd(s.startSecs, s.endSecs, direction)?.let { s.copy(endSecs = it) } ?: s }
    fun stepLead(direction: Int) = edit { s -> steppedLead(s.leadMinutes, direction)?.let { s.copy(leadMinutes = it) } ?: s }
    fun stepThreshold(direction: Int) = edit { s -> steppedThreshold(s.thresholdMinutes, direction)?.let { s.copy(thresholdMinutes = it) } ?: s }
    fun setNotifyCancel(on: Boolean) = edit { it.copy(notifyCancel = on) }
    fun setNotifyPlatform(on: Boolean) = edit { it.copy(notifyPlatform = on) }
    fun setNotifyAlerts(on: Boolean) = edit { it.copy(notifyAlerts = on) }
    fun setNotifyRecovered(on: Boolean) = edit { it.copy(notifyRecovered = on) }

    private fun rulesOf(s: WatchEditorState, previous: WatchRules) = previous.copy(
        delayThresholdMin = s.thresholdMinutes, notifyCancel = s.notifyCancel, notifyPlatform = s.notifyPlatform,
        notifyAlerts = s.notifyAlerts, notifyRecovered = s.notifyRecovered,
    )

    // ---------------------------------------------------------------- actions

    /** SAVE WATCH: creates or updates the watch; on success [WatchEditorState.finished] turns true. */
    fun save() {
        val s = mutable.value
        if (!s.canSave) return
        mutable.update { it.copy(saving = true, result = null) }
        scope.launch {
            try {
                // Every slot used: say so without asking the server (a new watch only).
                if (s.mode != EditorMode.EXISTING && core.watches.first().size >= WATCH_LIMIT) {
                    mutable.update { it.copy(saving = false, result = ResultLine(limitWords(), ResultKind.ERROR)) }
                    return@launch
                }
                when (s.mode) {
                    EditorMode.NEW_ONE_OFF -> core.watchService(route.fromService!!, rulesOf(s, WatchRules()))
                    EditorMode.NEW_COMMUTE -> core.saveWatch(
                        base!!.copy(
                            id = newId(), daysOfWeek = s.days.sorted(), windowStartSecs = s.startSecs, windowEndSecs = s.endSecs,
                            leadMinutes = s.leadMinutes, rules = rulesOf(s, base!!.rules),
                        ),
                    )
                    EditorMode.EXISTING -> core.saveWatch(
                        base!!.copy(
                            daysOfWeek = if (s.isCommute) s.days.sorted() else base!!.daysOfWeek,
                            windowStartSecs = s.startSecs, windowEndSecs = s.endSecs, leadMinutes = s.leadMinutes,
                            rules = rulesOf(s, base!!.rules),
                        ),
                    )
                }
                mutable.update { it.copy(saving = false, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                mutable.update { it.copy(saving = false, result = ResultLine(failureWords(e, "save"), ResultKind.ERROR)) }
            }
        }
    }

    /** SEND TEST: asks the server to push a test notification for the saved watch. */
    fun sendTest() {
        val s = mutable.value
        if (s.busy || s.loading || s.missing) return
        val id = if (s.mode == EditorMode.EXISTING) base?.id else null
        if (id == null) {
            mutable.update { it.copy(result = ResultLine(TEST_NEEDS_SAVE_WORDS, ResultKind.INFO)) }
            return
        }
        mutable.update { it.copy(testing = true, result = null) }
        scope.launch {
            val sent = try {
                core.sendTestNotification(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                false
            }
            mutable.update {
                it.copy(testing = false, result = if (sent) ResultLine(TEST_SENT_WORDS, ResultKind.OK) else ResultLine(TEST_FAILED_WORDS, ResultKind.ERROR))
            }
        }
    }

    /** DELETE WATCH: opens the confirmation. */
    fun askDelete() = mutable.update { if (it.canDelete && !it.busy) it.copy(confirmDelete = true) else it }

    /** Keep the watch: closes the confirmation. */
    fun cancelDelete() = mutable.update { it.copy(confirmDelete = false) }

    /** DELETE in the confirmation. */
    fun confirmDelete() {
        val id = base?.id ?: return
        if (!mutable.value.canDelete) return
        mutable.update { it.copy(confirmDelete = false, deleting = true, result = null) }
        scope.launch {
            try {
                core.deleteWatch(id)
                mutable.update { it.copy(deleting = false, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                mutable.update { it.copy(deleting = false, result = ResultLine(failureWords(e, "delete"), ResultKind.ERROR)) }
            }
        }
    }
}
