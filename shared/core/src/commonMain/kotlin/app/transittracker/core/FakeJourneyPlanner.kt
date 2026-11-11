@file:OptIn(ExperimentalTime::class)

package app.transittracker.core

import app.transittracker.model.DepartureStatus
import app.transittracker.model.Itinerary
import app.transittracker.model.ItineraryLeg
import app.transittracker.model.JourneyPlanner
import app.transittracker.model.LatLng
import app.transittracker.model.LegKind
import app.transittracker.model.LegPoint
import app.transittracker.model.Mode
import app.transittracker.model.Occupancy
import app.transittracker.model.Place
import app.transittracker.model.PlaceKind
import app.transittracker.model.PlacesResult
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanResponse
import app.transittracker.model.PlanResult
import app.transittracker.model.PlanTimeKind
import app.transittracker.model.ScheduledDeparture
import app.transittracker.model.StopId
import app.transittracker.model.WallClock
import app.transittracker.model.arriveMs
import app.transittracker.model.changes
import app.transittracker.model.departMs
import app.transittracker.model.departuresAfter
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A [JourneyPlanner] over canned Sydney data ([FakeData]) for front-end previews and UI tests.
 * Nothing touches the network; answers are deterministic for a given [clock] and request.
 *
 * Places: the sample stations (as stops with their GTFS ids), a bus stand, a few addresses,
 * points of interest and localities ([PLACES]). Words of the query must each start a word of the
 * place's name or detail; with `near`, nearer places come first. Queries shorter than two
 * characters find nothing, as with the real planner.
 *
 * Plans, whatever the two ends are (the ends only give the first and last walks their names and
 * positions), departing at or after the requested time (or arriving by it):
 * - a **train-to-train change**: T1 Parramatta → Strathfield, a short walk between platforms,
 *   T9 Strathfield → Chatswood;
 * - a **train-to-bus change**: T1 Parramatta → Central, a walk to the Eddy Av bus stand, bus 373
 *   to Coogee Beach (not matched to the timetable, so timetabled);
 * - a **cancelled ride**: bus 545 Parramatta → Chatswood, cancelled;
 * - a **walk-only** answer when the two ends are within [WALK_ONLY_MAX_M] metres
 *   (for example "Town Hall Station" and "Queen Victoria Building").
 *
 * Train rides are real trips of the sample timetable with [FakeData]'s live data applied, so
 * their `tripId` / `serviceDate` open in [FakeTransitCore.service] and agree with it. Ends less
 * than 50 m apart give the same `Failed` answer the server's `400` would.
 *
 * Set [simulate] to preview the other outcomes.
 */
