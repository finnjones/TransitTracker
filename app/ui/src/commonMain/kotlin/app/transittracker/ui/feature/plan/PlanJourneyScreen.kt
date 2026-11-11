package app.transittracker.ui.feature.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.transittracker.model.Place
import app.transittracker.model.SavedTrip
import app.transittracker.ui.app.LocalAppDeps
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.DashedDivider
import app.transittracker.ui.components.FieldInput
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PageHeader
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.PixelTextField
import app.transittracker.ui.components.SectionLabel
import app.transittracker.ui.components.SolidDivider
import app.transittracker.ui.components.StepperRow
import app.transittracker.ui.components.ToggleChip
import app.transittracker.ui.components.pixelFill
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.format.formatClock
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.OnBack
import app.transittracker.ui.nav.Route
import app.transittracker.ui.places.SavedPlace
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.TtTheme

/** What the form can ask for; the screen maps these to navigation. */
class PlanFormActions(
    val back: () -> Unit = {},
    val find: () -> Unit = {},
    val allPlaces: () -> Unit = {},
    val openRoute: (SavedTrip) -> Unit = {},
)

/** Plan a journey: from, to, when (plan 5.6 step 1, NewTrip mockup). */
@Composable
fun PlanJourneyScreen(route: Route.PlanJourney) {
    val deps = LocalAppDeps.current
    val holder = rememberStateHolder(route) { scope ->
        PlanJourneyStateHolder(
            deps.planner, deps.core, deps.places, deps.platform, deps.clock, RecentSearches.of(deps),
            route.fromPlaceId, route.toPlaceId, scope,
        )
    }
    PlanJourneyPage(holder, route.fromOnboarding)
}

/** The page for [holder] (screenshots build their own holder). */
@Composable
fun PlanJourneyPage(holder: PlanJourneyStateHolder, fromOnboarding: Boolean = false) {
    val nav = LocalNavigator.current
    val state by holder.state.collectAsState()
    val actions = remember(holder, nav) {
        PlanFormActions(
            back = { nav.back() },
            find = { holder.find(fromOnboarding)?.let { nav.push(it) } },
            allPlaces = { nav.push(Route.SavedPlaces) },
            openRoute = { nav.push(Route.TripTimetable(it.id)) },
        )
    }
    // Back closes the search view before it leaves the page.
    OnBack(enabled = state.editing != null) { holder.closeSearch() }
    PlanJourneyContent(state, holder, actions)
}

@Composable
fun PlanJourneyContent(state: PlanFormUiState, holder: PlanJourneyStateHolder, actions: PlanFormActions) {
    Column(Modifier.fillMaxSize().background(TtTheme.colors.screen)) {
        val editing = state.editing
        if (editing != null) {
            SearchPage(state, editing, holder)
            return@Column
        }
        PageHeader("Plan a journey", onBack = actions.back)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val c = TtTheme.colors
            if (state.offline) {
                NoticePanel(
                    "Planning needs a connection. Your saved routes and recent journeys still work.",
                    Lamp.RING, c.info, Modifier.flapRow(1), title = "Planning is offline",
                )
            } else if (state.plannerUnavailable) {
                NoticePanel(
                    "Journey planning is not available right now. Your saved routes still work.",
                    Lamp.RING, c.info, Modifier.flapRow(1), title = "Planner unavailable",
                )
            }
            state.notice?.let { NoticePanel(it, Lamp.TRIANGLE, c.caution, Modifier.flapRow(1), action = "OK", onAction = holder::dismissNotice) }
            EndsPanel(state, holder, Modifier.flapRow(2))
            WhenSection(state, holder, Modifier.flapRow(3))
            QuickPicks(state, holder, actions, Modifier.flapRow(4))
            if (state.recent.isNotEmpty()) RecentSection(state, holder, Modifier.flapRow(5))
            if (state.offline && state.savedRoutes.isNotEmpty()) SavedRoutesSection(state.savedRoutes, actions, Modifier.flapRow(6))
        }
        Column(Modifier.fillMaxWidth().background(TtTheme.colors.screen)) {
            SolidDivider()
            PixelButton(
                actions.find, Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 24.dp),
                ButtonKind.PRIMARY, enabled = state.canFind, faceHeight = 52.dp,
            ) { color -> PixelText("FIND JOURNEYS", style = TtTheme.type.label13Bold, color = color, maxLines = 1) }
        }
    }
}

// ---------------------------------------------------------------- from / to

