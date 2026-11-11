package app.transittracker.ui.feature.notify

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.transittracker.model.SavedTrip
import app.transittracker.ui.app.LocalAppDeps
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.DialogBox
import app.transittracker.ui.components.DialogScrim
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIconButton
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelSwitch
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.SectionLabel
import app.transittracker.ui.components.StatusLamp
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.TtTheme

/** What the Notify tab can ask for; the screen maps these to navigation and the holder. */
class NotifyActions(
    val add: () -> Unit = {},
    val open: (watchId: String) -> Unit = {},
    val setPaused: (watchId: String, paused: Boolean) -> Unit = { _, _ -> },
    val allow: () -> Unit = {},
    val openPhoneSettings: () -> Unit = {},
    val retry: () -> Unit = {},
    val closeProblem: () -> Unit = {},
    val pickTrip: (SavedTrip) -> Unit = {},
    val closePicker: () -> Unit = {},
)

/** The Notify tab: push watches (plan 5.9). */
@Composable
fun NotifyScreen() {
    val deps = LocalAppDeps.current
    val nav = LocalNavigator.current
    val zone = TtTheme.time.timeZone
    val format = TtTheme.time.clockFormat
    val holder = rememberStateHolder { scope -> NotifyStateHolder(deps.core, deps.platform, deps.clock, scope, zone, format) }
    val state by holder.state.collectAsState()
    var picking by rememberSaveable { mutableStateOf(false) }
    val actions = remember(nav, holder) {
        NotifyActions(
            add = {
                val trips = holder.state.value.trips
                if (trips.size == 1) nav.push(Route.WatchEditor(fromTripId = trips.first().id)) else picking = true
            },
            open = { id -> nav.push(Route.WatchEditor(watchId = id)) },
            setPaused = holder::setPaused,
            allow = holder::allowNotifications,
            openPhoneSettings = holder::openPhoneSettings,
            retry = holder::retry,
            closeProblem = holder::dismissProblem,
            pickTrip = { trip ->
                picking = false
                nav.push(Route.WatchEditor(fromTripId = trip.id))
            },
            closePicker = { picking = false },
        )
    }
    NotifyContent(state, picking && state.canAdd, actions)
}

/** The Notify tab for [state]; stateless. [picking] shows the "which trip" dialog. */
@Composable
fun NotifyContent(state: NotifyUiState, picking: Boolean, actions: NotifyActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            NotifyHeader(state, actions.add, Modifier.flapRow(0))
            if (!state.loading) {
                if (state.isEmpty) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        var row = 1
                        if (state.notificationsBlocked) PermissionBanner(actions.allow, Modifier.flapRow(row++))
                        if (state.readOnly) OfflineBanner(Modifier.flapRow(row++))
                        EmptyWatches(state, actions.add, Modifier.flapRow(row))
                    }
                } else {
                    WatchList(state, actions, Modifier.weight(1f))
                }
            }
        }
        state.problem?.let { p ->
            DialogScrim(actions.closeProblem) {
                DialogBox(
                    "Could not change the watch",
                    body = p.words,
                    actions = {
                        PixelButton("CLOSE", actions.closeProblem, Modifier.weight(1f))
                        PixelButton("RETRY", actions.retry, Modifier.weight(1f), kind = ButtonKind.PRIMARY)
                    },
                )
            }
        }
        if (picking) TripPicker(state.trips, actions)
    }
}

@Composable
private fun NotifyHeader(state: NotifyUiState, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PixelText("Notify", Modifier.semantics { heading() }, TtTheme.type.title22)
            if (!state.loading) {
                Label(
                    "${state.count} OF ${state.limit} WATCHES",
                    color = if (state.full) TtTheme.colors.caution else TtTheme.colors.textSecondary, size = LabelSize.L10,
                )
            }
        }
        PixelIconButton(
            PixelIcons.Plus, "Add a watch", onAdd, kind = ButtonKind.PRIMARY, enabled = state.canAdd, iconSize = 21.dp,
        )
    }
}

// ---------------------------------------------------------------- list

