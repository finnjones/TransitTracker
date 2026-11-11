package app.transittracker.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Occupancy
import app.transittracker.ui.components.BadgeStyle
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.CarCount
import app.transittracker.ui.components.Countdown
import app.transittracker.ui.components.CountdownSize
import app.transittracker.ui.components.DashedDivider
import app.transittracker.ui.components.DayToggleRow
import app.transittracker.ui.components.DialogBox
import app.transittracker.ui.components.Digits
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelButtonImpl
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIconButton
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelSwitch
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.PlatformSign
import app.transittracker.ui.components.RouteBadge
import app.transittracker.ui.components.SeatPips
import app.transittracker.ui.components.SectionLabel
import app.transittracker.ui.components.StatusLine
import app.transittracker.ui.components.StatusSize
import app.transittracker.ui.components.Stepper
import app.transittracker.ui.components.FieldInput
import app.transittracker.ui.components.PixelTextField
import app.transittracker.ui.components.StepperRow
import app.transittracker.ui.components.SwitchRow
import app.transittracker.ui.components.ToggleChip
import app.transittracker.ui.format.countdown
import app.transittracker.ui.format.statusPresentation
import app.transittracker.ui.theme.TtTheme

/** A phone-sized page on the Screen colour with the 16 dp gutter. */
@Composable
fun GalleryPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(TtTheme.colors.screen).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Page 1: colour, type, buttons, switches, steppers, toggles. */
@Composable
fun ComponentGalleryPage1() = GalleryPage {
    val c = TtTheme.colors
    SectionLabel("Colour")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (col in listOf(c.screen, c.panel, c.raised, c.divider, c.textSecondary, c.text, c.go, c.caution, c.stop, c.info)) {
            Box(Modifier.size(28.dp).background(c.divider).padding(TtTheme.dot.dp).background(col))
        }
    }
    SectionLabel("Type")
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Countdown(countdown(SampleData.NOW_MS + 4 * 60_000, SampleData.NOW_MS, TtTheme.time.timeZone), size = CountdownSize.XL76)
        Digits("07:38")
    }
    PixelText("Strathfield to Central", style = TtTheme.type.title22)
    PixelText("North Strathfield", style = TtTheme.type.name17)
    PixelText("Berowra · Plat 2 · 9 min", style = TtTheme.type.row15)
    PixelText("Allow an extra 30 minutes. Buses run every 15 minutes.", style = TtTheme.type.reading15, color = c.textReading)
    Label("NOTIFY ME · SAVE WATCH", size = LabelSize.L13)
    SectionLabel("Buttons")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PixelButton("PRIMARY", {}, Modifier.weight(1f), ButtonKind.PRIMARY)
        PixelButton("SECONDARY", {}, Modifier.weight(1f))
        PixelButtonImpl({}, Modifier.weight(1f), ButtonKind.PRIMARY, true, 48.dp, null, true) { col ->
            PixelText("PRESSED", style = TtTheme.type.label12Bold, color = col)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        PixelIconButton(PixelIcons.Plus, "New trip", {}, kind = ButtonKind.PRIMARY, iconSize = 21.dp)
        PixelIconButton(PixelIcons.ChevronLeft, "Back", {})
        PixelButton("DISABLED", {}, enabled = false)
    }
    SectionLabel("Switch and stepper")
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        PixelSwitch(true)
        PixelSwitch(false)
        Stepper("5 min", {}, {}, "Less", "More", Modifier.size(width = 180.dp, height = 52.dp))
    }
    StepperRow("From", "07:15", {}, {}, "Earlier start", "Later start", valueStyle = TtTheme.type.digits30)
    SwitchRow("Platform changes", true, {})
    SwitchRow("Back on time", false, {})
}

/** Page 3: text fields. */
@Composable
fun ComponentGalleryPage3() = GalleryPage {
    SectionLabel("Text fields")
    PixelTextField("", {}, "Place name", Modifier.fillMaxWidth(), placeholder = "Name this place")
    PixelTextField("15 Albert Rd, Strathfield", {}, "Address", Modifier.fillMaxWidth(), input = FieldInput.SEARCH)
    PixelTextField(
        "Bondi Be", {}, "Search", Modifier.fillMaxWidth(), input = FieldInput.SEARCH,
        leading = { PixelIcon(PixelIcons.MapPin, tint = TtTheme.colors.textSecondary) },
        trailing = { Label("CLEAR", color = TtTheme.colors.textSecondary) },
    )
    PixelTextField("K7M5-S2QX", {}, "Invite code", Modifier.fillMaxWidth(), input = FieldInput.CODE, style = TtTheme.type.title22)
    PixelTextField("Off line", {}, "Disabled", Modifier.fillMaxWidth(), enabled = false)
}

