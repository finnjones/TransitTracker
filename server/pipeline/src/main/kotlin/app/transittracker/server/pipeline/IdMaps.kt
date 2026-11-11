package app.transittracker.server.pipeline

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * GTFS id -> compact pack integer id assignments for one pack, kept across versions so the
 * same stop, route or shape keeps its integer id from one nightly build to the next (which
 * keeps packs diff-friendly for future patch updates). New ids are appended after the current
 * maximum, in sorted GTFS-id order, so assignment is deterministic.
 */
@Serializable
data class PackIdMaps(
    val stops: Map<String, Int> = emptyMap(),
    val routes: Map<String, Int> = emptyMap(),
    val shapes: Map<String, Int> = emptyMap(),
) {
    /** Returns the maps extended with ids for every GTFS id in the arguments. */
    fun extend(stopIds: Collection<String>, routeIds: Collection<String>, shapeIds: Collection<String>) = PackIdMaps(
        stops = assign(stops, stopIds),
        routes = assign(routes, routeIds),
        shapes = assign(shapes, shapeIds),
    )
}

private fun assign(existing: Map<String, Int>, ids: Collection<String>): Map<String, Int> {
    val out = HashMap(existing)
    var next = (existing.values.maxOrNull() ?: 0) + 1
    for (id in ids.filterNot { it in existing }.distinct().sorted()) out[id] = next++
    return out
}

/** Persists [PackIdMaps] as one JSON file per pack under [dir] (written atomically). */
class IdMapStore(private val dir: Path) {
    private val json = Json { prettyPrint = false }

    private fun file(pack: String) = dir.resolve("$pack.json")

    fun load(pack: String): PackIdMaps {
        val f = file(pack)
        return if (Files.exists(f)) json.decodeFromString(PackIdMaps.serializer(), Files.readString(f)) else PackIdMaps()
    }

    fun save(pack: String, maps: PackIdMaps) {
        Files.createDirectories(dir)
        val sorted = PackIdMaps(maps.stops.toSortedMap(), maps.routes.toSortedMap(), maps.shapes.toSortedMap())
        val tmp = Files.createTempFile(dir, ".$pack", ".tmp")
        Files.writeString(tmp, json.encodeToString(PackIdMaps.serializer(), sorted))
        Files.move(tmp, file(pack), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
