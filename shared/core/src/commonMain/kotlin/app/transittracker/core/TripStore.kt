package app.transittracker.core

import app.transittracker.model.SavedTrip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/** Keys used in the [KeyValueStore]. */
object KvKeys {
    const val DEVICE_TOKEN = "device.token"
    const val DEVICE_ID = "device.id"
    const val PUSH_TOKEN = "device.pushToken"
    /** Push token not yet accepted by the server (sent after joining / on next start). */
    const val PUSH_TOKEN_PENDING = "device.pushTokenPending"
    const val TRIPS = "trips"
    const val WATCHES = "watches.cache"
    const val INSTALLED_PACKS = "packs.installed"
    const val CONFIG = "config.cache"
}

/**
 * Saved trips, persisted as JSON in the [KeyValueStore]. The list is kept sorted by
 * [SavedTrip.order], which is rewritten as 0..n-1 on every change.
 */
class TripStore(private val kv: KeyValueStore) {
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<SavedTrip>?>(null)
    private val serializer = ListSerializer(SavedTrip.serializer())

    /** Saved trips in display order; emits on every change. */
    val trips: Flow<List<SavedTrip>> = state.onStart { load() }.filterNotNull()

    suspend fun get(id: String): SavedTrip? = load().firstOrNull { it.id == id }

    /** Inserts or replaces [trip]; new trips go to the end. */
    suspend fun save(trip: SavedTrip) = mutate { list ->
        val i = list.indexOfFirst { it.id == trip.id }
        if (i >= 0) list.toMutableList().also { it[i] = trip } else list + trip
    }

    suspend fun delete(id: String) = mutate { list -> list.filter { it.id != id } }

    /** Puts trips in the order of [ids]; trips not listed keep their relative order after them. */
    suspend fun reorder(ids: List<String>) = mutate { list ->
        val byId = list.associateBy { it.id }
        val listed = ids.distinct().mapNotNull { byId[it] }
        listed + list.filter { it.id !in ids }
    }

    private suspend fun load(): List<SavedTrip> {
        state.value?.let { return it }
        return mutex.withLock {
            state.value ?: run {
                val stored = kv.get(KvKeys.TRIPS)?.let {
                    runCatching { ApiJson.decodeFromString(serializer, it) }.getOrNull()
                }.orEmpty()
                stored.sortedBy { it.order }.also { state.value = it }
            }
        }
    }

    private suspend fun mutate(change: (List<SavedTrip>) -> List<SavedTrip>) {
        load()
        mutex.withLock {
            val updated = change(state.value.orEmpty()).mapIndexed { i, t -> t.copy(order = i) }
            kv.put(KvKeys.TRIPS, ApiJson.encodeToString(serializer, updated))
            state.value = updated
        }
    }
}
