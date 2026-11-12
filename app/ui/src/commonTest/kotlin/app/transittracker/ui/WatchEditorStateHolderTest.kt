package app.transittracker.ui

import app.transittracker.core.FakeData
import app.transittracker.core.TransitCoreException
import app.transittracker.model.TestPushResponse
import app.transittracker.model.TransitCore
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchKind
import app.transittracker.model.WatchRules
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.feature.notify.EditorMode
import app.transittracker.ui.feature.notify.ResultKind
import app.transittracker.ui.feature.notify.TEST_FAILED_WORDS
import app.transittracker.ui.feature.notify.TEST_NEEDS_SAVE_WORDS
import app.transittracker.ui.feature.notify.TEST_SENT_WORDS
import app.transittracker.ui.feature.notify.WATCH_LIMIT
import app.transittracker.ui.feature.notify.WatchEditorState
import app.transittracker.ui.feature.notify.WatchEditorStateHolder
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.nav.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The watch editor's state holder against FakeTransitCore at Wed 11 Nov 07:38. */
class WatchEditorStateHolderTest {
    private val sydney = TimeZone.of("Australia/Sydney")

    private fun holder(core: TransitCore, route: Route.WatchEditor, scope: CoroutineScope) =
        WatchEditorStateHolder(core, route, SampleData.clock, scope, sydney, newId = { "new-watch" })

    private suspend fun WatchEditorStateHolder.ready(): WatchEditorState = state.first { !it.loading }

    private val existing = WatchDto(
        "m", WatchKind.COMMUTE, "Morning", FakeData.STRATHFIELD, FakeData.CENTRAL,
        daysOfWeek = listOf(1, 2, 3, 4, 5), windowStartSecs = 7 * 3600 + 15 * 60, windowEndSecs = 8 * 3600,
    )

    private suspend fun AppDeps.seed(vararg watches: WatchDto) {
        core.deleteWatch("fake-watch-commute")
        for (w in watches) core.saveWatch(w)
    }

    private fun failing(base: TransitCore, error: Throwable): TransitCore = object : TransitCore by base {
        override suspend fun saveWatch(watch: WatchDto) = throw error
        override suspend fun deleteWatch(id: String) = throw error
        override suspend fun sendTestNotification(id: String): TestPushResponse = throw error
    }

    // ---------------------------------------------------------------- starting points

    @Test
    fun aNewCommuteWatchStartsFromTheSavedTrip() = runTest {
        val deps = AppDeps.fake()
        deps.seed()
        val s = holder(deps.core, Route.WatchEditor(fromTripId = FakeSetup.TRIP_HOME_WORK), backgroundScope).ready()
        assertEquals(EditorMode.NEW_COMMUTE, s.mode)
        assertEquals(WatchKind.COMMUTE, s.kind)
        assertEquals("Strathfield", s.ticket?.from)
        assertEquals("Central", s.ticket?.to)
        assertEquals("ANY LINE", s.ticket?.detail)
        assertEquals(setOf(1, 2, 3, 4, 5), s.days)
        // 07:38 now: from the next quarter hour, for 45 minutes.
        assertEquals(7 * 3600 + 45 * 60, s.startSecs)
        assertEquals(8 * 3600 + 30 * 60, s.endSecs)
        assertEquals(45, s.leadMinutes)
        assertEquals(5, s.thresholdMinutes)
        assertEquals("WATCH 1 OF 10", s.slot)
        assertFalse(s.canDelete)
        assertTrue(s.canSave)
    }

    @Test
    fun aNewOneOffWatchStartsFromTheService() = runTest {
        val deps = AppDeps.fake()
        deps.seed()
        val dep = deps.core.upcoming(FakeSetup.TRIP_HOME_WORK, 3).first().first().departure
        val route = Route.WatchEditor(fromService = dep.ref, boardStopId = dep.originStopId, alightStopId = dep.destinationStopId)
        val s = holder(deps.core, route, backgroundScope).ready()
        assertEquals(EditorMode.NEW_ONE_OFF, s.mode)
        assertEquals(WatchKind.SINGLE_SERVICE, s.kind)
        assertFalse(s.isCommute)
        assertNotNull(s.ticket)
        assertTrue(s.ticket.detail.contains("LEAVES"), s.ticket.detail)
        assertTrue(s.canSave) // no days to pick for a single service
    }

    @Test
    fun anExistingWatchIsLoadedAsItIs() = runTest {
        val deps = AppDeps.fake()
        deps.seed(existing.copy(rules = WatchRules(delayThresholdMin = 8, notifyRecovered = true, notifyPlatform = false)))
        val s = holder(deps.core, Route.WatchEditor(watchId = "m"), backgroundScope).ready()
        assertEquals(EditorMode.EXISTING, s.mode)
        assertEquals(8, s.thresholdMinutes)
        assertTrue(s.notifyRecovered)
        assertFalse(s.notifyPlatform)
        assertEquals(7 * 3600 + 15 * 60, s.startSecs)
        assertEquals("WATCH 1 OF 10", s.slot)
        assertTrue(s.canDelete)
    }

