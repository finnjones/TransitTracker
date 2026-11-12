package app.transittracker.ui.feature.plan

import app.transittracker.model.Itinerary
import app.transittracker.model.LegPoint
import app.transittracker.model.SavedTrip
import app.transittracker.model.StopId
import app.transittracker.model.TransitCore
import app.transittracker.model.rides
import app.transittracker.ui.format.shortStopName
import app.transittracker.ui.nav.JourneyEnd
import app.transittracker.ui.places.PlacesStore
import kotlinx.coroutines.flow.first

/** Whether a planned journey can become a way of a saved route. */
sealed interface Savable {
    /** It can: one way, as a route of its own. */
    data class Ready(val trip: SavedTrip) : Savable

    /** It cannot: a plain sentence why. */
    data class Blocked(val reason: String) : Savable
}

/**
 * What saving would do: the route to save ([trip]), the saved route it can join as another way
 * ([joinTo], titled [joinTitle]) and whether that route already has every way ([alreadyThere]).
 */
data class SavePlan(
    val trip: SavedTrip,
    val joinTo: SavedTrip? = null,
    val joinTitle: String? = null,
    val alreadyThere: Boolean = false,
)

/**
 * Turns planned journeys into saved routes (plan 5.6 "Saved routes"): checks the phone's
 * timetable knows every stop, finds a saved route between the same ends, and saves, as a new
 * route or as more ways of that one, with the return when asked. Shared by the Journey and
 * Journey options pages.
 */
class RouteSaver(private val core: TransitCore, private val places: PlacesStore) {

    /** Pure checks, then the phone's timetable: can [itinerary] be saved, and as what. */
    suspend fun check(itinerary: Itinerary): Savable {
        saveBlocker(itinerary)?.let { return Savable.Blocked(it) }
        val points: List<LegPoint> = itinerary.rides.flatMap { listOf(it.from, it.to) }
        val ids = points.mapNotNull { it.stopId }.distinct()
        val unknown = ids.filter { id -> runCatching { core.linesAt(id).isEmpty() }.getOrDefault(true) }.toSet()
        unknownStopsReason(itinerary, unknown)?.let { return Savable.Blocked(it) }
        val stationOf = mutableMapOf<StopId, StopId>()
        for (p in points) {
            val id = p.stopId ?: continue
            if (id in stationOf) continue
            val near = runCatching { core.stopsNear(p.position, 400.0) }.getOrDefault(emptyList())
            val station = near.firstOrNull { it.id == id } ?: near.firstOrNull { shortStopName(it.name).equals(p.name, ignoreCase = true) }
            if (station != null) stationOf[id] = station.id
        }
        return Savable.Ready(savedTripOf(itinerary, stationOf))
    }

    /** What saving [trip] between [from] and [to] would do, given the routes saved now. */
    suspend fun plan(trip: SavedTrip, from: JourneyEnd, to: JourneyEnd): SavePlan {
        val trips = core.trips.first()
        val links = places.tripPlaces.first()
        val join = routeToJoin(trips, links, trip, from.savedPlaceId, to.savedPlaceId) ?: return SavePlan(trip)
        val title = joinTitle(join, links[join.id], places.currentPlaces())
        return SavePlan(trip, join, title, hasAllWays(join, trip))
    }

    /**
     * Saves [trip] between [from] and [to], and its return when [withReturn]. With [join] each is
     * added as more ways to the saved route between the same ends when there is one; otherwise
     * it is a route of its own at the end of Home (but the same pattern saved before is always
     * joined, so its other ways are never lost). Returns the ids of the routes saved.
     */
    suspend fun save(trip: SavedTrip, from: JourneyEnd, to: JourneyEnd, withReturn: Boolean, join: Boolean): List<String> {
        val trips = core.trips.first()
        val links = places.tripPlaces.first()
        var order = (trips.maxOfOrNull { it.order } ?: -1) + 1
        val saved = mutableListOf<String>()

        suspend fun one(t: SavedTrip, fromPlace: String?, toPlace: String?) {
            val target = if (join) routeToJoin(trips, links, t, fromPlace, toPlace) else trips.firstOrNull { it.id == t.id }
            if (target != null) {
                core.saveTrip(target.withWays(target.ways + t.ways))
                saved += target.id
            } else {
                core.saveTrip(t.copy(order = order++))
                saved += t.id
                if (fromPlace != null || toPlace != null) places.linkTrip(t.id, fromPlace, toPlace)
            }
        }

        one(trip, from.savedPlaceId, to.savedPlaceId)
        if (withReturn) one(returnTripOf(trip), to.savedPlaceId, from.savedPlaceId)
        return saved
    }
}
