package app.transittracker.ui

import app.transittracker.core.FakeData
import app.transittracker.model.DepartureStatus
import app.transittracker.model.ServiceRef
import app.transittracker.model.WallClock
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.LiveData
import app.transittracker.ui.feature.service.ChipKind
import app.transittracker.ui.feature.service.FooterKind
import app.transittracker.ui.feature.service.MarkerKind
import app.transittracker.ui.feature.service.ServiceStateHolder
import app.transittracker.ui.feature.service.ServiceUiState
import app.transittracker.ui.feature.service.StopRole
import app.transittracker.ui.gallery.SampleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The service state holder against FakeTransitCore at Wed 11 Nov 07:38 (AppDeps.fake). The fake's
 * live data is a fixed mix keyed on the trip id: T9N-0745 is 4 minutes late, T9N-0730 is cancelled,
 * T9N-0700 has platform changes, T1E-0915 is 4 minutes late and under way at 09:38.
 */
class ServiceStateHolderTest {
    private val date = "20261111"
    private fun at(minutes: Int) = WallClock { SampleData.NOW_MS + minutes * 60_000L }
    private fun p(station: String, n: Int) = FakeData.platform(station, n)

    private suspend fun load(
        deps: AppDeps,
        scope: CoroutineScope,
        trip: String,
        board: String? = null,
        alight: String? = null,
    ): ServiceUiState {
        val h = ServiceStateHolder(deps.core, ServiceRef(trip, date), board, alight, deps.clock, scope)
        return h.state.first { !it.loading }
    }

    @Test
    fun aLateServiceThatHasNotLeftShowsLeavesAndNoMarker() = runTest {
        val deps = AppDeps.fake()
        val s = load(deps, backgroundScope, "T9N-0745", p(FakeData.STRATHFIELD, 3), p(FakeData.CENTRAL, 3))
        assertEquals(DepartureStatus.LATE, s.status)
        assertEquals(240, s.delaySeconds)
        assertEquals("T9", s.routeName)
        assertEquals("Gordon", s.headsign)
        assertEquals(ChipKind.LIVE, s.chip?.kind)
        assertEquals(0, s.boardIndex)
        assertEquals(1, s.alightIndex)
        assertEquals(listOf(StopRole.BOARD, StopRole.ALIGHT, StopRole.PLAIN, StopRole.PLAIN), s.rows.map { it.role })
        assertNull(s.marker)
        assertEquals(FooterKind.LEAVES, s.footer.kind)
        assertEquals("Strathfield", s.footer.stopName)
        assertEquals("3", s.footer.platform)
        // 07:45 timetabled, 4 minutes late: leaves at 07:49, 11 minutes from now.
        assertEquals(SampleData.NOW_MS + 11 * 60_000, s.footer.targetMs)
        assertTrue(s.rows[0].times!!.differs)
        assertEquals(8, s.carCount)
    }

    @Test
    fun aServiceUnderWayShowsTheMarkerAndThenCountsToTheDestination() = runTest {
        val board = p(FakeData.CENTRAL, 2)
        val alight = p(FakeData.CHATSWOOD, 2)
        // 09:38: Parramatta and Strathfield passed, Central leaves at 09:44 (4 minutes late).
        val before = load(AppDeps.fake(clock = at(120)), backgroundScope, "T1E-0915", board, alight)
        assertEquals(DepartureStatus.LATE, before.status)
        val m = assertNotNull(before.marker)
        assertEquals(1, m.afterIndex)
        assertEquals(MarkerKind.LIVE, m.kind)
        assertEquals(FooterKind.LEAVES, before.footer.kind)
        assertEquals(listOf(true, true, false, false, false), before.rows.map { it.passed })
        // 09:48: it has left Central.
        val after = load(AppDeps.fake(clock = at(130)), backgroundScope, "T1E-0915", board, alight)
        assertEquals(FooterKind.ARRIVES, after.footer.kind)
        assertEquals("Chatswood", after.footer.stopName)
    }

    @Test
    fun aCancelledServiceHasNoCountdownMarkerOrSkippedRows() = runTest {
        val s = load(AppDeps.fake(), backgroundScope, "T9N-0730", p(FakeData.STRATHFIELD, 3), p(FakeData.CENTRAL, 3))
        assertTrue(s.cancelled)
        assertEquals(FooterKind.CANCELLED, s.footer.kind)
        assertNull(s.marker)
        assertTrue(s.rows.none { it.skipped || it.passed }, "the whole run is struck, not marked skipped or passed")
        assertEquals(4, s.rows.size)
    }

