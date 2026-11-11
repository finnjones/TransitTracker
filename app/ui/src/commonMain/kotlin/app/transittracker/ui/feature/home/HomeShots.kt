package app.transittracker.ui.feature.home

import androidx.compose.runtime.remember
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.LiveData
import app.transittracker.ui.gallery.AppShot
import app.transittracker.ui.gallery.Shot
import app.transittracker.ui.settings.CardDensity
import app.transittracker.ui.settings.ThemePreference
import app.transittracker.ui.settings.UiSettings
import app.transittracker.ui.theme.DayColors

/** Screenshots of the home screen (Main mockup), on `AppDeps.fake()` at Wed 11 Nov 07:38. */
object HomeShots {
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
    )
}
