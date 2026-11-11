package app.transittracker.ui.feature.places

import androidx.compose.runtime.Composable
import app.transittracker.ui.components.PlaceholderPage
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route

/**
 * Add or edit a saved place (plan 5.7).
 *
 * PLACEHOLDER: owned by the `places` feature. Replace the body; keep the signature (it is called
 * from `app/RouteContent.kt`). Read dependencies from `LocalAppDeps.current`, move with
 * `LocalNavigator.current`.
 */
@Composable
fun PlaceEditScreen(route: Route.PlaceEdit) {
    val nav = LocalNavigator.current
    PlaceholderPage(route.title, onBack = { nav.back() })
}
