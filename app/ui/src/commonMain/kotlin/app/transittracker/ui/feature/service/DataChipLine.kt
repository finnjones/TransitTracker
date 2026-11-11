package app.transittracker.ui.feature.service

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.StatusLamp
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.format.ageSpoken
import app.transittracker.ui.format.ageWords
import app.transittracker.ui.format.liveAgeWords
import app.transittracker.ui.theme.TtTheme

/**
 * The data chip without a frame (Service mockup): lamp, "LIVE" and the age of the data
 * ("NOW", "12S AGO"); STALE and TIMETABLE use the Ring lamp (plan 2.7). Read as one sentence.
 */
@Composable
fun DataChipLine(kind: ChipKind, ageMs: Long?, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val (lamp, color, word) = when (kind) {
        ChipKind.LIVE -> Triple(Lamp.CIRCLE, c.go, "LIVE")
        ChipKind.STALE -> Triple(Lamp.RING, c.textSecondary, "STALE")
        ChipKind.TIMETABLE -> Triple(Lamp.RING, c.info, "TIMETABLE")
        ChipKind.OFFLINE -> Triple(Lamp.RING, c.info, "OFFLINE")
    }
    val age = ageMs?.takeIf { kind == ChipKind.LIVE || kind == ChipKind.STALE }
    val spoken = when (kind) {
        ChipKind.LIVE -> "Live data" + (age?.let { ", updated ${ageSpoken(it)} ago" } ?: "")
        ChipKind.STALE -> "Live data is stale" + (age?.let { ", ${ageSpoken(it)} old" } ?: "")
        ChipKind.TIMETABLE -> "Timetable only, no live data"
        ChipKind.OFFLINE -> "Offline, showing the timetable"
    }
    Row(
        modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusLamp(lamp, color, size = 10.dp)
        Label(word, color = color, bold = true)
        if (age != null) Label(if (kind == ChipKind.STALE) ageWords(age) + " OLD" else liveAgeWords(age), color = c.textSecondary)
    }
}
