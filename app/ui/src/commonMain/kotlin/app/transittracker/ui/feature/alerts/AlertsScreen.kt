package app.transittracker.ui.feature.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import app.transittracker.model.AlertSeverity
import app.transittracker.ui.app.LocalAppDeps
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.DashedDivider
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIconData
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.RouteBadge
import app.transittracker.ui.components.StatusLamp
import app.transittracker.ui.components.ToggleChip
import app.transittracker.ui.components.pixelBorder
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.format.formatClock
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.TtTheme

/**
 * The Alerts tab: disruptions, your trips first (plan 5.8, Alerts mockup). The filter and the
 * expanded card live in the page's saved state; [initialExpanded] opens one card (screenshots).
 */
@Composable
fun AlertsScreen(initialExpanded: String? = null) {
    val deps = LocalAppDeps.current
    val holder = rememberStateHolder { scope -> AlertsStateHolder(deps.core, deps.clock, scope) }
    val state by holder.state.collectAsState()
    var filterName by rememberSaveable { mutableStateOf(AlertFilter.MY_TRIPS.name) }
    var expanded by rememberSaveable { mutableStateOf(initialExpanded) }
    AlertsContent(
        state = state,
        filter = AlertFilter.valueOf(filterName),
        expandedId = expanded,
        onFilter = { filterName = it.name },
        onToggle = { id -> expanded = if (expanded == id) null else id },
        onMoreInfo = { deps.platform.openUrl(it) },
    )
}

