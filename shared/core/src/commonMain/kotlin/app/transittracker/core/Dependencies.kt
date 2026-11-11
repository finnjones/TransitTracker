package app.transittracker.core

import app.transittracker.model.Timetable
import app.transittracker.model.WallClock
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Small persistent string store: device token and id, saved trips JSON, cached watches,
 * installed pack records and settings. Android: DataStore/SharedPreferences; iOS: NSUserDefaults;
 * JVM: [FileKeyValueStore]; tests: [InMemoryKeyValueStore].
 */
interface KeyValueStore {
    suspend fun get(key: String): String?

    /** Stores [value], or removes the key when [value] is null. */
    suspend fun put(key: String, value: String?)
}

/**
 * Storage for timetable pack files. Paths are opaque platform strings handed to [TimetableOpener].
 */
interface FileStore {
    /** Writes [bytes] to a new temporary file next to the pack directory and returns its path. */
    suspend fun writeTemp(bytes: ByteArray): String

    /**
     * Atomically moves the temporary file at [tempPath] to [name] (replacing any existing file),
     * so a reader never sees a partly written pack. Returns the final path.
     */
    suspend fun replace(tempPath: String, name: String): String

    /** Path for a pack file [name] (whether or not it exists). */
    fun resolve(name: String): String

    suspend fun exists(name: String): Boolean

    /** Deletes [name] if present; also accepts a temp path returned by [writeTemp]. */
    suspend fun delete(name: String)
}

/** Opens a timetable pack file (SQLite) at a [FileStore] path. Implemented by `:shared:timetable`. */
fun interface TimetableOpener {
    fun open(path: String): Timetable
}

/** zstd decompression of downloaded packs. JVM: [ZstdDecompressor]. */
fun interface Decompressor {
    fun decompress(input: ByteArray): ByteArray
}

/** Network state used by the pack updater's Wi-Fi-only policy. */
fun interface NetworkStatus {
    /** True on Wi-Fi / Ethernet or any connection the user does not pay per byte for. */
    fun isUnmetered(): Boolean
}

/** Static configuration for [TransitCoreFactory.create]. */
data class CoreConfig(
    /** tt-server base URL, e.g. `https://tt.example.ts.net`. */
    val baseUrl: String,
    val appVersion: String,
    /** "android" or "ios". */
    val platform: String,
    /** Packs every phone installs (as well as any already installed): trains, metro and light rail; buses. */
    val defaultPacks: List<String> = listOf("syd-rail", "syd-bus"),
    /** Only download pack updates on unmetered networks (first install is always allowed). */
    val wifiOnlyPackUpdates: Boolean = true,
    /** How often to check `/v1/config` for new packs. */
    val packCheckIntervalMs: Long = 6 * 3600_000L,
)

/** Everything the core needs from the platform. Every coroutine runs on [scope]. */
data class CoreDependencies(
    val httpClient: HttpClient,
    val kv: KeyValueStore,
    val files: FileStore,
    val opener: TimetableOpener,
    val decompressor: Decompressor,
    val network: NetworkStatus,
    val clock: WallClock,
    val scope: CoroutineScope,
    /** Where blocking work (timetable queries, decompression, hashing) runs. */
    val ioContext: CoroutineContext = Dispatchers.Default,
)
