package app.transittracker.server.pipeline

import app.transittracker.model.LatLng
import app.transittracker.timetable.Geo
import kotlin.math.roundToInt

/** One id that two input feeds define in incompatible ways. */
data class MergeConflict(val kind: String, val id: String, val sources: List<String>, val reason: String) {
    override fun toString(): String = "$kind '$id' (${sources.joinToString(" vs ")}): $reason"
}

/** Something the merger reconciled or renamed; reported so a person can review it. */
data class MergeNote(val kind: String, val id: String, val action: Action, val detail: String) {
    enum class Action { RECONCILED, RENAMED }

    override fun toString(): String = "${action.name.lowercase()} $kind '$id': $detail"
}

/** What happened while merging: per-kind counts of ids shared by several feeds, and every note. */
data class MergeReport(val sharedIds: Map<String, Int>, val notes: List<MergeNote>) {
    val reconciled: List<MergeNote> get() = notes.filter { it.action == MergeNote.Action.RECONCILED }
    val renamed: List<MergeNote> get() = notes.filter { it.action == MergeNote.Action.RENAMED }

    /** Adds every note to [report] as a warning (`merge_reconciled` / `merge_renamed`). */
    fun recordIn(report: ValidationReport.Builder) {
        notes.forEach { report.warning("merge_${it.action.name.lowercase()}", it.toString()) }
    }
}

class MergeResult(val feed: GtfsFeed, val report: MergeReport)

/**
 * Input feeds define the same id in incompatible ways. Every conflict found is listed (not just
 * the first), so one run shows everything that needs attention.
 */
class ConflictingIdException(val conflicts: List<MergeConflict>) : RuntimeException(describe(conflicts)) {
    init {
        require(conflicts.isNotEmpty())
    }

    val kind: String get() = conflicts.first().kind
    val id: String get() = conflicts.first().id
    val sources: List<String> get() = conflicts.first().sources

    private companion object {
        fun describe(conflicts: List<MergeConflict>): String {
            val shown = conflicts.take(10).joinToString("; ") { "Conflicting $it" }
            return if (conflicts.size > 10) "$shown; and ${conflicts.size - 10} more" else shown
        }
    }
}

/**
 * Tolerances for deciding that two records with the same id describe the same thing.
 *
 * Feeds from different operators describe shared interchange stations independently: the same
 * station id can carry coordinates at different precision, or placed at a different point of
 * the station complex (e.g. Sydney Trains vs Metro at Central, about 60 m apart).
 */
data class MergePolicy(
    /** Max distance between two descriptions of one station (location_type 1). */
    val stationToleranceMeters: Double = 300.0,
    /** Max distance for every other stop type (platforms, entrances, nodes). */
    val stopToleranceMeters: Double = 50.0,
)

/**
 * Merges several GTFS feeds (e.g. Sydney Trains + Metro) into one id space, deterministically.
 *
 * Policy, by kind of id:
 *
 * - **Ids that live data refers to — stops, routes, trips — are never renamed.** Records with the
 *   same id must describe the same thing:
 *   - *Stops* must have the same `location_type` and the same name (ignoring case, spacing and
 *     punctuation), compatible `parent_station` and `platform_code` (equal, or given by only
 *     one feed), and lie within [MergePolicy] distance of each other. The first feed's name and
 *     position are kept; a missing parent or platform code is filled from the other feed;
 *     unknown wheelchair access (0) yields to a known value, and contradictory values (1 vs 2)
 *     become unknown (0).
 *   - *Routes* must agree on type, short name and agency; differing presentation (long name,
 *     colours, sort order) keeps the first feed's values.
 *   - *Trips* must be identical, including their stop times (after the renames below).
 *   Anything else is a [MergeConflict].
 * - **Ids that only matter inside a feed — agency, service and shape ids — are renamed when two
 *   feeds use the same id for different things.** The later feed's id gets the suffix `~N`
 *   (N = 1-based position of the feed in the input list) and every reference in that feed is
 *   updated. Identical definitions are shared. (Agencies that differ only in URL, language or
 *   other contact details are treated as identical, keeping the first feed's record.)
 * - **Transfers** for the same stop pair: identical records are shared; otherwise the more
 *   restrictive record is kept (type 3 > 2 > 1 > 0, then the longer minimum transfer time).
 *
 * All conflicts are collected and thrown together as [ConflictingIdException]. Output order is
 * input order with the first occurrence winning, so the same inputs always give the same feed.
 * Duplicates within one feed are the loader's concern.
 */
