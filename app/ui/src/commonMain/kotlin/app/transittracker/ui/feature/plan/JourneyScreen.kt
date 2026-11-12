package app.transittracker.ui.feature.plan

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.transittracker.model.DepartureStatus
import app.transittracker.model.ItineraryLeg
import app.transittracker.model.Mode
import app.transittracker.ui.app.LocalAppDeps
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.DialogBox
import app.transittracker.ui.components.DialogScrim
import app.transittracker.ui.components.Digits
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIconData
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.PlatformSign
import app.transittracker.ui.components.RouteBadge
import app.transittracker.ui.components.SolidDivider
import app.transittracker.ui.components.StatusLamp
import app.transittracker.ui.components.StatusLine
import app.transittracker.ui.components.StatusSize
import app.transittracker.ui.components.SwitchRow
import app.transittracker.ui.components.drawPixelBorder
import app.transittracker.ui.components.pixelFill
import app.transittracker.ui.format.CountdownText
import app.transittracker.ui.format.DefaultLineColor
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.format.countdown
import app.transittracker.ui.format.formatClock
import app.transittracker.ui.format.parseLineColor
import app.transittracker.ui.format.statusPresentation
import app.transittracker.ui.motion.PageTransition
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.nav.Tab
import app.transittracker.ui.places.SavedPlace
import app.transittracker.ui.sprites.VehicleSprite
import app.transittracker.ui.sprites.WalkerPose
import app.transittracker.ui.sprites.WalkerSprite
import app.transittracker.ui.sprites.vehicleState
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.TtTheme

/** One journey, step by step, with SAVE ROUTE (plan 5.6 step 3, Journey mockup). */
@Composable
fun JourneyScreen(route: Route.JourneyDetail) {
    val deps = LocalAppDeps.current
    val holder = rememberStateHolder(route) { scope ->
        JourneyStateHolder(deps.core, deps.places, deps.clock, route.itinerary, route.from, route.to, scope)
    }
    JourneyPage(route, holder)
}

@Composable
fun JourneyPage(route: Route.JourneyDetail, holder: JourneyStateHolder, showSaveDialog: Boolean = false) {
    val nav = LocalNavigator.current
    val deps = LocalAppDeps.current
    val state by holder.state.collectAsState()
    val places by deps.places.places.collectAsState(emptyList())
    LaunchedEffect(state.save) {
        if (state.save is SaveState.Saved) {
            if (route.fromOnboarding) {
                nav.resetTo(Tab.TRIPS, PageTransition.Doors)
            } else {
                nav.popToRoot()
                if (nav.currentTab != Tab.TRIPS) nav.switchTab(Tab.TRIPS)
            }
        }
    }
    JourneyContent(route, state, places, onBack = { nav.back() }, onSave = holder::save, showSaveDialog = showSaveDialog)
}

private fun iconOf(end: app.transittracker.ui.nav.JourneyEnd, places: List<SavedPlace>): PixelIconData = endIcon(choiceOf(end, places).icon)