    @Test
    fun aWatchThatIsGoneIsMissing() = runTest {
        val deps = AppDeps.fake()
        val s = holder(deps.core, Route.WatchEditor(watchId = "nope"), backgroundScope).ready()
        assertTrue(s.missing)
        assertFalse(s.canSave)
        assertTrue(holder(deps.core, Route.WatchEditor(), backgroundScope).ready().missing)
        assertTrue(holder(deps.core, Route.WatchEditor(fromTripId = "no-such-trip"), backgroundScope).ready().missing)
    }

    // ---------------------------------------------------------------- editing

    @Test
    fun theSteppersStopAtTheirLimits() = runTest {
        val deps = AppDeps.fake()
        deps.seed(existing.copy(windowStartSecs = 7 * 3600 + 45 * 60, windowEndSecs = 8 * 3600))
        val h = holder(deps.core, Route.WatchEditor(watchId = "m"), backgroundScope)
        var s = h.ready()
        assertFalse(s.canStartLater) // already one step before the end
        h.stepStart(1)
        assertEquals(7 * 3600 + 45 * 60, h.state.value.startSecs)
        h.stepEnd(1)
        s = h.state.value
        assertEquals(8 * 3600 + 15 * 60, s.endSecs)
        assertTrue(h.state.value.canStartLater)
        h.stepStart(-1)
        assertEquals(7 * 3600 + 30 * 60, h.state.value.startSecs)
        h.stepLead(1)
        assertEquals(60, h.state.value.leadMinutes)
        h.stepThreshold(-1)
        h.stepThreshold(-1)
        assertEquals(3, h.state.value.thresholdMinutes)
        repeat(5) { h.stepThreshold(-1) }
        assertEquals(1, h.state.value.thresholdMinutes)
        assertFalse(h.state.value.canThresholdLower)
    }

    @Test
    fun withNoDayPickedTheWatchCannotBeSaved() = runTest {
        val deps = AppDeps.fake()
        deps.seed(existing)
        val h = holder(deps.core, Route.WatchEditor(watchId = "m"), backgroundScope)
        h.ready()
        h.setDays(emptySet())
        val s = h.state.value
        assertEquals("Pick at least one day.", s.problem)
        assertFalse(s.canSave)
        h.save()
        assertFalse(h.state.value.saving)
        assertEquals(existing.daysOfWeek, deps.core.watches.first().single().daysOfWeek)
    }

    // ---------------------------------------------------------------- saving

    @Test
    fun savingANewCommuteWatchStoresIt() = runTest {
        val deps = AppDeps.fake()
        deps.seed()
        val h = holder(deps.core, Route.WatchEditor(fromTripId = FakeSetup.TRIP_HOME_WORK), backgroundScope)
        h.ready()
        h.setDays(setOf(1, 3))
        h.stepThreshold(1)
        h.setNotifyRecovered(true)
        h.save()
        h.state.first { it.finished }
        val saved = deps.core.watches.first().single()
        assertEquals("new-watch", saved.id)
        assertEquals(WatchKind.COMMUTE, saved.kind)
        assertEquals(FakeData.STRATHFIELD, saved.originStationId)
        assertEquals(FakeData.CENTRAL, saved.destinationStationId)
        assertEquals(listOf(1, 3), saved.daysOfWeek)
        assertEquals(7 * 3600 + 45 * 60, saved.windowStartSecs)
        assertEquals(6, saved.rules.delayThresholdMin)
        assertTrue(saved.rules.notifyRecovered)
        assertEquals("Strathfield to Central", saved.label)
    }

    @Test
    fun savingAnExistingWatchKeepsItsIdentity() = runTest {
        val deps = AppDeps.fake()
        deps.seed(existing.copy(pausedUntilMs = 123L, routeIds = listOf("T1")))
        val h = holder(deps.core, Route.WatchEditor(watchId = "m"), backgroundScope)
        h.ready()
        h.stepLead(-1)
        h.setNotifyCancel(false)
        h.save()
        h.state.first { it.finished }
        val saved = deps.core.watches.first().single()
        assertEquals("m", saved.id)
        assertEquals(30, saved.leadMinutes)
        assertFalse(saved.rules.notifyCancel)
        assertEquals(123L, saved.pausedUntilMs)
        assertEquals(listOf("T1"), saved.routeIds)
    }

    @Test
    fun savingAOneOffWatchAsksTheCoreToWatchTheService() = runTest {
        val deps = AppDeps.fake()
        deps.seed()
        val ref = deps.core.upcoming(FakeSetup.TRIP_HOME_WORK, 3).first().first().departure.ref
        val h = holder(deps.core, Route.WatchEditor(fromService = ref), backgroundScope)
        h.ready()
        h.stepThreshold(1)
        h.save()
        h.state.first { it.finished }
        val saved = deps.core.watches.first().single()
        assertEquals(WatchKind.SINGLE_SERVICE, saved.kind)
        assertEquals(ref.tripId, saved.tripId)
        assertEquals(6, saved.rules.delayThresholdMin)
    }

