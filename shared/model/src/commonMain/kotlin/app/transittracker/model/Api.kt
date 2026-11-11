@file:OptIn(ExperimentalSerializationApi::class)

package app.transittracker.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/*
 * HTTP API payloads between the phone (`:shared:core`) and tt-server (`:server:app`).
 *
 * One set of classes, two encodings:
 *  - JSON (default, `application/json`) via kotlinx-serialization-json
 *  - protobuf (`application/x-protobuf`) via kotlinx-serialization-protobuf, used for the
 *    high-frequency live endpoints. Field numbers are part of the contract: never reuse or
 *    renumber; only add new fields with new numbers and defaults.
 *
 * Auth: every call except POST /v1/join sends `Authorization: Bearer <deviceToken>`.
 * Every response carries `X-Server-Time` (epoch ms).
 */

object ApiPaths {
    const val JOIN = "/v1/join"
    const val CONFIG = "/v1/config"
    const val PACKS = "/v1/packs"
    /** GET /v1/packs/{name}/{version} -> zstd-compressed SQLite pack bytes */
    const val PACK_FILE = "/v1/packs/{name}/{version}"
    /** GET /v1/rt?routes=R1,R2 */
    const val REALTIME = "/v1/rt"
    /** GET /v1/rt/trip/{tripId}?date=YYYYMMDD */
    const val REALTIME_TRIP = "/v1/rt/trip/{tripId}"
    /** GET /v1/vehicles?routes=R1,R2 */
    const val VEHICLES = "/v1/vehicles"
    /** GET /v1/alerts?modes=TRAIN,METRO (ETag / If-None-Match) */
    const val ALERTS = "/v1/alerts"
    /** GET /v1/shapes/{shapeId} */
    const val SHAPE = "/v1/shapes/{shapeId}"
    /** PUT /v1/device */
    const val DEVICE = "/v1/device"
    /** GET /v1/watches */
    const val WATCHES = "/v1/watches"
    /** PUT|DELETE /v1/watches/{id} */
    const val WATCH = "/v1/watches/{id}"
    /** POST /v1/watches/{id}/test */
    const val WATCH_TEST = "/v1/watches/{id}/test"
    /** GET /v1/places?q=text&lat=..&lon=..&limit=.. -> PlacesResponse (see Planning.kt) */
    const val PLACES = "/v1/places"
    /** POST /v1/plan (PlanRequest) -> PlanResponse (see Planning.kt) */
    const val PLAN = "/v1/plan"
    const val HEALTH = "/health"
    const val STATUS = "/status"
}

object ApiHeaders {
    const val SERVER_TIME = "X-Server-Time"
    const val POLL_INTERVAL = "X-Poll-Interval"
    const val TRACKER_POLL_INTERVAL = "X-Tracker-Poll-Interval"
}

object MediaTypes {
    const val PROTOBUF = "application/x-protobuf"
    const val JSON = "application/json"
    const val PROBLEM_JSON = "application/problem+json"
}

/** Keys in the FCM data payload; the phone reads these to open the right screen. */
object PushKeys {
    const val KIND = "kind"            // "delay" | "cancel" | "platform" | "alert" | "recovered" | "test"
    const val TRIP_ID = "tripId"
    const val SERVICE_DATE = "serviceDate"
    const val WATCH_ID = "watchId"
    const val ALERT_ID = "alertId"
}

// ---- Errors (RFC 9457 problem JSON) ----

@Serializable
data class ApiError(
    @ProtoNumber(1) val code: String,
    @ProtoNumber(2) val title: String,
    @ProtoNumber(3) val detail: String? = null,
    @ProtoNumber(4) val status: Int = 400,
)

// ---- Join / device ----

@Serializable
data class JoinRequest(
    @ProtoNumber(1) val inviteCode: String,
    @ProtoNumber(2) val platform: String = "android",
    @ProtoNumber(3) val appVersion: String = "",
)

@Serializable
data class JoinResponse(
    @ProtoNumber(1) val deviceId: String,
    @ProtoNumber(2) val deviceToken: String,
)

@Serializable
data class DeviceUpdate(
    @ProtoNumber(1) val pushToken: String? = null,
    @ProtoNumber(2) val appVersion: String? = null,
    @ProtoNumber(3) val notificationsEnabled: Boolean = true,
    @ProtoNumber(4) val locale: String? = null,
)

// ---- Config and packs ----

@Serializable
data class ConfigResponse(
    @ProtoNumber(1) val pollIntervalS: Int = 30,
    @ProtoNumber(2) val trackerPollIntervalS: Int = 15,
    @ProtoNumber(3) val minAppVersion: String = "0",
    @ProtoNumber(4) val attribution: String,
    @ProtoNumber(5) val statusMessage: String? = null,
    @ProtoNumber(6) val features: Map<String, Boolean> = emptyMap(),
    @ProtoNumber(7) val packs: List<PackInfo> = emptyList(),
    /** Most push watches one device may have. */
    @ProtoNumber(8) val maxWatches: Int = 10,
)

