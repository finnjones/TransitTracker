package app.transittracker.model

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * The contract between the on-device core (`:shared:core`) and the front end (Android now,
 * iOS later). The front end only talks to this interface; it never calls the HTTP API or reads
 * timetable packs directly, and never does transit logic itself.
 *
 * All times are epoch milliseconds. Flows are cold-safe and re-emit when live data, timetable
 * packs or saved data change.
 */
interface TransitCore {
    // ---- Lifecycle ----
    /** Opens local data, schedules pack updates. Safe to call more than once. */
    suspend fun start()
    /** App visible: live polling runs (obeying server intervals). */
    fun onForeground()
    /** App hidden: live polling pauses. */
    fun onBackground()

    // ---- Setup ----
    val joined: Flow<Boolean>
    suspend fun join(inviteCode: String): JoinResult
    /** Called by the front end whenever Firebase issues a new token. */
    suspend fun updatePushToken(token: String)

    // ---- Saved trips (stored on the phone) ----
    val trips: Flow<List<SavedTrip>>
    suspend fun saveTrip(trip: SavedTrip)
    suspend fun deleteTrip(id: String)
    suspend fun reorderTrips(ids: List<String>)

    // ---- Departures: timetable with the live layer applied ----
    fun upcoming(tripId: String, count: Int = 6): Flow<List<Journey>>
    fun timetable(tripId: String, serviceDate: ServiceDate): Flow<List<Journey>>
    fun service(ref: ServiceRef): Flow<ServiceDetail>
    /** Fast (tracker interval) updates for one service while a tracking screen is open. */
    fun track(ref: ServiceRef): Flow<TrackingState>

    // ---- Reference data ----
    suspend fun searchStops(query: String, near: LatLng? = null): List<Stop>
    suspend fun stopsNear(position: LatLng, radiusMeters: Double = 1000.0): List<Stop>
    suspend fun linesAt(stopId: StopId): List<Route>
    suspend fun shape(shapeId: ShapeId): List<LatLng>
    fun vehicles(routeIds: Set<RouteId>): Flow<List<VehiclePosition>>

    // ---- Alerts and status ----
    val alerts: Flow<List<Alert>>
    val dataStatus: Flow<DataStatus>

    // ---- Push watches (stored on the server) ----
    val watches: Flow<List<WatchDto>>
    suspend fun saveWatch(watch: WatchDto)
    suspend fun watchService(ref: ServiceRef, rules: WatchRules = WatchRules()): WatchDto
    suspend fun deleteWatch(id: String)
    suspend fun sendTestNotification(id: String): Boolean
    /** Maps an FCM data payload (see [PushKeys]) to the service to open, if any. */
    fun openFromNotification(data: Map<String, String>): ServiceRef?

    // ---- Journey planning (served by tt-server) ----
    /** Addresses, stations and stops matching [query], best first. Empty when offline. */
    suspend fun searchPlaces(query: String, near: LatLng? = null): List<Place>
    suspend fun plan(request: PlanRequest): PlanResult
    /** Re-applies live data to each ride of [journey] and recomputes the spare time at each change. */
    fun follow(journey: PlannedJourney): Flow<JourneyState>
    /** Turns a planned journey's rides into a [SavedTrip]; walks become each leg's minimum connection time. */
    fun savedTripFrom(journey: PlannedJourney, originName: String, destinationName: String): SavedTrip

    // ---- Device alarms (scheduled by the Android layer) ----
    suspend fun nextAlarmTimes(alarm: AlarmSpec, count: Int = 5): List<Long>
}

@Serializable
data class ServiceRef(val tripId: TripId, val serviceDate: ServiceDate)

@Serializable
data class TripLeg(
    /** Usually all platform stops of the origin station. */
    val originStopIds: Set<StopId>,
    val destinationStopIds: Set<StopId>,
    val routeIds: Set<RouteId>? = null,
    /** Minimum time to change onto this leg from the previous one. Ignored for the first leg. */
    val minConnectionMinutes: Int = 3,
)

@Serializable
data class SavedTrip(
    val id: String,
    val originName: String,
    val destinationName: String,
    val legs: List<TripLeg>,
    val customName: String? = null,
    val folder: String? = null,
    val order: Int = 0,
)

enum class DepartureStatus {
    /** Live data says on time (within 59 s). */
    ON_TIME,
    LATE,
    EARLY,
    CANCELLED,
    /** Extra service not in the timetable. */
    ADDED,
    /** No live data for this service: showing the timetable. */
    TIMETABLED,
}

