package app.transittracker.ui.feature.notify

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.transittracker.ui.app.LocalAppDeps
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.DayToggleRow
import app.transittracker.ui.components.DialogBox
import app.transittracker.ui.components.DialogScrim
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIconButton
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.SolidDivider
import app.transittracker.ui.components.StatusLamp
import app.transittracker.ui.components.StepperRow
import app.transittracker.ui.components.SwitchRow
import app.transittracker.ui.components.pixelFill
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.TtTheme
import kotlinx.coroutines.delay

/** The watch editor (plan 5.9, Notify mockup): a new commute watch, a new one-off watch, or an existing watch. */
@Composable
fun WatchEditorScreen(route: Route.WatchEditor) {
    val deps = LocalAppDeps.current
    val nav = LocalNavigator.current
    val zone = TtTheme.time.timeZone
    val format = TtTheme.time.clockFormat
    val holder = rememberStateHolder(route) { scope -> WatchEditorStateHolder(deps.core, route, deps.clock, scope, zone, format) }
    val state by holder.state.collectAsState()
    LaunchedEffect(state.finished) { if (state.finished) nav.back() }
    WatchEditorContent(
        state,
        WatchEditorActions(
            back = { nav.back() },
            days = holder::setDays,
            stepStart = holder::stepStart, stepEnd = holder::stepEnd, stepLead = holder::stepLead, stepThreshold = holder::stepThreshold,
            cancel = holder::setNotifyCancel, platform = holder::setNotifyPlatform,
            alerts = holder::setNotifyAlerts, recovered = holder::setNotifyRecovered,
            sendTest = holder::sendTest, save = holder::save,
            askDelete = holder::askDelete, cancelDelete = holder::cancelDelete, confirmDelete = holder::confirmDelete,
        ),
    )
}

/** What the editor can ask for. */
class WatchEditorActions(
    val back: () -> Unit = {},
    val days: (Set<Int>) -> Unit = {},
    val stepStart: (Int) -> Unit = {},
    val stepEnd: (Int) -> Unit = {},
    val stepLead: (Int) -> Unit = {},
    val stepThreshold: (Int) -> Unit = {},
    val cancel: (Boolean) -> Unit = {},
    val platform: (Boolean) -> Unit = {},
    val alerts: (Boolean) -> Unit = {},
    val recovered: (Boolean) -> Unit = {},
    val sendTest: () -> Unit = {},
    val save: () -> Unit = {},
    val askDelete: () -> Unit = {},
    val cancelDelete: () -> Unit = {},
    val confirmDelete: () -> Unit = {},
)

/** The editor for [state]; stateless. */
@Composable
fun WatchEditorContent(state: WatchEditorState, actions: WatchEditorActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Box(modifier.fillMaxSize().background(c.screen)) {
        Column(Modifier.fillMaxSize()) {
            EditorHeader(state, actions.back)
            when {
                state.loading -> Unit
                state.missing -> Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).flapRow(1)) {
                    PixelText(
                        "This watch is no longer there. Pick a saved trip on the Notify tab to start a new one.",
                        style = TtTheme.type.reading15, color = c.textReading,
                    )
                }
                else -> {
                    EditorBody(state, actions, Modifier.weight(1f))
                    EditorFooter(state, actions)
                }
            }
        }
        if (state.confirmDelete) {
            val name = state.ticket?.let { t -> if (t.to != null) "${t.from} to ${t.to}" else t.from }.orEmpty()
            DialogScrim(actions.cancelDelete) {
                DialogBox(
                    "Delete this watch?",
                    body = "You will stop getting notifications for $name.",
                    actions = {
                        PixelButton("KEEP IT", actions.cancelDelete, Modifier.weight(1f))
                        PixelButton("DELETE", actions.confirmDelete, Modifier.weight(1f), kind = ButtonKind.PRIMARY)
                    },
                )
            }
        }
    }
}

@Composable
private fun EditorHeader(state: WatchEditorState, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().flapRow(0).padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelIconButton(PixelIcons.ChevronLeft, "Back", onBack)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PixelText("Notify me", Modifier.semantics { heading() }, TtTheme.type.title20, maxLines = 1)
            if (state.slot.isNotEmpty()) Label(state.slot, color = TtTheme.colors.textSecondary, size = LabelSize.L10)
        }
    }
}

// ---------------------------------------------------------------- body

