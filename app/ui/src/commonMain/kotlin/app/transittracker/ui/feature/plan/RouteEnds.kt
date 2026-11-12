package app.transittracker.ui.feature.plan

import app.transittracker.model.StopId
import app.transittracker.model.TransitCore
import app.transittracker.ui.nav.JourneyEnd
import app.transittracker.ui.places.PlacesStore
import kotlinx.coroutines.flow.first

/**
 * The two ends of saved route [tripId], to plan it again: its saved places, else the stations it
 * starts and ends at (of any of its ways), found by name among the phone's stops. Null when the
 * route or an end cannot be found.
 */
suspend fun routeEnds(core: TransitCore, places: PlacesStore, tripId: String): Pair<JourneyEnd, JourneyEnd>? {
    val t = core.trips.first().firstOrNull { it.id == tripId } ?: return null
    val link = runCatching { places.tripPlaces.first()[tripId] }.getOrNull()
    val all = runCatching { places.currentPlaces() }.getOrDefault(emptyList())
    suspend fun end(placeId: String?, name: String, ids: Set<StopId>): JourneyEnd? {
        all.firstOrNull { it.id == placeId }?.let { return JourneyEnd(it.name, it.position, savedPlaceId = it.id) }
        val found = runCatching { core.searchStops(name) }.getOrDefault(emptyList())
        val stop = found.firstOrNull { it.id in ids || it.parentId in ids } ?: found.firstOrNull() ?: return null
        return JourneyEnd(name, stop.position)
    }
    val from = end(link?.fromPlaceId, t.originName, t.ways.flatMapTo(HashSet()) { it.legs.first().originStopIds }) ?: return null
    val to = end(link?.toPlaceId, t.destinationName, t.ways.flatMapTo(HashSet()) { it.legs.last().destinationStopIds }) ?: return null
    return from to to
}
