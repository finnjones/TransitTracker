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
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.StatusLamp
import app.transittracker.ui.components.pixelBorder
import app.transittracker.ui.components.pixelFill
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.theme.ColorRole
import app.transittracker.ui.theme.TtTheme

/** The header data chip (plan 2.7): LIVE · 8S, STALE, TIMETABLE, OFFLINE. */
@Composable
internal fun MapChip(chip: TrackChip, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    data class Look(val lamp: Lamp, val role: ColorRole, val word: String, val extra: String?, val spoken: String)
    val look = when (chip) {
        is TrackChip.Live -> Look(
            Lamp.CIRCLE, ColorRole.GO, "LIVE", chip.ageMs?.let { ageWords(it) },
            "Live data" + (chip.ageMs?.let { ", updated ${ageSpoken(it)} ago" } ?: ""),
        )
        TrackChip.Stale -> Look(Lamp.RING, ColorRole.SECONDARY, "STALE", null, "Live data is stale")
        TrackChip.Timetable -> Look(Lamp.RING, ColorRole.INFO, "TIMETABLE", null, "Timetable only, no live data")
        TrackChip.Offline -> Look(Lamp.RING, ColorRole.INFO, "OFFLINE", null, "Offline, showing the timetable")
    }
    Row(
        modifier
            .clearAndSetSemantics { contentDescription = look.spoken }
            .pixelFill(c.panel)
            .pixelBorder(c.divider)
            .padding(horizontal = dot.dp + 8.dp, vertical = dot.dp + 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusLamp(look.lamp, c.of(look.role), size = 12.dp)
        Label(look.word, color = c.of(look.role), bold = true)
        if (look.extra != null) Label(look.extra, color = c.textSecondary)
    }
}

internal fun ageWords(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "${s}S" else "${s / 60} MIN"
}

internal fun ageSpoken(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "$s seconds" else "${s / 60} minutes"
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