    @Test
    fun aFailedSaveSaysWhyInAPlainSentenceAndStaysOnTheScreen() = runTest {
        val deps = AppDeps.fake()
        deps.seed()
        val core = failing(deps.core, TransitCoreException("Could not save watch: connect timed out"))
        val h = holder(core, Route.WatchEditor(fromTripId = FakeSetup.TRIP_HOME_WORK), backgroundScope)
        h.ready()
        h.save()
        val s = h.state.first { it.result != null }
        assertEquals("No connection, so the watch was not saved. Check your internet and try again.", s.result?.text)
        assertEquals(ResultKind.ERROR, s.result?.kind)
        assertFalse(s.saving)
        assertFalse(s.finished)
        assertTrue(s.canSave) // the user can try again
        assertTrue(deps.core.watches.first().isEmpty())
    }

    @Test
    fun theServersRefusalToTakeAnEleventhWatchIsExplained() = runTest {
        val deps = AppDeps.fake()
        deps.seed()
        val core = failing(deps.core, TransitCoreException("Could not save watch: Too many watches", status = 409))
        val h = holder(core, Route.WatchEditor(fromTripId = FakeSetup.TRIP_HOME_WORK), backgroundScope)
        h.ready()
        h.save()
        assertEquals(
            "You already have $WATCH_LIMIT watches. Delete one to add another.",
            h.state.first { it.result != null }.result?.text,
        )
    }

    @Test
    fun aFullListStopsANewWatchWithoutAskingTheServer() = runTest {
        val deps = AppDeps.fake()
        deps.seed(*Array(WATCH_LIMIT) { existing.copy(id = "w$it") })
        val h = holder(deps.core, Route.WatchEditor(fromTripId = FakeSetup.TRIP_HOME_WORK), backgroundScope)
        val s = h.ready()
        assertEquals("WATCH 10 OF 10", s.slot)
        h.save()
        val after = h.state.first { it.result != null }
        assertEquals("You already have $WATCH_LIMIT watches. Delete one to add another.", after.result?.text)
        assertFalse(after.finished)
        assertEquals(WATCH_LIMIT, deps.core.watches.first().size)
        // Editing one of the ten is still fine.
        val edit = holder(deps.core, Route.WatchEditor(watchId = "w3"), backgroundScope)
        edit.ready()
        edit.save()
        edit.state.first { it.finished }
    }

    // ---------------------------------------------------------------- test push and delete

    @Test
    fun sendTestNeedsASavedWatchThenReportsTheOutcome() = runTest {
        val deps = AppDeps.fake()
        deps.seed(existing)
        val fresh = holder(deps.core, Route.WatchEditor(fromTripId = FakeSetup.TRIP_HOME_WORK), backgroundScope)
        fresh.ready()
        fresh.sendTest()
        assertEquals(TEST_NEEDS_SAVE_WORDS, fresh.state.value.result?.text)
        assertEquals(ResultKind.INFO, fresh.state.value.result?.kind)

        val saved = holder(deps.core, Route.WatchEditor(watchId = "m"), backgroundScope)
        saved.ready()
        saved.sendTest()
        val sent = saved.state.first { it.result != null }
        assertEquals(TEST_SENT_WORDS, sent.result?.text)
        assertEquals(ResultKind.OK, sent.result?.kind)
        assertFalse(sent.testing)

        val broken = holder(failing(deps.core, TransitCoreException("x")), Route.WatchEditor(watchId = "m"), backgroundScope)
        broken.ready()
        broken.sendTest()
        assertEquals(TEST_FAILED_WORDS, broken.state.first { it.result != null }.result?.text)
    }

    @Test
    fun deletingNeedsConfirmationAndThenRemovesTheWatch() = runTest {
        val deps = AppDeps.fake()
        deps.seed(existing)
        val h = holder(deps.core, Route.WatchEditor(watchId = "m"), backgroundScope)
        h.ready()
        h.askDelete()
        assertTrue(h.state.value.confirmDelete)
        h.cancelDelete()
        assertFalse(h.state.value.confirmDelete)
        assertEquals(1, deps.core.watches.first().size)
        h.askDelete()
        h.confirmDelete()
        val s = h.state.first { it.finished }
        assertFalse(s.confirmDelete)
        assertTrue(deps.core.watches.first().isEmpty())
    }

    @Test
    fun aFailedDeleteKeepsTheWatchAndSaysWhy() = runTest {
        val deps = AppDeps.fake()
        deps.seed(existing)
        val h = holder(failing(deps.core, TransitCoreException("Could not delete watch: timeout")), Route.WatchEditor(watchId = "m"), backgroundScope)
        h.ready()
        h.askDelete()
        h.confirmDelete()
        val s = h.state.first { it.result != null }
        assertEquals("No connection, so the watch was not deleted. Check your internet and try again.", s.result?.text)
        assertFalse(s.finished)
        assertFalse(s.deleting)
        assertEquals(1, deps.core.watches.first().size)
    }

    @Test
    fun aNewWatchHasNothingToDelete() = runTest {
        val deps = AppDeps.fake()
        val h = holder(deps.core, Route.WatchEditor(fromTripId = FakeSetup.TRIP_HOME_WORK), backgroundScope)
        h.ready()
        h.askDelete()
        assertFalse(h.state.value.confirmDelete)
    }
}
