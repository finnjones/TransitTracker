package app.transittracker.ui.feature.settings

import androidx.compose.runtime.Composable
import app.transittracker.ui.components.PlaceholderPage
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route

/**
 * The Settings tab (plan 5.11).
 *
 * PLACEHOLDER: owned by the `settings` feature. Replace the body; keep the signature (it is called
 * from `app/RouteContent.kt`). Read dependencies from `LocalAppDeps.current`, move with
 * `LocalNavigator.current`.
 */
@Composable
fun SettingsScreen() {
    val nav = LocalNavigator.current
    PlaceholderPage(Route.Settings.title, onBack = { nav.back() })
}
