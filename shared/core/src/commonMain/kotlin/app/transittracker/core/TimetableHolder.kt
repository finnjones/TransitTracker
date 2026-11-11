package app.transittracker.core

import app.transittracker.model.Timetable
import app.transittracker.timetable.CompositeTimetable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Holds the open timetable packs and hands out a combined view.
 *
 * Queries lease the packs they read, so a pack replaced by the updater is closed only after the
 * last query using it has finished. [generation] changes whenever the set of packs changes, so
 * flows can recompute.
 */
class TimetableHolder(private val ioContext: CoroutineContext) {
    private class Entry(val name: String, val version: String, val timetable: Timetable) {
        var onClosed: () -> Unit = {}
        var leases = 0
        var retired = false
    }

    private val mutex = Mutex()
    private var entries: List<Entry> = emptyList()
    private val _generation = MutableStateFlow(0)

    /** Incremented whenever a pack is installed or removed. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /** Installed pack versions by name. */
    suspend fun versions(): Map<String, String> = mutex.withLock { entries.associate { it.name to it.version } }

    /**
     * Installs [timetable] as pack [name], replacing any previous version. The previous version is
     * closed once no query is using it, then [onReplacedClosed] runs (e.g. to delete its file).
     */
    suspend fun install(name: String, version: String, timetable: Timetable, onReplacedClosed: () -> Unit = {}) {
        mutex.withLock {
            val old = entries.firstOrNull { it.name == name }
            entries = (entries.filter { it.name != name } + Entry(name, version, timetable)).sortedBy { it.name }
            if (old != null) {
                old.onClosed = onReplacedClosed
                retire(old)
            }
        }
        _generation.value++
    }

    /** Removes pack [name]; it is closed once unused. */
    suspend fun remove(name: String) {
        mutex.withLock {
            val old = entries.firstOrNull { it.name == name } ?: return
            entries = entries - old
            retire(old)
        }
        _generation.value++
    }

    /**
     * Runs [block] on the I/O context against all installed packs combined, or returns null when no
     * pack is installed.
     */
    suspend fun <T> read(block: (Timetable) -> T): T? {
        val leased = mutex.withLock {
            entries.onEach { it.leases++ }
        }
        if (leased.isEmpty()) return null
        try {
            // The view is never closed: the leased packs are closed by this holder once retired.
            val view = if (leased.size == 1) leased[0].timetable else CompositeTimetable(leased.map { it.timetable })
            return withContext(ioContext) { block(view) }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    for (e in leased) {
                        e.leases--
                        if (e.retired && e.leases == 0) closeEntry(e)
                    }
                }
            }
        }
    }

    /** Closes every pack (core shutdown). */
    suspend fun closeAll() {
        mutex.withLock {
            entries.forEach(::retire)
            entries = emptyList()
        }
        _generation.value++
    }

    private fun retire(entry: Entry) {
        entry.retired = true
        if (entry.leases == 0) closeEntry(entry)
    }

    private fun closeEntry(entry: Entry) {
        runCatching { entry.timetable.close() }
        entry.onClosed()
    }
}
