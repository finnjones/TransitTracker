package app.transittracker.server.planner

import app.transittracker.model.Itinerary
import app.transittracker.model.ItineraryLeg
import app.transittracker.model.LatLng
import app.transittracker.model.LegKind
import app.transittracker.model.LegPoint
import app.transittracker.model.Mode
import app.transittracker.model.Occupancy
import app.transittracker.model.PlanTimeKind
import app.transittracker.model.StopId
import app.transittracker.model.Timetable
import app.transittracker.model.arriveMs
import app.transittracker.model.changes
import app.transittracker.model.departMs
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max

/** How a Trip Planner leg is turned into ours. */
internal sealed interface LegClass {
    data class Ride(val mode: Mode) : LegClass
    data object Walk : LegClass
    /** Cycling, taxi, car, on-demand, or anything unknown: the journey is dropped. */
    data object Unsupported : LegClass
}

/** Product classes from the Swagger's `RouteProduct.class` / `excludedMeans` [S] and KRAIL [K]. */
internal object ProductClasses {
    const val TRAIN = 1
    const val METRO = 2
    const val LIGHT_RAIL = 4
    const val BUS = 5
    const val COACH = 7
    const val FERRY = 9
    const val SCHOOL_BUS = 11
    const val WALK = 99
    const val FOOTPATH = 100

    /** `iconId` 23 = "On Demand": product class 5 in KRAIL's capture [K], class 10 on the live API (dropped either way). */
    const val ON_DEMAND_ICON = 23

    fun classify(transportation: TransportationDto?, footPath: List<FootPathInfoDto>?): LegClass {
        val product = transportation?.product
        val cls = product?.productClass
            ?: return if (!footPath.isNullOrEmpty()) LegClass.Walk else LegClass.Unsupported
        val icon = transportation.iconId ?: product.iconId
        return when (cls) {
            WALK, FOOTPATH -> LegClass.Walk
            TRAIN -> LegClass.Ride(Mode.TRAIN)
            METRO -> LegClass.Ride(Mode.METRO)
            LIGHT_RAIL -> LegClass.Ride(Mode.LIGHT_RAIL)
            BUS -> if (icon == ON_DEMAND_ICON) LegClass.Unsupported else LegClass.Ride(Mode.BUS)
            SCHOOL_BUS -> LegClass.Ride(Mode.BUS)
            COACH -> LegClass.Ride(Mode.COACH)
            FERRY -> LegClass.Ride(Mode.FERRY)
            else -> LegClass.Unsupported // 101 bicycle ... 105 taxi, 106 car, and anything new
        }
    }

    /** Product classes to exclude (`exclMOT_<class>=1` with `excludedMeans=checkbox` [S]) for a mode. */
    fun excludedFor(mode: Mode): List<Int> = when (mode) {
        Mode.TRAIN -> listOf(TRAIN)
        Mode.METRO -> listOf(METRO)
        Mode.LIGHT_RAIL -> listOf(LIGHT_RAIL)
        Mode.BUS -> listOf(BUS, SCHOOL_BUS)
        Mode.COACH -> listOf(COACH)
        Mode.FERRY -> listOf(FERRY)
        Mode.OTHER -> emptyList()
    }

    fun mode(cls: Int): Mode? = when (cls) {
        TRAIN -> Mode.TRAIN
        METRO -> Mode.METRO
        LIGHT_RAIL -> Mode.LIGHT_RAIL
        BUS, SCHOOL_BUS -> Mode.BUS
        COACH -> Mode.COACH
        FERRY -> Mode.FERRY
        else -> null
    }
}

/** Display names and platforms from planner location names. */
internal object Names {
    private val PLATFORM = Regex("""^(Platform|Stand|Wharf|Bay|Side|Track|Gate)\s+[A-Za-z0-9]{1,6}$""", RegexOption.IGNORE_CASE)
    private val STATION_SUFFIX = Regex("""\s+Station$""", RegexOption.IGNORE_CASE)