@Composable
private fun EndsPanel(state: PlanFormUiState, holder: PlanJourneyStateHolder, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().pixelFill(c.text, dots = 0).padding(horizontal = 14.dp)) {
            EndRow("FROM", state.from, "Choose a start") { holder.edit(Field.FROM) }
            DashedDivider(color = c.lipPrimary)
            EndRow("TO", state.to, "Choose where to") { holder.edit(Field.TO) }
        }
        val source = remember { MutableInteractionSource() }
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(44.dp)
                .pixelFill(c.screen, dots = 0)
                .clickable(source, null, enabled = state.from != null || state.to != null, role = Role.Button, onClick = holder::swap)
                .semantics { contentDescription = "Swap from and to" },
            contentAlignment = Alignment.Center,
        ) { PixelIcon(PixelIcons.Swap, tint = c.text, size = 21.dp) }
    }
}

@Composable
private fun EndRow(label: String, choice: EndChoice?, empty: String, onClick: () -> Unit) {
    val c = TtTheme.colors
    val sentence = if (choice == null) "${label.lowercase().replaceFirstChar { it.uppercase() }}: not chosen. Tap to choose"
    else "${label.lowercase().replaceFirstChar { it.uppercase() }}: ${choice.end.name}" + (if (choice.detail.isNotEmpty()) ", ${choice.detail}" else "") + ". Tap to change"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).tapRow(sentence, onClick).padding(end = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelText(label, Modifier.width(40.dp), TtTheme.type.label10Bold, c.screen, maxLines = 1)
        if (choice != null) {
            PixelIcon(endIcon(choice.icon), tint = c.screen, size = 24.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PixelText(choice.end.name, style = TtTheme.type.title20, color = c.screen, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (choice.detail.isNotEmpty()) PixelText(choice.detail, style = TtTheme.type.row15, color = c.divider, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            PixelIcon(PixelIcons.MapPin, tint = c.lipPrimary, size = 24.dp)
            PixelText(empty, style = TtTheme.type.row15Strong, color = c.lipPrimary, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- when

@Composable
private fun WhenSection(state: PlanFormUiState, holder: PlanJourneyStateHolder, modifier: Modifier = Modifier) {
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("WHEN")
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (k in WhenKind.entries) {
                ToggleChip(k.label, state.whenKind == k, { if (it) holder.setWhen(k) }, Modifier.weight(1f))
            }
        }
        if (state.whenKind != WhenKind.NOW) {
            val now = state.nowMs
            StepperRow(
                "TIME", formatClock(state.pickedMs, tz, fmt), { holder.stepTime(-TIME_STEP_MINUTES) }, { holder.stepTime(TIME_STEP_MINUTES) },
                "Five minutes earlier", "Five minutes later", valueStyle = TtTheme.type.digits30, labelWidth = 56.dp,
                canDecrement = canStepBack(state.pickedMs, TIME_STEP_MINUTES, now),
                canIncrement = canStepForward(state.pickedMs, TIME_STEP_MINUTES, now),
            )
            StepperRow(
                "DAY", dayWords(state.pickedMs, now, tz), { holder.stepTime(-24 * 60) }, { holder.stepTime(24 * 60) },
                "A day earlier", "A day later", labelWidth = 56.dp,
                canDecrement = canStepBack(state.pickedMs, 24 * 60, now),
                canIncrement = canStepForward(state.pickedMs, 24 * 60, now),
            )
        }
    }
}

// ---------------------------------------------------------------- quick picks

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickPicks(state: PlanFormUiState, holder: PlanJourneyStateHolder, actions: PlanFormActions?, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("QUICK PICKS · " + if (state.target == Field.FROM) "FROM" else "TO")
            if (actions != null) {
                val source = remember { MutableInteractionSource() }
                PixelText(
                    "ALL PLACES",
                    Modifier.clickable(source, null, role = Role.Button, onClick = actions.allPlaces).padding(vertical = 6.dp),
                    TtTheme.type.label10Bold, c.text, maxLines = 1,
                )
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (p in state.places) PickButton(endIcon(choiceOf(p).icon), p.name.uppercase(), "Use ${p.name}") { holder.choosePlace(p) }
            PickButton(PixelIcons.Locate, if (state.locating) "FINDING..." else "WHERE I AM", "Use where I am now", enabled = !state.locating) {
                holder.useLocation()
            }
        }
    }
}

@Composable
private fun PickButton(icon: app.transittracker.ui.components.PixelIconData, text: String, spoken: String, enabled: Boolean = true, onClick: () -> Unit) {
    PixelButton(onClick, kind = ButtonKind.FLAT, enabled = enabled, faceHeight = 44.dp, contentDescription = spoken) { color ->
        Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelIcon(icon, tint = color, size = 18.dp)
            PixelText(text, style = TtTheme.type.label11Bold, color = color, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- recent and saved routes

@Composable
private fun RecentSection(state: PlanFormUiState, holder: PlanJourneyStateHolder, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("RECENT", Modifier.padding(bottom = 4.dp))
        for (r in state.recent) {
            PairRow(r.from.name, r.to.name, "Plan ${r.from.name} to ${r.to.name} again") { holder.useRecent(r) }
        }
    }
}

@Composable
private fun SavedRoutesSection(trips: List<SavedTrip>, actions: PlanFormActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("YOUR SAVED ROUTES", Modifier.padding(bottom = 4.dp))
        for (t in trips) {
            PairRow(t.originName, t.destinationName, "Timetable for ${t.originName} to ${t.destinationName}") { actions.openRoute(t) }
        }
    }
}

@Composable
private fun PairRow(from: String, to: String, sentence: String, onClick: () -> Unit) {
    val c = TtTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pixelFill(c.panel, dots = 0).tapRow(sentence, onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelText(from, style = TtTheme.type.row15Strong, maxLines = 1)
        PixelIcon(PixelIcons.ChevronRight, tint = c.textSecondary)
        PixelText(to, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- search

@Composable
private fun SearchPage(state: PlanFormUiState, field: Field, holder: PlanJourneyStateHolder) {
    val c = TtTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(field) { runCatching { focus.requestFocus() } }
    PageHeader(if (field == Field.FROM) "From" else "To", onBack = holder::closeSearch)
    Column(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PixelTextField(
            state.query, holder::setQuery,
            label = if (field == Field.FROM) "Search for where to start" else "Search for where to go",
            modifier = Modifier.fillMaxWidth().flapRow(1),
            placeholder = "Address, station or stop",
            input = FieldInput.SEARCH,
            focusRequester = focus,
            leading = { PixelIcon(PixelIcons.MapPin, tint = c.textSecondary, size = 18.dp) },
        )
        QuickPicks(state, holder, null, Modifier.flapRow(2))
        state.notice?.let { NoticePanel(it, Lamp.TRIANGLE, c.caution, action = "OK", onAction = holder::dismissNotice) }
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        fun sentence(text: String) = @Composable { PixelText(text, Modifier.padding(2.dp), TtTheme.type.reading15, c.textReading) }
        when (val s = state.search) {
            SearchState.Idle -> if (state.places.isNotEmpty()) {
                SectionLabel("SAVED PLACES", Modifier.padding(bottom = 4.dp))
                for (p in state.places) PlaceRow(choiceOf(p)) { holder.choosePlace(p) }
            }
            SearchState.Searching -> Label("SEARCHING...", color = c.textSecondary)
            is SearchState.Found -> {
                SectionLabel("RESULTS", Modifier.padding(bottom = 4.dp))
                for (p in s.places) ResultRow(p) { holder.chooseResult(p) }
            }
            SearchState.NoResults -> sentence("Nothing matches “${state.query.trim()}”. Try a street, a suburb or a station name.")()
            SearchState.Offline -> NoticePanel(
                "Search needs a connection. Pick a saved place or use WHERE I AM.", Lamp.RING, c.info, title = "Search is offline",
            )
            SearchState.Unavailable -> NoticePanel(
                "Search is not available right now. Pick a saved place or use WHERE I AM.", Lamp.RING, c.info, title = "Planner unavailable",
            )
            is SearchState.Failed -> NoticePanel(
                "The search did not work (${s.message}). Try again in a moment.", Lamp.CROSS, c.stop, title = "Search failed",
                action = "RETRY", onAction = holder::retrySearch,
            )
        }
    }
}

@Composable
private fun ResultRow(p: Place, onClick: () -> Unit) = PlaceRow(choiceOf(p), onClick)

@Composable
private fun PlaceRow(choice: EndChoice, onClick: () -> Unit) {
    val c = TtTheme.colors
    val sentence = choice.end.name + if (choice.detail.isNotEmpty()) ", ${choice.detail}" else ""
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).pixelFill(c.panel, dots = 0).tapRow(sentence, onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelIcon(endIcon(choice.icon), tint = c.textSecondary, size = 18.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PixelText(choice.end.name, style = TtTheme.type.row15Strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (choice.detail.isNotEmpty()) Label(choice.detail.uppercase(), size = LabelSize.L10, color = c.textSecondary)
        }
    }
}

/** For screenshots: a saved place by id. */
internal fun List<SavedPlace>.byId(id: String) = first { it.id == id }
