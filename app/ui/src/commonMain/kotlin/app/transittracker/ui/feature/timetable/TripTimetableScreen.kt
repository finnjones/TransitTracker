package app.transittracker.ui.feature.timetable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.transittracker.model.Departure
import app.transittracker.model.DepartureStatus
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceTime
import app.transittracker.ui.app.LocalAppDeps
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.DashedDivider
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIconButton
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.RouteBadge
import app.transittracker.ui.components.StatusLamp
import app.transittracker.ui.components.StatusLine
import app.transittracker.ui.components.StatusSize
import app.transittracker.ui.components.drawDashes
import app.transittracker.ui.feature.home.BannerKind
import app.transittracker.ui.feature.home.DataChip
import app.transittracker.ui.feature.home.HomeBanner
import app.transittracker.ui.feature.service.ChipKind
import app.transittracker.ui.feature.service.DataChipLine
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.format.formatClock
import app.transittracker.ui.format.formatDayDate
import app.transittracker.ui.format.statusPresentation
import app.transittracker.ui.motion.FlapClock
import app.transittracker.ui.motion.LocalFlapClock
import app.transittracker.ui.motion.PageTransition
import app.transittracker.ui.motion.Timeline
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.nav.Tab
import app.transittracker.ui.settings.ClockFormat
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.TtTheme
import kotlinx.datetime.TimeZone
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.rememberCoroutineScope
import app.transittracker.ui.components.ToggleChip
import kotlinx.coroutines.launch

/** What the timetable can ask for; the screen maps these to navigation and the holder. */
class TimetableActions(
    val back: () -> Unit = {},
    val previousDay: () -> Unit = {},
    val nextDay: () -> Unit = {},
    val goToday: () -> Unit = {},
    val goToDay: (ServiceDate) -> Unit = {},
    val openService: (Departure) -> Unit = {},
    val notify: () -> Unit = {},
    val edit: () -> Unit = {},
    val openAlerts: () -> Unit = {},
    val toggleEarlier: () -> Unit = {},
    /** Shows one way of the route, or BEST (null). */
    val selectWay: (String?) -> Unit = {},
    /** Pins a way for Home, or unpins (null). */
    val pinWay: (String?) -> Unit = {},
    /** Plans the route again now, to see and add other ways. */
    val findMoreWays: () -> Unit = {},
)

/** All services of one saved trip on a chosen day (plan 5.3). */
@Composable
fun TripTimetableScreen(route: Route.TripTimetable) {
    val deps = LocalAppDeps.current
    val nav = LocalNavigator.current
    // The day and the folded block survive the page being covered by a service (the holder does not).
    var savedDate by rememberSaveable { mutableStateOf(route.serviceDate) }
    var showEarlier by rememberSaveable { mutableStateOf(false) }
    val holder = rememberStateHolder(route.tripId) { scope ->
        TimetableStateHolder(deps.core, deps.places, deps.clock, route.tripId, savedDate, scope)
    }
    val state by holder.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.date) { if (state.date.isNotEmpty()) savedDate = state.date }
    val actions = remember(nav, holder, route) {
        TimetableActions(
            back = { nav.back() },
            previousDay = { holder.moveDays(-1) },
            nextDay = { holder.moveDays(1) },
            goToday = { holder.goToToday() },
            goToDay = { holder.setDate(it) },
            openService = { d ->
                nav.push(Route.Service(d.ref, d.originStopId, d.destinationStopId), PageTransition.vehiclePass(d.mode, d.routeColor))
            },
            notify = { nav.push(Route.WatchEditor(fromTripId = route.tripId)) },
            edit = { nav.push(Route.EditRoute(route.tripId)) },
            openAlerts = { nav.switchTab(Tab.ALERTS) },
            toggleEarlier = { showEarlier = !showEarlier },
            selectWay = holder::selectWay,
            pinWay = holder::pinWay,
            findMoreWays = {
                scope.launch {
                    val ends = holder.ends()
                    if (ends != null) nav.push(Route.JourneyOptions(ends.first, ends.second)) else nav.push(Route.PlanJourney())
                }
            },
        )
    }
    TimetableContent(state, showEarlier, actions)
}

