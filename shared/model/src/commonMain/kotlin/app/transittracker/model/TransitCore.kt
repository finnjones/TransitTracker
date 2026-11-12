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
    /** Joins with a one-time invite code (servers and builds without sign-in). */
    suspend fun join(inviteCode: String): JoinResult
    /**
     * Joins with a Firebase Authentication ID token from the app's sign-in. The server checks it
     * and that the account is allowed; [JoinResult.NotAllowed] says when it is not.
     */
    suspend fun signIn(idToken: String): JoinResult = JoinResult.Failed("Sign-in is not supported")
    /** Forgets this phone's join (device token and id): [joined] turns false and onboarding shows again. */
    suspend fun signOut() {}
    /** Called by the front end whenever Firebase issues a new token. */
    suspend fun updatePushToken(token: String)

    // ---- Saved trips (stored on the phone) ----
    val trips: Flow<List<SavedTrip>>
    suspend fun saveTrip(trip: SavedTrip)
    suspend fun deleteTrip(id: String)
    suspend fun reorderTrips(ids: List<String>)
    /** Reads saved trips from storage again (after they were restored from the user's account). */
    suspend fun reloadTrips() {}

    // ---- Departures: timetable with the live layer applied ----
    fun upcoming(tripId: String, count: Int = 6): Flow<List<Journey>>
    fun timetable(tripId: String, serviceDate: ServiceDate): Flow<List<Journey>>
    /**
     * Journeys of [tripId] already under way: the first leg has left, the last has not yet
     * arrived. Chained with live data like [upcoming] (a late train moves the connection on).
     * Earliest first. The front end decides, from the phone's location, which one the user is on.
     */
    fun underway(tripId: String): Flow<List<Journey>> = kotlinx.coroutines.flow.flowOf(emptyList())
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
    /** Alerts whose routes or stops (including parent stations) touch the saved trip [tripId]. */
    fun alertsFor(tripId: String): Flow<List<Alert>>
    val dataStatus: Flow<DataStatus>
    /** Pull to refresh: fetches config and installs newer timetable packs now. False when offline or not joined. */
    suspend fun checkForUpdates(): Boolean

    // ---- Push watches (stored on the server) ----
    val watches: Flow<List<WatchDto>>
    suspend fun saveWatch(watch: WatchDto)
    suspend fun watchService(ref: ServiceRef, rules: WatchRules = WatchRules()): WatchDto
    suspend fun deleteWatch(id: String)
    /**
     * Asks the server to push a test notification through watch [id]. When it does not go out,
     * [TestPushResponse.detail] says why (e.g. the phone has no push token on the server).
     * Throws when the server cannot be reached.
     */
    suspend fun sendTestNotification(id: String): TestPushResponse
    /** Pull to refresh: reloads watches from the server. False when that failed. */
    suspend fun refreshWatches(): Boolean
    /** Maps an FCM data payload (see [PushKeys]) to the service to open, if any. */
    fun openFromNotification(data: Map<String, String>): ServiceRef?

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

/**
 * One way of making a saved trip: its rides, in order ("the T9 from Gordon", "the 333 bus from
 * Ocean St"). [id] is stable for the same pattern of stops and lines ([wayIdFor]).
 */
@Serializable
data class RouteOption(
    val id: String,
    val legs: List<TripLeg>,
    /** What the user called this way; null shows its lines. */
    val label: String? = null,
)

/**
 * A saved trip from A to B. It has one or more ways of getting there ([ways]); departures of every
 * way are offered and the front end shows the best one for when the user leaves.
 *
 * [legs] is always the first way, so data saved before ways existed (and older app versions
 * reading newer data) see a one-way trip. Change ways with [withWays], which keeps the two in step.
 */
