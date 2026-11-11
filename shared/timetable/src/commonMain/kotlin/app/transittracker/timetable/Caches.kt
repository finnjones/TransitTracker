package app.transittracker.timetable

/** A thread-safe, size-bounded cache. */
internal interface ConcurrentCache<K : Any, V : Any> {
    /** Returns the cached value, computing it with [compute] (outside any lock) if absent. */
    fun getOrPut(key: K, compute: () -> V): V

    fun clear()
}

/** Platform-specific thread-safe LRU cache holding at most [maxSize] entries. */
internal expect fun <K : Any, V : Any> concurrentLruCache(maxSize: Int): ConcurrentCache<K, V>
