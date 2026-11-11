package app.transittracker.ui.feature.places

import androidx.compose.runtime.Composable
import app.transittracker.ui.components.PlaceholderPage
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route

/**
 * The saved places list (plan 5.7).
 *
 * PLACEHOLDER: owned by the `places` feature. Replace the body; keep the signature (it is called
 * from `app/RouteContent.kt`). Read dependencies from `LocalAppDeps.current`, move with
 * `LocalNavigator.current`.
 */
@Composable
fun PlacesScreen() {
    val nav = LocalNavigator.current
    PlaceholderPage(Route.SavedPlaces.title, onBack = { nav.back() })
}