/** Page 2: day toggles, badges, status language, signs, seats, cars, icons, dialog. */
@Composable
fun ComponentGalleryPage2() = GalleryPage {
    val c = TtTheme.colors
    SectionLabel("Days and chips")
    DayToggleRow(setOf(1, 2, 3, 4, 5), {})
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ToggleChip("FASTEST", true, {})
        ToggleChip("FEWER CHANGES", false, {})
        ToggleChip("LESS WALKING", false, {})
    }
    SectionLabel("Lines · colours come from the timetable")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((n, col) in listOf("T1" to SampleData.T1, "T2" to SampleData.T2, "T3" to SampleData.T3, "T4" to SampleData.T4, "T8" to SampleData.T8, "T9" to SampleData.T9, "M1" to SampleData.M1)) {
            RouteBadge(n, col, width = 34.dp + TtTheme.dot * 2)
        }
        RouteBadge("333", SampleData.BUS, style = BadgeStyle.COMPACT)
    }
    SectionLabel("Status language · shape, word, then colour")
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusLine(statusPresentation(DepartureStatus.ON_TIME))
            StatusLine(statusPresentation(DepartureStatus.LATE, 240))
            StatusLine(statusPresentation(DepartureStatus.EARLY, -60))
            StatusLine(statusPresentation(DepartureStatus.CANCELLED))
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusLine(statusPresentation(DepartureStatus.ADDED))
            StatusLine(statusPresentation(DepartureStatus.TIMETABLED))
            StatusLine(statusPresentation(DepartureStatus.ON_TIME, stale = true, dataAgeMs = 180_000))
            StatusLine(statusPresentation(DepartureStatus.ON_TIME), size = StatusSize.SMALL, suffix = "PLAT 5")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        PlatformSign("3", changed = false)
        PlatformSign("6", changed = true)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        CarCount(8)
        CarCount(4)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SeatPips(Occupancy.MANY_SEATS_AVAILABLE)
        SeatPips(Occupancy.FEW_SEATS_AVAILABLE)
        SeatPips(Occupancy.STANDING_ROOM_ONLY)
    }
    SectionLabel("Icons")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in listOf(PixelIcons.Train, PixelIcons.MapPin, PixelIcons.Warning, PixelIcons.Bell, PixelIcons.Sliders, PixelIcons.Info, PixelIcons.House, PixelIcons.Briefcase)) {
            PixelIcon(i)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in listOf(PixelIcons.Plus, PixelIcons.Minus, PixelIcons.Swap, PixelIcons.Locate, PixelIcons.ChevronLeft, PixelIcons.ChevronRight)) {
            PixelIcon(i, tint = c.textSecondary)
        }
        for (l in app.transittracker.ui.format.Lamp.entries) PixelIcon(PixelIcons.lamp(l), tint = c.info, size = 10.dp)
    }
    DashedDivider()
    DialogBox(
        "Delete this trip?",
        body = "Home to Work and its alarms will be removed from this phone.",
        actions = {
            PixelButton("KEEP", {}, Modifier.weight(1f))
            PixelButton("DELETE", {}, Modifier.weight(1f), ButtonKind.PRIMARY)
        },
    )
}

/**
 * The first trip card of the Main mockup, built only from design-system pieces: a check that the
 * pieces fit together at the mockup's sizes (not a real screen; screens belong to other modules).
 */
@Composable
fun SampleTripCard(modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val tz = TtTheme.time.timeZone
    val now = SampleData.NOW_MS
    PixelPanel(modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PixelText("Home", style = TtTheme.type.name17)
                    PixelIcon(PixelIcons.ChevronRight, tint = c.textSecondary)
                    PixelText("Work", style = TtTheme.type.name17)
                }
                Label("STRATHFIELD TO CENTRAL", size = LabelSize.L10, color = c.textSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RouteBadge("T9", SampleData.T9)
                PixelText("Gordon")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Countdown(app.transittracker.ui.format.countdown(now + 4 * 60_000 + 20_000, now, tz))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PixelText("07:38", color = c.textSecondary, strike = true)
                PixelText("07:42", style = TtTheme.type.title22, color = c.caution)
                Label("ARR 07:56", color = c.textSecondary)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StatusLine(statusPresentation(DepartureStatus.LATE, 240))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                app.transittracker.ui.sprites.WalkerSprite(app.transittracker.ui.sprites.WalkerPose.WALK)
                Label("LEAVE BY 07:39", size = LabelSize.L10, bold = true)
            }
            PlatformSign("3", changed = false)
        }
        app.transittracker.ui.strip.JourneyStrip(
            SampleData.calls(listOf("Lidcombe", "Rhodes", "Concord West", "Strathfield", "Burwood", "Central"), 1, 1.0, delayMin = 4),
            "S3", "S5", app.transittracker.model.Mode.TRAIN, SampleData.T9, DepartureStatus.LATE, SampleData.clock,
            lateTag = false,
        )
        Column {
            DashedDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelText("07:47", style = TtTheme.type.row15Strong)
                RouteBadge("T1", SampleData.T1, width = 34.dp + TtTheme.dot * 2)
                Column(Modifier.weight(1f)) {
                    PixelText("Berowra · Plat 2")
                    StatusLine(statusPresentation(DepartureStatus.ON_TIME), size = StatusSize.SMALL)
                }
                PixelText("9 min", style = TtTheme.type.row15Strong)
            }
        }
    }
}

/** A page with [SampleTripCard], to compare with the Main mockup. */
@Composable
fun SampleCardPage() = GalleryPage {
    SectionLabel("To work")
    SampleTripCard()
}
