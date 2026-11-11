package app.transittracker.ui.feature.places

import app.transittracker.model.Route
import app.transittracker.model.SavedTrip
import app.transittracker.model.TripLeg
import app.transittracker.ui.places.SavedPlace

/*
 * Pure rules of the saved routes screens (plan 5.2 folders, 5.6 saved routes, 5.7 places on trips).
 * A saved route is the contract's `SavedTrip`.
 */

/** A line badge's data: the name and the feed's colours (hex, no '#'). */
data class LineBadge(val name: String, val colorHex: String?, val textColorHex: String?)

/** Distinct line badges for [routes], in name order. */
fun lineBadges(routes: List<Route>): List<LineBadge> =
    routes.distinctBy { it.shortName }.sortedBy { it.shortName }.map { LineBadge(it.shortName, it.color, it.textColor) }

/** Up to [LINE_BADGES_SHOWN] badges are drawn; more are written as "7 LINES". */
const val LINE_BADGES_SHOWN = 4

/**
 * The lines a leg uses. If the leg is limited to some routes, those of them that serve the leg's
 * stations; otherwise the lines that serve both its board and its alight stations.
 */
fun legLines(leg: TripLeg, atBoard: List<Route>, atAlight: List<Route>): List<Route> {
    val limit = leg.routeIds
    return if (limit != null) {
        (atBoard + atAlight).filter { it.id in limit }.distinctBy { it.id }
    } else {
        val alight = atAlight.map { it.id }.toSet()
        atBoard.filter { it.id in alight }
    }
}

/** A route in the order shown: its id and folder (null = none). */
data class RouteEntry(val id: String, val folder: String?)

/** The folder name as stored: blank means none. */
fun cleanFolder(folder: String?): String? = folder?.trim()?.takeIf { it.isNotEmpty() }

/** Routes in their saved order as [RouteEntry]s. */
fun entriesOf(trips: List<SavedTrip>): List<RouteEntry> =
    trips.sortedBy { it.order }.map { RouteEntry(it.id, cleanFolder(it.folder)) }

/** Folders in the order their first route appears (as on Home); unfiled routes are the `null` folder. */
fun groupEntries(entries: List<RouteEntry>): List<Pair<String?, List<RouteEntry>>> {
    val groups = LinkedHashMap<String?, MutableList<RouteEntry>>()
    for (e in entries) groups.getOrPut(e.folder) { mutableListOf() } += e
    return groups.map { (k, v) -> k to v }
}

/**
 * Moves route [id] one step up ([delta] -1) or down (+1) in the grouped list. Within a folder it
 * swaps with its neighbour; at the end of a folder it crosses into the next folder (the end of the
 * one above, or the start of the one below) and takes its name. At the very top or bottom nothing
 * changes. The result is grouped: folders contiguous, in order.
 */
fun moveRoute(entries: List<RouteEntry>, id: String, delta: Int): List<RouteEntry> {
    val groups = LinkedHashMap<String?, MutableList<RouteEntry>>()
    for ((k, v) in groupEntries(entries)) groups[k] = v.toMutableList()
    val keys = groups.keys.toList()
    val gi = keys.indexOfFirst { k -> groups.getValue(k).any { it.id == id } }
    if (gi < 0) return entries
    val list = groups.getValue(keys[gi])
    val i = list.indexOfFirst { it.id == id }
    when {
        delta < 0 && i > 0 -> list.add(i - 1, list.removeAt(i))
        delta < 0 && gi > 0 -> {
            val e = list.removeAt(i)
            groups.getValue(keys[gi - 1]).add(e.copy(folder = keys[gi - 1]))
        }
        delta > 0 && i < list.lastIndex -> list.add(i + 1, list.removeAt(i))
        delta > 0 && gi < keys.lastIndex -> {
            val e = list.removeAt(i)
            groups.getValue(keys[gi + 1]).add(0, e.copy(folder = keys[gi + 1]))
        }
        else -> return entries
    }
    return groups.values.flatten()
}

