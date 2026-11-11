package app.transittracker.ui

import app.transittracker.core.FakeData
import app.transittracker.core.TransitCoreException
import app.transittracker.model.TransitCore
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchKind
import app.transittracker.model.WatchRules
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.LiveData
import app.transittracker.ui.feature.notify.NotifyStateHolder
import app.transittracker.ui.feature.notify.NotifyUiState
import app.transittracker.ui.feature.notify.PAUSE_FOREVER_MS
import app.transittracker.ui.feature.notify.WATCH_LIMIT
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.platform.FakePlatformServices
import app.transittracker.ui.platform.PermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Notify list's state holder against FakeTransitCore at Wed 11 Nov 07:38. */
class NotifyStateHolderTest {
    private val sydney = TimeZone.of("Australia/Sydney")

    private fun holder(core: TransitCore, platform: FakePlatformServices, scope: CoroutineScope) =
        NotifyStateHolder(core, platform, SampleData.clock, scope, sydney)

    private val morning = WatchDto(
        "m", WatchKind.COMMUTE, "Morning", FakeData.STRATHFIELD, FakeData.CENTRAL,
        windowStartSecs = 7 * 3600 + 15 * 60, windowEndSecs = 8 * 3600,
    )
    private val evening = WatchDto(
        "e", WatchKind.COMMUTE, "Evening", FakeData.CENTRAL, FakeData.STRATHFIELD,
        windowStartSecs = 17 * 3600, windowEndSecs = 18 * 3600, rules = WatchRules(delayThresholdMin = 10),
    )

    private suspend fun AppDeps.seed(vararg watches: WatchDto) {
        core.deleteWatch("fake-watch-commute")
        for (w in watches) core.saveWatch(w)
    }

    private fun failing(base: TransitCore, error: Throwable, failures: IntArray = intArrayOf(Int.MAX_VALUE)): TransitCore =
        object : TransitCore by base {
            override suspend fun saveWatch(watch: WatchDto) {
                if (failures[0]-- > 0) throw error
                base.saveWatch(watch)
            }
        }

    @Test
    fun theListIsEmptyUntilThereIsAWatchAndSaysSo() = runTest {
        val deps = AppDeps.fake()
        deps.seed()
        val s = holder(deps.core, FakePlatformServices(), backgroundScope).state.first { !it.loading }
        assertTrue(s.isEmpty)
        assertEquals(0, s.count)
        assertEquals(WATCH_LIMIT, s.limit)
        assertTrue(s.canAdd) // the fake has saved trips
    }

    @Test
    fun commuteWatchesAreSummarisedAndOneOffsGoUnderToday() = runTest {
        val deps = AppDeps.fake()
        deps.seed(evening, morning)
        val ref = deps.core.upcoming(app.transittracker.ui.fake.FakeSetup.TRIP_HOME_WORK, 3).first().first().departure.ref
        deps.core.watchService(ref, WatchRules())
        deps.core.saveWatch(
            WatchDto("later", WatchKind.SINGLE_SERVICE, "T1 to Gordon", "A", "B", tripId = "x", serviceDate = "20261113"),
        )
        val s = holder(deps.core, FakePlatformServices(), backgroundScope).state.first { !it.loading && it.count == 4 }
        // Earliest window first.
        assertEquals(listOf("m", "e"), s.commutes.map { it.id })
        assertEquals(
            "Strathfield ▶ Central · Mon–Fri · 07:15–08:00 · late by 5 min",
            s.commutes.first().summary.line,
        )
        assertEquals("Central ▶ Strathfield · Mon–Fri · 17:00–18:00 · late by 10 min", s.commutes.last().summary.line)
        assertEquals(1, s.today.size)
        assertEquals(listOf("later"), s.later.map { it.id })
        assertEquals(WatchKind.SINGLE_SERVICE, s.today.single().watch.kind)
        assertTrue(s.commutes.all { it.active })
    }

    @Test
    fun theSlotCountIsTheNumberOfWatches() = runTest {
        val deps = AppDeps.fake()
        deps.seed(morning, evening)
        val s = holder(deps.core, FakePlatformServices(), backgroundScope).state.first { it.count == 2 }
        assertEquals(2, s.count)
        assertFalse(s.full)
        for (i in 1..8) deps.core.saveWatch(morning.copy(id = "extra-$i"))
        val full = holder(deps.core, FakePlatformServices(), backgroundScope).state.first { it.count == WATCH_LIMIT }
        assertTrue(full.full)
        assertFalse(full.canAdd)
    }

