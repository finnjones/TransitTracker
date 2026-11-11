package app.transittracker.ui.feature.service

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.transittracker.model.WallClock
import app.transittracker.ui.app.LocalAppDeps
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.CarCount
import app.transittracker.ui.components.Digits
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIconButton
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.PlatformSign
import app.transittracker.ui.components.RouteBadge
import app.transittracker.ui.components.SeatPips
import app.transittracker.ui.components.StatusLine
import app.transittracker.ui.components.pixelBorder
import app.transittracker.ui.format.DefaultLineColor
import app.transittracker.ui.format.countdownMmSs
import app.transittracker.ui.format.formatClock
import app.transittracker.ui.format.parseLineColor
import app.transittracker.ui.format.shortStopName
import app.transittracker.ui.format.statusPresentation
import app.transittracker.ui.motion.PageTransition
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.sprites.blinkOn
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.strip.JourneyStrip
import app.transittracker.ui.theme.TtColors
import app.transittracker.ui.theme.TtTheme
import kotlin.math.max

/** What the service screen can ask for; the screen maps these to navigation. */
class ServiceActions(
    val back: () -> Unit = {},
    val notify: () -> Unit = {},
    val track: () -> Unit = {},
)

/** One service, every stop, where the vehicle is, actions (plan 5.4, Service mockup). */
@Composable
fun ServiceScreen(route: Route.Service) {
    val deps = LocalAppDeps.current
    val nav = LocalNavigator.current
    val holder = rememberStateHolder(route.ref, route.boardStopId, route.alightStopId) { scope ->
        ServiceStateHolder(deps.core, route.ref, route.boardStopId, route.alightStopId, deps.clock, scope)
    }
    val state by holder.state.collectAsState()
    val actions = remember(nav, route) {
        ServiceActions(
            back = { nav.back() },
            notify = {
                nav.push(Route.WatchEditor(fromService = route.ref, boardStopId = route.boardStopId, alightStopId = route.alightStopId))
            },
            track = {
                nav.push(Route.Tracking(route.ref, route.boardStopId, route.alightStopId), PageTransition.Doors)
            },
        )
    }
    ServiceContent(state, deps.clock, actions)
}

/** The service screen for [state]; stateless apart from the strip reading [clock]. */
@Composable
fun ServiceContent(state: ServiceUiState, clock: WallClock, actions: ServiceActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(TtTheme.colors.screen)) {
        Header(state, actions.back)
        when {
            state.loading -> Box(Modifier.weight(1f))
            !state.found -> NotFound(Modifier.weight(1f))
            else -> {
                StatusPanel(state, clock, Modifier.padding(horizontal = 16.dp))
                StopList(state, Modifier.weight(1f).fillMaxWidth())
                FooterBar(state, actions)
            }
        }
    }
}

// ---------------------------------------------------------------- header

@Composable
private fun Header(state: ServiceUiState, onBack: () -> Unit) {
    val c = TtTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelIconButton(PixelIcons.ChevronLeft, "Back", onBack)
        if (!state.loading && state.found) {
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) { heading() },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RouteBadge(state.routeName, state.routeColor, state.routeTextColor)
                    PixelText("to ${state.headsign}", style = TtTheme.type.title20, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                state.routeLongName?.let { Label(it.uppercase(), size = LabelSize.L10, color = c.textSecondary) }
            }
        } else {
            PixelText("Service", Modifier.weight(1f).semantics { heading() }, TtTheme.type.title20)
        }
    }
}

@Composable
private fun NotFound(modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        PixelText(
            "This service is no longer listed. It may have finished or been replaced.",
            style = TtTheme.type.reading15, color = TtTheme.colors.textReading,
        )
    }
}

// ---------------------------------------------------------------- status and strip

