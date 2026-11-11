package app.transittracker.ui.feature.places

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import app.transittracker.ui.components.PixelIconButton
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.PixelTextField
import app.transittracker.ui.components.SectionLabel
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.TtTheme

/** What Manage routes can ask for. */
class ManageRoutesActions(
    val back: () -> Unit = {},
    val open: (tripId: String) -> Unit = {},
    val move: (tripId: String, delta: Int) -> Unit = { _, _ -> },
    val rename: (folder: String) -> Unit = {},
    val renameText: (String) -> Unit = {},
    val cancelRename: () -> Unit = {},
    val confirmRename: () -> Unit = {},
    val planJourney: () -> Unit = {},
    val retry: () -> Unit = {},
    val closeProblem: () -> Unit = {},
)

/** Reorder, rename and open saved routes (plan 5.2, 5.6 "Saved routes"). */
@Composable
fun ManageRoutesScreen() {
    val deps = LocalAppDeps.current
    val nav = LocalNavigator.current
    val holder = rememberStateHolder { scope -> ManageRoutesStateHolder(deps.core, deps.places, scope) }
    val state by holder.state.collectAsState()
    val actions = remember(nav, holder) {
        ManageRoutesActions(
            back = { nav.back() },
            open = { id -> nav.push(Route.EditRoute(id)) },
            move = holder::move,
            rename = holder::startRename, renameText = holder::setRenameText,
            cancelRename = holder::cancelRename, confirmRename = holder::confirmRename,
            planJourney = { nav.push(Route.PlanJourney()) },
            retry = holder::retry, closeProblem = holder::dismissProblem,
        )
    }
    ManageRoutesContent(state, actions)
}

/** Manage routes for [state]; stateless. */
@Composable
fun ManageRoutesContent(state: ManageRoutesUiState, actions: ManageRoutesActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Box(modifier.fillMaxSize().background(c.screen)) {
        Column(Modifier.fillMaxSize()) {
            PlacesHeader(
                "Manage routes",
                if (state.loading) null else if (state.count == 1) "1 SAVED ROUTE" else "${state.count} SAVED ROUTES",
                actions.back,
            )
            when {
                state.loading -> Unit
                state.isEmpty -> EmptyRoutes(actions.planJourney)
                else -> RouteList(state, actions, Modifier.weight(1f))
            }
        }
        state.renaming?.let { RenameDialog(it, actions) }
        state.problem?.let { ProblemDialog(it, actions.closeProblem, actions.retry) }
    }
}

@Composable
private fun RouteList(state: ManageRoutesUiState, actions: ManageRoutesActions, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        var row = 1
        for (folder in state.folders) {
            val headingRow = row++
            if (folder.name != null || state.hasFolders) {
                item(key = "folder-${folder.name}") {
                    FolderHeading(folder.name, actions, Modifier.flapRow(headingRow))
                }
            }
            for (r in folder.rows) {
                val rowNo = row++
                item(key = r.id) { RouteCard(r, actions, Modifier.fillMaxWidth().flapRow(rowNo)) }
            }
        }
    }
}

@Composable
private fun FolderHeading(name: String?, actions: ManageRoutesActions, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(name ?: "NO FOLDER")
        if (name != null) {
            PixelButton(
                "RENAME", { actions.rename(name) }, Modifier.semantics { contentDescription = "Rename folder $name" },
                kind = ButtonKind.FLAT, style = TtTheme.type.label10Bold,
            )
        }
    }
}

/** One saved route: its ends, its lines and the move buttons. Tapping the text opens it for editing. */
@Composable
private fun RouteCard(row: RouteRow, actions: ManageRoutesActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val spoken = buildString {
        append(row.spokenName)
        row.stationsLine?.let { append(". ${it.lowercase()}") }
        if (row.lines.isNotEmpty()) append(". Lines ${spokenLines(row.lines)}")
        if (row.changes > 0) append(". ${if (row.changes == 1) "1 change" else "${row.changes} changes"}")
    }
    PixelPanel(modifier, contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).heightIn(min = TtTheme.spacing.touch * 2)
                    .tappable("Edit this route") { actions.open(row.id) }
                    .clearAndSetSemantics { contentDescription = spoken }
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                RouteTitle(row.titleParts)
                row.stationsLine?.let { Label(it, color = c.textSecondary, size = LabelSize.L10) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LineBadges(row.lines)
                    if (row.changes > 0) {
                        Label(if (row.changes == 1) "1 CHANGE" else "${row.changes} CHANGES", color = c.textSecondary, size = LabelSize.L10)
                    }
                }
            }
            Column {
                PixelIconButton(MoveIcons.Up, row.upWords + ": " + row.spokenName, { actions.move(row.id, -1) }, enabled = row.canMoveUp, iconSize = 14.dp)
                PixelIconButton(MoveIcons.Down, row.downWords + ": " + row.spokenName, { actions.move(row.id, 1) }, enabled = row.canMoveDown, iconSize = 14.dp)
            }
        }
    }
}

/** "Home > Work" with a chevron, or a single name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RouteTitle(parts: List<String>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        for ((i, part) in parts.withIndex()) {
            if (i > 0) PixelIcon(PixelIcons.ChevronRight, tint = TtTheme.colors.text)
            PixelText(part, style = TtTheme.type.name17)
        }
    }
}

@Composable
private fun RenameDialog(r: FolderRename, actions: ManageRoutesActions) {
    DialogScrim(actions.cancelRename) {
        DialogBox(
            "Rename folder",
            actions = {
                PixelButton("CANCEL", actions.cancelRename, Modifier.weight(1f))
                PixelButton("SAVE", actions.confirmRename, Modifier.weight(1f), kind = ButtonKind.PRIMARY, enabled = r.canSave)
            },
            content = {
                PixelTextField(r.text, actions.renameText, "Folder name", Modifier.fillMaxWidth(), placeholder = "Folder name", input = FieldInput.NAME, onSubmit = actions.confirmRename)
                if (!r.canSave) PixelText("A folder needs a name.", style = TtTheme.type.reading15, color = TtTheme.colors.caution)
            },
        )
    }
}

@Composable
private fun EmptyRoutes(onPlan: () -> Unit) {
    val c = TtTheme.colors
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = 24.dp).flapRow(1),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PixelIcon(PixelIcons.Train, tint = c.textSecondary, size = 48.dp)
            PixelText("No saved routes yet", Modifier.semantics { heading() }, TtTheme.type.title20)
            PixelText(
                "Plan a journey, then tap SAVE ROUTE to keep it here. Saved routes show their next services on the Trips tab.",
                style = TtTheme.type.reading15, color = c.textReading, textAlign = TextAlign.Center,
            )
            PixelButton("PLAN A JOURNEY", onPlan, kind = ButtonKind.PRIMARY)
        }
    }
}
