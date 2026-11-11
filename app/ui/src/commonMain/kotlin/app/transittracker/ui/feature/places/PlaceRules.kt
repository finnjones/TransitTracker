package app.transittracker.ui.feature.places

import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.Place
import app.transittracker.model.PlaceKind
import app.transittracker.model.Stop
import app.transittracker.model.StopId
import app.transittracker.ui.format.shortStopName
import app.transittracker.ui.places.PlaceStop
import app.transittracker.ui.places.TripPlaces
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The pure rules of saved places (plan 5.7): which stops count as walking distance, how long the
 * walk is, how a search answer is ordered, and when a place can be saved. No coroutines, no UI.
 */

/** Walking speed behind the estimate: 80 m a minute (4.8 km/h), which gives the Add place mockup's figures. */
const val WALK_METERS_PER_MINUTE = 80

/** Rail stations (train and metro) are offered within this distance (plan 5.7)... */
const val STATION_RADIUS_M = 1500.0

/** ...nearest three at most... */
const val STATION_LIMIT = 3

/**
 * ...plus light rail stops and ferry wharves within this distance, nearest [LIGHT_RAIL_FERRY_LIMIT]
 * (a change to plan 5.7, which put them with buses at 500 m: people walk further to a tram or a
 * ferry than to a bus, e.g. Randwick Light Rail is 529 m from Coogee St, Randwick)...
 */
const val LIGHT_RAIL_FERRY_RADIUS_M = 1000.0

const val LIGHT_RAIL_FERRY_LIMIT = 2

/** ...plus bus and coach stops within this distance (plan 5.7). */
const val LOCAL_STOP_RADIUS_M = 500.0

/** The plan gives no number for local stops; a busy street has dozens, so only the nearest few are offered. */
const val LOCAL_STOP_LIMIT = 5

const val MIN_WALK_MINUTES = 1
const val MAX_WALK_MINUTES = 60

/** Fewest characters typed before the address lookup is asked. */
const val MIN_QUERY_LENGTH = 3

private const val EARTH_RADIUS_M = 6_371_000.0

/** Straight-line distance between two positions in metres (haversine). */
fun distanceMeters(a: LatLng, b: LatLng): Double {
    val lat1 = a.lat * PI / 180
    val lat2 = b.lat * PI / 180
    val dLat = lat2 - lat1
    val dLon = (b.lon - a.lon) * PI / 180
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}

/** Estimated walk from a straight-line [distanceMeters]: rounded to the nearest minute, never under one. */
fun estimateWalkMinutes(distanceMeters: Int): Int =
    (distanceMeters.toDouble() / WALK_METERS_PER_MINUTE).roundToInt().coerceIn(MIN_WALK_MINUTES, MAX_WALK_MINUTES)

/** "250 M" under a kilometre, "1.1 KM" above. */
fun formatDistance(meters: Int): String {
    if (meters < 1000) return "$meters M"
    val tenths = (meters + 50) / 100
    return "${tenths / 10}.${tenths % 10} KM"
}

/** "3 MIN". Whether it is an estimate is said beside it, never with a symbol: a "~" in the pixel faces reads as a minus. */
fun formatWalk(minutes: Int): String = "$minutes MIN"

/** What a screen reader says for a walk: "about 3 minutes' walk". */
fun spokenWalk(minutes: Int, estimated: Boolean): String =
    (if (estimated) "about " else "") + (if (minutes == 1) "1 minute's walk" else "$minutes minutes' walk")

/** A rail station: trains or metro call there. Everything else (bus, light rail, ferry, coach) is a local stop. */
fun isRailStation(stop: Stop): Boolean = Mode.TRAIN in stop.modes || Mode.METRO in stop.modes

/** Which walking-distance rule a stop falls under: the most far-reaching mode that calls there. */
enum class StopTier { RAIL, LIGHT_RAIL_FERRY, LOCAL }

fun stopTier(stop: Stop): StopTier = when {
    isRailStation(stop) -> StopTier.RAIL
    Mode.LIGHT_RAIL in stop.modes || Mode.FERRY in stop.modes -> StopTier.LIGHT_RAIL_FERRY
    else -> StopTier.LOCAL
}

/**
 * The stops to offer for a place at [position], from [candidates] (stops near it, platforms or
 * stands included; the phone's timetable and the planner's stops-near lookup together, so the same
 * station may come twice): platforms are folded into their station, then the rule of plan 5.7
 * applies. Rail stations within [STATION_RADIUS_M], nearest [STATION_LIMIT]; light rail and ferry
 * within [LIGHT_RAIL_FERRY_RADIUS_M], nearest [LIGHT_RAIL_FERRY_LIMIT]; bus and coach stops within
 * [LOCAL_STOP_RADIUS_M], nearest [LOCAL_STOP_LIMIT]. Nearest first, walk times estimated.
 */
