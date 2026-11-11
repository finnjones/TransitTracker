package app.transittracker.ui.feature.plan

import androidx.compose.runtime.Composable
import app.transittracker.ui.components.PlaceholderPage
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route

/**
 * Journey options for one search (plan 5.6 step 2).
 *
 * PLACEHOLDER: owned by the `plan` feature. Replace the body; keep the signature (it is called
 * from `app/RouteContent.kt`). Read dependencies from `LocalAppDeps.current`, move with
 * `LocalNavigator.current`.
 */
@Composable
fun JourneyOptionsScreen(route: Route.JourneyOptions) {
    val nav = LocalNavigator.current
    PlaceholderPage(route.title, onBack = { nav.back() })
}