    /**
     * A leg stop's display name: "Central Station, Platform 16" -> "Central"; a bus stand keeps its
     * street ("Macquarie University Station, Herring Rd, Stand D" -> "Macquarie University Station,
     * Herring Rd"), a wharf its name ("Davistown, Central RSL Wharf"). " Station" goes only from a
     * name that is just the station.
     */
    fun stopName(stop: LegStopDto): String? {
        val parts = nameParts(stop.disassembledName) ?: nameParts(stop.name)?.let { withoutLocality(it, stop) } ?: return null
        val name = parts.joinToString(", ")
        return (if (parts.size == 1) STATION_SUFFIX.replace(name, "").trim() else name).ifEmpty { null }
    }

    /**
     * Every part of a comma-separated name that is not a platform: "Central Station, Platform 16" ->
     * "Central Station"; "Davistown, Central RSL Wharf" stays whole (on the live API the first part
     * alone is sometimes just the suburb).
     */
    fun withoutPlatform(raw: String?): String? = nameParts(raw)?.joinToString(", ")

    private fun nameParts(raw: String?): List<String>? =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && !PLATFORM.matches(it) }?.takeIf { it.isNotEmpty() }

    /** The long `name` ends with the suburb ("Strathfield Station, Platform 3, Strathfield"): drop it. */
    private fun withoutLocality(parts: List<String>, stop: LegStopDto): List<String> {
        val locality = generateSequence(stop.parent) { it.parent }.firstOrNull { it.type == "locality" }?.name?.trim()
        return if (parts.size > 1 && parts.last().equals(locality, ignoreCase = true)) parts.dropLast(1) else parts
    }

    /**
     * "Platform 2" -> "2"; "Stand A", "Wharf 3" and "Wharf 3, Side A" (ferries' `platformName`) as
     * they are. Null when the planner names none: for ordinary bus stops `platformName` is the stop's
     * name, which is ignored.
     */
    fun platform(stop: LegStopDto): String? {
        val named = stop.properties.string("platformName")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val found = if (!named.isNullOrEmpty() && named.all { PLATFORM.matches(it) }) {
            named.joinToString(", ")
        } else {
            (named.orEmpty() + listOfNotNull(stop.disassembledName, stop.name).flatMap { it.split(',') })
                .map { it.trim() }.firstOrNull { PLATFORM.matches(it) } ?: return null
        }
        return if (found.startsWith("Platform ", ignoreCase = true)) found.substring(9).trim() else found
    }

    /** A place's own name: the short name, else the first part of the long one. */
    fun placeName(disassembled: String?, name: String?): String? =
        disassembled?.trim()?.takeIf { it.isNotEmpty() } ?: name?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

/** The ends of the journey as the phone sent them, for legs whose ends the planner leaves out. */
internal data class JourneyEnds(val from: LatLng, val to: LatLng)

/** Result of mapping one Trip Planner answer. */
internal data class MappedPlan(
    val itineraries: List<Itinerary>,
    val matchedRides: Int,
    val unmatchedRides: Int,
    /** Journeys dropped because they need a mode the app does not have, or could not be read. */
    val droppedJourneys: Int,
)

/**
 * Turns a `/v1/tp/trip` answer into [Itinerary]s (contract: `Planning.kt`):
 *
 * - walking legs (product class 99/100) become WALK, public transport legs RIDE; journeys needing
 *   any other mode (cycling, taxi, car, on-demand) are dropped;
 * - a walk TfNSW describes as `footPathInfo` (BEFORE/AFTER) or `interchange` on a ride becomes its
 *   own WALK leg, so every change has an explicit walk with its minutes; consecutive walks are merged
 *   and zero-length walks dropped;
 * - names lose " Station" and the platform; `stopId` is set only for ids in the loaded timetable;
 * - rides are matched to timetable trips by [RideMatcher] (never guessed).
 */
