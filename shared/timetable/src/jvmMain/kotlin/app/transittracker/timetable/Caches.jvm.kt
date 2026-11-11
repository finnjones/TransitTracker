package app.transittracker.timetable

internal actual fun <K : Any, V : Any> concurrentLruCache(maxSize: Int): ConcurrentCache<K, V> = JvmLruCache(maxSize)

private class JvmLruCache<K : Any, V : Any>(private val maxSize: Int) : ConcurrentCache<K, V> {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }

    override fun getOrPut(key: K, compute: () -> V): V {
        synchronized(map) { map[key] }?.let { return it }
        val value = compute()
        return synchronized(map) { map.putIfAbsent(key, value) ?: value }
    }

    override fun clear() = synchronized(map) { map.clear() }
}