@Serializable
data class PackInfo(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val version: String,
    @ProtoNumber(3) val sizeBytes: Long,
    /** SHA-256 (lowercase hex) of the downloaded (compressed) bytes. */
    @ProtoNumber(4) val sha256: String,
    /** Path relative to the server base URL, e.g. `/v1/packs/syd-rail/20261111T0300`. */
    @ProtoNumber(5) val url: String,
    @ProtoNumber(6) val modes: List<Mode> = emptyList(),
    @ProtoNumber(7) val validFrom: ServiceDate = "",
    @ProtoNumber(8) val validTo: ServiceDate = "",
    /** SHA-256 of the uncompressed SQLite file, checked after decompression. */
    @ProtoNumber(9) val sqliteSha256: String = "",
)

@Serializable
data class PacksResponse(@ProtoNumber(1) val packs: List<PackInfo>)

// ---- Real-time ----

@Serializable
data class RealtimeResponse(
    @ProtoNumber(1) val serverTimeMs: Long,
    @ProtoNumber(2) val pollIntervalS: Int = 30,
    @ProtoNumber(3) val trackerPollIntervalS: Int = 15,
    /** Timetable version the trip ids were matched against. */
    @ProtoNumber(4) val timetableVersion: String = "",
    @ProtoNumber(5) val feeds: List<FeedHealth> = emptyList(),
    @ProtoNumber(6) val trips: List<TripDelta> = emptyList(),
    /** Current alerts ETag; the phone refetches /v1/alerts only when this changes. */
    @ProtoNumber(7) val alertsEtag: String? = null,
)

@Serializable
data class FeedHealth(
    @ProtoNumber(1) val feed: String,
    @ProtoNumber(2) val mode: Mode,
    /** GTFS-R header timestamp of the data served (epoch ms); 0 if never fetched. */
    @ProtoNumber(3) val dataTimeMs: Long = 0,
    @ProtoNumber(4) val stale: Boolean = false,
    /** False if the last fetch attempt failed (we may still be serving older data). */
    @ProtoNumber(5) val ok: Boolean = true,
)

@Serializable
enum class TripStatus { SCHEDULED, CANCELLED, ADDED, REPLACEMENT }

@Serializable
data class TripDelta(
    @ProtoNumber(1) val tripId: TripId,
    @ProtoNumber(2) val startDate: ServiceDate,
    @ProtoNumber(3) val routeId: RouteId = "",
    @ProtoNumber(4) val status: TripStatus = TripStatus.SCHEDULED,
    /**
     * One entry per *remaining* scheduled stop, delays already propagated (GTFS-R rules),
     * so clients never propagate. Empty for CANCELLED.
     */
    @ProtoNumber(5) val stops: List<StopDelta> = emptyList(),
    @ProtoNumber(6) val occupancy: Occupancy = Occupancy.UNKNOWN,
    @ProtoNumber(7) val carCount: Int? = null,
    @ProtoNumber(8) val vehicleId: String? = null,
    /** Timestamp of this trip's update in the feed (epoch ms). */
    @ProtoNumber(9) val updatedMs: Long = 0,
    @ProtoNumber(10) val stale: Boolean = false,
    /** Full stop list for ADDED / REPLACEMENT trips that are not in the timetable. */
    @ProtoNumber(11) val addedStops: List<AddedStop> = emptyList(),
    /** Headsign for ADDED / REPLACEMENT trips. */
    @ProtoNumber(12) val headsign: String? = null,
)

@Serializable
data class StopDelta(
    @ProtoNumber(1) val stopSequence: Int,
    /** Scheduled stop id at this sequence. */
    @ProtoNumber(2) val stopId: StopId,
    @ProtoNumber(3) val arrivalDelayS: Int? = null,
    @ProtoNumber(4) val departureDelayS: Int? = null,
    /** Set only when the live stop differs from the scheduled one (platform change). */
    @ProtoNumber(5) val actualStopId: StopId? = null,
    @ProtoNumber(6) val skipped: Boolean = false,
)

@Serializable
data class AddedStop(
    @ProtoNumber(1) val stopSequence: Int,
    @ProtoNumber(2) val stopId: StopId,
    @ProtoNumber(3) val arrivalMs: Long? = null,
    @ProtoNumber(4) val departureMs: Long? = null,
)

@Serializable
data class VehiclesResponse(
    @ProtoNumber(1) val serverTimeMs: Long,
    @ProtoNumber(2) val pollIntervalS: Int = 30,
    @ProtoNumber(3) val vehicles: List<VehiclePosition> = emptyList(),
)

