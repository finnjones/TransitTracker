package app.transittracker.ui.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.app.BottomBar
import app.transittracker.ui.app.PagePreview
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.LiveData
import app.transittracker.ui.gallery.Shot
import app.transittracker.ui.nav.Tab
import app.transittracker.ui.platform.FakePlatformServices
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.settings.ThemePreference
import app.transittracker.ui.settings.UiSettings
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.DayColors
import app.transittracker.ui.theme.TtTheme

/**
 * Screenshots of the Settings tab and the licences page. The page runs on its real state holder
 * against the fake core; [group] scrolls the page so that group is at the top.
 */
object SettingsShots {
    @Composable
    private fun Page(
        deps: AppDeps,
        group: SettingsGroup? = null,
        noWatch: Boolean = false,
        sendTest: Boolean = false,
    ) {
        val zone = TtTheme.time.timeZone
        val holder = rememberStateHolder { scope ->
            SettingsStateHolder(deps.core, deps.settings, deps.places, deps.platform, deps.clock, scope, zone)
        }
        val state by holder.state.collectAsState()
        val list = rememberLazyListState(group?.let { groupIndex(it) } ?: 0)
        LaunchedEffect(Unit) {
            if (noWatch) deps.core.deleteWatch("fake-watch-commute")
            if (sendTest) holder.sendTest()
        }
        PagePreview(deps) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { SettingsContent(state, SettingsActions(), listState = list) }
                BottomBar(Tab.SETTINGS, 2, onSelect = {})
            }
        }
    }

    @Composable
    private fun Licences() {
        // Reads the real licence files bundled with the app.
        LicencesPage("Contains public sector information licensed under the Creative Commons Attribution 4.0 licence.", {})
    }

    private fun deps(
        platform: FakePlatformServices = FakePlatformServices(),
        scenario: CoreScenario = CoreScenario(),
        settings: UiSettings = UiSettings(),
    ) = AppDeps.fake(platform = platform, scenario = scenario, settings = settings)

    private val day = UiSettings(theme = ThemePreference.DAY)

    val shots: List<Shot> = listOf(
        Shot("settings-top") { Page(remember { deps() }) },
        Shot("settings-data") { Page(remember { deps() }, SettingsGroup.DATA) },
        Shot("settings-data-updates") {
            Page(remember { deps(scenario = CoreScenario(packDownloading = true, live = LiveData.STALE)) }, SettingsGroup.DATA)
        },
        Shot("settings-data-offline") {
            Page(remember { deps(scenario = CoreScenario(packOutOfDate = true, live = LiveData.OFFLINE)) }, SettingsGroup.DATA)
        },
        Shot("settings-display") { Page(remember { deps() }, SettingsGroup.DISPLAY) },
        Shot("settings-notifications") { Page(remember { deps() }, SettingsGroup.NOTIFICATIONS) },
        Shot("settings-notifications-denied") {
            Page(
                remember { deps(platform = FakePlatformServices(notificationPermission = PermissionState.DENIED, batterySaver = true)) },
                SettingsGroup.NOTIFICATIONS,
            )
        },
        Shot("settings-notifications-test-sent") { Page(remember { deps() }, SettingsGroup.NOTIFICATIONS, sendTest = true) },
        Shot("settings-notifications-no-watch") {
            Page(remember { deps() }, SettingsGroup.NOTIFICATIONS, noWatch = true, sendTest = true)
        },
        Shot("settings-about") { Page(remember { deps() }, SettingsGroup.ABOUT) },
        Shot("settings-licences") { Licences() },
        Shot("settings-top-day", DayColors) { Page(remember { deps(settings = day) }) },
        Shot("settings-display-day", DayColors) { Page(remember { deps(settings = day) }, SettingsGroup.DISPLAY) },
        Shot("settings-licences-day", DayColors) { Licences() },
    )
}
