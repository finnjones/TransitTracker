package app.transittracker.server.planner

import app.transittracker.model.Itinerary
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.PlacesResponse
import app.transittracker.model.PlanEndpoint
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanResponse
import app.transittracker.model.PlanTimeKind
import app.transittracker.model.Timetable
import app.transittracker.model.WallClock
import app.transittracker.server.live.BackoffPolicy
import app.transittracker.server.live.TfnswQuota
import app.transittracker.server.spi.ApiException
import app.transittracker.server.spi.Planner
import app.transittracker.server.spi.PlannerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong

/**
 * Journey planning through TfNSW's Trip Planner (`/v1/tp/stop_finder` and `/v1/tp/trip`), trimmed to
 * the contract in `Planning.kt` and matched to the loaded timetable.
 *
 * - Every call draws on the server-wide [quota] (the same daily budget and per-second limiter as the
 *   live feeds); replay fetchers do not.
 * - Throttle statuses (401, 403, 429, 503, as in the live code) start a backoff; meanwhile, and when
 *   the budget is used up or nothing is configured, requests fail with 503 `planner_unavailable`.
 *   Anything else unusable from TfNSW is 502 `planner_upstream`.
 * - Identical plans are cached for 60 s (positions rounded to 5 decimal places, time to the minute),
 *   identical searches for 5 minutes; both caches are bounded and in memory only.
 * - Privacy: places, positions and search text are sent only to TfNSW, never stored beyond those
 *   caches and never logged; logs carry counts, status and duration.
 *
 * @param liveModes modes with live feeds configured that can be fetched (`PlanResponse.liveModes`)
 * @param source "tfnsw" or "replay", for `/status`
 */
