package app.transittracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.transittracker.ui.theme.TtTheme

/**
 * A card (System and Main mockups): Panel fill with the four-bar pixel border. The border is
 * drawn inside the bounds, and [contentPadding] is added inside the border (12 dp, as the cards).
 * [selected] swaps the border to Text, as for the recommended journey in the options list.
 */
@Composable
fun PixelPanel(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    borderColor: Color = if (selected) TtTheme.colors.text else TtTheme.colors.divider,
    background: Color = TtTheme.colors.panel,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    verticalSpacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dot = TtTheme.dot
    Column(
        modifier
            .pixelFill(background)
            .pixelBorder(borderColor)
            .padding(dot.dp)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = content,
    )
}

/**
 * A dialog box: a Panel with a Text border, a title, optional body text and a row of buttons
 * (usually a [PixelButton] pair, primary last). Put it in a [DialogScrim] to show it over a page.
 */
@Composable
fun DialogBox(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    PixelPanel(
        modifier.semantics { paneTitle = title },
        borderColor = TtTheme.colors.text,
        contentPadding = PaddingValues(16.dp),
        verticalSpacing = 12.dp,
    ) {
        PixelText(title, style = TtTheme.type.title20)
        if (body != null) PixelText(body, style = TtTheme.type.reading15, color = TtTheme.colors.textReading)
        content?.invoke(this)
        if (actions != null) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), content = actions)
        }
    }
}

/**
 * Covers the page with a hard (not blurred) Screen-coloured veil and centres [content]. Tapping
 * the veil calls [onDismiss] when given.
 */
@Composable
fun DialogScrim(
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val veil = TtTheme.colors.screen.copy(alpha = 0.85f)
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .fillMaxSize()
            .pixelFill(veil, dots = 0)
            .then(if (onDismiss != null) Modifier.clickable(source, null, onClick = onDismiss) else Modifier)
            .padding(TtTheme.spacing.gutter),
        contentAlignment = Alignment.Center,
    ) {
        // Taps on the dialog itself must not reach the veil.
        Box(Modifier.widthIn(max = 420.dp).pointerInput(Unit) { detectTapGestures { } }) { content() }
    }
}
