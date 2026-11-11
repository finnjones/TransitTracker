package app.transittracker.server.spi

import app.transittracker.model.Alert
import app.transittracker.model.AlertsResponse
import app.transittracker.model.Mode
import app.transittracker.model.PackInfo
import app.transittracker.model.RealtimeResponse
import app.transittracker.model.RouteId
import app.transittracker.model.ServiceDate
import app.transittracker.model.Timetable
import app.transittracker.model.TripDelta
import app.transittracker.model.TripId
import app.transittracker.model.VehiclesResponse
import app.transittracker.model.WatchDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/*
 * Interfaces between server modules. Each is implemented by exactly one module and consumed
 * by others; `:server:app` wires the real implementations together in Main.
 *
 *   TimetableRepository  <- :server:pipeline
 *   LiveData             <- :server:live
 *   PushSender, WatchService <- :server:watch
 *   DeviceRepository, WatchRepository, NotificationLog <- :server:app (SQLite)
 */

/** Thrown by any module to produce an HTTP error; mapped to problem JSON by :server:app. */
class ApiException(val status: Int, val code: String, message: String) : RuntimeException(message)

// ---------------------------------------------------------------------------------------------
// Timetable (implemented by :server:pipeline)

interface TimetableRepository {
    /** The live timetable (all current packs combined), or null before the first successful build. */
    val current: StateFlow<Timetable?>

    /** Published packs, newest version of each. */
    fun packs(): List<PackInfo>

    /** File holding the compressed bytes of a published pack version, if it exists. */
    fun packFile(name: String, version: String): Path?

    /** Checks TfNSW for new GTFS and rebuilds changed packs. */
    suspend fun refresh(force: Boolean = false): RefreshResult

    /** Runs the nightly schedule in [scope]; also loads existing packs from disk at start. */
    fun start(scope: CoroutineScope)
}

data class RefreshResult(val rebuilt: List<String>, val unchanged: List<String>, val failed: Map<String, String>)

// ---------------------------------------------------------------------------------------------
// Live data (implemented by :server:live)

enum class DemandKind { SCREEN, TRACKING, WATCH }

interface LiveData {
    /** Live data for trips on [routeIds]; fetches from TfNSW only if cached data is older than the demand's minimum age. */
    suspend fun realtime(routeIds: Set<RouteId>, demand: DemandKind = DemandKind.SCREEN): RealtimeResponse

    /** Live data for one service (tracking). */
    suspend fun trip(tripId: TripId, serviceDate: ServiceDate, demand: DemandKind = DemandKind.TRACKING): RealtimeResponse

    suspend fun vehicles(routeIds: Set<RouteId>): VehiclesResponse

    /** Current alerts, optionally filtered by mode, with an ETag over the filtered content. */
    suspend fun alerts(modes: Set<Mode>? = null): AlertsSnapshot

    /**
     * Keep feeds serving [routeIds] fresh at the [demand]'s minimum age until [untilMillis],
     * even with no phone requests. Used by the watch engine. Calling again extends/updates.
     */
    fun keepWarm(key: String, routeIds: Set<RouteId>, demand: DemandKind, untilMillis: Long)

    /** Emitted after each trip-updates refresh: every trip whose delta changed (or became stale). */
    val tripChanges: SharedFlow<TripChangeBatch>

    /** Emitted when the alert set changes. */
    val alertChanges: SharedFlow<List<Alert>>

    /** Latest delta for a trip from the cache, without fetching. */
    fun cachedTrip(tripId: TripId, serviceDate: ServiceDate): TripDelta?

    fun status(): LiveStatus

    fun start(scope: CoroutineScope)
}

data class TripChangeBatch(val feedId: String, val dataTimeMs: Long, val stale: Boolean, val changed: List<TripDelta>)

data class AlertsSnapshot(val etag: String, val response: AlertsResponse)

data class LiveStatus(
    val callsToday: Int,
    val dailyBudget: Int,
    val feeds: List<FeedStatus>,
)

data class FeedStatus(
    val feedId: String,
    /** "tripupdates" | "vehicles" | "alerts" */
    val kind: String,
    val lastFetchMs: Long?,
    val lastSuccessMs: Long?,
    val dataTimeMs: Long?,
    val lastHttpStatus: Int?,
    val consecutiveErrors: Int,
    val backoffUntilMs: Long?,
    val matchedTrips: Int,
    val unmatchedTrips: Int,
    val stale: Boolean,
)

// ---------------------------------------------------------------------------------------------
// Push (implemented by :server:watch)

data class PushMessage(
    val title: String,
    val body: String,
    /** See [app.transittracker.model.PushKeys]. */
    val data: Map<String, String>,
    /** Same key replaces the previous notification instead of stacking. */
    val collapseKey: String,
    val ttlSeconds: Long,
    val highPriority: Boolean = true,
)

sealed interface PushResult {
    data class Sent(val messageId: String) : PushResult
    /** Token no longer valid: clear it. */
    data object InvalidToken : PushResult
    data class Failed(val reason: String, val retryable: Boolean) : PushResult
}

interface PushSender {
    suspend fun send(token: String, message: PushMessage): PushResult
}

/** Watch management used by the HTTP API. */
interface WatchService {
    fun list(deviceId: String): List<WatchDto>
    /** Validates and stores; throws [ApiException] (400) on invalid input. */
    fun upsert(deviceId: String, watch: WatchDto): WatchDto
    fun delete(deviceId: String, watchId: String): Boolean
    suspend fun sendTest(deviceId: String, watchId: String): PushResult
    fun status(): WatchStatus
    fun start(scope: CoroutineScope)
}

data class WatchStatus(val totalWatches: Int, val activeWatches: Int, val sentToday: Int, val failedToday: Int)

// ---------------------------------------------------------------------------------------------
// Persistence (implemented by :server:app over SQLite)

data class DeviceRecord(
    val deviceId: String,
    val pushToken: String?,
    val platform: String,
    val appVersion: String,
    val notificationsEnabled: Boolean,
    val createdMs: Long,
    val lastSeenMs: Long,
    val revoked: Boolean,
)

interface DeviceRepository {
    fun get(deviceId: String): DeviceRecord?
    fun all(): List<DeviceRecord>
    fun clearPushToken(deviceId: String)
}

data class StoredWatch(val deviceId: String, val watch: WatchDto)

interface WatchRepository {
    fun list(deviceId: String): List<WatchDto>
    fun all(): List<StoredWatch>
    fun get(deviceId: String, watchId: String): WatchDto?
    fun upsert(deviceId: String, watch: WatchDto)
    fun delete(deviceId: String, watchId: String): Boolean
}

data class NotificationLogEntry(
    val atMs: Long,
    val deviceId: String,
    val watchId: String?,
    val tripId: String?,
    val serviceDate: String?,
    /** PushKeys.KIND value */
    val kind: String,
    val title: String,
    val result: String,
)

interface NotificationLog {
    fun record(entry: NotificationLogEntry)
    fun recent(limit: Int = 100): List<NotificationLogEntry>
}
