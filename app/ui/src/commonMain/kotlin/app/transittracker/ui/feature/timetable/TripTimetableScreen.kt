package app.transittracker.ui.feature.timetable

import androidx.compose.runtime.Composable
import app.transittracker.ui.components.PlaceholderPage
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route

/**
 * All services of one saved trip on a chosen day (plan 5.3).
 *
 * PLACEHOLDER: owned by the `timetable` feature. Replace the body; keep the signature (it is called
 * from `app/RouteContent.kt`). Read dependencies from `LocalAppDeps.current`, move with
 * `LocalNavigator.current`.
 */
@Composable
fun TripTimetableScreen(route: Route.TripTimetable) {
    val nav = LocalNavigator.current
    PlaceholderPage(route.title, onBack = { nav.back() })
}
