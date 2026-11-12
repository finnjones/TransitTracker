package app.transittracker.ui.feature.timetable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.transittracker.core.FakeData
import app.transittracker.model.SavedTrip
import app.transittracker.model.TripLeg
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.app.AppPage
import app.transittracker.ui.app.PagePreview
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.fake.LiveData
import app.transittracker.ui.gallery.Shot
import app.transittracker.ui.nav.Route
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.settings.ThemePreference
import app.transittracker.ui.settings.UiSettings
import app.transittracker.ui.theme.DayColors

/**
 * Screenshots of the trip timetable (plan 5.3), on `AppDeps.fake()` at Wed 11 Nov 07:38. The
 * trips are the Main mockup's; "nowhere" is a trip no line runs, for the empty day.
 */
object TimetableShots {
    private val nowhere = SavedTrip(
        "trip-nowhere", "Parramatta", "Tallawong",
        listOf(TripLeg(setOf(FakeData.PARRAMATTA), setOf(FakeData.TALLAWONG))), order = 0,
    )

    @Composable
    private fun Page(route: Route.TripTimetable, deps: () -> AppDeps) {
        val d = remember { deps() }
        PagePreview(d) { AppPage(route) }
    }

    private val homeWork = Route.TripTimetable(FakeSetup.TRIP_HOME_WORK)

    /** Today with the folded services opened (the screen keeps that choice in saved state). */
    @Composable
    private fun EarlierOpen() {
        val deps = remember { AppDeps.fake() }
        PagePreview(deps) {
            val holder = rememberStateHolder { scope ->
                TimetableStateHolder(deps.core, deps.places, deps.clock, FakeSetup.TRIP_HOME_WORK, null, scope)
            }
            val state by holder.state.collectAsState()
            TimetableContent(state, showEarlier = true, actions = TimetableActions())
        }
    }

    /** The day's services still being read: the board works where the rows will appear. */
    @Composable
    private fun StillLoading() {
        val deps = remember { AppDeps.fake() }
        PagePreview(deps) {
            val holder = rememberStateHolder { scope ->
                TimetableStateHolder(deps.core, deps.places, deps.clock, FakeSetup.TRIP_HOME_WORK, null, scope)
            }
            val state by holder.state.collectAsState()
            val waiting = state.copy(rowsLoaded = false, earlier = emptyList(), upcoming = emptyList(), timetableOnly = false)
            TimetableContent(waiting, showEarlier = false, actions = TimetableActions(), loaderDelayMs = 0)
        }
    }

    val shots: List<Shot> = listOf(
        Shot("timetable-today") { Page(homeWork) { AppDeps.fake() } },
        Shot("timetable-earlier") { EarlierOpen() },
        Shot("timetable-loading") { StillLoading() },
        Shot("timetable-tomorrow") { Page(Route.TripTimetable(FakeSetup.TRIP_HOME_WORK, "20261112")) { AppDeps.fake() } },
        Shot("timetable-empty") { Page(Route.TripTimetable("trip-nowhere")) { AppDeps.fake(trips = listOf(nowhere)) } },
        // Two days on, trackwork on the T9 touches the trip: the alert banner.
        Shot("timetable-alert") { Page(Route.TripTimetable(FakeSetup.TRIP_HOME_WORK, "20261113")) { AppDeps.fake() } },
        Shot("timetable-offline") { Page(homeWork) { AppDeps.fake(scenario = CoreScenario(live = LiveData.OFFLINE)) } },
        Shot("timetable-changes") { Page(Route.TripTimetable(FakeSetup.TRIP_TOWNHALL_TALLAWONG)) { AppDeps.fake() } },
        Shot("timetable-day", DayColors) {
            Page(homeWork) { AppDeps.fake(settings = UiSettings(theme = ThemePreference.DAY)) }
        },
    )
}
