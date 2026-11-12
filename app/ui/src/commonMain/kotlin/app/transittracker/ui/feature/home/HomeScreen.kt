package app.transittracker.ui.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.transittracker.model.Departure
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Journey
import app.transittracker.model.Mode
import app.transittracker.model.ServiceDetail
import app.transittracker.model.ServiceRef
import app.transittracker.model.WallClock
import app.transittracker.ui.app.LocalAppDeps
import app.transittracker.ui.components.BadgeStyle
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.Countdown
import app.transittracker.ui.components.CountdownSize
import app.transittracker.ui.components.DataChipBox
import app.transittracker.ui.components.DataChipKind
import app.transittracker.ui.components.DashedDivider
import app.transittracker.ui.components.Digits
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIconButton
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.PlatformSign
import app.transittracker.ui.components.RouteBadge
import app.transittracker.ui.components.SeatPips
import app.transittracker.ui.components.SectionLabel
import app.transittracker.ui.components.StatusLamp
import app.transittracker.ui.components.StatusLine
import app.transittracker.ui.components.StatusSize
import app.transittracker.ui.components.drawDashes
import app.transittracker.ui.components.pixelBorder
import app.transittracker.ui.components.pixelFill
import app.transittracker.ui.format.CountdownText
import app.transittracker.ui.format.DefaultLineColor
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.format.ageSpoken
import app.transittracker.ui.format.ageWords
import app.transittracker.ui.format.countdown
import app.transittracker.ui.format.formatClock
import app.transittracker.ui.format.formatDayDate
import app.transittracker.ui.format.parseLineColor
import app.transittracker.ui.format.shortStopName
import app.transittracker.ui.format.statusPresentation
import app.transittracker.ui.motion.PageTransition
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.MapLeg
import app.transittracker.ui.nav.Route
import app.transittracker.ui.feature.follow.FollowStage
import app.transittracker.ui.feature.map.vehicleWord
import app.transittracker.ui.sprites.VehicleSprite
import app.transittracker.ui.sprites.VehicleState
import app.transittracker.ui.sprites.WalkerPose
import app.transittracker.ui.sprites.WalkerSprite
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.strip.JourneyStrip
import app.transittracker.ui.theme.ColorRole
import app.transittracker.ui.theme.TtTheme
import kotlinx.coroutines.flow.Flow

/** What the home screen can ask for; the screen maps these to navigation. */
class HomeActions(
    val openService: (Departure) -> Unit = {},
    val openTimetable: (tripId: String, headline: Departure?) -> Unit = { _, _ -> },
    val planJourney: () -> Unit = {},
    val manageRoutes: () -> Unit = {},
    /** The journey the user is on, on one map: every ride, the walks between them, the vehicles. */
    val openJourneyMap: (tripId: String, journey: Journey) -> Unit = { _, _ -> },
    /** NOT ON THIS: stop following the journey whose first ride is [ServiceRef]. */
    val notOnThis: (ServiceRef) -> Unit = {},
)

/** The Trips tab (plan 5.2, Main mockup). */
@Composable
fun HomeScreen() {
    val deps = LocalAppDeps.current
    val nav = LocalNavigator.current
    val holder = rememberStateHolder { scope ->
        HomeStateHolder(deps.core, deps.places, deps.settings, deps.clock, scope, follower = deps.follower)
    }
    val state by holder.state.collectAsState()
    val actions = remember(nav, holder) {
        HomeActions(
            openService = { d ->
                nav.push(Route.Service(d.ref, d.originStopId, d.destinationStopId), PageTransition.vehiclePass(d.mode, d.routeColor))
            },
            openTimetable = { id, d ->
                nav.push(Route.TripTimetable(id), d?.let { PageTransition.vehiclePass(it.mode, it.routeColor) })
            },
            planJourney = { nav.push(Route.PlanJourney()) },
            manageRoutes = { nav.push(Route.ManageRoutes) },
            openJourneyMap = { id, j ->
                nav.push(Route.JourneyMap(id, j.legs.map { MapLeg(it.ref, it.originStopId, it.destinationStopId) }))
            },
            notOnThis = holder::notOnThis,
        )
    }
    HomeContent(state, deps.clock, holder::service, actions)
}