@Composable
private fun StatusPanel(state: ServiceUiState, clock: WallClock, modifier: Modifier) {
    val status = statusPresentation(state.status, state.delaySeconds, state.stale, state.dataAgeMs)
    val lineColor = parseLineColor(state.routeColor) ?: DefaultLineColor
    // A cancelled run has every call skipped, which would leave the strip with no stops: draw the run as timetabled.
    val strips = remember(state.calls, state.cancelled) {
        state.calls.map { it.copy(stopName = shortStopName(it.stopName), skipped = it.skipped && !state.cancelled) }
    }
    val showMeta = state.carCount != null || state.occupancy != app.transittracker.model.Occupancy.UNKNOWN
    PixelPanel(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), verticalSpacing = 10.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StatusLine(status)
            // Stale and timetable-only data are already the status line's words (plan 2.7).
            state.chip?.takeIf { it.kind == ChipKind.LIVE }?.let { DataChipLine(it.kind, it.ageMs) }
        }
        JourneyStrip(
            strips, state.stripBoardId.orEmpty(), state.stripAlightId.orEmpty(), state.mode, lineColor,
            state.status, clock, stale = state.stale, delaySeconds = state.delaySeconds, lateTag = false,
        )
        if (showMeta) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                state.carCount?.let { CarCount(it, diagram = false) }
                SeatPips(state.occupancy)
            }
        }
    }
}

// ---------------------------------------------------------------- stop list

@Composable
private fun StopList(state: ServiceUiState, modifier: Modifier) {
    val c = TtTheme.colors
    val lineColor = parseLineColor(state.routeColor) ?: DefaultLineColor
    val look = remember(c, lineColor, state.cancelled) {
        val dim = lerp(c.screen, lineColor, 0.4f)
        LineLook(line = if (state.cancelled) dim else lineColor, dim = dim)
    }
    val listState = rememberLazyListState()
    // Open on the vehicle, or on your stop, with a little of what came before it above.
    LaunchedEffect(state.loading) {
        if (state.loading) return@LaunchedEffect
        val anchor = state.items.indexOfFirst { it is ListItem.Marker }.takeIf { it >= 0 }
            ?: state.items.indexOfFirst { it is ListItem.Stop && it.row.role == StopRole.BOARD }.takeIf { it >= 0 }
            ?: 0
        listState.scrollToItem(max(0, anchor - 3))
    }
    LazyColumn(modifier, listState, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
        itemsIndexed(state.items, key = { _, item -> if (item is ListItem.Stop) "stop-${item.row.index}" else "marker" }) { _, item ->
            when (item) {
                is ListItem.Stop -> StopItem(item.row, state, look)
                is ListItem.Marker -> MarkerItem(item.marker, state, look)
            }
        }
    }
}

/** The stop line's two tints: ahead of the vehicle, and already travelled. */
private class LineLook(val line: Color, val dim: Color)

private fun DrawScope.drawSegment(color: Color, top: Float, bottom: Float, dot: Float) {
    // 6 dp wide, centred in the 28 dp cell (mockup: left 11, width 6).
    val w = 3 * dot
    val x = 14.dp.toPx() - w / 2f
    drawRect(color, Offset(x, top), Size(w, bottom - top))
}

@Composable
private fun StopItem(row: StopRow, state: ServiceUiState, look: LineLook) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    val fmt = TtTheme.time.clockFormat
    val tz = TtTheme.time.timeZone
    val cancelled = state.cancelled
    val key = row.role != StopRole.PLAIN
    val dimmed = row.passed || row.skipped || cancelled
    val inverted = row.role == StopRole.BOARD
    val travelled = state.marker?.let { row.index <= it.afterIndex } ?: row.passed
    val lineTint = if (travelled || cancelled) look.dim else look.line
    val nameColor = when {
        inverted -> c.screen
        dimmed && !key -> c.textSecondary
        cancelled -> c.textSecondary
        else -> c.text
    }
    val minHeight = when {
        key -> 56.dp
        row.passed || row.skipped -> 40.dp
        else -> 48.dp
    }
    val sentence = stopSentence(row, state, tz, fmt)
    var shell = Modifier
        .fillMaxWidth()
        .then(if (inverted) Modifier.background(c.text) else Modifier)
        .then(if (row.role == StopRole.ALIGHT) Modifier.pixelBorder(c.text) else Modifier)
        .clearAndSetSemantics { contentDescription = sentence }
    shell = shell.heightIn(min = minHeight).height(IntrinsicSize.Min)
    val pad = when (row.role) {
        StopRole.BOARD -> 8.dp
        StopRole.ALIGHT -> 8.dp
        StopRole.PLAIN -> 8.dp
    }
    Row(
        shell.padding(horizontal = pad),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(28.dp).fillMaxHeight().drawBehind {
                val d = dot.px.toFloat()
                drawSegment(lineTint, 0f, size.height, d)
                drawStopGlyph(row, c, cancelled, d)
            },
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PixelText(
                row.call.stopName.let(::shortStopName),
                style = if (key) TtTheme.type.name17 else TtTheme.type.row15,
                color = nameColor, strike = row.skipped || cancelled, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            PlatformLine(row, inverted)
        }
        TimeCells(row, cancelled, inverted, tz, fmt)
    }
}

