package app.transittracker.server.app

import app.transittracker.model.Alert
import app.transittracker.model.AlertsResponse
import app.transittracker.model.Mode
import app.transittracker.model.PackInfo
import app.transittracker.model.PushKeys
import app.transittracker.model.RealtimeResponse
import app.transittracker.model.RouteId
import app.transittracker.model.ServiceDate
import app.transittracker.model.Timetable
import app.transittracker.model.TripDelta
import app.transittracker.model.TripId
import app.transittracker.model.VehiclesResponse
import app.transittracker.model.WallClock
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchKind
import app.transittracker.server.spi.AlertsSnapshot
import app.transittracker.server.spi.ApiException
import app.transittracker.server.spi.DemandKind
import app.transittracker.server.spi.DeviceRepository
import app.transittracker.server.spi.LiveData
import app.transittracker.server.spi.LiveStatus
import app.transittracker.server.spi.NotificationLog
import app.transittracker.server.spi.NotificationLogEntry
import app.transittracker.server.spi.PushMessage
import app.transittracker.server.spi.PushResult
import app.transittracker.server.spi.PushSender
import app.transittracker.server.spi.RefreshResult
import app.transittracker.server.spi.ServerConfig
import app.transittracker.server.spi.TimetableRepository
import app.transittracker.server.spi.TripChangeBatch
import app.transittracker.server.spi.WatchRepository
import app.transittracker.server.spi.WatchService
import app.transittracker.server.spi.WatchStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.slf4j.LoggerFactory
import java.nio.file.Path

/*
 * Wiring of the server modules. Every place that will switch from a stub to the real
 * implementation after the module branches are merged is marked `// INTEGRATION:`.
 */

/** The module implementations tt-server runs with. */
class Services(
    val timetables: TimetableRepository,
    val live: LiveData,
    val push: PushSender,
    val watches: WatchService,
) {
    /** Starts every module's background work in [scope] (timetable first: the others read it). */
    fun start(scope: CoroutineScope) {
        timetables.start(scope)
        live.start(scope)
        watches.start(scope)
    }
}

/** Builds the module implementations over the app's SQLite repositories. */
fun wireServices(
    config: ServerConfig,
    devices: DeviceRepository,
    watchRepository: WatchRepository,
    notificationLog: NotificationLog,
    clock: WallClock = SystemClock,
): Services {
    // INTEGRATION: replace with the :server:pipeline TimetableRepository, e.g.
    //   PackTimetableRepository(config, clock)
    val timetables: TimetableRepository = StubTimetableRepository()

    // INTEGRATION: replace with the :server:live LiveData, e.g.
    //   LiveDataService(config, timetables, clock)
    val live: LiveData = StubLiveData(config, clock)

    // INTEGRATION: replace with the :server:watch FCM PushSender, e.g.
    //   FcmPushSender(config)  (only when config.firebaseServiceAccountPath != null)
    val push: PushSender = StubPushSender()

    // INTEGRATION: replace with the :server:watch WatchService, e.g.
    //   WatchEngine(config, timetables, live, push, devices, watchRepository, notificationLog, clock)
    val watches: WatchService = StubWatchService(watchRepository, devices, push, notificationLog, clock)

    return Services(timetables, live, push, watches)
}

// ---- stubs (delete once the real modules are wired in) ----------------------------------------

/** INTEGRATION: stub until :server:pipeline is merged. Has no packs and no timetable. */
class StubTimetableRepository : TimetableRepository {
    override val current: StateFlow<Timetable?> = MutableStateFlow(null)
    override fun packs(): List<PackInfo> = emptyList()
    override fun packFile(name: String, version: String): Path? = null
    override suspend fun refresh(force: Boolean) = RefreshResult(emptyList(), emptyList(), emptyMap())
    override fun start(scope: CoroutineScope) {
        LoggerFactory.getLogger(javaClass).warn("Using StubTimetableRepository: no timetable packs will be built")
    }
}

/** INTEGRATION: stub until :server:live is merged. Returns empty, well-formed live responses. */
class StubLiveData(private val config: ServerConfig, private val clock: WallClock) : LiveData {
    override suspend fun realtime(routeIds: Set<RouteId>, demand: DemandKind) = empty()
    override suspend fun trip(tripId: TripId, serviceDate: ServiceDate, demand: DemandKind) = empty()
    override suspend fun vehicles(routeIds: Set<RouteId>) =
        VehiclesResponse(serverTimeMs = clock.nowMillis(), pollIntervalS = config.pollIntervalS)
    override suspend fun alerts(modes: Set<Mode>?) = AlertsSnapshot("none", AlertsResponse(clock.nowMillis()))
    override fun keepWarm(key: String, routeIds: Set<RouteId>, demand: DemandKind, untilMillis: Long) = Unit
    override val tripChanges: SharedFlow<TripChangeBatch> = MutableSharedFlow()
    override val alertChanges: SharedFlow<List<Alert>> = MutableSharedFlow()
    override fun cachedTrip(tripId: TripId, serviceDate: ServiceDate): TripDelta? = null
    override fun status() = LiveStatus(callsToday = 0, dailyBudget = config.dailyCallBudget, feeds = emptyList())
    override fun start(scope: CoroutineScope) {
        LoggerFactory.getLogger(javaClass).warn("Using StubLiveData: no live data will be fetched")
    }

