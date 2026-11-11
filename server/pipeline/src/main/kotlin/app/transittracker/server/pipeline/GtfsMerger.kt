package app.transittracker.server.pipeline

/**
 * Two input bundles define the same id differently. Packs merge several bundles (e.g. Sydney
 * Trains + Metro) into one id space, so this is fatal rather than guessed at.
 */
class ConflictingIdException(val kind: String, val id: String, val sources: List<String>) :
    RuntimeException("Conflicting $kind '$id' in ${sources.joinToString(" and ")}")

/**
 * Merges several feeds into one. Records with the same id in more than one feed must be
 * identical (shared interchange stations, shared agencies); otherwise [ConflictingIdException]
 * is thrown. Duplicates within one feed are the loader's concern (first occurrence wins here).
 * Output order is deterministic: input order, first occurrence wins.
 */
object GtfsMerger {
    fun merge(feeds: List<GtfsFeed>): GtfsFeed {
        require(feeds.isNotEmpty()) { "No feeds to merge" }
        if (feeds.size == 1) return feeds.single()
        val names = feeds.map { f -> f.sources.joinToString("+") { it.first } }

        fun <T, K> mergeBy(kind: String, select: (GtfsFeed) -> List<T>, key: (T) -> K): List<T> {
            val out = LinkedHashMap<K, Pair<T, Int>>()
            feeds.forEachIndexed { i, feed ->
                for (item in select(feed)) {
                    val k = key(item)
                    val existing = out[k]
                    if (existing == null) {
                        out[k] = item to i
                    } else if (existing.second != i && existing.first != item) {
                        throw ConflictingIdException(kind, k.toString(), listOf(names[existing.second], names[i]))
                    }
                }
            }
            return out.values.map { it.first }
        }

        fun <V> mergeMaps(kind: String, select: (GtfsFeed) -> Map<String, V>): Map<String, V> {
            val out = LinkedHashMap<String, Pair<V, Int>>()
            feeds.forEachIndexed { i, feed ->
                for ((k, v) in select(feed)) {
                    val existing = out[k]
                    if (existing == null) {
                        out[k] = v to i
                    } else if (existing.second != i && existing.first != v) {
                        throw ConflictingIdException(kind, k, listOf(names[existing.second], names[i]))
                    }
                }
            }
            return out.mapValues { it.value.first }
        }

        return GtfsFeed(
            sources = feeds.flatMap { it.sources },
            agencies = mergeBy("agency", { it.agencies }) { it.id ?: it.name },
            stops = mergeBy("stop", { it.stops }) { it.id },
            routes = mergeBy("route", { it.routes }) { it.id },
            trips = mergeBy("trip", { it.trips }) { it.id },
            stopTimes = mergeMaps("stop times of trip") { it.stopTimes },
            calendars = mergeBy("calendar service", { it.calendars }) { it.serviceId },
            calendarDates = mergeBy("calendar date", { it.calendarDates }) { it.serviceId to it.date },
            shapes = mergeMaps("shape") { it.shapes },
            transfers = mergeBy("transfer", { it.transfers }) { it.fromStopId to it.toStopId },
        )
    }
}
