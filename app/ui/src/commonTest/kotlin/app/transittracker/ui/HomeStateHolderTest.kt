package app.transittracker.ui

import app.transittracker.model.DepartureStatus
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.fake.LiveData
import app.transittracker.ui.feature.home.BannerKind
import app.transittracker.ui.feature.home.DataChip
import app.transittracker.ui.feature.home.HomeStateHolder
import app.transittracker.ui.feature.home.HomeUiState
import app.transittracker.ui.feature.home.LeaveByState
import app.transittracker.ui.settings.CardDensity
import app.transittracker.ui.settings.UiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The home state holder against FakeTransitCore at Wed 11 Nov 07:38 (AppDeps.fake). */
class HomeStateHolderTest {
    private fun holder(deps: AppDeps, scope: CoroutineScope) =
        HomeStateHolder(deps.core, deps.places, deps.settings, deps.clock, scope)

    private suspend fun HomeStateHolder.ready(extra: (HomeUiState) -> Boolean = { true }): HomeUiState =
        state.first { !it.loading && extra(it) }

    @Test
    fun liveDataGivesFoldersHeadlinesAndALiveChip() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope)
        val s = h.ready { st -> st.folders.flatMap { it.cards }.all { it.headline != null } }
        assertEquals(listOf("To work", "Home"), s.folders.map { it.name })
        assertTrue(s.chip is DataChip.Live)
        assertTrue(s.banners.isEmpty())
        val cards = s.folders.flatMap { it.cards }
        // The first trip of each folder is a full card, the rest compact.
        assertEquals(listOf(true, false, true, false), cards.map { it.full })
        for (c in cards) {
            val headline = assertNotNull(c.headline, c.trip.id)
            assertTrue(headline.legs.none { it.status == DepartureStatus.CANCELLED }, "cancelled headline on ${c.trip.id}")
            assertTrue(c.following.size <= 2)
        }
        // Home to Work is titled with the places and the stations beneath.
        val first = cards.first()
        assertEquals(listOf("Home", "Work"), first.titleParts)
        assertEquals("STRATHFIELD TO CENTRAL", first.stationsLine)
        // The multi-leg trip shows its change.
        val multi = cards.first { it.trip.id == FakeSetup.TRIP_TOWNHALL_TALLAWONG }
        assertEquals(1, multi.changes.size)
    }

    @Test
    fun lateServicesAreLiveAndCancelledOnesNeverHeadline() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope)
        val s = h.ready { st -> st.folders.flatMap { it.cards }.all { it.headline != null } }
        val all = s.folders.flatMap { it.cards }.flatMap { listOfNotNull(it.headline) + it.following }.map { it.departure }
        val late = all.filter { it.status == DepartureStatus.LATE }
        assertTrue(late.isNotEmpty(), "the fake has late services around 07:38")
        assertTrue(late.all { it.expectedDepartureMs > it.scheduledDepartureMs && (it.delaySeconds ?: 0) >= 60 })
        for (card in s.folders.flatMap { it.cards }) {
            assertTrue(card.headline!!.legs.none { it.status == DepartureStatus.CANCELLED })
        }
    }

    @Test
    fun staleDataShowsTheChipBannerAndStaleDepartures() = runTest {
        val h = holder(AppDeps.fake(scenario = CoreScenario(live = LiveData.STALE)), backgroundScope)
        val s = h.ready { it.chip == DataChip.Stale && it.folders.isNotEmpty() && it.folders[0].cards[0].headline != null }
        assertEquals(listOf(BannerKind.STALE), s.banners.map { it.kind })
        val d = s.folders[0].cards[0].headline!!.departure
        assertTrue(d.stale)
    }

    @Test
    fun offlineShowsTheTimetable() = runTest {
        val h = holder(AppDeps.fake(scenario = CoreScenario(live = LiveData.OFFLINE)), backgroundScope)
        val s = h.ready { it.chip == DataChip.Offline && it.folders.isNotEmpty() && it.folders[0].cards[0].headline != null }
        assertEquals(listOf(BannerKind.OFFLINE), s.banners.map { it.kind })
        val all = s.folders.flatMap { it.cards }.mapNotNull { it.headline?.departure }
        assertTrue(all.all { it.status == DepartureStatus.TIMETABLED })
    }

    @Test
    fun noTripsIsTheEmptyState() = runTest {
        val h = holder(AppDeps.fake(trips = emptyList()), backgroundScope)
        val s = h.ready()
        assertTrue(s.isEmpty)
        assertTrue(s.folders.isEmpty())
    }

    @Test
    fun compactDensityMakesEveryCardCompact() = runTest {
        val h = holder(AppDeps.fake(settings = UiSettings(cardDensity = CardDensity.COMPACT)), backgroundScope)
        val s = h.ready()
        assertTrue(s.folders.flatMap { it.cards }.none { it.full })
    }

    @Test
    fun bannersFollowTheDataStatus() = runTest {
        val h = holder(
            AppDeps.fake(scenario = CoreScenario(statusMessage = "Maintenance tonight", packDownloading = true, packOutOfDate = true)),
            backgroundScope,
        )
        val s = h.ready { it.banners.size == 3 }
        assertEquals(listOf(BannerKind.MESSAGE, BannerKind.UPDATING, BannerKind.OUT_OF_DATE), s.banners.map { it.kind })
        assertEquals("Maintenance tonight", s.banners[0].text)
    }

    @Test
    fun aTripFromAPlaceHasLeaveByOthersDoNot() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope)
        val s = h.ready { st -> st.folders.firstOrNull()?.cards?.firstOrNull()?.leaveBy != null }
        val homeWork = s.folders[0].cards[0]
        val lb = assertNotNull(homeWork.leaveBy)
        assertEquals(3, lb.walkMinutes)
        assertEquals(homeWork.headline!!.departure.expectedDepartureMs - 3 * 60_000, lb.leaveAtMs)
        assertEquals("Strathfield", lb.stopName)
        val expected = when {
            s.nowMs < lb.leaveAtMs - 120_000 -> LeaveByState.PLENTY
            s.nowMs < lb.leaveAtMs -> LeaveByState.NOW
            else -> LeaveByState.RUN
        }
        assertEquals(expected, lb.state)
        val other = s.folders[0].cards[1]
        assertNull(other.leaveBy)
        assertFalse(other.linkedToPlaces)
    }
}