/** Draws the stop's glyph in the 28 dp cell, centred on the line (Service mockup sizes in dots). */
private fun DrawScope.drawStopGlyph(row: StopRow, c: TtColors, cancelled: Boolean, d: Float) {
    val cx = 14.dp.toPx()
    val cy = size.height / 2f
    fun box(dots: Int, fill: Color?, border: Color?, borderDots: Int = 2) {
        val s = dots * d
        val x = cx - s / 2f
        val y = cy - s / 2f
        if (fill != null) drawRect(fill, Offset(x, y), Size(s, s))
        if (border != null) {
            val b = borderDots * d
            drawRect(border, Offset(x, y), Size(s, b))
            drawRect(border, Offset(x, y + s - b), Size(s, b))
            drawRect(border, Offset(x, y + b), Size(b, s - 2 * b))
            drawRect(border, Offset(x + s - b, y + b), Size(b, s - 2 * b))
        }
    }
    when (row.role) {
        StopRole.BOARD -> box(10, c.text, c.screen)
        StopRole.ALIGHT -> box(10, c.text, c.screen)
        StopRole.PLAIN -> when {
            row.skipped -> box(8, c.screen, c.divider)
            row.passed || cancelled -> box(6, c.textSecondary, null)
            else -> box(8, c.screen, c.text)
        }
    }
}

@Composable
private fun PlatformLine(row: StopRow, inverted: Boolean) {
    val c = TtTheme.colors
    val ink = if (inverted) c.screen else if (row.passed || row.skipped) c.textSecondary else c.textSecondary
    val strong = row.role != StopRole.PLAIN
    val platform = row.call.platform
    val lead = when (row.role) {
        StopRole.BOARD -> "BOARD HERE"
        StopRole.ALIGHT -> "GET OFF"
        StopRole.PLAIN -> null
    }
    if (lead == null && platform == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (lead != null) Label(lead, color = if (inverted) c.screen else c.text, bold = true, size = LabelSize.L10)
        if (lead != null && platform != null) Label("·", color = ink, bold = strong, size = LabelSize.L10)
        if (platform != null) {
            if (row.call.platformChanged) PlatformChip(platform)
            else Label("PLAT $platform", color = if (strong && !inverted) c.text else ink, bold = strong, size = LabelSize.L10)
        }
    }
}

/** "NOW PLAT 6" in a Caution block (plan 2.7), inside a stop row. */
@Composable
private fun PlatformChip(platform: String) {
    val c = TtTheme.colors
    Box(Modifier.background(c.caution).padding(horizontal = 4.dp, vertical = 1.dp)) {
        Label("NOW PLAT $platform", color = c.screen, bold = true, size = LabelSize.L10)
    }
}

