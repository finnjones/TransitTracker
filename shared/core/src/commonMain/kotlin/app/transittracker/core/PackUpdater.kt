package app.transittracker.core

import app.transittracker.model.Mode
import app.transittracker.model.PackInfo
import app.transittracker.model.PackStatus
import app.transittracker.model.ServiceDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.coroutines.CoroutineContext

/** A pack installed on this device (persisted in the [KeyValueStore]). */
@Serializable
data class InstalledPack(
    val name: String,
    val version: String,
    /** [FileStore] name of the SQLite file. */
    val fileName: String,
    val validTo: ServiceDate = "",
    val modes: List<Mode> = emptyList(),
)

/** What happened to one pack during an update check. */
sealed interface PackUpdateResult {
    val name: String

    data class UpToDate(override val name: String, val version: String) : PackUpdateResult
    data class Updated(override val name: String, val fromVersion: String?, val toVersion: String) : PackUpdateResult

    /** A newer version exists but the Wi-Fi-only policy deferred it. */
    data class Deferred(override val name: String, val version: String) : PackUpdateResult

    /** Download or verification failed; the previous version (if any) stays in use. */
    data class Failed(override val name: String, val version: String, val reason: String) : PackUpdateResult

    /** The server does not offer this pack; [serverReason] is the server's own explanation, if it sent one. */
    data class NotOffered(override val name: String, val serverReason: String? = null) : PackUpdateResult
}

/**
 * Keeps timetable packs current: download, verify `sha256`, decompress, verify `sqliteSha256`,
 * atomic replace, open, swap into [holder], close the old one. Any failure keeps the old pack.
 */