@Composable
fun JourneyContent(
    route: Route.JourneyDetail,
    state: JourneyUiState,
    places: List<SavedPlace>,
    onBack: () -> Unit,
    onSave: (withReturn: Boolean, join: Boolean) -> Unit,
    showSaveDialog: Boolean = false,
) {
    val c = TtTheme.colors
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    val t = state.timeline
    var dialog by rememberSaveable { mutableStateOf(showSaveDialog) }
    var withReturn by rememberSaveable { mutableStateOf(false) }
    var join by rememberSaveable { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(c.screen)) {
        Column(Modifier.fillMaxSize()) {
            JourneyHeader(
                route.from.name, route.to.name,
                (t.missed?.let { "MISSES THE ${it.toLine} · " } ?: "${t.minutes} MIN · ") + summaryWords(t.changes, t.walkMinutes), onBack,
            )
            val missed = t.missed
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
            ) {
                if (missed != null) {
                    item(key = "missed") {
                        NoticePanel(
                            missedSentence(missed, tz, fmt), Lamp.CROSS, c.stop,
                            Modifier.padding(bottom = 10.dp).flapRow(1),
                            title = "This journey no longer works",
                            action = "SEE OTHER JOURNEYS", onAction = onBack,
                        )
                    }
                }
                itemsIndexed(t.steps) { i, step ->
                    val m = Modifier.fillMaxWidth().flapRow(2 + i)
                    when (step) {
                        is Step.Start -> EndRow(step.name, step.atMs, iconOf(route.from, places), leaveWords(countdown(t.leaveMs, state.nowMs, tz, fmt, verb = "leave")), first = true, m)
                        is Step.Walk -> WalkRow(step, m)
                        is Step.Board -> BoardRow(step, top = i > 0, m)
                        is Step.Alight -> AlightRow(step, bottom = true, m)
                        is Step.Change -> ChangeRow(step.connection, m)
                        is Step.End -> EndRow(step.name, step.atMs, iconOf(route.to, places), "ARRIVE", first = false, m)
                    }
                }
            }
            Footer(route, state, onSave = { dialog = true })
        }
        val ready = (state.save as? SaveState.Ready) ?: (state.save as? SaveState.Failed)?.ready
        if (dialog && ready != null) {
            val joinTitle = ready.joinTitle
            DialogScrim(onDismiss = { dialog = false }) {
                if (ready.alreadyThere && joinTitle != null) {
                    DialogBox(
                        "Already saved",
                        body = routeDescription(route.from.name, route.to.name, route.itinerary) +
                            " is already one of the ways of $joinTitle. Trips shows it whenever it is the best way to go.",
                        actions = { PixelButton("CLOSE", { dialog = false }, Modifier.weight(1f), ButtonKind.PRIMARY) },
                    )
                } else {
                    DialogBox(
                        "Save this route",
                        body = routeDescription(route.from.name, route.to.name, route.itinerary) + ". " +
                            if (joinTitle != null && join) {
                                "Trips will show $joinTitle by whichever way gets you there first when you leave."
                            } else {
                                "Trips will show the next time it runs, with its leave-by time."
                            },
                        actions = {
                            PixelButton("CANCEL", { dialog = false }, Modifier.weight(1f))
                            PixelButton("SAVE", { dialog = false; onSave(withReturn, join) }, Modifier.weight(1f), ButtonKind.PRIMARY)
                        },
                    ) {
                        if (joinTitle != null) SwitchRow("Add as another way to $joinTitle", join, { join = it })
                        SwitchRow("Also save the return", withReturn, { withReturn = it })
                    }
                }
            }
        }
    }
}

private fun leaveWords(cd: CountdownText) = when (cd.kind) {
    CountdownText.Kind.NOW -> "LEAVE NOW"
    CountdownText.Kind.MINUTES -> "LEAVE IN ${cd.value} MIN"
    CountdownText.Kind.CLOCK -> "LEAVE AT ${cd.value}"
}

// ---------------------------------------------------------------- footer

@Composable
private fun Footer(route: Route.JourneyDetail, state: JourneyUiState, onSave: () -> Unit) {
    val c = TtTheme.colors
    val t = state.timeline
    val passed = t.leaveMs <= state.nowMs
    Column(Modifier.fillMaxWidth().background(c.panel)) {
        SolidDivider()
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!route.browseOnly) when (val s = state.save) {
                is SaveState.Blocked -> PixelText(s.reason, style = TtTheme.type.reading15, color = c.textReading)
                is SaveState.Failed -> PixelText("Saving did not work (${s.message}). Try again.", style = TtTheme.type.reading15, color = c.stop)
                else -> Unit
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                val name = route.from.name.uppercase()
                Column(
                    Modifier.clearAndSetSemantics {
                        contentDescription = if (passed) "Leave ${route.from.name} now" else "Leave ${route.from.name} in " + leaveClockSpoken(t.leaveMs, state.nowMs)
                    },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PixelText(if (passed) "LEAVE $name NOW" else "LEAVE $name IN", style = TtTheme.type.label10, color = c.textSecondary, maxLines = 1)
                    Digits(leaveClock(t.leaveMs, state.nowMs), style = TtTheme.type.digits44)
                }
                val enabled = state.save is SaveState.Ready || state.save is SaveState.Failed
                val label = when (state.save) {
                    SaveState.Checking -> "SAVE ROUTE"
                    SaveState.Saving -> "SAVING..."
                    is SaveState.Saved -> "SAVED"
                    is SaveState.Ready -> if (state.save.alreadyThere) "SAVED" else if (state.save.joinTo != null) "ADD AS A WAY" else "SAVE ROUTE"
                    else -> "SAVE ROUTE"
                }
                if (!route.browseOnly) PixelButton(label, onSave, Modifier.padding(bottom = 6.dp), ButtonKind.SECONDARY, enabled = enabled)
            }
        }
    }
}

