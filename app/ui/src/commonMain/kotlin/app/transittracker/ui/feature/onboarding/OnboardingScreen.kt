package app.transittracker.ui.feature.onboarding

import androidx.compose.runtime.Composable
import app.transittracker.ui.components.PlaceholderPage
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route

/**
 * Onboarding: invite, timetable download, notifications, first journey (plan 5.1).
 *
 * PLACEHOLDER: owned by the `onboarding` feature. Replace the body; keep the signature (it is called
 * from `app/RouteContent.kt`). Read dependencies from `LocalAppDeps.current`, move with
 * `LocalNavigator.current`.
 */
@Composable
fun OnboardingScreen() {
    val nav = LocalNavigator.current
    PlaceholderPage(Route.Onboarding.title, onBack = { nav.back() })
}
