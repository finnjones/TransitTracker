package app.transittracker.core

import app.transittracker.model.Ticket
import app.transittracker.model.isExpired
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/**
 * Tickets, persisted as JSON in the [KeyValueStore] under [KvKeys.TICKETS] (on the phone only).
 * The list is kept sorted by [Ticket.issuedAtMs], oldest first. It holds every stored ticket,
 * expired or not: hiding expired ones is up to the reader, and [deleteExpired] drops them.
 */
class TicketStore(private val kv: KeyValueStore) {
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<Ticket>?>(null)
    private val serializer = ListSerializer(Ticket.serializer())

    /** Stored tickets, oldest issued first; emits on every change. */
    val tickets: Flow<List<Ticket>> = state.onStart { load() }.filterNotNull()

    suspend fun get(id: String): Ticket? = load().firstOrNull { it.id == id }

    /** Inserts [ticket], or replaces the stored ticket with the same id. */
    suspend fun save(ticket: Ticket) = mutate { list -> list.filter { it.id != ticket.id } + ticket }

    suspend fun delete(id: String) = mutate { list -> list.filter { it.id != id } }

    /** Drops the tickets expired at [nowMs]. Writes only when there is one to drop. */
    suspend fun deleteExpired(nowMs: Long) {
        if (load().none { it.isExpired(nowMs) }) return
        mutate { list -> list.filterNot { it.isExpired(nowMs) } }
    }

    private suspend fun read(): List<Ticket> = kv.get(KvKeys.TICKETS)?.let {
        runCatching { ApiJson.decodeFromString(serializer, it) }.getOrNull()
    }.orEmpty().sortedBy { it.issuedAtMs }

    private suspend fun load(): List<Ticket> {
        state.value?.let { return it }
        return mutex.withLock {
            state.value ?: read().also { state.value = it }
        }
    }

    private suspend fun mutate(change: (List<Ticket>) -> List<Ticket>) {
        load()
        mutex.withLock {
            val updated = change(state.value.orEmpty()).sortedBy { it.issuedAtMs }
            kv.put(KvKeys.TICKETS, ApiJson.encodeToString(serializer, updated))
            state.value = updated
        }
    }
}
