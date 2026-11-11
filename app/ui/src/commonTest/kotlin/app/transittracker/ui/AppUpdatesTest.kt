package app.transittracker.ui

import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.feature.settings.SettingsStateHolder
import app.transittracker.ui.feature.settings.UpdateAction
import app.transittracker.ui.feature.settings.sizeWords
import app.transittracker.ui.feature.settings.updateWords
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.platform.ApkInstaller
import app.transittracker.ui.platform.AppUpdate
import app.transittracker.ui.platform.DownloadResult
import app.transittracker.ui.platform.FakeAppUpdater
import app.transittracker.ui.platform.FakePlatformServices
import app.transittracker.ui.platform.InstallerResult
import app.transittracker.ui.platform.ReleaseFeedUpdater
import app.transittracker.ui.platform.UpdateProblem
import app.transittracker.ui.platform.UpdateState
import app.transittracker.ui.platform.newestUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-app updates: reading the release feed, the update steps, and what Settings says. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdatesTest {
    private fun release(
        tag: String,
        name: String? = null,
        apk: String? = "transittracker.apk",
        draft: Boolean = false,
        prerelease: Boolean = false,
        digest: String? = null,
    ): String {
        val url = "https://github.com/o/r/releases/download/$tag/$apk"
        val digestField = if (digest == null) "" else ",\"digest\":\"$digest\""
        val assets = if (apk == null) "" else "{\"name\":\"$apk\",\"size\":1234,\"browser_download_url\":\"$url\"$digestField}"
        val nameField = if (name == null) "null" else "\"$name\""
        return "{\"tag_name\":\"$tag\",\"name\":$nameField,\"body\":\"- Fix one\\n- Fix two\"," +
            "\"draft\":$draft,\"prerelease\":$prerelease,\"assets\":[$assets]}"
    }

    private fun feed(vararg releases: String) = releases.joinToString(",", "[", "]")

    @Test
    fun theNewestPublishedAndroidReleaseIsOffered() {
        val json = feed(
            release("android-40", "TransitTracker 0.1.40"),
            release("android-44", "TransitTracker 0.1.44", draft = true),
            release("android-43", "TransitTracker 0.1.43", prerelease = true),
            release("android-42", "TransitTracker 0.1.42", digest = "sha256:abc"),
            release("v1.0.0", "Server 1.0.0"),
            release("android-41", "TransitTracker 0.1.41"),
        )
        val update = newestUpdate(json, currentVersionCode = 39)!!
        assertEquals(42, update.versionCode)
        assertEquals("0.1.42", update.versionName)
        assertEquals("https://github.com/o/r/releases/download/android-42/transittracker.apk", update.apkUrl)
        assertEquals(1234, update.sizeBytes)
        assertEquals("sha256:abc", update.digest)
        assertEquals("- Fix one\n- Fix two", update.notes)
    }

    @Test
    fun nothingNewerOrNoApkMeansUpToDate() {
        assertNull(newestUpdate(feed(release("android-42"), release("android-41")), currentVersionCode = 42))
        assertNull(newestUpdate(feed(release("android-50", apk = null)), currentVersionCode = 42))
        assertNull(newestUpdate(feed(release("android-x1")), currentVersionCode = 0))
        assertNull(newestUpdate("[]", currentVersionCode = 1))
        // No release name: the build number stands in for the version.
        assertEquals("build 43", newestUpdate(feed(release("android-43")), 42)!!.versionName)
    }

    @Test
    fun aFeedThatIsNotAReleaseListIsAnError() {
        assertFailsWith<Exception> { newestUpdate("""{"message":"API rate limit exceeded"}""", 1) }
    }

    private class Installer(
        var releases: String = "[]",
        var allowed: Boolean = true,
        var download: DownloadResult = DownloadResult.Done("/cache/update.apk"),
    ) : ApkInstaller {
        var gate: CompletableDeferred<Unit>? = null
        val installed = mutableListOf<String>()
        var settingsOpened = 0

        override suspend fun fetchReleases(): String {
            gate?.await()
            return releases
        }

        override suspend fun download(update: AppUpdate, onProgress: (Float?) -> Unit): DownloadResult {
            onProgress(0.5f)
            gate?.await()
            return download
        }

        override fun installAllowed() = allowed
        override fun openInstallPermissionSettings() {
            settingsOpened++
        }

        override suspend fun install(file: String) {
            installed += file
        }
    }

    @Test
    fun checkThenInstallHandsTheDownloadToTheInstaller() = runTest {
        val installer = Installer(releases = feed(release("android-42", "TransitTracker 0.1.42")))
        val updater = ReleaseFeedUpdater(41, installer, backgroundScope)
        updater.check()
        runCurrent()
        val available = assertIs<UpdateState.Available>(updater.state.value)
        assertEquals(42, available.update.versionCode)

        installer.gate = CompletableDeferred()
        updater.install()
        runCurrent()
        assertEquals(UpdateState.Downloading(available.update, 0.5f), updater.state.value)
        installer.gate!!.complete(Unit)
        runCurrent()
        assertEquals(UpdateState.Installing(available.update), updater.state.value)
        assertEquals(listOf("/cache/update.apk"), installer.installed)

        updater.onInstallerResult(InstallerResult.WAITING_FOR_USER)
        assertEquals(UpdateState.Installing(available.update), updater.state.value)
        updater.onInstallerResult(InstallerResult.CANCELLED)
        assertEquals(UpdateState.Failed(UpdateProblem.CANCELLED, available.update), updater.state.value)
        // INSTALL again from the failure, without checking first.
        updater.install()
        runCurrent()
        assertEquals(2, installer.installed.size)
        updater.onInstallerResult(InstallerResult.DIFFERENT_SIGNATURE)
        assertEquals(UpdateState.Failed(UpdateProblem.DIFFERENT_SIGNATURE, available.update), updater.state.value)
    }

    @Test
    fun upToDateAndFailedChecks() = runTest {
        val installer = Installer(releases = feed(release("android-41")))
        val updater = ReleaseFeedUpdater(41, installer, backgroundScope)
        updater.check()
        runCurrent()
        assertEquals(UpdateState.UpToDate, updater.state.value)
        installer.releases = "not json"
        updater.check()
        runCurrent()
        assertEquals(UpdateState.Failed(UpdateProblem.CHECK_FAILED), updater.state.value)
        // Nothing to install after a failed check.
        updater.install()
        runCurrent()
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun aSecondTapWhileBusyIsIgnored() = runTest {
        val installer = Installer(releases = feed(release("android-42")))
        installer.gate = CompletableDeferred()
        var fetches = 0
        val counting = object : ApkInstaller by installer {
            override suspend fun fetchReleases(): String {
                fetches++
                return installer.fetchReleases()
            }
        }
        val updater = ReleaseFeedUpdater(41, counting, backgroundScope)
        updater.check()
        updater.check()
        runCurrent()
        assertEquals(1, fetches)
        assertEquals(UpdateState.Checking, updater.state.value)
    }

    @Test
    fun withoutInstallPermissionTheSettingsPageOpensAndInstallingResumesOnReturn() = runTest {
        val installer = Installer(releases = feed(release("android-42")), allowed = false)
        val updater = ReleaseFeedUpdater(41, installer, backgroundScope)
        updater.check()
        runCurrent()
        updater.install()
        assertIs<UpdateState.NeedsPermission>(updater.state.value)
        assertEquals(1, installer.settingsOpened)

        updater.onResume() // still not allowed: stays waiting
        assertIs<UpdateState.NeedsPermission>(updater.state.value)
        installer.allowed = true
        updater.onResume()
        runCurrent()
        assertIs<UpdateState.Installing>(updater.state.value)
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun aDamagedDownloadIsNotInstalled() = runTest {
        val installer = Installer(
            releases = feed(release("android-42")),
            download = DownloadResult.Failed(UpdateProblem.BAD_DOWNLOAD),
        )
        val updater = ReleaseFeedUpdater(41, installer, backgroundScope)
        updater.check()
        runCurrent()
        updater.install()
        runCurrent()
        val failed = assertIs<UpdateState.Failed>(updater.state.value)
        assertEquals(UpdateProblem.BAD_DOWNLOAD, failed.problem)
        assertTrue(installer.installed.isEmpty())
    }

    // ---------------------------------------------------------------- words

    @Test
    fun everyStepHasPlainWordsAndTheRightButton() {
        val u = FakeAppUpdater.SAMPLE
        assertNull(updateWords(null).button)
        assertEquals("CHECK FOR UPDATES", updateWords(UpdateState.Idle).button)
        assertTrue(updateWords(UpdateState.Checking).busy)
        assertEquals(Lamp.CIRCLE, updateWords(UpdateState.UpToDate).lamp)
        updateWords(UpdateState.Available(u)).let {
            assertEquals("Version 0.1.42 is ready to install.", it.headline)
            assertEquals("31.4 MB download.", it.detail)
            assertEquals("INSTALL UPDATE", it.button)
            assertEquals(UpdateAction.INSTALL, it.action)
            assertEquals(u.notes, it.notes)
        }
        assertEquals("DOWNLOADING 45%", updateWords(UpdateState.Downloading(u, 0.45f)).button)
        assertEquals("DOWNLOADING...", updateWords(UpdateState.Downloading(u, null)).button)
        assertTrue(updateWords(UpdateState.Installing(u)).busy)
        assertEquals("OPEN PHONE SETTINGS", updateWords(UpdateState.NeedsPermission(u)).button)
        for (problem in UpdateProblem.entries) {
            val words = updateWords(UpdateState.Failed(problem, u))
            assertTrue(words.headline.isNotBlank())
            val expected = when (problem) {
                UpdateProblem.DIFFERENT_SIGNATURE -> null // nothing the app can do: say what to do instead
                UpdateProblem.CHECK_FAILED -> UpdateAction.CHECK
                else -> UpdateAction.INSTALL
            }
            assertEquals(expected, words.button?.let { words.action }, "$problem")
        }
        assertEquals(UpdateAction.CHECK, updateWords(UpdateState.Failed(UpdateProblem.DOWNLOAD_FAILED, null)).action)
    }

    @Test
    fun sizesAreInMegabytes() {
        assertEquals("31.4 MB", sizeWords(31_400_000))
        assertEquals("0.1 MB", sizeWords(60_000))
        assertEquals("", sizeWords(0))
    }

    @Test
    fun settingsShowsTheUpdaterAndItsButtonDrivesIt() = runTest {
        val updater = FakeAppUpdater()
        val deps = AppDeps.fake(platform = FakePlatformServices(updater = updater))
        val holder = SettingsStateHolder(
            deps.core, deps.settings, deps.places, deps.platform, SampleData.clock, backgroundScope, TimeZone.of("Australia/Sydney"),
        )
        assertEquals("CHECK FOR UPDATES", holder.state.first { it.loaded }.update.button)
        holder.updateApp(UpdateAction.CHECK)
        assertEquals("INSTALL UPDATE", holder.state.first { it.update.button == "INSTALL UPDATE" }.update.button)
        holder.updateApp(UpdateAction.INSTALL)
        assertEquals(1, updater.checks)
        assertEquals(1, updater.installs)
        assertIs<UpdateState.Downloading>(updater.state.value)

        val none = AppDeps.fake(platform = FakePlatformServices(updater = null))
        val plain = SettingsStateHolder(
            none.core, none.settings, none.places, none.platform, SampleData.clock, backgroundScope, TimeZone.of("Australia/Sydney"),
        )
        assertNull(plain.state.first { it.loaded }.update.button)
    }
}
