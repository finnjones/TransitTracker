package app.transittracker.ui.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.transittracker.model.RouteId
import app.transittracker.model.TransitCore
import app.transittracker.model.VehiclePosition
import app.transittracker.model.WallClock
import kotlinx.coroutines.flow.Flow
import app.transittracker.ui.app.LocalAppDeps
import app.transittracker.ui.components.BadgeStyle
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIconButton
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.RouteBadge
import app.transittracker.ui.components.StatusLine
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.nav.JourneyEnd
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.TtTheme

/** What the Map tab can ask for; the screen maps these to navigation and the holder. */
class MapActions(
    val selectVehicle: (String) -> Unit = {},
    val selectStation: (SceneStation) -> Unit = {},
    val clear: () -> Unit = {},
    val nearMe: () -> Unit = {},
    val openTracking: (VehicleCard) -> Unit = {},
    val openService: (VehicleCard) -> Unit = {},
    val planFrom: (StationCard) -> Unit = {},
    val planJourney: () -> Unit = {},
)

/** The Map tab (plan 5.5): the saved trips' lines with their live vehicles, and NEAR ME. */
@Composable
fun MapScreen() = MapTabPage()

/**
 * [MapScreen] with preview hooks for screenshots: open the first vehicle's card, press NEAR ME,
 * open a station's card by name.
 */
@Composable
internal fun MapTabPage(
    selectFirstVehicle: Boolean = false,
    pressNearMe: Boolean = false,
    selectStation: String? = null,
    vehicleFeed: ((TransitCore) -> (Set<RouteId>) -> Flow<List<VehiclePosition>>)? = null,
) {
    val deps = LocalAppDeps.current
    val nav = LocalNavigator.current
    val geometry = remember(deps.core) { CoreMapGeometry(deps.core) }
    val holder = rememberStateHolder { scope ->
        MapStateHolder(deps.core, geometry, deps.platform, deps.places, deps.clock, scope, vehicleFeed = vehicleFeed?.invoke(deps.core) ?: deps.core::vehicles)
    }
    val state by holder.state.collectAsState()
    val actions = remember(nav, holder) {
        MapActions(
            selectVehicle = holder::selectVehicle,
            selectStation = { s -> holder.selectStation(s.name, s.stopId ?: "", s.point) },
            clear = holder::clearSelection,
            nearMe = holder::nearMe,
            openTracking = { card -> card.ref?.let { nav.push(Route.Tracking(it)) } },
            openService = { card -> card.ref?.let { nav.push(Route.Service(it)) } },
            // PlanJourney takes saved place ids only: start from the saved place that uses this station.
            planFrom = { card ->
                // From the saved place that uses this station, else from the station itself.
                val station = card.position?.takeIf { card.placeId == null }?.let { JourneyEnd(card.name, it) }
                nav.push(Route.PlanJourney(fromPlaceId = card.placeId, from = station))
            },
            planJourney = { nav.push(Route.PlanJourney()) },
        )
    }
    if (pressNearMe) LaunchedEffect(Unit) { holder.nearMe() }
    if (selectFirstVehicle) {
        val first = state.vehicleWords.firstOrNull()?.first
        LaunchedEffect(first) { if (first != null && state.vehicleCard == null) holder.selectVehicle(first) }
    }
    if (selectStation != null) {
        val st = state.scene.stations.firstOrNull { it.name == selectStation }
        LaunchedEffect(st != null) { if (st != null) holder.selectStation(st.name, st.stopId ?: "", st.point) }
    }
    MapContent(state, deps.clock, actions)
}

