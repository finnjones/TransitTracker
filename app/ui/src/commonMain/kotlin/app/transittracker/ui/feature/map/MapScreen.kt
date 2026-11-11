package app.transittracker.ui.feature.map

import androidx.compose.runtime.Composable
import app.transittracker.ui.components.PlaceholderPage
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route

/**
 * The Map tab: lines, live vehicles, nearby stations (plan 5.5).
 *
 * PLACEHOLDER: owned by the `map` feature. Replace the body; keep the signature (it is called
 * from `app/RouteContent.kt`). Read dependencies from `LocalAppDeps.current`, move with
 * `LocalNavigator.current`.
 */
@Composable
fun MapScreen() {
    val nav = LocalNavigator.current
    PlaceholderPage(Route.MapTab.title, onBack = { nav.back() })
}
