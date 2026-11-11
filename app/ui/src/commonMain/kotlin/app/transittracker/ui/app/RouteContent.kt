package app.transittracker.ui.app

import androidx.compose.runtime.Composable
import app.transittracker.ui.feature.alerts.AlertsScreen
import app.transittracker.ui.feature.home.HomeScreen
import app.transittracker.ui.feature.map.MapScreen
import app.transittracker.ui.feature.map.TrackingScreen
import app.transittracker.ui.feature.notify.NotifyScreen
import app.transittracker.ui.feature.notify.WatchEditorScreen
import app.transittracker.ui.feature.onboarding.OnboardingScreen
import app.transittracker.ui.feature.places.EditRouteScreen
import app.transittracker.ui.feature.places.ManageRoutesScreen
import app.transittracker.ui.feature.places.PlaceEditScreen
import app.transittracker.ui.feature.places.PlacesScreen
import app.transittracker.ui.feature.plan.JourneyOptionsScreen
import app.transittracker.ui.feature.plan.JourneyScreen
import app.transittracker.ui.feature.plan.PlanJourneyScreen
import app.transittracker.ui.feature.service.ServiceScreen
import app.transittracker.ui.feature.settings.SettingsScreen
import app.transittracker.ui.feature.timetable.TripTimetableScreen
import app.transittracker.ui.gallery.DebugGalleryScreen
import app.transittracker.ui.nav.Route

/**
 * The one route-to-screen map. Every screen has a fixed signature here; feature owners change
 * their screen's body, never this file. Tab roots get the bottom bar from the shell; their
 * screens draw only the area above it.
 */
@Composable
fun RouteContent(route: Route) {
    when (route) {
        Route.Trips -> HomeScreen()
        Route.MapTab -> MapScreen()
        Route.Alerts -> AlertsScreen()
        Route.Notify -> NotifyScreen()
        Route.Settings -> SettingsScreen()
        is Route.TripTimetable -> TripTimetableScreen(route)
        is Route.Service -> ServiceScreen(route)
        is Route.Tracking -> TrackingScreen(route)
        is Route.PlanJourney -> PlanJourneyScreen(route)
        is Route.JourneyOptions -> JourneyOptionsScreen(route)
        is Route.JourneyDetail -> JourneyScreen(route)
        Route.SavedPlaces -> PlacesScreen()
        is Route.PlaceEdit -> PlaceEditScreen(route)
        Route.ManageRoutes -> ManageRoutesScreen()
        is Route.EditRoute -> EditRouteScreen(route)
        is Route.WatchEditor -> WatchEditorScreen(route)
        Route.Onboarding -> OnboardingScreen()
        Route.DebugGallery -> DebugGalleryScreen()
    }
}
