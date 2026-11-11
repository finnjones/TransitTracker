package app.transittracker.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.transittracker.ui.res.Res
import app.transittracker.ui.res.doto_black
import app.transittracker.ui.res.jersey15_regular
import app.transittracker.ui.res.pixelify_sans_400
import app.transittracker.ui.res.pixelify_sans_600
import app.transittracker.ui.res.pixelify_sans_700
import app.transittracker.ui.res.silkscreen_bold
import app.transittracker.ui.res.silkscreen_regular
import org.jetbrains.compose.resources.Font

/** The faces (plan 2.5). All SIL OFL; licences in composeResources/files/licenses. */
@Immutable
data class TtFonts(
    /** Doto Black: dot-matrix numerals (countdowns, the clock, time pickers). */
    val doto: FontFamily,
    /** Pixelify Sans 400 / 600 / 700: station names, rows, long button labels, reading text. */
    val pixelify: FontFamily,
    /** Silkscreen 400 / 700: small capital labels, status words, button labels. */
    val silkscreen: FontFamily,
    /**
     * Jersey 15: every numeral that is not a dot-matrix countdown. Pixelify's 5 reads as an S and
     * Silkscreen Bold's 4 closes up, so [app.transittracker.ui.components.PixelText] sets digits
     * in this face whatever the surrounding style.
     */
    val numerals: FontFamily,
)

@Composable
fun rememberTtFonts(): TtFonts {
    val doto = Font(Res.font.doto_black, FontWeight.Black)
    val p4 = Font(Res.font.pixelify_sans_400, FontWeight.Normal)
    val p6 = Font(Res.font.pixelify_sans_600, FontWeight.SemiBold)
    val p7 = Font(Res.font.pixelify_sans_700, FontWeight.Bold)
    val s4 = Font(Res.font.silkscreen_regular, FontWeight.Normal)
    val s7 = Font(Res.font.silkscreen_bold, FontWeight.Bold)
    val n = Font(Res.font.jersey15_regular, FontWeight.Normal)
    return remember(doto, p4, p6, p7, s4, s7, n) {
        TtFonts(FontFamily(doto), FontFamily(p4, p6, p7), FontFamily(s4, s7), FontFamily(n))
    }
}

/**
 * Type styles (plan 2.5). Sizes are in sp so the system font size is honoured. Use these as they
 * are; if a screen genuinely needs another size, `copy(fontSize = …)` from the nearest style and
 * stay inside the plan's sizes (Doto 30/44/60/76, Pixelify 15/17/20/22, Silkscreen 10–13).
 * No style sets a colour: components choose it from [TtColors].
 */
@Immutable
data class TtType(
    // Doto Black: countdowns and clocks.
    val digits76: TextStyle,
    val digits60: TextStyle,
    val digits44: TextStyle,
    val digits30: TextStyle,
    // Pixelify Sans: names, rows, reading text.
    val title22: TextStyle,
    val title20: TextStyle,
    val name17: TextStyle,
    val row15: TextStyle,
    val row15Strong: TextStyle,
    val reading15: TextStyle,
    // Silkscreen: labels, status words, buttons.
    val label10: TextStyle,
    val label10Bold: TextStyle,
    val label11: TextStyle,
    val label11Bold: TextStyle,
    val label12Bold: TextStyle,
    val label13Bold: TextStyle,
    /** Section headings ("TO WORK"): Silkscreen 11 with 1 sp tracking, drawn in secondary text. */
    val section: TextStyle,
    /** The face for numerals inside Pixelify and Silkscreen text; applied by `PixelText`, not by screens. */
    val numerals: FontFamily,
)

private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(family: FontFamily, weight: FontWeight, size: TextUnit, line: TextUnit, tracking: TextUnit = 0.sp) =
    TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size,
        lineHeight = line,
        letterSpacing = tracking,
        lineHeightStyle = tight,
    )

fun ttType(fonts: TtFonts): TtType = TtType(
    digits76 = style(fonts.doto, FontWeight.Black, 76.sp, 64.sp),
    digits60 = style(fonts.doto, FontWeight.Black, 60.sp, 50.sp),
    digits44 = style(fonts.doto, FontWeight.Black, 44.sp, 38.sp),
    digits30 = style(fonts.doto, FontWeight.Black, 30.sp, 30.sp, 1.sp),
    title22 = style(fonts.pixelify, FontWeight.Bold, 22.sp, 24.sp),
    title20 = style(fonts.pixelify, FontWeight.Bold, 20.sp, 22.sp),
    name17 = style(fonts.pixelify, FontWeight.SemiBold, 17.sp, 22.sp),
    row15 = style(fonts.pixelify, FontWeight.Normal, 15.sp, 18.sp),
    row15Strong = style(fonts.pixelify, FontWeight.SemiBold, 15.sp, 18.sp),
    reading15 = style(fonts.pixelify, FontWeight.Normal, 15.sp, 22.sp),
    label10 = style(fonts.silkscreen, FontWeight.Normal, 10.sp, 12.sp),
    label10Bold = style(fonts.silkscreen, FontWeight.Bold, 10.sp, 12.sp),
    label11 = style(fonts.silkscreen, FontWeight.Normal, 11.sp, 14.sp),
    label11Bold = style(fonts.silkscreen, FontWeight.Bold, 11.sp, 14.sp),
    label12Bold = style(fonts.silkscreen, FontWeight.Bold, 12.sp, 14.sp),
    label13Bold = style(fonts.silkscreen, FontWeight.Bold, 13.sp, 16.sp),
    section = style(fonts.silkscreen, FontWeight.Normal, 11.sp, 12.sp, 1.sp),
    numerals = fonts.numerals,
)
