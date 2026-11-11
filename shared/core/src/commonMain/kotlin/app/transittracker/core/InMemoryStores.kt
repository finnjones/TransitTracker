package app.transittracker.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [KeyValueStore] kept in memory; for tests and previews. */
class InMemoryKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    private val mutex = Mutex()
    private val values = initial.toMutableMap()

    override suspend fun get(key: String): String? = mutex.withLock { values[key] }

    override suspend fun put(key: String, value: String?) {
        mutex.withLock { if (value == null) values.remove(key) else values[key] = value }
    }

    /** Snapshot of the stored values. */
    suspend fun snapshot(): Map<String, String> = mutex.withLock { values.toMap() }
}

/** [FileStore] kept in memory; paths are `mem:<name>`. For tests and previews. */
class InMemoryFileStore : FileStore {
    private val mutex = Mutex()
    private val files = mutableMapOf<String, ByteArray>()
    private var tempCounter = 0

    override suspend fun writeTemp(bytes: ByteArray): String = mutex.withLock {
        val path = "mem:tmp/${tempCounter++}"
        files[path] = bytes.copyOf()
        path
    }

    override suspend fun replace(tempPath: String, name: String): String = mutex.withLock {
        val bytes = files.remove(tempPath) ?: error("No temp file $tempPath")
        val path = resolve(name)
        files[path] = bytes
        path
    }

    override fun resolve(name: String): String = if (name.startsWith("mem:")) name else "mem:$name"

    override suspend fun exists(name: String): Boolean = mutex.withLock { resolve(name) in files }

    override suspend fun delete(name: String) {
        mutex.withLock { files.remove(resolve(name)) }
    }

    /** Contents of the file at [path] (a resolved path or a name), or null. */
    suspend fun read(path: String): ByteArray? = mutex.withLock { files[resolve(path)]?.copyOf() }

    /** All stored paths. */
    suspend fun paths(): Set<String> = mutex.withLock { files.keys.toSet() }
}