/** The home screen for [state]; stateless apart from the strips' subscriptions to [service]. */
@Composable
fun HomeContent(
    state: HomeUiState,
    clock: WallClock,
    service: (ServiceRef) -> Flow<ServiceDetail>,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        HomeHeader(state, actions, Modifier.flapRow(0))
        when {
            state.loading -> Unit
            state.isEmpty -> {
                for ((i, b) in state.banners.withIndex()) Banner(b, Modifier.padding(horizontal = 16.dp).padding(top = 12.dp).flapRow(1 + i))
                EmptyHome(actions, Modifier.weight(1f).flapRow(1 + state.banners.size))
            }
            else -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                var row = 1
                for (b in state.banners) {
                    val r = row++
                    item(key = "banner-${b.kind}") { Banner(b, Modifier.flapRow(r)) }
                }
                for (folder in state.folders) {
                    if (folder.name != null) {
                        val r = row++
                        item(key = "folder-${folder.name}") { SectionLabel(folder.name, Modifier.flapRow(r)) }
                    }
                    for (card in folder.cards) {
                        val r = row++
                        item(key = "trip-${card.trip.id}") {
                            val m = Modifier.fillMaxWidth().flapRow(r)
                            val otw = card.onTheWay
                            if (otw != null) OnTheWayCard(card, otw, state.nowMs, actions, m)
                            else if (card.full) FullTripCard(card, state.nowMs, clock, service, actions, m)
                            else CompactTripCard(card, state.nowMs, actions, m)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- header

@Composable
private fun HomeHeader(state: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val tz = TtTheme.time.timeZone
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.nowMs > 0) {
                Digits(formatClock(state.nowMs, tz, TtTheme.time.clockFormat))
                Label(formatDayDate(state.nowMs, tz), color = c.textSecondary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            state.chip?.let { DataChipView(it) }
            PixelIconButton(PixelIcons.Plus, "Plan a journey", actions.planJourney, kind = ButtonKind.PRIMARY, iconSize = 21.dp)
        }
    }
}

@Composable
private fun DataChipView(chip: DataChip) = when (chip) {
    is DataChip.Live -> DataChipBox(DataChipKind.LIVE, chip.ageMs)
    DataChip.Stale -> DataChipBox(DataChipKind.STALE)
    DataChip.Offline -> DataChipBox(DataChipKind.OFFLINE)
}

// ---------------------------------------------------------------- banners

@Composable
private fun Banner(banner: HomeBanner, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val (lamp, color) = when (banner.kind) {
        BannerKind.OFFLINE -> Lamp.RING to c.info
        BannerKind.STALE -> Lamp.RING to c.textSecondary
        BannerKind.MESSAGE -> Lamp.CIRCLE to c.text
        BannerKind.UPDATING -> Lamp.RING to c.info
        BannerKind.OUT_OF_DATE -> Lamp.TRIANGLE to c.caution
    }
    PixelPanel(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = banner.text }, contentPadding = PaddingValues(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusLamp(lamp, color)
            PixelText(banner.text, Modifier.weight(1f), TtTheme.type.reading15, c.textReading)
        }
        if (banner.kind == BannerKind.UPDATING) {
            // The train filling a track (plan 2.7): no progress figure, so it stands at the start.
            Box(Modifier.fillMaxWidth().height(36.dp)) {
                EmptyTrack(Modifier.fillMaxWidth().height(36.dp))
                VehicleSprite(Mode.TRAIN, DefaultLineColor, VehicleState.Moving)
            }
        }
    }
}

// ---------------------------------------------------------------- full card

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullTripCard(
    card: TripCard,
    nowMs: Long,
    clock: WallClock,
    service: (ServiceRef) -> Flow<ServiceDetail>,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val headline = card.headline
    val open = { actions.openTimetable(card.trip.id, headline?.departure) }
    PixelPanel(
        modifier.cardTaps(open, actions.manageRoutes, "Timetable for ${card.titleParts.joinToString(" to ")}"),
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = if (card.following.isEmpty()) 12.dp else 0.dp),
    ) {
        CardTitle(card, headline?.departure)
        if (card.changes.isNotEmpty() && headline != null) ChangesRow(headline, card.changes)
        if (headline == null) {
            NoServices(card)
        } else {
            val d = headline.departure
            Headline(d, headline, nowMs, card, actions)
            HeadlineStrip(d, clock, service, Modifier.serviceTaps({ actions.openService(d) }, actions.manageRoutes))
            for (connection in headline.legs.drop(1)) ConnectionLine(connection, card.trip.id, headline, actions)
        }
        for (j in card.following) {
            Column {
                DashedDivider()
                ServiceRow(j.departure, nowMs, actions)
            }
        }
    }
}

@Composable
private fun CardTitle(card: TripCard, first: Departure?) {
    val c = TtTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            TitleParts(card.titleParts, TtTheme.type.name17)
            if (card.stationsLine != null) Label(card.stationsLine, size = LabelSize.L10, color = c.textSecondary)
            if (card.waysLabel != null) Label(card.waysLabel, size = LabelSize.L10, color = c.info, bold = true)
        }
        if (first != null) {
            Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RouteBadge(first.routeName, first.routeColor, null)
                PixelText(first.headsign, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TitleParts(parts: List<String>, style: androidx.compose.ui.text.TextStyle) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((i, p) in parts.withIndex()) {
            if (i > 0) PixelIcon(PixelIcons.ChevronRight, tint = TtTheme.colors.textSecondary)
            PixelText(p, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "[T1] → [M1] · CHANGE AT CHATSWOOD · 6 MIN" (plan 5.2, multi-leg trips). */
@Composable
private fun ChangesRow(journey: Journey, changes: List<ChangeSummary>, compact: Boolean = false) {
    val c = TtTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((i, leg) in journey.legs.withIndex()) {
            if (i > 0) PixelText("→", style = TtTheme.type.row15, color = c.textSecondary)
            RouteBadge(leg.routeName, leg.routeColor, null, style = if (compact) BadgeStyle.COMPACT else BadgeStyle.NORMAL)
        }
        val first = changes.first()
        val words = buildString {
            append("CHANGE")
            if (first.stationName != null) append(" AT ${first.stationName.uppercase()}")
            append(" · ${first.waitMinutes} MIN")
        }
        Label(words, size = LabelSize.L10, color = c.textSecondary)
    }
}

@Composable
private fun Headline(d: Departure, journey: Journey, nowMs: Long, card: TripCard, actions: HomeActions) {
    val c = TtTheme.colors
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    val status = statusPresentation(d)
    val cd = countdown(d.expectedDepartureMs, nowMs, tz, fmt, status.countdownPrefix)
    val spoken = serviceSentence(d, cd, status.spoken) + (card.leaveBy?.let { ", " + leaveBySpoken(it, tz, fmt) } ?: "")
    Column(
        Modifier.fillMaxWidth().serviceTaps({ actions.openService(d) }, actions.manageRoutes, spoken),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Countdown(cd, size = CountdownSize.XL76)
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (status.strikeScheduledTime) {
                    PixelText(formatClock(d.scheduledDepartureMs, tz, fmt), color = c.textSecondary, strike = true)
                }
                PixelText(
                    formatClock(d.expectedDepartureMs, tz, fmt), style = TtTheme.type.title22,
                    color = if (status.strikeScheduledTime) c.caution else c.text,
                )
                Label("ARR " + formatClock(journey.arrival.expectedArrivalMs, tz, fmt), color = c.textSecondary)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StatusLine(status)
            val lb = card.leaveBy
            if (lb != null) LeaveByLabel(lb)
            else if (!card.linkedToPlaces) SeatPips(d.occupancy, showWords = false)
            if (d.originPlatform != null) PlatformSign(d.originPlatform!!, d.platformChanged)
        }
    }
}

@Composable
private fun LeaveByLabel(lb: LeaveBy) {
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    val (pose, words, color) = when (lb.state) {
        LeaveByState.PLENTY -> Triple(WalkerPose.STAND, "LEAVE BY " + formatClock(lb.leaveAtMs, tz, fmt), TtTheme.colors.text)
        LeaveByState.NOW -> Triple(WalkerPose.WALK, "LEAVE NOW", TtTheme.colors.text)
        LeaveByState.RUN -> Triple(WalkerPose.RUN, "RUN", TtTheme.colors.caution)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        WalkerSprite(pose)
        Label(words, color = color, bold = true, size = LabelSize.L10)
    }
}

private fun leaveBySpoken(lb: LeaveBy, tz: kotlinx.datetime.TimeZone, fmt: app.transittracker.ui.settings.ClockFormat): String = when (lb.state) {
    LeaveByState.PLENTY -> "leave by ${formatClock(lb.leaveAtMs, tz, fmt)}, ${lb.walkMinutes} minute walk to ${lb.stopName}"
    LeaveByState.NOW -> "leave now, ${lb.walkMinutes} minute walk to ${lb.stopName}"
    LeaveByState.RUN -> "run, the walk to ${lb.stopName} takes ${lb.walkMinutes} minutes"
}

@Composable
private fun HeadlineStrip(d: Departure, clock: WallClock, service: (ServiceRef) -> Flow<ServiceDetail>, modifier: Modifier) {
    val flow = remember(d.ref) { service(d.ref) }
    val detail by flow.collectAsState(null)
    val calls = detail?.calls
    if (detail == null || calls.isNullOrEmpty()) {
        Box(modifier.fillMaxWidth().height(54.dp))
        return
    }
    val shortCalls = remember(calls) { calls.map { it.copy(stopName = shortStopName(it.stopName)) } }
    val stale = detail?.stale ?: d.stale
    JourneyStrip(
        shortCalls, d.originStopId, d.destinationStopId, d.mode,
        parseLineColor(d.routeColor) ?: DefaultLineColor, d.status, clock, modifier,
        stale = stale, delaySeconds = d.delaySeconds, lateTag = false,
    )
}

@Composable
private fun NoServices(card: TripCard) {
    val tz = TtTheme.time.timeZone
    val first = card.noMoreServices?.firstNextDayMs
    val text = if (card.noMoreServices != null) {
        "No more services tonight" + (first?.let { " · first " + formatClock(it, tz, TtTheme.time.clockFormat) } ?: "")
    } else "No running services right now"
    PixelText(text, style = TtTheme.type.reading15, color = TtTheme.colors.textReading)
    EmptyTrack(Modifier.fillMaxWidth().height(24.dp))
}

/** A row for a following service (Main mockup): time, badge, where to · platform, status, minutes. */
@Composable
private fun ServiceRow(d: Departure, nowMs: Long, actions: HomeActions) {
    val c = TtTheme.colors
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    val status = statusPresentation(d)
    val cancelled = d.status == DepartureStatus.CANCELLED
    val cd = countdown(d.expectedDepartureMs, nowMs, tz, fmt, status.countdownPrefix)
    val where = d.headsign + (d.originPlatform?.let { " · Plat $it" } ?: "")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .serviceTaps({ actions.openService(d) }, actions.manageRoutes, serviceSentence(d, cd, status.spoken)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelText(
            formatClock(if (cancelled) d.scheduledDepartureMs else d.expectedDepartureMs, tz, fmt, withSuffix = false),
            Modifier.width(50.dp), TtTheme.type.row15Strong,
            color = if (cancelled) c.textSecondary else c.text, strike = cancelled, maxLines = 1,
        )
        RouteBadge(d.routeName, d.routeColor, null, width = 34.dp + TtTheme.dot * 2)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PixelText(where, color = if (cancelled) c.textSecondary else c.text, strike = cancelled, maxLines = 1, overflow = TextOverflow.Ellipsis)
            StatusLine(status, size = StatusSize.SMALL)
        }
        PixelText(
            if (cancelled) "—" else rowCountdown(cd),
            style = TtTheme.type.row15Strong, color = if (cancelled) c.textSecondary else c.text,
            textAlign = TextAlign.End, maxLines = 1,
        )
    }
}

private fun rowCountdown(cd: CountdownText): String = when (cd.kind) {
    CountdownText.Kind.MINUTES -> cd.value + " min"
    CountdownText.Kind.NOW -> cd.value.lowercase()
    CountdownText.Kind.CLOCK -> cd.value
}

/** "T9 to Gordon, leaves in 4 minutes, platform 3, 4 minutes late" (plan 7). */
private fun serviceSentence(d: Departure, cd: CountdownText, statusSpoken: String): String = buildString {
    append("${d.routeName} to ${d.headsign}")
    if (d.status != DepartureStatus.CANCELLED) append(", ${cd.spoken}")
    d.originPlatform?.let { append(if (d.platformChanged) ", platform changed, now platform $it" else ", platform $it") }
    append(", $statusSpoken")
}

/**
 * "THEN [600] 08:14 · STAND C · 4 MIN LATE": a later ride of a multi-leg headline, so the bus is
 * on the card, not only the train. Tap: that service.
 */
@Composable
private fun ConnectionLine(d: Departure, tripId: String, journey: Journey, actions: HomeActions) {
    val c = TtTheme.colors
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    val status = statusPresentation(d)
    val cancelled = d.status == DepartureStatus.CANCELLED
    val time = formatClock(if (cancelled) d.scheduledDepartureMs else d.expectedDepartureMs, tz, fmt)
    val sentence = "Then ${d.routeName} to ${d.headsign}, leaves $time" +
        (d.originPlatform?.let { ", ${standWord(d.mode).lowercase()} $it" } ?: "") + ", ${status.spoken}"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).serviceTaps({ actions.openService(d) }, actions.manageRoutes, sentence),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label("THEN", size = LabelSize.L10, color = c.textSecondary)
        RouteBadge(d.routeName, d.routeColor, null, style = BadgeStyle.COMPACT)
        PixelText(time, style = TtTheme.type.row15Strong, color = if (cancelled) c.textSecondary else c.text, strike = cancelled, maxLines = 1)
        d.originPlatform?.let { Label("${standWord(d.mode)} $it", size = LabelSize.L10, color = c.textSecondary) }
        StatusLine(status, Modifier.weight(1f), size = StatusSize.SMALL)
    }
}

/** What a boarding point is called for [mode]: platform, stand, wharf. */
private fun standWord(mode: Mode): String = when (mode) {
    Mode.BUS, Mode.COACH -> "STAND"
    Mode.FERRY -> "WHARF"
    else -> "PLAT"
}

// ---------------------------------------------------------------- on your way

/**
 * Follow my journey (the phone's location says the user is on this trip's journey): the ride
 * they are on and when it reaches the change, the connection with its own countdown, platform or
 * stand and status, or, while changing, the service being walked to. Under it the next
 * departures stay as OTHER SERVICES. Tap: the journey on one map.
 */
@Composable
private fun OnTheWayCard(card: TripCard, otw: OnTheWay, nowMs: Long, actions: HomeActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    val leg = otw.leg
    val riding = otw.stage == FollowStage.RIDING
    val openMap = { actions.openJourneyMap(card.trip.id, otw.journey) }
    PixelPanel(
        modifier.cardTaps(openMap, actions.manageRoutes, "Map of your journey"),
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = if (card.following.isEmpty()) 12.dp else 0.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TitleParts(card.titleParts, TtTheme.type.name17)
            Label("ON YOUR WAY", color = c.go, bold = true, size = LabelSize.L10)
        }
        // The ride now (or the one being walked to).
        val status = statusPresentation(leg)
        val word = vehicleWord(leg.mode)
        val (label, target, verb) = if (riding) {
            Triple("ON THE $word · ARRIVES" + (otw.legEnd?.let { " ${it.uppercase()}" } ?: ""), leg.expectedArrivalMs, "arrives")
        } else {
            Triple("CHANGE" + (otw.legStart?.let { " AT ${it.uppercase()}" } ?: "") + " · $word LEAVES", leg.expectedDepartureMs, "leaves")
        }
        val cd = countdown(target, nowMs, tz, fmt, status.countdownPrefix, verb)
        val spoken = "${if (riding) "On the" else "Change to the"} ${leg.routeName} to ${leg.headsign}, ${cd.spoken}, ${status.spoken}"
        Column(
            Modifier.fillMaxWidth().serviceTaps({ actions.openService(leg) }, actions.manageRoutes, spoken),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RouteBadge(leg.routeName, leg.routeColor, null)
                PixelText(leg.headsign, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Label(label, size = LabelSize.L10, color = c.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Countdown(cd, size = CountdownSize.L60)
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PixelText(formatClock(target, tz, fmt), style = TtTheme.type.title22)
                    Label("ARR " + formatClock(otw.arrivalMs, tz, fmt), color = c.textSecondary)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                StatusLine(status)
                if (!riding && leg.originPlatform != null) PlatformSign(leg.originPlatform!!, leg.platformChanged)
            }
        }
        // The connection to make.
        otw.next?.let { next -> NextRide(next, otw, nowMs, actions) }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton("MAP", openMap, Modifier.weight(1f), style = TtTheme.type.label11Bold)
            PixelButton("NOT ON THIS", { actions.notOnThis(otw.journey.departure.ref) }, Modifier.weight(1f), style = TtTheme.type.label11Bold)
        }
        if (card.following.isNotEmpty()) {
            DashedDivider()
            Label("OTHER SERVICES", Modifier.padding(top = 8.dp), size = LabelSize.L10, color = c.textSecondary)
            for ((i, j) in card.following.withIndex()) {
                Column {
                    if (i > 0) DashedDivider()
                    ServiceRow(j.departure, nowMs, actions)
                }
            }
        }
    }
}

