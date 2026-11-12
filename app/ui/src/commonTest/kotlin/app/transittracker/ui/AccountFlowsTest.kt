package app.transittracker.ui

import app.transittracker.core.InMemoryKeyValueStore
import app.transittracker.model.TransitCore
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.feature.onboarding.JoinStatus
import app.transittracker.ui.feature.onboarding.OnboardingStateHolder
import app.transittracker.ui.feature.onboarding.OnboardingStep
import app.transittracker.ui.feature.settings.SettingsStateHolder
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.platform.Account
import app.transittracker.ui.platform.FakeAccountService
import app.transittracker.ui.platform.FakePlatformServices
import app.transittracker.ui.platform.SignInResult
import app.transittracker.ui.settings.KeyValueSettingsStore
import app.transittracker.ui.settings.ThemePreference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Signing in instead of an invite code: onboarding's first step and Settings' account rows. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountFlowsTest {
    private val sam = Account("u1", "sam@example.com", "Sam")

    private fun success(token: String, restored: Boolean = false) = SignInResult.Success(sam, token, restored)

    private fun onboarding(deps: AppDeps, scope: kotlinx.coroutines.CoroutineScope, onRestored: suspend () -> Unit = {}) =
        OnboardingStateHolder(deps.core, deps.platform, scope, OnboardingStep.INVITE, onRestored = onRestored)

    @Test
    fun signingInJoinsAndMovesOnToTheDownload() = runTest {
        val accounts = FakeAccountService(nextResult = success("good-token"))
        val deps = AppDeps.fake(joined = false, platform = FakePlatformServices(accounts = accounts))
        val h = onboarding(deps, backgroundScope)
        runCurrent()
        assertTrue(h.state.value.signIn)
        assertTrue(h.state.value.canJoin) // no code needed
        h.join()
        runCurrent()
        assertEquals(OnboardingStep.DOWNLOAD, h.state.value.step)
        assertEquals(JoinStatus.IDLE, h.state.value.join)
        assertTrue(deps.core.joined.first())
        assertEquals(sam, accounts.account.value)
    }

    @Test
    fun anAccountTheServerRefusesIsSignedOutAndSaysSo() = runTest {
        val accounts = FakeAccountService(nextResult = success("denied-token"))
        val deps = AppDeps.fake(joined = false, platform = FakePlatformServices(accounts = accounts))
        val h = onboarding(deps, backgroundScope)
        runCurrent()
        h.join()
        runCurrent()
        assertEquals(JoinStatus.NOT_ALLOWED, h.state.value.join)
        assertEquals("This account is not allowed on this server.", h.state.value.joinMessage)
        assertEquals(OnboardingStep.INVITE, h.state.value.step)
        assertNull(accounts.account.value)
        assertEquals(1, accounts.signOuts)
        assertFalse(deps.core.joined.first())
    }

    @Test
    fun cancelledFailedAndUnreachableSignIns() = runTest {
        val accounts = FakeAccountService(nextResult = SignInResult.Cancelled)
        val deps = AppDeps.fake(joined = false, platform = FakePlatformServices(accounts = accounts))
        val h = onboarding(deps, backgroundScope)
        runCurrent()
        h.join()
        runCurrent()
        assertEquals(JoinStatus.IDLE, h.state.value.join)

        accounts.nextResult = SignInResult.Failed("No Google account")
        h.join()
        runCurrent()
        assertEquals(JoinStatus.FAILED, h.state.value.join)
        assertEquals("No Google account", h.state.value.joinMessage)

        accounts.nextResult = success("offline-token")
        h.join()
        runCurrent()
        assertEquals(JoinStatus.UNREACHABLE, h.state.value.join)
        assertNull(h.state.value.joinMessage)
    }

    @Test
    fun dataRestoredFromTheAccountIsReadAgain() = runTest {
        val accounts = FakeAccountService(nextResult = success("good-token", restored = true))
        val deps = AppDeps.fake(joined = false, platform = FakePlatformServices(accounts = accounts))
        var reloads = 0
        val h = onboarding(deps, backgroundScope) { reloads++ }
        runCurrent()
        h.join()
        runCurrent()
        assertEquals(1, reloads)
    }

    @Test
    fun reloadSavedReadsSettingsAndPlacesWrittenUnderneath() = runTest {
        val kv = InMemoryKeyValueStore()
        val deps = AppDeps.fake(kv = kv, trips = null)
        assertEquals(ThemePreference.NIGHT, deps.settings.current().theme) // read and cached
        kv.put(KeyValueSettingsStore.KEY_THEME, ThemePreference.DAY.name) // restored underneath
        assertEquals(ThemePreference.NIGHT, deps.settings.current().theme)
        deps.reloadSaved()
        assertEquals(ThemePreference.DAY, deps.settings.current().theme)
    }

    @Test
    fun settingsShowsTheAccountAndSignOutReturnsToOnboarding() = runTest {
        val accounts = FakeAccountService(account = sam)
        val deps = AppDeps.fake(platform = FakePlatformServices(accounts = accounts))
        val h = SettingsStateHolder(deps.core, deps.settings, deps.places, deps.platform, SampleData.clock, backgroundScope, TimeZone.of("Australia/Sydney"))
        val s = h.state.first { it.loaded }
        assertTrue(s.accountsAvailable)
        assertEquals("sam@example.com", s.account)
        h.signOut()
        runCurrent()
        assertNull(h.state.value.account)
        assertEquals(1, accounts.signOuts)
        assertFalse(deps.core.joined.first())
    }

    @Test
    fun settingsSignInKeepsTheJoinAndReloadsRestoredTrips() = runTest {
        val accounts = FakeAccountService(nextResult = success("t", restored = true))
        val deps = AppDeps.fake(platform = FakePlatformServices(accounts = accounts))
        val h = SettingsStateHolder(deps.core, deps.settings, deps.places, deps.platform, SampleData.clock, backgroundScope, TimeZone.of("Australia/Sydney"))
        assertNull(h.state.first { it.loaded }.account)
        h.signIn()
        runCurrent()
        assertEquals("sam@example.com", h.state.value.account)
        assertTrue(deps.core.joined.first())
    }

    @Test
    fun withoutAnAccountServiceTheInviteCodeStays() = runTest {
        val deps: AppDeps = AppDeps.fake(joined = false)
        val core: TransitCore = deps.core
        val h = OnboardingStateHolder(core, deps.platform, backgroundScope)
        runCurrent()
        assertFalse(h.state.value.signIn)
        assertFalse(h.state.value.canJoin)
    }
}
