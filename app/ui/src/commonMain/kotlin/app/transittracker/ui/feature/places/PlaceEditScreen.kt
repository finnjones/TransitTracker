package app.transittracker.ui.feature.places

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.transittracker.model.Place
import app.transittracker.ui.app.LocalAppDeps
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.DashedDivider
import app.transittracker.ui.components.DialogBox
import app.transittracker.ui.components.DialogScrim
import app.transittracker.ui.components.FieldInput
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelSwitch
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.PixelTextField
import app.transittracker.ui.components.Stepper
import app.transittracker.ui.components.pixelBorder
import app.transittracker.ui.components.pixelFill
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.places.PlaceIcon
import app.transittracker.ui.sprites.WalkerPose
import app.transittracker.ui.sprites.WalkerSprite
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.TtTheme

/** What the place editor can ask for. */
class PlaceEditActions(
    val back: () -> Unit = {},
    val name: (String) -> Unit = {},
    val icon: (PlaceIcon) -> Unit = {},
    val query: (String) -> Unit = {},
    val pick: (Place) -> Unit = {},
    val locate: () -> Unit = {},
    val retrySearch: () -> Unit = {},
    val retryStops: () -> Unit = {},
    val stopEnabled: (stopId: String, enabled: Boolean) -> Unit = { _, _ -> },
    val toggleWalk: (stopId: String) -> Unit = {},
    val stepWalk: (stopId: String, delta: Int) -> Unit = { _, _ -> },
    val save: () -> Unit = {},
    val askDelete: () -> Unit = {},
    val cancelDelete: () -> Unit = {},
    val confirmDelete: () -> Unit = {},
    val retry: () -> Unit = {},
    val closeProblem: () -> Unit = {},
)

/** Add a place or edit one (plan 5.7, Add place mockup). */
@Composable
fun PlaceEditScreen(route: Route.PlaceEdit) {
    val deps = LocalAppDeps.current
    val nav = LocalNavigator.current
    val holder = rememberStateHolder(route.placeId) { scope ->
        PlaceEditStateHolder(deps.core, deps.planner, deps.places, deps.platform, route.placeId, scope)
    }
    val state by holder.state.collectAsState()
    LaunchedEffect(state.finished) { if (state.finished) nav.back() }
    PlaceEditContent(
        route.title, state,
        PlaceEditActions(
            back = { nav.back() },
            name = holder::setName, icon = holder::setIcon, query = holder::setQuery, pick = holder::pick,
            locate = holder::useMyLocation, retrySearch = holder::retrySearch, retryStops = holder::retryStops,
            stopEnabled = holder::setStopEnabled, toggleWalk = holder::toggleWalk, stepWalk = holder::stepWalk,
            save = holder::save, askDelete = holder::askDelete, cancelDelete = holder::cancelDelete,
            confirmDelete = holder::confirmDelete, retry = holder::retry, closeProblem = holder::dismissProblem,
        ),
    )
}

/** The editor for [state]; stateless. */
@Composable
fun PlaceEditContent(title: String, state: PlaceEditState, actions: PlaceEditActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Box(modifier.fillMaxSize().background(c.screen)) {
        Column(Modifier.fillMaxSize()) {
            PlacesHeader(title, null, actions.back)
            when {
                state.loading -> Box(Modifier.weight(1f))
                state.missing -> Box(Modifier.weight(1f).padding(horizontal = 18.dp, vertical = 8.dp).flapRow(1)) {
                    PixelText(
                        "This place is no longer there. Go back to Saved places to add it again.",
                        style = TtTheme.type.reading15, color = c.textReading,
                    )
                }
                else -> {
                    EditorBody(state, actions, Modifier.weight(1f))
                    EditorFooter(state, actions)
                }
            }
        }
        if (state.confirmDelete) DeleteDialog(state, actions)
        state.problem?.let { ProblemDialog(it, actions.closeProblem, actions.retry) }
    }
}

// ---------------------------------------------------------------- body

@Composable
private fun EditorBody(state: PlaceEditState, actions: PlaceEditActions, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        NameAndIcon(state, actions, Modifier.flapRow(1))
        AddressSection(state, actions, Modifier.flapRow(2))
        StopsSection(state, actions, Modifier.flapRow(3))
        if (state.canDelete) {
            PixelButton(actions.askDelete, Modifier.fillMaxWidth().flapRow(4), kind = ButtonKind.SECONDARY, enabled = !state.busy) { color ->
                PixelText("DELETE PLACE", style = TtTheme.type.label12Bold, color = if (state.busy) color else TtTheme.colors.stop)
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Label(text, modifier.semantics { heading() }, color = TtTheme.colors.textSecondary, size = LabelSize.L10)
}

@Composable
private fun NameAndIcon(state: PlaceEditState, actions: PlaceEditActions, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("NAME AND ICON")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelTextField(
                state.name, actions.name, "Place name", Modifier.weight(1f),
                placeholder = "Home, Work, the gym", input = FieldInput.NAME,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (icon in PlaceIcon.entries) IconToggle(icon, state.icon == icon) { actions.icon(icon) }
            }
        }
    }
}

