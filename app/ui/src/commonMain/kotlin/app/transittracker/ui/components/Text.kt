package app.transittracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import app.transittracker.ui.theme.TtTheme

/**
 * Text in the design system. [style] is one of `TtTheme.type.*`; [color] defaults to Text.
 * [strike] draws the line-through used for timetabled times and cancelled rows.
 *
 * Numerals are always legible: in Pixelify and Silkscreen styles every digit (and the `:` `.` `,`
 * inside a number) is set in the numeral face, sized to match the surrounding letters. Doto styles
 * are left alone. Screens do nothing for this; pass plain strings.
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
    val type = TtTheme.type
    val shown = if (style.fontFamily == type.digits30.fontFamily) {
        remember(text) { AnnotatedString(text) }
    } else {
        remember(text, type.numerals) { withNumerals(text, type.numerals) }
    }
    BasicText(text = shown, modifier = modifier, style = s, overflow = overflow, softWrap = maxLines != 1, maxLines = maxLines)
}

/** Jersey 15's digits are shorter than Pixelify's and Silkscreen's at the same size; this evens them out. */
private const val NUMERAL_SCALE = 1.15f

/** True for a character that belongs to a number: a digit, or `:` `.` `,` with a digit on both sides. */
internal fun isNumeralAt(text: String, i: Int): Boolean {
    val c = text[i]
    if (c in '0'..'9') return true
    if (c != ':' && c != '.' && c != ',') return false
    return i > 0 && i < text.length - 1 && text[i - 1] in '0'..'9' && text[i + 1] in '0'..'9'
}

/** [text] with every run of numerals set in [numerals]. */
internal fun withNumerals(text: String, numerals: FontFamily): AnnotatedString {
    val builder = AnnotatedString.Builder(text)
    val span = SpanStyle(fontFamily = numerals, fontWeight = FontWeight.Normal, fontSize = NUMERAL_SCALE.em)
    var i = 0
    while (i < text.length) {
        if (!isNumeralAt(text, i)) {
            i++
            continue
        }
        val start = i
        while (i < text.length && isNumeralAt(text, i)) i++
        builder.addStyle(span, start, i)
    }
    return builder.toAnnotatedString()
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