class PackUpdater(
    private val api: ApiClient,
    private val kv: KeyValueStore,
    private val files: FileStore,
    private val opener: TimetableOpener,
    private val decompressor: Decompressor,
    private val network: NetworkStatus,
    private val holder: TimetableHolder,
    private val config: CoreConfig,
    private val scope: CoroutineScope,
    private val ioContext: CoroutineContext,
) {
    private val mutex = Mutex()
    private val installedState = MutableStateFlow<List<InstalledPack>>(emptyList())
    private val _status = MutableStateFlow<Map<String, PackStatus>>(emptyMap())
    private val _lastReport = MutableStateFlow<List<PackUpdateResult>>(emptyList())

    /** Per-pack status, for [app.transittracker.model.DataStatus.packs]. */
    val status: StateFlow<Map<String, PackStatus>> = _status.asStateFlow()

    /** Installed packs. */
    val installed: StateFlow<List<InstalledPack>> = installedState.asStateFlow()

    /** Results of the most recent update check (failures are reported here). */
    val lastReport: StateFlow<List<PackUpdateResult>> = _lastReport.asStateFlow()

    /** Opens the packs recorded as installed. Packs whose files are missing or unreadable are dropped. */
    suspend fun openInstalled() = mutex.withLock {
        val recorded = kv.get(KvKeys.INSTALLED_PACKS)?.let {
            runCatching { ApiJson.decodeFromString(ListSerializer(InstalledPack.serializer()), it) }.getOrNull()
        }.orEmpty()
        val opened = ArrayList<InstalledPack>()
        for (pack in recorded) {
            if (!files.exists(pack.fileName)) continue
            val timetable = runCatching { withContext(ioContext) { opener.open(files.resolve(pack.fileName)) } }
                .getOrElse { if (it is CancellationException) throw it; null } ?: continue
            holder.install(pack.name, pack.version, timetable)
            opened += pack
        }
        setInstalled(opened)
        opened.forEach { p -> setStatus(p.name) { it.copy(version = p.version, validTo = p.validTo) } }
    }

    /**
     * Checks [offered] packs against what is installed and installs newer versions.
     * [serverProblems] says why the server has no version of a pack (`ConfigResponse.timetableProblems`).
     */
    suspend fun update(offered: List<PackInfo>, serverProblems: Map<String, String> = emptyMap()): List<PackUpdateResult> = mutex.withLock {
        val installed = installedState.value
        val wanted = (installed.map { it.name } + config.defaultPacks).distinct()
        val results = wanted.map { name ->
            val info = offered.firstOrNull { it.name == name }
            val current = installed.firstOrNull { it.name == name }
            when {
                info == null -> PackUpdateResult.NotOffered(name, serverProblems[name]?.takeIf { it.isNotBlank() })
                current?.version == info.version -> {
                    setStatus(name) { it.copy(updateAvailable = false) }
                    PackUpdateResult.UpToDate(name, info.version)
                }
                installedState.value.isNotEmpty() && config.wifiOnlyPackUpdates && !network.isUnmetered() -> {
                    setStatus(name) { it.copy(updateAvailable = true) }
                    PackUpdateResult.Deferred(name, info.version)
                }
                else -> install(info, current)
            }
        }
        _lastReport.value = results
        results
    }

    private suspend fun install(info: PackInfo, current: InstalledPack?): PackUpdateResult {
        setStatus(info.name) { it.copy(updateAvailable = true, downloading = true) }
        var tempPath: String? = null
        try {
            val download = api.download(info.url)
            val compressed = when (download) {
                is ApiResult.Success -> download.value
                is ApiResult.HttpFailure -> return fail(info, "HTTP ${download.status}")
                is ApiResult.NetworkFailure -> return fail(info, download.message)
            }
            val sqlite = withContext(ioContext) {
                val actual = Sha256.hex(compressed)
                if (!actual.equals(info.sha256, ignoreCase = true)) return@withContext null
                decompressor.decompress(compressed)
            } ?: return fail(info, "sha256 mismatch")
            if (info.sqliteSha256.isNotEmpty()) {
                val actual = withContext(ioContext) { Sha256.hex(sqlite) }
                if (!actual.equals(info.sqliteSha256, ignoreCase = true)) return fail(info, "sqliteSha256 mismatch")
            }
            val fileName = "${info.name}-${info.version}.sqlite"
            tempPath = files.writeTemp(sqlite)
            val path = files.replace(tempPath, fileName)
            tempPath = null
            val timetable = try {
                withContext(ioContext) { opener.open(path) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (current?.fileName != fileName) files.delete(fileName)
                return fail(info, "open failed: ${e.message}")
            }
            val oldFile = current?.fileName?.takeIf { it != fileName }
            holder.install(info.name, info.version, timetable) {
                // Runs once the old pack is closed (no query is still reading it).
                if (oldFile != null) scope.launch { runCatching { files.delete(oldFile) } }
            }
            val record = InstalledPack(info.name, info.version, fileName, info.validTo, info.modes)
            setInstalled(installedState.value.filter { it.name != info.name } + record)
            setStatus(info.name) {
                PackStatus(info.name, info.version, info.validTo, updateAvailable = false, downloading = false)
            }
            return PackUpdateResult.Updated(info.name, current?.version, info.version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return fail(info, e.message ?: e.toString())
        } finally {
            tempPath?.let { runCatching { files.delete(it) } }
        }
    }

    private fun fail(info: PackInfo, reason: String): PackUpdateResult {
        setStatus(info.name) { it.copy(downloading = false, updateAvailable = true) }
        return PackUpdateResult.Failed(info.name, info.version, reason)
    }

    private suspend fun setInstalled(packs: List<InstalledPack>) {
        installedState.value = packs.sortedBy { it.name }
        kv.put(KvKeys.INSTALLED_PACKS, ApiJson.encodeToString(ListSerializer(InstalledPack.serializer()), installedState.value))
    }

    private fun setStatus(name: String, change: (PackStatus) -> PackStatus) {
        _status.update { m ->
            val current = m[name] ?: run {
                val inst = installedState.value.firstOrNull { it.name == name }
                PackStatus(name, inst?.version, inst?.validTo, updateAvailable = false)
            }
            m + (name to change(current))
        }
    }
}

/**
 * Plain sentences for [app.transittracker.model.DataStatus.timetableProblem]: why the timetable
 * did not arrive. They name what to check on the server, since the person reading them runs it.
 */
object TimetableProblems {
    const val NOT_OFFERED =
        "The server has no timetable yet. It builds one from TfNSW's data after it starts and then nightly: check TFNSW_API_KEY and the server log."
    const val UNREADABLE_CONFIG = "The server sent an answer the app could not read. Update tt-server to the latest version."

    /** `/v1/config` answered [status]. */
    fun config(status: Int): String = when (status) {
        401, 403 -> "The server no longer knows this phone. Clear the app's data and join again with a new invite code."
        404 -> "The server address looks wrong: it has no TransitTracker API. Check the address and that tt-server is up to date."
        else -> "The server could not send the timetable list (HTTP $status). Try again soon."
    }

    /** The problem in [report], if nothing usable was installed ([installed] is false). */
    fun of(report: List<PackUpdateResult>, installed: Boolean): String? {
        if (installed) return null
        report.filterIsInstance<PackUpdateResult.Failed>().firstOrNull()?.let {
            return "The timetable download failed (${it.reason}). Try again."
        }
        val notOffered = report.filterIsInstance<PackUpdateResult.NotOffered>()
        if (notOffered.isEmpty()) return null
        return notOffered.firstNotNullOfOrNull { it.serverReason } ?: NOT_OFFERED
    }
}
