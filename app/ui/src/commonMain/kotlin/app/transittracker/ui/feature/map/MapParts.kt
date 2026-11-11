package app.transittracker.ui.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.transittracker.ui.components.DataChipBox
import app.transittracker.ui.components.DataChipKind
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.StatusLamp
import app.transittracker.ui.components.pixelBorder
import app.transittracker.ui.components.pixelFill
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.theme.ColorRole
import app.transittracker.ui.theme.TtTheme

/** The header data chip (plan 2.7): the shared one. */
@Composable
internal fun MapChip(chip: TrackChip, modifier: Modifier = Modifier) = when (chip) {
    is TrackChip.Live -> DataChipBox(DataChipKind.LIVE, chip.ageMs, modifier)
    TrackChip.Stale -> DataChipBox(DataChipKind.STALE, modifier = modifier)
    TrackChip.Timetable -> DataChipBox(DataChipKind.TIMETABLE, modifier = modifier)
    TrackChip.Offline -> DataChipBox(DataChipKind.OFFLINE, modifier = modifier)
}

/** A one-line notice over the map ("Position unavailable"), with its lamp. */
@Composable
internal fun MapNotice(text: String, role: ColorRole, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val lamp = when (role) {
        ColorRole.STOP -> Lamp.CROSS
        ColorRole.CAUTION -> Lamp.TRIANGLE
        else -> Lamp.RING
    }
    PixelPanel(modifier.clearAndSetSemantics { contentDescription = text }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusLamp(lamp, c.of(role))
            PixelText(text, style = TtTheme.type.reading15, color = c.textReading)
        }
    }
}
