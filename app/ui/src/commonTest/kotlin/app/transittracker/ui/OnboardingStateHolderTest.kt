package app.transittracker.ui

import app.transittracker.model.DataStatus
import app.transittracker.model.JoinResult
import app.transittracker.model.PackStatus
import app.transittracker.model.TransitCore
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.feature.onboarding.DownloadPhase
import app.transittracker.ui.feature.onboarding.JoinStatus
import app.transittracker.ui.feature.onboarding.OnboardingStateHolder
import app.transittracker.ui.feature.onboarding.OnboardingStep
import app.transittracker.ui.platform.FakePlatformServices
import app.transittracker.ui.platform.PermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The onboarding state holder against the fake core, which has not joined until JOIN works. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingStateHolderTest {
    private fun holder(
        core: TransitCore,
        platform: FakePlatformServices = FakePlatformServices(notificationPermission = PermissionState.NOT_ASKED),
        scope: CoroutineScope,
        step: OnboardingStep = OnboardingStep.INVITE,
        stallMs: Long = 60_000,
    ) = OnboardingStateHolder(core, platform, scope, step, stallMs)

    private fun status(packs: List<PackStatus>, reachable: Boolean = true) = DataStatus(
        packs, null, false, reachable, true, "",
    )

    private fun pack(version: String?, downloading: Boolean = false) = PackStatus("syd-rail", version, null, false, downloading)

    /** The fake core, with the join answer and the data status replaced. */
    private class Scripted(
        val base: TransitCore,
        var answer: () -> JoinResult,
        val status: MutableStateFlow<DataStatus>? = null,
    ) : TransitCore by base {
        val codes = mutableListOf<String>()
        override suspend fun join(inviteCode: String): JoinResult {
            codes += inviteCode
            return answer()
        }
        override val dataStatus: Flow<DataStatus> get() = status ?: base.dataStatus
    }

    @Test
    fun joiningIsOnlyPossibleWithEightValidCharacters() = runTest {
        val deps = AppDeps.fake(joined = false)
        val h = holder(deps.core, scope = backgroundScope)
        runCurrent()
        assertFalse(h.state.value.canJoin)
        h.onCodeChange("k7m2q")
        runCurrent()
        assertEquals("K7M2-Q", h.state.value.code)
        assertFalse(h.state.value.canJoin)
        h.join() // does nothing
        runCurrent()
        assertEquals(JoinStatus.IDLE, h.state.value.join)
        assertFalse(deps.core.joined.first())

        h.onCodeChange("k7m2qx9l") // L is not in the alphabet
        runCurrent()
        assertFalse(h.state.value.canJoin)
        assertEquals("Invite codes never contain L.", h.state.value.codeHint)
        h.onCodeChange("k7m2 qx9r")
        runCurrent()
        assertTrue(h.state.value.canJoin)
        assertEquals(null, h.state.value.codeHint)
    }

    @Test
    fun aGoodCodeJoinsAndMovesToTheDownload() = runTest {
        val core = Scripted(AppDeps.fake(joined = false).core, { JoinResult.Joined("device-1") })
        val h = holder(core, scope = backgroundScope)
        h.onCodeChange("k7m2qx9r")
        h.join()
        runCurrent()
        assertEquals(listOf("K7M2-QX9R"), core.codes, "the server gets the normalised code")
        assertEquals(OnboardingStep.DOWNLOAD, h.state.value.step)
        assertEquals(JoinStatus.IDLE, h.state.value.join)
    }

    @Test
    fun theFakeCoreJoinsOnAcceptableCodesAndTheShellSeesIt() = runTest {
        val deps = AppDeps.fake(joined = false)
        val h = holder(deps.core, scope = backgroundScope)
        h.onCodeChange("k7m2qx9r")
        h.join()
        runCurrent()
        assertTrue(deps.core.joined.first())
        assertEquals(OnboardingStep.DOWNLOAD, h.state.value.step)
    }

    @Test
    fun anInvalidCodeSaysSoAndTypingClearsIt() = runTest {
        val deps = AppDeps.fake(joined = false)
        val h = holder(deps.core, scope = backgroundScope)
        h.onCodeChange("badk2222") // the fake core refuses codes containing "bad"
        h.join()
        runCurrent()
        assertEquals(JoinStatus.INVALID, h.state.value.join)
        assertEquals(OnboardingStep.INVITE, h.state.value.step)
        assertFalse(deps.core.joined.first())
        h.onCodeChange("badk222")
        runCurrent()
        assertEquals(JoinStatus.IDLE, h.state.value.join)
    }

    @Test
    fun anUnreachableServerOffersRetryWhichTriesTheSameCodeAgain() = runTest {
        val core = Scripted(AppDeps.fake(joined = false).core, { JoinResult.Failed("timeout") })
        val h = holder(core, scope = backgroundScope)
        h.onCodeChange("k7m2qx9r")
        h.join()
        runCurrent()
        assertEquals(JoinStatus.UNREACHABLE, h.state.value.join)
        assertTrue(h.state.value.canJoin)
        core.answer = { JoinResult.Joined("d") }
        h.join() // RETRY
        runCurrent()
        assertEquals(listOf("K7M2-QX9R", "K7M2-QX9R"), core.codes)
        assertEquals(OnboardingStep.DOWNLOAD, h.state.value.step)
    }

    @Test
    fun aThrowingCoreCountsAsUnreachable() = runTest {
        val core = Scripted(AppDeps.fake(joined = false).core, { error("no route to host") })
        val h = holder(core, scope = backgroundScope)
        h.onCodeChange("k7m2qx9r")
        h.join()
        runCurrent()
        assertEquals(JoinStatus.UNREACHABLE, h.state.value.join)
    }

    @Test
    fun theDownloadFollowsTheCoresPacks() = runTest {
        val status = MutableStateFlow(status(listOf(pack(null, downloading = true))))
        val core = Scripted(AppDeps.fake().core, { JoinResult.InvalidCode }, status)
        val h = holder(core, scope = backgroundScope, step = OnboardingStep.DOWNLOAD)
        runCurrent()
        assertEquals(DownloadPhase.DOWNLOADING, h.state.value.download.phase)
        assertEquals(50, h.state.value.download.percent)
        status.value = status(listOf(pack("1")))
        runCurrent()
        assertEquals(DownloadPhase.READY, h.state.value.download.phase)
        status.value = status(listOf(pack("1")), reachable = false)
        runCurrent()
        assertEquals(DownloadPhase.READY, h.state.value.download.phase, "a finished download stays finished")
    }

    @Test
    fun anUnreachableServerDuringTheDownloadIsOfflineAndRetryListensAgain() = runTest {
        val status = MutableStateFlow(status(listOf(pack(null, downloading = true)), reachable = false))
        val core = Scripted(AppDeps.fake().core, { JoinResult.InvalidCode }, status)
        val h = holder(core, scope = backgroundScope, step = OnboardingStep.DOWNLOAD)
        runCurrent()
        assertEquals(DownloadPhase.OFFLINE, h.state.value.download.phase)
        status.value = status(listOf(pack(null, downloading = true)))
        runCurrent()
        h.retryDownload()
        runCurrent()
        assertEquals(DownloadPhase.DOWNLOADING, h.state.value.download.phase)
    }

    @Test
    fun aDownloadThatMakesNoProgressIsStalledAfterAMinuteAndRetryStartsTheWatchAgain() = runTest {
        val status = MutableStateFlow(status(listOf(pack(null, downloading = true))))
        val core = Scripted(AppDeps.fake().core, { JoinResult.InvalidCode }, status)
        val h = holder(core, scope = backgroundScope, step = OnboardingStep.DOWNLOAD, stallMs = 60_000)
        runCurrent()
        advanceTimeBy(59_000)
        assertEquals(DownloadPhase.DOWNLOADING, h.state.value.download.phase)
        advanceTimeBy(2_000)
        assertEquals(DownloadPhase.STALLED, h.state.value.download.phase)
        h.retryDownload()
        runCurrent()
        assertEquals(DownloadPhase.DOWNLOADING, h.state.value.download.phase)
        // new progress restarts the minute
        advanceTimeBy(50_000)
        status.value = status(listOf(pack(null, downloading = true), pack(null)))
        runCurrent()
        advanceTimeBy(50_000)
        assertEquals(DownloadPhase.DOWNLOADING, h.state.value.download.phase)
    }

    @Test
    fun theStallClockOnlyRunsOnTheDownloadStep() = runTest {
        val status = MutableStateFlow(status(listOf(pack(null, downloading = true))))
        val core = Scripted(AppDeps.fake(joined = false).core, { JoinResult.Joined("d") }, status)
        val h = holder(core, scope = backgroundScope, stallMs = 60_000)
        advanceTimeBy(120_000) // sitting on the invite ticket
        h.onCodeChange("k7m2qx9r")
        h.join()
        runCurrent()
        assertEquals(DownloadPhase.DOWNLOADING, h.state.value.download.phase)
    }

    @Test
    fun notificationsAreAskedForOnceAndSkippedWhenAlreadyAllowed() = runTest {
        val core = AppDeps.fake().core
        val platform = FakePlatformServices(notificationPermission = PermissionState.NOT_ASKED)
        val h = holder(core, platform, backgroundScope, OnboardingStep.DOWNLOAD)
        h.continueFromDownload()
        runCurrent()
        assertEquals(OnboardingStep.NOTIFICATIONS, h.state.value.step)
        h.allowNotifications()
        runCurrent()
        assertEquals(PermissionState.GRANTED, platform.notificationPermission.value)
        assertEquals(OnboardingStep.JOURNEY, h.state.value.step)

        val allowed = FakePlatformServices(notificationPermission = PermissionState.GRANTED)
        val h2 = holder(core, allowed, backgroundScope, OnboardingStep.DOWNLOAD)
        h2.continueFromDownload()
        runCurrent()
        assertEquals(OnboardingStep.JOURNEY, h2.state.value.step, "already allowed: no notifications step")
    }

    @Test
    fun notNowMovesOnWithoutAskingAndADeniedPhoneOpensItsSettings() = runTest {
        val core = AppDeps.fake().core
        val platform = FakePlatformServices(notificationPermission = PermissionState.NOT_ASKED)
        val h = holder(core, platform, backgroundScope, OnboardingStep.NOTIFICATIONS)
        h.skipNotifications()
        runCurrent()
        assertEquals(OnboardingStep.JOURNEY, h.state.value.step)
        assertEquals(PermissionState.NOT_ASKED, platform.notificationPermission.value)

        val denied = FakePlatformServices(notificationPermission = PermissionState.DENIED)
        val h2 = holder(core, denied, backgroundScope, OnboardingStep.NOTIFICATIONS)
        runCurrent()
        assertTrue(h2.state.value.notificationsDenied)
        h2.allowNotifications()
        runCurrent()
        assertEquals(1, denied.notificationSettingsOpened)
        assertEquals(OnboardingStep.JOURNEY, h2.state.value.step)
    }
}
