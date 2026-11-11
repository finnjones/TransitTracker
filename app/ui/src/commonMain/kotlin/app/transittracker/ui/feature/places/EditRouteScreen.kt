package app.transittracker.ui.feature.places

import androidx.compose.runtime.Composable
import app.transittracker.ui.components.PlaceholderPage
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route

/**
 * Edit one saved route: name, folder, line filter, legs (plan 4.2).
 *
 * PLACEHOLDER: owned by the `places` feature. Replace the body; keep the signature (it is called
 * from `app/RouteContent.kt`). Read dependencies from `LocalAppDeps.current`, move with
 * `LocalNavigator.current`.
 */
@Composable
fun EditRouteScreen(route: Route.EditRoute) {
    val nav = LocalNavigator.current
    PlaceholderPage(route.title, onBack = { nav.back() })
}