fun walkingStops(position: LatLng, candidates: List<Stop>): List<PlaceStop> {
    class Group(val id: StopId, var name: String, var distance: Double, var tier: StopTier, val modes: MutableSet<Mode> = mutableSetOf())

    val groups = LinkedHashMap<StopId, Group>()
    for (s in candidates) {
        val key = s.parentId ?: s.id
        val d = distanceMeters(position, s.position)
        val tier = stopTier(s)
        val g = groups.getOrPut(key) { Group(key, shortStopName(s.name), d, tier) }
        if (s.parentId == null) g.name = shortStopName(s.name) // the station's own name beats a platform's
        g.distance = min(g.distance, d)
        if (tier.ordinal < g.tier.ordinal) g.tier = tier
        g.modes += s.modes
    }
    val all = groups.values.sortedBy { it.distance }
    fun pick(tier: StopTier, radius: Double, limit: Int) = all.filter { it.tier == tier && it.distance <= radius }.take(limit)
    val chosen = pick(StopTier.RAIL, STATION_RADIUS_M, STATION_LIMIT) +
        pick(StopTier.LIGHT_RAIL_FERRY, LIGHT_RAIL_FERRY_RADIUS_M, LIGHT_RAIL_FERRY_LIMIT) +
        pick(StopTier.LOCAL, LOCAL_STOP_RADIUS_M, LOCAL_STOP_LIMIT)
    return chosen.sortedBy { it.distance }.map { g ->
        val meters = g.distance.roundToInt()
        PlaceStop(g.id, g.name, estimateWalkMinutes(meters), meters, enabled = true, estimated = true, modes = g.modes.sortedBy { it.ordinal })
    }
}

/**
 * [found] stops for a moved or re-searched place, keeping what the user already decided about
 * stops that are still offered: switched off stays off, a corrected walk time stays corrected.
 */
fun keepChoices(found: List<PlaceStop>, previous: List<PlaceStop>): List<PlaceStop> {
    val before = previous.associateBy { it.stopId }
    return found.map { s ->
        val p = before[s.stopId] ?: return@map s
        s.copy(
            enabled = p.enabled,
            walkMinutes = if (p.estimated) s.walkMinutes else p.walkMinutes,
            estimated = s.estimated && p.estimated,
        )
    }
}

/** [minutes] moved by [delta], or null at the ends of [MIN_WALK_MINUTES]..[MAX_WALK_MINUTES]. */
fun steppedWalk(minutes: Int, delta: Int): Int? = (minutes + delta).takeIf { it in MIN_WALK_MINUTES..MAX_WALK_MINUTES }

/** Search answers with addresses first, then points of interest, suburbs, stops and the rest; the planner's order within a kind. */
fun rankPlaces(places: List<Place>): List<Place> = places.sortedBy { kindRank(it.kind) }

private fun kindRank(kind: PlaceKind): Int = when (kind) {
    PlaceKind.ADDRESS -> 0
    PlaceKind.POI -> 1
    PlaceKind.LOCALITY -> 2
    PlaceKind.STOP -> 3
    PlaceKind.OTHER -> 4
}

/** The text a picked search answer puts in the address box: "Albert Rd, Strathfield". */
fun addressText(place: Place): String = if (place.detail.isBlank()) place.name else "${place.name}, ${place.detail}"

/**
 * Why a place cannot be saved yet, as a plain sentence; null when it can (plan 6: say why a
 * button is off). The first missing thing is named.
 */
fun placeSaveProblem(name: String, hasPosition: Boolean, hasStops: Boolean, enabledStops: Int): String? = when {
    name.isBlank() -> "Give the place a name."
    !hasPosition -> "Pick an address from the list, or use where you are now."
    !hasStops -> NO_STOPS_NEAR
    enabledStops == 0 -> "Switch on at least one stop you would walk to."
    else -> null
}

/** "BUS", "LIGHT RAIL · BUS": what calls at a stop, for a stop shown without line badges; null if nothing is known. */
fun modesLabel(modes: List<Mode>): String? = modes.distinct().sortedBy { it.ordinal }.mapNotNull { m ->
    when (m) {
        Mode.TRAIN -> "TRAIN"
        Mode.METRO -> "METRO"
        Mode.LIGHT_RAIL -> "LIGHT RAIL"
        Mode.BUS -> "BUS"
        Mode.FERRY -> "FERRY"
        Mode.COACH -> "COACH"
        Mode.OTHER -> null
    }
}.takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** Said when nothing is within walking distance of a place. */
const val NO_STOPS_NEAR = "No stations or stops are within walking distance of here. Try another address."

/** How many saved routes use [placeId]: the trips linked to it at either end. */
fun routesUsingPlace(placeId: String, links: Collection<TripPlaces>): Int =
    links.count { it.fromPlaceId == placeId || it.toPlaceId == placeId }

/** "USED BY 2 ROUTES", "USED BY 1 ROUTE", "NOT USED YET". */
fun usedByWords(count: Int): String = when (count) {
    0 -> "NOT USED YET"
    1 -> "USED BY 1 ROUTE"
    else -> "USED BY $count ROUTES"
}

/** Moves [id] by [delta] places within [ids] (clamped); the list is unchanged if [id] is absent. */
fun movedIds(ids: List<String>, id: String, delta: Int): List<String> {
    val i = ids.indexOf(id)
    if (i < 0) return ids
    val j = max(0, min(ids.lastIndex, i + delta))
    if (i == j) return ids
    return ids.toMutableList().also { it.add(j, it.removeAt(i)) }
}
