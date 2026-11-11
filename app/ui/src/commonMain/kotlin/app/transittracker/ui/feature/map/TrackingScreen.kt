package app.transittracker.ui.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.transittracker.model.TrackingState
import kotlinx.coroutines.flow.Flow
import app.transittracker.model.WallClock
import app.transittracker.ui.app.LocalAppDeps
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.DashedDivider
import app.transittracker.ui.components.Digits
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIconButton
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.PlatformSign
import app.transittracker.ui.components.RouteBadge
import app.transittracker.ui.components.StatusLine
import app.transittracker.ui.components.StatusSize
import app.transittracker.ui.format.carCountWords
import app.transittracker.ui.format.countdownMmSs
import app.transittracker.ui.format.occupancyPresentation
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.ColorRole
import app.transittracker.ui.theme.TtTheme
import app.transittracker.ui.components.drawDashes
import androidx.compose.ui.draw.drawBehind

/** What the tracking screen can ask for; the screen maps these to navigation. */
class TrackingActions(
    val back: () -> Unit = {},
    val notify: () -> Unit = {},
    val stopList: () -> Unit = {},
)

/**
 * Live tracking of one service (plan 5.5, Track mockup): the service's line on the pixel map
 * with the travelled part dimmed, your stops flagged, the vehicle from above creeping between
 * reports; the footer counts down to your stop and has NOTIFY ME and STOP LIST.
 */
@Composable
fun TrackingScreen(route: Route.Tracking) = TrackingPage(route)

/**
 * [TrackingScreen] with preview hooks: [geometrySource] replaces the core-backed geometry (the
 * schematic shot) and [relabel] changes the tracking stream (aged positions, timetable only).
 */
@Composable
internal fun TrackingPage(
    route: Route.Tracking,
    geometrySource: MapGeometrySource? = null,
    relabel: ((Flow<TrackingState>) -> Flow<TrackingState>)? = null,
) {
    val deps = LocalAppDeps.current
    val nav = LocalNavigator.current
    val time = TtTheme.time
    val geometry = remember(deps.core, geometrySource) { geometrySource ?: CoreMapGeometry(deps.core) }
    val holder = rememberStateHolder(route) { scope ->
        val track = deps.core.track(route.ref).let { relabel?.invoke(it) ?: it }
        TrackingStateHolder(
            deps.core, geometry, deps.clock, route.ref, route.boardStopId, route.alightStopId, scope,
            track = track, zone = time.timeZone, clockFormat = time.clockFormat,
        )
    }
    val state by holder.state.collectAsState()
    val actions = remember(nav, route) {
        TrackingActions(
            back = { nav.back() },
            notify = { nav.push(Route.WatchEditor(fromService = route.ref, boardStopId = route.boardStopId, alightStopId = route.alightStopId)) },
            stopList = {
                // Back to the service screen when it is the page below; otherwise open it.
                val below = nav.backStack.getOrNull(nav.backStack.size - 2)?.route
                if (below is Route.Service && below.ref == route.ref) nav.back()
                else nav.push(Route.Service(route.ref, route.boardStopId, route.alightStopId))
            },
        )
    }
    TrackingContent(state, deps.clock, actions)
}

/** The tracking screen for [state]; stateless apart from the map's camera. */
@Composable
fun TrackingContent(state: TrackingUiState, clock: WallClock, actions: TrackingActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    Column(modifier.fillMaxSize().background(c.screen)) {
        TrackingHeader(state, actions, Modifier.flapRow(0))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!state.loading) {
                PixelMap(
                    state.scene, clock, state.description,
                    Modifier.fillMaxSize(),
                    fitKey = state.fitKey,
                    topInsetDp = if (state.notice != null) 64.dp else 0.dp,
                )
            } else {
                Box(Modifier.fillMaxSize().background(c.screen))
            }
            // The 2 dp rule between the header and the map.
            Box(Modifier.fillMaxWidth().height(dot.dp).background(c.divider))
            state.notice?.let {
                MapNotice(it, state.noticeRole, Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 16.dp, end = 16.dp + 48.dp + 12.dp))
            }
        }
        TrackingFooter(state, actions)
    }
}

