package app.transittracker.ui.feature.places

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.transittracker.model.LatLng
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.app.PagePreview
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.gallery.AppShot
import app.transittracker.ui.gallery.Shot
import app.transittracker.ui.motion.PageTransition
import app.transittracker.ui.nav.Navigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.places.PlaceIcon
import app.transittracker.ui.places.PlaceStop
import app.transittracker.ui.planning.FakeJourneyPlanner
import app.transittracker.ui.settings.ThemePreference
import app.transittracker.ui.settings.UiSettings
import app.transittracker.ui.theme.DayColors

/**
 * Screenshots of the `places` feature: the saved places list (Places mockup), the place editor
 * (Add place mockup), Manage routes and the route editor (no mockups; built in the same language).
 * Whole-app shots run the real screens on `AppDeps.fake()`; the others draw a screen from a
 * hand-made state, for situations the fakes do not reach (search results, a walk time being
 * corrected, dialogs).
 */
object PlacesShots {
    private fun open(route: Route) = Navigator().apply { push(route, PageTransition.Cut) }

    @Composable
    private fun Page(route: Route, deps: AppDeps = remember { AppDeps.fake() }) {
        AppShot(deps, remember { open(route) })
    }

    private val day get() = UiSettings(theme = ThemePreference.DAY)

    // ---- Add place: the Add place mockup's Home, with its three stops.

    private val strathfield = StopRow(
        PlaceStop("213510", "Strathfield", walkMinutes = 3, distanceMeters = 250, estimated = true),
        listOf(LineBadge("T1", "F99D1C", "000000"), LineBadge("T2", "0098CD", "000000"), LineBadge("T3", "F37021", "000000"), LineBadge("T9", "D11F2F", "FFFFFF")),
    )
    private val homebush = StopRow(
        PlaceStop("214010", "Homebush", walkMinutes = 14, distanceMeters = 1100, estimated = true),
        listOf(LineBadge("T2", "0098CD", "000000")),
    )
    private val burwood = StopRow(
        PlaceStop("213410", "Burwood", walkMinutes = 19, distanceMeters = 1500, enabled = false, estimated = true),
        listOf(LineBadge("T2", "0098CD", "000000"), LineBadge("T9", "D11F2F", "FFFFFF")),
    )
    private val albertRd = LatLng(-33.8745, 151.0890)

    private val withStops = PlaceEditState(
        loading = false, isNew = true, name = "Home", icon = PlaceIcon.HOME, query = "Albert Rd, Strathfield",
        position = albertRd, stops = listOf(strathfield, homebush, burwood),
    )

    @Composable
    private fun Editor(title: String, state: PlaceEditState, deps: AppDeps = remember { AppDeps.fake() }) {
        PagePreview(deps) { PlaceEditContent(title, state, PlaceEditActions()) }
    }

    private val typed = PlaceEditState(loading = false, name = "Home", icon = PlaceIcon.HOME, query = "alb")

    private val answers = rankPlaces(
        (FakeJourneyPlanner.PLACES.filter { it.name.contains("Alb", ignoreCase = true) } + FakeJourneyPlanner.PLACES.take(4)).distinct(),
    )

    // ---- Manage routes and Edit route: hand-made states for the dialogs and failed saves.

    private val editState = EditRouteUiState(
        loading = false, name = "", origin = "Strathfield", destination = "Central",
        folder = "To work", folders = listOf("To work", "Home"),
        places = listOf(PlaceChoice(FakeSetup.HOME, "Home", PlaceIcon.HOME), PlaceChoice(FakeSetup.WORK, "Work", PlaceIcon.WORK), PlaceChoice(FakeSetup.GYM, "Gym", PlaceIcon.PIN)),
        fromPlaceId = FakeSetup.HOME, toPlaceId = FakeSetup.WORK,
        legs = listOf(
            LegRow(1, "Strathfield", "Chatswood", listOf(LineBadge("T1", "F99D1C", "000000"), LineBadge("T9", "D11F2F", "FFFFFF")), null),
            LegRow(2, "Chatswood", "Tallawong", listOf(LineBadge("M1", "168388", "FFFFFF")), 4),
        ),
    )

