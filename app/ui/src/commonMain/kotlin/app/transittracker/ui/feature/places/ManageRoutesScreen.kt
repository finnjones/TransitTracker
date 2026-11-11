package app.transittracker.ui.feature.places

import androidx.compose.runtime.Composable
import app.transittracker.ui.components.PlaceholderPage
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route

/**
 * Reorder, rename, folder and delete saved routes (plan 5.6 "Saved routes").
 *
 * PLACEHOLDER: owned by the `places` feature. Replace the body; keep the signature (it is called
 * from `app/RouteContent.kt`). Read dependencies from `LocalAppDeps.current`, move with
 * `LocalNavigator.current`.
 */
@Composable
fun ManageRoutesScreen() {
    val nav = LocalNavigator.current
    PlaceholderPage(Route.ManageRoutes.title, onBack = { nav.back() })
}