object GtfsMerger {
    fun merge(feeds: List<GtfsFeed>, policy: MergePolicy = MergePolicy()): GtfsFeed = mergeWithReport(feeds, policy).feed

    fun mergeWithReport(feeds: List<GtfsFeed>, policy: MergePolicy = MergePolicy()): MergeResult {
        require(feeds.isNotEmpty()) { "No feeds to merge" }
        if (feeds.size == 1) return MergeResult(feeds.single(), MergeReport(emptyMap(), emptyList()))
        return Merge(feeds, policy).run()
    }

    private class Merge(private val feeds: List<GtfsFeed>, private val policy: MergePolicy) {
        private val names = feeds.map { f -> f.sources.joinToString("+") { it.first } }
        private val notes = ArrayList<MergeNote>()
        private val conflicts = ArrayList<MergeConflict>()
        private val shared = LinkedHashMap<String, Int>()

        private fun shared(kind: String) {
            shared[kind] = (shared[kind] ?: 0) + 1
        }

        private fun note(kind: String, id: String, action: MergeNote.Action, detail: String) {
            notes += MergeNote(kind, id, action, detail)
        }

        private fun conflict(kind: String, id: String, first: Int, second: Int, reason: String) {
            conflicts += MergeConflict(kind, id, listOf(names[first], names[second]), reason)
        }

        fun run(): MergeResult {
            // 1. Feed-internal ids: decide renames per feed.
            val agencyRemap = mergeAgencies()
            val serviceRemap = renameOnConflict("service", { f -> serviceDefinitions(f) })
            val shapeRemap = renameOnConflict("shape", { f -> f.shapes.mapValues { (_, pts) -> pts } })

            // 2. Apply them so every later comparison sees final ids.
            val remapped = feeds.mapIndexed { i, f -> applyRemaps(f, agencyRemap[i], serviceRemap[i], shapeRemap[i]) }

            // 3. Shared ids that must keep their names.
            val stops = mergeRecords(remapped, "stop", { it.stops }, { it.id }) { a, b -> reconcileStop(a, b) }
            val routes = mergeRecords(remapped, "route", { it.routes }, { it.id }) { a, b -> reconcileRoute(a, b) }
            val tripsAndTimes = mergeTrips(remapped)
            val transfers = mergeRecords(remapped, "transfer", { it.transfers }, { "${it.fromStopId}->${it.toStopId}" }) { a, b ->
                reconcileTransfer(a, b)
            }

            if (conflicts.isNotEmpty()) throw ConflictingIdException(conflicts.toList())

            val feed = GtfsFeed(
                sources = feeds.flatMap { it.sources },
                agencies = agencies,
                stops = stops,
                routes = routes,
                trips = tripsAndTimes.first,
                stopTimes = tripsAndTimes.second,
                calendars = distinctFirst(remapped.flatMap { it.calendars }) { it.serviceId },
                calendarDates = distinctFirst(remapped.flatMap { it.calendarDates }) { it.serviceId to it.date },
                shapes = LinkedHashMap<String, List<GtfsShapePoint>>().also { out ->
                    remapped.forEach { f -> f.shapes.forEach { (k, v) -> out.putIfAbsent(k, v) } }
                },
                transfers = transfers,
            )
            return MergeResult(feed, MergeReport(shared.toMap(), notes.toList()))
        }

        // ---- agencies ---------------------------------------------------------------------------

        private var agencies: List<GtfsAgency> = emptyList()