/** One of the three icons: inverted when chosen (Add place mockup). */
@Composable
private fun IconToggle(icon: PlaceIcon, selected: Boolean, onSelect: () -> Unit) {
    val c = TtTheme.colors
    Box(
        Modifier.size(48.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .semantics { contentDescription = "${icon.words()} icon" }
            .pixelFill(if (selected) c.text else c.panel)
            .pixelBorder(if (selected) c.text else c.divider),
        contentAlignment = Alignment.Center,
    ) {
        PixelIcon(icon.pixelIcon(), tint = if (selected) c.screen else c.textSecondary, size = 24.dp)
    }
}

// ---------------------------------------------------------------- address

@Composable
private fun AddressSection(state: PlaceEditState, actions: PlaceEditActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading("ADDRESS")
        PixelTextField(
            state.query, actions.query, "Address", Modifier.fillMaxWidth(),
            placeholder = "Street, suburb or place", input = FieldInput.SEARCH, onSubmit = actions.retrySearch,
        )
        SearchAnswer(state, actions)
        PixelButton(
            if (state.locating) "FINDING YOU…" else "OR USE WHERE I AM NOW", actions.locate, Modifier.fillMaxWidth(),
            kind = ButtonKind.SECONDARY, enabled = !state.locating, style = TtTheme.type.label11Bold,
        )
        state.locationProblem?.let { NoteLine(it, Lamp.TRIANGLE, c.caution) }
    }
}

/** What the address lookup answered: results to pick from, or a plain sentence (plan 6). */
@Composable
private fun SearchAnswer(state: PlaceEditState, actions: PlaceEditActions) {
    val c = TtTheme.colors
    when (val s = state.search) {
        AddressSearch.Idle -> Unit
        AddressSearch.Searching -> Label("SEARCHING…", color = c.textSecondary, size = LabelSize.L10)
        is AddressSearch.Results -> PixelPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), verticalSpacing = 0.dp) {
            for ((i, place) in s.places.withIndex()) {
                if (i > 0) DashedDivider()
                Column(
                    Modifier.fillMaxWidth().heightIn(min = TtTheme.spacing.touch)
                        .tappable("Use this address") { actions.pick(place) }
                        .clearAndSetSemantics { contentDescription = addressText(place) }
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    PixelText(place.name, style = TtTheme.type.row15Strong, maxLines = 1)
                    if (place.detail.isNotBlank()) PixelText(place.detail, style = TtTheme.type.reading15, color = c.textSecondary, maxLines = 1)
                }
            }
        }
        AddressSearch.NothingFound -> AnswerNote(
            "No address found for “${state.query.trim()}”. Check the spelling, or add the suburb.", null,
        )
        AddressSearch.Offline -> AnswerNote(
            "No connection, so addresses cannot be looked up now. Try again, or use where you are now.", actions.retrySearch,
        )
        AddressSearch.Unavailable -> AnswerNote(
            "Address lookup is not available right now. Try again later, or use where you are now.", actions.retrySearch,
        )
        AddressSearch.Failed -> AnswerNote("The address lookup had a problem. Try again in a moment.", actions.retrySearch)
    }
}

@Composable
private fun AnswerNote(text: String, onTryAgain: (() -> Unit)?) {
    PixelPanel(Modifier.fillMaxWidth().semantics { contentDescription = text }) {
        NoteLine(text, Lamp.RING, TtTheme.colors.info)
        if (onTryAgain != null) PixelButton("TRY AGAIN", onTryAgain, kind = ButtonKind.SECONDARY, style = TtTheme.type.label11Bold)
    }
}

// ---------------------------------------------------------------- stops

@Composable
private fun StopsSection(state: PlaceEditState, actions: PlaceEditActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeading("STOPS YOU WOULD WALK TO", Modifier.padding(bottom = 4.dp))
        when {
            state.position == null -> PixelText(
                "Pick an address, or use where you are now. The stations and stops within walking distance appear here.",
                style = TtTheme.type.reading15, color = c.textSecondary,
            )
            state.stopsLoading -> Label("LOOKING FOR STOPS…", color = c.textSecondary, size = LabelSize.L10)
            state.stopsProblem != null -> AnswerNote(state.stopsProblem, actions.retryStops)
            state.stops.isEmpty() -> PixelText(
                NO_STOPS_NEAR,
                style = TtTheme.type.reading15, color = c.caution,
            )
            else -> {
                for (row in state.stops) {
                    StopRowView(row, open = state.editingWalk == row.stop.stopId, actions)
                }
                DashedDivider()
                Label(
                    "WALK TIMES ARE ESTIMATES. TAP ONE TO CORRECT IT.",
                    Modifier.padding(top = 10.dp), color = c.textSecondary, size = LabelSize.L10,
                )
                state.stopsNote?.let { AnswerNote(it, actions.retryStops) }
            }
        }
    }
}

