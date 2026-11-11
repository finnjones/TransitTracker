package app.transittracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import app.transittracker.ui.theme.TtTheme

/**
 * Text in the design system. [style] is one of `TtTheme.type.*`; [color] defaults to Text.
 * [strike] draws the line-through used for timetabled times and cancelled rows.
 */
@Composable
fun PixelText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TtTheme.type.row15,
    color: Color = TtTheme.colors.text,
    strike: Boolean = false,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    var s = style.copy(color = color)
    if (strike) s = s.copy(textDecoration = TextDecoration.LineThrough)
    if (textAlign != null) s = s.copy(textAlign = textAlign)
    BasicText(text, modifier, s, maxLines = maxLines, overflow = overflow, softWrap = maxLines != 1)
}

/** Silkscreen label (status words, small captions). [bold] for status words and button labels. */
@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TtTheme.colors.text,
    bold: Boolean = false,
    size: LabelSize = LabelSize.L11,
) {
    val t = TtTheme.type
    val style = when (size) {
        LabelSize.L10 -> if (bold) t.label10Bold else t.label10
        LabelSize.L11 -> if (bold) t.label11Bold else t.label11
        LabelSize.L12 -> t.label12Bold
        LabelSize.L13 -> t.label13Bold
    }
    PixelText(text, modifier, style, color, maxLines = 1)
}

/** Silkscreen sizes 10 / 11 / 12 / 13 sp (12 and 13 are bold only). */
enum class LabelSize { L10, L11, L12, L13 }

/** Section heading, e.g. "TO WORK": Silkscreen 11, tracked, in secondary text. Marked as a heading. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    PixelText(
        text.uppercase(),
        modifier.semantics { heading() },
        TtTheme.type.section,
        TtTheme.colors.textSecondary,
        maxLines = 1,
    )
}

/** Doto numerals (clock, time pickers). [style] is one of the `digits*` styles. */
@Composable
fun Digits(text: String, modifier: Modifier = Modifier, style: TextStyle = TtTheme.type.digits30, color: Color = TtTheme.colors.text) {
    PixelText(text, modifier, style, color, maxLines = 1)
}

/** The dashed rule between rows: 1 dot high, Divider colour, full width. */
@Composable
fun DashedDivider(modifier: Modifier = Modifier, color: Color = TtTheme.colors.divider) {
    val dot = TtTheme.dot
    Canvas(modifier.fillMaxWidth().height(dot.dp)) { drawDashes(color, dot.px.toFloat(), 0f) }
}

/** A solid 1-dot rule (header and bottom-bar edges). */
@Composable
fun SolidDivider(modifier: Modifier = Modifier, color: Color = TtTheme.colors.divider) {
    val dot = TtTheme.dot
    Canvas(modifier.fillMaxWidth().height(dot.dp)) { drawRect(color) }
}
