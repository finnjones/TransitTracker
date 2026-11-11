package app.transittracker.ui.feature.notify

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.transittracker.core.FakeData
import app.transittracker.model.ServiceRef
import app.transittracker.model.TransitCore
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchKind
import app.transittracker.model.WatchRules
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
import app.transittracker.ui.nav.Route
import app.transittracker.ui.nav.Tab
import app.transittracker.ui.platform.FakePlatformServices
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.settings.ThemePreference
import app.transittracker.ui.settings.UiSettings
import app.transittracker.ui.theme.DayColors
import app.transittracker.ui.theme.TtTheme
import kotlinx.coroutines.flow.first

/**
 * Screenshots of the Notify tab and the watch editor (Notify mockup; the list has none, it is
 * built from plan 5.9). The fake core starts with one watch; the shots replace it with their own.
 */
object NotifyShots {
    private const val MORNING = "shot-watch-morning"
    private const val EVENING = "shot-watch-evening"
    private val now get() = SampleData.NOW_MS

    /** Strathfield to Central on weekday mornings; Central to Strathfield, paused, in the evening. */
    private suspend fun seed(core: TransitCore, commutes: Boolean, oneOff: Boolean): ServiceRef? {
        core.deleteWatch("fake-watch-commute")
        if (commutes) {
            core.saveWatch(
                WatchDto(
                    MORNING, WatchKind.COMMUTE, "Morning", FakeData.STRATHFIELD, FakeData.CENTRAL,
                    windowStartSecs = 7 * 3600 + 15 * 60, windowEndSecs = 8 * 3600,
                ),
            )
            core.saveWatch(
                WatchDto(
                    EVENING, WatchKind.COMMUTE, "Evening", FakeData.CENTRAL, FakeData.STRATHFIELD,
                    windowStartSecs = 17 * 3600, windowEndSecs = 18 * 3600 + 15 * 60,
                    rules = WatchRules(delayThresholdMin = 10), pausedUntilMs = now + PAUSE_FOREVER_MS,
                ),
            )
        }
        val ref = core.upcoming(FakeSetup.TRIP_HOME_WORK, 3).first().firstOrNull()?.departure?.ref
        if (oneOff && ref != null) core.watchService(ref, WatchRules())
        return ref
    }

    /** Holds [content] back until the fake core holds the shot's watches. */
    @Composable
    private fun Seeded(deps: AppDeps, commutes: Boolean = true, oneOff: Boolean = true, content: @Composable (ServiceRef?) -> Unit) {
        var ready by remember { mutableStateOf(false) }
        var ref by remember { mutableStateOf<ServiceRef?>(null) }
        LaunchedEffect(Unit) {
            ref = seed(deps.core, commutes, oneOff)
            ready = true
        }
        if (ready) content(ref)
    }

    @Composable
    private fun List(deps: AppDeps, commutes: Boolean = true, oneOff: Boolean = true) {
        Seeded(deps, commutes, oneOff) {
            AppShot(deps, remember { Navigator().apply { switchTab(Tab.NOTIFY, PageTransition.Cut) } })
        }
    }

    @Composable
    private fun Editor(deps: AppDeps, route: (ServiceRef?) -> Route.WatchEditor) {
        Seeded(deps) { ref ->
            AppShot(deps, remember { Navigator().apply { push(route(ref), PageTransition.Cut) } })
        }
    }

    private fun deps(
        platform: FakePlatformServices = FakePlatformServices(),
        scenario: CoreScenario = CoreScenario(),
        settings: UiSettings = UiSettings(),
    ) = AppDeps.fake(platform = platform, scenario = scenario, settings = settings)

    /** The editor from a hand-made state: the delete confirmation and a failed save. */
    @Composable
    private fun EditorState(state: WatchEditorState) {
        PagePreview(remember { AppDeps.fake() }) { WatchEditorContent(state, WatchEditorActions()) }
    }

    private val existing = WatchEditorState(
        loading = false, mode = EditorMode.EXISTING, slot = slotWords(1),
        ticket = WatchTicket("Strathfield", "Central", "ANY LINE"),
        startSecs = 7 * 3600 + 15 * 60, endSecs = 8 * 3600,
    )

    /** The list's "which trip" dialog, over a list of two watches. */
    @Composable
    private fun Picker() {
        val trips = FakeSetup.trips
        val state = NotifyUiState(loading = false, count = 2, trips = trips)
        PagePreview(remember { AppDeps.fake() }) {
            Column(Modifier.fillMaxSize().background(TtTheme.colors.screen)) {
                Box(Modifier.weight(1f).fillMaxWidth()) { NotifyContent(state, picking = true, actions = NotifyActions()) }
                BottomBar(Tab.NOTIFY, 2, onSelect = {})
            }
        }
    }

    val shots: List<Shot> = listOf(
        Shot("notify-list") { List(remember { deps() }) },
        Shot("notify-list-empty") { List(remember { deps() }, commutes = false, oneOff = false) },
        Shot("notify-list-permission") {
            List(remember { deps(platform = FakePlatformServices(notificationPermission = PermissionState.DENIED, batterySaver = true)) })
        },
        Shot("notify-list-offline") { List(remember { deps(scenario = CoreScenario(live = LiveData.OFFLINE)) }) },
        Shot("notify-list-picker") { Picker() },
        Shot("notify-list-day", DayColors) { List(remember { deps(settings = UiSettings(theme = ThemePreference.DAY)) }) },
        Shot("notify-editor-new") {
            Editor(remember { deps() }) { Route.WatchEditor(fromTripId = FakeSetup.TRIP_HOME_WORK) }
        },
        Shot("notify-editor-existing") {
            Editor(remember { deps() }) { Route.WatchEditor(watchId = MORNING) }
        },
        Shot("notify-editor-oneoff") {
            Editor(remember { deps() }) { ref -> Route.WatchEditor(fromService = ref) }
        },
        Shot("notify-editor-delete") { EditorState(existing.copy(confirmDelete = true)) },
        Shot("notify-editor-failed") {
            EditorState(
                existing.copy(
                    result = ResultLine("No connection, so the watch was not saved. Check your internet and try again.", ResultKind.ERROR),
                ),
            )
        },
        Shot("notify-editor-day", DayColors) {
            Editor(remember { deps(settings = UiSettings(theme = ThemePreference.DAY)) }) { Route.WatchEditor(watchId = MORNING) }
        },
    )
}
