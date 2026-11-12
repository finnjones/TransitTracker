package app.transittracker.ui.feature.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.transittracker.model.Mode
import app.transittracker.model.PlanRequest
import app.transittracker.model.SavedTrip
import app.transittracker.model.rides
import app.transittracker.ui.app.LocalAppDeps
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.Countdown
import app.transittracker.ui.components.CountdownSize
import app.transittracker.ui.components.DialogBox
import app.transittracker.ui.components.DialogScrim
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.SectionLabel
import app.transittracker.ui.components.StatusLamp
import app.transittracker.ui.components.SwitchRow
import app.transittracker.ui.components.ToggleChip
import app.transittracker.ui.components.pixelFill
import app.transittracker.ui.format.CountdownText
import app.transittracker.ui.format.DefaultLineColor
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.format.countdown
import app.transittracker.ui.format.formatClock
import app.transittracker.ui.format.parseLineColor
import app.transittracker.ui.motion.PageTransition
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.nav.Tab
import app.transittracker.ui.sprites.WalkerPose
import app.transittracker.ui.sprites.WalkerSprite
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.strip.Leg
import app.transittracker.ui.strip.LegStrip
import app.transittracker.ui.theme.TtTheme

/** What the options page can ask for; the screen maps these to navigation. */
class OptionsActions(
    val back: () -> Unit = {},
    val open: (OptionSummary) -> Unit = {},
    val openRoute: (SavedTrip) -> Unit = {},
)

/** SAVE THESE WAYS on the options page; the defaults do nothing (screenshots, previews). */
class SaveWaysActions(
    val start: () -> Unit = {},
    val toggle: (String) -> Unit = {},
    val cancel: () -> Unit = {},
    val confirm: (withReturn: Boolean, join: Boolean) -> Unit = { _, _ -> },
)

/** Journey options for one search (plan 5.6 step 2, Options mockup). */
@Composable
fun JourneyOptionsScreen(route: Route.JourneyOptions) {
    val deps = LocalAppDeps.current
    val holder = rememberStateHolder(route) { scope ->
        JourneyOptionsStateHolder(deps.planner, deps.core, deps.clock, planRequestOf(route), scope, from = route.from, to = route.to, places = deps.places.takeUnless { route.browseOnly })
    }
    JourneyOptionsPage(route, holder)
}

fun planRequestOf(route: Route.JourneyOptions) =
    PlanRequest(route.from.toPlanEndpoint(), route.to.toPlanEndpoint(), route.timeMs, route.timeKind)

@Composable
fun JourneyOptionsPage(route: Route.JourneyOptions, holder: JourneyOptionsStateHolder) {
    val nav = LocalNavigator.current
    val state by holder.state.collectAsState()
    val actions = remember(route, nav) {
        OptionsActions(
            back = { nav.back() },
            open = { o ->
                val first = o.itinerary.rides.firstOrNull()
                nav.push(
                    Route.JourneyDetail(route.from, route.to, o.itinerary, route.fromOnboarding, route.browseOnly),
                    first?.let { PageTransition.vehiclePass(it.mode ?: Mode.OTHER, it.routeColor) },
                )
            },
            openRoute = { nav.push(Route.TripTimetable(it.id)) },
        )
    }
    LaunchedEffect(state.saveWays) {
        if (state.saveWays is SaveWaysState.Saved) {
            if (route.fromOnboarding) {
                nav.resetTo(Tab.TRIPS, PageTransition.Doors)
            } else {
                nav.popToRoot()
                if (nav.currentTab != Tab.TRIPS) nav.switchTab(Tab.TRIPS)
            }
        }
    }
    val ways = remember(holder) { SaveWaysActions(holder::startSaveWays, holder::toggleWay, holder::cancelSaveWays, holder::confirmSaveWays) }
    JourneyOptionsContent(route, state, holder::sort, holder::retry, actions, ways)
}

