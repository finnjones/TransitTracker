package app.transittracker.ui.feature.alerts

import androidx.compose.runtime.Composable
import app.transittracker.ui.components.PlaceholderPage
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route

/**
 * The Alerts tab: disruptions, your trips first (plan 5.8).
 *
 * PLACEHOLDER: owned by the `alerts` feature. Replace the body; keep the signature (it is called
 * from `app/RouteContent.kt`). Read dependencies from `LocalAppDeps.current`, move with
 * `LocalNavigator.current`.
 */
@Composable
fun AlertsScreen() {
    val nav = LocalNavigator.current
    PlaceholderPage(Route.Alerts.title, onBack = { nav.back() })
}
