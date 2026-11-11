package app.transittracker.core

import app.transittracker.model.Alert
import app.transittracker.model.Mode
import app.transittracker.model.RealtimeResponse
import app.transittracker.model.RouteId
import app.transittracker.model.VehiclePosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Live data polling.
 *
 * Flows register interest through [Lease]s (reference counted):
 * - routes ([leaseRoutes]) → `GET /v1/rt?routes=…` every `pollIntervalS`;
 * - single trips ([leaseTrip], tracking screens) → `GET /v1/rt/trip/{id}` every `trackerPollIntervalS`;
 * - vehicle routes ([leaseVehicles]) → `GET /v1/vehicles?routes=…` every `pollIntervalS`.
 *
 * Nothing is polled while in the background or when nothing is observed. Errors back off
 * exponentially (2×, 4×, … the interval, capped at [MAX_BACKOFF_MS]). Alerts are refetched only
 * when the real-time response's `alertsEtag` changes.
 */
class LivePoller(
    private val api: ApiClient,
    private val clock: TimeSync,
    private val scope: CoroutineScope,
    private val alertModes: suspend () -> Set<Mode> = { emptySet() },
) {
    private val foreground = MutableStateFlow(false)
    private val routeRefs = MutableStateFlow<Map<RouteId, Int>>(emptyMap())
    private val tripRefs = MutableStateFlow<Map<TripKey, Int>>(emptyMap())
    private val vehicleRefs = MutableStateFlow<Map<RouteId, Int>>(emptyMap())

    private val _snapshot = MutableStateFlow(LiveSnapshot.EMPTY)
    private val _vehicles = MutableStateFlow<Map<RouteId, List<VehiclePosition>>>(emptyMap())
    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    private val pollIntervalS = MutableStateFlow(DEFAULT_POLL_S)
    private val trackerIntervalS = MutableStateFlow(DEFAULT_TRACKER_POLL_S)

    private val alertsMutex = Mutex()
    private var alertsEtag: String? = null
    private var alertsLoaded = false
    private var jobs: List<Job> = emptyList()

    // Last successful poll per loop, so a narrower re-subscription does not refetch at once.
    private val lastRoutePoll = MutableStateFlow<Pair<Set<RouteId>, Long>?>(null)
    private val lastVehiclePoll = MutableStateFlow<Pair<Set<RouteId>, Long>?>(null)
    private val lastTripPoll = MutableStateFlow<Pair<Set<TripKey>, Long>?>(null)

    /** Live trip updates; re-emits on every successful poll. */
    val snapshot: StateFlow<LiveSnapshot> = _snapshot.asStateFlow()

    /** Latest vehicle positions per route. */
    val vehicles: StateFlow<Map<RouteId, List<VehiclePosition>>> = _vehicles.asStateFlow()

    /** Current alerts. */
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()

    /** Routes currently observed (for diagnostics and tests). */
    val observedRoutes: Flow<Set<RouteId>> = routeRefs.map { it.keys }

    val isForeground: StateFlow<Boolean> = foreground.asStateFlow()

    val pollIntervalMs: Long get() = pollIntervalS.value * 1000L
    val trackerPollIntervalMs: Long get() = trackerIntervalS.value * 1000L

    /** Starts the polling loops on the scope (idempotent). */
    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            launchLoop(routeRefs, lastRoutePoll, { pollIntervalMs }, ::pollRoutes),
            launchLoop(tripRefs, lastTripPoll, { trackerPollIntervalMs }, ::pollTrips),
            launchLoop(vehicleRefs, lastVehiclePoll, { pollIntervalMs }, ::pollVehicles),
        )
    }

    /** App visible: resume polling immediately. */
    fun onForeground() {
        if (foreground.value) return
        lastRoutePoll.value = null
        lastTripPoll.value = null
        lastVehiclePoll.value = null
        foreground.value = true
    }

    /** App hidden: pause polling. */
    fun onBackground() {
        foreground.value = false
    }

    /** Applies intervals from `/v1/config`. */
    fun applyIntervals(pollS: Int, trackerS: Int) {
        if (pollS > 0) pollIntervalS.value = pollS
        if (trackerS > 0) trackerIntervalS.value = trackerS
        _snapshot.update { it.copy(pollIntervalMs = pollIntervalMs) }
    }

    /** A reference-counted registration of interest; [set] may change what it covers. */
    inner class Lease<K> internal constructor(private val refs: MutableStateFlow<Map<K, Int>>) {
        private var current: Set<K> = emptySet()
        private var closed = false

        fun set(keys: Set<K>) {
            if (closed || keys == current) return
            val added = keys - current
            val removed = current - keys
            refs.update { m ->
                val out = m.toMutableMap()
                added.forEach { out[it] = (out[it] ?: 0) + 1 }
                removed.forEach { k -> val n = (out[k] ?: 0) - 1; if (n <= 0) out.remove(k) else out[k] = n }
                out
            }
            current = keys
        }

        fun close() {
            set(emptySet())
            closed = true
        }
    }

    fun leaseRoutes(): Lease<RouteId> = Lease(routeRefs)
    fun leaseTrip(): Lease<TripKey> = Lease(tripRefs)
    fun leaseVehicles(): Lease<RouteId> = Lease(vehicleRefs)

    /** Fetches alerts once if they have never been loaded. */
    suspend fun ensureAlerts() {
        if (!alertsLoaded) refreshAlerts(force = true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <K> launchLoop(
        refs: StateFlow<Map<K, Int>>,
        lastPoll: MutableStateFlow<Pair<Set<K>, Long>?>,
        intervalMs: () -> Long,
        poll: suspend (Set<K>) -> Boolean,
    ): Job = scope.launch {
        combine(foreground, refs) { fg, r -> if (fg) r.keys else emptySet() }
            .distinctUntilChanged()
            .collectLatest { keys ->
                if (keys.isEmpty()) return@collectLatest
                val last = lastPoll.value
                if (last != null && last.first.containsAll(keys)) {
                    val wait = last.second + intervalMs() - clock.nowMillis()
                    if (wait > 0) delay(wait)
                }
                var failures = 0
                while (true) {
                    val ok = poll(keys)
                    if (ok) {
                        failures = 0
                        lastPoll.value = keys to clock.nowMillis()
                        delay(intervalMs())
                    } else {
                        failures++
                        delay(backoffMs(intervalMs(), failures))
                    }
                }
            }
    }

    private suspend fun pollRoutes(routes: Set<RouteId>): Boolean {
        val result = api.realtime(routes)
        if (result !is ApiResult.Success) return false
        val response = result.value
        applyRealtime(response, routes, result.serverTimeMs)
        val etag = response.alertsEtag
        if (etag != null && etag != alertsEtag) refreshAlerts(force = false, etag = etag)
        return true
    }

    private suspend fun pollTrips(keys: Set<TripKey>): Boolean {
        var ok = true
        for (key in keys) {
            val result = api.realtimeTrip(key.tripId, key.serviceDate)
            if (result is ApiResult.Success) {
                applyRealtime(result.value, replaceRoutes = emptySet(), result.serverTimeMs)
            } else {
                ok = false
            }
        }
        return ok
    }

    private suspend fun pollVehicles(routes: Set<RouteId>): Boolean {
        val result = api.vehicles(routes)
        if (result !is ApiResult.Success) return false
        val byRoute = result.value.vehicles.groupBy { it.routeId.orEmpty() }
        _vehicles.update { old -> old.filterKeys { it !in routes } + routes.associateWith { byRoute[it].orEmpty() } }
        if (result.value.pollIntervalS > 0) pollIntervalS.value = result.value.pollIntervalS
        return true
    }

    /** Merges a real-time response; deltas on [replaceRoutes] not in the response are dropped. */
    internal fun applyRealtime(response: RealtimeResponse, replaceRoutes: Set<RouteId>, headerTimeMs: Long?) {
        if (response.pollIntervalS > 0) pollIntervalS.value = response.pollIntervalS
        if (response.trackerPollIntervalS > 0) trackerIntervalS.value = response.trackerPollIntervalS
        val received = headerTimeMs ?: response.serverTimeMs.takeIf { it > 0 } ?: clock.nowMillis()
        _snapshot.update { old ->
            val trips = old.trips.filterValues { it.delta.routeId !in replaceRoutes }.toMutableMap()
            for (delta in response.trips) trips[TripKey(delta.tripId, delta.startDate)] = LiveTrip(delta, received)
            LiveSnapshot(
                trips = trips,
                pollIntervalMs = pollIntervalMs,
                lastUpdateMs = received,
                feedsStale = response.feeds.any { it.stale || !it.ok },
            )
        }
    }

    private suspend fun refreshAlerts(force: Boolean, etag: String? = null) {
        alertsMutex.withLock {
            if (!force && etag == alertsEtag) return
            val result = api.alerts(alertModes())
            if (result is ApiResult.Success) {
                _alerts.value = result.value.alerts
                alertsLoaded = true
                if (etag != null) alertsEtag = etag
            }
        }
    }

    companion object {
        const val DEFAULT_POLL_S = 30
        const val DEFAULT_TRACKER_POLL_S = 15
        const val MAX_BACKOFF_MS = 5 * 60_000L

        /** Delay after the [failures]-th consecutive failure: interval × 2^failures, capped at 5 min. */
        fun backoffMs(intervalMs: Long, failures: Int): Long {
            var d = intervalMs
            repeat(failures.coerceAtMost(20)) { d = (d * 2).coerceAtMost(MAX_BACKOFF_MS) }
            return d.coerceAtMost(MAX_BACKOFF_MS)
        }
    }
}