@Composable
fun JourneyOptionsContent(
    route: Route.JourneyOptions,
    state: OptionsUiState,
    onSort: (OptionSort) -> Unit,
    onRetry: () -> Unit,
    actions: OptionsActions,
    ways: SaveWaysActions = SaveWaysActions(),
) {
    val c = TtTheme.colors
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    Box(Modifier.fillMaxSize().background(c.screen)) {
        Column(Modifier.fillMaxSize()) {
            JourneyHeader(route.from.name, route.to.name, whenWords(route.timeMs, route.timeKind, state.nowMs, tz, fmt), actions.back)
            if (state.phase == OptionsPhase.FOUND || state.phase == OptionsPhase.LOADING) {
                Row(
                    Modifier.fillMaxWidth().flapRow(1).padding(start = 18.dp, end = 18.dp, top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (s in OptionSort.entries) ToggleChip(s.label, state.sort == s, { if (it) onSort(s) })
                }
            }
            when (state.phase) {
                OptionsPhase.LOADING -> Loading()
                OptionsPhase.FOUND -> OptionsList(state, actions, ways.start)
                OptionsPhase.NOTHING_FOUND -> Message {
                    NoticePanel(
                        "The planner found no way from ${route.from.name} to ${route.to.name} at this time. " +
                            "Try another time, or an end nearer a station or stop.",
                        Lamp.RING, c.info, title = "No journeys found",
                    )
                    NearStops(route.from.name, state.nearFrom)
                    NearStops(route.to.name, state.nearTo)
                    PixelButton("CHANGE THE SEARCH", actions.back, Modifier.fillMaxWidth(), ButtonKind.PRIMARY)
                }
                OptionsPhase.OFFLINE -> Message {
                    NoticePanel(
                        "Planning needs a connection. Your saved routes work offline.",
                        Lamp.RING, c.info, title = "Planning is offline", action = "TRY AGAIN", onAction = onRetry,
                    )
                    if (state.savedRoutes.isNotEmpty()) {
                        SectionLabel("YOUR SAVED ROUTES")
                        for (t in state.savedRoutes) RouteRow(t) { actions.openRoute(t) }
                    }
                }
                OptionsPhase.UNAVAILABLE -> Message {
                    NoticePanel(
                        "Journey planning is not available right now. Try again later; your saved routes still work.",
                        Lamp.RING, c.info, title = "Planner unavailable", action = "TRY AGAIN", onAction = onRetry,
                    )
                }
                OptionsPhase.FAILED -> Unit
            }
        }
        (state.saveWays as? SaveWaysState.Choosing)?.let { SaveWaysDialog(it, route, ways) }
        if (state.phase == OptionsPhase.FAILED) {
            DialogScrim(onDismiss = null) {
                DialogBox(
                    "Could not plan",
                    body = "Something went wrong while finding journeys" + (state.failMessage?.let { ": ${it.trimEnd('.')}." } ?: ".") +
                        " Try again in a moment.",
                    actions = {
                        PixelButton("CLOSE", actions.back, Modifier.weight(1f))
                        PixelButton("RETRY", onRetry, Modifier.weight(1f), ButtonKind.PRIMARY)
                    },
                )
            }
        }
    }
}

@Composable
private fun Message(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().flapRow(1).padding(start = 16.dp, end = 16.dp, top = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

@Composable
private fun NearStops(name: String, stops: List<String>) {
    if (stops.isEmpty()) {
        PixelText("No station or stop the phone knows is within walking distance of $name.", style = TtTheme.type.reading15, color = TtTheme.colors.textReading)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("NEAR ${name.uppercase()}")
        PixelText(stops.joinToString(" · "), style = TtTheme.type.row15)
    }
}

@Composable
private fun RouteRow(t: SavedTrip, onClick: () -> Unit) {
    val c = TtTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pixelFill(c.panel, dots = 0)
            .tapRow("Timetable for ${t.originName} to ${t.destinationName}", onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelText(t.originName, style = TtTheme.type.row15Strong, maxLines = 1)
        PixelIcon(PixelIcons.ChevronRight, tint = c.textSecondary)
        PixelText(t.destinationName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- loading

@Composable
private fun Loading() {
    val c = TtTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp)
            .clearAndSetSemantics { contentDescription = "Finding journeys" },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            WalkerSprite(WalkerPose.WALK)
            Label("FINDING JOURNEYS...", color = c.textSecondary, bold = true)
        }
        for (h in listOf(196, 112, 112)) {
            PixelPanel(Modifier.fillMaxWidth().height(h.dp), background = c.screen) {
                Box(Modifier.fillMaxWidth(0.4f).height(12.dp).pixelFill(c.raised, dots = 0))
                Box(Modifier.fillMaxWidth(0.7f).height(12.dp).pixelFill(c.raised, dots = 0))
            }
        }
    }
}

// ---------------------------------------------------------------- the list

@Composable
private fun OptionsList(state: OptionsUiState, actions: OptionsActions, onSaveWays: () -> Unit) {
    val c = TtTheme.colors
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        for ((i, o) in state.options.withIndex()) {
            item(key = o.itinerary.id) {
                val m = Modifier.fillMaxWidth().flapRow(2 + i)
                if (i == 0) RecommendedCard(o, state.sort, state.nowMs, actions, m) else OptionCard(o, state.nowMs, actions, m)
            }
        }
        val note = state.liveNote
        if (note != null) {
            item(key = "live-note") {
                Row(
                    Modifier.padding(2.dp).flapRow(2 + state.options.size),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusLamp(Lamp.RING, c.info, size = 10.dp)
                    PixelText(note, Modifier.weight(1f), TtTheme.type.label10, c.textSecondary)
                }
            }
        }
        if (state.canSaveWays && state.options.isNotEmpty()) {
            item(key = "save-ways") {
                Column(Modifier.fillMaxWidth().flapRow(3 + state.options.size), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelText(
                        "Save these as the ways of one route. Trips then shows whichever gets you there first when you leave.",
                        style = TtTheme.type.reading15, color = c.textReading,
                    )
                    val s = state.saveWays
                    if (s is SaveWaysState.Failed) PixelText("Saving did not work (${s.message}). Try again.", style = TtTheme.type.reading15, color = c.stop)
                    val label = when (s) {
                        SaveWaysState.Checking -> "CHECKING..."
                        SaveWaysState.Saving -> "SAVING..."
                        is SaveWaysState.Saved -> "SAVED"
                        else -> "SAVE THESE WAYS AS ONE ROUTE"
                    }
                    PixelButton(label, onSaveWays, Modifier.fillMaxWidth(), ButtonKind.SECONDARY, enabled = s is SaveWaysState.Idle || s is SaveWaysState.Failed)
                }
            }
        }
    }
}

@Composable
private fun SaveWaysDialog(s: SaveWaysState.Choosing, route: Route.JourneyOptions, ways: SaveWaysActions) {
    val c = TtTheme.colors
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    var withReturn by rememberSaveable { mutableStateOf(false) }
    var join by rememberSaveable { mutableStateOf(true) }
    val joinTitle = s.joinTitle
    val anyNew = s.ways.any { it.trip != null && !it.alreadySaved }
    DialogScrim(onDismiss = ways.cancel) {
        DialogBox(
            "Save the ways",
            body = if (anyNew) {
                "Pick the ways from ${route.from.name} to ${route.to.name} to keep. Each is kept as a pattern, so Trips can show its next services at any time."
            } else {
                "Every way found is already saved" + (joinTitle?.let { " in $it" } ?: "") + ", or cannot be saved."
            },
            actions = {
                PixelButton(if (anyNew) "CANCEL" else "CLOSE", ways.cancel, Modifier.weight(1f))
                if (anyNew) {
                    PixelButton("SAVE", { ways.confirm(withReturn, join) }, Modifier.weight(1f), ButtonKind.PRIMARY, enabled = s.selected.isNotEmpty())
                }
            },
        ) {
            for (w in s.ways) {
                val time = formatClock(w.leaveMs, tz, fmt)
                when {
                    w.trip == null -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PixelText("${w.title} · $time", style = TtTheme.type.row15, color = c.textSecondary)
                        PixelText(w.blocked.orEmpty(), style = TtTheme.type.label10, color = c.textSecondary)
                    }
                    w.alreadySaved -> SwitchRow("${w.title} · already saved", true, {}, enabled = false)
                    else -> SwitchRow("${w.title} · $time", w.id in s.selected, { ways.toggle(w.id) })
                }
            }
            if (anyNew) {
                if (joinTitle != null) SwitchRow("Add to $joinTitle", join, { join = it })
                SwitchRow("Also save the return", withReturn, { withReturn = it })
            }
        }
    }
}

fun legsOf(parts: List<StripPart>): List<Leg> = parts.map { p ->
    when (p) {
        is StripPart.Walk -> Leg.Walk(p.minutes)
        is StripPart.Ride -> Leg.Ride(p.minutes, p.mode, p.line, parseLineColor(p.colorHex) ?: DefaultLineColor)
    }
}

private fun leaveCaption(cd: CountdownText) = when (cd.kind) {
    CountdownText.Kind.NOW -> "LEAVE"
    CountdownText.Kind.MINUTES -> "LEAVE IN"
    CountdownText.Kind.CLOCK -> "LEAVE AT"
}

private fun leaveWords(cd: CountdownText) = when (cd.kind) {
    CountdownText.Kind.NOW -> "LEAVE NOW"
    CountdownText.Kind.MINUTES -> "LEAVE IN ${cd.value} MIN"
    CountdownText.Kind.CLOCK -> "LEAVE AT ${cd.value}"
}

private fun optionSentence(o: OptionSummary, cd: CountdownText, arrive: String, tag: String?): String = buildString {
    if (tag != null) append("${tag.lowercase().replaceFirstChar { it.uppercase() }}: ")
    append(cd.spoken)
    append(", arrive $arrive, ${o.minutes} minutes, ${summarySpoken(o.changes, o.walkMinutes)}")
    o.live?.let { append(". ${it.spoken.replaceFirstChar { c -> c.uppercase() }}") }
}

@Composable
private fun RecommendedCard(o: OptionSummary, sort: OptionSort, nowMs: Long, actions: OptionsActions, modifier: Modifier) {
    val c = TtTheme.colors
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    val cd = countdown(o.leaveMs, nowMs, tz, fmt, verb = "leave")
    val arrive = formatClock(o.arriveMs, tz, fmt)
    PixelPanel(modifier.tapRow(optionSentence(o, cd, arrive, sort.tag)) { actions.open(o) }, selected = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            PixelText(sort.tag, Modifier.pixelFill(c.text, dots = 0).padding(horizontal = 6.dp, vertical = 3.dp), TtTheme.type.label10Bold, c.screen, maxLines = 1)
            Label(formatClock(o.leaveMs, tz, fmt) + " – " + arrive)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PixelText(leaveCaption(cd), style = TtTheme.type.label10, color = c.textSecondary)
                Countdown(cd, size = CountdownSize.M44)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PixelText("${o.minutes} min", style = TtTheme.type.title22, maxLines = 1)
                Label(summaryWords(o.changes, o.walkMinutes), size = LabelSize.L10, color = c.textSecondary)
            }
        }
        LegStrip(legsOf(o.parts), sprites = true)
        o.live?.let { LiveLine(it) }
    }
}

@Composable
private fun OptionCard(o: OptionSummary, nowMs: Long, actions: OptionsActions, modifier: Modifier) {
    val c = TtTheme.colors
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    val cd = countdown(o.leaveMs, nowMs, tz, fmt, verb = "leave")
    val arrive = formatClock(o.arriveMs, tz, fmt)
    PixelPanel(modifier.tapRow(optionSentence(o, cd, arrive, null)) { actions.open(o) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Label(leaveWords(cd), size = LabelSize.L12)
            Label(formatClock(o.leaveMs, tz, fmt) + " – " + arrive)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            PixelText("${o.minutes} min", style = TtTheme.type.name17.copy(fontWeight = TtTheme.type.title20.fontWeight), maxLines = 1)
            Label(summaryWords(o.changes, o.walkMinutes), size = LabelSize.L10, color = c.textSecondary)
        }
        LegStrip(legsOf(o.parts))
        o.live?.let { LiveLine(it) }
    }
}

@Composable
private fun LiveLine(live: LiveState) {
    val color = TtTheme.colors.of(live.role)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusLamp(live.lamp, color, size = 12.dp)
        PixelText(live.words, Modifier.weight(1f), TtTheme.type.label10Bold, color)
    }
}
