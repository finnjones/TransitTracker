package app.transittracker.ui.feature.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.transittracker.core.FakeData
import app.transittracker.model.SavedTrip
import app.transittracker.model.TripLeg
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.app.BottomBar
import app.transittracker.ui.app.PagePreview
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.fake.LiveData
import app.transittracker.ui.gallery.AppShot
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.gallery.Shot
import app.transittracker.ui.motion.PageTransition
import app.transittracker.ui.nav.Navigator
import app.transittracker.ui.nav.Tab
import app.transittracker.ui.settings.ThemePreference
import app.transittracker.ui.settings.UiSettings
import app.transittracker.ui.theme.DayColors
import app.transittracker.ui.theme.TtTheme

/**
 * Screenshots of the Alerts tab (Alerts mockup), on `AppDeps.fake()` at Wed 11 Nov 07:38. The
 * mockup has saved trips Strathfield to Central and Chatswood to Tallawong (and a Town Hall trip the lift alert touches).
 */
object AlertsShots {
    private val mockupTrips = FakeSetup.trips.filter {
        it.id != FakeSetup.TRIP_CENTRAL_STRATHFIELD
    }

    private val chatswoodOnly = FakeSetup.trips.filter { it.id == FakeSetup.TRIP_CHATSWOOD_TALLAWONG }

    @Composable
    private fun AlertsApp(deps: AppDeps) {
        AppShot(deps, remember { Navigator().apply { switchTab(Tab.ALERTS, PageTransition.Cut) } })
    }

    /** The screen with one card open, above the bottom bar. */
    @Composable
    private fun Page(deps: AppDeps, initialExpanded: String?) {
        PagePreview(deps) {
            Column(Modifier.fillMaxSize().background(TtTheme.colors.screen)) {
                Box(Modifier.weight(1f).fillMaxWidth()) { AlertsScreen(initialExpanded) }
                BottomBar(Tab.ALERTS, 2, onSelect = {})
            }
        }
    }

    /** The mockup's first card, with a link so MORE INFO shows: built from data, no core. */
    @Composable
    private fun MoreInfoPage() {
        val trip = SavedTrip(
            "t1", "Strathfield", "Central",
            listOf(TripLeg(setOf(FakeData.STRATHFIELD), setOf(FakeData.CENTRAL), routeIds = setOf(FakeData.T9))),
        )
        val alert = FakeData.alerts(SampleData.NOW_MS).first().copy(url = "https://transportnsw.info/alerts")
        val state = buildAlertsState(
            AlertsInputs(
                alerts = listOf(alert), trips = listOf(trip),
                lines = mapOf(FakeData.T9 to LineInfo(FakeData.T9, "T9", "D11F2F", null)),
            ),
            SampleData.NOW_MS,
        ).copy(updatedMs = SampleData.NOW_MS - 12_000)
        PagePreview(AppDeps.fake()) {
            Column(Modifier.fillMaxSize().background(TtTheme.colors.screen)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AlertsContent(state, AlertFilter.MY_TRIPS, alert.id, {}, {}, {})
                }
                BottomBar(Tab.ALERTS, 1, onSelect = {})
            }
        }
    }

    val shots: List<Shot> = listOf(
        Shot("alerts-night") { Page(remember { AppDeps.fake(trips = mockupTrips) }, "fake-alert-trackwork") },
        Shot("alerts-more-info") { MoreInfoPage() },
        Shot("alerts-app") { AlertsApp(remember { AppDeps.fake() }) },
        Shot("alerts-clear") { Page(remember { AppDeps.fake(trips = chatswoodOnly) }, null) },
        Shot("alerts-offline") {
            Page(remember { AppDeps.fake(trips = mockupTrips, scenario = CoreScenario(live = LiveData.OFFLINE)) }, null)
        },
        Shot("alerts-day", DayColors) {
            Page(remember { AppDeps.fake(trips = mockupTrips, settings = UiSettings(theme = ThemePreference.DAY)) }, "fake-alert-trackwork")
        },
    )
}