    @Test
    fun pausingAndResumingSavesThePauseTime() = runTest {
        val deps = AppDeps.fake()
        deps.seed(morning)
        val h = holder(deps.core, FakePlatformServices(), backgroundScope)
        h.state.first { it.count == 1 }
        h.setPaused("m", true)
        val paused = h.state.first { s -> s.commutes.singleOrNull()?.active == false }
        assertEquals("PAUSED", paused.commutes.single().pausedWords)
        assertEquals(SampleData.NOW_MS + PAUSE_FOREVER_MS, deps.core.watches.first().single().pausedUntilMs)
        h.setPaused("m", false)
        h.state.first { s -> s.commutes.singleOrNull()?.active == true }
        assertNull(deps.core.watches.first().single().pausedUntilMs)
    }

    @Test
    fun aFailedChangeBecomesAProblemWithRetryAndClose() = runTest {
        val deps = AppDeps.fake()
        deps.seed(morning)
        val failures = intArrayOf(1)
        val core = failing(deps.core, TransitCoreException("Could not save watch: timeout"), failures)
        val h = holder(core, FakePlatformServices(), backgroundScope)
        h.state.first { it.count == 1 }
        h.setPaused("m", true)
        val problem = h.state.first { it.problem != null }.problem!!
        assertEquals("No connection, so the watch was not changed. Check your internet and try again.", problem.words)
        assertNull(deps.core.watches.first().single().pausedUntilMs)
        // RETRY goes through this time.
        h.retry()
        h.state.first { it.problem == null && it.commutes.singleOrNull()?.active == false }
        assertEquals(SampleData.NOW_MS + PAUSE_FOREVER_MS, deps.core.watches.first().single().pausedUntilMs)
        // CLOSE drops the problem without trying again.
        val alwaysFailing = failing(deps.core, TransitCoreException("x", 500))
        val h2 = holder(alwaysFailing, FakePlatformServices(), backgroundScope)
        h2.state.first { it.count == 1 }
        h2.setPaused("m", false)
        h2.state.first { it.problem != null }
        h2.dismissProblem()
        assertNull(h2.state.first { it.problem == null }.problem)
    }

    @Test
    fun offlineTheListIsReadOnly() = runTest {
        val deps = AppDeps.fake(scenario = CoreScenario(live = LiveData.OFFLINE))
        deps.seed(morning)
        val h = holder(deps.core, FakePlatformServices(), backgroundScope)
        val s = h.state.first { it.count == 1 && it.readOnly }
        assertFalse(s.canAdd)
        h.setPaused("m", true)
        assertNull(deps.core.watches.first().single().pausedUntilMs)
    }

    @Test
    fun theAllowBannerFollowsThePermission() = runTest {
        val deps = AppDeps.fake()
        deps.seed(morning)
        val platform = FakePlatformServices(notificationPermission = PermissionState.NOT_ASKED)
        val h = holder(deps.core, platform, backgroundScope)
        assertTrue(h.state.first { it.count == 1 }.notificationsBlocked)
        // First time: the system asks.
        h.allowNotifications()
        val after: NotifyUiState = h.state.first { !it.notificationsBlocked }
        assertFalse(after.notificationsBlocked)
        assertEquals(0, platform.notificationSettingsOpened)
        // Once refused the system will not ask again: the phone's settings open instead.
        val denied = FakePlatformServices(notificationPermission = PermissionState.DENIED)
        val h2 = holder(deps.core, denied, backgroundScope)
        assertTrue(h2.state.first { it.count == 1 }.notificationsBlocked)
        h2.allowNotifications()
        h2.state.first { it.count == 1 }
        kotlinx.coroutines.yield()
        assertEquals(1, denied.notificationSettingsOpened)
        assertTrue(h2.state.value.notificationsBlocked)
    }

    @Test
    fun batterySaverIsPassedOn() = runTest {
        val deps = AppDeps.fake()
        deps.seed(morning)
        val h = holder(deps.core, FakePlatformServices(batterySaver = true), backgroundScope)
        assertTrue(h.state.first { it.count == 1 }.batterySaver)
    }
}
