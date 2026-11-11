package app.transittracker.ui.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A build of the app newer than the one running, published as a release. */
data class AppUpdate(
    val versionCode: Long,
    /** "0.1.42". */
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    /** "sha256:<hex>" when the feed gives one; the download is checked against it. */
    val digest: String? = null,
    /** The release notes, as written (may be blank). */
    val notes: String = "",
)

/** Why an update step did not finish. */
enum class UpdateProblem {
    /** The release list could not be fetched or read. */
    CHECK_FAILED,
    DOWNLOAD_FAILED,
    /** The download was not the file the release described (size, checksum or package). */
    BAD_DOWNLOAD,
    /** Android refused it: signed with a different key than the installed app. */
    DIFFERENT_SIGNATURE,
    /** The person closed the installer without installing. */
    CANCELLED,
    INSTALL_FAILED,
}

/** Where checking for and installing an update has got to. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AppUpdate) : UpdateState

    /** [fraction] 0..1, or null while the size is unknown. */
    data class Downloading(val update: AppUpdate, val fraction: Float?) : UpdateState

    /** Android's installer has the file: it asks the person, or installs and restarts the app. */
    data class Installing(val update: AppUpdate) : UpdateState

    /** Android needs "Install unknown apps" allowed for this app; its settings page is open. */
    data class NeedsPermission(val update: AppUpdate) : UpdateState

    /** [update] is kept so INSTALL can be tried again without checking first. */
    data class Failed(val problem: UpdateProblem, val update: AppUpdate? = null) : UpdateState
}

/**
 * Checks for a newer build of the app and installs it. One per process
 * ([PlatformServices.updater]); null where the build cannot update itself.
 */
interface AppUpdater {
    val state: StateFlow<UpdateState>

    /** Asks the release feed for a newer build. Does nothing while a check or install is running. */
    fun check()

    /** Downloads the update found by [check] and hands it to the phone's installer. */
    fun install()
}

/** What the system installer said about a package handed to it. */
enum class InstallerResult { WAITING_FOR_USER, SUCCESS, CANCELLED, DIFFERENT_SIGNATURE, FAILED }

/** The platform half of [ReleaseFeedUpdater]: networking, files and the system installer. */
interface ApkInstaller {
    /** The release list (GitHub's `GET /repos/{owner}/{repo}/releases` JSON). Throws when it cannot. */
    suspend fun fetchReleases(): String

    /**
     * Downloads [update]'s APK, reporting progress (0..1, or null while unknown), and checks it
     * against [AppUpdate.sizeBytes] and [AppUpdate.digest]. Returns a path for [install], or a
     * problem ([UpdateProblem.DOWNLOAD_FAILED] or [UpdateProblem.BAD_DOWNLOAD]).
     */
    suspend fun download(update: AppUpdate, onProgress: (Float?) -> Unit): DownloadResult

    /** Whether Android lets this app install packages ("Install unknown apps"). */
    fun installAllowed(): Boolean

    /** Opens the system page where the person allows installs from this app. */
    fun openInstallPermissionSettings()

    /** Hands [file] to the system installer; it answers through [ReleaseFeedUpdater.onInstallerResult]. Throws when it cannot. */
    suspend fun install(file: String)
}

sealed interface DownloadResult {
    data class Done(val file: String) : DownloadResult
    data class Failed(val problem: UpdateProblem) : DownloadResult
}

/**
 * [AppUpdater] over a GitHub release feed: releases tagged `<tagPrefix><versionCode>` (e.g.
 * `android-42`) with one `.apk` asset, named "TransitTracker <versionName>". The newest one with
 * a higher version code than [currentVersionCode] is offered.
 */
