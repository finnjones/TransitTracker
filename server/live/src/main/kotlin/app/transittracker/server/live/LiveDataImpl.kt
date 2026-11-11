package app.transittracker.server.live

import app.transittracker.model.Alert
import app.transittracker.model.AlertsResponse
import app.transittracker.model.FeedHealth
import app.transittracker.model.Mode
import app.transittracker.model.RealtimeResponse
import app.transittracker.model.RouteId
import app.transittracker.model.ServiceDate
import app.transittracker.model.Timetable
import app.transittracker.model.TripDelta
import app.transittracker.model.TripId
import app.transittracker.model.VehiclePosition
import app.transittracker.model.VehiclesResponse
import app.transittracker.model.WallClock
import app.transittracker.server.spi.AlertsSnapshot
import app.transittracker.server.spi.DemandKind
import app.transittracker.server.spi.FeedDefinition
import app.transittracker.server.spi.FeedStatus
import app.transittracker.server.spi.LiveData
import app.transittracker.server.spi.LiveStatus
import app.transittracker.server.spi.ServerConfig
import app.transittracker.server.spi.TripChangeBatch
import com.google.transit.realtime.FeedMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fetch-on-demand GTFS-Realtime cache for tt-server (plan section 4).
 *
 * Each (feed, kind) is fetched only when someone asks and the cached copy is older than the demand's
 * minimum age; concurrent requests share one fetch. Throttling and errors back off per feed, calls are
 * rate limited and counted against a daily budget, and decoded trip updates are matched to the current
 * timetable and normalised to [TripDelta]s.
 *
 * @param config feeds, poll intervals, budget, data and recording directories
 * @param timetable the current timetable; null until the first pack is built (responses are empty then)
 * @param fetcher downloads raw feeds ([HttpFeedFetcher] in production, [ReplayFetcher] offline)
 * @param clock wall clock used for ages, staleness and the daily budget
 * @param recorder saves sampled raw feeds; null disables recording
 * @param rateLimiter limits calls to TfNSW (remote fetchers only); used only to build the default [quota]
 * @param quota the server-wide TfNSW daily budget and per-second limiter, shared with the journey
 *   planner (remote fetchers only)
 * @param backoff backoff schedule after throttling or errors
 * @param retryDelayMs wait before the single retry after a 5xx (non-throttle) or network error
 * @param warmTickMs how often the keep-warm loop started by [start] runs
 */
