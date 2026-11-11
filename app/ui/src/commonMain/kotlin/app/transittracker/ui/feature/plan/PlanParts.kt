package app.transittracker.ui.feature.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIconButton
import app.transittracker.ui.components.PixelIconData
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.StatusLamp
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.theme.TtTheme

/** The pixel icon of a journey end. */
fun endIcon(icon: EndIcon): PixelIconData = when (icon) {
    EndIcon.HOME -> PixelIcons.House
    EndIcon.WORK -> PixelIcons.Briefcase
    EndIcon.PIN -> PixelIcons.MapPin
    EndIcon.STOP -> PixelIcons.Train
    EndIcon.ADDRESS -> PixelIcons.MapPin
    EndIcon.HERE -> PixelIcons.Locate
}

/**
 * The header of the options and journey pages (Options and Journey mockups): back button, then
 * "Home ▸ Bondi Beach" in Pixelify 20 over a Silkscreen 10 line. Flap row 0.
 */
@Composable
fun JourneyHeader(fromName: String, toName: String, subtitle: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Row(
        modifier.fillMaxWidth().flapRow(0).padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelIconButton(PixelIcons.ChevronLeft, "Back", onBack)
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) { heading() },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelText(fromName, Modifier.weight(1f, fill = false), TtTheme.type.title20, maxLines = 1, overflow = TextOverflow.Ellipsis)
                PixelIcon(PixelIcons.ChevronRight, tint = c.textSecondary)
                PixelText(toName, Modifier.weight(1f, fill = false), TtTheme.type.title20, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PixelText(subtitle, style = TtTheme.type.section.copy(fontSize = TtTheme.type.label10.fontSize), color = c.textSecondary, maxLines = 1)
        }
    }
}

/** A notice panel: lamp and a plain sentence, with an optional action button under it. */
@Composable
fun NoticePanel(
    text: String,
    lamp: Lamp,
    color: Color,
    modifier: Modifier = Modifier,
    title: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = TtTheme.colors
    PixelPanel(modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalSpacing = 10.dp) {
        Row(
            Modifier.clearAndSetSemantics { contentDescription = listOfNotNull(title, text).joinToString(". ") },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            StatusLamp(lamp, color, Modifier.padding(top = 4.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (title != null) PixelText(title, style = TtTheme.type.row15Strong, color = color)
                PixelText(text, style = TtTheme.type.reading15, color = c.textReading)
            }
        }
        if (action != null && onAction != null) {
            PixelButton(action, onAction, Modifier.fillMaxWidth(), ButtonKind.SECONDARY)
        }
    }
}

/** A tappable row read as one [sentence]. */
@Composable
fun Modifier.tapRow(sentence: String, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return clickable(source, null, onClick = onClick).clearAndSetSemantics {
        contentDescription = sentence
        role = Role.Button
        onClick { onClick(); true }
    }
}
