package app.transittracker.ui

import app.transittracker.core.InMemoryKeyValueStore
import app.transittracker.model.TestPushResponse
import app.transittracker.model.TransitCore
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchKind
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.LiveData
import app.transittracker.ui.feature.settings.PackState
import app.transittracker.ui.feature.settings.SettingsStateHolder
import app.transittracker.ui.feature.settings.SettingsUiState
import app.transittracker.ui.feature.settings.TestOutcome
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.platform.FakePlatformServices
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.settings.CardDensity
import app.transittracker.ui.settings.ClockFormat
import app.transittracker.ui.settings.KeyValueSettingsStore
import app.transittracker.ui.settings.ThemePreference
import app.transittracker.ui.settings.UiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Settings state holder against the fakes at Wed 11 Nov 07:38. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStateHolderTest {
    private val sydney = TimeZone.of("Australia/Sydney")

    private fun holder(deps: AppDeps, scope: CoroutineScope) =
        SettingsStateHolder(deps.core, deps.settings, deps.places, deps.platform, SampleData.clock, scope, sydney)

    private suspend fun SettingsStateHolder.loaded(): SettingsUiState = state.first { it.loaded && it.data.live.headline != "Checking" }

    @Test
    fun theStateGathersDataPlacesAndAbout() = runTest {
        val platform = FakePlatformServices(appVersion = "1.2.3 (45)", deviceId = "dev-42")
        val deps = AppDeps.fake(platform = platform)
        val s = holder(deps, backgroundScope).loaded()
        assertEquals(3, s.savedPlaces)
        assertEquals(4, s.savedRoutes)
        assertEquals("Live data is up to date.", s.data.live.headline)
        assertEquals(1, s.data.packs.size)
        assertEquals(PackState.UP_TO_DATE, s.data.packs.single().state)
        assertTrue(s.data.attribution.isNotBlank())
        assertEquals("1.2.3 (45)", s.appVersion)
        assertEquals("dev-42", s.deviceId)
        assertEquals(UiSettings(), s.display)
    }

    @Test
    fun scenariosShowAsOfflineDownloadingAndOutOfDate() = runTest {
        val offline = holder(AppDeps.fake(scenario = CoreScenario(live = LiveData.OFFLINE, packOutOfDate = true)), backgroundScope).loaded()
        assertTrue(offline.data.live.headline.startsWith("Offline"))
        assertEquals(PackState.OUT_OF_DATE, offline.data.packs.single().state)

        val busy = holder(AppDeps.fake(scenario = CoreScenario(packDownloading = true, live = LiveData.STALE)), backgroundScope).loaded()
        assertEquals(PackState.DOWNLOADING, busy.data.packs.single().state)
        assertEquals("Live data is out of date.", busy.data.live.headline)
    }

    @Test
    fun noTripsAndNoPlacesAreCountedAsZero() = runTest {
        val deps = AppDeps.fake(scenario = CoreScenario(noTrips = true), withPlaces = false)
        val s = holder(deps, backgroundScope).loaded()
        assertEquals(0, s.savedPlaces)
        assertEquals(0, s.savedRoutes)
    }

    @Test
    fun displayChangesTakeEffectAtOnceAndArePersisted() = runTest {
        val kv = InMemoryKeyValueStore()
        val deps = AppDeps.fake(kv = kv)
        val h = holder(deps, backgroundScope)
        h.loaded()

        h.setTheme(ThemePreference.DAY)
        h.setClockFormat(ClockFormat.H12)
        h.setCardDensity(CardDensity.COMPACT)
        h.setReduceMotion(true)
        h.setSounds(true)
        h.setHaptics(false)
        runCurrent()

        val expected = UiSettings(ThemePreference.DAY, ClockFormat.H12, CardDensity.COMPACT, reduceMotion = true, sounds = true, haptics = false)
        assertEquals(expected, h.state.value.display, "the page shows the change")
        assertEquals(expected, deps.settings.current(), "the store, which the shell reads, has it")
        assertEquals(expected, KeyValueSettingsStore(kv).current(), "and a new store over the same storage reads it back")
        assertEquals("DAY", kv.snapshot()["ui.theme"])

        h.setTheme(ThemePreference.SYSTEM)
        runCurrent()
        assertEquals(ThemePreference.SYSTEM, h.state.value.display.theme)
        assertEquals("SYSTEM", kv.snapshot()["ui.theme"])
    }

    @Test
    fun thePushRegistrationLineFollowsThePhone() = runTest {
        val platform = FakePlatformServices()
        val h = holder(AppDeps.fake(platform = platform), backgroundScope)
        assertEquals(null, h.loaded().pushRegistration)
        platform.pushRegistrationState.value = "Firebase did not give this phone a notification token: SERVICE_NOT_AVAILABLE"
        runCurrent()
        assertEquals(
            "Firebase did not give this phone a notification token: SERVICE_NOT_AVAILABLE",
            h.state.value.pushRegistration,
        )
    }

    @Test
    fun theNotificationPermissionAndBatterySaverFollowThePhone() = runTest {
        val platform = FakePlatformServices(notificationPermission = PermissionState.NOT_ASKED, reduceMotion = true)
        val h = holder(AppDeps.fake(platform = platform), backgroundScope)
        assertEquals(PermissionState.NOT_ASKED, h.loaded().notifications)
        assertTrue(h.state.value.systemReduceMotion)
        assertFalse(h.state.value.batterySaver)

        h.allowNotifications()
        runCurrent()
        assertEquals(PermissionState.GRANTED, h.state.value.notifications)

        platform.batterySaverState.value = true
        platform.notificationPermissionState.value = PermissionState.DENIED
        runCurrent()
        assertTrue(h.state.value.batterySaver)
        h.allowNotifications() // refused for good: the phone's settings open instead
        runCurrent()
        assertEquals(1, platform.notificationSettingsOpened)
        h.openPhoneSettings()
        assertEquals(2, platform.notificationSettingsOpened)
    }

    @Test
    fun aTestGoesThroughAWatchAndSaysSo() = runTest {
        val deps = AppDeps.fake() // the fake core starts with one watch
        val h = holder(deps, backgroundScope)
        h.loaded()
        h.sendTest()
        runCurrent()
        assertEquals(TestOutcome.SENT, h.state.value.test.outcome)
        assertFalse(h.state.value.test.sending)
        assertEquals("Test sent. It should arrive in a moment.", h.state.value.test.words)
    }

    @Test
    fun withoutAWatchTheTestSaysThereIsNoneAndDoesNotPretend() = runTest {
        val deps = AppDeps.fake()
        deps.core.deleteWatch("fake-watch-commute")
        val h = holder(deps, backgroundScope)
        h.loaded()
        h.sendTest()
        runCurrent()
        assertEquals(TestOutcome.NO_WATCH, h.state.value.test.outcome)
        assertTrue("no watch" in h.state.value.test.words!!.lowercase())
    }

    private class Scripted(val base: TransitCore, val send: suspend (String) -> TestPushResponse) : TransitCore by base {
        val tested = mutableListOf<String>()
        override suspend fun sendTestNotification(id: String): TestPushResponse {
            tested += id
            return send(id)
        }
    }

    private fun watch(id: String, pausedUntil: Long? = null) =
        WatchDto(id, WatchKind.COMMUTE, id, "a", "b", pausedUntilMs = pausedUntil)

    @Test
    fun theTestPrefersAWatchThatIsNotPausedAndReportsRefusalAndFailure() = runTest {
        val base = AppDeps.fake()
        base.core.deleteWatch("fake-watch-commute")
        base.core.saveWatch(watch("paused", pausedUntil = SampleData.NOW_MS + 1_000_000))
        base.core.saveWatch(watch("active"))
        val core = Scripted(base.core) { TestPushResponse(sent = false, detail = "Device has no push token") }
        val deps = AppDeps(core, base.planner, base.settings, base.places, base.platform, base.clock)
        val h = holder(deps, backgroundScope)
        h.loaded()
        h.sendTest()
        runCurrent()
        assertEquals(listOf("active"), core.tested)
        assertEquals(TestOutcome.REFUSED, h.state.value.test.outcome)
        assertEquals(
            "This phone has not registered for notifications with the server yet. Close and reopen the app, then try again.",
            h.state.value.test.words,
        )

        val failing = Scripted(base.core) { error("network down") }
        val h2 = holder(AppDeps(failing, base.planner, base.settings, base.places, base.platform, base.clock), backgroundScope)
        h2.loaded()
        h2.sendTest()
        runCurrent()
        assertEquals(TestOutcome.UNREACHABLE, h2.state.value.test.outcome)
    }
}
