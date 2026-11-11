package app.transittracker.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Mode
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.SectionLabel
import app.transittracker.ui.sprites.Facing
import app.transittracker.ui.sprites.VehicleSprite
import app.transittracker.ui.sprites.VehicleState
import app.transittracker.ui.sprites.WalkerPose
import app.transittracker.ui.sprites.WalkerSprite
import app.transittracker.ui.strip.JourneyStrip
import app.transittracker.ui.strip.Leg
import app.transittracker.ui.strip.LegStrip
import app.transittracker.ui.theme.TtTheme

private val allStates: List<Pair<String, VehicleState>> = listOf(
    "MOVING" to VehicleState.Moving,
    "AT A STOP" to VehicleState.AtStop,
    "LATE" to VehicleState.Late(4),
    "TIMETABLE" to VehicleState.Ghost,
    "STALE" to VehicleState.Stale,
    "CANCELLED" to VehicleState.Cancelled,
)

private fun modeName(mode: Mode) = when (mode) {
    Mode.TRAIN -> "Train"
    Mode.METRO -> "Metro"
    Mode.LIGHT_RAIL -> "Light rail"
    Mode.BUS -> "Bus"
    Mode.COACH -> "Coach"
    Mode.FERRY -> "Ferry"
    Mode.OTHER -> "Other (unknown mode)"
}

/** Every state of one mode: two rows of three, each with its caption. */
@Composable
private fun ModeStates(mode: Mode) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PixelText(modeName(mode), style = TtTheme.type.name17)
        for (row in allStates.chunked(3)) {
            Row(Modifier.fillMaxWidth()) {
                for ((caption, state) in row) {
                    Column(Modifier.weight(1f).padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        VehicleSprite(mode, SampleData.livery(mode), state)
                        Label(caption, size = LabelSize.L10, color = TtTheme.colors.textSecondary)
                    }
                }
            }
        }
    }
}

/** Vehicles page 1: train, metro, light rail, bus in every state. */
@Composable
fun VehicleGalleryPage1() = GalleryPage {
    SectionLabel("Vehicles · every mode, every state")
    for (m in listOf(Mode.TRAIN, Mode.METRO, Mode.LIGHT_RAIL, Mode.BUS)) ModeStates(m)
}

/** Vehicles page 2: coach, ferry, the OTHER marker, facing, the walker and transition sprites. */
@Composable
fun VehicleGalleryPage2() = GalleryPage {
    for (m in listOf(Mode.COACH, Mode.FERRY, Mode.OTHER)) ModeStates(m)
    SectionLabel("Facing · walker stand, walk, run")
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
        VehicleSprite(Mode.BUS, SampleData.BUS, facing = Facing.RIGHT)
        VehicleSprite(Mode.BUS, SampleData.BUS, facing = Facing.LEFT)
        WalkerSprite(WalkerPose.STAND)
        WalkerSprite(WalkerPose.WALK)
        WalkerSprite(WalkerPose.RUN)
        WalkerSprite(WalkerPose.RUN, scale = 2)
    }
    SectionLabel("Page transition vehicles (scale 1)")
    VehicleSprite(Mode.TRAIN, SampleData.T9, long = true)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VehicleSprite(Mode.LIGHT_RAIL, SampleData.L1, long = true)
        VehicleSprite(Mode.TRAIN, SampleData.T1, scale = 2)
    }
}

@Composable
private fun StripCard(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(title, size = LabelSize.L10, color = TtTheme.colors.textSecondary)
        Box(Modifier.padding(top = 14.dp)) { content() }
    }
}