        private fun mergeAgencies(): List<Map<String, String>> {
            val out = LinkedHashMap<String, Pair<GtfsAgency, Int>>()
            val remaps = feeds.map { HashMap<String, String>() }
            feeds.forEachIndexed { i, feed ->
                for (agency in feed.agencies) {
                    val key = agency.id ?: agency.name
                    val existing = out[key]
                    when {
                        existing == null -> out[key] = agency to i
                        existing.second == i -> Unit
                        existing.first == agency -> shared("agency")
                        existing.first.name.normalizedName() == agency.name.normalizedName() &&
                            existing.first.timezone == agency.timezone -> {
                            shared("agency")
                            note("agency", key, MergeNote.Action.RECONCILED, "contact details differ; kept ${names[existing.second]}'s")
                        }
                        agency.id == null -> conflict(
                            "agency", key, existing.second, i,
                            "same name without agency_id but timezone ${existing.first.timezone} vs ${agency.timezone}",
                        )
                        else -> {
                            val newId = uniqueId(agency.id, i) { it in out }
                            remaps[i][agency.id] = newId
                            out[newId] = agency.copy(id = newId) to i
                            note("agency", agency.id, MergeNote.Action.RENAMED, "${names[i]}'s agency is different (${agency.name}); renamed to '$newId'")
                        }
                    }
                }
            }
            agencies = out.values.map { it.first }
            return remaps
        }

        // ---- services and shapes ----------------------------------------------------------------

        /** A service's full definition: its calendar row (without the id) and its exceptions. */
        private data class ServiceDef(
            val days: List<Boolean>?,
            val start: java.time.LocalDate?,
            val end: java.time.LocalDate?,
            val exceptions: List<Pair<java.time.LocalDate, Int>>,
        )

        private fun serviceDefinitions(feed: GtfsFeed): Map<String, ServiceDef> {
            val calendars = feed.calendars.associateBy { it.serviceId }
            val exceptions = feed.calendarDates.groupBy { it.serviceId }
            val ids = LinkedHashSet<String>().apply {
                feed.calendars.forEach { add(it.serviceId) }
                feed.calendarDates.forEach { add(it.serviceId) }
            }
            return ids.associateWith { id ->
                val c = calendars[id]
                ServiceDef(
                    days = c?.days,
                    start = c?.startDate,
                    end = c?.endDate,
                    exceptions = exceptions[id].orEmpty().map { it.date to it.exceptionType }.sortedBy { it.first },
                )
            }
        }

        /** For feed-internal ids: identical definitions are shared, different ones renamed in the later feed. */
        private fun <D> renameOnConflict(kind: String, definitions: (GtfsFeed) -> Map<String, D>): List<Map<String, String>> {
            val claimed = HashMap<String, Pair<D, Int>>()
            return feeds.mapIndexed { i, feed ->
                val remap = HashMap<String, String>()
                for ((id, def) in definitions(feed)) {
                    val existing = claimed[id]
                    when {
                        existing == null -> claimed[id] = def to i
                        existing.first == def -> shared(kind)
                        else -> {
                            val newId = uniqueId(id, i) { it in claimed }
                            remap[id] = newId
                            claimed[newId] = def to i
                            note(kind, id, MergeNote.Action.RENAMED, "${names[existing.second]} and ${names[i]} use it for different ${kind}s; ${names[i]}'s renamed to '$newId'")
                        }
                    }
                }
                remap
            }
        }

        private fun uniqueId(id: String, feedIndex: Int, taken: (String) -> Boolean): String {
            var candidate = "$id~${feedIndex + 1}"
            var n = 2
            while (taken(candidate)) candidate = "$id~${feedIndex + 1}.${n++}"
            return candidate
        }

        private fun applyRemaps(
            feed: GtfsFeed,
            agencies: Map<String, String>,
            services: Map<String, String>,
            shapes: Map<String, String>,
        ): GtfsFeed {
            if (agencies.isEmpty() && services.isEmpty() && shapes.isEmpty()) return feed
            fun service(id: String) = services[id] ?: id
            return GtfsFeed(
                sources = feed.sources,
                agencies = feed.agencies.map { a -> a.id?.let { agencies[it] }?.let { a.copy(id = it) } ?: a },
                stops = feed.stops,
                routes = feed.routes.map { r -> r.agencyId?.let { agencies[it] }?.let { r.copy(agencyId = it) } ?: r },
                trips = feed.trips.map { t ->
                    t.copy(serviceId = service(t.serviceId), shapeId = t.shapeId?.let { shapes[it] ?: it })
                },
                stopTimes = feed.stopTimes,
                calendars = feed.calendars.map { it.copy(serviceId = service(it.serviceId)) },
                calendarDates = feed.calendarDates.map { it.copy(serviceId = service(it.serviceId)) },
                shapes = LinkedHashMap<String, List<GtfsShapePoint>>().also { out ->
                    feed.shapes.forEach { (k, v) -> out[shapes[k] ?: k] = v }
                },
                transfers = feed.transfers,
            )
        }