/** What moving [id] by [delta] does, in words for the screen reader: "Move up", "Move up into To work". */
fun moveWords(entries: List<RouteEntry>, id: String, delta: Int): String {
    val groups = groupEntries(entries)
    val gi = groups.indexOfFirst { (_, v) -> v.any { it.id == id } }
    if (gi < 0) return if (delta < 0) "Move up" else "Move down"
    val list = groups[gi].second
    val i = list.indexOfFirst { it.id == id }
    val dir = if (delta < 0) "up" else "down"
    val cross = when {
        delta < 0 && i == 0 && gi > 0 -> groups[gi - 1].first
        delta > 0 && i == list.lastIndex && gi < groups.lastIndex -> groups[gi + 1].first
        else -> return "Move $dir"
    }
    return if (cross != null) "Move $dir into $cross" else "Move $dir out of its folder"
}

/** Whether moving [id] by [delta] would do anything. */
fun canMove(entries: List<RouteEntry>, id: String, delta: Int): Boolean = moveRoute(entries, id, delta) != entries

/** Renames folder [from] to [to] (trimmed; a blank name changes nothing). Folders that end up with the same name merge. */
fun renameFolder(entries: List<RouteEntry>, from: String, to: String): List<RouteEntry> {
    val name = cleanFolder(to) ?: return entries
    return groupEntries(entries.map { if (it.folder == from) it.copy(folder = name) else it }).flatMap { it.second }
}

/** The routes whose folder differs between [before] and [after], with their new folder. */
fun folderChanges(before: List<RouteEntry>, after: List<RouteEntry>): Map<String, String?> {
    val old = before.associate { it.id to it.folder }
    return after.filter { old[it.id] != it.folder }.associate { it.id to it.folder }
}

/** Folder names in use, in order of first appearance. */
fun folderNames(trips: List<SavedTrip>): List<String> =
    entriesOf(trips).mapNotNull { it.folder }.distinct()

/**
 * The title of a route as Home shows it: the place names when it runs from or to a saved place,
 * else its custom name, else its stations.
 */
fun routeTitleParts(trip: SavedTrip, from: SavedPlace?, to: SavedPlace?): List<String> = when {
    from != null || to != null -> listOf(from?.name ?: trip.originName, to?.name ?: trip.destinationName)
    !trip.customName.isNullOrBlank() -> listOf(trip.customName!!)
    else -> listOf(trip.originName, trip.destinationName)
}

/** "STRATHFIELD TO CENTRAL" under a title made of place names or a custom name; null when the title is the stations. */
fun routeStationsLine(trip: SavedTrip, from: SavedPlace?, to: SavedPlace?): String? =
    if (from != null || to != null || !trip.customName.isNullOrBlank()) "${trip.originName} to ${trip.destinationName}".uppercase() else null

/** The route as one phrase for the screen reader and dialogs: "Home to Work". */
fun routeSpokenName(parts: List<String>): String = parts.joinToString(" to ")

/** Why a route cannot be saved yet; null when it can. */
fun routeSaveProblem(fromPlaceId: String?, toPlaceId: String?): String? =
    if (fromPlaceId != null && fromPlaceId == toPlaceId) "A route cannot start and end at the same place." else null

/**
 * A warning when [from]'s switched-on stops do not include where the route starts, so Home could
 * not tell how long the walk is (plan 5.7: leave-by needs the walk to the stop the service leaves
 * from). Null when it matches, or there is no place.
 */
fun leaveByWarning(from: SavedPlace?, firstLeg: TripLeg?, originName: String): String? {
    if (from == null || firstLeg == null) return null
    val matches = from.enabledStops.any { it.stopId in firstLeg.originStopIds }
    return if (matches) null else "${from.name} has no stop at $originName switched on, so Home cannot show when to leave for this route."
}
