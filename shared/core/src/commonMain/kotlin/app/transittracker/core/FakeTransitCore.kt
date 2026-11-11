@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package app.transittracker.core

import app.transittracker.model.AlarmSpec
import app.transittracker.model.Alert
import app.transittracker.model.DataStatus
import app.transittracker.model.DepartureStatus
import app.transittracker.model.JoinResult
import app.transittracker.model.Journey
import app.transittracker.model.LatLng
import app.transittracker.model.PackStatus
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
import app.transittracker.model.TripLeg
import app.transittracker.model.VehiclePosition
import app.transittracker.model.WallClock
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchKind
import app.transittracker.model.WatchRules
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A [TransitCore] over canned Sydney data ([FakeData]) for front-end previews and UI tests.
 *
 * Departures are generated for every day relative to [clock], with a deterministic mix of on-time,
 * late, early, cancelled, platform-changed and added services. Saved trips and watches live in
 * memory. Nothing touches the network.
 *
 * @param refreshMs how often flows re-emit (as time passes).
 */
class FakeTransitCore(
    private val clock: WallClock = WallClock { Clock.System.now().toEpochMilliseconds() },
    joined: Boolean = true,
    private val refreshMs: Long = 15_000,
) : TransitCore {
    private val timetableData = FakeData.timetable()
    private val joinedState = MutableStateFlow(joined)
    private val tripsState = MutableStateFlow(SAMPLE_TRIPS)
    private val watchesState = MutableStateFlow(
        listOf(
            WatchDto(
                id = "fake-watch-commute",
                kind = WatchKind.COMMUTE,
                label = "Morning commute",
                originStationId = FakeData.PARRAMATTA,
                destinationStationId = FakeData.CENTRAL,
                windowStartSecs = 7 * 3600,
                windowEndSecs = 9 * 3600,
            ),
        ),
    )
    private val foreground = MutableStateFlow(false)

    /**
     * Journey planning over canned data (see [FakeJourneyPlanner]); its train rides are services
     * of this fake, so they open in [service] and [track].
     */
    val planner: FakeJourneyPlanner = FakeJourneyPlanner(clock)

    /** Server feature flags reported in [dataStatus] (for previews of hidden features). */
    val features = MutableStateFlow(mapOf("planner" to true, "push" to true, "realtime" to true, "vehicles" to true, "alerts" to true))

    /** Below this app version [dataStatus] asks for an update (for previews of "Update required"). */
    val minAppVersion = MutableStateFlow("0")

    /** Push tokens received (for UI tests). */
    val pushTokens = MutableStateFlow<List<String>>(emptyList())

    /** Whether the app is in the foreground (for UI tests). */
    val isForeground = foreground.asStateFlow()

    override suspend fun start() = Unit

    override fun onForeground() {
        foreground.value = true
    }

    override fun onBackground() {
        foreground.value = false
    }

    override val joined: Flow<Boolean> = joinedState.asStateFlow()

    /** Codes shorter than 4 characters or containing "bad" are rejected. */
    override suspend fun join(inviteCode: String): JoinResult {
        val code = inviteCode.trim()
        if (code.length < 4 || code.contains("bad", ignoreCase = true)) return JoinResult.InvalidCode
        joinedState.value = true
        return JoinResult.Joined("fake-device")
    }

    override suspend fun updatePushToken(token: String) {
        pushTokens.update { it + token }
    }

    override val trips: Flow<List<SavedTrip>> = tripsState.asStateFlow()

    override suspend fun saveTrip(trip: SavedTrip) {
        tripsState.update { list ->
            val i = list.indexOfFirst { it.id == trip.id }
            (if (i >= 0) list.toMutableList().also { it[i] = trip } else list + trip).mapIndexed { n, t -> t.copy(order = n) }
        }
    }

    override suspend fun deleteTrip(id: String) {
        tripsState.update { list -> list.filter { it.id != id }.mapIndexed { n, t -> t.copy(order = n) } }
    }

    override suspend fun reorderTrips(ids: List<String>) {
        tripsState.update { list ->
            val byId = list.associateBy { it.id }
            (ids.mapNotNull { byId[it] } + list.filter { it.id !in ids }).mapIndexed { n, t -> t.copy(order = n) }
        }
    }

    override fun upcoming(tripId: String, count: Int): Flow<List<Journey>> = journeys(tripId) { planner, trip, now ->
        planner.upcoming(trip, now, count)
    }

    override fun timetable(tripId: String, serviceDate: ServiceDate): Flow<List<Journey>> = journeys(tripId) { planner, trip, _ ->
        planner.forServiceDay(trip, serviceDate)
    }

    private fun journeys(tripId: String, compute: (JourneyPlanner, SavedTrip, Long) -> List<Journey>): Flow<List<Journey>> =
        combine(tripsState, ticker()) { list, _ -> list.firstOrNull { it.id == tripId } }
            .map { trip ->
                if (trip == null) return@map emptyList()
                val now = clock.nowMillis()
                compute(departurePlanner(now), trip, now)
            }
            .distinctUntilChanged()

    override fun service(ref: ServiceRef): Flow<ServiceDetail> = ticker().map { detail(ref, clock.nowMillis()) }.distinctUntilChanged()

    override fun track(ref: ServiceRef): Flow<TrackingState> = ticker().map {
        val now = clock.nowMillis()
        val detail = detail(ref, now)
        val next = detail.calls.firstOrNull { !it.passed && !it.skipped }
        val vehicle = if (detail.status == DepartureStatus.CANCELLED) null else vehicleFor(ref, detail, now)
        TrackingState(detail, vehicle, next, now)
    }

    override suspend fun searchStops(query: String, near: LatLng?): List<Stop> {
        val found = timetableData.searchStops(query, 30)
        return if (near == null) found else found.sortedBy { distanceMeters(near, it.position) }
    }

    override suspend fun stopsNear(position: LatLng, radiusMeters: Double): List<Stop> =
        timetableData.stopsNear(position, radiusMeters, 30)

    override suspend fun linesAt(stopId: StopId): List<Route> = timetableData.routesAtStop(stopId)

    override suspend fun shape(shapeId: ShapeId): List<LatLng> = timetableData.shape(shapeId).orEmpty()

    override fun vehicles(routeIds: Set<RouteId>): Flow<List<VehiclePosition>> = ticker().map {
        val now = clock.nowMillis()
        val snap = FakeData.liveSnapshot(timetableData, now)
        snap.trips.keys.mapNotNull { key ->
            val trip = timetableData.trip(key.tripId) ?: return@mapNotNull null
            if (trip.routeId !in routeIds) return@mapNotNull null
            val ref = ServiceRef(key.tripId, key.serviceDate)
            val detail = Overlay(timetableData, snap, now).serviceDetail(ref) ?: return@mapNotNull null
            val first = detail.calls.first().expectedDepartureMs ?: return@mapNotNull null
            val last = detail.calls.last().expectedArrivalMs ?: return@mapNotNull null
            if (now !in first..last || detail.status == DepartureStatus.CANCELLED) return@mapNotNull null
            vehicleFor(ref, detail, now)
        }
    }

    override val alerts: Flow<List<Alert>> = flow { emit(FakeData.alerts(clock.nowMillis())) }

    override fun alertsFor(tripId: String): Flow<List<Alert>> =
        combine(tripsState, alerts) { list, all ->
            list.firstOrNull { it.id == tripId }?.let { alertsForTrip(it, all, timetableData) }.orEmpty()
        }.distinctUntilChanged()

    override val dataStatus: Flow<DataStatus> = combine(joinedState, features, minAppVersion, ticker()) { joined, features, minVersion, _ ->
        DataStatus(
            packs = listOf(PackStatus("syd-rail", timetableData.version, null, updateAvailable = false)),
            lastLiveUpdateMs = clock.nowMillis(),
            liveStale = false,
            serverReachable = true,
            joined = joined,
            attribution = DefaultTransitCore.DEFAULT_ATTRIBUTION,
            statusMessage = "Sample data",
            features = features,
            minAppVersion = minVersion,
            maxWatches = DefaultTransitCore.DEFAULT_MAX_WATCHES,
        )
    }

    override val watches: Flow<List<WatchDto>> = watchesState.asStateFlow()

    override suspend fun saveWatch(watch: WatchDto) {
        val now = clock.nowMillis()
        val stamped = watch.copy(createdMs = if (watch.createdMs == 0L) now else watch.createdMs, updatedMs = now)
        watchesState.update { list ->
            if (list.any { it.id == watch.id }) list.map { if (it.id == watch.id) stamped else it } else list + stamped
        }
    }

    override suspend fun watchService(ref: ServiceRef, rules: WatchRules): WatchDto {
        val detail = detail(ref, clock.nowMillis())
        require(detail.calls.isNotEmpty()) { "Unknown service ${ref.tripId}" }
        val origin = timetableData.stop(detail.calls.first().stopId)?.parentId ?: detail.calls.first().stopId
        val dest = timetableData.stop(detail.calls.last().stopId)?.parentId ?: detail.calls.last().stopId
        val watch = WatchDto(
            id = Uuid.random().toString(),
            kind = WatchKind.SINGLE_SERVICE,
            label = "${detail.route?.shortName ?: ""} to ${detail.headsign}".trim(),
            originStationId = origin,
            destinationStationId = dest,
            routeIds = listOfNotNull(detail.route?.id),
            rules = rules,
            tripId = ref.tripId,
            serviceDate = ref.serviceDate,
        )
        saveWatch(watch)
        return watchesState.value.first { it.id == watch.id }
    }

    override suspend fun deleteWatch(id: String) {
        watchesState.update { list -> list.filter { it.id != id } }
    }

    override suspend fun sendTestNotification(id: String): Boolean = watchesState.value.any { it.id == id }

    override fun openFromNotification(data: Map<String, String>): ServiceRef? = parseNotification(data)

    /** True when joined, as the real core answers when the server can be reached. */
    override suspend fun checkForUpdates(): Boolean = joinedState.value

    /** True when joined; the watches are already in memory. */
    override suspend fun refreshWatches(): Boolean = joinedState.value

    override suspend fun nextAlarmTimes(alarm: AlarmSpec, count: Int): List<Long> {
        val trip = tripsState.value.firstOrNull { it.id == alarm.savedTripId } ?: return emptyList()
        return alarmTimes(timetableData, trip, alarm, clock.nowMillis(), count)
    }

    private fun departurePlanner(now: Long) = JourneyPlanner(timetableData, Overlay(timetableData, FakeData.liveSnapshot(timetableData, now), now))

    private fun detail(ref: ServiceRef, now: Long): ServiceDetail =
        Overlay(timetableData, FakeData.liveSnapshot(timetableData, now), now).serviceDetail(ref)
            ?: ServiceDetail(ref, null, "", DepartureStatus.TIMETABLED, emptyList())

    private fun vehicleFor(ref: ServiceRef, detail: ServiceDetail, now: Long): VehiclePosition? {
        // Place the vehicle at the last stop it has reached.
        val at = detail.calls.lastOrNull { it.passed } ?: return null
        val stop = timetableData.stop(at.stopId) ?: return null
        return VehiclePosition(
            vehicleId = "fake-${ref.tripId}",
            tripId = ref.tripId,
            startDate = ref.serviceDate,
            routeId = detail.route?.id,
            position = stop.position,
            timestampMs = now,
            occupancy = detail.occupancy,
            carCount = detail.carCount,
            label = detail.headsign,
        )
    }

    private fun ticker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(refreshMs)
        }
    }

    companion object {
        /** Saved trips the fake starts with. */
        val SAMPLE_TRIPS: List<SavedTrip> = listOf(
            SavedTrip(
                id = "trip-parramatta-central",
                originName = "Parramatta",
                destinationName = "Central",
                legs = listOf(TripLeg(setOf(FakeData.PARRAMATTA), setOf(FakeData.CENTRAL))),
                order = 0,
            ),
            SavedTrip(
                id = "trip-central-parramatta",
                originName = "Central",
                destinationName = "Parramatta",
                legs = listOf(TripLeg(setOf(FakeData.CENTRAL), setOf(FakeData.PARRAMATTA))),
                order = 1,
            ),
            SavedTrip(
                id = "trip-townhall-tallawong",
                originName = "Town Hall",
                destinationName = "Tallawong",
                legs = listOf(
                    TripLeg(setOf(FakeData.TOWN_HALL), setOf(FakeData.CHATSWOOD)),
                    TripLeg(setOf(FakeData.CHATSWOOD), setOf(FakeData.TALLAWONG), minConnectionMinutes = 4),
                ),
                customName = "Town Hall to Tallawong via Chatswood",
                order = 2,
            ),
        )

        /** Today's service date for [nowMs] (helper for previews). */
        fun today(nowMs: Long): ServiceDate = ServiceTime.localDate(nowMs)
    }
}
