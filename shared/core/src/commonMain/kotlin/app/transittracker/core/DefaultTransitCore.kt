@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)

package app.transittracker.core

import app.transittracker.model.Alert
import app.transittracker.model.AlarmKind
import app.transittracker.model.AlarmSpec
import app.transittracker.model.ApiError
import app.transittracker.model.ConfigResponse
import app.transittracker.model.DataStatus
import app.transittracker.model.DepartureStatus
import app.transittracker.model.DeviceUpdate
import app.transittracker.model.JoinRequest
import app.transittracker.model.JoinResult
import app.transittracker.model.Journey
import app.transittracker.model.LatLng
import app.transittracker.model.PackStatus
import app.transittracker.model.PushKeys
import app.transittracker.model.Route
import app.transittracker.model.RouteId
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceDetail
import app.transittracker.model.ServiceRef
import app.transittracker.model.ServiceTime
import app.transittracker.model.ShapeId
import app.transittracker.model.Stop
import app.transittracker.model.StopId
import app.transittracker.model.TrackingState
import app.transittracker.model.TransitCore
import app.transittracker.model.VehiclePosition
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchKind
import app.transittracker.model.WatchRules
import app.transittracker.model.WatchesResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Thrown by [TransitCore] write operations when the server rejects or cannot be reached. */
class TransitCoreException(
    message: String,
    /** HTTP status, or null when the server could not be reached. */
    val status: Int? = null,
    val error: ApiError? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The real [TransitCore]: timetable packs + live overlay + server API. Create with
 * [TransitCoreFactory.create].
 *
 * All background work (pack checks, polling) runs on [CoreDependencies.scope]; cancel that scope
 * to shut the core down.
 */
class DefaultTransitCore internal constructor(
    private val config: CoreConfig,
    private val deps: CoreDependencies,
) : TransitCore {
    private val scope: CoroutineScope = deps.scope
    private val kv = deps.kv

    /** Server-corrected clock used for every "now". */
    val timeSync = TimeSync(deps.clock)

    private val tokenState = MutableStateFlow<String?>(null)
    private val tokenLoaded = MutableStateFlow(false)

    /** Low-level API client (exposed for diagnostics). */
    val api = ApiClient(deps.httpClient, config.baseUrl, timeSync) { loadToken() }

    private val holder = TimetableHolder(deps.ioContext)
    private val updater = PackUpdater(
        api, kv, deps.files, deps.opener, deps.decompressor, deps.network, holder, config, scope, deps.ioContext,
    )

    /** Live polling engine (exposed for diagnostics and tests). */
    val poller = LivePoller(api, timeSync, scope) {
        updater.installed.value.flatMap { it.modes }.toSet()
    }
    private val tripStore = TripStore(kv)
    private val configState = MutableStateFlow<ConfigResponse?>(null)
    private val watchesState = MutableStateFlow<List<WatchDto>?>(null)
    private val startMutex = Mutex()
    private var started = false
    private val checkMutex = Mutex()

    /** Results of the latest pack update check. */
    val lastPackReport: Flow<List<PackUpdateResult>> = updater.lastReport

    // ---- Lifecycle ----

    override suspend fun start() {
        startMutex.withLock {
            if (started) return
            started = true
        }
        loadToken()
        kv.get(KvKeys.CONFIG)?.let { json ->
            runCatching { ApiJson.decodeFromString(ConfigResponse.serializer(), json) }.getOrNull()?.let(::applyConfig)
        }
        updater.openInstalled()
        poller.start()
        scope.launch {
            while (true) {
                checkForUpdates()
                delay(config.packCheckIntervalMs)
            }
        }
    }

    override fun onForeground() = poller.onForeground()

    override fun onBackground() = poller.onBackground()

    /**
     * Fetches `/v1/config`, applies poll intervals and installs newer packs. Runs on start, every
     * [CoreConfig.packCheckIntervalMs] and after joining. Returns null when not joined or offline.
     */
    suspend fun checkForUpdates(): List<PackUpdateResult>? = checkMutex.withLock {
        if (loadToken() == null) return null
        sendPendingPushToken()
        val result = api.config()
        if (result !is ApiResult.Success) return null
        val cfg = result.value
        applyConfig(cfg)
        kv.put(KvKeys.CONFIG, ApiJson.encodeToString(ConfigResponse.serializer(), cfg))
        updater.update(cfg.packs)
    }

    private fun applyConfig(cfg: ConfigResponse) {
        configState.value = cfg
        poller.applyIntervals(cfg.pollIntervalS, cfg.trackerPollIntervalS)
    }

    private suspend fun loadToken(): String? {
        if (!tokenLoaded.value) {
            tokenState.value = kv.get(KvKeys.DEVICE_TOKEN)
            tokenLoaded.value = true
        }
        return tokenState.value
    }

    // ---- Setup ----

    override val joined: Flow<Boolean> = flow {
        loadToken()
        emitAll(tokenState.map { it != null })
    }.distinctUntilChanged()

    override suspend fun join(inviteCode: String): JoinResult {
        val result = api.join(JoinRequest(inviteCode.trim(), config.platform, config.appVersion))
        return when (result) {
            is ApiResult.Success -> {
                kv.put(KvKeys.DEVICE_TOKEN, result.value.deviceToken)
                kv.put(KvKeys.DEVICE_ID, result.value.deviceId)
                tokenState.value = result.value.deviceToken
                tokenLoaded.value = true
                scope.launch {
                    checkForUpdates()
                    refreshWatches()
                }
                JoinResult.Joined(result.value.deviceId)
            }
            is ApiResult.HttpFailure ->
                if (result.status in 400..499 && result.status != 408 && result.status != 429) {
                    JoinResult.InvalidCode
                } else {
                    JoinResult.Failed(result.error?.title ?: "Server error ${result.status}")
                }
            is ApiResult.NetworkFailure -> JoinResult.Failed(result.message)
        }
    }

    override suspend fun updatePushToken(token: String) {
        kv.put(KvKeys.PUSH_TOKEN, token)
        kv.put(KvKeys.PUSH_TOKEN_PENDING, token)
        sendPendingPushToken()
    }

    private suspend fun sendPendingPushToken() {
        val pending = kv.get(KvKeys.PUSH_TOKEN_PENDING) ?: return
        if (loadToken() == null) return
        val result = api.updateDevice(DeviceUpdate(pushToken = pending, appVersion = config.appVersion))
        if (result is ApiResult.Success && kv.get(KvKeys.PUSH_TOKEN_PENDING) == pending) {
            kv.put(KvKeys.PUSH_TOKEN_PENDING, null)
        }
    }

    // ---- Saved trips ----

    override val trips: Flow<List<SavedTrip>> = tripStore.trips

    override suspend fun saveTrip(trip: SavedTrip) = tripStore.save(trip)

    override suspend fun deleteTrip(id: String) = tripStore.delete(id)

    override suspend fun reorderTrips(ids: List<String>) = tripStore.reorder(ids)

    // ---- Departures ----

    override fun upcoming(tripId: String, count: Int): Flow<List<Journey>> =
        journeysFlow(tripId) { planner, trip, now ->
            planner.upcoming(trip, now, count) to true
        }

    override fun timetable(tripId: String, serviceDate: ServiceDate): Flow<List<Journey>> =
        journeysFlow(tripId) { planner, trip, now ->
            planner.forServiceDay(trip, serviceDate) to (serviceDate in ServiceTime.candidateServiceDates(now))
        }

    /** Shared plumbing: recompute on trip / pack / live changes and every [TICK_MS]; lease routes for polling. */
    private fun journeysFlow(
        tripId: String,
        compute: (JourneyPlanner, SavedTrip, Long) -> Pair<List<Journey>, Boolean>,
    ): Flow<List<Journey>> = flow {
        val lease = poller.leaseRoutes()
        try {
            val trip = tripStore.trips.map { list -> list.firstOrNull { it.id == tripId } }.distinctUntilChanged()
            emitAll(
                combine(trip, holder.generation, poller.snapshot, ticker()) { t, _, snap, _ -> t to snap }
                    .mapLatest { (t, snap) ->
                        if (t == null) {
                            lease.set(emptySet())
                            return@mapLatest emptyList()
                        }
                        val now = timeSync.nowMillis()
                        val computed = holder.read { tt ->
                            val planner = JourneyPlanner(tt, Overlay(tt, snap, now))
                            val (journeys, live) = compute(planner, t, now)
                            Triple(journeys, live, planner.routesOf(t, journeys))
                        }
                        lease.set(if (computed != null && computed.second) computed.third else emptySet())
                        computed?.first.orEmpty()
                    }
                    .distinctUntilChanged(),
            )
        } finally {
            lease.close()
        }
    }

    override fun service(ref: ServiceRef): Flow<ServiceDetail> = flow {
        val lease = poller.leaseRoutes()
        try {
            emitAll(
                combine(holder.generation, poller.snapshot, ticker()) { _, snap, _ -> snap }
                    .mapLatest { snap ->
                        val now = timeSync.nowMillis()
                        val detail = holder.read { tt -> Overlay(tt, snap, now).serviceDetail(ref) }
                        val route = detail?.route?.id ?: snap[ref.tripId, ref.serviceDate]?.delta?.routeId
                        lease.set(setOfNotNull(route?.takeIf { it.isNotEmpty() }))
                        detail ?: ServiceDetail(ref, null, "", DepartureStatus.TIMETABLED, emptyList())
                    }
                    .distinctUntilChanged(),
            )
        } finally {
            lease.close()
        }
    }

    override fun track(ref: ServiceRef): Flow<TrackingState> = flow {
        val tripLease = poller.leaseTrip()
        val vehicleLease = poller.leaseVehicles()
        tripLease.set(setOf(TripKey(ref.tripId, ref.serviceDate)))
        try {
            emitAll(
                combine(service(ref), poller.vehicles) { detail, vehicles ->
                    val route = detail.route?.id ?: poller.snapshot.value[ref.tripId, ref.serviceDate]?.delta?.routeId
                    vehicleLease.set(setOfNotNull(route?.takeIf { it.isNotEmpty() }))
                    val vehicle = vehicles.values.asSequence().flatten().firstOrNull {
                        it.tripId == ref.tripId && (it.startDate == null || it.startDate == ref.serviceDate)
                    }
                    TrackingState(
                        detail = detail,
                        vehicle = vehicle,
                        nextCall = detail.calls.firstOrNull { !it.passed && !it.skipped },
                        updatedMs = timeSync.nowMillis(),
                    )
                },
            )
        } finally {
            tripLease.close()
            vehicleLease.close()
        }
    }

    // ---- Reference data ----

    override suspend fun searchStops(query: String, near: LatLng?): List<Stop> {
        val found = holder.read { it.searchStops(query, SEARCH_LIMIT) }.orEmpty()
        return if (near == null) found else found.sortedBy { distanceMeters(near, it.position) }
    }

    override suspend fun stopsNear(position: LatLng, radiusMeters: Double): List<Stop> =
        holder.read { it.stopsNear(position, radiusMeters, SEARCH_LIMIT) }.orEmpty()

    override suspend fun linesAt(stopId: StopId): List<Route> = holder.read { tt ->
        (tt.stopsAtStation(stopId).map { it.id } + stopId).distinct()
            .flatMap { tt.routesAtStop(it) }
            .distinctBy { it.id }
            .sortedBy { it.shortName }
    }.orEmpty()

    override suspend fun shape(shapeId: ShapeId): List<LatLng> {
        holder.read { it.shape(shapeId) }?.let { return it }
        val remote = api.shape(shapeId).valueOrNull() ?: return emptyList()
        return runCatching { decodePolyline(remote.encodedPolyline) }.getOrDefault(emptyList())
    }

    override fun vehicles(routeIds: Set<RouteId>): Flow<List<VehiclePosition>> = flow {
        val lease = poller.leaseVehicles()
        lease.set(routeIds)
        try {
            emitAll(poller.vehicles.map { byRoute -> routeIds.flatMap { byRoute[it].orEmpty() } }.distinctUntilChanged())
        } finally {
            lease.close()
        }
    }

    // ---- Alerts and status ----

    override val alerts: Flow<List<Alert>> = poller.alerts.onStart {
        if (loadToken() != null) scope.launch { poller.ensureAlerts() }
    }

    override val dataStatus: Flow<DataStatus> = combine(
        updater.status,
        poller.snapshot,
        api.reachable,
        joined.onStart { emit(false) },
        combine(configState, ticker()) { cfg, _ -> cfg },
    ) { packs, live, reachable, joined, cfg ->
        val now = timeSync.nowMillis()
        val names = (packs.keys + config.defaultPacks).sorted()
        val last = live.lastUpdateMs
        DataStatus(
            packs = names.map { packs[it] ?: PackStatus(it, null, null, updateAvailable = false) },
            lastLiveUpdateMs = last,
            liveStale = live.feedsStale || (last != null && now - last > 2 * live.pollIntervalMs),
            serverReachable = reachable ?: false,
            joined = joined,
            attribution = cfg?.attribution ?: DEFAULT_ATTRIBUTION,
            statusMessage = cfg?.statusMessage,
        )
    }.distinctUntilChanged()

    // ---- Watches ----

    override val watches: Flow<List<WatchDto>> = watchesState
        .onStart {
            if (watchesState.value == null) {
                watchesState.value = kv.get(KvKeys.WATCHES)?.let {
                    runCatching { ApiJson.decodeFromString(WatchesResponse.serializer(), it).watches }.getOrNull()
                }.orEmpty()
            }
            scope.launch { refreshWatches() }
        }
        .filterNotNull()

    /** Reloads watches from the server into the offline cache. Returns false if that failed. */
    suspend fun refreshWatches(): Boolean {
        if (loadToken() == null) return false
        val result = api.watches()
        if (result !is ApiResult.Success) return false
        setWatches(result.value.watches)
        return true
    }

    private suspend fun setWatches(list: List<WatchDto>) {
        watchesState.value = list
        kv.put(KvKeys.WATCHES, ApiJson.encodeToString(WatchesResponse.serializer(), WatchesResponse(list)))
    }

    private suspend fun cachedWatches(): List<WatchDto> = watchesState.value ?: kv.get(KvKeys.WATCHES)?.let {
        runCatching { ApiJson.decodeFromString(WatchesResponse.serializer(), it).watches }.getOrNull()
    }.orEmpty()

    /** @throws TransitCoreException if the server rejects the watch or cannot be reached. */
    override suspend fun saveWatch(watch: WatchDto) {
        val now = timeSync.nowMillis()
        val toSend = watch.copy(createdMs = if (watch.createdMs == 0L) now else watch.createdMs, updatedMs = now)
        val saved = api.putWatch(toSend).orThrow("Could not save watch")
        val list = cachedWatches()
        setWatches(if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it } else list + saved)
    }

    override suspend fun watchService(ref: ServiceRef, rules: WatchRules): WatchDto =
        watchService(ref, rules, originStationId = null, destinationStationId = null)

    /**
     * Creates a SINGLE_SERVICE watch for [ref]. By default the watch covers the service's first
     * and last stations; pass [originStationId] / [destinationStationId] to watch only the part
     * the rider travels on.
     *
     * @throws IllegalArgumentException if the service is unknown.
     * @throws TransitCoreException if the server rejects the watch.
     */
    suspend fun watchService(
        ref: ServiceRef,
        rules: WatchRules,
        originStationId: StopId?,
        destinationStationId: StopId?,
    ): WatchDto {
        val now = timeSync.nowMillis()
        val snap = poller.snapshot.value
        val info = holder.read { tt ->
            val detail = Overlay(tt, snap, now).serviceDetail(ref) ?: return@read null
            fun station(id: StopId) = tt.stop(id)?.parentId ?: id
            val first = detail.calls.firstOrNull() ?: return@read null
            val last = detail.calls.last()
            val routeId = detail.route?.id ?: snap[ref.tripId, ref.serviceDate]?.delta?.routeId
            val origin = originStationId ?: station(first.stopId)
            val originCall = detail.calls.firstOrNull { station(it.stopId) == origin } ?: first
            val time = originCall.scheduledDepartureMs ?: originCall.scheduledArrivalMs ?: now
            val label = listOfNotNull(
                detail.route?.shortName,
                formatLocalTime(time),
                tt.stop(origin)?.name ?: originCall.stopName,
                "to",
                tt.stop(destinationStationId ?: station(last.stopId))?.name ?: last.stopName,
            ).joinToString(" ")
            WatchDraft(origin, destinationStationId ?: station(last.stopId), routeId, label)
        } ?: throw IllegalArgumentException("Unknown service ${ref.tripId} on ${ref.serviceDate}")
        val watch = WatchDto(
            id = Uuid.random().toString(),
            kind = WatchKind.SINGLE_SERVICE,
            label = info.label,
            originStationId = info.origin,
            destinationStationId = info.destination,
            routeIds = listOfNotNull(info.routeId?.takeIf { it.isNotEmpty() }),
            rules = rules,
            tripId = ref.tripId,
            serviceDate = ref.serviceDate,
            createdMs = now,
            updatedMs = now,
        )
        saveWatch(watch)
        return cachedWatches().firstOrNull { it.id == watch.id } ?: watch
    }

    private data class WatchDraft(val origin: StopId, val destination: StopId, val routeId: RouteId?, val label: String)

    /** @throws TransitCoreException if the server cannot be reached (an unknown id counts as deleted). */
    override suspend fun deleteWatch(id: String) {
        val result = api.deleteWatch(id)
        if (!(result is ApiResult.HttpFailure && result.status == 404)) result.orThrow("Could not delete watch")
        setWatches(cachedWatches().filter { it.id != id })
    }

    override suspend fun sendTestNotification(id: String): Boolean = api.testWatch(id).valueOrNull()?.sent ?: false

    override fun openFromNotification(data: Map<String, String>): ServiceRef? = parseNotification(data)

    // ---- Alarms ----

    override suspend fun nextAlarmTimes(alarm: AlarmSpec, count: Int): List<Long> {
        val trip = tripStore.get(alarm.savedTripId) ?: return emptyList()
        val now = timeSync.nowMillis()
        return holder.read { tt -> alarmTimes(tt, trip, alarm, now, count) }.orEmpty()
    }

    private fun <T> ApiResult<T>.orThrow(what: String): T = when (this) {
        is ApiResult.Success -> value
        is ApiResult.HttpFailure -> throw TransitCoreException("$what: ${error?.title ?: "HTTP $status"}", status, error)
        is ApiResult.NetworkFailure -> throw TransitCoreException("$what: $message", cause = cause)
    }

    private fun ticker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }

    companion object {
        /** How often flows re-evaluate departures as time passes. */
        const val TICK_MS = 30_000L
        const val SEARCH_LIMIT = 30
        const val DEFAULT_ATTRIBUTION = "Data: Transport for NSW (CC BY 4.0)"
    }
}