class LiveDataImpl(
    private val config: ServerConfig,
    private val timetable: StateFlow<Timetable?>,
    private val fetcher: FeedFetcher,
    private val clock: WallClock,
    private val recorder: FeedRecorder? = config.recordIntervalMinutes.takeIf { it > 0 }?.let {
        FeedRecorder(config.recordingsDir, clock, minIntervalMs = it * 60_000L, retentionDays = config.recordRetentionDays)
    },
    rateLimiter: RateLimiter = RateLimiter(config.maxCallsPerSecond),
    private val quota: TfnswQuota = TfnswQuota.fromConfig(config, clock, rateLimiter),
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val retryDelayMs: Long = 5_000,
    private val warmTickMs: Long = 1_000,
) : LiveData {

    private val sources: Map<Pair<String, FeedKind>, Source> = buildMap {
        for (feed in config.feeds) for (kind in FeedKind.entries) {
            if (kind.pathOf(feed) != null) put(feed.id to kind, Source(feed, kind))
        }
    }

    private val tripData = ConcurrentHashMap<String, TripData>()
    private val vehicleData = ConcurrentHashMap<String, VehicleData>()
    private val alertData = ConcurrentHashMap<String, List<Alert>>()
    private val alertFirstSeen = ConcurrentHashMap<String, Pair<Int, Long>>()

    private val lastEmitted = ConcurrentHashMap<String, Map<TripKey, TripDelta>>()
    private val emitLocks = ConcurrentHashMap<String, Mutex>()
    private val alertEmitLock = Mutex()
    @Volatile private var lastPublishedAlerts: List<Alert>? = null

    private val warm = ConcurrentHashMap<String, WarmRegistration>()
    private val started = AtomicBoolean(false)
    private val noKeyLogged = AtomicBoolean(false)

    private val _tripChanges = MutableSharedFlow<TripChangeBatch>(extraBufferCapacity = 256)
    private val _alertChanges = MutableSharedFlow<List<Alert>>(extraBufferCapacity = 16)

    override val tripChanges: SharedFlow<TripChangeBatch> = _tripChanges.asSharedFlow()
    override val alertChanges: SharedFlow<List<Alert>> = _alertChanges.asSharedFlow()

    private val keyMissing: Boolean get() = fetcher.isRemote && config.tfnswApiKey.isNullOrBlank()

    // ---------------------------------------------------------------------------------------------
    // LiveData

    override suspend fun realtime(routeIds: Set<RouteId>, demand: DemandKind): RealtimeResponse {
        val tt = timetable.value ?: return emptyRealtime()
        val feeds = feedsFor(routeIds, tt)
        refreshFeeds(feeds, kindsFor(demand), demand)
        val now = clock.nowMillis()
        val trips = feeds.flatMap { feed ->
            val feedStale = isStale(source(feed, FeedKind.TRIP_UPDATES), minAgeMs(feed, FeedKind.TRIP_UPDATES, demand), now)
            tripData[feed.id]?.deltas?.values.orEmpty()
                .filter { it.routeId in routeIds }
                .map { present(it, feed, feedStale, now) }
        }.sortedWith(compareBy({ it.routeId }, { it.tripId }, { it.startDate }))
        return realtimeResponse(now, tt, feeds, demand, trips)
    }

    override suspend fun trip(tripId: TripId, serviceDate: ServiceDate, demand: DemandKind): RealtimeResponse {
        val tt = timetable.value ?: return emptyRealtime()
        val key = TripKey(tripId, serviceDate)
        val routeId = tt.trip(tripId)?.routeId
        val feeds = if (routeId != null) {
            feedsFor(setOf(routeId), tt)
        } else {
            // Added trips are not in the timetable: use the feed that reported them.
            config.feeds.filter { tripData[it.id]?.deltas?.containsKey(key) == true }
        }
        refreshFeeds(feeds, kindsFor(demand), demand)
        val now = clock.nowMillis()
        val trips = feeds.mapNotNull { feed ->
            val feedStale = isStale(source(feed, FeedKind.TRIP_UPDATES), minAgeMs(feed, FeedKind.TRIP_UPDATES, demand), now)
            tripData[feed.id]?.deltas?.get(key)?.let { present(it, feed, feedStale, now) }
        }
        return realtimeResponse(now, tt, feeds, demand, trips)
    }

    override suspend fun vehicles(routeIds: Set<RouteId>): VehiclesResponse {
        val tt = timetable.value ?: return VehiclesResponse(clock.nowMillis(), config.pollIntervalS)
        val feeds = feedsFor(routeIds, tt)
        refreshFeeds(feeds, listOf(FeedKind.VEHICLES), DemandKind.SCREEN)
        val now = clock.nowMillis()
        val vehicles = feeds.flatMap { vehicleData[it.id]?.vehicles.orEmpty() }
            .filter { it.routeId in routeIds && now - it.timestampMs <= VEHICLE_MAX_AGE_MS }
            .sortedBy { it.vehicleId }
        return VehiclesResponse(serverTimeMs = now, pollIntervalS = config.pollIntervalS, vehicles = vehicles)
    }

    override suspend fun alerts(modes: Set<Mode>?): AlertsSnapshot {
        val feeds = config.feeds.filter { it.alertsPath != null && (modes == null || it.mode in modes) }
        refreshFeeds(feeds, listOf(FeedKind.ALERTS), DemandKind.SCREEN)
        val alerts = currentAlerts(modes)
        return AlertsSnapshot(AlertTranslator.etag(alerts), AlertsResponse(clock.nowMillis(), alerts))
    }

    override fun keepWarm(key: String, routeIds: Set<RouteId>, demand: DemandKind, untilMillis: Long) {
        if (untilMillis <= clock.nowMillis() || routeIds.isEmpty()) {
            warm.remove(key)
        } else {
            warm[key] = WarmRegistration(routeIds, demand, untilMillis)
        }
    }

    override fun cachedTrip(tripId: TripId, serviceDate: ServiceDate): TripDelta? {
        val key = TripKey(tripId, serviceDate)
        val now = clock.nowMillis()
        for (feed in config.feeds) {
            val delta = tripData[feed.id]?.deltas?.get(key) ?: continue
            val feedStale = isStale(source(feed, FeedKind.TRIP_UPDATES), minAgeMs(feed, FeedKind.TRIP_UPDATES, DemandKind.WATCH), now)
            return present(delta, feed, feedStale, now)
        }
        return null
    }

    override fun status(): LiveStatus {
        val now = clock.nowMillis()
        return LiveStatus(
            // Shared with the journey planner: this is every TfNSW call made today with the key.
            callsToday = quota.callsToday,
            dailyBudget = quota.dailyBudget,
            feeds = sources.values.map { s ->
                val data = if (s.kind == FeedKind.TRIP_UPDATES) tripData[s.feed.id] else null
                FeedStatus(
                    feedId = s.feed.id,
                    kind = s.kind.id,
                    lastFetchMs = s.lastFetchMs,
                    lastSuccessMs = s.lastSuccessMs,
                    dataTimeMs = s.dataTimeMs,
                    lastHttpStatus = s.lastHttpStatus,
                    consecutiveErrors = s.consecutiveErrors,
                    backoffUntilMs = s.backoffUntilMs?.takeIf { it > now },
                    matchedTrips = data?.matched ?: 0,
                    unmatchedTrips = data?.unmatched ?: 0,
                    stale = isStale(s, minAgeMs(s.feed, s.kind, DemandKind.SCREEN), now),
                )
            },
        )
    }

    /**
     * Starts the keep-warm loop in [scope]: every [warmTickMs] it drops expired registrations, refreshes
     * trip updates and alerts of feeds with active registrations when due, and emits [tripChanges] for
     * trips that became stale. Calling it again has no effect.
     */
    override fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        if (keyMissing) logNoKey()
        scope.launch(CoroutineName("live-keep-warm")) {
            recorder?.let { r -> runCatching { withContext(Dispatchers.IO) { r.prune() } } }
            while (isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Keep-warm tick failed", e)
                }
                delay(warmTickMs)
            }
        }
    }

    /** One keep-warm iteration; exposed for tests and for callers that drive their own loop. */
    suspend fun tick() {
        val now = clock.nowMillis()
        warm.entries.removeIf { it.value.untilMillis <= now }
        val tt = timetable.value
        if (tt != null && warm.isNotEmpty()) {
            val wanted = HashMap<FeedDefinition, DemandKind>()
            for (registration in warm.values) {
                for (feed in feedsFor(registration.routeIds, tt)) {
                    val current = wanted[feed]
                    if (current == null || minAgeMs(feed, FeedKind.TRIP_UPDATES, registration.demand) <
                        minAgeMs(feed, FeedKind.TRIP_UPDATES, current)
                    ) {
                        wanted[feed] = registration.demand
                    }
                }
            }
            coroutineScope {
                wanted.map { (feed, demand) ->
                    async { refreshFeeds(listOf(feed), listOf(FeedKind.TRIP_UPDATES, FeedKind.ALERTS), demand) }
                }.awaitAll()
            }
        }
        for (feed in config.feeds) publishTripChanges(feed)
    }

    // ---------------------------------------------------------------------------------------------
    // Fetching

    private suspend fun refreshFeeds(feeds: List<FeedDefinition>, kinds: List<FeedKind>, demand: DemandKind) {
        val targets = feeds.flatMap { feed -> kinds.mapNotNull { kind -> sources[feed.id to kind] } }
        if (targets.isEmpty()) return
        val outcomes = coroutineScope {
            targets.map { s -> async { s to refresh(s, minAgeMs(s.feed, s.kind, demand)) } }.awaitAll()
        }
        // Alerts are published as one combined set, so wait until every alerts feed is in.
        if (outcomes.any { (s, outcome) -> s.kind == FeedKind.ALERTS && outcome == Refresh.UPDATED }) publishAlerts()
    }

    private suspend fun refresh(source: Source, minAgeMs: Long): Refresh {
        val outcome = try {
            source.mutex.withLock { refreshLocked(source, minAgeMs) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Refreshing {} {} failed", source.feed.id, source.kind.id, e)
            Refresh.FAILED
        }
        if (outcome != Refresh.NOT_DUE && source.kind == FeedKind.TRIP_UPDATES) publishTripChanges(source.feed)
        return outcome
    }

    private suspend fun refreshLocked(source: Source, minAgeMs: Long): Refresh {
        val now = clock.nowMillis()
        source.lastSuccessMs?.let { if (now - it < minAgeMs) return Refresh.NOT_DUE }
        if (keyMissing) {
            logNoKey()
            source.lastAttemptOk = false
            return Refresh.SKIPPED
        }
        source.backoffUntilMs?.let { if (now < it) return Refresh.SKIPPED }

        val result = fetchWithRetry(source)
        val fetchedAt = clock.nowMillis()
        if (result != null) source.lastFetchMs = fetchedAt
        when (result) {
            null -> return Refresh.SKIPPED // daily budget used up
            is FetchResult.NotConfigured -> {
                if (source.notConfiguredLogged.compareAndSet(false, true)) {
                    log.warn("Feed {} {} not available: {}", source.feed.id, source.kind.id, result.reason)
                }
                source.lastAttemptOk = false
                return Refresh.SKIPPED
            }
            is FetchResult.HttpError -> {
                onFailure(source, result.status, "HTTP ${result.status} ${result.detail.take(80)}", fetchedAt)
                return Refresh.FAILED
            }
            is FetchResult.NetworkError -> {
                onFailure(source, null, result.detail, fetchedAt)
                return Refresh.FAILED
            }
            is FetchResult.Success -> {
                val message = try {
                    FeedMessage.ADAPTER.decode(result.bytes)
                } catch (e: Exception) {
                    onFailure(source, 200, "undecodable feed: $e", fetchedAt)
                    return Refresh.FAILED
                }
                source.lastHttpStatus = 200
                source.consecutiveErrors = 0
                source.backoffUntilMs = null
                source.lastAttemptOk = true
                source.lastSuccessMs = fetchedAt
                recorder?.record(source.feed.id, source.kind, result.bytes)

                val headerMs = message.header_.timestamp?.takeIf { it > 0 }?.let { it * 1000 }
                val tt = timetable.value
                if (headerMs != null && headerMs == source.dataTimeMs && source.processedVersion == tt?.version) {
                    return Refresh.UNCHANGED
                }
                val feedTimeMs = headerMs ?: fetchedAt
                process(source, message, tt, feedTimeMs)
                source.dataTimeMs = feedTimeMs
                source.processedVersion = tt?.version
                return Refresh.UPDATED
            }
        }
    }

    /** One call, plus one retry after [retryDelayMs] for non-throttle 5xx and network errors. Null if over budget. */
    private suspend fun fetchWithRetry(source: Source): FetchResult? {
        val first = callOnce(source) ?: return null
        val retry = when (first) {
            is FetchResult.HttpError -> first.status >= 500 && first.status !in BackoffPolicy.THROTTLE_STATUSES
            is FetchResult.NetworkError -> true
            else -> false
        }
        if (!retry) return first
        log.info("Fetch of {} {} failed ({}); retrying in {} ms", source.feed.id, source.kind.id, first, retryDelayMs)
        delay(retryDelayMs)
        return callOnce(source) ?: first
    }

    private suspend fun callOnce(source: Source): FetchResult? {
        if (fetcher.isRemote && !quota.acquire()) return null
        return fetcher.fetch(source.feed, source.kind)
    }

    private fun onFailure(source: Source, status: Int?, detail: String, now: Long) {
        source.lastHttpStatus = status
        source.lastAttemptOk = false
        source.consecutiveErrors++
        val wait = backoff.delayMs(source.consecutiveErrors)
        source.backoffUntilMs = now + wait
        val throttled = status != null && status in BackoffPolicy.THROTTLE_STATUSES
        log.warn(
            "Feed {} {} {} (error #{}); backing off {} s",
            source.feed.id, source.kind.id, if (throttled) "throttled: $detail" else "failed: $detail",
            source.consecutiveErrors, wait / 1000,
        )
    }

    private fun logNoKey() {
        if (noKeyLogged.compareAndSet(false, true)) {
            log.warn("TFNSW_API_KEY is not set: live data is disabled, TfNSW is never called and all live responses are empty/stale")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Processing

    private fun process(source: Source, message: FeedMessage, tt: Timetable?, feedTimeMs: Long) {
        val feed = source.feed
        when (source.kind) {
            FeedKind.TRIP_UPDATES -> tripData[feed.id] = processTrips(feed, message, tt, feedTimeMs)
            FeedKind.VEHICLES -> vehicleData[feed.id] = VehicleData(
                message.entity.filter { it.is_deleted != true }.mapNotNull { VehicleMapper.map(it, feedTimeMs, tt) },
            )
            FeedKind.ALERTS -> alertData[feed.id] = processAlerts(feed, message, tt, feedTimeMs)
        }
    }

    private fun processTrips(feed: FeedDefinition, message: FeedMessage, tt: Timetable?, feedTimeMs: Long): TripData {
        if (tt == null) return TripData(emptyMap(), 0, 0)
        val matcher = TripMatcher(tt)
        val deltas = LinkedHashMap<TripKey, TripDelta>()
        var matched = 0
        val unmatched = ArrayList<String>()
        for (entity in message.entity) {
            val update = entity.trip_update ?: continue
            if (entity.is_deleted == true) continue
            val result = try {
                matcher.match(update, feedTimeMs)
            } catch (e: Exception) {
                log.debug("Matching trip {} failed", update.trip.trip_id, e)
                MatchResult.Unmatched(update.trip.trip_id, "error: $e")
            }
            when (result) {
                is MatchResult.Matched -> {
                    matched++
                    deltas[TripKey(result.delta.tripId, result.delta.startDate)] = result.delta
                }
                is MatchResult.Finished -> matched++
                is MatchResult.Unmatched -> {
                    unmatched += "${result.tripId}: ${result.reason}"
                }
            }
        }
        if (unmatched.isNotEmpty()) {
            log.info(
                "Feed {}: matched {} trip updates, {} unmatched against timetable {} (e.g. {})",
                feed.id, matched, unmatched.size, tt.version, unmatched.take(3),
            )
        }
        return TripData(deltas, matched, unmatched.size)
    }

    private fun processAlerts(feed: FeedDefinition, message: FeedMessage, tt: Timetable?, feedTimeMs: Long): List<Alert> {
        val seenKeys = HashSet<String>()
        val alerts = message.entity.mapNotNull { entity ->
            val alert = AlertTranslator.translate(entity, feed, tt, updatedMs = 0) ?: return@mapNotNull null
            val key = "${feed.id}/${alert.id}"
            seenKeys += key
            val hash = alert.hashCode()
            // updatedMs = when this version of the alert was first seen, so unchanged alerts keep their ETag.
            val firstSeen = alertFirstSeen.compute(key) { _, old -> if (old?.first == hash) old else hash to feedTimeMs }!!
            alert.copy(updatedMs = firstSeen.second)
        }
        alertFirstSeen.keys.removeIf { it.startsWith("${feed.id}/") && it !in seenKeys }
        return alerts
    }

    // ---------------------------------------------------------------------------------------------
    // Change notification

    private suspend fun publishTripChanges(feed: FeedDefinition) {
        val lock = emitLocks.getOrPut(feed.id) { Mutex() }
        lock.withLock {
            val data = tripData[feed.id] ?: return
            val source = source(feed, FeedKind.TRIP_UPDATES) ?: return
            val now = clock.nowMillis()
            val feedStale = isStale(source, minAgeMs(feed, FeedKind.TRIP_UPDATES, DemandKind.WATCH), now)
            val current = data.deltas.mapValues { (_, d) -> d.copy(stale = feedStale || isTripStale(d, now)) }
            val previous = lastEmitted[feed.id].orEmpty()
            val changed = current.filter { (key, delta) ->
                val before = previous[key]
                before == null || before.copy(updatedMs = 0) != delta.copy(updatedMs = 0)
            }.values.toList()
            lastEmitted[feed.id] = current
            if (changed.isNotEmpty()) {
                _tripChanges.emit(TripChangeBatch(feed.id, source.dataTimeMs ?: 0, feedStale, changed))
            }
        }
    }

    private suspend fun publishAlerts() {
        alertEmitLock.withLock {
            val alerts = currentAlerts(null)
            if (alerts != lastPublishedAlerts) {
                lastPublishedAlerts = alerts
                _alertChanges.emit(alerts)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers

    private fun source(feed: FeedDefinition, kind: FeedKind): Source? = sources[feed.id to kind]

    /** Feeds whose mode serves any of [routeIds]; unknown routes are ignored. */
    private fun feedsFor(routeIds: Set<RouteId>, tt: Timetable): List<FeedDefinition> {
        val modes = routeIds.mapNotNullTo(HashSet()) { tt.route(it)?.mode }
        return config.feeds.filter { it.mode in modes }
    }

    private fun kindsFor(demand: DemandKind): List<FeedKind> = when (demand) {
        DemandKind.SCREEN, DemandKind.TRACKING -> listOf(FeedKind.TRIP_UPDATES, FeedKind.VEHICLES, FeedKind.ALERTS)
        DemandKind.WATCH -> listOf(FeedKind.TRIP_UPDATES, FeedKind.ALERTS)
    }

    private fun minAgeMs(feed: FeedDefinition, kind: FeedKind, demand: DemandKind): Long = 1000L * when (kind) {
        FeedKind.ALERTS -> feed.minAgeAlertsS
        else -> when (demand) {
            DemandKind.SCREEN, DemandKind.TRACKING -> feed.minAgeOnScreenS
            DemandKind.WATCH -> feed.minAgeWatchS
        }
    }

    /**
     * Stale when never fetched, last fetched more than 2x the minimum age ago, the feed's own timestamp
     * is older than that (or 3 min), or the daily budget is used up.
     */
    private fun isStale(source: Source?, minAgeMs: Long, now: Long): Boolean {
        source ?: return true
        val lastSuccess = source.lastSuccessMs ?: return true
        if (now - lastSuccess > 2 * minAgeMs) return true
        val dataTime = source.dataTimeMs
        if (dataTime != null && now - dataTime > maxOf(2 * minAgeMs, TRIP_STALE_MS)) return true
        if (fetcher.isRemote && quota.exhausted) return true
        return false
    }

    private fun isTripStale(delta: TripDelta, now: Long): Boolean = now - delta.updatedMs > TRIP_STALE_MS

    /** Adds staleness and vehicle details (occupancy, car count) to a stored delta. */
    private fun present(delta: TripDelta, feed: FeedDefinition, feedStale: Boolean, now: Long): TripDelta {
        val vehicle = vehicleData[feed.id]?.find(delta)?.takeIf { now - it.timestampMs <= VEHICLE_MAX_AGE_MS }
        return delta.copy(
            stale = feedStale || isTripStale(delta, now),
            occupancy = vehicle?.occupancy ?: delta.occupancy,
            carCount = vehicle?.carCount ?: delta.carCount,
            vehicleId = delta.vehicleId ?: vehicle?.vehicleId,
        )
    }

    private fun currentAlerts(modes: Set<Mode>?): List<Alert> =
        config.feeds.flatMap { alertData[it.id].orEmpty() }
            .distinctBy { it.id }
            .filter { modes == null || it.modes.any { mode -> mode in modes } }
            .sortedBy { it.id }

    private fun realtimeResponse(
        now: Long,
        tt: Timetable,
        feeds: List<FeedDefinition>,
        demand: DemandKind,
        trips: List<TripDelta>,
    ) = RealtimeResponse(
        serverTimeMs = now,
        pollIntervalS = config.pollIntervalS,
        trackerPollIntervalS = config.trackerPollIntervalS,
        timetableVersion = tt.version,
        feeds = feeds.map { health(it, demand, now) },
        trips = trips,
        alertsEtag = if (feeds.isEmpty()) null else AlertTranslator.etag(currentAlerts(feeds.mapTo(HashSet()) { it.mode })),
    )

    private fun emptyRealtime() = RealtimeResponse(
        serverTimeMs = clock.nowMillis(),
        pollIntervalS = config.pollIntervalS,
        trackerPollIntervalS = config.trackerPollIntervalS,
    )

    private fun health(feed: FeedDefinition, demand: DemandKind, now: Long): FeedHealth {
        val source = source(feed, FeedKind.TRIP_UPDATES) ?: sources.values.firstOrNull { it.feed.id == feed.id }
        return FeedHealth(
            feed = feed.id,
            mode = feed.mode,
            dataTimeMs = source?.dataTimeMs ?: 0,
            stale = isStale(source, source?.let { minAgeMs(feed, it.kind, demand) } ?: 0, now),
            ok = source != null && source.lastAttemptOk && !keyMissing,
        )
    }

    private class Source(val feed: FeedDefinition, val kind: FeedKind) {
        val mutex = Mutex()
        val notConfiguredLogged = AtomicBoolean(false)
        @Volatile var lastFetchMs: Long? = null
        @Volatile var lastSuccessMs: Long? = null
        @Volatile var dataTimeMs: Long? = null
        @Volatile var lastHttpStatus: Int? = null
        @Volatile var consecutiveErrors: Int = 0
        @Volatile var backoffUntilMs: Long? = null
        @Volatile var lastAttemptOk: Boolean = true
        @Volatile var processedVersion: String? = null
    }

    private class TripData(val deltas: Map<TripKey, TripDelta>, val matched: Int, val unmatched: Int)

    private class VehicleData(val vehicles: List<VehiclePosition>) {
        private val byKey = vehicles.filter { it.tripId != null && it.startDate != null }
            .associateBy { TripKey(it.tripId!!, it.startDate!!) }
        private val byVehicleId = vehicles.associateBy { it.vehicleId }
        private val byTripId = vehicles.filter { it.tripId != null && it.startDate == null }
            .groupBy { it.tripId!! }.filterValues { it.size == 1 }.mapValues { it.value.single() }

        fun find(delta: TripDelta): VehiclePosition? =
            byKey[TripKey(delta.tripId, delta.startDate)]
                ?: delta.vehicleId?.let { byVehicleId[it] }
                ?: byTripId[delta.tripId]
    }

    private data class WarmRegistration(val routeIds: Set<RouteId>, val demand: DemandKind, val untilMillis: Long)

    private enum class Refresh { NOT_DUE, SKIPPED, FAILED, UNCHANGED, UPDATED }

    companion object {
        /** A trip update older than this is stale. */
        const val TRIP_STALE_MS: Long = 3 * 60_000L

        /** Vehicle positions older than this are hidden. */
        const val VEHICLE_MAX_AGE_MS: Long = 2 * 60_000L

        /** File in [ServerConfig.dataDir] holding today's TfNSW call count (see [TfnswQuota]). */
        const val CALL_COUNTER_FILE: String = TfnswQuota.CALL_COUNTER_FILE

        private val log = LoggerFactory.getLogger(LiveDataImpl::class.java)
    }
}