// ---------------------------------------------------------------- the rail

private sealed interface Seg {
    data object None : Seg
    data object Walk : Seg
    data class Ride(val color: Color) : Seg
}

/** The 24 dp rail of the timeline: dotted for walks, the line colour for rides, a node in the middle. */
@Composable
private fun Rail(top: Seg, bottom: Seg, square: Color?, icon: PixelIconData?, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    Box(modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val d = dot.px.toFloat()
            val mid = (size.height / 2 / d).toInt() * d
            val cx = (size.width / 2 / d).toInt() * d
            fun seg(s: Seg, y0: Float, y1: Float) {
                when (s) {
                    Seg.None -> Unit
                    Seg.Walk -> {
                        var y = y0
                        while (y < y1) {
                            drawRect(c.textSecondary, Offset(cx - d, y), Size(2 * d, minOf(2 * d, y1 - y)))
                            y += 4 * d
                        }
                    }
                    is Seg.Ride -> drawRect(s.color, Offset(cx - d - d / 2f, y0), Size(3 * d, y1 - y0))
                }
            }
            seg(top, 0f, mid)
            seg(bottom, mid, size.height)
            if (square != null) {
                val s = 8 * d
                val left = cx - 4 * d
                val tp = mid - 4 * d
                drawRect(square, Offset(left, tp), Size(s, s))
                drawPixelBorder(c.text, d, left, tp, s, s)
            }
        }
        if (icon != null) {
            Box(Modifier.size(24.dp).background(c.text), contentAlignment = Alignment.Center) {
                PixelIcon(icon, tint = c.screen, size = 16.dp)
            }
        }
    }
}

@Composable
private fun TimelineRow(
    time: @Composable () -> Unit,
    rail: @Composable () -> Unit,
    minHeight: Int,
    sentence: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier.height(IntrinsicSize.Min).heightIn(min = minHeight.dp).clearAndSetSemantics { contentDescription = sentence },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(46.dp)) { time() }
        rail()
        Box(Modifier.weight(1f).padding(vertical = 4.dp)) { content() }
    }
}

private fun lineColor(leg: ItineraryLeg): Color = parseLineColor(leg.routeColor) ?: DefaultLineColor

// ---------------------------------------------------------------- rows

@Composable
private fun EndRow(name: String, atMs: Long, icon: PixelIconData, words: String, first: Boolean, modifier: Modifier) {
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    val time = formatClock(atMs, tz, fmt, withSuffix = false)
    TimelineRow(
        time = { PixelText(time, style = TtTheme.type.row15Strong, maxLines = 1) },
        rail = { Rail(if (first) Seg.None else Seg.Walk, if (first) Seg.Walk else Seg.None, null, icon) },
        minHeight = 44,
        sentence = if (first) "$name, ${words.lowercase()}, at $time" else "$name, arrive at $time",
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelText(name, Modifier.weight(1f, fill = false), TtTheme.type.name17, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PixelText(words, style = TtTheme.type.label10Bold, maxLines = 1)
        }
    }
}

@Composable
private fun WalkRow(step: Step.Walk, modifier: Modifier) {
    val c = TtTheme.colors
    val min = (step.seconds + 59) / 60
    TimelineRow(
        time = {},
        rail = { Rail(Seg.Walk, Seg.Walk, null, null) },
        minHeight = 34,
        sentence = "Walk $min ${if (min == 1) "minute" else "minutes"}" + (step.meters?.let { ", $it metres" } ?: ""),
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            WalkerSprite(WalkerPose.STAND)
            Label("WALK $min MIN", bold = true)
            step.meters?.let { Label("$it M", size = LabelSize.L10, color = c.textSecondary) }
        }
    }
}

private fun stopsWords(n: Int) = if (n == 1) "1 STOP" else "$n STOPS"

