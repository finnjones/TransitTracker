package app.transittracker.core

import android.content.Context
import android.net.ConnectivityManager
import app.transittracker.model.Timetable
import app.transittracker.timetable.AndroidTimetables
import com.github.luben.zstd.ZstdInputStream
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

// Android implementations of what CoreDependencies asks for. Plain java.io where no Context is
// needed (so they also run in Robolectric tests); only APIs available at minSdk 26.

/** zstd decompression via zstd-jni's Android build (streaming, so the frame need not record its size). */
object AndroidZstdDecompressor : Decompressor {
    override fun decompress(input: ByteArray): ByteArray =
        ZstdInputStream(ByteArrayInputStream(input)).use { zin ->
            val out = ByteArrayOutputStream(maxOf(input.size * 4, 64 * 1024))
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = zin.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
}

/** [KeyValueStore] backed by one properties file, rewritten atomically (temp file + rename) on every change. */
class PropertiesKeyValueStore(private val file: File) : KeyValueStore {
    private val mutex = Mutex()
    private var cache: Properties? = null

    override suspend fun get(key: String): String? = mutex.withLock { load().getProperty(key) }

    override suspend fun put(key: String, value: String?) {
        mutex.withLock {
            val props = load()
            if (value == null) props.remove(key) else props.setProperty(key, value)
            withContext(Dispatchers.IO) {
                val dir = file.absoluteFile.parentFile ?: throw IOException("No directory for $file")
                writeAtomically(dir, file) { out -> props.store(out, null) }
            }
        }
    }

    private suspend fun load(): Properties = cache ?: withContext(Dispatchers.IO) {
        Properties().also { p -> if (file.isFile) file.inputStream().use { p.load(it) } }
    }.also { cache = it }
}

/**
 * A [KeyValueStore] over two stores: keys for which [toFirst] is true go to [first], all others
 * to [second]. The Android app keeps display settings, trips and places in a file that may be
 * backed up and everything else (the device token above all) in one that never is.
 */
class SplitKeyValueStore(
    private val first: KeyValueStore,
    private val second: KeyValueStore,
    private val toFirst: (String) -> Boolean,
) : KeyValueStore {
    override suspend fun get(key: String): String? = (if (toFirst(key)) first else second).get(key)

    override suspend fun put(key: String, value: String?) = (if (toFirst(key)) first else second).put(key, value)
}

/** [FileStore] over a directory; temp files live in `<root>/tmp` so renames stay on one file system. */
class AndroidFileStore(private val root: File) : FileStore {
    override suspend fun writeTemp(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val dir = File(root, "tmp")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val tmp = File.createTempFile("pack", ".part", dir)
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        tmp.path
    }

    override suspend fun replace(tempPath: String, name: String): String = withContext(Dispatchers.IO) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create $root")
        val target = File(root, name)
        // rename(2) replaces the target atomically on the same file system.
        if (!File(tempPath).renameTo(target)) throw IOException("Cannot move $tempPath to $target")
        target.path
    }

    override fun resolve(name: String): String = File(root, name).path

    override suspend fun exists(name: String): Boolean = withContext(Dispatchers.IO) { fileFor(name).exists() }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) { fileFor(name).delete() }
    }

    /** A name inside [root], or an absolute path (a temp path from [writeTemp]). */
    private fun fileFor(name: String): File = File(name).let { if (it.isAbsolute) it else File(root, name) }
}

/** Unmetered (Wi-Fi, Ethernet, unmetered mobile plans) or not, from the active network. */
class AndroidNetworkStatus(context: Context) : NetworkStatus {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun isUnmetered(): Boolean {
        val cm = connectivity ?: return false
        if (cm.activeNetwork == null) return false
        return !cm.isActiveNetworkMetered
    }
}

/** Default Android HTTP client (OkHttp engine). */
fun androidHttpClient(): HttpClient = HttpClient(OkHttp)

/** Opens downloaded packs with the `:shared:timetable` read-only framework SQLite driver. */
object AndroidTimetableOpener : TimetableOpener {
    override fun open(path: String): Timetable = AndroidTimetables.open(path)
}

private fun writeAtomically(dir: File, target: File, write: (FileOutputStream) -> Unit) {
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
    val tmp = File.createTempFile(target.name, ".tmp", dir)
    try {
        FileOutputStream(tmp).use { out ->
            write(out)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) throw IOException("Cannot replace $target")
    } catch (e: Throwable) {
        tmp.delete()
        throw e
    }
}
