package app.transittracker.ui.format

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Used when a route has no colour in the timetable. */
val DefaultLineColor = Color(0xFFA3A092)

/** Parses a GTFS colour (`RRGGBB`, optional '#'); null when missing or malformed. */
fun parseLineColor(hex: String?): Color? {
    val h = hex?.trim()?.removePrefix("#") ?: return null
    if (h.length != 6) return null
    val v = h.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or v)
}

/** WCAG relative luminance of an opaque colour. */
fun relativeLuminance(c: Color): Double {
    fun ch(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
}

/** WCAG contrast ratio between two opaque colours (1..21). */
fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/**
 * Badge text colour for a line colour (plan 2.4): the feed's own text colour only when it reaches
 * 4.5 : 1, otherwise black or white, whichever contrasts better.
 */
fun badgeTextColor(lineColor: Color, feedTextColor: Color? = null): Color {
    if (feedTextColor != null && contrastRatio(lineColor, feedTextColor) >= 4.5) return feedTextColor
    return if (contrastRatio(lineColor, Color.Black) >= contrastRatio(lineColor, Color.White)) Color.Black else Color.White
}
