package app.transittracker.ui.feature.service

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.transittracker.core.FakeData
import app.transittracker.model.ServiceRef
import app.transittracker.model.WallClock
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.app.AppPage
import app.transittracker.ui.app.PagePreview
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.LiveData
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.gallery.Shot
import app.transittracker.ui.nav.Route
import app.transittracker.ui.settings.ThemePreference
import app.transittracker.ui.settings.UiSettings
import app.transittracker.ui.theme.DayColors

/**
 * Screenshots of the service screen (Service mockup), on `AppDeps.fake()`. The fake's live data is
 * a fixed mix keyed on the trip id, so each shot names the service that shows its case; some move
 * the clock to find the service in the right place.
 */
object ServiceShots {
    private const val DATE = "20261111"

    private fun clockAt(minutesFromSample: Int) = WallClock { SampleData.NOW_MS + minutesFromSample * 60_000L }

    /** Strathfield, Central and Chatswood platforms for a service (platform 1..6). */
    private fun p(station: String, n: Int) = FakeData.platform(station, n)

    @Composable
    private fun Page(route: Route.Service, deps: () -> AppDeps) {
        val d = remember { deps() }
        PagePreview(d) { AppPage(route) }
    }

    /** T9 to Gordon 07:45 from Strathfield, 4 minutes late (the mockup's service), not yet left. */
    private val t9Late = Route.Service(
        ServiceRef("T9N-0745", DATE), p(FakeData.STRATHFIELD, 3), p(FakeData.CENTRAL, 3),
    )

    /** T1 Parramatta to Chatswood 09:15, 4 minutes late and under way at 09:38. */
    private val t1Travelling = Route.Service(
        ServiceRef("T1E-0915", DATE), p(FakeData.CENTRAL, 2), p(FakeData.CHATSWOOD, 2),
    )

    val shots: List<Shot> = listOf(
        Shot("service-live") { Page(t9Late) { AppDeps.fake() } },
        Shot("service-late") { Page(t1Travelling) { AppDeps.fake(clock = clockAt(120)) } },
        Shot("service-timetable") { Page(t9Late) { AppDeps.fake(scenario = CoreScenario(live = LiveData.OFFLINE)) } },
        Shot("service-stale") { Page(t1Travelling) { AppDeps.fake(clock = clockAt(120), scenario = CoreScenario(live = LiveData.STALE)) } },
        Shot("service-cancelled") {
            Page(Route.Service(ServiceRef("T9N-0730", DATE), p(FakeData.STRATHFIELD, 3), p(FakeData.CENTRAL, 3))) { AppDeps.fake() }
        },
        // T9 07:00 from Strathfield: both Strathfield and Central moved to platform 4, seen at 06:50.
        Shot("service-platform-change") {
            Page(Route.Service(ServiceRef("T9N-0700", DATE), p(FakeData.STRATHFIELD, 4), p(FakeData.CENTRAL, 4))) { AppDeps.fake(clock = clockAt(-48)) }
        },
        Shot("service-extra") {
            Page(Route.Service(ServiceRef("ADDED-T1-0812", DATE), p(FakeData.CENTRAL, 4), p(FakeData.PARRAMATTA, 1))) { AppDeps.fake() }
        },
        Shot("service-no-stops") { Page(Route.Service(ServiceRef("T1E-0915", DATE))) { AppDeps.fake(clock = clockAt(120)) } },
        Shot("service-day", DayColors) {
            Page(t1Travelling) { AppDeps.fake(clock = clockAt(120), settings = UiSettings(theme = ThemePreference.DAY)) }
        },
    )
}
