package app.transittracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.theme.TtTheme

/**
 * The header of a pushed page: a 48 dp back button, the title (Pixelify 20, a heading) and
 * optional [actions] on the right. It is flap row 0.
 */
@Composable
fun PageHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().flapRow(0).padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) PixelIconButton(PixelIcons.ChevronLeft, "Back", onBack)
        PixelText(title, Modifier.weight(1f).semantics { heading() }, TtTheme.type.title20, maxLines = 1)
        if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * A page that is still to be built: its title, a back button and one line saying so. Used by the
 * placeholder screens until their owners replace them.
 */
@Composable
fun PlaceholderPage(title: String, onBack: (() -> Unit)?, note: String = "This screen is still to be built.") {
    Column(Modifier.fillMaxSize().background(TtTheme.colors.screen)) {
        PageHeader(title, onBack)
        Box(Modifier.fillMaxWidth().flapRow(1).padding(horizontal = 16.dp, vertical = 8.dp)) {
            PixelText(note, style = TtTheme.type.reading15, color = TtTheme.colors.textSecondary)
        }
    }
}
