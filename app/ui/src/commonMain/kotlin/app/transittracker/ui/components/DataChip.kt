package app.transittracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.format.ageSpoken
import app.transittracker.ui.format.ageWords
import app.transittracker.ui.theme.ColorRole
import app.transittracker.ui.theme.TtTheme

/** What the data chip in a page header says (plan 2.7). */
enum class DataChipKind { LIVE, STALE, TIMETABLE, OFFLINE }

/**
 * The framed data chip of a page header: "LIVE 12S" (or "LIVE NOW" for the first few seconds),
 * STALE, TIMETABLE, OFFLINE. Shape, word and colour together; read as one sentence.
 * [ageMs] is shown for LIVE only.
 */
@Composable
fun DataChipBox(kind: DataChipKind, ageMs: Long? = null, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    val age = ageMs?.takeIf { kind == DataChipKind.LIVE }
    val (lamp, role, word) = when (kind) {
        DataChipKind.LIVE -> Triple(Lamp.CIRCLE, ColorRole.GO, "LIVE")
        DataChipKind.STALE -> Triple(Lamp.RING, ColorRole.SECONDARY, "STALE")
        DataChipKind.TIMETABLE -> Triple(Lamp.RING, ColorRole.INFO, "TIMETABLE")
        DataChipKind.OFFLINE -> Triple(Lamp.RING, ColorRole.INFO, "OFFLINE")
    }
    val spoken = when (kind) {
        DataChipKind.LIVE -> "Live data" + (age?.let { ", updated ${ageSpoken(it)} ago" } ?: "")
        DataChipKind.STALE -> "Live data is stale"
        DataChipKind.TIMETABLE -> "Timetable only, no live data"
        DataChipKind.OFFLINE -> "Offline, showing the timetable"
    }
    Row(
        modifier
            .clearAndSetSemantics { contentDescription = spoken }
            .pixelFill(c.panel)
            .pixelBorder(c.divider)
            .padding(horizontal = dot.dp + 8.dp, vertical = dot.dp + 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusLamp(lamp, c.of(role), size = 12.dp)
        Label(word, color = c.of(role), bold = true)
        if (age != null) Label(if (age < 5_000) "NOW" else ageWords(age), color = c.textSecondary)
    }
}
