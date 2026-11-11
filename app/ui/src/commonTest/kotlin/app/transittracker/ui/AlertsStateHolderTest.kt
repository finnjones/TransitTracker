package app.transittracker.ui

import app.transittracker.model.Alert
import app.transittracker.ui.alerts.tripLines
import app.transittracker.ui.app.alertBadgeCount
import app.transittracker.ui.app.alertsOnTrips
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.fake.LiveData
import app.transittracker.ui.feature.alerts.AlertsStateHolder
import app.transittracker.ui.feature.alerts.AlertsUiState
import app.transittracker.ui.gallery.SampleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Alerts state holder against FakeTransitCore at Wed 11 Nov 07:38. */
class AlertsStateHolderTest {
    private fun holder(deps: AppDeps, scope: CoroutineScope) = AlertsStateHolder(deps.core, deps.clock, scope)

    private suspend fun AlertsStateHolder.ready(): AlertsUiState = state.first { !it.loading && it.mine.size + it.clearTrips.size > 0 }

    @Test
    fun theFakeAlertsTouchTheTripsTheyNameAndTheRestIsAllClear() = runTest {
        val trips = FakeSetup.trips.filter { it.id != FakeSetup.TRIP_CENTRAL_STRATHFIELD }
        val h = holder(AppDeps.fake(trips = trips), backgroundScope)
        // The trips' lines are known before the data status arrives, so wait for that too.
        val s = h.state.first { !it.loading && it.all.size == 2 && it.mine.size == 2 && it.updatedMs != null }
        // The lift alert names Town Hall, the origin of a saved trip; trackwork names T9 and so
        // touches the trip whose services run on T9.
        val lift = s.all.first { it.id == "fake-alert-lift" }
        assertEquals(listOf(FakeSetup.TRIP_TOWNHALL_TALLAWONG), lift.trips.map { it.id })
        assertEquals(listOf("Town Hall"), lift.stationNames)
        val trackwork = s.all.first { it.id == "fake-alert-trackwork" }
        assertTrue(FakeSetup.TRIP_HOME_WORK in trackwork.trips.map { it.id })
        assertEquals("T9", trackwork.lines.single().name)
        // Chatswood to Tallawong has nothing on it.
        assertTrue(FakeSetup.TRIP_CHATSWOOD_TALLAWONG in s.clearTrips.map { it.id })
        assertFalse(s.offline)
        assertEquals(SampleData.NOW_MS - 12_000, s.updatedMs)
    }

    @Test
    fun aLineAlertReachesATripThatHasNoLineFilter() = runTest {
        val deps = AppDeps.fake()
        val trip = FakeSetup.trips.first { it.id == FakeSetup.TRIP_HOME_WORK }
        assertTrue(trip.legs.all { it.routeIds == null })
        // The lines that call at both ends of the trip, without asking for departures.
        val lines = tripLines(deps.core, trip)
        assertTrue(lines.isNotEmpty())
        val alert = Alert("line", "Delays", routeIds = listOf(lines.first()))
        val now = SampleData.NOW_MS
        assertEquals(emptyList(), alertsOnTrips(listOf(alert), listOf(trip), now).map { it.id })
        assertEquals(listOf("line"), alertsOnTrips(listOf(alert), listOf(trip), now, mapOf(trip.id to lines)).map { it.id })
        // The badge uses those lines.
        assertEquals(1, alertBadgeCount(deps.core, deps.clock).first { it > 0 })
    }

    @Test
    fun withOneUntouchedTripMyTripsIsEmptyAndTheTripIsClear() = runTest {
        val only = FakeSetup.trips.filter { it.id == FakeSetup.TRIP_CHATSWOOD_TALLAWONG }
        val s = holder(AppDeps.fake(trips = only), backgroundScope).ready()
        assertTrue(s.mine.isEmpty())
        assertEquals(listOf(FakeSetup.TRIP_CHATSWOOD_TALLAWONG), s.clearTrips.map { it.id })
        assertEquals(2, s.all.size)
    }

    @Test
    fun noTripsMeansEveryAlertIsOnlyOnTheNetwork() = runTest {
        val s = holder(AppDeps.fake(trips = emptyList()), backgroundScope).state.first { !it.loading && it.all.size == 2 }
        assertTrue(s.mine.isEmpty())
        assertTrue(s.clearTrips.isEmpty())
        assertEquals(0, s.tripCount)
    }

    @Test
    fun offlineIsFlaggedAndTheLastAlertsStillShow() = runTest {
        val deps = AppDeps.fake(scenario = CoreScenario(live = LiveData.OFFLINE))
        val s = holder(deps, backgroundScope).state.first { !it.loading && it.offline && it.all.isNotEmpty() }
        assertTrue(s.offline)
    }
}