class TfnswPlanner(
    private val timetable: StateFlow<Timetable?>,
    private val fetcher: PlannerFetcher,
    private val quota: TfnswQuota?,
    private val clock: WallClock,
    private val liveModes: List<Mode> = emptyList(),
    private val source: String = if (fetcher.isRemote) "tfnsw" else "replay",
    private val backoff: BackoffPolicy = BackoffPolicy(),
    planCacheTtlMs: Long = 60_000,
    placesCacheTtlMs: Long = 5 * 60_000,
    cacheEntries: Int = 500,
) : Planner {

    private val planCache = TtlCache<PlanKey, List<Itinerary>>(cacheEntries, planCacheTtlMs, clock)
    private val placesCache = TtlCache<String, List<RankedPlace>>(cacheEntries, placesCacheTtlMs, clock)

    private val placesRequests = AtomicInteger()
    private val planRequests = AtomicInteger()
    private val cacheHits = AtomicInteger()
    private val upstreamCalls = AtomicInteger()
    private val upstreamFailures = AtomicInteger()
    private val matchedRides = AtomicInteger()
    private val unmatchedRides = AtomicInteger()
    @Volatile private var lastCallMs: Long? = null
    @Volatile private var lastHttpStatus: Int? = null
    @Volatile private var backoffUntilMs: Long? = null
    @Volatile private var consecutiveThrottles = 0

    /** One matcher per loaded timetable (it caches the routes by name). */
    @Volatile private var matcherFor: Pair<Timetable, RideMatcher>? = null

    override val available: Boolean get() = fetcher.configured

    // ---- places ----

    override suspend fun places(query: String, near: LatLng?, limit: Int): PlacesResponse {
        val text = query.trim().replace(Regex("\\s+"), " ")
        if (text.length !in 2..80) throw badRequest("Search text must be 2 to 80 characters")
        if (limit !in 1..MAX_PLACES) throw badRequest("limit must be 1 to $MAX_PLACES")
        near?.let { requireInArea(it, "Search position") }
        placesRequests.incrementAndGet()
        val key = text.lowercase(Locale.ROOT)
        val ranked = placesCache.get(key)?.also { cacheHits.incrementAndGet() } ?: run {
            val body = call(PlannerEndpoint.STOP_FINDER, TripPlannerRequests.stopFinder(text))
            val response = decode(body, StopFinderResponseDto.serializer())
            response.error?.message?.let { rejected() }
            // Timetable lookups read SQLite: off the request thread.
            withContext(Dispatchers.IO) { PlaceMapper(timetable.value).map(response) }.also { placesCache.put(key, it) }
        }
        val places = PlaceRanking.rank(ranked, text, near, limit)
        log.info("Place search: {} result(s) of {} (limit {})", places.size, ranked.size, limit)
        return PlacesResponse(serverTimeMs = clock.nowMillis(), places = places)
    }

    // ---- plan ----

    override suspend fun plan(request: PlanRequest): PlanResponse {
        val now = clock.nowMillis()
        validate(request, now)
        planRequests.incrementAndGet()
        val timeMs = request.timeMs ?: now
        val tt = timetable.value
        val key = PlanKey(
            from = endpointKey(request.from),
            to = endpointKey(request.to),
            minute = Math.floorDiv(timeMs, 60_000L),
            kind = request.timeKind,
            excluded = request.excludedModes.toSortedSet(),
            timetableVersion = tt?.version,
        )
        val itineraries = planCache.get(key)?.also { cacheHits.incrementAndGet() } ?: run {
            val started = System.nanoTime()
            val body = call(PlannerEndpoint.TRIP, TripPlannerRequests.trip(request, timeMs))
            val response = decode(body, TripResponseDto.serializer())
            response.error?.message?.let { rejected() }
            val mapped = withContext(Dispatchers.IO) {
                TripMapper(tt, matcher(tt)).map(response, JourneyEnds(request.from.position, request.to.position), request.timeKind)
            }
            matchedRides.addAndGet(mapped.matchedRides)
            unmatchedRides.addAndGet(mapped.unmatchedRides)
            log.info(
                "Journey plan: {} journey(s) from the trip planner, {} kept, {} dropped; rides matched {}, unmatched {}; {} ms",
                response.journeys.orEmpty().size, mapped.itineraries.size, mapped.droppedJourneys,
                mapped.matchedRides, mapped.unmatchedRides, (System.nanoTime() - started) / 1_000_000,
            )
            mapped.itineraries.also { planCache.put(key, it) }
        }
        return PlanResponse(
            serverTimeMs = clock.nowMillis(),
            itineraries = itineraries.take(request.maxItineraries),
            timetableVersion = tt?.version.orEmpty(),
            liveModes = liveModes,
        )
    }

    override fun status(): PlannerStatus = PlannerStatus(
        available = available,
        source = if (available) source else "off",
        placesRequests = placesRequests.get(),
        planRequests = planRequests.get(),
        cacheHits = cacheHits.get(),
        upstreamCalls = upstreamCalls.get(),
        upstreamFailures = upstreamFailures.get(),
        matchedRides = matchedRides.get(),
        unmatchedRides = unmatchedRides.get(),
        lastCallMs = lastCallMs,
        lastHttpStatus = lastHttpStatus,
        backoffUntilMs = backoffUntilMs?.takeIf { it > clock.nowMillis() },
    )

    // ---- validation ----

    private fun validate(request: PlanRequest, now: Long) {
        requireInArea(request.from.position, "Start")
        requireInArea(request.to.position, "End")
        request.from.placeId?.let(::requirePlaceId)
        request.to.placeId?.let(::requirePlaceId)
        if (Geo.distanceMetres(request.from.position, request.to.position) <= MIN_DISTANCE_M) {
            throw badRequest("Start and end must be more than ${MIN_DISTANCE_M.toInt()} m apart")
        }
        if (request.maxItineraries !in 1..MAX_ITINERARIES) throw badRequest("maxItineraries must be 1 to $MAX_ITINERARIES")
        request.timeMs?.let { t ->
            if (t < now - MAX_PAST_MS || t > now + MAX_FUTURE_MS) {
                throw badRequest("Time must be no more than 7 days in the past or 60 days ahead")
            }
        }
    }

    private fun requireInArea(p: LatLng, what: String) {
        if (!p.lat.isFinite() || !p.lon.isFinite() || p.lat !in AREA_LAT || p.lon !in AREA_LON) {
            throw badRequest("$what is outside New South Wales")
        }
    }

    private fun requirePlaceId(id: String) {
        if (id.isEmpty() || id.length > 300 || id.any { it.isISOControl() }) throw badRequest("Bad place id")
    }

    // ---- calling TfNSW ----

    private suspend fun call(endpoint: PlannerEndpoint, params: List<Pair<String, String>>): String {
        if (!fetcher.configured) throw unavailable("Journey planning is not set up on this server")
        val now = clock.nowMillis()
        backoffUntilMs?.let { if (now < it) throw unavailable("Transport for NSW is limiting requests; try again in a few minutes") }
        if (fetcher.isRemote && quota != null && !quota.acquire()) {
            throw unavailable("Today's journey planning quota is used up; it resets at midnight")
        }
        if (fetcher.isRemote) upstreamCalls.incrementAndGet()
        val started = System.nanoTime()
        val result = fetcher.fetch(endpoint, params)
        val ms = (System.nanoTime() - started) / 1_000_000
        lastCallMs = clock.nowMillis()
        return when (result) {
            is PlannerFetch.Success -> {
                lastHttpStatus = 200
                consecutiveThrottles = 0
                backoffUntilMs = null
                log.info("Trip planner {}: 200 in {} ms", endpoint.fileStem, ms)
                result.body
            }
            is PlannerFetch.HttpError -> {
                lastHttpStatus = result.status
                upstreamFailures.incrementAndGet()
                if (result.status in BackoffPolicy.THROTTLE_STATUSES) {
                    consecutiveThrottles++
                    val wait = backoff.delayMs(consecutiveThrottles)
                    backoffUntilMs = clock.nowMillis() + wait
                    log.warn("Trip planner {}: throttled (HTTP {}) in {} ms; backing off {} s", endpoint.fileStem, result.status, ms, wait / 1000)
                    throw unavailable("Transport for NSW is limiting requests; try again in a few minutes")
                }
                log.warn("Trip planner {}: HTTP {} in {} ms", endpoint.fileStem, result.status, ms)
                throw upstream("Transport for NSW's trip planner answered with an error")
            }
            is PlannerFetch.NetworkError -> {
                lastHttpStatus = null
                upstreamFailures.incrementAndGet()
                log.warn("Trip planner {}: no answer ({}) after {} ms", endpoint.fileStem, if (result.timeout) "timeout" else "network error", ms)
                throw upstream("Transport for NSW's trip planner did not answer")
            }
            is PlannerFetch.NotConfigured -> {
                log.warn("Trip planner {}: not available: {}", endpoint.fileStem, result.reason)
                throw unavailable("Journey planning is not set up on this server")
            }
        }
    }

    private fun <T> decode(body: String, serializer: KSerializer<T>): T = try {
        TfnswJson.decodeFromString(serializer, body)
    } catch (e: SerializationException) {
        upstreamFailures.incrementAndGet()
        log.warn("Trip planner answer could not be read: {}", e.javaClass.simpleName)
        throw upstream("Transport for NSW's trip planner answered with something unreadable")
    } catch (e: IllegalArgumentException) {
        upstreamFailures.incrementAndGet()
        log.warn("Trip planner answer could not be read: {}", e.javaClass.simpleName)
        throw upstream("Transport for NSW's trip planner answered with something unreadable")
    }

    /** TfNSW answered 200 with an `error` object [S]. */
    private fun rejected(): Nothing {
        upstreamFailures.incrementAndGet()
        log.warn("Trip planner answered with an error object")
        throw upstream("Transport for NSW's trip planner answered with an error")
    }

    private fun matcher(tt: Timetable?): RideMatcher? {
        tt ?: return null
        matcherFor?.let { (cached, matcher) -> if (cached === tt) return matcher }
        return RideMatcher(tt).also { matcherFor = tt to it }
    }

    private data class EndpointKey(val lat: Long, val lon: Long, val placeId: String?)

    private data class PlanKey(
        val from: EndpointKey,
        val to: EndpointKey,
        val minute: Long,
        val kind: PlanTimeKind,
        val excluded: Set<Mode>,
        val timetableVersion: String?,
    )

    private fun endpointKey(end: PlanEndpoint) =
        EndpointKey((end.position.lat * 1e5).roundToLong(), (end.position.lon * 1e5).roundToLong(), end.placeId)

    companion object {
        /** New South Wales and its surrounds. */
        val AREA_LAT = -38.0..-28.0
        val AREA_LON = 140.0..154.0
        const val MIN_DISTANCE_M = 50.0
        const val MAX_ITINERARIES = 6
        const val MAX_PLACES = 20
        const val MAX_PAST_MS = 7L * 24 * 3600_000
        const val MAX_FUTURE_MS = 60L * 24 * 3600_000

        private val log = LoggerFactory.getLogger(TfnswPlanner::class.java)

        private fun badRequest(message: String) = ApiException(400, "bad_request", message)
        private fun unavailable(message: String) = ApiException(503, "planner_unavailable", message)
        private fun upstream(message: String) = ApiException(502, "planner_upstream", message)
    }
}