/** Journey strips for every ground and the main vehicle states. */
@Composable
fun JourneyStripGallery() = GalleryPage {
    val names = SampleData.westernLine
    val clock = SampleData.clock
    StripCard("RAIL · T9 · 4 MIN LATE, BETWEEN RHODES AND CONCORD WEST") {
        JourneyStrip(SampleData.calls(names, 3, 1.5, delayMin = 4), "S5", "S8", Mode.TRAIN, SampleData.T9, DepartureStatus.LATE, clock)
    }
    StripCard("RAIL · M1 · ON TIME, AT CONCORD WEST") {
        JourneyStrip(SampleData.calls(names, 3, 4.2), "S5", "S8", Mode.METRO, SampleData.M1, DepartureStatus.ON_TIME, clock)
    }
    StripCard("WIRE · L1 · ON TIME") {
        JourneyStrip(SampleData.calls(names, 2, 2.0), "S5", "S8", Mode.LIGHT_RAIL, SampleData.L1, DepartureStatus.ON_TIME, clock)
    }
    StripCard("ROAD · BUS 333 · ON TIME, FAR AWAY") {
        JourneyStrip(SampleData.calls(names, 0, 1.0), "S5", "S8", Mode.BUS, SampleData.BUS, DepartureStatus.ON_TIME, clock)
    }
    StripCard("ROAD · COACH · STALE") {
        JourneyStrip(SampleData.calls(names, 4, 1.0), "S5", "S8", Mode.COACH, SampleData.COACH, DepartureStatus.ON_TIME, clock, stale = true)
    }
    StripCard("WATER · F1 · TIMETABLE ONLY (GHOST)") {
        JourneyStrip(SampleData.calls(names, 3, 2.0, live = false), "S5", "S8", Mode.FERRY, SampleData.FERRY, DepartureStatus.TIMETABLED, clock)
    }
    StripCard("LINE · OTHER · PAST YOUR STOP") {
        JourneyStrip(SampleData.calls(names, 6, 2.0), "S5", "S8", Mode.OTHER, SampleData.OTHER, DepartureStatus.ON_TIME, clock)
    }
    StripCard("RAIL · T1 · CANCELLED") {
        JourneyStrip(SampleData.calls(names, 3, 2.0), "S5", "S8", Mode.TRAIN, SampleData.T1, DepartureStatus.CANCELLED, clock)
    }
}

/** Leg strips as in the options list: with sprites (recommended), and without. */
@Composable
fun LegStripGallery() = GalleryPage {
    SectionLabel("Leg strip · with sprites")
    PixelPanel(selected = true) {
        LegStrip(
            listOf(
                Leg.Walk(3), Leg.Ride(14, Mode.TRAIN, "T9", SampleData.T9), Leg.Walk(7),
                Leg.Ride(11, Mode.TRAIN, "T4", SampleData.T4), Leg.Walk(6),
                Leg.Ride(11, Mode.BUS, "333", SampleData.BUS), Leg.Walk(2),
            ),
            sprites = true,
        )
    }
    SectionLabel("Without sprites")
    PixelPanel {
        LegStrip(
            listOf(
                Leg.Walk(3), Leg.Ride(17, Mode.TRAIN, "T1", SampleData.T1), Leg.Walk(5),
                Leg.Ride(9, Mode.TRAIN, "T4", SampleData.T4), Leg.Walk(6),
                Leg.Ride(14, Mode.BUS, "380", SampleData.BUS), Leg.Walk(2),
            ),
        )
    }
    PixelPanel {
        LegStrip(listOf(Leg.Walk(3), Leg.Ride(14, Mode.TRAIN, "T9", SampleData.T9), Leg.Walk(7), Leg.Ride(11, Mode.TRAIN, "T4", SampleData.T4), Leg.Walk(31)))
    }
    SectionLabel("Other modes")
    PixelPanel {
        LegStrip(
            listOf(
                Leg.Walk(4), Leg.Ride(12, Mode.FERRY, "F1", SampleData.FERRY), Leg.Walk(3),
                Leg.Ride(10, Mode.LIGHT_RAIL, "L1", SampleData.L1), Leg.Walk(2), Leg.Ride(9, Mode.METRO, "M1", SampleData.M1),
            ),
            sprites = true,
        )
    }
    Box(Modifier.width(1.dp).height(1.dp))
}