@Composable
private fun WatchList(state: NotifyUiState, actions: NotifyActions, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        var row = 1
        if (state.notificationsBlocked) {
            val r = row++
            item(key = "banner-permission") { PermissionBanner(actions.allow, Modifier.flapRow(r)) }
        }
        if (state.readOnly) {
            val r = row++
            item(key = "banner-offline") { OfflineBanner(Modifier.flapRow(r)) }
        }
        if (state.full) {
            val r = row++
            item(key = "banner-full") { FullNote(Modifier.flapRow(r)) }
        }
        fun section(key: String, title: String?, cards: List<WatchCard>) {
            if (cards.isEmpty()) return
            if (title != null) {
                val r = row++
                item(key = "section-$key") { SectionLabel(title, Modifier.flapRow(r)) }
            }
            for (card in cards) {
                val r = row++
                item(key = card.id) {
                    WatchCardView(card, state.readOnly, actions, Modifier.fillMaxWidth().flapRow(r))
                }
            }
        }
        val several = listOf(state.commutes, state.today, state.later).count { it.isNotEmpty() } > 1
        section("commutes", if (several) "COMMUTES" else null, state.commutes)
        section("today", "TODAY", state.today)
        section("later", "LATER", state.later)
        val r = row
        item(key = "tip") { BatteryTip(state.batterySaver, actions.openPhoneSettings, Modifier.flapRow(r)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchCardView(card: WatchCard, readOnly: Boolean, actions: NotifyActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val ink = if (card.active) c.text else c.textSecondary
    val title = card.summary.title
    val spokenTitle = title.joinToString(" to ")
    val spoken = buildString {
        append(spokenTitle)
        append(". ")
        append(card.summary.details.joinToString(". "))
        card.pausedWords?.let { append(". ${it.lowercase()}") }
    }
    val source = remember { MutableInteractionSource() }
    PixelPanel(modifier, contentPadding = PaddingValues(start = 12.dp, top = 6.dp, end = 8.dp, bottom = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).heightIn(min = TtTheme.spacing.touch)
                    .clickable(source, null, role = Role.Button, onClickLabel = "Edit this watch") { actions.open(card.id) }
                    .clearAndSetSemantics { contentDescription = spoken }
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    for ((i, part) in title.withIndex()) {
                        if (i > 0) PixelIcon(PixelIcons.ChevronRight, tint = ink)
                        PixelText(part, style = TtTheme.type.name17, color = ink)
                    }
                }
                PixelText(card.summary.details.joinToString(" · "), style = TtTheme.type.reading15, color = c.textSecondary)
                card.pausedWords?.let { Label(it, color = c.caution, bold = true, size = LabelSize.L10) }
            }
            PixelSwitch(
                card.active,
                Modifier.semantics { contentDescription = "Watch on or off: $spokenTitle" },
                onCheckedChange = { on -> actions.setPaused(card.id, !on) },
                enabled = !readOnly,
            )
        }
    }
}

// ---------------------------------------------------------------- banners and notes

@Composable
private fun PermissionBanner(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    PixelPanel(modifier.fillMaxWidth(), borderColor = c.caution) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusLamp(Lamp.TRIANGLE, c.caution)
            PixelText("Notifications are off, so a late train will not reach you.", Modifier.weight(1f), TtTheme.type.reading15, c.textReading)
        }
        PixelButton("ALLOW", onAllow, kind = ButtonKind.SECONDARY)
    }
}

@Composable
private fun OfflineBanner(modifier: Modifier = Modifier) {
    val text = "No connection. Your watches are shown as last saved and cannot be changed now."
    PixelPanel(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = text }) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusLamp(Lamp.RING, TtTheme.colors.info)
            PixelText(text, Modifier.weight(1f), TtTheme.type.reading15, TtTheme.colors.textReading)
        }
    }
}

@Composable
private fun FullNote(modifier: Modifier = Modifier) {
    val text = "All $WATCH_LIMIT watch slots are in use. Delete one to add another."
    PixelPanel(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = text }) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusLamp(Lamp.RING, TtTheme.colors.info)
            PixelText(text, Modifier.weight(1f), TtTheme.type.reading15, TtTheme.colors.textReading)
        }
    }
}

/** The battery-saver tip (plan 5.9): some phones stop background delivery. */
@Composable
private fun BatteryTip(saverOn: Boolean, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val text = (if (saverOn) "Battery saver is on, so your phone may hold notifications back. " else "") +
        "If alerts arrive late, allow TransitTracker to run in the background in your phone's settings."
    PixelPanel(modifier.fillMaxWidth(), borderColor = c.divider, background = c.screen) {
        Label("TIP", color = if (saverOn) c.caution else c.textSecondary, bold = true, size = LabelSize.L10)
        PixelText(text, style = TtTheme.type.reading15, color = c.textReading)
        PixelButton("PHONE SETTINGS", onOpenSettings, kind = ButtonKind.SECONDARY, style = TtTheme.type.label11Bold)
    }
}

@Composable
private fun EmptyWatches(state: NotifyUiState, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Column(modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PixelIcon(PixelIcons.Bell, tint = c.textSecondary, size = 48.dp)
        PixelText("No watches yet", Modifier.semantics { heading() }, TtTheme.type.title20)
        PixelText(
            if (state.trips.isEmpty()) "A watch tells you when a train you use is late, cancelled or changes platform. Save a trip on the Trips tab first, then watch it here."
            else "A watch tells you when a train you use is late, cancelled or changes platform. Pick one of your saved trips to start.",
            style = TtTheme.type.reading15, color = c.textReading, textAlign = TextAlign.Center,
        )
        PixelButton("WATCH A TRIP", onAdd, kind = ButtonKind.PRIMARY, enabled = state.canAdd)
    }
}

/** Which saved trip to watch, when there are several. */
@Composable
private fun TripPicker(trips: List<SavedTrip>, actions: NotifyActions) {
    DialogScrim(actions.closePicker) {
        DialogBox(
            "Watch which trip?",
            actions = { PixelButton("CANCEL", actions.closePicker, Modifier.weight(1f)) },
            content = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (trip in trips) {
                        PixelButton(
                            "${trip.originName} to ${trip.destinationName}", { actions.pickTrip(trip) },
                            Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, style = TtTheme.type.row15Strong,
                        )
                    }
                }
            },
        )
    }
}
