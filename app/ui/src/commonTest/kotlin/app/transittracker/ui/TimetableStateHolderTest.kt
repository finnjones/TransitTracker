package app.transittracker.ui

import app.transittracker.core.FakeData
import app.transittracker.model.DepartureStatus
import app.transittracker.model.SavedTrip
import app.transittracker.model.TripLeg
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.fake.LiveData
import app.transittracker.ui.feature.home.BannerKind
import app.transittracker.ui.feature.home.DataChip
import app.transittracker.ui.feature.timetable.TimetableStateHolder
import app.transittracker.ui.feature.timetable.TimetableUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The timetable state holder against FakeTransitCore at Wed 11 Nov 07:38 (AppDeps.fake). */
class TimetableStateHolderTest {
    private val today = "20261111"

    private fun holder(deps: AppDeps, scope: CoroutineScope, tripId: String = FakeSetup.TRIP_HOME_WORK, date: String? = null) =
        TimetableStateHolder(deps.core, deps.places, deps.clock, tripId, date, scope)

    private suspend fun TimetableStateHolder.ready(extra: (TimetableUiState) -> Boolean = { true }): TimetableUiState =
        state.first { !it.loading && it.rowsLoaded && extra(it) }

    @Test
    fun todayHasEarlierServicesFoldedAndTheRestInOrder() = runTest {
        val s = holder(AppDeps.fake(), backgroundScope).ready { it.chip != null }
        assertEquals(today, s.date)
        assertTrue(s.isToday)
        assertFalse(s.canGoBack)
        assertTrue(s.canGoForward)
        assertEquals(listOf("Home", "Work"), s.titleParts)
        assertEquals("STRATHFIELD TO CENTRAL", s.stationsLine)
        assertTrue(s.earlier.isNotEmpty())
        assertTrue(s.earlier.all { it.journey.departure.expectedDepartureMs < s.nowMs })
        assertTrue(s.upcoming.all { it.journey.departure.expectedDepartureMs >= s.nowMs })
        val times = s.upcoming.map { it.journey.departure.expectedDepartureMs }
        assertEquals(times.sorted(), times)
        assertTrue(s.chip is DataChip.Live)
        assertFalse(s.timetableOnly, "services around now have live data")
        assertTrue(s.alerts.isEmpty())
    }

    @Test
    fun liveDataGivesLateAndCancelledServices() = runTest {
        val s = holder(AppDeps.fake(), backgroundScope).ready()
        val all = (s.earlier + s.upcoming).map { it.journey.departure }
        val late = all.filter { it.status == DepartureStatus.LATE }
        assertTrue(late.isNotEmpty())
        assertTrue(late.all { it.expectedDepartureMs > it.scheduledDepartureMs })
        // T9N-0745 is the 4-minutes-late service of the sample data.
        val t9 = all.first { it.ref.tripId == "T9N-0745" }
        assertEquals(240, t9.delaySeconds)
        val cancelled = all.filter { it.status == DepartureStatus.CANCELLED }
        assertTrue(cancelled.any { it.ref.tripId == "T9N-0730" })
        // A cancelled service stays in the list, in time order.
        assertTrue(s.earlier.any { it.journey.departure.status == DepartureStatus.CANCELLED })
    }

    @Test
    fun theDayStepsAndFlipsToTimetableOnly() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope)
        h.ready()
        h.moveDays(1)
        val tomorrow = h.ready { it.date == "20261112" }
        assertFalse(tomorrow.isToday)
        assertTrue(tomorrow.canGoBack)
        assertTrue(tomorrow.earlier.isEmpty())
        assertTrue(tomorrow.timetableOnly)
        assertTrue(tomorrow.upcoming.isNotEmpty())
        assertEquals("20261112", h.date.value)
        h.moveDays(-1)
        assertEquals(today, h.ready { it.date == today }.date)
        h.moveDays(2)
        h.goToToday()
        assertEquals(today, h.ready { it.date == today }.date)
    }

    @Test
    fun theDayStepsAcrossAMonthEnd() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope, date = "20261130")
        assertEquals("20261130", h.ready { it.date == "20261130" }.date)
        h.moveDays(1)
        assertEquals("20261201", h.ready { it.date == "20261201" }.date)
        h.setDate("20261231")
        h.moveDays(1)
        assertEquals("20270101", h.ready { it.date == "20270101" }.date)
    }

    @Test
    fun theRouteCanOpenOnAnotherDay() = runTest {
        val s = holder(AppDeps.fake(), backgroundScope, date = "20261112").ready()
        assertEquals("20261112", s.date)
        assertTrue(s.upcoming.isNotEmpty())
    }

    @Test
    fun alertsOnTheTripsLinesShowOnTheDaysTheyApply() = runTest {
        // The sample trackwork on the T9 runs from Fri 13 to Sun 15.
        val h = holder(AppDeps.fake(), backgroundScope, date = "20261113")
        val fri = h.ready { it.date == "20261113" }
        assertEquals(1, fri.alerts.size)
        assertTrue(fri.alerts.first().headline.startsWith("Trackwork"))
        h.setDate("20261112")
        assertTrue(h.ready { it.date == "20261112" }.alerts.isEmpty())
    }

    @Test
    fun staleDataShowsTheStaleChipAndBanner() = runTest {
        val s = holder(AppDeps.fake(scenario = CoreScenario(live = LiveData.STALE)), backgroundScope).ready { it.chip == DataChip.Stale }
        assertEquals(listOf(BannerKind.STALE), s.banners.map { it.kind })
        assertTrue((s.earlier + s.upcoming).any { it.journey.departure.stale })
    }

    @Test
    fun offlineIsTimetableOnlyWithTheOfflineBanner() = runTest {
        val s = holder(AppDeps.fake(scenario = CoreScenario(live = LiveData.OFFLINE)), backgroundScope).ready { it.chip == DataChip.Offline }
        assertTrue(s.timetableOnly)
        assertTrue(s.banners.any { it.kind == BannerKind.OFFLINE })
        assertTrue((s.earlier + s.upcoming).all { it.journey.departure.status == DepartureStatus.TIMETABLED })
    }

    @Test
    fun aTripNoLineRunsHasNoServicesAndNoNextOne() = runTest {
        val nowhere = SavedTrip("trip-nowhere", "Parramatta", "Tallawong", listOf(TripLeg(setOf(FakeData.PARRAMATTA), setOf(FakeData.TALLAWONG))))
        val deps = AppDeps.fake(trips = listOf(nowhere))
        val h = TimetableStateHolder(deps.core, deps.places, deps.clock, "trip-nowhere", null, backgroundScope, lookAheadDays = 3)
        val s = h.state.first { it.isEmpty && it.nextSearched }
        assertTrue(s.nothingToCome)
        assertTrue(s.earlier.isEmpty() && s.upcoming.isEmpty())
        assertNull(s.next)
    }

    @Test
    fun aMissingTripIsNotFound() = runTest {
        val s = holder(AppDeps.fake(), backgroundScope, tripId = "no-such-trip").state.first { !it.loading }
        assertFalse(s.tripFound)
    }
}