/** The connection while riding: "THEN [600] BUS · LEAVES 08:14 · STAND C", its countdown, the change time. */
@Composable
private fun NextRide(next: Departure, otw: OnTheWay, nowMs: Long, actions: HomeActions) {
    val c = TtTheme.colors
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    val status = statusPresentation(next)
    val cancelled = next.status == DepartureStatus.CANCELLED
    val cd = countdown(next.expectedDepartureMs, nowMs, tz, fmt, status.countdownPrefix)
    val change = when {
        cancelled -> "CANCELLED · CHECK OTHER SERVICES"
        otw.changeMinutes == null -> null
        otw.tight -> "TIGHT CHANGE · ${otw.changeMinutes.coerceAtLeast(0)} MIN"
        else -> "${otw.changeMinutes} MIN TO CHANGE" + (otw.legEnd?.let { " AT ${it.uppercase()}" } ?: "")
    }
    val spoken = "Then ${next.routeName} to ${next.headsign}, ${cd.spoken}" +
        (next.originPlatform?.let { ", ${standWord(next.mode).lowercase()} $it" } ?: "") + ", ${status.spoken}" +
        (change?.let { ", ${it.lowercase()}" } ?: "")
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp).serviceTaps({ actions.openService(next) }, actions.manageRoutes, spoken),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DashedDivider()
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("THEN", size = LabelSize.L10, color = c.textSecondary)
            RouteBadge(next.routeName, next.routeColor, null)
            PixelText("${vehicleWord(next.mode)} · ${next.headsign}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Countdown(cd, size = CountdownSize.S30, color = if (cancelled) c.textSecondary else c.text)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PixelText(
                    "LEAVES " + formatClock(if (cancelled) next.scheduledDepartureMs else next.expectedDepartureMs, tz, fmt),
                    style = TtTheme.type.row15Strong, strike = cancelled, color = if (cancelled) c.textSecondary else c.text,
                )
                StatusLine(status, size = StatusSize.SMALL)
                change?.let { Label(it, size = LabelSize.L10, bold = otw.tight, color = if (otw.tight) c.caution else c.textSecondary) }
            }
            next.originPlatform?.let { PlatformSign(it, next.platformChanged) }
        }
    }
}