data class Departure(
    val ref: ServiceRef,
    val routeId: RouteId,
    val routeName: String,
    val routeColor: String?,
    val mode: Mode,
    val headsign: String,
    val originStopId: StopId,
    val originPlatform: String?,
    val platformChanged: Boolean,
    val scheduledDepartureMs: Long,
    val expectedDepartureMs: Long,
    val destinationStopId: StopId,
    val scheduledArrivalMs: Long,
    val expectedArrivalMs: Long,
    /** Departure delay in seconds; null when there is no live data. */
    val delaySeconds: Int?,
    val status: DepartureStatus,
    val occupancy: Occupancy = Occupancy.UNKNOWN,
    val carCount: Int? = null,
    /** Age of the live data used, or null when timetabled. */
    val dataAgeMs: Long? = null,
    val stale: Boolean = false,
)

/** One way of doing a saved trip; single-leg trips have one leg. */
data class Journey(val legs: List<Departure>) {
    val departure: Departure get() = legs.first()
    val arrival: Departure get() = legs.last()
}

data class StopCall(
    val stopSequence: Int,
    val stopId: StopId,
    val stopName: String,
    val platform: String?,
    val platformChanged: Boolean,
    val scheduledArrivalMs: Long?,
    val scheduledDepartureMs: Long?,
    val expectedArrivalMs: Long?,
    val expectedDepartureMs: Long?,
    val skipped: Boolean,
    /** True once the expected departure is in the past. */
    val passed: Boolean,
)

data class ServiceDetail(
    val ref: ServiceRef,
    val route: Route?,
    val headsign: String,
    val status: DepartureStatus,
    val calls: List<StopCall>,
    val occupancy: Occupancy = Occupancy.UNKNOWN,
    val carCount: Int? = null,
    val dataAgeMs: Long? = null,
    val stale: Boolean = false,
)

data class TrackingState(
    val detail: ServiceDetail,
    val vehicle: VehiclePosition?,
    val nextCall: StopCall?,
    val updatedMs: Long,
)

data class PackStatus(
    val name: String,
    val version: String?,
    val validTo: ServiceDate?,
    val updateAvailable: Boolean,
    val downloading: Boolean = false,
)

data class DataStatus(
    val packs: List<PackStatus>,
    val lastLiveUpdateMs: Long?,
    val liveStale: Boolean,
    val serverReachable: Boolean,
    val joined: Boolean,
    val attribution: String,
    val statusMessage: String? = null,
)

@Serializable
enum class AlarmKind { BEFORE_DEPARTURE, BEFORE_ARRIVAL }

@Serializable
data class AlarmSpec(
    val id: String,
    val savedTripId: String,
    val kind: AlarmKind,
    val minutesBefore: Int,
    /** Only alarm for these ISO days (1 = Monday). Empty = every day. */
    val daysOfWeek: List<Int> = emptyList(),
)

sealed interface JoinResult {
    data class Joined(val deviceId: String) : JoinResult
    data object InvalidCode : JoinResult
    data class Failed(val message: String) : JoinResult
}

sealed interface PlanResult {
    data class Journeys(val journeys: List<PlannedJourney>, val source: String) : PlanResult
    /** The planner can't be used right now (no connection, server or TfNSW down, quota). */
    data class Unavailable(val message: String) : PlanResult
    /** The request was rejected (e.g. unknown place, start equals end). */
    data class Failed(val code: String, val message: String) : PlanResult
}

/** A ride of a followed journey with live data applied. */
data class FollowedLeg(
    val leg: PlannedLeg,
    val expectedDepartureMs: Long,
    val expectedArrivalMs: Long,
    /** TIMETABLED for walks and for rides that aren't matched or have no live data. */
    val status: DepartureStatus,
    val platform: String?,
    val platformChanged: Boolean,
    val stale: Boolean,
)

/** Spare time at a change between ride [fromLegIndex] and the next ride. */
data class ConnectionMargin(
    val fromLegIndex: Int,
    val toLegIndex: Int,
    /** Expected arrival + walk to expected departure of the next ride; negative = missed. */
    val spareSeconds: Int,
    val atRisk: Boolean,
)

data class JourneyState(
    val journey: PlannedJourney,
    val legs: List<FollowedLeg>,
    val connections: List<ConnectionMargin>,
    val expectedArrivalMs: Long,
    val updatedMs: Long,
)
