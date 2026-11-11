package app.transittracker.server.pipeline

import app.transittracker.model.WallClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.io.path.name

/** A GTFS zip on disk, as fetched for one source path. */
data class ArchivedBundle(val path: String, val file: Path, val sha256: String, val changed: Boolean)

/** What the archive remembers per source path. */
@Serializable
data class ArchivedSourceState(
    val etag: String? = null,
    val lastModified: String? = null,
    val sha256: String,
    /** File name of the newest archived zip, relative to the source's folder. */
    val file: String,
)

/**
 * Keeps raw GTFS zips under [dir] (one folder per source path) with the HTTP validators of the
 * newest one, so that fetches are conditional and unchanged bundles are not re-downloaded or
 * rebuilt. Zips older than [retentionDays] are pruned, except the newest of each source.
 */
class GtfsArchive(private val dir: Path, private val retentionDays: Int, private val clock: WallClock) {
    private val log = LoggerFactory.getLogger(GtfsArchive::class.java)
    private val json = Json { prettyPrint = true }
    private val stateSerializer = MapSerializer(String.serializer(), ArchivedSourceState.serializer())
    private val stateFile = dir.resolve("state.json")
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC)

    @Volatile
    private var state: Map<String, ArchivedSourceState> = loadState()

    private fun loadState(): Map<String, ArchivedSourceState> = try {
        if (Files.exists(stateFile)) json.decodeFromString(stateSerializer, Files.readString(stateFile)) else emptyMap()
    } catch (e: Exception) {
        log.warn("Ignoring unreadable GTFS archive state {}", stateFile, e)
        emptyMap()
    }

    private fun saveState() {
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".state", ".tmp")
        Files.writeString(tmp, json.encodeToString(stateSerializer, state.toSortedMap()))
        Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Folder name for a source path, e.g. `/v1/gtfs/schedule/metro` -> `v1_gtfs_schedule_metro`. */
    fun folderFor(path: String): Path = dir.resolve(path.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "root" })

    /** The newest archived zip for [path], if any. */
    fun latest(path: String): ArchivedBundle? {
        val st = state[path] ?: return null
        val file = folderFor(path).resolve(st.file)
        return if (Files.isRegularFile(file)) ArchivedBundle(path, file, st.sha256, changed = false) else null
    }

    /**
     * Conditionally fetches [path] from [source]. Returns the newest archived copy, flagged
     * [ArchivedBundle.changed] when new bytes were stored. When the source fails but a copy is
     * archived, that copy is returned (unchanged), so a pack can still be built from it.
     */
    suspend fun fetch(source: GtfsSource, path: String): ArchivedBundle {
        val previous = latest(path)
        val validators = state[path]?.takeIf { previous != null }?.let { CacheValidators(it.etag, it.lastModified) }
        val folder = folderFor(path)
        Files.createDirectories(folder)
        val tmp = folder.resolve(".download-${UUID.randomUUID()}.tmp")
        try {
            val result = try {
                source.fetch(path, validators, tmp)
            } catch (e: IOException) {
                if (previous == null) throw e
                log.warn("Fetching GTFS {} failed ({}); using the archived copy {}", path, e.message, previous.file.name)
                return previous
            }
            when (result) {
                FetchResult.NotModified -> {
                    return previous ?: throw IOException("Source said $path is not modified but no archived copy exists")
                }
                is FetchResult.Downloaded -> {
                    val sha = PackBuilder.sha256(tmp)
                    if (previous != null && previous.sha256 == sha) {
                        state = state + (path to state.getValue(path).copy(etag = result.validators.etag, lastModified = result.validators.lastModified))
                        saveState()
                        return previous
                    }
                    val name = "${stamp.format(Instant.ofEpochMilli(clock.nowMillis()))}-${sha.take(12)}.zip"
                    val target = folder.resolve(name)
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    // Retention is measured on the pipeline clock, not the filesystem's.
                    Files.setLastModifiedTime(target, FileTime.fromMillis(clock.nowMillis()))
                    state = state + (path to ArchivedSourceState(result.validators.etag, result.validators.lastModified, sha, name))
                    saveState()
                    log.info("Archived new GTFS for {} ({} bytes, sha256 {})", path, Files.size(target), sha.take(12))
                    return ArchivedBundle(path, target, sha, changed = true)
                }
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Deletes archived zips older than the retention period, keeping the newest per source. */
    fun prune() {
        if (!Files.isDirectory(dir)) return
        val cutoff = clock.nowMillis() - retentionDays * 24L * 3600 * 1000
        val keep = state.map { (path, st) -> folderFor(path).resolve(st.file).toAbsolutePath() }.toSet()
        Files.list(dir).use { folders ->
            folders.filter { Files.isDirectory(it) }.forEach { folder ->
                Files.list(folder).use { files ->
                    files.filter { it.name.endsWith(".zip") && it.toAbsolutePath() !in keep }
                        .filter { Files.getLastModifiedTime(it).toMillis() < cutoff }
                        .forEach {
                            log.info("Pruning archived GTFS {}", it)
                            Files.deleteIfExists(it)
                        }
                }
            }
        }
    }
}