@Serializable
data class SavedTrip(
    val id: String,
    val originName: String,
    val destinationName: String,
    val legs: List<TripLeg>,
    val customName: String? = null,
    val folder: String? = null,
    val order: Int = 0,
    /** Every way when there is more than one (or the only way has a label); empty means [legs] is the only way. */
    val options: List<RouteOption> = emptyList(),
    /** The way the user chose to always see; null lets the app pick the best one each time. */
    val pinnedWayId: String? = null,
) {
    /** Every way of making the trip, the first being [legs]. Never empty for a trip with legs. */
    val ways: List<RouteOption>
        get() = options.ifEmpty { if (legs.isEmpty()) emptyList() else listOf(RouteOption(wayIdFor(legs), legs)) }

    /** The way with [id], or null. */
    fun way(id: String?): RouteOption? = id?.let { w -> ways.firstOrNull { it.id == w } }

    /** The pinned way, when it is still one of [ways]. */
    val pinnedWay: RouteOption? get() = way(pinnedWayId)

    /**
     * This trip with [ways] (duplicates by id dropped, first kept): [legs] becomes the first way's,
     * a pin on a way that is gone is cleared.
     */
    fun withWays(ways: List<RouteOption>): SavedTrip {
        val distinct = ways.distinctBy { it.id }
        require(distinct.isNotEmpty()) { "a saved trip needs at least one way" }
        val single = distinct.size == 1 && distinct[0].label == null && distinct[0].id == wayIdFor(distinct[0].legs)
        return copy(
            legs = distinct[0].legs,
            options = if (single) emptyList() else distinct,
            pinnedWayId = pinnedWayId?.takeIf { p -> distinct.any { it.id == p } },
        )
    }
}

/**
 * The pattern of [legs] as text, the same for the same stops and lines in any order:
 * "213510.200060.T9_200060.2000331.T4".
 */
fun routePattern(legs: List<TripLeg>): String = legs.joinToString("_") { l ->
    listOf(l.originStopIds.sorted().joinToString("+"), l.destinationStopIds.sorted().joinToString("+"), l.routeIds.orEmpty().sorted().joinToString("+"))
        .joinToString(".")
}

/** The [RouteOption.id] of a way with these legs: "way-" and its [routePattern]. */
fun wayIdFor(legs: List<TripLeg>): String = "way-" + routePattern(legs)

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

/**
 * One run of a saved trip: the services taken, one per leg (single-leg trips have one).
 * [wayId] says which of the trip's [SavedTrip.ways] it follows; null when not known. The same run
 * can fit more than one way (a way on any line and one on the T9 only): the others are [alsoWays].
 */
data class Journey(val legs: List<Departure>, val wayId: String? = null, val alsoWays: Set<String> = emptySet()) {
    /** True when this run follows way [id] (or its way is not known). */
    fun follows(id: String): Boolean = wayId == null || wayId == id || id in alsoWays

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
    val position: LatLng? = null,
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
    /** The service's line on the map, when the timetable has one. */
    val shapeId: ShapeId? = null,
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
    /** Server feature flags from `/v1/config` (e.g. "planner"). */
    val features: Map<String, Boolean> = emptyMap(),
    /** Below this app version the front end shows "Update required". */
    val minAppVersion: String = "0",
    /** Most push watches this device may have. */
    val maxWatches: Int = 10,
    /**
     * Why the last timetable check did not bring a timetable, as a sentence for the user (the
     * server has none yet, a download failed, the server refused); null when it did, or before
     * the first check.
     */
    val timetableProblem: String? = null,
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
    /** Signed in, but the server does not let this account in ([message] says so, from the server). */
    data class NotAllowed(val message: String) : JoinResult
    data class Failed(val message: String) : JoinResult
}

/**
 * The items of [items] that no other beats, in their original order. One run beats another when
 * it lets you leave no earlier ([leave]) and still arrives no later ([arrive]), and is better in
 * at least one. Two runs with the same times both stay.
 */
fun <T> bestRuns(items: List<T>, leave: (T) -> Long, arrive: (T) -> Long): List<T> {
    if (items.size < 2) return items
    // Latest leave first. [laterArrive] is the earliest arrival of the runs leaving later than the current group.
    val order = items.indices.sortedByDescending { leave(items[it]) }
    val keep = BooleanArray(items.size)
    var laterArrive = Long.MAX_VALUE
    var i = 0
    while (i < order.size) {
        val l = leave(items[order[i]])
        var j = i
        while (j < order.size && leave(items[order[j]]) == l) j++
        val group = order.subList(i, j)
        val groupBest = group.minOf { arrive(items[it]) }
        for (k in group) {
            val a = arrive(items[k])
            keep[k] = a < laterArrive && a == groupBest
        }
        laterArrive = minOf(laterArrive, groupBest)
        i = j
    }
    return items.filterIndexed { idx, _ -> keep[idx] }
}