        // ---- ids that keep their names ----------------------------------------------------------

        private sealed interface Outcome<out T> {
            data object Same : Outcome<Nothing>
            data class Reconciled<T>(val merged: T, val details: List<String>) : Outcome<T>
            data class Incompatible(val reason: String) : Outcome<Nothing>
        }

        private fun <T, K : Any> mergeRecords(
            remapped: List<GtfsFeed>,
            kind: String,
            select: (GtfsFeed) -> List<T>,
            key: (T) -> K,
            reconcile: (T, T) -> Outcome<T>,
        ): List<T> {
            val out = LinkedHashMap<K, Pair<T, Int>>()
            remapped.forEachIndexed { i, feed ->
                for (item in select(feed)) {
                    val k = key(item)
                    val existing = out[k] ?: run { out[k] = item to i; null } ?: continue
                    if (existing.second == i) continue
                    if (existing.first == item) {
                        shared(kind)
                        continue
                    }
                    when (val o = reconcile(existing.first, item)) {
                        Outcome.Same -> shared(kind)
                        is Outcome.Reconciled -> {
                            shared(kind)
                            out[k] = o.merged to existing.second
                            note(kind, k.toString(), MergeNote.Action.RECONCILED, "${names[existing.second]} + ${names[i]}: ${o.details.joinToString("; ")}")
                        }
                        is Outcome.Incompatible -> conflict(kind, k.toString(), existing.second, i, o.reason)
                    }
                }
            }
            return out.values.map { it.first }
        }

        private fun reconcileStop(a: GtfsStop, b: GtfsStop): Outcome<GtfsStop> {
            val problems = ArrayList<String>()
            val details = ArrayList<String>()
            if (a.locationType != b.locationType) problems += "location_type ${a.locationType} vs ${b.locationType}"
            if (a.name.normalizedName() != b.name.normalizedName()) problems += "name '${a.name}' vs '${b.name}'"

            val parent = pickOptional(a.parentStation, b.parentStation, "parent_station", problems, details)
            val platform = pickOptional(a.platformCode, b.platformCode, "platform_code", problems, details)

            var lat = a.lat
            var lon = a.lon
            val aMissing = a.lat == 0.0 && a.lon == 0.0
            val bMissing = b.lat == 0.0 && b.lon == 0.0
            when {
                aMissing && !bMissing -> {
                    lat = b.lat; lon = b.lon
                    details += "position taken from the second feed"
                }
                !aMissing && !bMissing && (a.lat != b.lat || a.lon != b.lon) -> {
                    val meters = Geo.haversineMeters(LatLng(a.lat, a.lon), LatLng(b.lat, b.lon))
                    val limit = if (a.locationType == 1) policy.stationToleranceMeters else policy.stopToleranceMeters
                    if (meters > limit) {
                        problems += "positions ${formatMeters(meters)} apart (limit ${formatMeters(limit)})"
                    } else {
                        details += "positions ${formatMeters(meters)} apart; kept ${a.lat},${a.lon}"
                    }
                }
            }

            val wheelchair = when {
                a.wheelchairBoarding == b.wheelchairBoarding -> a.wheelchairBoarding
                a.wheelchairBoarding == 0 -> b.wheelchairBoarding.also { details += "wheelchair_boarding filled ($it)" }
                b.wheelchairBoarding == 0 -> a.wheelchairBoarding
                else -> 0.also { details += "wheelchair_boarding ${a.wheelchairBoarding} vs ${b.wheelchairBoarding}: set to unknown" }
            }
            if (a.name != b.name && problems.isEmpty()) details += "name spelling '${a.name}' vs '${b.name}'; kept the first"

            if (problems.isNotEmpty()) return Outcome.Incompatible(problems.joinToString("; "))
            val merged = a.copy(lat = lat, lon = lon, parentStation = parent, platformCode = platform, wheelchairBoarding = wheelchair)
            return if (merged == a && details.isEmpty()) Outcome.Same else Outcome.Reconciled(merged, details)
        }

