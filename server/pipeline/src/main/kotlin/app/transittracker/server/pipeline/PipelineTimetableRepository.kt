package app.transittracker.server.pipeline

import app.transittracker.model.Mode
import app.transittracker.model.PackInfo
import app.transittracker.model.Timetable
import app.transittracker.model.WallClock
import app.transittracker.server.spi.RefreshResult
import app.transittracker.server.spi.ServerConfig
import app.transittracker.server.spi.TimetableRepository
import app.transittracker.timetable.CompositeTimetable
import app.transittracker.timetable.JvmTimetables
import app.transittracker.timetable.TimetablePack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * [TimetableRepository] backed by the nightly GTFS pipeline (plan section 8.1).
 *
 * - [start] loads the newest published version of every pack in `config.packsDir`, then runs
 *   [refresh] every night at `config.nightlyBuildHour` (Sydney time), and once at start if a
 *   configured pack has never been built. While one still has not been built, it tries again
 *   after `options.missingPackRetry`, doubling up to `options.missingPackRetryMax`.
 * - [refresh] conditionally fetches each `PackDefinition.gtfsPaths` through [source], archives
 *   new zips, and rebuilds a pack only when its inputs changed: load, validate, merge, build,
 *   compress, publish atomically, open, then swap [current]. Any failure keeps the previous pack.
 * - [importLocal] builds a pack from local zips or directories (development, tests, `tt-admin`).
 *
 * [current] is always a [CompositeTimetable] of the loaded packs (config order first).
 *
 * @param source where GTFS comes from; null (no TfNSW API key) makes [refresh] fail gracefully.
 */