// ---------------------------------------------------------------- compact card

@Composable
private fun CompactTripCard(card: TripCard, nowMs: Long, actions: HomeActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    val d = card.headline?.departure
    val open = { actions.openTimetable(card.trip.id, d) }
    PixelPanel(
        modifier.cardTaps(open, actions.manageRoutes, null),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TitleParts(card.titleParts, TtTheme.type.row15Strong)
                if (card.waysLabel != null) Label(card.waysLabel, size = LabelSize.L10, color = c.info, bold = true)
                if (d != null) {
                    val status = statusPresentation(d)
                    val suffix = formatClock(d.expectedDepartureMs, tz, fmt) + (d.originPlatform?.let { " · PLAT $it" } ?: "")
                    StatusLine(status, size = StatusSize.SMALL, suffix = suffix)
                } else {
                    val first = card.noMoreServices?.firstNextDayMs
                    Label(
                        "NO MORE SERVICES TONIGHT" + (first?.let { " · FIRST " + formatClock(it, tz, fmt) } ?: ""),
                        size = LabelSize.L10, color = c.textSecondary,
                    )
                }
                if (card.changes.isNotEmpty() && card.headline != null) ChangesRow(card.headline, card.changes, compact = true)
            }
            if (d != null) {
                val status = statusPresentation(d)
                Countdown(countdown(d.expectedDepartureMs, nowMs, tz, fmt, status.countdownPrefix), size = CountdownSize.S30)
            }
        }
    }
}