        private fun reconcileRoute(a: GtfsRoute, b: GtfsRoute): Outcome<GtfsRoute> {
            val problems = ArrayList<String>()
            if (a.type != b.type) problems += "route_type ${a.type} vs ${b.type}"
            if (a.shortName.trim() != b.shortName.trim()) problems += "route_short_name '${a.shortName}' vs '${b.shortName}'"
            if (a.agencyId != b.agencyId) problems += "agency ${a.agencyId} vs ${b.agencyId}"
            if (problems.isNotEmpty()) return Outcome.Incompatible(problems.joinToString("; "))
            val differing = buildList {
                if (a.longName != b.longName) add("route_long_name")
                if (a.color != b.color) add("route_color")
                if (a.textColor != b.textColor) add("route_text_color")
                if (a.sortOrder != b.sortOrder) add("route_sort_order")
            }
            return Outcome.Reconciled(a, listOf("${differing.joinToString()} differ; kept the first"))
        }

        private fun reconcileTransfer(a: GtfsTransfer, b: GtfsTransfer): Outcome<GtfsTransfer> {
            fun rank(t: GtfsTransfer) = when (t.transferType) {
                3 -> 4
                2 -> 3
                1 -> 2
                0 -> 1
                else -> 0
            }
            val keep = compareBy<GtfsTransfer>({ rank(it) }, { it.minTransferTime ?: -1 }).let { cmp -> if (cmp.compare(b, a) > 0) b else a }
            return Outcome.Reconciled(
                keep,
                listOf("type ${a.transferType}/${a.minTransferTime} vs ${b.transferType}/${b.minTransferTime}; kept ${keep.transferType}/${keep.minTransferTime}"),
            )
        }

        private fun mergeTrips(remapped: List<GtfsFeed>): Pair<List<GtfsTrip>, Map<String, List<GtfsStopTime>>> {
            val trips = LinkedHashMap<String, Pair<GtfsTrip, Int>>()
            val times = LinkedHashMap<String, List<GtfsStopTime>>()
            remapped.forEachIndexed { i, feed ->
                for (trip in feed.trips) {
                    val existing = trips[trip.id]
                    val stopTimes = feed.stopTimes[trip.id].orEmpty()
                    when {
                        existing == null -> {
                            trips[trip.id] = trip to i
                            times[trip.id] = stopTimes
                        }
                        existing.second == i -> Unit
                        existing.first == trip && times[trip.id] == stopTimes -> shared("trip")
                        else -> conflict(
                            "trip", trip.id, existing.second, i,
                            if (existing.first != trip) "trip details differ" else "stop times differ",
                        )
                    }
                }
                // Stop times for trips the feed doesn't list are kept as-is (the validator reports them).
                feed.stopTimes.forEach { (id, st) -> if (id !in trips) times.putIfAbsent(id, st) }
            }
            return trips.values.map { it.first } to times
        }

        private fun pickOptional(a: String?, b: String?, field: String, problems: MutableList<String>, details: MutableList<String>): String? {
            val x = a?.trim()?.takeIf { it.isNotEmpty() }
            val y = b?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                x == y -> a
                x == null -> b.also { details += "$field filled ($y)" }
                y == null -> a
                else -> a.also { problems += "$field '$x' vs '$y'" }
            }
        }

        private fun <T, K> distinctFirst(items: List<T>, key: (T) -> K): List<T> {
            val seen = HashSet<K>()
            return items.filter { seen.add(key(it)) }
        }
    }

    /** Lower case, with every run of non-letters/digits collapsed to one space. */
    internal fun String.normalizedName(): String =
        lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun formatMeters(m: Double): String = if (m < 1.0) "%.2f m".format(m) else "${m.roundToInt()} m"
}
