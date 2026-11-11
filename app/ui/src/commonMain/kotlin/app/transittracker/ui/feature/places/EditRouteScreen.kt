package app.transittracker.ui.feature.places

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.transittracker.ui.app.LocalAppDeps
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.DialogBox
import app.transittracker.ui.components.DialogScrim
import app.transittracker.ui.components.FieldInput
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.PixelTextField
import app.transittracker.ui.components.ToggleChip
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.TtTheme

/** What the route editor can ask for. */
class EditRouteActions(
    val back: () -> Unit = {},
    val name: (String) -> Unit = {},
    val folder: (String) -> Unit = {},
    val from: (String?) -> Unit = {},
    val to: (String?) -> Unit = {},
    val notify: () -> Unit = {},
    val save: () -> Unit = {},
    val askDelete: () -> Unit = {},
    val cancelDelete: () -> Unit = {},
    val confirmDelete: () -> Unit = {},
    val retry: () -> Unit = {},
    val closeProblem: () -> Unit = {},
)

/** Edit one saved route (plan 4.2 "Edit a saved route"). */
@Composable
fun EditRouteScreen(route: Route.EditRoute) {
    val deps = LocalAppDeps.current
    val nav = LocalNavigator.current
    val holder = rememberStateHolder(route.tripId) { scope -> EditRouteStateHolder(deps.core, deps.places, route.tripId, scope) }
    val state by holder.state.collectAsState()
    LaunchedEffect(state.finished) { if (state.finished) nav.back() }
    EditRouteContent(
        state,
        EditRouteActions(
            back = { nav.back() },
            name = holder::setName, folder = holder::setFolder, from = holder::setFrom, to = holder::setTo,
            notify = { nav.push(Route.WatchEditor(fromTripId = route.tripId)) },
            save = holder::save, askDelete = holder::askDelete, cancelDelete = holder::cancelDelete,
            confirmDelete = holder::confirmDelete, retry = holder::retry, closeProblem = holder::dismissProblem,
        ),
    )
}

/** The route editor for [state]; stateless. */
@Composable
fun EditRouteContent(state: EditRouteUiState, actions: EditRouteActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Box(modifier.fillMaxSize().background(c.screen)) {
        Column(Modifier.fillMaxSize()) {
            PlacesHeader("Edit route", if (state.loading || state.missing) null else state.defaultName.uppercase(), actions.back)
            when {
                state.loading -> Box(Modifier.weight(1f))
                state.missing -> Box(Modifier.weight(1f).padding(horizontal = 18.dp, vertical = 8.dp).flapRow(1)) {
                    PixelText(
                        "This route is no longer there. Go back to Manage routes to see the ones you have.",
                        style = TtTheme.type.reading15, color = c.textReading,
                    )
                }
                else -> {
                    Body(state, actions, Modifier.weight(1f))
                    Footer {
                        state.saveProblem?.let { NoteLine(it, Lamp.TRIANGLE, c.caution) }
                        PixelButton(
                            if (state.saving) "SAVING…" else "SAVE ROUTE", actions.save, Modifier.fillMaxWidth(),
                            kind = ButtonKind.PRIMARY, enabled = state.canSave, style = TtTheme.type.label13Bold,
                        )
                    }
                }
            }
        }
        if (state.confirmDelete) {
            DialogScrim(actions.cancelDelete) {
                DialogBox(
                    "Delete ${state.title}?",
                    body = "The route will be removed from your trips. Your saved places are not affected.",
                    actions = {
                        PixelButton("KEEP IT", actions.cancelDelete, Modifier.weight(1f))
                        PixelButton("DELETE", actions.confirmDelete, Modifier.weight(1f), kind = ButtonKind.PRIMARY)
                    },
                )
            }
        }
        state.problem?.let { ProblemDialog(it, actions.closeProblem, actions.retry) }
    }
}

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) {
    Label(text, modifier.semantics { heading() }, color = TtTheme.colors.textSecondary, size = LabelSize.L10)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Body(state: EditRouteUiState, actions: EditRouteActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val linked = state.fromPlaceId != null || state.toPlaceId != null
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(Modifier.flapRow(1), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Heading("NAME")
            PixelTextField(state.name, actions.name, "Route name", Modifier.fillMaxWidth(), placeholder = state.defaultName, input = FieldInput.NAME)
            if (linked) {
                PixelText(
                    "Routes between saved places are titled with the place names on the Trips tab.",
                    style = TtTheme.type.reading15, color = c.textSecondary,
                )
            }
        }
        Column(Modifier.flapRow(2), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Heading("FOLDER")
            PixelTextField(state.folder, actions.folder, "Folder", Modifier.fillMaxWidth(), placeholder = "No folder", input = FieldInput.NAME)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip("NONE", state.folder.isBlank(), { actions.folder("") })
                for (f in state.folders) ToggleChip(f.uppercase(), state.folder.trim() == f, { actions.folder(f) })
            }
        }
        Column(Modifier.flapRow(3), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Heading("RUNS BETWEEN")
            if (state.places.isEmpty()) {
                PixelText(
                    "Save a place on the Saved places page and a route can start or end there.",
                    style = TtTheme.type.reading15, color = c.textSecondary,
                )
            } else {
                PlaceChoices("FROM", state, state.fromPlaceId, actions.from)
                PlaceChoices("TO", state, state.toPlaceId, actions.to)
            }
            state.warning?.let { NoteLine(it, Lamp.TRIANGLE, c.caution) }
        }
        Column(Modifier.flapRow(4), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Heading("LEGS")
            for (leg in state.legs) LegCard(leg)
        }
        Column(Modifier.flapRow(5), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PixelButton(actions.notify, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, enabled = !state.busy) { color ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PixelIcon(PixelIcons.Bell, tint = color, size = 18.dp)
                    PixelText("NOTIFY ME", style = TtTheme.type.label12Bold, color = color)
                }
            }
            PixelButton(actions.askDelete, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, enabled = !state.busy) { color ->
                PixelText("DELETE ROUTE", style = TtTheme.type.label12Bold, color = if (state.busy) color else c.stop)
            }
        }
    }
}

/** "FROM" and its chips: NONE, then each saved place. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceChoices(label: String, state: EditRouteUiState, selected: String?, onPick: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label, color = TtTheme.colors.textSecondary, size = LabelSize.L10)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip("A STATION", selected == null, { onPick(null) })
            for (p in state.places) ToggleChip(p.name.uppercase(), selected == p.id, { onPick(p.id) })
        }
    }
}

/** One leg, read-only: board, alight, lines and the change time. */
@Composable
private fun LegCard(leg: LegRow) {
    val c = TtTheme.colors
    val spoken = "Leg ${leg.number}. Board at ${leg.board}, get off at ${leg.alight}." +
        (if (leg.lines.isNotEmpty()) " Lines ${spokenLines(leg.lines)}." else "") +
        (leg.changeMinutes?.let { " At least $it minutes to change." } ?: "")
    PixelPanel(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }, verticalSpacing = 6.dp) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Label("LEG ${leg.number}", color = c.textSecondary, size = LabelSize.L10)
            LineBadges(leg.lines)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelText(leg.board, Modifier.weight(1f, fill = false), TtTheme.type.name17)
            PixelIcon(PixelIcons.ChevronRight, tint = c.text)
            PixelText(leg.alight, Modifier.weight(1f, fill = false), TtTheme.type.name17)
        }
        leg.changeMinutes?.let { Label("AT LEAST $it MIN TO CHANGE", color = c.textSecondary, size = LabelSize.L10) }
    }
}