/** One stop (Add place mockup): name and lines, the walk, and the switch. Tap the walk to correct it. */
@Composable
private fun StopRowView(row: StopRow, open: Boolean, actions: PlaceEditActions) {
    val c = TtTheme.colors
    val s = row.stop
    val ink = if (s.enabled) c.text else c.textSecondary
    val spoken = "${s.name}${if (row.lines.isEmpty()) modesLabel(s.modes)?.let { ", ${it.lowercase()}" }.orEmpty() else ""}, ${spokenWalk(s.walkMinutes, s.estimated)}, ${formatDistance(s.distanceMeters).lowercase().replace(" m", " metres").replace(" km", " kilometres")}" +
        if (row.lines.isNotEmpty()) ", lines ${spokenLines(row.lines)}" else ""
    DashedDivider()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            PixelText(s.name, style = TtTheme.type.row15Strong, color = ink, maxLines = 2)
            LineBadges(row.lines, dim = !s.enabled)
            // Stops the phone has no timetable for (buses, light rail) say what calls there instead.
            if (row.lines.isEmpty()) modesLabel(s.modes)?.let { Label(it, color = c.textSecondary, size = LabelSize.L10) }
        }
        Row(
            Modifier.heightIn(min = TtTheme.spacing.touch)
                .tappable("Correct the walk time") { actions.toggleWalk(s.stopId) }
                .semantics { contentDescription = "Walk time ${spokenWalk(s.walkMinutes, s.estimated)}. Tap to correct it." },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalkerSprite(WalkerPose.STAND)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label(formatWalk(s.walkMinutes), color = ink, bold = true)
                Label(if (s.estimated) formatDistance(s.distanceMeters) else "YOUR TIME", color = c.textSecondary, size = LabelSize.L10)
            }
        }
        PixelSwitch(
            s.enabled, Modifier.semantics { contentDescription = "Use ${s.name}" },
            onCheckedChange = { actions.stopEnabled(s.stopId, it) },
        )
    }
    if (open) WalkCorrection(s, actions)
}

/** The stepper under a stop whose walk time is being corrected. */
@Composable
private fun WalkCorrection(stop: app.transittracker.ui.places.PlaceStop, actions: PlaceEditActions) {
    val c = TtTheme.colors
    PixelPanel(Modifier.fillMaxWidth().padding(bottom = 6.dp), background = c.raised, verticalSpacing = 8.dp) {
        Label("WALK TO ${stop.name.uppercase()}", color = c.textSecondary, size = LabelSize.L10)
        Stepper(
            "${stop.walkMinutes} MIN",
            { actions.stepWalk(stop.stopId, -1) }, { actions.stepWalk(stop.stopId, 1) },
            "Shorter walk", "Longer walk",
            canDecrement = steppedWalk(stop.walkMinutes, -1) != null,
            canIncrement = steppedWalk(stop.walkMinutes, 1) != null,
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Label(
                if (stop.estimated) "ESTIMATE FROM DISTANCE" else "YOUR FIGURE",
                Modifier.weight(1f), color = c.textSecondary, size = LabelSize.L10,
            )
            PixelButton("DONE", { actions.toggleWalk(stop.stopId) }, kind = ButtonKind.SECONDARY, style = TtTheme.type.label11Bold)
        }
    }
}

// ---------------------------------------------------------------- footer and dialogs

@Composable
private fun EditorFooter(state: PlaceEditState, actions: PlaceEditActions) {
    Footer {
        val reason = state.saveProblem
        if (!state.canSave && reason != null) NoteLine(reason, Lamp.RING, TtTheme.colors.info)
        PixelButton(
            if (state.saving) "SAVING…" else "SAVE PLACE", actions.save, Modifier.fillMaxWidth(),
            kind = ButtonKind.PRIMARY, enabled = state.canSave, style = TtTheme.type.label13Bold,
        )
    }
}

@Composable
private fun DeleteDialog(state: PlaceEditState, actions: PlaceEditActions) {
    val name = state.name.trim().ifEmpty { "this place" }
    val body = when (state.routesUsing) {
        0 -> "No saved routes use it. This cannot be undone."
        1 -> "1 saved route uses it. It will stay, and go back to running between its stations."
        else -> "${state.routesUsing} saved routes use it. They will stay, and go back to running between their stations."
    }
    DialogScrim(actions.cancelDelete) {
        DialogBox(
            "Delete $name?", body = body,
            actions = {
                PixelButton("KEEP IT", actions.cancelDelete, Modifier.weight(1f))
                PixelButton("DELETE", actions.confirmDelete, Modifier.weight(1f), kind = ButtonKind.PRIMARY)
            },
        )
    }
}
