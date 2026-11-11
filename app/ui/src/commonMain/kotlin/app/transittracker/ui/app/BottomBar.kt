package app.transittracker.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import app.transittracker.ui.components.CountBadge
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIconData
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.SolidDivider
import app.transittracker.ui.nav.Tab
import app.transittracker.ui.theme.TtTheme

private fun Tab.icon(): PixelIconData = when (this) {
    Tab.TRIPS -> PixelIcons.Train
    Tab.MAP -> PixelIcons.MapPin
    Tab.ALERTS -> PixelIcons.Warning
    Tab.NOTIFY -> PixelIcons.Bell
    Tab.SETTINGS -> PixelIcons.Sliders
}

/**
 * The bottom bar (Main mockup): five tabs with plain labels, 72 dp high on the Panel colour with
 * a 1-dot Divider rule on top. The selected tab is inverted (plan 2.4: selection is inversion).
 * [alertCount] > 0 puts a Caution count badge on ALERTS.
 */
@Composable
fun BottomBar(current: Tab, alertCount: Int, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    Column(modifier.fillMaxWidth().background(c.panel)) {
        SolidDivider()
        Row(Modifier.fillMaxWidth().height(72.dp - TtTheme.dot.dp)) {
            for (tab in Tab.entries) {
                val selected = tab == current
                val badge = if (tab == Tab.ALERTS && alertCount > 0) alertCount else 0
                val spoken = tab.label.lowercase().replaceFirstChar { it.uppercase() } +
                    (if (badge > 0) ", $badge active on your trips" else "")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (selected) c.text else c.panel)
                        .clickable(remember { MutableInteractionSource() }, null) { onSelect(tab) }
                        .clearAndSetSemantics {
                            contentDescription = spoken
                            role = Role.Tab
                            this.selected = selected
                            onClick { onSelect(tab); true }
                        },
                ) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically),
                    ) {
                        val ink = if (selected) c.screen else c.textSecondary
                        PixelIcon(tab.icon(), tint = ink)
                        PixelText(
                            tab.label,
                            style = if (selected) TtTheme.type.label10Bold else TtTheme.type.label10,
                            color = ink, maxLines = 1,
                        )
                    }
                    if (badge > 0) CountBadge(badge, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 14.dp))
                }
            }
        }
    }
}