@Composable
private fun TimeCells(
    row: StopRow,
    cancelled: Boolean,
    inverted: Boolean,
    tz: kotlinx.datetime.TimeZone,
    fmt: app.transittracker.ui.settings.ClockFormat,
) {
    val c = TtTheme.colors
    val key = row.role != StopRole.PLAIN
    val style = if (key) TtTheme.type.name17 else TtTheme.type.row15Strong
    if (row.skipped) {
        Label("SKIPS", color = c.textSecondary, bold = true, size = LabelSize.L10)
        return
    }
    val t = row.times ?: return
    val base = when {
        inverted -> c.screen
        row.passed || cancelled -> c.textSecondary
        else -> c.text
    }
    if (cancelled) {
        PixelText(formatClock(t.scheduledMs, tz, fmt), style = TtTheme.type.row15, color = c.textSecondary, strike = true, maxLines = 1)
    } else if (t.differs && t.expectedMs != null && !row.passed) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            PixelText(
                formatClock(t.scheduledMs, tz, fmt), Modifier.alignByBaseline(), TtTheme.type.row15,
                if (inverted) c.screen.copy(alpha = 0.6f) else c.textSecondary, strike = true, maxLines = 1,
            )
            PixelText(
                formatClock(t.expectedMs, tz, fmt), Modifier.alignByBaseline(), style,
                if (inverted) c.screen else if (row.passed) c.textSecondary else c.caution, maxLines = 1,
            )
        }
    } else {
        PixelText(formatClock(t.shownMs, tz, fmt), style = if (key) style else TtTheme.type.row15, color = base, maxLines = 1)
    }
}

/** "Strathfield, platform 3, board here, 4 minutes late: 07:42, timetabled 07:38" (plan 7). */
private fun stopSentence(
    row: StopRow,
    state: ServiceUiState,
    tz: kotlinx.datetime.TimeZone,
    fmt: app.transittracker.ui.settings.ClockFormat,
): String = buildString {
    append(shortStopName(row.call.stopName))
    row.call.platform?.let { append(if (row.call.platformChanged) ", platform changed, now platform $it" else ", platform $it") }
    when (row.role) {
        StopRole.BOARD -> append(", board here")
        StopRole.ALIGHT -> append(", get off here")
        StopRole.PLAIN -> Unit
    }
    when {
        row.skipped -> append(", skipped")
        row.times != null -> {
            val t = row.times
            if (state.cancelled) append(", timetabled ${formatClock(t.scheduledMs, tz, fmt)}, cancelled")
            else {
                append(if (row.passed) ", passed at " else ", ")
                append(formatClock(t.shownMs, tz, fmt))
                if (t.differs) append(", timetabled ${formatClock(t.scheduledMs, tz, fmt)}")
            }
        }
    }
}

// ---------------------------------------------------------------- the vehicle on the line

@Composable
private fun MarkerItem(marker: VehicleMarker, state: ServiceUiState, look: LineLook) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    val clock = TtTheme.spriteClock
    val reduce = TtTheme.motion.reduceMotion
    val before = state.calls.getOrNull(marker.afterIndex)?.stopName?.let(::shortStopName)
    val after = state.calls.getOrNull(marker.afterIndex + 1)?.stopName?.let(::shortStopName)
    val noun = modeNoun(state.mode)
    val (words, color) = when (marker.kind) {
        MarkerKind.LIVE -> "$noun IS HERE" to c.caution
        MarkerKind.STALE -> "$noun WAS LAST SEEN HERE" to c.textSecondary
        MarkerKind.TIMETABLE -> "$noun SHOULD BE HERE" to c.info
    }
    val spoken = buildString {
        append(when (marker.kind) {
            MarkerKind.LIVE -> "The ${noun.lowercase()} is "
            MarkerKind.STALE -> "The ${noun.lowercase()} was last seen "
            MarkerKind.TIMETABLE -> "The ${noun.lowercase()} should be "
        })
        append(if (marker.atStop && before != null) "at ${shortStopName(before)}"
        else if (before != null && after != null) "between $before and $after" else "on its way")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .heightIn(min = 32.dp)
            .height(IntrinsicSize.Min)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(28.dp).fillMaxHeight().drawBehind {
                val d = dot.px.toFloat()
                drawSegment(look.dim, 0f, size.height / 2f, d)
                drawSegment(if (state.cancelled) look.dim else look.line, size.height / 2f, size.height, d)
                // 24 x 20 dp car: line colour, a 2 dp Text border, two 6 dp windows (mockup).
                val cx = 14.dp.toPx()
                val cy = size.height / 2f
                val w = 12 * d
                val h = 10 * d
                val x = cx - w / 2f
                val y = cy - h / 2f
                val alpha = when (marker.kind) {
                    MarkerKind.LIVE -> if (clock.blinkOn(reduce)) 1f else 0.3f
                    MarkerKind.STALE -> 0.5f
                    MarkerKind.TIMETABLE -> 0.8f
                }
                val ghost = marker.kind == MarkerKind.TIMETABLE
                val edge = if (ghost) c.textSecondary else c.text
                drawRect(if (ghost) c.screen else look.line, Offset(x, y), Size(w, h), alpha = alpha)
                drawRect(edge, Offset(x, y), Size(w, d), alpha = alpha)
                drawRect(edge, Offset(x, y + h - d), Size(w, d), alpha = alpha)
                drawRect(edge, Offset(x, y), Size(d, h), alpha = alpha)
                drawRect(edge, Offset(x + w - d, y), Size(d, h), alpha = alpha)
                val win = 3 * d
                val wy = cy - win / 2f
                drawRect(edge, Offset(cx - 2 * d - win, wy), Size(win, win), alpha = alpha)
                drawRect(edge, Offset(cx + 2 * d, wy), Size(win, win), alpha = alpha)
            },
        )
        Label(words, Modifier.weight(1f), color = color, bold = true, size = LabelSize.L10)
    }
}

