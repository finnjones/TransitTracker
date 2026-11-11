package app.transittracker.ui.places

import app.transittracker.core.KeyValueStore
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.StopId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The icon of a saved place (plan 2.6: house, briefcase, pin). */
@Serializable
enum class PlaceIcon { HOME, WORK, PIN }

/**
 * A stop within walking distance of a place (plan 5.7). [stopId] is the station (or stand) id;
 * [walkMinutes] comes from the planner or straight-line distance ([estimated] true) and the user
 * can correct it; a switched-off stop ([enabled] false) is not used by trips from this place.
 * [modes] is what calls there, for stops whose lines the phone's timetable does not know (buses).
 */
@Serializable
data class PlaceStop(
    val stopId: StopId,
    val name: String,
    val walkMinutes: Int,
    val distanceMeters: Int,
    val enabled: Boolean = true,
    val estimated: Boolean = false,
    val modes: List<Mode> = emptyList(),
)

/** A named address the user saved: Home, Work, the gym. Never leaves the phone. */
@Serializable
data class SavedPlace(
    val id: String,
    val name: String,
    val icon: PlaceIcon,
    /** What the user typed or picked, e.g. "Albert Rd, Strathfield". */
    val address: String,
    val position: LatLng,
    /** Nearest first. */
    val stops: List<PlaceStop> = emptyList(),
) {
    val enabledStops: List<PlaceStop> get() = stops.filter { it.enabled }
}

/** Which places a saved trip (`SavedTrip.id`) starts and ends at (plan 5.7); either may be null. */
@Serializable
data class TripPlaces(
    val tripId: String,
    val fromPlaceId: String? = null,
    val toPlaceId: String? = null,
)

/**
 * Saved places and the link from saved trips to them. Stored on the phone only (plan 5.7: places
 * are never sent to the server). Flows emit the stored value first, then every change.
 */
interface PlacesStore {
    /** In the user's order. */
    val places: Flow<List<SavedPlace>>

    /** Trip id -> its places. Trips between two stations have no entry. */
    val tripPlaces: Flow<Map<String, TripPlaces>>

    fun place(id: String): Flow<SavedPlace?> = places.map { list -> list.firstOrNull { it.id == id } }

    suspend fun currentPlaces(): List<SavedPlace>

    /** Adds [place], or replaces the place with the same id (keeping its position in the list). */
    suspend fun savePlace(place: SavedPlace)

    /** Deletes the place and clears it from every trip link. */
    suspend fun deletePlace(id: String)

    /** Puts places in this order (ids not listed keep their relative order after them). */
    suspend fun reorderPlaces(ids: List<String>)

    /** Links a saved trip to its places; both null removes the link. */
    suspend fun linkTrip(tripId: String, fromPlaceId: String?, toPlaceId: String?)

    /** Removes the link of a deleted trip. */
    suspend fun unlinkTrip(tripId: String) = linkTrip(tripId, null, null)
}

/**
 * [PlacesStore] over the core's [KeyValueStore]: two JSON values, `places.list` and
 * `places.tripLinks`. Unreadable stored data is treated as empty rather than crashing.
 */
class KeyValuePlacesStore(private val kv: KeyValueStore) : PlacesStore {
    private class Data(val places: List<SavedPlace>, val links: Map<String, TripPlaces>)

    private val state = MutableStateFlow<Data?>(null)
    private val mutex = Mutex()

    override val places: Flow<List<SavedPlace>> = state.onStart { load() }.filterNotNull().map { it.places }
    override val tripPlaces: Flow<Map<String, TripPlaces>> = state.onStart { load() }.filterNotNull().map { it.links }

    override suspend fun currentPlaces(): List<SavedPlace> = load().places

    override suspend fun savePlace(place: SavedPlace) = edit { d ->
        val i = d.places.indexOfFirst { it.id == place.id }
        val list = if (i >= 0) d.places.toMutableList().also { it[i] = place } else d.places + place
        Data(list, d.links)
    }

    override suspend fun deletePlace(id: String) = edit { d ->
        val links = d.links.mapValues { (_, l) ->
            l.copy(fromPlaceId = l.fromPlaceId.takeIf { it != id }, toPlaceId = l.toPlaceId.takeIf { it != id })
        }.filterValues { it.fromPlaceId != null || it.toPlaceId != null }
        Data(d.places.filter { it.id != id }, links)
    }

    override suspend fun reorderPlaces(ids: List<String>) = edit { d ->
        val byId = d.places.associateBy { it.id }
        Data(ids.mapNotNull { byId[it] } + d.places.filter { it.id !in ids }, d.links)
    }

    override suspend fun linkTrip(tripId: String, fromPlaceId: String?, toPlaceId: String?) = edit { d ->
        val links = if (fromPlaceId == null && toPlaceId == null) d.links - tripId
        else d.links + (tripId to TripPlaces(tripId, fromPlaceId, toPlaceId))
        Data(d.places, links)
    }

    private suspend fun edit(change: (Data) -> Data) {
        mutex.withLock {
            val old = state.value ?: read()
            val new = change(old)
            if (new.places != old.places) kv.put(KEY_PLACES, json.encodeToString(placesSerializer, new.places))
            if (new.links != old.links) kv.put(KEY_LINKS, json.encodeToString(linksSerializer, new.links.values.toList()))
            state.value = new
        }
    }

    private suspend fun load(): Data = state.value ?: mutex.withLock { state.value ?: read().also { state.value = it } }

    private suspend fun read(): Data {
        val places = kv.get(KEY_PLACES)?.let { runCatching { json.decodeFromString(placesSerializer, it) }.getOrNull() }.orEmpty()
        val links = kv.get(KEY_LINKS)?.let { runCatching { json.decodeFromString(linksSerializer, it) }.getOrNull() }.orEmpty()
        return Data(places, links.associateBy { it.tripId })
    }

    companion object {
        const val KEY_PLACES = "places.list"
        const val KEY_LINKS = "places.tripLinks"
        private val json = Json { ignoreUnknownKeys = true }
        private val placesSerializer = ListSerializer(SavedPlace.serializer())
        private val linksSerializer = ListSerializer(TripPlaces.serializer())
    }
}
