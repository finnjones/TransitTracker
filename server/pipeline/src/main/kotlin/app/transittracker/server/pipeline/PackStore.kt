package app.transittracker.server.pipeline

import app.transittracker.model.PackInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.name

/** One input of a pack build: the source path (or local file name) and the SHA-256 of its bytes. */
@Serializable
data class SourceRef(val source: String, val sha256: String)

/**
 * The small JSON file published next to each pack version. Its presence marks the version as
 * published; it is written last (atomically) and deleted first.
 */
@Serializable
data class PackManifest(
    val info: PackInfo,
    val builtAtMillis: Long,
    val sources: List<SourceRef> = emptyList(),
    val stats: Map<String, Int> = emptyMap(),
)

/**
 * Published packs on disk:
 * ```
 * <root>/<name>/<version>.sqlite       uncompressed, opened by the server
 * <root>/<name>/<version>.sqlite.zst   served to phones
 * <root>/<name>/<version>.json         PackManifest
 * <root>/.staging/...                  builds in progress
 * ```
 */
class PackStore(private val root: Path, private val keepVersions: Int) {
    private val log = LoggerFactory.getLogger(PackStore::class.java)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun sqlitePath(name: String, version: String): Path = root.resolve(name).resolve("$version.sqlite")

    fun compressedPath(name: String, version: String): Path = root.resolve(name).resolve("$version.sqlite.zst")

    private fun manifestPath(name: String, version: String): Path = root.resolve(name).resolve("$version.json")

    /** A fresh, empty staging directory on the same filesystem as the published packs. */
    fun newStagingDir(): Path = Files.createDirectories(root.resolve(".staging").resolve(UUID.randomUUID().toString()))

    /** Removes leftovers of interrupted builds. */
    fun cleanStaging() {
        root.resolve(".staging").toFile().deleteRecursively()
    }

    /** Names of packs with a folder in the store. */
    fun names(): List<String> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { s ->
            s.filter { Files.isDirectory(it) && !it.name.startsWith(".") }.map { it.name }.sorted().toList()
        }
    }

    /** Published versions of [name] whose files are all present, newest first. */
    fun manifests(name: String): List<PackManifest> {
        val dir = root.resolve(name)
        if (!Files.isDirectory(dir)) return emptyList()
        val list = Files.list(dir).use { s -> s.filter { it.name.endsWith(".json") }.toList() }
        return list.mapNotNull { file ->
            try {
                json.decodeFromString(PackManifest.serializer(), Files.readString(file))
            } catch (e: Exception) {
                log.warn("Ignoring unreadable pack manifest {}", file, e)
                null
            }
        }.filter { Files.isRegularFile(sqlitePath(name, it.info.version)) && Files.isRegularFile(compressedPath(name, it.info.version)) }
            .sortedWith(compareByDescending<PackManifest> { it.builtAtMillis }.thenByDescending { it.info.version })
    }

    /** True if any file of [version] exists (published or not). */
    fun versionExists(name: String, version: String): Boolean =
        Files.exists(manifestPath(name, version)) || Files.exists(sqlitePath(name, version)) || Files.exists(compressedPath(name, version))

    /** Moves the artifacts into place, then writes the manifest. */
    fun publish(artifacts: PackArtifacts, manifest: PackManifest) {
        val name = manifest.info.name
        val version = manifest.info.version
        Files.createDirectories(root.resolve(name))
        Files.move(artifacts.sqlite, sqlitePath(name, version), StandardCopyOption.ATOMIC_MOVE)
        Files.move(artifacts.compressed, compressedPath(name, version), StandardCopyOption.ATOMIC_MOVE)
        val tmp = Files.createTempFile(root.resolve(name), ".$version", ".json.tmp")
        Files.writeString(tmp, json.encodeToString(PackManifest.serializer(), manifest))
        Files.move(tmp, manifestPath(name, version), StandardCopyOption.ATOMIC_MOVE)
    }

    /** Deletes a version: manifest first, so a half-deleted version is never seen as published. */
    fun delete(name: String, version: String) {
        Files.deleteIfExists(manifestPath(name, version))
        Files.deleteIfExists(compressedPath(name, version))
        Files.deleteIfExists(sqlitePath(name, version))
    }

    /** Keeps the newest `keepVersions` published versions of [name] plus [protect]; deletes the rest. */
    fun prune(name: String, protect: Set<String> = emptySet()) {
        manifests(name).drop(keepVersions).filter { it.info.version !in protect }.forEach {
            log.info("Deleting old pack {}/{}", name, it.info.version)
            delete(name, it.info.version)
        }
    }

    companion object {
        private val safe = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")

        /** Pack names and versions are path components: letters, digits, `.`, `_`, `-`, no `..`. */
        fun isSafeComponent(text: String): Boolean = safe.matches(text) && ".." !in text
    }
}
