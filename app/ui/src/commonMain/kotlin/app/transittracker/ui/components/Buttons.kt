package app.transittracker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.transittracker.ui.theme.TtColors
import app.transittracker.ui.theme.TtTheme

enum class ButtonKind {
    /** Text-coloured block with Screen-coloured label and a lip (one per screen, the main action). */
    PRIMARY,
    /** Raised block with a Divider border and a black lip. */
    SECONDARY,
    /** Panel block with a Divider border and no lip (back buttons, header buttons). */
    FLAT,
}

private class ButtonColors(val fill: Color, val border: Color, val content: Color, val lip: Color?)

private fun buttonColors(c: TtColors, kind: ButtonKind, enabled: Boolean): ButtonColors = when {
    !enabled -> ButtonColors(c.raised, c.divider, c.textSecondary, null)
    kind == ButtonKind.PRIMARY -> ButtonColors(c.text, c.text, c.screen, c.lipPrimary)
    kind == ButtonKind.SECONDARY -> ButtonColors(c.raised, c.divider, c.text, c.lipSecondary)
    else -> ButtonColors(c.panel, c.divider, c.text, null)
}

/**
 * The pixel button (System mockup): a block with a 1-dot border and, for PRIMARY and SECONDARY,
 * a hard 2-dot lip under it. Pressed, the face moves down 2 dots onto its lip (plan 3.5) and a
 * light haptic tick plays (if enabled in settings). [faceHeight] is the height inside the border;
 * the footprint is faceHeight + 2 border dots + 2 lip dots (FLAT: no lip, no lip space).
 * [content] gets the label colour.
 */
@Composable
fun PixelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.SECONDARY,
    enabled: Boolean = true,
    faceHeight: Dp = 48.dp,
    contentDescription: String? = null,
    content: @Composable (contentColor: Color) -> Unit,
) = PixelButtonImpl(onClick, modifier, kind, enabled, faceHeight, contentDescription, false, content)

/** [forcePressed] draws the pressed face (galleries). */
@Composable
internal fun PixelButtonImpl(
    onClick: () -> Unit,
    modifier: Modifier,
    kind: ButtonKind,
    enabled: Boolean,
    faceHeight: Dp,
    contentDescription: String?,
    forcePressed: Boolean,
    content: @Composable (contentColor: Color) -> Unit,
) {
    val dot = TtTheme.dot
    val colors = buttonColors(TtTheme.colors, kind, enabled)
    val source = remember { MutableInteractionSource() }
    val pressedNow by source.collectIsPressedAsState()
    val pressed = pressedNow || forcePressed
    val haptics = LocalHapticFeedback.current
    val hapticsOn = TtTheme.motion.haptics
    LaunchedEffect(pressedNow) {
        if (pressedNow && hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
    }
    // FLAT buttons have no lip: they keep their place and darken when pressed instead.
    val lipped = kind != ButtonKind.FLAT
    val down = pressed && enabled && lipped
    val fill = if (pressed && enabled && !lipped) TtTheme.colors.raised else colors.fill
    val b = dot.px(1)
    val lip = if (lipped) dot.px(2) else 0f
    val sem = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Box(
        modifier
            .height(faceHeight + dot * (if (lipped) 4 else 2))
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(sem)
            .drawBehind {
                val faceTop = if (down) lip else 0f
                val faceH = size.height - lip
                if (!down && colors.lip != null && lipped) {
                    drawRect(colors.lip, Offset(b, faceH), Size(size.width - 2 * b, lip))
                }
                drawRect(fill, Offset(b, faceTop + b), Size(size.width - 2 * b, faceH - 2 * b))
                drawPixelBorder(colors.border, b, top = faceTop, height = faceH)
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .padding(bottom = if (lipped) dot * 2 else 0.dp)
                .offset { IntOffset(0, if (down) lip.toInt() else 0) }
                .padding(horizontal = dot.dp + 6.dp),
            contentAlignment = Alignment.Center,
        ) { content(colors.content) }
    }
}

/**
 * Text button. Labels are Silkscreen Bold 12 in capitals; pass a Pixelify style (e.g.
 * `TtTheme.type.row15Strong`) for long labels.
 */
@Composable
fun PixelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.SECONDARY,
    enabled: Boolean = true,
    style: TextStyle = TtTheme.type.label12Bold,
) {
    PixelButton(onClick, modifier.widthIn(min = 48.dp), kind, enabled) { color ->
        PixelText(text, style = style, color = color, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/**
 * Square icon button: 44 dp inside the border, so 48 dp with it (the header "+" and back buttons).
 * [contentDescription] is required: icon-only buttons must have a label (plan 7).
 */
@Composable
fun PixelIconButton(
    icon: PixelIconData,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.FLAT,
    enabled: Boolean = true,
    iconSize: Dp? = null,
) {
    val dot = TtTheme.dot
    PixelButton(
        onClick, modifier.width(44.dp + dot * 2), kind, enabled,
        faceHeight = 44.dp, contentDescription = contentDescription,
    ) { color -> PixelIcon(icon, tint = color, size = iconSize) }
}