class FakeJourneyPlanner(
    private val clock: WallClock = WallClock { Clock.System.now().toEpochMilliseconds() },
) : JourneyPlanner {
    private val timetable = FakeData.timetable()

    /** Outcomes the fake can be told to give, for previews of each screen state. */
    enum class Simulate {
        /** Normal answers. */
        NONE,

        /** Places and plans find nothing. */
        NOTHING_FOUND,

        /** Every call answers `Offline`. */
        OFFLINE,

        /** Every call answers `Unavailable`. */
        UNAVAILABLE,

        /** Every call answers `Failed` with the rate-limit sentence. */
        FAILED,
    }

    /** What the next calls answer; [Simulate.NONE] for normal answers. */
    var simulate: Simulate = Simulate.NONE

    override suspend fun searchPlaces(query: String, near: LatLng?, limit: Int): PlacesResult {
        when (simulate) {
            Simulate.OFFLINE -> return PlacesResult.Offline
            Simulate.UNAVAILABLE -> return PlacesResult.Unavailable
            Simulate.FAILED -> return PlacesResult.Failed(PlannerMessages.TOO_MANY)
            Simulate.NOTHING_FOUND -> return PlacesResult.Found(emptyList())
            Simulate.NONE -> Unit
        }
        val q = RemoteJourneyPlanner.normalizePlaceQuery(query) ?: return PlacesResult.Found(emptyList())
        val words = q.lowercase().split(' ', ',').filter { it.isNotEmpty() }
        val found = PLACES.filter { place ->
            val haystack = "${place.name} ${place.detail}".lowercase().split(' ', ',').filter { it.isNotEmpty() }
            words.all { w -> haystack.any { it.startsWith(w) } }
        }
        val ordered = if (near == null) found else found.sortedBy { distanceMeters(near, it.position) }
        return PlacesResult.Found(ordered.take(limit.coerceIn(1, RemoteJourneyPlanner.MAX_PLACES)))
    }

    /** The sample stations and the bus stand within [radiusMeters] of [position], nearest first. */
    override suspend fun stopsNear(position: LatLng, radiusMeters: Int): PlacesResult {
        when (simulate) {
            Simulate.OFFLINE -> return PlacesResult.Offline
            Simulate.UNAVAILABLE -> return PlacesResult.Unavailable
            Simulate.FAILED -> return PlacesResult.Failed(PlannerMessages.TOO_MANY)
            Simulate.NOTHING_FOUND -> return PlacesResult.Found(emptyList())
            Simulate.NONE -> Unit
        }
        val near = PLACES.filter { it.kind == PlaceKind.STOP }
            .map { it to distanceMeters(position, it.position) }
            .filter { it.second <= radiusMeters }
            .sortedBy { it.second }
            .map { it.first }
        return PlacesResult.Found(near)
    }

    override suspend fun plan(request: PlanRequest): PlanResult {
        val now = clock.nowMillis()
        when (simulate) {
            Simulate.OFFLINE -> return PlanResult.Offline
            Simulate.UNAVAILABLE -> return PlanResult.Unavailable
            Simulate.FAILED -> return PlanResult.Failed(PlannerMessages.TOO_MANY)
            Simulate.NOTHING_FOUND -> return PlanResult.Found(response(now, emptyList()))
            Simulate.NONE -> Unit
        }
        if (distanceMeters(request.from.position, request.to.position) <= 50.0) {
            return PlanResult.Failed(PlannerMessages.BAD_JOURNEY)
        }
        return PlanResult.Found(response(now, itineraries(request, now)))
    }

    /** The itineraries [plan] answers [request] with, best first (exposed for previews). */
    fun itineraries(request: PlanRequest, nowMs: Long = clock.nowMillis()): List<Itinerary> {
        val time = request.timeMs ?: nowMs
        val ends = Ends(
            fromName = PLACES.firstOrNull { it.id == request.from.placeId }?.name ?: "Start",
            from = request.from.position,
            toName = PLACES.firstOrNull { it.id == request.to.placeId }?.name ?: "Destination",
            to = request.to.position,
        )
        val overlay = Overlay(timetable, FakeData.liveSnapshot(timetable, nowMs), nowMs)
        val builders = buildList<(Long) -> Itinerary?> {
            add { trainToTrain(ends, overlay, it) }
            add { trainToBus(ends, overlay, it) }
            add { cancelledBus(ends, it) }
            if (distanceMeters(ends.from, ends.to) <= WALK_ONLY_MAX_M) add { walkOnly(ends, it) }
        }
        val found = builders.mapNotNull { build ->
            when (request.timeKind) {
                PlanTimeKind.DEPART_AT -> build(time)
                PlanTimeKind.ARRIVE_BY -> arrivingBy(time, build)
            }
        }.filter { itinerary -> itinerary.legs.none { leg -> leg.mode?.let { it in request.excludedModes } == true } }
        val sorted = when (request.timeKind) {
            PlanTimeKind.DEPART_AT -> found.sortedWith(compareBy<Itinerary> { it.arriveMs }.thenBy { it.changes })
            PlanTimeKind.ARRIVE_BY -> found.sortedWith(compareByDescending<Itinerary> { it.departMs }.thenBy { it.changes })
        }
        return sorted.take(request.maxItineraries.coerceIn(1, RemoteJourneyPlanner.MAX_ITINERARIES))
    }

    private fun response(now: Long, itineraries: List<Itinerary>) = PlanResponse(
        serverTimeMs = now,
        itineraries = itineraries,
        timetableVersion = timetable.version,
        liveModes = listOf(Mode.TRAIN, Mode.METRO),
    )

    private data class Ends(val fromName: String, val from: LatLng, val toName: String, val to: LatLng)

    /** The latest-leaving itinerary from [build] that arrives (as timetabled) by [arriveBy]. */
    private fun arrivingBy(arriveBy: Long, build: (Long) -> Itinerary?): Itinerary? {
        for (step in 0..ARRIVE_BY_STEPS) {
            val itinerary = build(arriveBy - step * 5 * MIN_MS) ?: continue
            if (itinerary.legs.last().to.scheduledMs <= arriveBy) return itinerary
        }
        return null
    }

    // ---- The canned itineraries ----

    private fun trainToTrain(ends: Ends, overlay: Overlay, startMs: Long): Itinerary? {
        val toParramatta = startMs + 6 * MIN_MS
        val t1 = next(FakeData.platform(FakeData.PARRAMATTA, 2), FakeData.platform(FakeData.STRATHFIELD, 2), FakeData.T1, toParramatta) ?: return null
        val t9 = next(FakeData.platform(FakeData.STRATHFIELD, 3), FakeData.platform(FakeData.CHATSWOOD, 3), FakeData.T9, t1.arrivalMillis + 4 * MIN_MS) ?: return null
        val first = ride(t1, overlay)
        val second = ride(t9, overlay)
        return itinerary(
            listOf(
                walk(endPoint(ends.fromName, ends.from, t1.departureMillis - 6 * MIN_MS), first.from.copy(scheduledMs = t1.departureMillis), 6 * 60, 450),
                first,
                walk(first.to, second.from.copy(scheduledMs = t1.arrivalMillis + 3 * MIN_MS), 3 * 60, 120),
                second,
                walk(second.to, endPoint(ends.toName, ends.to, t9.arrivalMillis + 7 * MIN_MS), 7 * 60, 520),
            ),
        )
    }

    private fun trainToBus(ends: Ends, overlay: Overlay, startMs: Long): Itinerary? {
        val t1 = next(FakeData.platform(FakeData.PARRAMATTA, 2), FakeData.platform(FakeData.CENTRAL, 2), FakeData.T1, startMs + 6 * MIN_MS) ?: return null
        val train = ride(t1, overlay)
        val atStand = t1.arrivalMillis + 4 * MIN_MS
        val busDeparts = atStand + (10 * MIN_MS - atStand.mod(10 * MIN_MS)) // every 10 minutes
        val stand = LegPoint("Central", EDDY_AV, platform = "Stand D", scheduledMs = busDeparts)
        val bus = ItineraryLeg(
            kind = LegKind.RIDE,
            from = stand,
            to = LegPoint("Coogee Beach", COOGEE, scheduledMs = busDeparts + 24 * MIN_MS),
            durationS = 24 * 60,
            distanceM = 7_400,
            path = encodePolyline(listOf(EDDY_AV, COOGEE)),
            mode = Mode.BUS,
            routeName = "373",
            headsign = "Coogee",
            stopCount = 14,
        )
        return itinerary(
            listOf(
                walk(endPoint(ends.fromName, ends.from, t1.departureMillis - 6 * MIN_MS), train.from, 6 * 60, 450),
                train,
                walk(train.to, stand.copy(scheduledMs = atStand), 4 * 60, 280),
                bus,
                walk(bus.to, endPoint(ends.toName, ends.to, bus.to.scheduledMs + 5 * MIN_MS), 5 * 60, 400),
            ),
        )
    }

    private fun cancelledBus(ends: Ends, startMs: Long): Itinerary {
        val atStand = startMs + 4 * MIN_MS
        // Leaves at :07 and :37.
        val offset = (atStand - 7 * MIN_MS).mod(30 * MIN_MS)
        val departs = if (offset == 0L) atStand else atStand + 30 * MIN_MS - offset
        val stand = LegPoint("Parramatta", PARRAMATTA_STAND_B, platform = "Stand B", scheduledMs = departs)
        val bus = ItineraryLeg(
            kind = LegKind.RIDE,
            from = stand,
            to = LegPoint("Chatswood", CHATSWOOD_INTERCHANGE, platform = "Stand E", scheduledMs = departs + 48 * MIN_MS),
            durationS = 48 * 60,
            distanceM = 21_000,
            path = encodePolyline(listOf(PARRAMATTA_STAND_B, CHATSWOOD_INTERCHANGE)),
            mode = Mode.BUS,
            routeName = "545",
            headsign = "Chatswood",
            stopCount = 31,
            realtime = true,
            cancelled = true,
        )
        return itinerary(
            listOf(
                walk(endPoint(ends.fromName, ends.from, departs - 4 * MIN_MS), stand, 4 * 60, 300),
                bus,
                walk(bus.to, endPoint(ends.toName, ends.to, bus.to.scheduledMs + 6 * MIN_MS), 6 * 60, 450),
            ),
        )
    }

    private fun walkOnly(ends: Ends, startMs: Long): Itinerary {
        val meters = (distanceMeters(ends.from, ends.to) * 1.25).roundToInt()
        val seconds = (meters / WALK_SPEED_MPS).roundToInt().coerceAtLeast(60)
        return itinerary(
            listOf(
                walk(endPoint(ends.fromName, ends.from, startMs), endPoint(ends.toName, ends.to, startMs + seconds * 1000L), seconds, meters),
            ),
        )
    }

    // ---- Building blocks ----

    private fun next(origin: StopId, destination: StopId, routeId: String, afterMs: Long): ScheduledDeparture? =
        timetable.departuresAfter(setOf(origin), setOf(destination), afterMs, limit = 1, routeIds = setOf(routeId)).firstOrNull()

    private fun endPoint(name: String, position: LatLng, atMs: Long) = LegPoint(name, position, scheduledMs = atMs)

    private fun walk(from: LegPoint, to: LegPoint, seconds: Int, meters: Int) = ItineraryLeg(
        kind = LegKind.WALK,
        from = from.copy(expectedMs = null),
        to = to.copy(scheduledMs = from.scheduledMs + seconds * 1000L, expectedMs = null),
        durationS = seconds,
        distanceM = meters,
        path = encodePolyline(listOf(from.position, to.position)),
    )

    /** A train ride on a real sample trip, with the sample live data applied. */
    private fun ride(s: ScheduledDeparture, overlay: Overlay): ItineraryLeg {
        val live = overlay.departure(s)
        val route = timetable.route(s.routeId)
        val trip = timetable.trip(s.tripId)
        val cancelled = live.status == DepartureStatus.CANCELLED
        val hasDelay = live.delaySeconds != null && !cancelled
        val calls = trip?.stopTimes.orEmpty().filter { it.stopSequence in s.originStopSequence..s.destinationStopSequence }
        val path = calls.mapNotNull { timetable.stop(it.stopId)?.position }
        return ItineraryLeg(
            kind = LegKind.RIDE,
            from = point(s.originStopId, s.departureMillis, live.expectedDepartureMs.takeIf { hasDelay }),
            to = point(s.destinationStopId, s.arrivalMillis, live.expectedArrivalMs.takeIf { hasDelay }),
            durationS = ((s.arrivalMillis - s.departureMillis) / 1000).toInt(),
            distanceM = path.zipWithNext { a, b -> distanceMeters(a, b) }.sum().roundToInt(),
            path = encodePolyline(path),
            mode = route?.mode ?: Mode.TRAIN,
            routeName = route?.shortName ?: s.routeId,
            headsign = s.headsign,
            routeColor = route?.color,
            stopCount = s.destinationStopSequence - s.originStopSequence,
            realtime = hasDelay || cancelled,
            cancelled = cancelled,
            occupancy = if (hasDelay) live.occupancy else Occupancy.UNKNOWN,
            routeId = s.routeId,
            tripId = s.tripId,
            serviceDate = s.serviceDate,
        )
    }

    private fun point(stopId: StopId, scheduledMs: Long, expectedMs: Long?): LegPoint {
        val stop = timetable.stop(stopId)
        val station = stop?.parentId?.let { timetable.stop(it) } ?: stop
        return LegPoint(
            name = (station?.name ?: stopId).removeSuffix(" Station"),
            position = stop?.position ?: LatLng(0.0, 0.0),
            stopId = stopId,
            platform = stop?.platformCode,
            scheduledMs = scheduledMs,
            expectedMs = expectedMs,
        )
    }

    private fun itinerary(legs: List<ItineraryLeg>): Itinerary {
        val rides = legs.filter { it.kind == LegKind.RIDE }
        val key = if (rides.isEmpty()) {
            "walk|${legs.first().from.position}|${legs.last().to.position}|${legs.first().from.scheduledMs / MIN_MS}"
        } else {
            rides.joinToString(";") { "${it.routeName}|${it.from.stopId ?: it.from.name}|${it.from.scheduledMs}" }
        }
        return Itinerary(id = shortHash(key), legs = legs)
    }

    companion object {
        /** Ends at most this far apart also get a walk-only itinerary. */
        const val WALK_ONLY_MAX_M = 3_000.0

        private const val WALK_SPEED_MPS = 1.3
        private const val MIN_MS = 60_000L
        private const val ARRIVE_BY_STEPS = 48

        private val EDDY_AV = LatLng(-33.8830, 151.2088)
        private val COOGEE = LatLng(-33.9205, 151.2577)
        private val PARRAMATTA_STAND_B = LatLng(-33.8176, 151.0040)
        private val CHATSWOOD_INTERCHANGE = LatLng(-33.7965, 151.1810)

        private val SUBURBS = mapOf(
            FakeData.CENTRAL to "Haymarket",
            FakeData.TOWN_HALL to "Sydney",
            FakeData.STRATHFIELD to "Strathfield",
            FakeData.PARRAMATTA to "Parramatta",
            FakeData.CHATSWOOD to "Chatswood",
            FakeData.TALLAWONG to "Rouse Hill",
        )

        /** The places the fake knows: sample stations first, then a stand, addresses, points of interest and localities. */
        val PLACES: List<Place> = FakeData.stops.filter { it.parentId == null }.map { stop ->
            Place(
                id = "fake-stop-${stop.id}",
                kind = PlaceKind.STOP,
                name = stop.name,
                detail = SUBURBS[stop.id].orEmpty(),
                position = stop.position,
                stopId = stop.id,
                modes = stop.modes.sortedBy { it.ordinal },
            )
        } + listOf(
            Place("fake-stand-eddy-av-d", PlaceKind.STOP, "Central Station, Eddy Av, Stand D", "Haymarket", EDDY_AV, modes = listOf(Mode.BUS)),
            Place("fake-address-martin-pl", PlaceKind.ADDRESS, "1 Martin Pl", "Sydney", LatLng(-33.8678, 151.2101)),
            Place("fake-address-george-st", PlaceKind.ADDRESS, "100 George St", "Parramatta", LatLng(-33.8146, 151.0084)),
            Place("fake-address-albert-rd", PlaceKind.ADDRESS, "10 Albert Rd", "Strathfield", LatLng(-33.8731, 151.0935)),
            Place("fake-poi-qvb", PlaceKind.POI, "Queen Victoria Building", "Sydney", LatLng(-33.8718, 151.2067)),
            Place("fake-poi-bondi", PlaceKind.POI, "Bondi Beach", "Bondi Beach", LatLng(-33.8908, 151.2743)),
            Place("fake-poi-coogee", PlaceKind.POI, "Coogee Beach", "Coogee", COOGEE),
            Place("fake-poi-taronga", PlaceKind.POI, "Taronga Zoo", "Mosman", LatLng(-33.8430, 151.2412)),
            Place("fake-locality-parramatta", PlaceKind.LOCALITY, "Parramatta", "NSW 2150", LatLng(-33.8150, 151.0011)),
            Place("fake-locality-chatswood", PlaceKind.LOCALITY, "Chatswood", "NSW 2067", LatLng(-33.7969, 151.1803)),
        )

        /** 8 hex digits, stable across platforms. */
        internal fun shortHash(s: String): String {
            var h = 0x811c9dc5.toInt()
            for (c in s) {
                h = h xor c.code
                h *= 0x01000193
            }
            return h.toUInt().toString(16).padStart(8, '0')
        }
    }
}