// ---------------------------------------------------------------- empty

@Composable
private fun EmptyHome(actions: HomeActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        EmptyPlatformScene(Modifier.fillMaxWidth().height(160.dp))
        PixelText("No trips yet", style = TtTheme.type.title20)
        PixelText(
            "Plan a journey and save it as a route. Its next services will wait for you here.",
            style = TtTheme.type.reading15, color = c.textReading, textAlign = TextAlign.Center,
        )
        PixelButton("ADD YOUR FIRST TRIP", actions.planJourney, Modifier.fillMaxWidth(), ButtonKind.PRIMARY)
    }
}

/**
 * The empty platform at night (plan 2.6 "Scenes"): a moon, a lamp post with its pool of light, a
 * bench, the platform edge and an empty track. Drawn from whole dots.
 */
@Composable
private fun EmptyPlatformScene(modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val dot = TtTheme.dot.px.toFloat()
    Canvas(modifier.clearAndSetSemantics { contentDescription = "An empty platform at night" }) {
        fun r(x: Int, y: Int, w: Int, h: Int, col: androidx.compose.ui.graphics.Color) =
            drawRect(col, Offset(x * dot, y * dot), Size(w * dot, h * dot))
        val wDots = (size.width / dot).toInt()
        val hDots = (size.height / dot).toInt()
        val cx = wDots / 2
        // Moon.
        r(cx + 40, 6, 6, 1, c.text); r(cx + 39, 7, 8, 5, c.text); r(cx + 40, 12, 6, 1, c.text); r(cx + 42, 7, 5, 4, c.screen)
        // Stars.
        for ((sx, sy) in listOf(-60 to 10, -35 to 4, -12 to 14, 20 to 8, 58 to 18, -50 to 26)) r(cx + sx, sy, 1, 1, c.textSecondary)
        val ground = hDots - 22
        // Lamp post and its light.
        r(cx - 30, ground - 44, 2, 44, c.divider); r(cx - 34, ground - 46, 10, 2, c.divider); r(cx - 33, ground - 44, 8, 2, c.lit)
        r(cx - 40, ground - 1, 20, 1, c.raised)
        // Bench.
        r(cx + 6, ground - 8, 22, 2, c.textSecondary); r(cx + 6, ground - 13, 22, 2, c.divider)
        r(cx + 8, ground - 6, 2, 6, c.divider); r(cx + 24, ground - 6, 2, 6, c.divider)
        // Platform, edge line, track.
        r(0, ground, wDots, 6, c.raised); r(0, ground, wDots, 1, c.caution)
        r(0, ground + 12, wDots, 1, c.textSecondary)
        var x = 0
        while (x < wDots) { r(x, ground + 13, 1, 2, c.divider); x += 4 }
    }
}