@Composable
private fun BoardRow(step: Step.Board, top: Boolean, modifier: Modifier) {
    val c = TtTheme.colors
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    val leg = step.leg
    val t = step.times
    val status = statusPresentation(t.status, t.delaySeconds, t.stale, t.dataAgeMs)
    val color = lineColor(leg)
    val cancelled = t.cancelled
    val sched = formatClock(t.scheduledDepartMs, tz, fmt, withSuffix = false)
    val exp = formatClock(t.departMs, tz, fmt, withSuffix = false)
    val line = leg.routeName.orEmpty()
    val sentence = buildString {
        append("$exp, ${leg.from.name}")
        t.platform?.let { append(if (t.platformChanged) ", platform changed, now ${platformWords(it).lowercase()}" else ", ${platformWords(it).lowercase()}") }
        append(". $line to ${leg.headsign.orEmpty()}, ${status.spoken}")
        leg.stopCount?.let { append(", ${stopsWords(it).lowercase()}") }
    }
    TimelineRow(
        time = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (status.strikeScheduledTime && !cancelled) {
                    PixelText(sched, style = TtTheme.type.label11, color = c.textSecondary, strike = true, maxLines = 1)
                    PixelText(exp, style = TtTheme.type.row15Strong, color = c.caution, maxLines = 1)
                } else {
                    PixelText(if (cancelled) sched else exp, style = TtTheme.type.row15Strong, color = if (cancelled) c.textSecondary else c.text, strike = cancelled, maxLines = 1)
                }
            }
        },
        rail = { Rail(if (top) Seg.Walk else Seg.None, Seg.Ride(color), color, null) },
        minHeight = 66,
        sentence = sentence,
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PixelText(leg.from.name, Modifier.weight(1f, fill = false), TtTheme.type.name17, strike = cancelled, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val p = t.platform
                    if (p != null) {
                        if (t.platformChanged) PlatformSign(p, true) else Label(platformWords(p), size = LabelSize.L10, bold = true)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RouteBadge(line, leg.routeColor, null)
                    PixelText("to ${leg.headsign.orEmpty()}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusLine(status, size = StatusSize.SMALL)
                    leg.stopCount?.let { Label("· " + stopsWords(it), size = LabelSize.L10, color = c.textSecondary) }
                }
            }
            VehicleSprite(
                leg.mode ?: Mode.OTHER, color,
                vehicleState(t.status, t.stale, if (t.status == DepartureStatus.LATE) t.delaySeconds else null, atStop = false),
            )
        }
    }
}

@Composable
private fun AlightRow(step: Step.Alight, bottom: Boolean, modifier: Modifier) {
    val c = TtTheme.colors
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    val t = step.times
    val late = t.arriveMs - t.scheduledArriveMs >= 60_000
    val time = formatClock(t.arriveMs, tz, fmt, withSuffix = false)
    val color = lineColor(step.leg)
    TimelineRow(
        time = { PixelText(time, style = TtTheme.type.row15Strong, color = if (late) c.caution else c.text, maxLines = 1) },
        rail = { Rail(Seg.Ride(color), if (bottom) Seg.Walk else Seg.None, color, null) },
        minHeight = 36,
        sentence = "$time, get off at ${step.leg.to.name}" + (t.arrivePlatform?.let { ", ${platformWords(it).lowercase()}" } ?: ""),
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelText(step.leg.to.name, Modifier.weight(1f, fill = false), TtTheme.type.row15Strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            t.arrivePlatform?.let { Label(platformWords(it), size = LabelSize.L10, color = c.textSecondary) }
        }
    }
}

@Composable
private fun ChangeRow(cn: Connection, modifier: Modifier) {
    val c = TtTheme.colors
    val walk = (cn.walkSeconds + 59) / 60
    val head = buildString {
        append("CHANGE · ")
        if (walk > 0) append("WALK $walk MIN") else append("SAME STOP")
        cn.toPlatform?.let { append(" TO ${platformWords(it)}") }
    }
    val m = cn.margin
    val color = c.of(marginRole(m))
    val words = marginWords(cn)
    TimelineRow(
        time = {},
        rail = { Rail(Seg.Walk, Seg.Walk, null, null) },
        minHeight = 42,
        sentence = head.lowercase().replaceFirstChar { it.uppercase() } + ", " + words.lowercase(),
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            WalkerSprite(if (m == Margin.OK) WalkerPose.STAND else WalkerPose.RUN)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                PixelText(head, style = TtTheme.type.label11Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusLamp(marginLamp(m), color, size = 10.dp)
                    Label(words, size = LabelSize.L10, bold = true, color = color)
                }
            }
        }
    }
}