    @Test
    fun staleDataGivesTheStaleChipAnApproximateCountdownAndAStaleMarker() = runTest {
        val deps = AppDeps.fake(clock = at(120), scenario = CoreScenario(live = LiveData.STALE))
        val s = load(deps, backgroundScope, "T1E-0915", p(FakeData.CENTRAL, 2), p(FakeData.CHATSWOOD, 2))
        assertTrue(s.stale)
        assertEquals(ChipKind.STALE, s.chip?.kind)
        assertTrue(s.footer.approximate)
        assertEquals(MarkerKind.STALE, s.marker?.kind)
    }

    @Test
    fun timetableOnlyHasNoLiveTimesAndAGhostMarker() = runTest {
        val deps = AppDeps.fake(clock = at(120), scenario = CoreScenario(live = LiveData.OFFLINE))
        val s = load(deps, backgroundScope, "T1E-0915", p(FakeData.CENTRAL, 2), p(FakeData.CHATSWOOD, 2))
        assertEquals(DepartureStatus.TIMETABLED, s.status)
        assertEquals(ChipKind.TIMETABLE, s.chip?.kind)
        assertNull(s.delaySeconds)
        assertEquals(MarkerKind.TIMETABLE, s.marker?.kind)
        assertTrue(s.rows.none { it.times?.differs == true })
        assertFalse(s.footer.approximate)
        assertEquals(FooterKind.LEAVES, s.footer.kind)
    }

    @Test
    fun anExtraServiceIsLiveWithoutTimetabledTimes() = runTest {
        val s = load(AppDeps.fake(), backgroundScope, "ADDED-T1-0812", p(FakeData.CENTRAL, 4), p(FakeData.PARRAMATTA, 1))
        assertEquals(DepartureStatus.ADDED, s.status)
        assertEquals(ChipKind.LIVE, s.chip?.kind)
        assertEquals(StopRole.BOARD, s.rows.first().role)
        assertEquals(StopRole.ALIGHT, s.rows.last().role)
        assertEquals(FooterKind.LEAVES, s.footer.kind)
    }

    @Test
    fun aChangedPlatformShowsOnTheRowAndTheFooter() = runTest {
        // 06:50, before T9N-0700 leaves: Strathfield and Central have moved to platform 4.
        val s = load(AppDeps.fake(clock = at(-48)), backgroundScope, "T9N-0700", p(FakeData.STRATHFIELD, 4), p(FakeData.CENTRAL, 4))
        assertEquals(listOf(true, true, false, false), s.rows.map { it.call.platformChanged })
        assertEquals("4", s.footer.platform)
        assertTrue(s.footer.platformChanged)
    }

    @Test
    fun withoutYourStopsTheStripAndFooterUseTheNextStop() = runTest {
        val s = load(AppDeps.fake(clock = at(120)), backgroundScope, "T1E-0915")
        assertNull(s.boardIndex)
        assertNull(s.alightIndex)
        assertTrue(s.rows.all { it.role == StopRole.PLAIN })
        assertEquals(FooterKind.NEXT, s.footer.kind)
        assertEquals("Central", s.footer.stopName)
        assertEquals(s.calls[2].stopId, s.stripBoardId)
        assertEquals(s.calls.last().stopId, s.stripAlightId)
    }

    @Test
    fun anUnknownServiceIsNotFound() = runTest {
        val s = load(AppDeps.fake(), backgroundScope, "NO-SUCH-TRIP")
        assertFalse(s.found)
        assertTrue(s.rows.isEmpty())
    }

    @Test
    fun yourStopsAreMatchedByTheIdsTheRouteCarries() = runTest {
        // The route carries the platform the list showed (4); the scheduled platform 3 matches nothing.
        val deps = AppDeps.fake(clock = at(-48))
        val right = load(deps, backgroundScope, "T9N-0700", p(FakeData.STRATHFIELD, 4), p(FakeData.CENTRAL, 4))
        assertEquals(0, right.boardIndex)
        assertEquals(1, right.alightIndex)
        val stale = load(deps, backgroundScope, "T9N-0700", p(FakeData.STRATHFIELD, 3), p(FakeData.CENTRAL, 3))
        assertNull(stale.boardIndex)
        assertNull(stale.alightIndex)
    }
}