/** Rails and sleepers with nothing on them (no services). */
@Composable
private fun EmptyTrack(modifier: Modifier) {
    val c = TtTheme.colors
    val dot = TtTheme.dot.px.toFloat()
    Canvas(modifier.clearAndSetSemantics { }) {
        val y = (size.height / dot).toInt() * dot - 3 * dot
        drawRect(c.textSecondary, Offset(0f, y), Size(size.width, dot))
        drawDashes(c.divider, dot, y + dot, on = 1, off = 3, thick = 2)
    }
}

// ---------------------------------------------------------------- taps

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.cardTaps(onClick: () -> Unit, onLongClick: () -> Unit, label: String?): Modifier {
    val source = remember { MutableInteractionSource() }
    return combinedClickable(source, null, onClickLabel = label, onLongClickLabel = "Manage routes", onLongClick = onLongClick, onClick = onClick)
}

/** A service: tap opens it, long-press manages routes; read as one [sentence]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.serviceTaps(onClick: () -> Unit, onLongClick: () -> Unit, sentence: String? = null): Modifier {
    val source = remember { MutableInteractionSource() }
    val base = combinedClickable(source, null, onLongClick = onLongClick, onClick = onClick)
    return if (sentence == null) base else base.clearAndSetSemantics {
        contentDescription = sentence
        role = Role.Button
        onClick { onClick(); true }
        onLongClick("Manage routes") { onLongClick(); true }
    }
}