/** The Alerts tab for [state]; stateless. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertsContent(
    state: AlertsUiState,
    filter: AlertFilter,
    expandedId: String?,
    onFilter: (AlertFilter) -> Unit,
    onToggle: (String) -> Unit,
    onMoreInfo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = TtTheme.colors
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().flapRow(0).padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PixelText("Alerts", Modifier.semantics { heading() }, TtTheme.type.title22)
            val updated = state.updatedMs?.let { "UPDATED " + formatClock(it, TtTheme.time.timeZone, TtTheme.time.clockFormat) }
            if (updated != null) Label(updated, color = c.textSecondary, size = LabelSize.L10)
        }
        Row(
            Modifier.fillMaxWidth().flapRow(1).padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ToggleChip("MY TRIPS · ${state.mine.size}", filter == AlertFilter.MY_TRIPS, { onFilter(AlertFilter.MY_TRIPS) })
            ToggleChip("ALL LINES", filter == AlertFilter.ALL_LINES, { onFilter(AlertFilter.ALL_LINES) })
        }
        if (state.loading) return@Column
        val cards = state.cards(filter)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            var row = 2
            if (state.offline) {
                val r = row++
                item(key = "offline") { OfflineNote(Modifier.flapRow(r)) }
            }
            for (card in cards) {
                val r = row++
                item(key = card.id) {
                    AlertCardView(
                        card, state.nowMs, card.id == expandedId && card.expandable, { onToggle(card.id) }, onMoreInfo,
                        Modifier.fillMaxWidth().flapRow(r),
                    )
                }
            }
            if (filter == AlertFilter.MY_TRIPS) {
                for (trip in state.clearTrips) {
                    val r = row++
                    item(key = "clear-${trip.id}") { AllClearRow(tripWords(trip), Modifier.flapRow(r)) }
                }
                if (state.tripCount == 0) {
                    val r = row++
                    item(key = "no-trips") {
                        EmptyNote(
                            "No saved trips yet. Save a trip on the Trips tab and the alerts that touch it show up here.",
                            "SEE ALL LINES", { onFilter(AlertFilter.ALL_LINES) }, Modifier.flapRow(r),
                        )
                    }
                }
            } else if (cards.isEmpty()) {
                val r = row++
                item(key = "none") { EmptyNote("No disruptions reported. Every line is running as planned.", null, {}, Modifier.flapRow(r)) }
            }
        }
    }
}

// ---------------------------------------------------------------- card

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertCardView(
    card: AlertCard,
    nowMs: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onMoreInfo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = TtTheme.colors
    val alert = card.alert
    val (icon, tint) = severityLook(alert.severity)
    val whenText = alertWhen(alert.activePeriods, nowMs, TtTheme.time.timeZone, TtTheme.time.clockFormat)
    val ago = agoWords(alert.updatedMs, nowMs)?.let { "UPDATED $it" }
    val source = remember { MutableInteractionSource() }
    PixelPanel(modifier, contentPadding = PaddingValues(12.dp), verticalSpacing = 10.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(
                    source, null, enabled = card.expandable, role = Role.Button,
                    onClickLabel = if (expanded) "Hide details" else "Show details", onClick = onToggle,
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PixelIcon(icon, tint = tint)
                    Label(severityWord(alert.severity), color = tint, bold = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label(whenText, color = c.textSecondary, size = LabelSize.L10)
                    if (card.expandable) PixelIcon(if (expanded) PixelIcons.Minus else PixelIcons.Plus, tint = c.textSecondary)
                }
            }
            PixelText(alert.headline, style = TtTheme.type.name17)
            if (expanded && alert.description.isNotBlank()) {
                PixelText(alert.description, style = TtTheme.type.reading15, color = c.textReading)
            }
            val hasAffects = card.lines.isNotEmpty() || card.stationNames.isNotEmpty() || card.trips.isNotEmpty()
            if (hasAffects || (!expanded && ago != null && card.trips.isEmpty())) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FlowRow(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (line in card.lines.take(MAX_BADGES)) RouteBadge(line.name, line.colorHex, line.textColorHex)
                        if (card.lines.size > MAX_BADGES) Label("+${card.lines.size - MAX_BADGES}", color = c.textSecondary, size = LabelSize.L10)
                        if (card.stationNames.isNotEmpty()) {
                            Label(
                                card.stationNames.joinToString(", ") { it.uppercase() } + " " + card.stationWord,
                                Modifier.pixelBorder(c.textSecondary).padding(horizontal = 5.dp + TtTheme.dot.dp, vertical = 1.dp + TtTheme.dot.dp),
                                color = c.textSecondary, size = LabelSize.L10,
                            )
                        }
                        if (card.trips.isNotEmpty()) Label(onYourTripWords(card.trips), color = c.textSecondary, size = LabelSize.L10)
                    }
                    if (!expanded && ago != null && card.trips.isEmpty()) Label(ago, color = c.textSecondary, size = LabelSize.L10)
                }
            }
        }
        if (expanded && (ago != null || !alert.url.isNullOrBlank())) {
            DashedDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (ago != null) Label(ago, color = c.textSecondary, size = LabelSize.L10) else Row {}
                alert.url?.takeIf { it.isNotBlank() }?.let { url ->
                    PixelButton("MORE INFO", { onMoreInfo(url) }, kind = ButtonKind.SECONDARY, style = TtTheme.type.label11Bold)
                }
            }
        }
    }
}

private const val MAX_BADGES = 4

@Composable
private fun severityLook(s: AlertSeverity): Pair<PixelIconData, androidx.compose.ui.graphics.Color> {
    val c = TtTheme.colors
    return when (s) {
        AlertSeverity.SEVERE -> PixelIcons.Warning to c.stop
        AlertSeverity.WARNING -> PixelIcons.Warning to c.caution
        AlertSeverity.INFO -> PixelIcons.Info to c.info
        AlertSeverity.UNKNOWN -> PixelIcons.Info to c.textSecondary
    }
}

// ---------------------------------------------------------------- the rest

@Composable
private fun AllClearRow(words: String, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp)
            .clearAndSetSemantics { contentDescription = "All clear on ${words.lowercase()}" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusLamp(Lamp.CIRCLE, c.go, size = 12.dp)
        Label("ALL CLEAR", color = c.go, bold = true, size = LabelSize.L10)
        Label(words, color = c.textSecondary, size = LabelSize.L10)
    }
}

@Composable
private fun OfflineNote(modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val text = "No connection. These are the last alerts we received."
    PixelPanel(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = text }) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusLamp(Lamp.RING, c.info)
            PixelText(text, Modifier.weight(1f), TtTheme.type.reading15, c.textReading)
        }
    }
}

@Composable
private fun EmptyNote(text: String, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PixelText(text, style = TtTheme.type.reading15, color = TtTheme.colors.textReading)
        if (action != null) PixelButton(action, onAction, kind = ButtonKind.SECONDARY)
    }
}
