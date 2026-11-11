package app.transittracker.server.live

import app.transittracker.model.ServiceTime
import app.transittracker.model.WallClock
import app.transittracker.server.spi.FeedDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Saves sampled raw feed bytes for replay and debugging: at most one file per [minIntervalMs] per
 * feed + kind, under `<dir>/<YYYYMMDD>/<feedId>-<kind>-<epochMs>.pb.gz` (Sydney date), keeping
 * [retentionDays] days.
 */
class FeedRecorder(
    private val dir: Path,
    private val clock: WallClock,
    private val minIntervalMs: Long = 60_000,
    private val retentionDays: Int = 14,
) {
    private val lastRecorded = ConcurrentHashMap<String, Long>()
    @Volatile private var lastPrunedDay: String? = null

    /** Records [bytes] unless this feed + kind was recorded less than [minIntervalMs] ago. Returns the file written. */
    suspend fun record(feedId: String, kind: FeedKind, bytes: ByteArray): Path? {
        val now = clock.nowMillis()
        val key = "$feedId-${kind.id}"
        var due = false
        lastRecorded.compute(key) { _, last ->
            if (last == null || now - last >= minIntervalMs) {
                due = true
                now
            } else {
                last
            }
        }
        if (!due) return null
        return withContext(Dispatchers.IO) {
            try {
                val day = ServiceTime.localDate(now)
                if (lastPrunedDay != day) {
                    prune()
                    lastPrunedDay = day
                }
                val dayDir = dir.resolve(day)
                Files.createDirectories(dayDir)
                val file = dayDir.resolve("$key-$now.pb.gz")
                GZIPOutputStream(Files.newOutputStream(file)).use { it.write(bytes) }
                file
            } catch (e: Exception) {
                log.warn("Could not record {} feed: {}", key, e.toString())
                null
            }
        }
    }

    /** Deletes day directories older than the retention period. Returns the number of directories removed. */
    fun prune(): Int {
        if (!Files.isDirectory(dir)) return 0
        val today = LocalDate.parse(ServiceTime.localDate(clock.nowMillis()), DateTimeFormatter.BASIC_ISO_DATE)
        val oldestKept = today.minusDays(retentionDays.toLong() - 1)
        var removed = 0
        Files.list(dir).use { stream ->
            stream.filter { it.isDirectory() && DAY_DIR.matches(it.name) }.forEach { dayDir ->
                val date = LocalDate.parse(dayDir.name, DateTimeFormatter.BASIC_ISO_DATE)
                if (date.isBefore(oldestKept)) {
                    dayDir.toFile().deleteRecursively()
                    removed++
                }
            }
        }
        if (removed > 0) log.info("Removed {} recording day(s) older than {} days", removed, retentionDays)
        return removed
    }

    private companion object {
        val log = LoggerFactory.getLogger(FeedRecorder::class.java)
        val DAY_DIR = Regex("""\d{8}""")
    }
}

/**
 * Serves recorded or fixture feed files instead of calling TfNSW, for tests and for local
 * development without an API key.
 *
 * Files are found recursively under [root] and named `<feedId>-<kind>.pb[.gz]` (a fixed fixture)
 * or `<feedId>-<kind>-<epochMs>.pb[.gz]` (a recording). Recordings are served in time order, one per
 * call; after the last one it starts again if [loop], else keeps serving the last.
 */
class ReplayFetcher(private val root: Path, private val loop: Boolean = true) : FeedFetcher {
    override val isRemote: Boolean get() = false

    private val files: Map<String, List<Path>> = index()
    private val positions = ConcurrentHashMap<String, Int>()

    override suspend fun fetch(feed: FeedDefinition, kind: FeedKind): FetchResult {
        val key = "${feed.id}-${kind.id}"
        val list = files[key]
        if (list.isNullOrEmpty()) return FetchResult.NotConfigured("no recording for $key under $root")
        val index = positions.compute(key) { _, current ->
            when {
                current == null -> 0
                current + 1 < list.size -> current + 1
                loop -> 0
                else -> current
            }
        }!!
        val file = list[index]
        return withContext(Dispatchers.IO) {
            val bytes = if (file.name.endsWith(".gz")) {
                GZIPInputStream(Files.newInputStream(file)).use { it.readBytes() }
            } else {
                Files.readAllBytes(file)
            }
            FetchResult.Success(bytes)
        }
    }

    private fun index(): Map<String, List<Path>> {
        if (!Files.isDirectory(root)) return emptyMap()
        val found = HashMap<String, MutableList<Pair<Long, Path>>>()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { path ->
                val match = FILE.matchEntire(path.name) ?: return@forEach
                val (feedId, kind, time) = match.destructured
                found.getOrPut("$feedId-$kind") { ArrayList() } += (time.toLongOrNull() ?: 0L) to path
            }
        }
        return found.mapValues { (_, v) -> v.sortedWith(compareBy({ it.first }, { it.second.toString() })).map { it.second } }
    }

    private companion object {
        val FILE = Regex("""(.+)-(tripupdates|vehicles|alerts)(?:-(\d+))?\.pb(?:\.gz)?""")
    }
}
