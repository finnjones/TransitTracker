package app.transittracker.ui.feature.places

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.DialogBox
import app.transittracker.ui.components.DialogScrim
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIconButton
import app.transittracker.ui.components.PixelIconData
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.RouteBadge
import app.transittracker.ui.components.StatusLamp
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.places.PlaceIcon
import app.transittracker.ui.theme.TtTheme

/*
 * Pieces shared by the four screens of the places feature: the header, the problem dialog, the
 * place icon and the line badges.
 */

/** The icon of a saved place (plan 2.6). */
internal fun PlaceIcon.pixelIcon(): PixelIconData = when (this) {
    PlaceIcon.HOME -> PixelIcons.House
    PlaceIcon.WORK -> PixelIcons.Briefcase
    PlaceIcon.PIN -> PixelIcons.Pin
}

internal fun PlaceIcon.words(): String = when (this) {
    PlaceIcon.HOME -> "House"
    PlaceIcon.WORK -> "Briefcase"
    PlaceIcon.PIN -> "Pin"
}

/** The header of the pushed pages in this feature: back, a title, an optional small line under it, optional actions. */
@Composable
internal fun PlacesHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().flapRow(0).padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelIconButton(PixelIcons.ChevronLeft, "Back", onBack)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PixelText(title, Modifier.semantics { heading() }, TtTheme.type.title20, maxLines = 1)
            if (subtitle != null) Label(subtitle, color = TtTheme.colors.textSecondary, size = LabelSize.L10)
        }
        if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** The square holding a place's icon (Places mockup): Text fill, Screen ink. */
@Composable
internal fun PlaceIconTile(icon: PlaceIcon, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Box(modifier.size(40.dp).background(c.text), contentAlignment = Alignment.Center) {
        PixelIcon(icon.pixelIcon(), tint = c.screen, size = 24.dp)
    }
}

/** The line badges of a stop or leg; more than [LINE_BADGES_SHOWN] are written as "7 LINES". */
@Composable
internal fun LineBadges(lines: List<LineBadge>, modifier: Modifier = Modifier, dim: Boolean = false) {
    if (lines.isEmpty()) return
    val alpha = if (dim) 0.5f else 1f
    if (lines.size > LINE_BADGES_SHOWN) {
        Label("${lines.size} LINES", modifier, color = TtTheme.colors.textSecondary, size = LabelSize.L10)
    } else {
        Row(modifier.alpha(alpha), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (l in lines) RouteBadge(l.name, l.colorHex, l.textColorHex, width = 34.dp)
        }
    }
}

/** "T1, T2 and T9" for the screen reader. */
internal fun spokenLines(lines: List<LineBadge>): String = when {
    lines.isEmpty() -> ""
    lines.size > LINE_BADGES_SHOWN -> "${lines.size} lines"
    lines.size == 1 -> lines[0].name
    else -> lines.dropLast(1).joinToString(", ") { it.name } + " and " + lines.last().name
}

/** The dialog for a failed action (plan 6): a plain sentence, RETRY or CLOSE. */
@Composable
internal fun ProblemDialog(problem: Problem, onClose: () -> Unit, onRetry: () -> Unit) {
    DialogScrim(onClose) {
        DialogBox(
            problem.title,
            body = problem.words,
            actions = {
                PixelButton("CLOSE", onClose, Modifier.weight(1f))
                PixelButton("RETRY", onRetry, Modifier.weight(1f), kind = ButtonKind.PRIMARY)
            },
        )
    }
}

/** A sentence beside a status lamp, as the banners of the Notify tab. */
@Composable
internal fun NoteLine(text: String, lamp: Lamp, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusLamp(lamp, color)
        PixelText(text, Modifier.weight(1f), TtTheme.type.reading15, TtTheme.colors.textReading)
    }
}

/** Clickable without a ripple, as a button for the screen reader. */
@Composable
internal fun Modifier.tappable(label: String, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.clickable(source, null, role = Role.Button, onClickLabel = label, onClick = onClick)
}