/** Maps an FCM data payload (see [PushKeys]) to the service it is about, if any. */
fun parseNotification(data: Map<String, String>): ServiceRef? {
    val tripId = data[PushKeys.TRIP_ID]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val date = data[PushKeys.SERVICE_DATE]?.trim() ?: return null
    if (date.length != 8 || !date.all { it.isDigit() }) return null
    if (runCatching { ServiceTime.parseDate(date) }.isFailure) return null
    return ServiceRef(tripId, date)
}

/**
 * Alarm times for [alarm] on [trip]: the scheduled journeys over the coming days (only on
 * [AlarmSpec.daysOfWeek], judged by the local date of the first departure) minus
 * [AlarmSpec.minutesBefore], after [nowMs], earliest first.
 */
fun alarmTimes(tt: app.transittracker.model.Timetable, trip: SavedTrip, alarm: AlarmSpec, nowMs: Long, count: Int, days: Int = 14): List<Long> {
    if (count <= 0) return emptyList()
    val planner = JourneyPlanner(tt, Overlay(tt, LiveSnapshot.EMPTY, nowMs))
    val today = ServiceTime.parseDate(ServiceTime.localDate(nowMs))
    val result = ArrayList<Long>()
    var extraDays = 0
    for (offset in -1..days) {
        val date = ServiceTime.formatDate(today.plus(DatePeriod(days = offset)))
        for (journey in planner.forServiceDay(trip, date)) {
            val departure = journey.departure.scheduledDepartureMs
            val day = ServiceTime.isoDayOfWeek(ServiceTime.localDate(departure))
            if (alarm.daysOfWeek.isNotEmpty() && day !in alarm.daysOfWeek) continue
            val reference = when (alarm.kind) {
                AlarmKind.BEFORE_DEPARTURE -> departure
                AlarmKind.BEFORE_ARRIVAL -> journey.arrival.scheduledArrivalMs
            }
            val at = reference - alarm.minutesBefore * 60_000L
            if (at > nowMs) result += at
        }
        if (result.size >= count && ++extraDays > 1) break
    }
    return result.distinct().sorted().take(count)
}

/** `HH:mm` in Sydney local time. */
internal fun formatLocalTime(epochMs: Long): String {
    val secs = ServiceTime.secondsOfLocalDay(epochMs)
    return "${(secs / 3600).toString().padStart(2, '0')}:${((secs % 3600) / 60).toString().padStart(2, '0')}"
}
