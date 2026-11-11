package app.transittracker.ui.feature.places

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.transittracker.ui.app.LocalAppDeps
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.DashedDivider
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIconButton
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.SolidDivider
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.sprites.WalkerPose
import app.transittracker.ui.sprites.WalkerSprite
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.TtTheme

/** What the saved places list can ask for. */
class PlacesActions(
    val back: () -> Unit = {},
    val add: () -> Unit = {},
    val edit: (placeId: String) -> Unit = {},
    val toggleReorder: () -> Unit = {},
    val move: (placeId: String, delta: Int) -> Unit = { _, _ -> },
)

/** The saved places list (plan 5.7, Places mockup). */
@Composable
fun PlacesScreen() {
    val deps = LocalAppDeps.current
    val nav = LocalNavigator.current
    val holder = rememberStateHolder { scope -> PlacesStateHolder(deps.places, deps.core, scope) }
    val state by holder.state.collectAsState()
    val actions = remember(nav, holder) {
        PlacesActions(
            back = { nav.back() },
            add = { nav.push(Route.PlaceEdit()) },
            edit = { id -> nav.push(Route.PlaceEdit(id)) },
            toggleReorder = holder::toggleReordering,
            move = holder::move,
        )
    }
    PlacesContent(state, actions)
}

/** The list for [state]; stateless. */
@Composable
fun PlacesContent(state: PlacesUiState, actions: PlacesActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Column(modifier.fillMaxSize().background(c.screen)) {
        PlacesHeader(
            "Saved places", "KEPT ON THIS PHONE ONLY", actions.back,
            actions = {
                if (state.canReorder) {
                    PixelButton(if (state.reordering) "DONE" else "ORDER", actions.toggleReorder, kind = ButtonKind.SECONDARY, style = TtTheme.type.label11Bold)
                }
            },
        )
        when {
            state.loading -> Box(Modifier.weight(1f))
            state.isEmpty -> EmptyPlaces(actions.add, Modifier.weight(1f))
            else -> {
                PlaceList(state, actions, Modifier.weight(1f))
                Footer { PixelButton("ADD A PLACE", actions.add, Modifier.fillMaxWidth(), kind = ButtonKind.PRIMARY, style = TtTheme.type.label13Bold) }
            }
        }
    }
}

/** The bar under a page's content: a rule, then the page's main action (Places and Add place mockups). */
@Composable
internal fun Footer(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(TtTheme.colors.screen)) {
        SolidDivider()
        Column(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun PlaceList(state: PlacesUiState, actions: PlacesActions, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(state.cards.size, key = { state.cards[it].id }) { i ->
            val card = state.cards[i]
            val row = Modifier.fillMaxWidth().flapRow(i + 1)
            if (state.reordering) {
                ReorderCard(card, canUp = i > 0, canDown = i < state.cards.lastIndex, actions, row)
            } else {
                PlaceCardView(card, actions, row)
            }
        }
        item(key = "note") { PhoneOnlyNote(Modifier.flapRow(state.cards.size + 1)) }
    }
}

/** One place with its stops (Places mockup). */
@Composable
private fun PlaceCardView(card: PlaceCard, actions: PlacesActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val spoken = buildString {
        append("${card.name}, ${card.address}. ")
        if (card.stops.isEmpty()) append("No stops switched on. ")
        for (s in card.stops) {
            append("${s.name}, ${spokenWalk(s.walkMinutes, s.estimated)}")
            if (s.lines.isNotEmpty()) append(", lines ${spokenLines(s.lines)}")
            append(". ")
        }
        append(card.usedByWords.lowercase())
    }
    PixelPanel(modifier, verticalSpacing = 10.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).tappable("Edit this place") { actions.edit(card.id) }.clearAndSetSemantics { contentDescription = spoken },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlaceIconTile(card.icon)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PixelText(card.name, style = TtTheme.type.name17, maxLines = 1)
                    PixelText(card.address, style = TtTheme.type.reading15, color = c.textSecondary, maxLines = 2)
                }
            }
            PixelButton(
                "EDIT", { actions.edit(card.id) }, Modifier.semantics { contentDescription = "Edit ${card.name}" },
                kind = ButtonKind.SECONDARY, style = TtTheme.type.label11Bold,
            )
        }
        DashedDivider()
        Column(Modifier.clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (card.stops.isEmpty()) {
                PixelText("No stops switched on. Tap EDIT to choose some.", style = TtTheme.type.reading15, color = c.caution)
            }
            for (s in card.stops) StopLine(s)
        }
        Label(card.usedByWords, color = c.textSecondary, size = LabelSize.L10)
    }
}

@Composable
private fun StopLine(stop: PlaceStopLine) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelText(stop.name, Modifier.weight(1f, fill = false), TtTheme.type.row15, maxLines = 1)
            LineBadges(stop.lines)
        }
        Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            WalkerSprite(WalkerPose.STAND)
            Label(formatWalk(stop.walkMinutes), bold = true)
        }
    }
}

/** A place while the order is being changed: icon, name and two move buttons. */
@Composable
private fun ReorderCard(card: PlaceCard, canUp: Boolean, canDown: Boolean, actions: PlacesActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    PixelPanel(modifier, contentPadding = PaddingValues(start = 12.dp, top = 6.dp, end = 6.dp, bottom = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaceIconTile(card.icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PixelText(card.name, style = TtTheme.type.name17, maxLines = 1)
                PixelText(card.address, style = TtTheme.type.reading15, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PixelIconButton(MoveIcons.Up, "Move ${card.name} up", { actions.move(card.id, -1) }, enabled = canUp, iconSize = 14.dp)
            PixelIconButton(MoveIcons.Down, "Move ${card.name} down", { actions.move(card.id, 1) }, enabled = canDown, iconSize = 14.dp)
        }
    }
}

/** Plan 5.7: places never leave the phone; only the address text is sent, to be looked up. */
@Composable
private fun PhoneOnlyNote(modifier: Modifier = Modifier) {
    val text = "Places stay on this phone. Only the address you type is sent, to look it up."
    PixelPanel(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = text }, borderColor = TtTheme.colors.divider, background = TtTheme.colors.screen) {
        NoteLine(text, Lamp.RING, TtTheme.colors.info)
    }
}

@Composable
private fun EmptyPlaces(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = 24.dp).flapRow(1),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PixelIcon(PixelIcons.House, tint = c.textSecondary, size = 48.dp)
            PixelText("No places yet", Modifier.semantics { heading() }, TtTheme.type.title20)
            PixelText(
                "A place is a named address, like Home or Work. Save one and TransitTracker finds the stations you would walk to, " +
                    "works out when to leave, and names your routes after it.",
                style = TtTheme.type.reading15, color = c.textReading, textAlign = TextAlign.Center,
            )
            PixelButton("ADD YOUR FIRST PLACE", onAdd, kind = ButtonKind.PRIMARY)
        }
        PhoneOnlyNote(Modifier.flapRow(2).heightIn(min = 0.dp))
    }
}
