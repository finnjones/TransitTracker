package app.transittracker.server.watch

import app.transittracker.model.Alert
import app.transittracker.model.AlertSeverity
import app.transittracker.model.PushKeys
import app.transittracker.model.RouteId
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceTime
import app.transittracker.model.StopDelta
import app.transittracker.model.StopId
import app.transittracker.model.Timetable
import app.transittracker.model.TripDelta
import app.transittracker.model.TripId
import app.transittracker.model.TripStatus
import app.transittracker.model.WallClock
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchKind
import app.transittracker.server.spi.ApiException
import app.transittracker.server.spi.DemandKind
import app.transittracker.server.spi.DeviceRepository
import app.transittracker.server.spi.LiveData
import app.transittracker.server.spi.NotificationLog
import app.transittracker.server.spi.NotificationLogEntry
import app.transittracker.server.spi.PushMessage
import app.transittracker.server.spi.PushResult
import app.transittracker.server.spi.PushSender
import app.transittracker.server.spi.ServerConfig
import app.transittracker.server.spi.TripChangeBatch
import app.transittracker.server.spi.WatchRepository
import app.transittracker.server.spi.WatchService
import app.transittracker.server.spi.WatchStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.slf4j.LoggerFactory

/**
 * The push watch engine (plan section 7.3).
 *
 * - **Coverage**: works out the concrete trips each watch covers (yesterday, today and tomorrow,
 *   local Sydney dates, so lead times and late windows around midnight work), daily at 03:30,
 *   whenever the timetable changes, and on every upsert.
 * - **Activation**: every minute, a watch is active from `window start − lead` until its last
 *   covered trip's expected departure from the origin plus a grace period; active watches keep
 *   their routes' feeds warm through [LiveData.keepWarm].
 * - **Evaluation**: on every trip-updates refresh and alert change, compares each covered trip
 *   with what the friend was last told and notifies on a change of state only.
 * - **Sending**: one [PushMessage] per decision, every attempt logged to [NotificationLog];
 *   an invalid token is cleared from the device.
 *
 * Thread-safe: in-memory state is guarded by one lock that is never held across a suspension
 * or a push send.
 */