class ReleaseFeedUpdater(
    private val currentVersionCode: Long,
    private val installer: ApkInstaller,
    private val scope: CoroutineScope,
    private val tagPrefix: String = TAG_PREFIX,
) : AppUpdater {
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = mutable.asStateFlow()
    private var job: Job? = null

    override fun check() {
        if (busy()) return
        mutable.value = UpdateState.Checking
        job = scope.launch {
            mutable.value = try {
                val update = newestUpdate(installer.fetchReleases(), currentVersionCode, tagPrefix)
                if (update == null) UpdateState.UpToDate else UpdateState.Available(update)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                UpdateState.Failed(UpdateProblem.CHECK_FAILED)
            }
        }
    }

    override fun install() {
        val update = pendingUpdate() ?: return
        if (busy()) return
        if (!installer.installAllowed()) {
            mutable.value = UpdateState.NeedsPermission(update)
            installer.openInstallPermissionSettings()
            return
        }
        mutable.value = UpdateState.Downloading(update, null)
        job = scope.launch {
            val result = try {
                installer.download(update) { fraction -> mutable.value = UpdateState.Downloading(update, fraction) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                DownloadResult.Failed(UpdateProblem.DOWNLOAD_FAILED)
            }
            when (result) {
                is DownloadResult.Failed -> mutable.value = UpdateState.Failed(result.problem, update)
                is DownloadResult.Done -> {
                    mutable.value = UpdateState.Installing(update)
                    try {
                        installer.install(result.file)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        mutable.value = UpdateState.Failed(UpdateProblem.INSTALL_FAILED, update)
                    }
                }
            }
        }
    }

    /**
     * The app came back to the front: if it was waiting for "Install unknown apps" and that is now
     * allowed, carry on installing.
     */
    fun onResume() {
        val s = mutable.value
        if (s is UpdateState.NeedsPermission && installer.installAllowed()) {
            mutable.value = UpdateState.Available(s.update)
            install()
        }
    }

    /** The system installer's answer to [ApkInstaller.install]. */
    fun onInstallerResult(result: InstallerResult) {
        val update = (mutable.value as? UpdateState.Installing)?.update ?: pendingUpdate()
        mutable.value = when (result) {
            // The installer's own screen is up; SUCCESS ends this process (Android restarts the app).
            InstallerResult.WAITING_FOR_USER, InstallerResult.SUCCESS ->
                update?.let { UpdateState.Installing(it) } ?: mutable.value
            InstallerResult.CANCELLED -> UpdateState.Failed(UpdateProblem.CANCELLED, update)
            InstallerResult.DIFFERENT_SIGNATURE -> UpdateState.Failed(UpdateProblem.DIFFERENT_SIGNATURE, update)
            InstallerResult.FAILED -> UpdateState.Failed(UpdateProblem.INSTALL_FAILED, update)
        }
    }

    private fun busy(): Boolean = job?.isActive == true

    private fun pendingUpdate(): AppUpdate? = when (val s = mutable.value) {
        is UpdateState.Available -> s.update
        is UpdateState.NeedsPermission -> s.update
        is UpdateState.Failed -> s.update
        is UpdateState.Installing -> s.update
        else -> null
    }

    companion object {
        const val TAG_PREFIX = "android-"
        const val NAME_PREFIX = "TransitTracker "
    }
}

// ---------------------------------------------------------------- the feed

@Serializable
private data class ReleaseJson(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<AssetJson> = emptyList(),
)

@Serializable
private data class AssetJson(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String = "",
    val digest: String? = null,
)

private val feedJson = Json { ignoreUnknownKeys = true }

/**
 * The newest published release in [releasesJson] whose version code (from its tag,
 * `<tagPrefix><code>`) is above [currentVersionCode] and that has an APK; null when there is none.
 * Drafts and pre-releases are skipped. Throws when the JSON is not a release list.
 */
fun newestUpdate(releasesJson: String, currentVersionCode: Long, tagPrefix: String = ReleaseFeedUpdater.TAG_PREFIX): AppUpdate? =
    feedJson.decodeFromString(ListSerializer(ReleaseJson.serializer()), releasesJson)
        .asSequence()
        .filter { !it.draft && !it.prerelease && it.tagName.startsWith(tagPrefix) }
        .mapNotNull { release ->
            val code = release.tagName.removePrefix(tagPrefix).toLongOrNull() ?: return@mapNotNull null
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) && it.url.startsWith("https://") }
                ?: return@mapNotNull null
            val name = release.name?.removePrefix(ReleaseFeedUpdater.NAME_PREFIX)?.trim().orEmpty()
            AppUpdate(
                versionCode = code,
                versionName = name.ifEmpty { "build $code" },
                apkUrl = apk.url,
                sizeBytes = apk.size,
                digest = apk.digest?.takeIf { it.startsWith("sha256:") },
                notes = release.body.orEmpty().trim(),
            )
        }
        .filter { it.versionCode > currentVersionCode }
        .maxByOrNull { it.versionCode }

// ---------------------------------------------------------------- fake

/** [AppUpdater] for previews, screenshots and tests: [state] is settable; calls are counted. */
class FakeAppUpdater(initial: UpdateState = UpdateState.Idle) : AppUpdater {
    val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    var checks = 0
        private set
    var installs = 0
        private set

    /** What [check] finds. */
    var available: AppUpdate? = SAMPLE

    override fun check() {
        checks++
        mutableState.value = available?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
    }

    override fun install() {
        installs++
        val update = when (val s = mutableState.value) {
            is UpdateState.Available -> s.update
            is UpdateState.Failed -> s.update
            else -> null
        } ?: return
        mutableState.value = UpdateState.Downloading(update, 0.4f)
    }

    companion object {
        val SAMPLE = AppUpdate(
            versionCode = 42,
            versionName = "0.1.42",
            apkUrl = "https://example.invalid/transittracker-0.1.42.apk",
            sizeBytes = 31_400_000,
            notes = "Location: ask every provider at once so \"my location\" resolves",
        )
    }
}