@Composable
private fun EditorBody(state: WatchEditorState, actions: WatchEditorActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        state.ticket?.let { Ticket(it, Modifier.flapRow(1)) }
        if (state.isCommute) {
            Column(Modifier.flapRow(2), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("DAYS", color = c.textSecondary, size = LabelSize.L10)
                DayToggleRow(state.days, actions.days, Modifier.padding(2.dp))
                state.problem?.let { PixelText(it, style = TtTheme.type.reading15, color = c.caution) }
            }
            Column(Modifier.flapRow(3), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(
                    "SERVICES LEAVING " + (state.ticket?.from ?: "").uppercase(),
                    color = c.textSecondary, size = LabelSize.L10, modifier = Modifier.semantics { heading() },
                )
                StepperRow(
                    "From", clockOfSecs(state.startSecs), { actions.stepStart(-1) }, { actions.stepStart(1) },
                    "Earlier start", "Later start", valueStyle = TtTheme.type.digits30,
                    canDecrement = state.canStartEarlier, canIncrement = state.canStartLater,
                )
                StepperRow(
                    "Until", clockOfSecs(state.endSecs), { actions.stepEnd(-1) }, { actions.stepEnd(1) },
                    "Earlier end", "Later end", valueStyle = TtTheme.type.digits30,
                    canDecrement = state.canEndEarlier, canIncrement = state.canEndLater,
                )
                StepperRow(
                    "Start early", minutesWords(state.leadMinutes), { actions.stepLead(-1) }, { actions.stepLead(1) },
                    "Less lead time", "More lead time",
                    canDecrement = state.canLeadLess, canIncrement = state.canLeadMore,
                )
            }
        }
        Column(Modifier.flapRow(4), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label(
                "TELL ME WHEN", color = c.textSecondary, size = LabelSize.L10,
                modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
            )
            StepperRow(
                "Late by", minutesWords(state.thresholdMinutes), { actions.stepThreshold(-1) }, { actions.stepThreshold(1) },
                "Lower delay threshold", "Raise delay threshold",
                canDecrement = state.canThresholdLower, canIncrement = state.canThresholdRaise,
            )
            SwitchRow("Cancelled", state.notifyCancel, actions.cancel)
            SwitchRow("Platform changes", state.notifyPlatform, actions.platform)
            SwitchRow("Line alerts", state.notifyAlerts, actions.alerts)
            SwitchRow("Back on time", state.notifyRecovered, actions.recovered)
        }
        if (state.canDelete) {
            PixelButton(actions.askDelete, Modifier.fillMaxWidth().flapRow(5), kind = ButtonKind.SECONDARY, enabled = !state.busy) { color ->
                PixelText("DELETE WATCH", style = TtTheme.type.label12Bold, color = if (state.busy) color else c.stop)
            }
        }
    }
}

/** The trip as a ticket: Text-coloured card, Screen-coloured ink (Notify mockup). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Ticket(ticket: WatchTicket, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val spoken = if (ticket.to != null) "${ticket.from} to ${ticket.to}. ${ticket.detail.lowercase()}" else "${ticket.from}. ${ticket.detail.lowercase()}"
    Column(
        modifier.fillMaxWidth().pixelFill(c.text).padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            PixelText(ticket.from, style = TtTheme.type.title20, color = c.screen)
            if (ticket.to != null) {
                PixelIcon(PixelIcons.ChevronRight, tint = c.screen)
                PixelText(ticket.to, style = TtTheme.type.title20, color = c.screen)
            }
        }
        Label(ticket.detail, color = c.screen, bold = true, size = LabelSize.L10)
    }
}

// ---------------------------------------------------------------- footer

@Composable
private fun EditorFooter(state: WatchEditorState, actions: WatchEditorActions) {
    val c = TtTheme.colors
    Column(Modifier.fillMaxWidth().background(c.screen)) {
        SolidDivider()
        Column(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.result?.let { ResultView(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PixelButton(
                    if (state.testing) workingText("SENDING") else "SEND TEST",
                    actions.sendTest, Modifier.weight(1f), kind = ButtonKind.SECONDARY, enabled = !state.busy,
                )
                PixelButton(
                    if (state.saving) workingText("SAVING") else "SAVE WATCH",
                    actions.save, Modifier.weight(2f), kind = ButtonKind.PRIMARY, enabled = state.canSave,
                )
            }
        }
    }
}

@Composable
private fun ResultView(result: ResultLine) {
    val c = TtTheme.colors
    val (lamp, color) = when (result.kind) {
        ResultKind.OK -> Lamp.CIRCLE to c.go
        ResultKind.INFO -> Lamp.RING to c.info
        ResultKind.ERROR -> Lamp.CROSS to c.stop
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusLamp(lamp, color)
        PixelText(result.text, Modifier.weight(1f), TtTheme.type.reading15, c.textReading)
    }
}

/** The three-frame "working" label (plan 6): dots count up; still when motion is reduced. */
@Composable
private fun workingText(word: String): String {
    val reduce = TtTheme.motion.reduceMotion
    var frame by remember { mutableIntStateOf(2) }
    LaunchedEffect(reduce) {
        if (!reduce) {
            frame = 0
            while (true) {
                delay(250)
                frame = (frame + 1) % 3
            }
        }
    }
    return word + ".".repeat(frame + 1)
}
