package app.transittracker.android

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import app.transittracker.ui.platform.ApkInstaller
import app.transittracker.ui.platform.AppUpdate
import app.transittracker.ui.platform.DownloadResult
import app.transittracker.ui.platform.InstallerResult
import app.transittracker.ui.platform.ReleaseFeedUpdater
import app.transittracker.ui.platform.UpdateProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updates from the repository's GitHub releases (CI publishes one per push to main, see
 * androidApp/README.md). The APK is downloaded into the cache, checked (size, SHA-256, package
 * name and version code) and handed to Android's [PackageInstaller]; Android itself refuses an
 * APK signed with a different key.
 */
internal object AppUpdates {
    /** The updater for this build, or null when the build names no release repository. */
    fun create(app: Application, scope: CoroutineScope): ReleaseFeedUpdater? {
        val repo = BuildConfig.UPDATE_REPO.trim()
        if (!REPO.matches(repo)) return null
        return ReleaseFeedUpdater(BuildConfig.VERSION_CODE.toLong(), AndroidApkInstaller(app, repo), scope)
    }

    private val REPO = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

    /** Remembers the version being installed, so the "updated" notification shows only after our own update. */
    internal const val PREFS = "updates"
    internal const val KEY_INSTALLING = "installing.versionCode"
}

private class AndroidApkInstaller(private val app: Application, private val repo: String) : ApkInstaller {
    private val dir: File get() = File(app.cacheDir, "updates")

    override suspend fun fetchReleases(): String = withContext(Dispatchers.IO) {
        val conn = open("https://api.github.com/repos/$repo/releases?per_page=30")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Releases: HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun download(update: AppUpdate, onProgress: (Float?) -> Unit): DownloadResult = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, "transittracker-${update.versionCode}.apk")
        // Only the newest download is kept.
        dir.listFiles()?.filter { it.name != target.name }?.forEach { it.delete() }
        if (target.isFile && verify(target, update) == null) {
            onProgress(1f)
            return@withContext DownloadResult.Done(target.path)
        }
        val part = File(dir, "${target.name}.part")
        val conn = open(update.apkUrl)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext DownloadResult.Failed(UpdateProblem.DOWNLOAD_FAILED)
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: update.sizeBytes.takeIf { it > 0 }
            onProgress(if (total == null) null else 0f)
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPercent = -1L
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        if (total != null) {
                            val percent = read * 100 / total
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress((read.toDouble() / total).toFloat().coerceAtMost(1f))
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            part.delete()
            return@withContext DownloadResult.Failed(UpdateProblem.DOWNLOAD_FAILED)
        } finally {
            conn.disconnect()
        }
        verify(part, update)?.let { problem ->
            part.delete()
            return@withContext DownloadResult.Failed(problem)
        }
        if (!part.renameTo(target)) return@withContext DownloadResult.Failed(UpdateProblem.DOWNLOAD_FAILED)
        DownloadResult.Done(target.path)
    }

    /** Null when [file] is the APK [update] describes; otherwise why not. */
    private fun verify(file: File, update: AppUpdate): UpdateProblem? {
        if (update.sizeBytes > 0 && file.length() != update.sizeBytes) return UpdateProblem.BAD_DOWNLOAD
        update.digest?.removePrefix("sha256:")?.let { expected ->
            if (!sha256(file).equals(expected, ignoreCase = true)) return UpdateProblem.BAD_DOWNLOAD
        }
        val info = app.packageManager.getPackageArchiveInfo(file.path, 0) ?: return UpdateProblem.BAD_DOWNLOAD
        if (info.packageName != app.packageName) return UpdateProblem.BAD_DOWNLOAD
        if (PackageInfoCompat.getLongVersionCode(info) != update.versionCode) return UpdateProblem.BAD_DOWNLOAD
        return null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun installAllowed(): Boolean = app.packageManager.canRequestPackageInstalls()

    override fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
        (app as TransitTrackerApplication).graph.platform.startActivity(intent)
    }

    override suspend fun install(file: String) = withContext(Dispatchers.IO) {
        val apk = File(file)
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            // Android 12+: once this app installed itself, later updates need no confirmation.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val versionCode = app.packageManager.getPackageArchiveInfo(file, 0)?.let(PackageInfoCompat::getLongVersionCode) ?: -1L
                app.getSharedPreferences(AppUpdates.PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(AppUpdates.KEY_INSTALLING, versionCode).apply()
                val callback = Intent(app, UpdateStatusReceiver::class.java).setAction(UpdateStatusReceiver.ACTION)
                // Mutable: the installer adds the status extras. The intent is explicit (this app's receiver).
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(app, id, callback, flags).intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            // GitHub requires a User-Agent; the asset URL redirects (https to https) to its download host.
            setRequestProperty("User-Agent", "TransitTracker-Android")
            instanceFollowRedirects = true
        }
}

/** The installer's answers to a session [AndroidApkInstaller] committed. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val platform = (context.applicationContext as? TransitTrackerApplication)?.graph?.platform ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val result = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android's own "Do you want to update this app?" screen.
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let(platform::startActivity)
                InstallerResult.WAITING_FOR_USER
            }
            PackageInstaller.STATUS_SUCCESS -> InstallerResult.SUCCESS
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallerResult.CANCELLED
            PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallerResult.DIFFERENT_SIGNATURE
            else -> if (message.contains("signature", ignoreCase = true)) InstallerResult.DIFFERENT_SIGNATURE else InstallerResult.FAILED
        }
        if (result != InstallerResult.SUCCESS && result != InstallerResult.WAITING_FOR_USER) {
            android.util.Log.w("TransitTracker", "Update not installed: status $status $message")
        }
        platform.updater?.onInstallerResult(result)
    }

    companion object {
        const val ACTION = "app.transittracker.UPDATE_STATUS"
    }
}

/**
 * After the app replaced itself, Android has stopped it: a notification says it is done and
 * opens it again. Only after an update this app installed (not adb or a manual install).
 */
class AppReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences(AppUpdates.PREFS, Context.MODE_PRIVATE)
        val installing = prefs.getLong(AppUpdates.KEY_INSTALLING, -1L)
        prefs.edit().remove(AppUpdates.KEY_INSTALLING).apply()
        if (installing != BuildConfig.VERSION_CODE.toLong()) return
        Notifications.show(
            context,
            "TransitTracker updated",
            "Now on version ${BuildConfig.VERSION_NAME}. Tap to open.",
            emptyMap(),
            Notifications.GENERAL,
            "app-updated",
        )
    }
}
