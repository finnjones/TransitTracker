package app.transittracker.ui.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.transittracker.model.LatLng
import app.transittracker.model.Ticket
import app.transittracker.model.TicketEnd
import app.transittracker.model.TicketStatus
import app.transittracker.model.ticketIdFor
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.fake.LiveData
import app.transittracker.ui.feature.follow.FollowMatch
import app.transittracker.ui.feature.follow.FollowStage
import app.transittracker.ui.gallery.AppShot
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.gallery.Shot
import app.transittracker.ui.settings.CardDensity
import app.transittracker.ui.settings.ThemePreference
import app.transittracker.ui.settings.UiSettings
import app.transittracker.ui.theme.DayColors
import kotlinx.coroutines.flow.first

/** Screenshots of the home screen (Main mockup), on `AppDeps.fake()` at Wed 11 Nov 07:38. */
object HomeShots {
    /**
     * A ticket (GO) for the Town Hall to Tallawong journey (the T1, then the M1 at Chatswood), on
     * the sample data without that saved route: [status] waiting (the next run picked), on the way
     * (Follow my journey on the T1 under way at 07:38) or arrived three minutes ago.
     */
    private suspend fun ticketDeps(status: TicketStatus, settings: UiSettings): AppDeps {
        val pattern = FakeSetup.trips.first { it.id == FakeSetup.TRIP_TOWNHALL_TALLAWONG }
        val deps = AppDeps.fake(trips = FakeSetup.trips.filter { it.id != pattern.id }, settings = settings)
        val id = ticketIdFor(pattern.legs)
        val base = Ticket(
            trip = pattern.copy(id = id, folder = null, order = 0),
            from = TicketEnd("Town Hall", LatLng(-33.8732, 151.2069)),
            to = TicketEnd("Tallawong", LatLng(-33.6914, 150.9066)),
            plannedDepartureMs = SampleData.NOW_MS,
            plannedArrivalMs = SampleData.NOW_MS,
            issuedAtMs = SampleData.NOW_MS - 4 * 60_000,
        )
        deps.core.saveTicket(base)
        val journey = when (status) {
            TicketStatus.WAITING -> deps.core.upcoming(id).first().firstOrNull()
            else -> deps.core.underway(id).first().let { u -> u.firstOrNull { SampleData.NOW_MS < it.legs[0].expectedArrivalMs } ?: u.firstOrNull() }
        } ?: return deps
        deps.core.saveTicket(
            base.copy(
                plannedRides = journey.legs.map { it.ref },
                plannedDepartureMs = journey.departure.expectedDepartureMs,
                plannedArrivalMs = journey.arrival.expectedArrivalMs,
                status = status,
                arrivedAtMs = if (status == TicketStatus.ARRIVED) SampleData.NOW_MS - 3 * 60_000 else null,
            ),
        )
        if (status == TicketStatus.ON_THE_WAY) {
            deps.follower.showForPreview(FollowMatch(id, journey.legs[0].ref, 0, FollowStage.RIDING, 0.0), null)
        }
        return deps
    }

    @Composable
    private fun TicketApp(status: TicketStatus, settings: UiSettings = UiSettings()) {
        var deps by remember { mutableStateOf<AppDeps?>(null) }
        LaunchedEffect(Unit) { deps = ticketDeps(status, settings) }
        AppShot(deps ?: return)
    }

    val shots: List<Shot> = listOf(
        Shot("home-night") { AppShot(remember { AppDeps.fake() }) },
        Shot("home-day", DayColors) { AppShot(remember { AppDeps.fake(settings = UiSettings(theme = ThemePreference.DAY)) }) },
        Shot("home-compact") { AppShot(remember { AppDeps.fake(settings = UiSettings(cardDensity = CardDensity.COMPACT)) }) },
        Shot("home-empty") { AppShot(remember { AppDeps.fake(trips = emptyList()) }) },
        Shot("home-offline") { AppShot(remember { AppDeps.fake(scenario = CoreScenario(live = LiveData.OFFLINE)) }) },
        Shot("home-stale") { AppShot(remember { AppDeps.fake(scenario = CoreScenario(live = LiveData.STALE)) }) },
        Shot("home-banners") {
            AppShot(
                remember {
                    AppDeps.fake(
                        scenario = CoreScenario(statusMessage = "Sample data", packDownloading = true, packOutOfDate = true),
                    )
                },
            )
        },
        Shot("home-ticket") { TicketApp(TicketStatus.WAITING) },
        Shot("home-ticket-day", DayColors) { TicketApp(TicketStatus.WAITING, UiSettings(theme = ThemePreference.DAY)) },
        Shot("home-ticket-on-the-way") { TicketApp(TicketStatus.ON_THE_WAY) },
        Shot("home-ticket-arrived") { TicketApp(TicketStatus.ARRIVED) },
    )
}
