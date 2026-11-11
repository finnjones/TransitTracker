package app.transittracker.ui.feature.notify

import androidx.compose.runtime.Composable
import app.transittracker.ui.components.PlaceholderPage
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route

/**
 * The Notify tab: push watches (plan 5.9).
 *
 * PLACEHOLDER: owned by the `notify` feature. Replace the body; keep the signature (it is called
 * from `app/RouteContent.kt`). Read dependencies from `LocalAppDeps.current`, move with
 * `LocalNavigator.current`.
 */
@Composable
fun NotifyScreen() {
    val nav = LocalNavigator.current
    PlaceholderPage(Route.Notify.title, onBack = { nav.back() })
}
