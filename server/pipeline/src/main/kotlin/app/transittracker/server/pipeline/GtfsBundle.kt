package app.transittracker.server.pipeline

import java.io.Closeable
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** A GTFS static bundle: a zip file or (for development and tests) a directory of `.txt` files. */
interface GtfsBundle : Closeable {
    /** Human-readable name used in validation messages and pack metadata. */
    val name: String

    /** Base names of the files present, e.g. `stops.txt`. */
    val files: Set<String>

    /** Opens [file] (a base name), or returns null if absent. */
    fun open(file: String): InputStream?

    companion object {
        /** Opens a zip file or a directory. */
        fun open(path: Path): GtfsBundle = when {
            path.isDirectory() -> DirectoryGtfsBundle(path)
            path.isRegularFile() -> ZipGtfsBundle(path)
            else -> throw IllegalArgumentException("No GTFS bundle at $path")
        }
    }
}

/**
 * A zipped bundle. Files may sit at the root or inside one folder; when a name occurs more than
 * once the shallowest entry wins. macOS metadata entries are ignored.
 */
class ZipGtfsBundle(path: Path) : GtfsBundle {
    private val zip = ZipFile(path.toFile())
    override val name: String = path.name

    private val entries = zip.entries().asSequence()
        .filter { !it.isDirectory && !it.name.startsWith("__MACOSX/") && !it.name.substringAfterLast('/').startsWith("._") }
        .groupBy { it.name.substringAfterLast('/') }
        .mapValues { (_, list) -> list.minBy { it.name.count { c -> c == '/' } } }

    override val files: Set<String> = entries.keys

    override fun open(file: String): InputStream? = entries[file]?.let { zip.getInputStream(it) }

    override fun close() = zip.close()
}

/** A directory of GTFS text files. */
class DirectoryGtfsBundle(private val dir: Path) : GtfsBundle {
    override val name: String = dir.name

    override val files: Set<String> = Files.list(dir).use { s ->
        s.filter { it.isRegularFile() }.map { it.name }.toList().toSet()
    }

    override fun open(file: String): InputStream? =
        dir.resolve(file).takeIf { file in files }?.let { Files.newInputStream(it) }

    override fun close() = Unit
}