/** The day change flips the service rows like a departure board (plan 3.4); page arrival stays the page's. */
@Stable
private class DayFlap(private val outer: FlapClock) : FlapClock {
    /** First row index that belongs to the day's services; set on every composition. */
    var base = 0
    /** Milliseconds since the day changed; negative when idle. */
    var elapsedMs by mutableLongStateOf(-1L)

    override fun rowProgress(index: Int): Float =
        if (elapsedMs >= 0 && index >= base) Timeline.flapRowProgress(index - base, elapsedMs) else outer.rowProgress(index)
}

/** The timetable for [state]; stateless. */
@Composable
fun TimetableContent(
    state: TimetableUiState,
    showEarlier: Boolean,
    actions: TimetableActions,
    modifier: Modifier = Modifier,
) {
    val outer = LocalFlapClock.current
    val flap = remember(outer) { DayFlap(outer) }
    val reduce = TtTheme.motion.reduceMotion
    var lastDate by remember { mutableStateOf<String?>(null) }
    // When the day changes the rows are hidden at once (before the first frame of the new day) and flip in.
    val changed = lastDate != null && lastDate != state.date && state.date.isNotEmpty()
    remember(state.date) { if (changed && !reduce) flap.elapsedMs = 0L; Unit }
    LaunchedEffect(state.date) {
        val first = lastDate == null
        lastDate = state.date
        if (first || reduce || state.date.isEmpty()) {
            flap.elapsedMs = -1L
            return@LaunchedEffect
        }
        var start = -1L
        while (true) {
            val t = withFrameMillis { it }
            if (start < 0) start = t
            val e = t - start
            if (e >= Timeline.FLAP_MS) break
            flap.elapsedMs = e
        }
        flap.elapsedMs = -1L
    }
    CompositionLocalProvider(LocalFlapClock provides flap) {
        Column(modifier.fillMaxSize().background(TtTheme.colors.screen)) {
            Header(state, actions)
            if (!state.loading && state.tripFound) {
                DaySelector(state, actions, Modifier.flapRow(1))
                WaysBar(state, actions, Modifier.flapRow(1))
                TimetableList(state, showEarlier, actions, flap, Modifier.weight(1f).fillMaxWidth())
                ActionBar(actions)
            } else if (!state.loading) {
                Box(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    PixelText(
                        "This route is not saved any more.",
                        style = TtTheme.type.reading15, color = TtTheme.colors.textReading,
                    )
                }
            } else Box(Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------- header and day selector

@Composable
private fun Header(state: TimetableUiState, actions: TimetableActions) {
    val c = TtTheme.colors
    Row(
        Modifier.fillMaxWidth().flapRow(0).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelIconButton(PixelIcons.ChevronLeft, "Back", actions.back)
        if (state.titleParts.isEmpty()) {
            PixelText("Timetable", Modifier.weight(1f).semantics { heading() }, TtTheme.type.title20)
        } else {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.semantics(mergeDescendants = true) { heading() },
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    for ((i, p) in state.titleParts.withIndex()) {
                        if (i > 0) PixelIcon(PixelIcons.ChevronRight, tint = c.textSecondary)
                        PixelText(p, Modifier.weight(1f, fill = false), TtTheme.type.title20, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // Under the title: the stations when it shows places, and the state of the data.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    state.stationsLine?.let { Label(it, Modifier.weight(1f, fill = false), c.textSecondary, size = LabelSize.L10) }
                    state.chip?.let { chip ->
                        when (chip) {
                            is DataChip.Live -> DataChipLine(ChipKind.LIVE, chip.ageMs)
                            DataChip.Stale -> DataChipLine(ChipKind.STALE, null)
                            DataChip.Offline -> DataChipLine(ChipKind.OFFLINE, null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySelector(state: TimetableUiState, actions: TimetableActions, modifier: Modifier) {
    val c = TtTheme.colors
    val tz = TtTheme.time.timeZone
    val relation = if (state.date.isEmpty()) DayRelation.OTHER else dayRelation(state.date, state.today)
    val dateText = dayText(state.date, tz)
    val (title, sub) = when (relation) {
        DayRelation.TODAY -> "TODAY" to dateText
        DayRelation.TOMORROW -> "TOMORROW" to dateText
        DayRelation.YESTERDAY -> "YESTERDAY" to dateText
        DayRelation.OTHER -> dateText to daysAheadWords(daysFrom(state.today, state.date))
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelIconButton(PixelIcons.ChevronLeft, "Previous day", actions.previousDay, enabled = state.canGoBack)
        val source = remember { MutableInteractionSource() }
        val spoken = if (relation == DayRelation.TODAY) "Today, $dateText" else "$title, $dateText. Tap to go to today"
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable(source, null, enabled = relation != DayRelation.TODAY, role = Role.Button, onClick = actions.goToday)
                .clearAndSetSemantics { contentDescription = spoken },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            PixelText(title, style = TtTheme.type.title20, maxLines = 1)
            Label(sub, size = LabelSize.L10, color = c.textSecondary)
        }
        PixelIconButton(PixelIcons.ChevronRight, "Next day", actions.nextDay, enabled = state.canGoForward)
    }
}

/** "THU 12 NOV" for a service date, from noon of that day. */
private fun dayText(date: ServiceDate, tz: TimeZone): String =
    if (date.isEmpty()) "" else formatDayDate(ServiceTime.toEpochMillis(date, 12 * 3600, tz), tz)

private fun daysAheadWords(days: Int): String = when {
    days == 1 -> "IN 1 DAY"
    days > 1 -> "IN $days DAYS"
    days == -1 -> "1 DAY AGO"
    else -> "${-days} DAYS AGO"
}

// ---------------------------------------------------------------- the list

@Composable
private fun TimetableList(
    state: TimetableUiState,
    showEarlier: Boolean,
    actions: TimetableActions,
    flap: DayFlap,
    modifier: Modifier,
) {
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    // Rows 0 and 1 are the header and the day selector; the list continues from 2.
    var row = 2
    val banners = state.banners
    val alert = state.alerts.firstOrNull()
    val showTimetableNote = state.timetableOnly && banners.none { it.kind == BannerKind.OFFLINE } && state.rowsLoaded
    val bannerRows = banners.size + (if (alert != null) 1 else 0) + (if (showTimetableNote) 1 else 0)
    flap.base = row + bannerRows
    LazyColumn(modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
        for (b in banners) {
            val r = row++
            item(key = "banner-${b.kind}") { NoteBanner(b, Modifier.padding(bottom = 8.dp).flapRow(r)) }
        }
        if (alert != null) {
            val r = row++
            item(key = "alert") { AlertBanner(state.alerts, actions.openAlerts, Modifier.padding(bottom = 8.dp).flapRow(r)) }
        }
        if (showTimetableNote) {
            val r = row++
            item(key = "note-timetable") {
                NoteBanner(
                    HomeBanner(BannerKind.UPDATING, "Timetable only. Live status appears closer to the departure."),
                    Modifier.padding(bottom = 8.dp).flapRow(r),
                )
            }
        }
        if (state.earlier.isNotEmpty()) {
            val r = row++
            item(key = "fold") {
                FoldControl(state.earlier.size, showEarlier, actions.toggleEarlier, Modifier.padding(bottom = 4.dp).flapRow(r))
            }
            if (showEarlier) {
                for (entry in state.earlier) {
                    val rr = row++
                    item(key = "e-${entry.key}") { EntryRow(entry, earlier = true, actions, Modifier.flapRow(rr)) }
                }
            }
        }
        if (state.isToday && state.rowsLoaded && !state.isEmpty) {
            val r = row++
            item(key = "now") { NowMarker(state.nowMs, tz, fmt, Modifier.padding(vertical = 4.dp).flapRow(r)) }
        }
        for (entry in state.upcoming) {
            val r = row++
            item(key = "u-${entry.key}") { EntryRow(entry, earlier = false, actions, Modifier.flapRow(r)) }
        }
        if (state.nothingToCome) {
            val r = row++
            item(key = "nothing") { NothingToCome(state, actions, tz, fmt, Modifier.flapRow(r)) }
        }
    }
}

@Composable
private fun NoteBanner(banner: HomeBanner, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val (lamp, color) = when (banner.kind) {
        BannerKind.OFFLINE -> Lamp.RING to c.info
        BannerKind.STALE -> Lamp.RING to c.textSecondary
        BannerKind.MESSAGE -> Lamp.CIRCLE to c.text
        BannerKind.UPDATING -> Lamp.RING to c.info
        BannerKind.OUT_OF_DATE -> Lamp.TRIANGLE to c.caution
    }
    PixelPanel(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = banner.text },
        contentPadding = PaddingValues(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusLamp(lamp, color)
            PixelText(banner.text, Modifier.weight(1f), TtTheme.type.reading15, c.textReading)
        }
    }
}

/** One banner for the alerts on this trip's lines on the day shown; tapping opens the Alerts tab. */
@Composable
private fun AlertBanner(alerts: List<TripAlert>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val first = alerts.first()
    val more = alerts.size - 1
    val severe = first.severity == app.transittracker.model.AlertSeverity.SEVERE
    val color = if (severe) c.stop else c.caution
    val source = remember { MutableInteractionSource() }
    val spoken = "Alert on this route: ${first.headline}" + if (more > 0) ", and $more more" else ""
    PixelPanel(
        modifier
            .fillMaxWidth()
            .clickable(source, null, role = Role.Button, onClick = onOpen)
            .clearAndSetSemantics { contentDescription = spoken; role = Role.Button; onClick("Open alerts") { onOpen(); true } },
        borderColor = color,
        contentPadding = PaddingValues(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            PixelIcon(PixelIcons.Warning, tint = color, size = 24.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label(if (more > 0) "ALERT · +$more MORE" else "ALERT", color = color, bold = true, size = LabelSize.L10)
                PixelText(first.headline, style = TtTheme.type.reading15, color = c.textReading, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FoldControl(count: Int, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val words = if (open) "HIDE EARLIER SERVICES" else "SHOW $count EARLIER " + if (count == 1) "SERVICE" else "SERVICES"
    PixelButton(onToggle, modifier.fillMaxWidth(), ButtonKind.FLAT, faceHeight = 40.dp, contentDescription = words.lowercase()) { color ->
        Label(words, color = color, bold = true, size = LabelSize.L10)
    }
}

/** The line between services that have left and those still to come. */
@Composable
private fun NowMarker(nowMs: Long, tz: TimeZone, fmt: ClockFormat, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    Row(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Now, ${formatClock(nowMs, tz, fmt)}. Services above have left." },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.weight(1f).height(dot * 2)) { drawDashes(c.caution, dot.px.toFloat(), 0f, on = 3, off = 2, thick = 2) }
        Label("NOW " + formatClock(nowMs, tz, fmt), color = c.caution, bold = true, size = LabelSize.L10)
        Canvas(Modifier.weight(1f).height(dot * 2)) { drawDashes(c.caution, dot.px.toFloat(), 0f, on = 3, off = 2, thick = 2) }
    }
}

// ---------------------------------------------------------------- one service

/**
 * A row (plan 5.3): departure (expected, the timetabled time struck through when it differs),
 * a badge per leg, arrival and duration, the status, the platform. One item for a screen reader.
 */
@Composable
private fun EntryRow(entry: TimetableEntry, earlier: Boolean, actions: TimetableActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    val tz = TtTheme.time.timeZone
    val fmt = TtTheme.time.clockFormat
    val j = entry.journey
    val first = j.departure
    val head = headlineLeg(j)
    val status = statusPresentation(head)
    val cancelled = head.status == DepartureStatus.CANCELLED
    val dim = earlier || cancelled
    val source = remember { MutableInteractionSource() }
    val sentence = entrySentence(entry, status.spoken, tz, fmt)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(source, null, role = Role.Button) { actions.openService(first) }
                .clearAndSetSemantics { contentDescription = sentence; role = Role.Button; onClick("Open service") { actions.openService(first); true } }
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(52.dp)) {
                val strike = statusPresentation(first).strikeScheduledTime && !cancelled
                PixelText(
                    formatClock(if (cancelled) first.scheduledDepartureMs else first.expectedDepartureMs, tz, fmt, withSuffix = false),
                    style = TtTheme.type.row15Strong,
                    color = when {
                        cancelled || earlier -> c.textSecondary
                        strike -> c.caution
                        else -> c.text
                    },
                    strike = cancelled, maxLines = 1,
                )
                if (strike) {
                    PixelText(formatClock(first.scheduledDepartureMs, tz, fmt, withSuffix = false), style = TtTheme.type.row15, color = c.textSecondary, strike = true, maxLines = 1)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (leg in j.legs) {
                    RouteBadge(leg.routeName, leg.routeColor, null, width = if (j.legs.size == 1) 34.dp + dot * 2 else null)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    PixelText(
                        "→ " + formatClock(j.arrival.expectedArrivalMs, tz, fmt, withSuffix = false),
                        Modifier.alignByBaseline(), TtTheme.type.row15, if (dim) c.textSecondary else c.text, strike = cancelled, maxLines = 1,
                    )
                    Label(entry.durationMinutes.toString() + " MIN", Modifier.alignByBaseline(), c.textSecondary, size = LabelSize.L10)
                }
                StatusLine(status, size = StatusSize.SMALL)
            }
            val platform = first.originPlatform
            if (platform != null && !cancelled) PlatformCell(platform, first.platformChanged)
        }
        DashedDivider()
    }
}

@Composable
private fun PlatformCell(platform: String, changed: Boolean) {
    val c = TtTheme.colors
    if (changed) {
        Box(Modifier.background(c.caution).padding(horizontal = 4.dp, vertical = 2.dp)) {
            Label("NOW PLAT $platform", color = c.screen, bold = true, size = LabelSize.L10)
        }
    } else {
        Label("PLAT $platform", color = c.textSecondary, size = LabelSize.L10)
    }
}

/** "T9 to Gordon, leaves 07:42, arrives 08:02, 20 minutes, platform 3, 4 minutes late" (plan 7). */
private fun entrySentence(entry: TimetableEntry, statusSpoken: String, tz: TimeZone, fmt: ClockFormat): String {
    val j = entry.journey
    val d = j.departure
    return buildString {
        append(j.legs.joinToString(", then ") { "${it.routeName} to ${it.headsign}" })
        if (d.status != DepartureStatus.CANCELLED) {
            append(", leaves ${formatClock(d.expectedDepartureMs, tz, fmt)}")
            append(", arrives ${formatClock(j.arrival.expectedArrivalMs, tz, fmt)}")
            append(", ${entry.durationMinutes} minutes")
            d.originPlatform?.let { append(if (d.platformChanged) ", platform changed, now platform $it" else ", platform $it") }
        } else {
            append(", was ${formatClock(d.scheduledDepartureMs, tz, fmt)}")
        }
        append(", $statusSpoken")
    }
}

// ---------------------------------------------------------------- nothing left

@Composable
private fun NothingToCome(state: TimetableUiState, actions: TimetableActions, tz: TimeZone, fmt: ClockFormat, modifier: Modifier) {
    val c = TtTheme.colors
    val noneAtAll = state.earlier.isEmpty()
    val day = dayText(state.date, tz)
    val dayWords = when (dayRelation(state.date, state.today)) {
        DayRelation.TODAY -> "today"
        DayRelation.TOMORROW -> "tomorrow"
        else -> day
    }
    val headline = if (noneAtAll) "No services $dayWords." else "No more services today."
    val next = state.next
    val nextWords = when {
        next != null -> {
            val rel = dayRelation(next.date, state.date)
            val which = if (rel == DayRelation.TOMORROW) "tomorrow" else dayText(next.date, tz)
            "The next one leaves $which at ${formatClock(next.departureMs, tz, fmt)}."
        }
        state.nextSearched -> "No services found in the next two weeks."
        else -> null
    }
    Column(modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PixelText(headline, style = TtTheme.type.title20)
        if (nextWords != null) PixelText(nextWords, style = TtTheme.type.reading15, color = c.textReading)
        EmptyRails(Modifier.fillMaxWidth().height(24.dp))
        if (next != null) {
            val rel = dayRelation(next.date, state.date)
            val label = if (rel == DayRelation.TOMORROW && state.isToday) "SEE TOMORROW" else "SEE " + dayText(next.date, tz)
            PixelButton(label, { actions.goToDay(next.date) }, Modifier.fillMaxWidth(), ButtonKind.SECONDARY)
        }
    }
}

/** Rails and sleepers with nothing on them (no services, plan 6). */
@Composable
private fun EmptyRails(modifier: Modifier) {
    val c = TtTheme.colors
    val dot = TtTheme.dot.px.toFloat()
    Canvas(modifier.clearAndSetSemantics { }) {
        val y = (size.height / dot).toInt() * dot - 3 * dot
        drawRect(c.textSecondary, Offset(0f, y), Size(size.width, dot))
        drawDashes(c.divider, dot, y + dot, on = 1, off = 3, thick = 2)
    }
}

// ---------------------------------------------------------------- ways

/**
 * The ways of the route (BEST and one tab each), pinning the one shown, and FIND MORE WAYS NOW
 * (plan the route again to add another way; offered for a route with one way too).
 */
@Composable
private fun WaysBar(state: TimetableUiState, actions: TimetableActions, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.ways.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleChip("BEST", state.selectedWay == null, { if (it) actions.selectWay(null) })
                for (w in state.ways) {
                    val pin = if (w.id == state.pinnedWay) " ★" else ""
                    ToggleChip(w.name.uppercase() + pin, state.selectedWay == w.id, { if (it) actions.selectWay(w.id) })
                }
            }
            val selected = state.ways.firstOrNull { it.id == state.selectedWay }
            val pinned = state.ways.firstOrNull { it.id == state.pinnedWay }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val words = when {
                    selected == null && pinned == null -> "Trips shows whichever way gets you there first."
                    selected == null -> "Trips shows only ${pinned!!.name}. Showing the best of every way here."
                    selected.id == state.pinnedWay -> "Trips always shows this way."
                    else -> "Pin this way to always see it on Trips."
                }
                PixelText(words, Modifier.weight(1f), TtTheme.type.label10, c.textSecondary)
                when {
                    selected != null && selected.id != state.pinnedWay -> PixelButton("PIN", { actions.pinWay(selected.id) }, kind = ButtonKind.FLAT)
                    pinned != null && (selected == null || selected.id == pinned.id) -> PixelButton("UNPIN", { actions.pinWay(null) }, kind = ButtonKind.FLAT)
                }
            }
        }
        PixelButton(
            if (state.ways.isEmpty()) "FIND OTHER WAYS NOW" else "FIND MORE WAYS NOW",
            actions.findMoreWays, Modifier.fillMaxWidth(), ButtonKind.FLAT,
        )
    }
}

// ---------------------------------------------------------------- actions

@Composable
private fun ActionBar(actions: TimetableActions) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.panel)
            .drawBehind { drawRect(c.divider, Offset.Zero, Size(size.width, dot.px(1))) }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp + dot.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PixelButton("NOTIFY ME", actions.notify, Modifier.weight(2f), ButtonKind.PRIMARY)
        PixelButton("EDIT", actions.edit, Modifier.weight(1f), ButtonKind.SECONDARY)
    }
}
