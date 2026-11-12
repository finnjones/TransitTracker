package app.transittracker.ui.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import app.transittracker.ui.format.countdownMmSs
import app.transittracker.ui.format.formatClock
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.TtTheme

/** What the journey map can ask for; the screen maps these to navigation. */
class JourneyMapActions(
    val back: () -> Unit = {},
    /** Live tracking of ride [index] (one service, its full stop list). */
    val track: (index: Int) -> Unit = {},
    /** Every service of the saved trip today: the other trains leaving. */
    val timetable: () -> Unit = {},
)

/**
 * One journey of a saved trip on one map (Follow my journey): the train from where you board to
 * where you change, the walk to the bus, the bus to the end, each vehicle where it is, and you.
 * The footer counts down to the next thing that happens and lists the rides and the changes.
 */
@Composable
fun JourneyMapScreen(route: Route.JourneyMap) {
    val deps = LocalAppDeps.current
    val nav = LocalNavigator.current
    val time = TtTheme.time
    val geometry = remember(deps.core) { CoreMapGeometry(deps.core) }
    val holder = rememberStateHolder(route) { scope ->
        JourneyMapStateHolder(
            deps.core, geometry, deps.clock, route.tripId, route.legs, scope,
            follow = deps.follower.state, zone = time.timeZone, clockFormat = time.clockFormat,
        )
    }
    val state by holder.state.collectAsState()
    val actions = remember(nav, route) {
        JourneyMapActions(
            back = { nav.back() },
            track = { k ->
                route.legs.getOrNull(k)?.let { leg -> nav.push(Route.Tracking(leg.ref, leg.boardStopId, leg.alightStopId)) }
            },
            timetable = { nav.push(Route.TripTimetable(route.tripId)) },
        )
    }
    JourneyMapContent(state, deps.clock, actions)
}

/** The journey map for [state]; stateless apart from the map's camera. */
@Composable
fun JourneyMapContent(state: JourneyMapUiState, clock: WallClock, actions: JourneyMapActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    Column(modifier.fillMaxSize().background(c.screen)) {
        JourneyMapHeader(state, actions, Modifier.flapRow(0))
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
            Box(Modifier.fillMaxWidth().height(dot.dp).background(c.divider))
            state.notice?.let {
                MapNotice(it, state.noticeRole, Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 16.dp, end = 16.dp + 48.dp + 12.dp))
            }
        }
        JourneyMapFooter(state, actions)
    }
}

@Composable
private fun JourneyMapHeader(state: JourneyMapUiState, actions: JourneyMapActions, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelIconButton(PixelIcons.ChevronLeft, "Back", actions.back)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PixelText(state.title, style = TtTheme.type.title20, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((k, r) in state.rides.withIndex()) {
                    if (k > 0) PixelText("→", style = TtTheme.type.row15, color = TtTheme.colors.textSecondary)
                    if (r.routeName.isNotEmpty()) RouteBadge(r.routeName, r.routeColor, r.routeTextColor)
                }
            }
        }
        state.chip?.let { MapChip(it) }
    }
}

@Composable
private fun JourneyMapFooter(state: JourneyMapUiState, actions: JourneyMapActions) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.panel)
            .drawBehind { drawRect(c.divider, size = androidx.compose.ui.geometry.Size(size.width, dot.px(1))) }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp + dot.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The next thing that happens.
        val cd = state.countdown
        Column(Modifier.flapRow(1), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                state.bigWords != null -> {
                    Label(if (state.following) "ON YOUR WAY" else "YOUR JOURNEY", color = if (state.following) c.go else c.textSecondary, size = LabelSize.L10)
                    PixelText(state.bigWords, style = TtTheme.type.title22, maxLines = 1)
                }
                cd != null -> Column(
                    Modifier.clearAndSetSemantics { contentDescription = "${cd.spokenVerb} ${spokenMmSs(cd.targetMs - state.nowMs, cd.prefix.isNotEmpty())}" },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.following) Label("ON YOUR WAY ·", color = c.go, bold = true, size = LabelSize.L10)
                        Label(cd.label, color = c.textSecondary, size = LabelSize.L10)
                    }
                    Digits(cd.prefix + countdownMmSs(cd.targetMs, state.nowMs), style = TtTheme.type.digits44)
                }
            }
        }
        DashedDivider(Modifier.flapRow(2))
        // The rides, and the change before each later one.
        Column(Modifier.flapRow(2), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((k, r) in state.rides.withIndex()) {
                if (k > 0) state.changes.getOrNull(k - 1)?.let { ChangeRow(it) }
                RideRow(r, onClick = { actions.track(k) })
            }
        }
        Row(Modifier.fillMaxWidth().flapRow(3).padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PixelButton("TRACK", { actions.track(state.currentLeg) }, Modifier.weight(1f), kind = ButtonKind.PRIMARY, enabled = state.rides.isNotEmpty())
            PixelButton("OTHER SERVICES", actions.timetable, Modifier.weight(2f))
        }
    }
}

@Composable
private fun RideRow(r: JourneyRide, onClick: () -> Unit) {
    val c = TtTheme.colors
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    val dim = r.done && !r.current
    val spoken = "${r.routeName} ${vehicleWord(r.mode).lowercase()} to ${r.headsign}, from ${r.fromName}" +
        (r.departMs?.let { " at ${formatClock(it, tz, fmt)}" } ?: "") + " to ${r.toName}" +
        (r.arriveMs?.let { ", arrives ${formatClock(it, tz, fmt)}" } ?: "") + ", ${r.status.spoken}" +
        if (r.current) ", your ride now" else ""
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick("Track this ride") { onClick(); true }
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(36.dp).background(if (r.current) c.go else c.screen))
        RouteBadge(r.routeName.ifEmpty { "?" }, r.routeColor, r.routeTextColor)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PixelText(
                "${r.departMs?.let { formatClock(it, tz, fmt) } ?: "--:--"} ${r.fromName} → ${r.arriveMs?.let { formatClock(it, tz, fmt) } ?: "--:--"} ${r.toName}",
                style = TtTheme.type.row15Strong, color = if (dim) c.textSecondary else c.text, strike = r.cancelled,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            StatusLine(r.status, size = StatusSize.SMALL)
        }
        r.platform?.let { if (!r.done) PlatformSign(it, r.platformChanged) }
    }
}

@Composable
private fun ChangeRow(ch: JourneyChange) {
    val c = TtTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("┊", color = c.textSecondary, size = LabelSize.L10)
        Label(
            (if (ch.tight) "TIGHT CHANGE · " else "CHANGE · ") + "${ch.minutes.coerceAtLeast(0)} MIN" +
                (ch.atName.takeIf { it.isNotEmpty() }?.let { " · WALK TO ${it.uppercase()}" } ?: ""),
            color = if (ch.tight) c.caution else c.textSecondary, bold = ch.tight, size = LabelSize.L10,
        )
    }
}