@Composable
private fun TrackingHeader(state: TrackingUiState, actions: TrackingActions, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelIconButton(PixelIcons.ChevronLeft, "Back", actions.back)
        Row(
            Modifier.weight(1f).semantics(mergeDescendants = true) { heading() },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.routeName.isNotEmpty()) RouteBadge(state.routeName, state.routeColor, state.routeTextColor)
            if (state.headsign.isNotEmpty()) {
                PixelText("to ${state.headsign}", style = TtTheme.type.title20, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        state.chip?.let { MapChip(it) }
    }
}

@Composable
private fun TrackingFooter(state: TrackingUiState, actions: TrackingActions) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.panel)
            .drawBehind { drawRect(c.divider, size = androidx.compose.ui.geometry.Size(size.width, dot.px(1))) }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp + dot.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().flapRow(1), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val cd = state.countdown
                when {
                    state.bigWords != null -> {
                        Label(if (state.phase == TrackPhase.CANCELLED) "THIS SERVICE IS" else "YOUR TRIP", color = c.textSecondary, size = LabelSize.L10)
                        PixelText(
                            state.bigWords, style = TtTheme.type.title22,
                            color = if (state.phase == TrackPhase.CANCELLED) c.stop else c.text, maxLines = 1,
                        )
                    }
                    cd != null -> {
                        val left = countdownMmSs(cd.targetMs, state.nowMs)
                        Column(
                            Modifier.clearAndSetSemantics { contentDescription = "${cd.spokenVerb} ${spokenMmSs(cd.targetMs - state.nowMs, cd.prefix.isNotEmpty())}" },
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Label(cd.label, color = c.textSecondary, size = LabelSize.L10)
                            Digits(cd.prefix + left, style = TtTheme.type.digits44)
                        }
                    }
                    else -> {
                        Label("NEXT STOP", color = c.textSecondary, size = LabelSize.L10)
                        PixelText("No stops ahead", style = TtTheme.type.title20, color = c.textSecondary)
                    }
                }
            }
            if (state.platform != null && state.bigWords == null) {
                PlatformSign(state.platform, state.platformChanged, Modifier.padding(start = 8.dp, bottom = 2.dp))
            }
        }
        DashedDivider(Modifier.flapRow(2))
        Row(Modifier.fillMaxWidth().flapRow(2), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FactColumn("NEXT STOP", Modifier.weight(1f)) {
                PixelText(state.nextStop ?: "—", style = TtTheme.type.row15Strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FactColumn("RUNNING", Modifier.weight(1f)) {
                StatusLine(state.status, size = StatusSize.SMALL, modifier = Modifier.padding(top = 3.dp))
            }
            val seats = occupancyPresentation(state.occupancy)
            FactColumn(state.cars?.let { carCountWords(it) } ?: "SEATS", Modifier.weight(1f)) {
                PixelText(
                    seats?.words?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "No data",
                    style = TtTheme.type.row15Strong,
                    color = if (seats == null) c.textSecondary else c.text,
                    maxLines = 1,
                )
            }
        }
        Row(Modifier.fillMaxWidth().flapRow(3).padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PixelButton("NOTIFY ME", actions.notify, Modifier.weight(2f), kind = ButtonKind.PRIMARY, enabled = state.ref != null)
            PixelButton("STOP LIST", actions.stopList, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FactColumn(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(label, color = TtTheme.colors.textSecondary, size = LabelSize.L10)
        content()
    }
}

/** "4 minutes 12 seconds" (with "about" for estimates). */
internal fun spokenMmSs(ms: Long, approx: Boolean): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val m = s / 60
    val r = s % 60
    val words = buildList {
        if (m > 0) add(if (m == 1L) "1 minute" else "$m minutes")
        if (r > 0 || m == 0L) add(if (r == 1L) "1 second" else "$r seconds")
    }.joinToString(" ")
    return (if (approx) "about " else "") + words
}