    val shots: List<Shot> = listOf(
        // The list.
        Shot("places-list") { Page(Route.SavedPlaces) },
        Shot("places-list-day", DayColors) { Page(Route.SavedPlaces, remember { AppDeps.fake(settings = day) }) },
        Shot("places-empty") { Page(Route.SavedPlaces, remember { AppDeps.fake(withPlaces = false) }) },
        Shot("places-reorder") {
            val cards = FakeSetup.places.mapIndexed { i, p ->
                PlaceCard(p.id, p.name, p.icon, p.address, emptyList(), usedBy = if (i < 2) 1 else 0)
            }
            PagePreview(remember { AppDeps.fake() }) {
                PlacesContent(PlacesUiState(loading = false, cards = cards, reordering = true), PlacesActions())
            }
        },
        // Add a place.
        Shot("places-add-empty") { Page(Route.PlaceEdit()) },
        Shot("places-add-results") {
            Editor("Add a place", typed.copy(search = AddressSearch.Results(answers)))
        },
        Shot("places-add-offline") {
            Editor("Add a place", typed.copy(search = AddressSearch.Offline))
        },
        Shot("places-add-stops") { Editor("Add a place", withStops) },
        Shot("places-add-stops-day", DayColors) { Editor("Add a place", withStops, remember { AppDeps.fake(settings = day) }) },
        Shot("places-add-walk") {
            Editor(
                "Add a place",
                withStops.copy(
                    editingWalk = "214010",
                    stops = listOf(strathfield, homebush.copy(stop = homebush.stop.copy(walkMinutes = 12, estimated = false)), burwood),
                ),
            )
        },
        Shot("places-add-refused") {
            Editor(
                "Add a place",
                PlaceEditState(
                    loading = false, name = "Gym", icon = PlaceIcon.PIN,
                    locationProblem = "Location is off for TransitTracker. Turn it on in your phone's settings, or type an address.",
                ),
            )
        },
        // Edit a place.
        Shot("places-edit") { Page(Route.PlaceEdit(FakeSetup.HOME)) },
        Shot("places-edit-delete") {
            Editor("Edit place", withStops.copy(isNew = false, confirmDelete = true, routesUsing = 2))
        },
        // Manage routes.
        Shot("routes-manage") { Page(Route.ManageRoutes) },
        Shot("routes-manage-day", DayColors) { Page(Route.ManageRoutes, remember { AppDeps.fake(settings = day) }) },
        Shot("routes-manage-empty") { Page(Route.ManageRoutes, remember { AppDeps.fake(trips = emptyList()) }) },
        Shot("routes-manage-rename") {
            PagePreview(remember { AppDeps.fake() }) {
                ManageRoutesContent(
                    ManageRoutesUiState(
                        loading = false,
                        folders = listOf(
                            FolderGroup(
                                "To work",
                                listOf(
                                    RouteRow("a", listOf("Home", "Work"), "STRATHFIELD TO CENTRAL", listOf(LineBadge("T1", "F99D1C", "000000"), LineBadge("T9", "D11F2F", "FFFFFF")), 0, false, true, "Move up", "Move down"),
                                ),
                            ),
                        ),
                        renaming = FolderRename("To work", "To work"),
                    ),
                    ManageRoutesActions(),
                )
            }
        },
        // Edit a route.
        Shot("routes-edit") { Page(Route.EditRoute(FakeSetup.TRIP_HOME_WORK)) },
        Shot("routes-edit-day", DayColors) {
            Page(Route.EditRoute(FakeSetup.TRIP_TOWNHALL_TALLAWONG), remember { AppDeps.fake(settings = day) })
        },
        Shot("routes-edit-legs") {
            PagePreview(remember { AppDeps.fake() }) { EditRouteContent(editState, EditRouteActions()) }
        },
        Shot("routes-edit-delete") {
            PagePreview(remember { AppDeps.fake() }) { EditRouteContent(editState.copy(confirmDelete = true), EditRouteActions()) }
        },
        Shot("routes-edit-failed") {
            PagePreview(remember { AppDeps.fake() }) {
                EditRouteContent(
                    editState.copy(problem = Problem("Could not save the route", "The route was not saved. Try again in a moment.")),
                    EditRouteActions(),
                )
            }
        },
    )
}
