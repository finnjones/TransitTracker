package app.transittracker.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import app.transittracker.ui.theme.TtTheme

/**
 * The pixel border (System mockup): four bars of [dots] dots, one per side, with the corner
 * squares left out so the box reads as drawn on a grid. The bars sit inside the bounds, so add
 * the border width to your padding ([PixelPanel] and the controls already do).
 */
@Composable
fun Modifier.pixelBorder(color: Color, dots: Int = 1): Modifier {
    val w = TtTheme.dot.px(dots)
    return drawBehind { drawPixelBorder(color, w) }
}

/** Fills the area inside a [pixelBorder] (everything but the bars and missing corners). */
@Composable
fun Modifier.pixelFill(color: Color, dots: Int = 1): Modifier {
    val w = TtTheme.dot.px(dots)
    return drawBehind { drawRect(color, Offset(w, w), Size(size.width - 2 * w, size.height - 2 * w)) }
}

fun DrawScope.drawPixelBorder(color: Color, w: Float, left: Float = 0f, top: Float = 0f, width: Float = size.width, height: Float = size.height) {
    drawRect(color, Offset(left + w, top), Size(width - 2 * w, w))
    drawRect(color, Offset(left + w, top + height - w), Size(width - 2 * w, w))
    drawRect(color, Offset(left, top + w), Size(w, height - 2 * w))
    drawRect(color, Offset(left + width - w, top + w), Size(w, height - 2 * w))
}

/**
 * A dashed rule: [on] dots of colour, [off] dots of gap, [thick] dots high, starting with a dash
 * at the left edge. Whole dots only, so the pattern is crisp.
 */
fun DrawScope.drawDashes(color: Color, dotPx: Float, top: Float, left: Float = 0f, width: Float = size.width, on: Int = 3, off: Int = 2, thick: Int = 1) {
    val step = (on + off) * dotPx
    var x = left
    val end = left + width
    while (x < end) {
        val w = minOf(on * dotPx, end - x)
        drawRect(color, Offset(x, top), Size(w, thick * dotPx))
        x += step
    }
}