// ---------------------------------------------------------------- footer

@Composable
private fun FooterBar(state: ServiceUiState, actions: ServiceActions) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    val f = state.footer
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.panel)
            .drawBehind { drawRect(c.divider, Offset.Zero, Size(size.width, dot.px(1))) }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp + dot.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            FooterCountdown(f, state.nowMs, Modifier.weight(1f))
            if (f.platform != null && f.kind != FooterKind.DONE && f.kind != FooterKind.CANCELLED) {
                PlatformSign(f.platform, f.platformChanged, Modifier.padding(start = 12.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PixelButton("NOTIFY ME", actions.notify, Modifier.weight(2f), ButtonKind.PRIMARY)
            PixelButton("TRACK", actions.track, Modifier.weight(1f), ButtonKind.SECONDARY, enabled = f.kind != FooterKind.CANCELLED)
        }
    }
}

@Composable
private fun FooterCountdown(f: Footer, nowMs: Long, modifier: Modifier) {
    val c = TtTheme.colors
    val name = f.stopName.orEmpty().uppercase()
    when (f.kind) {
        FooterKind.CANCELLED -> Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label("THIS SERVICE IS CANCELLED", size = LabelSize.L10, color = c.stop, bold = true)
            PixelText("It will not run. See the timetable for the next one.", style = TtTheme.type.reading15, color = c.textReading)
        }
        FooterKind.DONE -> Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label("THIS SERVICE HAS FINISHED", size = LabelSize.L10, color = c.textSecondary)
        }
        else -> {
            val label = when (f.kind) {
                FooterKind.LEAVES -> "LEAVES $name IN"
                FooterKind.ARRIVES -> "ARRIVES $name IN"
                else -> "NEXT STOP $name IN"
            }
            val digits = f.targetMs?.let { countdownMmSs(it, nowMs) } ?: "--:--"
            val shown = (if (f.approximate) "~" else "") + digits
            val spoken = f.targetMs?.let { spokenCountdown(label, it - nowMs) } ?: label.lowercase()
            Column(modifier.clearAndSetSemantics { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label(label, size = LabelSize.L10, color = c.textSecondary)
                Digits(shown, style = TtTheme.type.digits44)
            }
        }
    }
}

/** "leaves Strathfield in 4 minutes 12 seconds". */
internal fun spokenCountdown(label: String, diffMs: Long): String {
    val total = (diffMs.coerceAtLeast(0) / 1000).toInt()
    val m = total / 60
    val s = total % 60
    val words = buildString {
        if (m > 0) append(if (m == 1) "1 minute" else "$m minutes")
        if (m > 0 && s > 0) append(' ')
        if (s > 0 || m == 0) append(if (s == 1) "1 second" else "$s seconds")
    }
    return label.lowercase().removeSuffix(" in") + " in " + words
}
