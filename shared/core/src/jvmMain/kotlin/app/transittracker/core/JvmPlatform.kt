package app.transittracker.core

import app.transittracker.model.Timetable
import app.transittracker.timetable.JvmTimetables
import com.github.luben.zstd.ZstdInputStream
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/** zstd decompression via zstd-jni (streaming, so the frame need not record its content size). */
object ZstdDecompressor : Decompressor {
    override fun decompress(input: ByteArray): ByteArray =
        ZstdInputStream(ByteArrayInputStream(input)).use { it.readAllBytes() }
}

/** [KeyValueStore] backed by one properties file, rewritten atomically on every change. */
class FileKeyValueStore(private val file: Path) : KeyValueStore {
    private val mutex = Mutex()
    private var cache: Properties? = null

    override suspend fun get(key: String): String? = mutex.withLock { load().getProperty(key) }

    override suspend fun put(key: String, value: String?) {
        mutex.withLock {
            val props = load()
            if (value == null) props.remove(key) else props.setProperty(key, value)
            withContext(Dispatchers.IO) {
                val dir = file.toAbsolutePath().parent
                Files.createDirectories(dir)
                val tmp = Files.createTempFile(dir, file.fileName.toString(), ".tmp")
                Files.newOutputStream(tmp).use { props.store(it, null) }
                moveAtomically(tmp, file)
            }
        }
    }

    private suspend fun load(): Properties = cache ?: withContext(Dispatchers.IO) {
        Properties().also { p -> if (Files.exists(file)) Files.newInputStream(file).use { p.load(it) } }
    }.also { cache = it }
}

/** [FileStore] over a directory; temp files live in `<root>/tmp` so moves stay on one file system. */
class JvmFileStore(private val root: Path) : FileStore {
    override suspend fun writeTemp(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val dir = Files.createDirectories(root.resolve("tmp"))
        val tmp = Files.createTempFile(dir, "pack", ".part")
        Files.write(tmp, bytes)
        tmp.toString()
    }

    override suspend fun replace(tempPath: String, name: String): String = withContext(Dispatchers.IO) {
        Files.createDirectories(root)
        val target = root.resolve(name)
        moveAtomically(Path.of(tempPath), target)
        target.toString()
    }

    override fun resolve(name: String): String = root.resolve(name).toString()

    override suspend fun exists(name: String): Boolean = withContext(Dispatchers.IO) { Files.exists(root.resolve(name)) }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) { Files.deleteIfExists(root.resolve(name)) }
    }
}

/** A [NetworkStatus] with a fixed answer (desktop / tests). */
class StaticNetworkStatus(private val unmetered: Boolean = true) : NetworkStatus {
    override fun isUnmetered(): Boolean = unmetered
}

/** Default JVM HTTP client (CIO engine). */
fun defaultHttpClient(): HttpClient = HttpClient(CIO)

private fun moveAtomically(from: Path, to: Path) {
    try {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
    }
}

/** Opens downloaded packs with the `:shared:timetable` SQLite reader (read-only, pooled connections). */
object JvmTimetableOpener : TimetableOpener {
    override fun open(path: String): Timetable = JvmTimetables.open(Path.of(path))
}