    private fun empty() = RealtimeResponse(
        serverTimeMs = clock.nowMillis(),
        pollIntervalS = config.pollIntervalS,
        trackerPollIntervalS = config.trackerPollIntervalS,
        alertsEtag = "none",
    )
}

/** INTEGRATION: stub until :server:watch is merged. Sends nothing. */
class StubPushSender : PushSender {
    override suspend fun send(token: String, message: PushMessage): PushResult =
        PushResult.Failed("Push sending is not configured on this server", retryable = false)
}

/**
 * INTEGRATION: stub until :server:watch is merged. Validates and stores watches through the
 * [WatchRepository] but never evaluates them; test notifications go through [push].
 */
class StubWatchService(
    private val repository: WatchRepository,
    private val devices: DeviceRepository,
    private val push: PushSender,
    private val log: NotificationLog,
    private val clock: WallClock,
) : WatchService {
    override fun list(deviceId: String): List<WatchDto> = repository.list(deviceId)

    override fun upsert(deviceId: String, watch: WatchDto): WatchDto {
        validate(watch)
        val now = clock.nowMillis()
        val createdMs = repository.get(deviceId, watch.id)?.createdMs ?: now
        val stored = watch.copy(createdMs = createdMs, updatedMs = now)
        repository.upsert(deviceId, stored)
        return stored
    }

    override fun delete(deviceId: String, watchId: String): Boolean = repository.delete(deviceId, watchId)

    override suspend fun sendTest(deviceId: String, watchId: String): PushResult {
        val watch = repository.get(deviceId, watchId) ?: throw ApiException(404, "watch_not_found", "No such watch")
        val token = devices.get(deviceId)?.pushToken
            ?: return PushResult.Failed("No push token registered for this device", retryable = false)
        val title = "Test notification"
        val result = push.send(
            token,
            PushMessage(
                title = title,
                body = "Notifications for \"${watch.label.ifBlank { "your watch" }}\" are working",
                data = mapOf(PushKeys.KIND to "test", PushKeys.WATCH_ID to watchId),
                collapseKey = "test-$watchId",
                ttlSeconds = 300,
            ),
        )
        if (result == PushResult.InvalidToken) devices.clearPushToken(deviceId)
        val outcome = when (result) {
            is PushResult.Sent -> "sent"
            PushResult.InvalidToken -> "invalid_token"
            is PushResult.Failed -> "failed: ${result.reason}"
        }
        log.record(NotificationLogEntry(clock.nowMillis(), deviceId, watchId, null, null, "test", title, outcome))
        return result
    }

    override fun status(): WatchStatus {
        val total = repository.all().size
        return WatchStatus(totalWatches = total, activeWatches = 0, sentToday = 0, failedToday = 0)
    }

    override fun start(scope: CoroutineScope) {
        LoggerFactory.getLogger(javaClass).warn("Using StubWatchService: watches are stored but never evaluated")
    }

    private fun validate(watch: WatchDto) {
        fun bad(detail: String): Nothing = throw ApiException(400, "invalid_watch", detail)
        if (watch.id.isBlank()) bad("id is required")
        if (watch.originStationId.isBlank() || watch.destinationStationId.isBlank()) bad("origin and destination are required")
        if (watch.label.length > 100) bad("label too long")
        if (watch.routeIds.size > MAX_ROUTES) bad("too many routes")
        when (watch.kind) {
            WatchKind.COMMUTE -> {
                if (watch.daysOfWeek.isEmpty() || watch.daysOfWeek.any { it !in 1..7 }) bad("daysOfWeek must be 1..7")
                val day = 0..(48 * 3600)
                if (watch.windowStartSecs !in day || watch.windowEndSecs !in day || watch.windowEndSecs < watch.windowStartSecs) {
                    bad("invalid window")
                }
                if (watch.leadMinutes !in 0..240) bad("leadMinutes must be 0..240")
            }
            WatchKind.SINGLE_SERVICE -> {
                if (watch.tripId.isNullOrBlank() || watch.serviceDate.isNullOrBlank()) bad("tripId and serviceDate are required")
            }
        }
    }
}