/** The Map tab for [state]; stateless apart from the map's camera. */
@Composable
fun MapContent(state: MapUiState, clock: WallClock, actions: MapActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    Column(modifier.fillMaxSize().background(c.screen)) {
        Row(
            Modifier.fillMaxWidth().flapRow(0).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PixelText("Map", Modifier.semantics { heading() }, TtTheme.type.title22)
                val sub = when {
                    state.loading -> ""
                    state.noTrips -> "NO SAVED TRIPS"
                    else -> "${state.liveCount} LIVE ON YOUR LINES"
                }
                Label(sub, color = c.textSecondary, size = LabelSize.L10)
            }
            state.chip?.let { MapChip(it) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val cardShown = state.vehicleCard != null || state.stationCard != null
            if (!state.loading) {
                PixelMap(
                    state.scene, clock, state.description, Modifier.fillMaxSize(),
                    fitKey = state.fitKey,
                    topInsetDp = if (state.banner != null) 72.dp else 0.dp,
                    bottomInsetDp = if (cardShown) 150.dp else 72.dp,
                    onStationTap = actions.selectStation,
                    onVehicleTap = { actions.selectVehicle(it.id) },
                    onEmptyTap = actions.clear,
                    actions = state.vehicleWords.map { (id, words) -> "Show $words" to { actions.selectVehicle(id) } },
                )
            }
            Box(Modifier.fillMaxWidth().height(dot.dp).background(c.divider))
            state.banner?.let {
                MapNotice(it, state.bannerRole, Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 16.dp, end = 16.dp + 48.dp + 12.dp))
            }
            if (state.noTrips) {
                NoTrips(actions, Modifier.align(Alignment.Center).padding(horizontal = 32.dp))
            }
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp).flapRow(1),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    state.vehicleCard != null -> VehicleCardView(state.vehicleCard, actions)
                    state.stationCard != null -> StationCardView(state.stationCard, actions)
                }
                if (!state.noTrips && !state.loading) {
                    val label = when (state.nearMe) {
                        is NearMe.Shown -> "HIDE NEAR ME"
                        NearMe.Locating -> "FINDING YOU…"
                        else -> "NEAR ME"
                    }
                    PixelButton(actions.nearMe, kind = ButtonKind.SECONDARY, enabled = state.nearMe != NearMe.Locating) { color ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PixelIcon(PixelIcons.Locate, tint = color, size = 14.dp)
                            PixelText(label, style = TtTheme.type.label12Bold, color = color, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoTrips(actions: MapActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    PixelPanel(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalSpacing = 12.dp) {
        PixelIcon(PixelIcons.MapPin, tint = c.textSecondary, size = 24.dp)
        PixelText("No lines to show yet", style = TtTheme.type.name17)
        PixelText(
            "The map shows the lines of your saved trips and their trains as they run. Plan a journey and save it to see it here.",
            style = TtTheme.type.reading15, color = c.textReading,
        )
        PixelButton("PLAN A JOURNEY", actions.planJourney, Modifier.fillMaxWidth(), kind = ButtonKind.PRIMARY)
    }
}

@Composable
private fun VehicleCardView(card: VehicleCard, actions: MapActions) {
    val c = TtTheme.colors
    PixelPanel(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, contentPadding = PaddingValues(12.dp), verticalSpacing = 10.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            card.line?.let { RouteBadge(it.name, it.color, it.textColor) }
            PixelText(
                card.headsign?.let { "to $it" } ?: "Live vehicle", Modifier.weight(1f),
                style = TtTheme.type.name17, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            PixelIconButton(PixelIcons.LampCross, "Close", actions.clear)
        }
        if (card.status != null) StatusLine(card.status) else Label("LIVE POSITION", color = c.textSecondary)
        if (card.ref != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PixelButton("OPEN", { actions.openTracking(card) }, Modifier.weight(2f), kind = ButtonKind.PRIMARY)
                PixelButton("STOPS", { actions.openService(card) }, Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StationCardView(card: StationCard, actions: MapActions) {
    val c = TtTheme.colors
    PixelPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalSpacing = 10.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label("STATION", color = c.textSecondary, size = LabelSize.L10)
                PixelText(card.name, style = TtTheme.type.name17, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PixelIconButton(PixelIcons.LampCross, "Close", actions.clear)
        }
        when {
            card.lines == null -> Label("LOOKING UP LINES…", color = c.textSecondary, size = LabelSize.L10)
            card.lines.isEmpty() -> Label("NO LINES FOUND", color = c.textSecondary, size = LabelSize.L10)
            else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in card.lines) RouteBadge(r.shortName, r.color, r.textColor, style = BadgeStyle.NORMAL)
            }
        }
        PixelButton("Plan a journey from here", { actions.planFrom(card) }, Modifier.fillMaxWidth(), kind = ButtonKind.PRIMARY, style = TtTheme.type.row15Strong)
    }
}