class WatchEngine(
    @Suppress("unused") private val config: ServerConfig,
    private val watches: WatchRepository,
    private val devices: DeviceRepository,
    private val log: NotificationLog,
    private val live: LiveData,
    private val timetable: StateFlow<Timetable?>,
    private val push: PushSender,
    private val clock: WallClock,
    private val settings: WatchEngineSettings = WatchEngineSettings(),
) : WatchService {

    private val logger = LoggerFactory.getLogger(WatchEngine::class.java)
    private val validator = WatchValidator(settings)
    private val coverage = CoverageCalculator(settings)
    private val lock = Any()

    // ---- state guarded by [lock] ----
    private val entries = HashMap<WatchKey, WatchEntry>()
    private val index = HashMap<TripKey, MutableSet<WatchKey>>()
    private val tripStates = HashMap<TripStateKey, TripNotifyState>()
    /** (alert id, local date) pairs already notified, per watch. */
    private val alertsNotified = HashMap<WatchKey, MutableSet<Pair<String, ServiceDate>>>()
    private var latestAlerts: List<Alert> = emptyList()
    private var coverageDate: ServiceDate? = null
    private var coverageTimetable: Timetable? = null
    private var counterDate: ServiceDate? = null
    private var sentToday = 0
    private var failedToday = 0

    // ------------------------------------------------------------------------------------------
    // WatchService

    override fun list(deviceId: String): List<WatchDto> = watches.list(deviceId)

    override fun upsert(deviceId: String, watch: WatchDto): WatchDto {
        val now = clock.nowMillis()
        val tt = timetable.value
        val existing = watches.get(deviceId, watch.id)
        val others = watches.list(deviceId).count { it.id != watch.id }
        validator.validate(watch, tt, others)
        val stored = watch.copy(
            daysOfWeek = watch.daysOfWeek.distinct().sorted(),
            routeIds = watch.routeIds.distinct(),
            createdMs = existing?.createdMs?.takeIf { it > 0 } ?: now,
            updatedMs = now,
        )
        watches.upsert(deviceId, stored)
        val key = WatchKey(deviceId, stored.id)
        val warm = synchronized(lock) {
            putEntryLocked(key, stored, tt, now)
            pruneStatesLocked()
            warmRequestLocked(entries.getValue(key), now)
        }
        warm?.let { live.keepWarm(it.key, it.routeIds, DemandKind.WATCH, it.untilMillis) }
        logger.info("Watch {} upserted for device {} ({} covered trips)", stored.id, deviceId, coveredTrips(deviceId, stored.id).size)
        return stored
    }

    override fun delete(deviceId: String, watchId: String): Boolean {
        val removed = watches.delete(deviceId, watchId)
        val key = WatchKey(deviceId, watchId)
        val wasIndexed = synchronized(lock) {
            val had = removeEntryLocked(key)
            alertsNotified.remove(key)
            tripStates.keys.removeAll { it.watch == key }
            had
        }
        if (wasIndexed) live.keepWarm(warmKey(watchId), emptySet(), DemandKind.WATCH, clock.nowMillis())
        return removed
    }

    override suspend fun sendTest(deviceId: String, watchId: String): PushResult {
        val watch = watches.get(deviceId, watchId) ?: throw ApiException(404, "watch_not_found", "No watch $watchId")
        val device = devices.get(deviceId)?.takeUnless { it.revoked }
            ?: throw ApiException(404, "device_not_found", "Unknown device")
        if (!device.notificationsEnabled) return PushResult.Failed("Notifications are disabled on this device", retryable = false)
        if (device.pushToken.isNullOrBlank()) return PushResult.Failed("Device has no push token", retryable = false)
        val names = synchronized(lock) { entries[WatchKey(deviceId, watchId)]?.let { it.originName to it.destinationName } }
        val title = titleFor(watch, names?.first, names?.second)
        return deliver(
            Outgoing(
                deviceId = deviceId,
                watchId = watchId,
                kind = NotificationKind.TEST,
                tripId = null,
                serviceDate = null,
                alertId = null,
                title = title,
                body = "Test notification: alerts for this watch will look like this",
                collapseKey = "test:$watchId",
                ttlSeconds = TEST_TTL_SECONDS,
            ),
        )
    }

    override fun status(): WatchStatus {
        val now = clock.nowMillis()
        val total = watches.all().size
        return synchronized(lock) {
            rollCountersLocked(now)
            WatchStatus(
                totalWatches = total,
                activeWatches = entries.values.count { activeOccurrenceLocked(it, now) != null },
                sentToday = sentToday,
                failedToday = failedToday,
            )
        }
    }

    override fun start(scope: CoroutineScope) {
        scope.launch {
            timetable.collect { tt ->
                val changed = synchronized(lock) { tt !== coverageTimetable || coverageDate == null }
                if (changed) {
                    logger.info("Timetable {}; recomputing watch coverage", tt?.version ?: "not loaded")
                    recomputeAll()
                }
            }
        }
        scope.launch { live.tripChanges.collect { batch -> guarded("trip changes") { onTripChanges(batch) } } }
        scope.launch { live.alertChanges.collect { alerts -> guarded("alert changes") { onAlerts(alerts) } } }
        scope.launch {
            while (isActive) {
                guarded("activation tick") { tick() }
                val now = clock.nowMillis()
                delay(settings.tickIntervalMs - Math.floorMod(now, settings.tickIntervalMs))
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Coverage

    /** Recomputes coverage of every stored watch against the current timetable. */
    internal fun recomputeAll() {
        val now = clock.nowMillis()
        synchronized(lock) { recomputeLocked(now) }
    }

    /** Covered trips of one watch, sorted by departure (for tests and diagnostics). */
    internal fun coveredTrips(deviceId: String, watchId: String): List<CoveredTrip> =
        synchronized(lock) { entries[WatchKey(deviceId, watchId)]?.trips.orEmpty() }

    private fun recomputeLocked(now: Long) {
        val tt = timetable.value
        entries.clear()
        index.clear()
        for (stored in watches.all()) {
            putEntryLocked(WatchKey(stored.deviceId, stored.watch.id), stored.watch, tt, now)
        }
        pruneStatesLocked()
        val today = ServiceTime.localDate(now)
        alertsNotified.values.forEach { set -> set.removeAll { it.second < previousDate(today) } }
        coverageDate = today
        coverageTimetable = tt
        logger.info("Watch coverage: {} watches, {} trips indexed", entries.size, index.size)
    }

    private fun putEntryLocked(key: WatchKey, watch: WatchDto, tt: Timetable?, now: Long) {
        removeEntryLocked(key)
        val originStops = stationStops(tt, watch.originStationId)
        val destinationStops = stationStops(tt, watch.destinationStationId)
        val trips = when {
            tt == null -> emptyList()
            watch.kind == WatchKind.SINGLE_SERVICE -> coverage.singleService(tt, watch, originStops, destinationStops)
            else -> {
                val today = ServiceTime.parseDate(ServiceTime.localDate(now))
                listOf(today.minus(1, DateTimeUnit.DAY), today, today.plus(1, DateTimeUnit.DAY))
                    .flatMap { coverage.commute(tt, watch, originStops, destinationStops, it) }
            }
        }.distinctBy { TripKey(it.tripId, it.serviceDate) }.sortedBy { it.departureMillis }
        val entry = WatchEntry(
            key = key,
            watch = watch,
            originStops = originStops,
            destinationStops = destinationStops,
            originName = stationName(tt, watch.originStationId),
            destinationName = stationName(tt, watch.destinationStationId),
            trips = trips,
        )
        entries[key] = entry
        for (trip in trips) index.getOrPut(TripKey(trip.tripId, trip.serviceDate)) { HashSet() } += key
    }

    private fun removeEntryLocked(key: WatchKey): Boolean {
        val old = entries.remove(key) ?: return false
        for (trip in old.trips) {
            val tripKey = TripKey(trip.tripId, trip.serviceDate)
            index[tripKey]?.let { it -= key; if (it.isEmpty()) index.remove(tripKey) }
        }
        return true
    }

    private fun pruneStatesLocked() {
        tripStates.keys.removeAll { stateKey ->
            entries[stateKey.watch]?.trips?.none { it.tripId == stateKey.tripId && it.serviceDate == stateKey.serviceDate } ?: true
        }
        alertsNotified.keys.removeAll { it !in entries }
    }

    private fun stationStops(tt: Timetable?, stationId: StopId): Set<StopId> =
        (tt?.stopsAtStation(stationId)?.map { it.id }.orEmpty() + stationId).toSet()

    private fun stationName(tt: Timetable?, stationId: StopId): String =
        NotificationText.stationName(tt?.stop(stationId)?.name ?: stationId)

    // ------------------------------------------------------------------------------------------
    // Activation

    /**
     * One run of the activation loop: daily coverage refresh at 03:30, single-service expiry,
     * keep-warm demand for active watches, and alert evaluation for newly active watches.
     */
    internal suspend fun tick() {
        val now = clock.nowMillis()
        val expired = ArrayList<WatchKey>()
        val warm = synchronized(lock) {
            val today = ServiceTime.localDate(now)
            if (coverageDate != today && ServiceTime.secondsOfLocalDay(now) >= settings.dailyCoverageSecondOfDay) {
                recomputeLocked(now)
            }
            entries.values.filter { isExpiredLocked(it, now) }.forEach { expired += it.key }
            entries.values.filter { it.key !in expired }.mapNotNull { warmRequestLocked(it, now) }
        }
        for (key in expired) {
            logger.info("Single-service watch {} expired after arrival", key.watchId)
            delete(key.deviceId, key.watchId)
        }
        for (request in warm) live.keepWarm(request.key, request.routeIds, DemandKind.WATCH, request.untilMillis)
        evaluateAlerts(now)
    }

    private fun warmRequestLocked(entry: WatchEntry, now: Long): WarmRequest? {
        val occurrence = activeOccurrenceLocked(entry, now) ?: return null
        return WarmRequest(warmKey(entry.watch.id), occurrence.trips.map { it.routeId }.toSet(), occurrence.untilMillis)
    }

    private fun occurrencesLocked(entry: WatchEntry): List<Occurrence> =
        entry.trips.groupBy { it.windowStartMillis }.map { (windowStart, trips) ->
            val lastDeparture = trips.maxOf { expectedDepartureMillis(it) }
            Occurrence(
                trips = trips,
                fromMillis = windowStart - entry.watch.leadMinutes * 60_000L,
                untilMillis = lastDeparture + settings.departureGraceMs,
            )
        }

    private fun activeOccurrenceLocked(entry: WatchEntry, now: Long): Occurrence? =
        occurrencesLocked(entry).firstOrNull { now >= it.fromMillis && now <= it.untilMillis }

    /** Scheduled departure plus the latest non-stale cached delay (never earlier than scheduled). */
    private fun expectedDepartureMillis(trip: CoveredTrip): Long {
        val delta = live.cachedTrip(trip.tripId, trip.serviceDate)?.takeUnless { it.stale } ?: return trip.departureMillis
        val delayS = originDelta(delta, trip)?.let { it.departureDelayS ?: it.arrivalDelayS } ?: 0
        return trip.departureMillis + maxOf(delayS, 0) * 1000L
    }

    private fun expectedArrivalMillis(trip: CoveredTrip): Long {
        val delta = live.cachedTrip(trip.tripId, trip.serviceDate)?.takeUnless { it.stale } ?: return trip.arrivalMillis
        val stop = delta.stops.firstOrNull { it.stopSequence == trip.destinationStopSequence }
        val delayS = stop?.let { it.arrivalDelayS ?: it.departureDelayS } ?: 0
        return trip.arrivalMillis + maxOf(delayS, 0) * 1000L
    }

    private fun isExpiredLocked(entry: WatchEntry, now: Long): Boolean {
        if (entry.watch.kind != WatchKind.SINGLE_SERVICE) return false
        val trip = entry.trips.firstOrNull()
        if (trip != null) return now > expectedArrivalMillis(trip) + settings.departureGraceMs
        // Not covered (trip gone from the timetable): expire once its service day is long over.
        val date = entry.watch.serviceDate ?: return false
        if (timetable.value == null) return false
        return now > ServiceTime.toEpochMillis(date, ServiceTime.MAX_SERVICE_SECONDS) + settings.departureGraceMs
    }

    // ------------------------------------------------------------------------------------------
    // Evaluation: trips

    /** Evaluates one trip-updates refresh and sends any notifications it causes. */
    internal suspend fun onTripChanges(batch: TripChangeBatch) {
        val now = clock.nowMillis()
        if (batch.stale || isOld(batch.dataTimeMs, now)) {
            logger.debug("Ignoring stale trip batch from {}", batch.feedId)
            return
        }
        val outgoing = synchronized(lock) { batch.changed.flatMap { decideTripLocked(it, now) } }
        for (message in outgoing) deliver(message)
    }

    private fun isOld(timestampMs: Long, now: Long) = timestampMs > 0 && now - timestampMs > settings.staleAfterMs

    private fun decideTripLocked(delta: TripDelta, now: Long): List<Outgoing> {
        if (delta.stale || isOld(delta.updatedMs, now)) return emptyList()
        val keys = index[TripKey(delta.tripId, delta.startDate)] ?: return emptyList()
        val result = ArrayList<Outgoing>()
        for (key in keys) {
            val entry = entries[key] ?: continue
            val trip = entry.trips.firstOrNull { it.tripId == delta.tripId && it.serviceDate == delta.startDate } ?: continue
            val occurrence = activeOccurrenceLocked(entry, now) ?: continue
            if (trip !in occurrence.trips) continue
            if (isPaused(entry.watch, now) || !canReceive(key.deviceId)) continue
            decideForWatchLocked(entry, occurrence, trip, delta, now)?.let { result += it }
        }
        return result
    }

    private fun decideForWatchLocked(
        entry: WatchEntry,
        occurrence: Occurrence,
        trip: CoveredTrip,
        delta: TripDelta,
        now: Long,
    ): Outgoing? {
        val rules = entry.watch.rules
        val cancelled = delta.status == TripStatus.CANCELLED
        val origin = originDelta(delta, trip)
        // Live data lists remaining stops only: no origin entry means the trip has left it.
        if (!cancelled && delta.stops.isNotEmpty() && origin == null) return null
        val delayS = if (cancelled) 0 else origin?.let { it.departureDelayS ?: it.arrivalDelayS } ?: 0
        val expectedDeparture = trip.departureMillis + delayS * 1000L
        if (now >= expectedDeparture) return null

        val stateKey = TripStateKey(entry.key, trip.tripId, trip.serviceDate)
        val state = tripStates.getOrPut(stateKey) { TripNotifyState() }
        val origName = entry.originName
        val destName = entry.destinationName
        val depart = trip.departureMillis

        // Cancellation, or the trip no longer calls at one of the watched stations.
        val skippedStation = when {
            origin?.skipped == true -> origName
            delta.stops.firstOrNull { it.stopSequence == trip.destinationStopSequence }?.skipped == true -> destName
            else -> null
        }
        if (cancelled || skippedStation != null) {
            if (state.cancelled) return null
            state.cancelled = true
            if (!rules.notifyCancel) return null
            val next = nextServiceText(occurrence, trip)
            val body = if (cancelled) {
                NotificationText.cancelled(depart, origName, destName, next)
            } else {
                NotificationText.skipsStop(depart, origName, destName, skippedStation!!, next)
            }
            return tripMessage(entry, trip, NotificationKind.CANCEL, body, now)
        }
        if (state.cancelled) state.cancelled = false // reinstated
        if (origin == null) return null // no prediction for this trip yet

        val parts = ArrayList<Pair<String, String>>() // kind to sentence
        val delayMin = Math.floorDiv(delayS, 60)
        val last = state.lastDelayNotifiedMin
        when {
            last == null && delayMin >= rules.delayThresholdMin -> {
                parts += NotificationKind.DELAY to NotificationText.delayed(depart, destName, delayMin, expectedDeparture)
                state.lastDelayNotifiedMin = delayMin
            }
            last != null && delayMin >= last + rules.delayStepMin -> {
                parts += NotificationKind.DELAY to NotificationText.delayGrew(depart, destName, delayMin, expectedDeparture)
                state.lastDelayNotifiedMin = delayMin
            }
            last != null && delayMin <= settings.onTimeMaxDelayMin -> {
                if (rules.notifyRecovered) parts += NotificationKind.RECOVERED to NotificationText.recovered(depart, destName)
                state.lastDelayNotifiedMin = null
            }
        }

        val platformStop = origin.actualStopId ?: trip.originStopId
        val previousPlatform = state.platformStopId ?: trip.originStopId
        if (platformStop != previousPlatform) {
            state.platformStopId = platformStop
            if (rules.notifyPlatform) {
                parts.add(0, NotificationKind.PLATFORM to NotificationText.platform(depart, destName, platformLabel(platformStop)))
            }
        }
        if (parts.isEmpty()) return null
        return tripMessage(entry, trip, parts.first().first, parts.joinToString(". ") { it.second }, now)
    }

    private fun originDelta(delta: TripDelta, trip: CoveredTrip): StopDelta? =
        delta.stops.firstOrNull { it.stopSequence == trip.originStopSequence }

    private fun platformLabel(stopId: StopId): String {
        val stop = timetable.value?.stop(stopId)
        return stop?.platformCode?.takeIf { it.isNotBlank() } ?: stop?.name ?: stopId
    }

    /** "Next: 7:51 (on time)" for the next covered, not-cancelled trip after [after]. */
    private fun nextServiceText(occurrence: Occurrence, after: CoveredTrip): String {
        for (next in occurrence.trips.filter { it.departureMillis > after.departureMillis }.sortedBy { it.departureMillis }) {
            val delta = live.cachedTrip(next.tripId, next.serviceDate)?.takeUnless { it.stale }
            if (delta?.status == TripStatus.CANCELLED) continue
            val stop = delta?.let { originDelta(it, next) }
            val state = when {
                stop == null -> NotificationText.TIMETABLED
                stop.skipped -> continue
                else -> {
                    val minutes = Math.floorDiv(stop.departureDelayS ?: stop.arrivalDelayS ?: 0, 60)
                    if (minutes <= settings.onTimeMaxDelayMin) NotificationText.ON_TIME else NotificationText.lateBy(minutes)
                }
            }
            return NotificationText.next(next.departureMillis, state)
        }
        return NotificationText.NO_NEXT
    }

    private fun tripMessage(entry: WatchEntry, trip: CoveredTrip, kind: String, body: String, now: Long) = Outgoing(
        deviceId = entry.key.deviceId,
        watchId = entry.watch.id,
        kind = kind,
        tripId = trip.tripId,
        serviceDate = trip.serviceDate,
        alertId = null,
        title = titleFor(entry.watch, entry.originName, entry.destinationName),
        body = body,
        collapseKey = "${trip.tripId}:${trip.serviceDate}",
        ttlSeconds = maxOf(settings.minTtlSeconds, (trip.departureMillis - now) / 1000L),
    )

    // ------------------------------------------------------------------------------------------
    // Evaluation: alerts

    /** Records the current alert set and notifies active watches of alerts that touch them. */
    internal suspend fun onAlerts(alerts: List<Alert>) {
        synchronized(lock) { latestAlerts = alerts }
        evaluateAlerts(clock.nowMillis())
    }

    private suspend fun evaluateAlerts(now: Long) {
        val outgoing = synchronized(lock) { decideAlertsLocked(now) }
        for (message in outgoing) deliver(message)
    }

    private fun decideAlertsLocked(now: Long): List<Outgoing> {
        if (latestAlerts.isEmpty()) return emptyList()
        val today = ServiceTime.localDate(now)
        val result = ArrayList<Outgoing>()
        for (entry in entries.values) {
            if (!entry.watch.rules.notifyAlerts || isPaused(entry.watch, now)) continue
            val occurrence = activeOccurrenceLocked(entry, now) ?: continue
            val pending = occurrence.trips.filter { now < expectedDepartureMillis(it) }
            if (pending.isEmpty()) continue
            if (!canReceive(entry.key.deviceId)) continue
            val routes: Set<RouteId> = occurrence.trips.map { it.routeId }.toSet() + entry.watch.routeIds
            val tripIds: Set<TripId> = occurrence.trips.map { it.tripId }.toSet()
            val stops = entry.originStops + entry.destinationStops
            val sent = alertsNotified.getOrPut(entry.key) { HashSet() }
            for (alert in latestAlerts) {
                if (alert.severity == AlertSeverity.INFO) continue
                if (!alert.touches(routes, stops, tripIds)) continue
                if (!alert.overlaps(now, occurrence.untilMillis)) continue
                if (!sent.add(alert.id to today)) continue
                result += Outgoing(
                    deviceId = entry.key.deviceId,
                    watchId = entry.watch.id,
                    kind = NotificationKind.ALERT,
                    tripId = null,
                    serviceDate = null,
                    alertId = alert.id,
                    title = titleFor(entry.watch, entry.originName, entry.destinationName),
                    body = alert.headline,
                    collapseKey = "alert:${alert.id}",
                    ttlSeconds = maxOf(settings.minTtlSeconds, (occurrence.untilMillis - now) / 1000L),
                )
            }
        }
        return result
    }

    private fun Alert.touches(routes: Set<RouteId>, stops: Set<StopId>, trips: Set<TripId>): Boolean =
        routeIds.any { it in routes } || stopIds.any { it in stops } || tripIds.any { it in trips }

    private fun Alert.overlaps(from: Long, until: Long): Boolean =
        activePeriods.isEmpty() ||
            activePeriods.any { (it.startMs ?: Long.MIN_VALUE) <= until && (it.endMs ?: Long.MAX_VALUE) >= from }

    // ------------------------------------------------------------------------------------------
    // Gating and sending

    private fun isPaused(watch: WatchDto, now: Long) = watch.pausedUntilMs?.let { now < it } ?: false

    private fun canReceive(deviceId: String): Boolean {
        val device = devices.get(deviceId) ?: return false
        return !device.revoked && device.notificationsEnabled && !device.pushToken.isNullOrBlank()
    }

    private fun titleFor(watch: WatchDto, origin: String?, destination: String?): String =
        watch.label.ifBlank {
            "${origin ?: NotificationText.stationName(watch.originStationId)} → " +
                (destination ?: NotificationText.stationName(watch.destinationStationId))
        }

    /** Sends one message, logs the attempt and clears an invalid token. */
    private suspend fun deliver(message: Outgoing): PushResult {
        val now = clock.nowMillis()
        val token = devices.get(message.deviceId)?.pushToken
        val result = if (token.isNullOrBlank()) {
            PushResult.Failed("Device has no push token", retryable = false)
        } else {
            try {
                push.send(token, message.toPushMessage())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Push send threw for device {}: {}", message.deviceId, e.toString())
                PushResult.Failed(e.message ?: e.javaClass.simpleName, retryable = true)
            }
        }
        if (result is PushResult.InvalidToken) {
            logger.info("Push token of device {} is no longer valid; clearing it", message.deviceId)
            runCatching { devices.clearPushToken(message.deviceId) }
                .onFailure { logger.warn("Could not clear push token of device {}", message.deviceId, it) }
        }
        synchronized(lock) {
            rollCountersLocked(now)
            if (result is PushResult.Sent) sentToday++ else failedToday++
        }
        runCatching {
            log.record(
                NotificationLogEntry(
                    atMs = now,
                    deviceId = message.deviceId,
                    watchId = message.watchId,
                    tripId = message.tripId,
                    serviceDate = message.serviceDate,
                    kind = message.kind,
                    title = message.title,
                    result = result.describe(),
                ),
            )
        }.onFailure { logger.warn("Could not record notification log entry", it) }
        logger.info("Notification {} for watch {} ({}): {}", message.kind, message.watchId, message.collapseKey, result.describe())
        return result
    }

    private fun rollCountersLocked(now: Long) {
        val today = ServiceTime.localDate(now)
        if (counterDate != today) {
            counterDate = today
            sentToday = 0
            failedToday = 0
        }
    }

    private suspend inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Watch engine: {} failed", what, e)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Internal types

    private data class WatchKey(val deviceId: String, val watchId: String)

    private data class TripKey(val tripId: TripId, val serviceDate: ServiceDate)

    private data class TripStateKey(val watch: WatchKey, val tripId: TripId, val serviceDate: ServiceDate)

    /** What the friend was last told about one trip of one watch. */
    private class TripNotifyState(
        var cancelled: Boolean = false,
        /** Delay (minutes) in the last delay notification; null = none since on time. */
        var lastDelayNotifiedMin: Int? = null,
        /** Departure platform stop last notified (null = the scheduled one). */
        var platformStopId: StopId? = null,
    )

    private class WatchEntry(
        val key: WatchKey,
        val watch: WatchDto,
        val originStops: Set<StopId>,
        val destinationStops: Set<StopId>,
        val originName: String,
        val destinationName: String,
        val trips: List<CoveredTrip>,
    )

    /** One window's worth of covered trips and the period the watch is active for them. */
    private class Occurrence(val trips: List<CoveredTrip>, val fromMillis: Long, val untilMillis: Long)

    private class WarmRequest(val key: String, val routeIds: Set<RouteId>, val untilMillis: Long)

    private class Outgoing(
        val deviceId: String,
        val watchId: String,
        val kind: String,
        val tripId: TripId?,
        val serviceDate: ServiceDate?,
        val alertId: String?,
        val title: String,
        val body: String,
        val collapseKey: String,
        val ttlSeconds: Long,
    ) {
        fun toPushMessage() = PushMessage(
            title = title,
            body = body,
            data = buildMap {
                put(PushKeys.KIND, kind)
                put(PushKeys.WATCH_ID, watchId)
                tripId?.let { put(PushKeys.TRIP_ID, it) }
                serviceDate?.let { put(PushKeys.SERVICE_DATE, it) }
                alertId?.let { put(PushKeys.ALERT_ID, it) }
            },
            collapseKey = collapseKey,
            ttlSeconds = ttlSeconds,
            highPriority = true,
        )
    }

    private companion object {
        const val TEST_TTL_SECONDS = 300L

        fun warmKey(watchId: String) = "watch:$watchId"

        fun previousDate(date: ServiceDate): ServiceDate =
            ServiceTime.formatDate(ServiceTime.parseDate(date).minus(1, DateTimeUnit.DAY))

        fun PushResult.describe(): String = when (this) {
            is PushResult.Sent -> "sent"
            PushResult.InvalidToken -> "invalid_token"
            is PushResult.Failed -> "failed(${if (retryable) "retryable" else "permanent"}): $reason"
        }
    }
}