@Serializable
data class VehiclePosition(
    @ProtoNumber(1) val vehicleId: String,
    @ProtoNumber(2) val tripId: TripId? = null,
    @ProtoNumber(3) val startDate: ServiceDate? = null,
    @ProtoNumber(4) val routeId: RouteId? = null,
    @ProtoNumber(5) val position: LatLng,
    @ProtoNumber(6) val bearing: Float? = null,
    @ProtoNumber(7) val speedMps: Float? = null,
    @ProtoNumber(8) val timestampMs: Long,
    @ProtoNumber(9) val occupancy: Occupancy = Occupancy.UNKNOWN,
    @ProtoNumber(10) val carCount: Int? = null,
    @ProtoNumber(11) val label: String? = null,
)

// ---- Alerts ----

@Serializable
enum class AlertSeverity { UNKNOWN, INFO, WARNING, SEVERE }

@Serializable
data class TimeRange(
    @ProtoNumber(1) val startMs: Long? = null,
    @ProtoNumber(2) val endMs: Long? = null,
)

@Serializable
data class Alert(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val headline: String,
    @ProtoNumber(3) val description: String = "",
    @ProtoNumber(4) val url: String? = null,
    @ProtoNumber(5) val severity: AlertSeverity = AlertSeverity.UNKNOWN,
    /** GTFS-R cause / effect enum names, e.g. "TRACK_WORK", "REDUCED_SERVICE". */
    @ProtoNumber(6) val cause: String? = null,
    @ProtoNumber(7) val effect: String? = null,
    @ProtoNumber(8) val activePeriods: List<TimeRange> = emptyList(),
    @ProtoNumber(9) val routeIds: List<RouteId> = emptyList(),
    @ProtoNumber(10) val stopIds: List<StopId> = emptyList(),
    @ProtoNumber(11) val tripIds: List<TripId> = emptyList(),
    @ProtoNumber(12) val modes: List<Mode> = emptyList(),
    @ProtoNumber(13) val updatedMs: Long = 0,
)

@Serializable
data class AlertsResponse(
    @ProtoNumber(1) val serverTimeMs: Long,
    @ProtoNumber(2) val alerts: List<Alert> = emptyList(),
)

// ---- Shapes ----

@Serializable
data class ShapeResponse(
    @ProtoNumber(1) val shapeId: ShapeId,
    /** Google encoded polyline (precision 5). */
    @ProtoNumber(2) val encodedPolyline: String,
)

// ---- Watches (push notifications) ----

@Serializable
enum class WatchKind { COMMUTE, SINGLE_SERVICE }

@Serializable
data class WatchRules(
    @ProtoNumber(1) val delayThresholdMin: Int = 5,
    /** Re-notify when the delay grows by at least this much since the last notification. */
    @ProtoNumber(2) val delayStepMin: Int = 5,
    @ProtoNumber(3) val notifyCancel: Boolean = true,
    @ProtoNumber(4) val notifyPlatform: Boolean = true,
    @ProtoNumber(5) val notifyAlerts: Boolean = true,
    @ProtoNumber(6) val notifyRecovered: Boolean = false,
)

@Serializable
data class WatchDto(
    /** Client-generated UUID; PUT /v1/watches/{id} is an upsert. */
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val kind: WatchKind = WatchKind.COMMUTE,
    @ProtoNumber(3) val label: String = "",
    /** Station (parent stop) ids; the server expands them to platforms. */
    @ProtoNumber(4) val originStationId: StopId,
    @ProtoNumber(5) val destinationStationId: StopId,
    /** Optional route filter; empty = any route. */
    @ProtoNumber(6) val routeIds: List<RouteId> = emptyList(),
    /** ISO days 1 = Monday .. 7 = Sunday. COMMUTE only. */
    @ProtoNumber(7) val daysOfWeek: List<Int> = listOf(1, 2, 3, 4, 5),
    /** Departure window from the origin, seconds after local midnight. COMMUTE only. */
    @ProtoNumber(8) val windowStartSecs: Int = 0,
    @ProtoNumber(9) val windowEndSecs: Int = 0,
    /** Start watching this many minutes before the window. */
    @ProtoNumber(10) val leadMinutes: Int = 45,
    @ProtoNumber(11) val rules: WatchRules = WatchRules(),
    @ProtoNumber(12) val pausedUntilMs: Long? = null,
    /** SINGLE_SERVICE only. */
    @ProtoNumber(13) val tripId: TripId? = null,
    @ProtoNumber(14) val serviceDate: ServiceDate? = null,
    @ProtoNumber(15) val createdMs: Long = 0,
    @ProtoNumber(16) val updatedMs: Long = 0,
)

@Serializable
data class WatchesResponse(@ProtoNumber(1) val watches: List<WatchDto>)

@Serializable
data class TestPushResponse(
    @ProtoNumber(1) val sent: Boolean,
    @ProtoNumber(2) val detail: String? = null,
)