class PipelineTimetableRepository(
    private val config: ServerConfig,
    private val source: GtfsSource? = config.tfnswApiKey?.let { HttpGtfsSource(config.tfnswBaseUrl, it) },
    private val options: PipelineOptions = PipelineOptions.fromEnv(),
    private val clock: WallClock = WallClock { System.currentTimeMillis() },
) : TimetableRepository, AutoCloseable {
    private val log = LoggerFactory.getLogger(PipelineTimetableRepository::class.java)
    private val store = PackStore(config.packsDir, options.keepVersions)
    private val archive = GtfsArchive(config.gtfsArchiveDir, options.archiveRetentionDays, clock)
    private val idMapStore = IdMapStore(config.dataDir.resolve("idmaps"))
    private val externalValidator = options.gtfsValidatorJar?.let { MobilityDataValidator(it, options.gtfsValidatorTimeout) }

    private class LoadedPack(val manifest: PackManifest, val timetable: TimetablePack)

    /** Guards builds, publication and [loaded]. Never held across a suspension point. */
    private val buildLock = Any()
    private val refreshMutex = Mutex()

    @Volatile
    private var loaded: Map<String, LoadedPack> = emptyMap()

    /** Inputs of the last failed build per pack, so identical bad input is not rebuilt nightly. */
    private val failedInputs = ConcurrentHashMap<String, List<SourceRef>>()

    /** Plain sentence for the last failed refresh of each pack, for [problems]. */
    private val lastFailure = ConcurrentHashMap<String, String>()

    @Volatile
    private var refreshing = false

    @Volatile
    private var scope: CoroutineScope? = null

    private val currentFlow = MutableStateFlow<Timetable?>(null)
    override val current: StateFlow<Timetable?> = currentFlow.asStateFlow()

    override fun packs(): List<PackInfo> {
        val snapshot = loaded
        return orderedNames(snapshot.keys).map { snapshot.getValue(it).manifest.info }
    }

    override fun packFile(name: String, version: String): Path? {
        if (!PackStore.isSafeComponent(name) || !PackStore.isSafeComponent(version)) return null
        if (store.manifests(name).none { it.info.version == version }) return null
        return store.compressedPath(name, version).takeIf { Files.isRegularFile(it) }
    }

    /** Opens the newest published version of every pack on disk that is not loaded yet. */
    fun loadPublished() {
        synchronized(buildLock) {
            store.cleanStaging()
            val next = HashMap(loaded)
            for (name in store.names()) {
                if (name in next) continue
                for (manifest in store.manifests(name)) {
                    val version = manifest.info.version
                    try {
                        next[name] = LoadedPack(manifest, JvmTimetables.open(store.sqlitePath(name, version)))
                        log.info("Loaded timetable pack {}/{}", name, version)
                        break
                    } catch (e: Exception) {
                        log.error("Cannot open pack {}/{}; trying an older version", name, version, e)
                    }
                }
            }
            loaded = next
            publishCurrent()
        }
    }

    override fun start(scope: CoroutineScope) {
        this.scope = scope
        loadPublished()
        scope.launch(CoroutineName("timetable-nightly")) {
            if (source != null && missingPacks().isNotEmpty()) refreshLogged("startup")
            var retry = options.missingPackRetry
            while (isActive) {
                val now = clock.nowMillis()
                val untilNightly = nextNightlyRunMillis(now) - now
                val missing = if (source != null) missingPacks() else emptyList()
                if (missing.isNotEmpty() && retry.inWholeMilliseconds < untilNightly) {
                    log.warn("No timetable yet for {}; trying again in {}", missing.joinToString(), retry)
                    delay(retry)
                    retry = (retry * 2).coerceAtMost(options.missingPackRetryMax)
                    refreshLogged("retry")
                } else {
                    retry = options.missingPackRetry
                    delay(untilNightly.coerceAtLeast(1_000L))
                    refreshLogged("nightly")
                }
            }
        }
    }

    override fun problems(): Map<String, String> {
        val missing = missingPacks()
        if (missing.isEmpty()) return emptyMap()
        return missing.associateWith { name ->
            when {
                source == null -> NO_API_KEY
                refreshing && !lastFailure.containsKey(name) -> "The server is building its first timetable from TfNSW's data now. This takes a minute or two."
                else -> lastFailure[name] ?: "The server has not built this timetable yet. It tries after it starts and then nightly."
            }
        }
    }

    /** Configured packs with no published version loaded. */
    private fun missingPacks(): List<String> = config.packs.map { it.name }.filter { it !in loaded }

    private suspend fun refreshLogged(reason: String) {
        try {
            val result = refresh()
            log.info("Timetable refresh ({}): rebuilt={} unchanged={} failed={}", reason, result.rebuilt, result.unchanged, result.failed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Timetable refresh ({}) failed", reason, e)
        }
    }

    /** Epoch millis of the next `nightlyBuildHour`:00 in Sydney strictly after [nowMillis]. */
    fun nextNightlyRunMillis(nowMillis: Long): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(SYDNEY)
        var next = now.truncatedTo(ChronoUnit.DAYS).withHour(config.nightlyBuildHour.coerceIn(0, 23))
        if (!next.isAfter(now)) next = ZonedDateTime.of(now.toLocalDate().plusDays(1), next.toLocalTime(), SYDNEY)
        return next.toInstant().toEpochMilli()
    }

    override suspend fun refresh(force: Boolean): RefreshResult = refreshMutex.withLock {
        val src = source
        if (src == null) {
            log.warn("No TfNSW API key configured (TFNSW_API_KEY): cannot fetch GTFS; keeping current packs")
            return RefreshResult(emptyList(), emptyList(), config.packs.associate { it.name to "TfNSW API key not configured" })
        }
        val rebuilt = ArrayList<String>()
        val unchanged = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()
        refreshing = true
        try {
            for (def in config.packs) {
                var refs: List<SourceRef>? = null
                try {
                    val inputs = def.gtfsPaths.map { archive.fetch(src, it) }
                    refs = inputs.map { SourceRef(it.path, it.sha256) }
                    val published = loaded[def.name]?.manifest
                    when {
                        !force && published != null && published.sources == refs -> {
                            log.info("Timetable {} unchanged ({})", def.name, published.info.version)
                            lastFailure.remove(def.name)
                            unchanged += def.name
                        }
                        !force && failedInputs[def.name] == refs ->
                            failed[def.name] = "GTFS unchanged since the last failed build"
                        else -> {
                            withContext(Dispatchers.IO) { buildAndPublish(def.name, inputs.map { it.file }, refs, def.modes) }
                            failedInputs.remove(def.name)
                            lastFailure.remove(def.name)
                            rebuilt += def.name
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (refs != null && e is PackBuildException) failedInputs[def.name] = refs
                    failed[def.name] = e.message ?: e.javaClass.simpleName
                    lastFailure[def.name] = describeFailure(e)
                    log.error("Timetable refresh of {} failed; keeping the previous pack", def.name, e)
                }
            }
        } finally {
            refreshing = false
        }
        try {
            archive.prune()
        } catch (e: Exception) {
            log.warn("Pruning the GTFS archive failed", e)
        }
        RefreshResult(rebuilt, unchanged, failed)
    }

    /**
     * Builds and publishes [packName] from local GTFS zips (or directories) and makes it current.
     * Throws [PackBuildException] if the input fails validation; nothing is published then.
     */
    fun importLocal(packName: String, zips: List<Path>): PackInfo {
        require(zips.isNotEmpty()) { "No GTFS inputs given" }
        val refs = zips.map { SourceRef(it.name, hashInput(it)) }
        val modes = config.packs.firstOrNull { it.name == packName }?.modes
        return buildAndPublish(packName, zips, refs, modes).info
    }

    private fun buildAndPublish(name: String, inputs: List<Path>, refs: List<SourceRef>, modes: Set<Mode>?): PackManifest =
        synchronized(buildLock) {
            require(PackStore.isSafeComponent(name)) { "Bad pack name '$name'" }
            val startedNanos = System.nanoTime()
            val now = clock.nowMillis()
            val today = sydneyDate(now)
            val report = ValidationReport.Builder()
            val feeds = inputs.map { path -> GtfsBundle.open(path).use { GtfsLoader.load(it, report) } }
            externalValidator?.let { v -> inputs.filter { Files.isRegularFile(it) }.forEach { v.validate(it, report) } }
            val merged = try {
                GtfsMerger.mergeWithReport(feeds)
            } catch (e: ConflictingIdException) {
                e.conflicts.forEach { report.error("merge_conflict", "Conflicting $it") }
                log.error("Cannot merge GTFS for {}: {} conflicting id(s)", name, e.conflicts.size)
                e.conflicts.take(50).forEach { log.error("  {}", it) }
                throw PackBuildException("Cannot merge GTFS for $name: ${e.message}", report.build(), e)
            }
            merged.report.recordIn(report)
            if (merged.report.notes.isNotEmpty()) {
                log.info("Merged GTFS for {}: shared ids {}, {} reconciled, {} renamed", name, merged.report.sharedIds, merged.report.reconciled.size, merged.report.renamed.size)
                merged.report.notes.take(50).forEach { log.info("  {}", it) }
            }
            val feed = merged.feed
            GtfsValidator.validate(feed, report, today)
            val validation = report.build()
            if (validation.hasErrors) {
                log.error("GTFS for {} failed validation: {}", name, validation.summary())
                validation.errors.take(20).forEach { log.error("  {}", it) }
                throw PackBuildException("GTFS for $name failed validation: ${validation.summary()}", validation)
            }
            if (validation.warningCount > 0) log.warn("GTFS for {} has warnings: {}", name, validation.summary())

            val version = nextVersion(name, now)
            val request = PackBuildRequest(
                name = name,
                version = version,
                buildDate = today,
                windowDays = options.windowDays,
                lookbackDays = options.lookbackDays,
                modes = modes,
                shapeToleranceMeters = options.shapeToleranceMeters,
                sourceLabels = refs.map { "${it.source}@${it.sha256.take(12)}" },
            )
            val built = PackBuilder.buildContent(feed, request, idMapStore.load(name))
            warnAboutEmptyInputs(name, refs, feeds, built)
            val staging = store.newStagingDir()
            try {
                val artifacts = PackBuilder.writeArtifacts(built, request, staging, options.zstdLevel)
                JvmTimetables.open(artifacts.sqlite).use { check(it.version == version) { "Built pack reports version ${it.version}" } }
                val manifest = PackManifest(artifacts.info, now, refs, built.stats)
                store.publish(artifacts, manifest)
                val timetable = try {
                    JvmTimetables.open(store.sqlitePath(name, version))
                } catch (e: Exception) {
                    store.delete(name, version)
                    throw e
                }
                idMapStore.save(name, built.idMaps)
                val previous = loaded[name]
                loaded = loaded + (name to LoadedPack(manifest, timetable))
                publishCurrent()
                previous?.let { retire(it.timetable) }
                store.prune(name, protect = setOf(version))
                log.info(
                    "Published timetable {}/{}: {} bytes compressed, {} in {} ms",
                    name, version, artifacts.info.sizeBytes, built.stats, (System.nanoTime() - startedNanos) / 1_000_000,
                )
                manifest
            } finally {
                staging.toFile().deleteRecursively()
            }
        }

    /**
     * An input none of whose trips made it into the pack (e.g. a frozen feed whose services all
     * ended) is almost always a wrong or stale source; the pack is still published from the others.
     */
    private fun warnAboutEmptyInputs(name: String, refs: List<SourceRef>, feeds: List<GtfsFeed>, built: BuiltContent) {
        if (feeds.size < 2) return
        val inPack = built.content.trips.mapTo(HashSet()) { it.gtfsId }
        refs.zip(feeds).forEach { (ref, input) ->
            if (input.trips.isNotEmpty() && input.trips.none { it.id in inPack }) {
                log.warn("GTFS input {} for {} has no trips in the pack window ({} trips, none running): stale or wrong source?", ref.source, name, input.trips.size)
            }
        }
    }

    /** `yyyyMMdd'T'HHmm` in Sydney time, with `-2`, `-3`, ... appended if that version exists. */
    private fun nextVersion(name: String, nowMillis: Long): String {
        val base = VERSION_FORMAT.format(Instant.ofEpochMilli(nowMillis).atZone(SYDNEY))
        if (!store.versionExists(name, base)) return base
        return generateSequence(2) { it + 1 }.map { "$base-$it" }.first { !store.versionExists(name, it) }
    }

    private fun orderedNames(names: Set<String>): List<String> {
        val configured = config.packs.map { it.name }.filter { it in names }
        return configured + (names - configured.toSet()).sorted()
    }

    private fun publishCurrent() {
        val snapshot = loaded
        currentFlow.value = if (snapshot.isEmpty()) null else CompositeTimetable(orderedNames(snapshot.keys).map { snapshot.getValue(it).timetable })
    }

    /** Closes a replaced pack after a grace period, so in-flight queries can finish. */
    private fun retire(timetable: TimetablePack) {
        val s = scope
        if (s != null && s.isActive) {
            s.launch(CoroutineName("retire-${timetable.version}")) {
                delay(options.retiredPackCloseDelay)
                timetable.close()
            }
        } else {
            timetable.close()
        }
    }

    /** Closes all loaded packs. */
    override fun close() {
        synchronized(buildLock) {
            loaded.values.forEach { runCatching { it.timetable.close() } }
            loaded = emptyMap()
            currentFlow.value = null
        }
    }

    private fun hashInput(path: Path): String {
        if (!path.isDirectory()) return PackBuilder.sha256(path)
        val md = MessageDigest.getInstance("SHA-256")
        Files.list(path).use { s -> s.filter { Files.isRegularFile(it) }.sorted().toList() }.forEach { f ->
            md.update(f.name.toByteArray())
            md.update(0)
            md.update(Files.readAllBytes(f))
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val NO_API_KEY =
            "TFNSW_API_KEY is not set on the server, so it cannot download TfNSW's timetable. Add it to .env and restart tt-server."

        /** What a person running the server should do about a failed refresh, in one or two sentences. */
        internal fun describeFailure(e: Exception): String {
            val detail = e.message?.take(300) ?: e.javaClass.simpleName
            return when {
                e is HttpStatusException && (e.status == 401 || e.status == 403) ->
                    "TfNSW refused the server's API key (HTTP ${e.status}). Check TFNSW_API_KEY and that your TfNSW application has the GTFS timetable products added."
                e is HttpStatusException && e.status == 404 ->
                    "TfNSW has no timetable at the address the server asked for (HTTP 404). Update tt-server to the latest version."
                e is HttpStatusException && e.temporary ->
                    "TfNSW's servers answered HTTP ${e.status}. The server tries again in a few minutes, then hourly, until it works."
                e is PackBuildException ->
                    "TfNSW's timetable data could not be turned into a timetable: $detail. Update tt-server to the latest version."
                e is java.io.IOException ->
                    "The server could not download TfNSW's timetable ($detail). Check its internet connection. It tries again in a few minutes."
                else -> "Building the timetable failed: $detail. See the server log."
            }
        }

        private val SYDNEY: ZoneId = ZoneId.of("Australia/Sydney")
        private val VERSION_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm")

        /** Sydney calendar date of an instant. */
        fun sydneyDate(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(SYDNEY).toLocalDate()
    }
}