internal class TripMapper(
    private val timetable: Timetable?,
    private val matcher: RideMatcher? = timetable?.let { RideMatcher(it) },
) {
    fun map(response: TripResponseDto, ends: JourneyEnds, kind: PlanTimeKind): MappedPlan {
        var matched = 0
        var unmatched = 0
        var dropped = 0
        val itineraries = ArrayList<Itinerary>()
        for (journey in response.journeys.orEmpty()) {
            val drafts = try {
                drafts(journey, ends)
            } catch (e: IllegalArgumentException) {
                null
            }
            if (drafts == null) {
                dropped++
                continue
            }
            val legs = drafts.map { draft ->
                if (draft.ride == null) return@map draft.leg
                val (leg, ok) = matchRide(draft.leg, draft.ride)
                if (ok) matched++ else unmatched++
                leg
            }
            itineraries += Itinerary(id = itineraryId(legs, drafts), legs = legs)
        }
        val ordered = when (kind) {
            PlanTimeKind.DEPART_AT -> itineraries.sortedWith(compareBy<Itinerary>({ it.arriveMs }, { it.changes }, { -it.departMs }))
            PlanTimeKind.ARRIVE_BY -> itineraries.sortedWith(compareBy<Itinerary>({ -it.departMs }, { it.changes }, { it.arriveMs }))
        }
        return MappedPlan(ordered.distinctBy { it.id }, matched, unmatched, dropped)
    }

    // ---- legs ----

    /** A leg being built, with what matching needs about its ride. */
    private class Draft(val leg: ItineraryLeg, val ride: RideFacts? = null, val coords: List<LatLng> = emptyList(), val plannerKey: String = "")

    /** Null when the journey must be dropped. */
    private fun drafts(journey: JourneyDto, ends: JourneyEnds): List<Draft>? {
        val raw = journey.legs
        if (raw.isEmpty()) return null
        val classes = raw.map { ProductClasses.classify(it.transportation, it.footPathInfo) }
        if (classes.any { it == LegClass.Unsupported }) return null

        val out = ArrayList<Draft>()
        for ((i, leg) in raw.withIndex()) {
            when (val cls = classes[i]) {
                LegClass.Walk -> out += walkLeg(leg, ends, first = i == 0, last = i == raw.lastIndex)
                is LegClass.Ride -> {
                    val ride = rideLeg(leg, cls.mode)
                    // `footPathInfoRedundant` is NOT read: on the live API it is true on every Sydney Trains
                    // leg with an AFTER walk to the next ride, and no separate walk leg exists then. A walk
                    // that is its own leg is detected by adjacency instead, so it is never added twice.
                    val before = leg.footPathInfo.orEmpty().filter { it.position.equals("BEFORE", true) }.sumOf { it.duration ?: 0 }
                    // A walk already in front of this ride (its own leg, or the previous ride's AFTER walk) wins.
                    val prevIsWalk = out.isNotEmpty() && out.last().ride == null
                    if (before > 0 && !prevIsWalk) {
                        val from = out.lastOrNull()?.leg?.to ?: endPoint(ends.from, "Start", 0)
                        out += embeddedWalk(from = from, to = ride.leg.from, durationS = before.toInt(), endsAtTo = true)
                    }
                    out += ride
                    val nextIsWalk = i < raw.lastIndex && classes[i + 1] == LegClass.Walk
                    if (!nextIsWalk) {
                        val after = leg.footPathInfo.orEmpty().filter { it.position.equals("AFTER", true) }.sumOf { it.duration ?: 0 }
                        val next = raw.getOrNull(i + 1)
                        val nextMode = (classes.getOrNull(i + 1) as? LegClass.Ride)?.mode
                        val to = next?.origin?.let { point(it, departure = true, fallback = ends.to, fallbackName = "Destination", resolveIds = true, mode = nextMode) }
                            ?: endPoint(ends.to, "Destination", 0)
                        val durationS = when {
                            after > 0 -> after.toInt()
                            next != null && leg.interchange?.type in setOf(ProductClasses.WALK, ProductClasses.FOOTPATH) ->
                                walkSeconds(leg.interchange?.coords.orEmpty().mapNotNull(::latLng).ifEmpty { listOf(ride.leg.to.position, to.position) })
                            else -> 0
                        }
                        if (durationS > 0) {
                            out += embeddedWalk(
                                from = ride.leg.to,
                                to = to,
                                durationS = durationS,
                                endsAtTo = false,
                                coords = leg.interchange?.coords.orEmpty().mapNotNull(::latLng),
                            )
                        }
                    }
                }
                LegClass.Unsupported -> return null
            }
        }
        val kept = out.filter { it.ride != null || !zeroLength(it.leg) }
        val merged = mergeWalks(kept)
        return merged.ifEmpty { null }
    }

    private fun zeroLength(leg: ItineraryLeg): Boolean = leg.durationS <= 0 || leg.distanceM == 0

    private fun walkLeg(leg: LegDto, ends: JourneyEnds, first: Boolean, last: Boolean): Draft {
        val origin = leg.origin
        val destination = leg.destination
        var from = origin?.let { point(it, departure = true, fallback = ends.from, fallbackName = "Start", resolveIds = true) }
            ?: endPoint(ends.from, "Start", 0)
        var to = destination?.let { point(it, departure = false, fallback = ends.to, fallbackName = "Destination", resolveIds = true) }
            ?: endPoint(ends.to, "Destination", 0)
        val footSeconds = leg.footPathInfo.orEmpty().sumOf { it.duration ?: 0 }
        val durationS = (leg.duration ?: footSeconds.takeIf { it > 0 } ?: ((to.scheduledMs - from.scheduledMs) / 1000))
            .coerceAtLeast(0).toInt()
        // Fill a missing time from the other end.
        if (from.scheduledMs == 0L && to.scheduledMs != 0L) from = from.copy(scheduledMs = to.scheduledMs - durationS * 1000L)
        if (to.scheduledMs == 0L && from.scheduledMs != 0L) to = to.copy(scheduledMs = from.scheduledMs + durationS * 1000L)
        if (from.scheduledMs == 0L || to.scheduledMs == 0L) throw IllegalArgumentException("walk without times")
        val coords = leg.coords.orEmpty().mapNotNull(::latLng)
        return Draft(
            leg = ItineraryLeg(
                kind = LegKind.WALK,
                from = from,
                to = to,
                durationS = durationS,
                distanceM = leg.distance?.toInt(),
                path = coords.takeIf { it.size >= 2 }?.let(PolylineCodec::encode),
            ),
            coords = coords,
        )
    }

    private fun embeddedWalk(from: LegPoint, to: LegPoint, durationS: Int, endsAtTo: Boolean, coords: List<LatLng> = emptyList()): Draft {
        val ms = durationS * 1000L
        val (start, end) = if (endsAtTo) {
            // A walk BEFORE a ride ends when the ride leaves.
            from.copy(scheduledMs = to.scheduledMs - ms, expectedMs = to.expectedMs?.minus(ms)) to to
        } else {
            // A walk AFTER a ride starts when the ride arrives.
            from to to.copy(scheduledMs = from.scheduledMs + ms, expectedMs = from.expectedMs?.plus(ms))
        }
        return Draft(
            leg = ItineraryLeg(
                kind = LegKind.WALK,
                from = start,
                to = end,
                durationS = durationS,
                path = coords.takeIf { it.size >= 2 }?.let(PolylineCodec::encode),
            ),
            coords = coords,
        )
    }

    private fun rideLeg(leg: LegDto, mode: Mode): Draft {
        val origin = leg.origin ?: throw IllegalArgumentException("ride without origin")
        val destination = leg.destination ?: throw IllegalArgumentException("ride without destination")
        val realtime = leg.isRealtimeControlled == true
        val from = point(origin, departure = true, fallback = null, fallbackName = "", resolveIds = true, realtime = realtime, mode = mode)
        val to = point(destination, departure = false, fallback = null, fallbackName = "", resolveIds = true, realtime = realtime, mode = mode)
        if (from.scheduledMs == 0L || to.scheduledMs == 0L) throw IllegalArgumentException("ride without times")
        val transportation = leg.transportation
        val routeName = transportation?.disassembledName ?: transportation?.number
        val coords = leg.coords.orEmpty().mapNotNull(::latLng)
        val status = leg.realtimeStatus.orEmpty()
        val cancelled = leg.isCancelled == true || status.any { it.contains("CANCEL", ignoreCase = true) }
        val departures = listOfNotNull(
            time(origin.departureTimePlanned),
            time(origin.departureTimeBaseTimetable),
        ).ifEmpty { listOf(from.scheduledMs) }.distinct()
        return Draft(
            leg = ItineraryLeg(
                kind = LegKind.RIDE,
                from = from,
                to = to,
                durationS = (leg.duration ?: ((to.scheduledMs - from.scheduledMs) / 1000)).coerceAtLeast(0).toInt(),
                distanceM = leg.distance?.toInt(),
                path = coords.takeIf { it.size >= 2 }?.let(PolylineCodec::encode),
                mode = mode,
                routeName = routeName,
                headsign = transportation?.destination?.name?.let { Names.placeName(null, it) },
                stopCount = stopCount(leg.stopSequence),
                realtime = realtime,
                cancelled = cancelled,
                occupancy = occupancy(origin.properties.string("occupancy")),
            ),
            ride = RideFacts(
                routeName = routeName,
                realtimeTripIds = listOfNotNull(
                    transportation?.properties.string("RealtimeTripId"),
                    transportation?.properties.string("AVMSTripID"),
                ).distinct(),
                boardingStopIds = knownIds(origin, mode),
                alightingStopIds = knownIds(destination, mode),
                departureMs = departures,
            ),
            coords = coords,
            plannerKey = origin.id ?: origin.name.orEmpty(),
        )
    }

    /**
     * A leg end. Planned time goes in `scheduledMs`; the estimate in `expectedMs` when the planner
     * used live data ([realtime]) or its estimate differs from the plan.
     */
    private fun point(
        stop: LegStopDto,
        departure: Boolean,
        fallback: LatLng?,
        fallbackName: String,
        resolveIds: Boolean,
        realtime: Boolean = false,
        mode: Mode? = null,
    ): LegPoint {
        val planned = time(if (departure) stop.departureTimePlanned else stop.arrivalTimePlanned)
            ?: time(if (departure) stop.departureTimeBaseTimetable else stop.arrivalTimeBaseTimetable)
            ?: time(if (departure) stop.arrivalTimePlanned else stop.departureTimePlanned)
        val estimated = time(if (departure) stop.departureTimeEstimated else stop.arrivalTimeEstimated)
        val isStop = stop.type in STOP_TYPES
        val stopId = if (resolveIds && isStop) knownIds(stop, mode).firstOrNull() else null
        val position = latLng(stop.coord) ?: fallback ?: throw IllegalArgumentException("point without position")
        val name = (if (isStop) Names.stopName(stop) else Names.placeName(stop.disassembledName, stop.name))
            ?: fallbackName.ifEmpty { throw IllegalArgumentException("point without name") }
        return LegPoint(
            name = name,
            position = position,
            stopId = stopId,
            platform = if (isStop) Names.platform(stop) ?: stopId?.let { timetable?.stop(it)?.platformCode } else null,
            scheduledMs = planned ?: estimated ?: 0L,
            expectedMs = estimated?.takeIf { planned == null || realtime || it != planned },
        )
    }

    private fun endPoint(position: LatLng, name: String, timeMs: Long) = LegPoint(name = name, position = position, scheduledMs = timeMs)

    /**
     * The planner's ids for a stop (platform, station, legacy id) that exist in the timetable.
     *
     * For a ride on [mode], only timetable stops that [mode] serves count: on the live API a bus
     * stand at a railway station has the station as its `parent` (Bondi Junction Stand D -> 202210),
     * which must not become the bus stop's id. For a walk's end ([mode] null) only the stop's own id.
     */
    private fun knownIds(stop: LegStopDto, mode: Mode?): List<StopId> {
        val tt = timetable ?: return emptyList()
        val candidates = if (mode == null) {
            listOfNotNull(stop.id)
        } else {
            listOfNotNull(
                stop.id,
                stop.parent?.id?.takeIf { stop.parent.type in STOP_TYPES },
                stop.parent?.properties.string("stopId"),
                stop.properties.string("stopId"),
            )
        }
        return candidates.distinct().filter { id ->
            val known = tt.stop(id) ?: return@filter false
            mode == null || known.modes.isEmpty() || mode in known.modes
        }
    }

    private fun mergeWalks(drafts: List<Draft>): List<Draft> {
        val out = ArrayList<Draft>()
        for (d in drafts) {
            val prev = out.lastOrNull()
            if (prev != null && prev.ride == null && d.ride == null) {
                val coords = prev.coords + d.coords
                out[out.lastIndex] = Draft(
                    leg = prev.leg.copy(
                        to = d.leg.to,
                        durationS = prev.leg.durationS + d.leg.durationS,
                        distanceM = prev.leg.distanceM?.let { a -> d.leg.distanceM?.let { b -> a + b } },
                        path = coords.takeIf { it.size >= 2 }?.let(PolylineCodec::encode),
                    ),
                    coords = coords,
                )
            } else {
                out += d
            }
        }
        return out
    }

    // ---- matching ----

    /** The leg with timetable ids filled in when matched; the flag says whether it was. */
    private fun matchRide(leg: ItineraryLeg, ride: RideFacts): Pair<ItineraryLeg, Boolean> {
        val match = matcher?.match(ride) ?: return leg to false
        val tt = timetable ?: return leg to false
        val route = tt.route(match.routeId)
        val boarding = leg.from.stopId?.takeIf { it in matcher.stationStops(listOf(match.boardingStopId)) } ?: match.boardingStopId
        val alighting = leg.to.stopId ?: match.alightingStopId
        return leg.copy(
            from = leg.from.copy(stopId = boarding, platform = leg.from.platform ?: tt.stop(boarding)?.platformCode),
            to = leg.to.copy(stopId = alighting, platform = leg.to.platform ?: alighting?.let { tt.stop(it)?.platformCode }),
            routeId = match.routeId,
            tripId = match.tripId,
            serviceDate = match.serviceDate,
            routeColor = route?.color?.takeIf { it.isNotBlank() },
        ) to true
    }

    // ---- ids ----

    /** A short hash of the rides (line, boarding stop, scheduled departure); walks only: of the walk. */
    private fun itineraryId(legs: List<ItineraryLeg>, drafts: List<Draft>): String {
        val key = drafts.zip(legs).filter { it.second.kind == LegKind.RIDE }.joinToString(";") { (draft, leg) ->
            "${leg.routeName}|${draft.plannerKey}|${leg.from.scheduledMs}"
        }.ifEmpty {
            val first = legs.first()
            val last = legs.last()
            "walk|${first.from.scheduledMs}|${round5(first.from.position)}|${round5(last.to.position)}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun round5(p: LatLng) = "%.5f,%.5f".format(p.lat, p.lon)

    companion object {
        private val STOP_TYPES = setOf("stop", "platform")

        /** Walking pace used only when TfNSW gives an interchange path but no duration. */
        private const val WALK_METRES_PER_SECOND = 1.25

        internal fun time(text: String?): Long? =
            text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

        internal fun latLng(coord: List<Double>?): LatLng? {
            if (coord == null || coord.size < 2) return null
            val (lat, lon) = coord
            if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return LatLng(lat, lon)
        }

        /**
         * Stops ridden: the stops after boarding that the service calls at. On the live API
         * `stopSequence` also lists stations an express runs through, without any time (a CCN train
         * Strathfield -> Epping lists 9 stations and calls at 2); those are not counted. Without any
         * times at all, every listed stop counts.
         */
        internal fun stopCount(sequence: List<LegStopDto>?): Int? {
            val after = sequence?.drop(1)?.takeIf { it.isNotEmpty() } ?: return null
            val timed = after.count { s ->
                listOf(s.arrivalTimePlanned, s.arrivalTimeEstimated, s.departureTimePlanned, s.departureTimeEstimated).any { it != null }
            }
            return (if (timed > 0) timed else after.size).takeIf { it > 0 }
        }

        private fun walkSeconds(path: List<LatLng>): Int {
            val metres = path.zipWithNext().sumOf { (a, b) -> Geo.distanceMetres(a, b) }
            return max(0, ceil(metres / WALK_METRES_PER_SECOND).toInt())
        }

        /** TfNSW occupancy words [K] ("MANY_SEATS", "FEW_SEATS", ...) as GTFS-R levels. */
        internal fun occupancy(text: String?): Occupancy = when (text?.uppercase()) {
            null -> Occupancy.UNKNOWN
            "EMPTY" -> Occupancy.EMPTY
            "MANY_SEATS", "MANY_SEATS_AVAILABLE" -> Occupancy.MANY_SEATS_AVAILABLE
            "FEW_SEATS", "FEW_SEATS_AVAILABLE" -> Occupancy.FEW_SEATS_AVAILABLE
            "STANDING_ONLY", "STANDING_ROOM_ONLY" -> Occupancy.STANDING_ROOM_ONLY
            "CRUSHED_STANDING_ROOM_ONLY" -> Occupancy.CRUSHED_STANDING_ROOM_ONLY
            "FULL" -> Occupancy.FULL
            "NOT_ACCEPTING_PASSENGERS" -> Occupancy.NOT_ACCEPTING_PASSENGERS
            else -> Occupancy.UNKNOWN
        }
    }
}
