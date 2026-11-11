package app.transittracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.theme.TtTheme
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A pixel icon: whole squares on a small grid (plan 2.6). The shapes are the SVG paths from the
 * mockups, kept in the same `Mx yhWvHh-Wz` form so they can be compared line for line.
 */
@Immutable
class PixelIconData(val width: Int, val height: Int, path: String) {
    /** Squares as (x, y, w, h) in grid cells. */
    val rects: List<IntArray> = parseRects(path)

    private companion object {
        val rect = Regex("""M(\d+) (\d+)h(\d+)v(\d+)h-\d+z""")
        fun parseRects(path: String): List<IntArray> =
            rect.findAll(path).map { m -> IntArray(4) { m.groupValues[it + 1].toInt() } }.toList()
                .also { require(it.isNotEmpty()) { "no squares in $path" } }
    }
}

/** Every icon in the mockups. 7 x 7: lamps and controls; 4 x 7: chevrons; 12 x 12: navigation and places. */
object PixelIcons {
    // Status lamps (7 x 7)
    val LampCircle = PixelIconData(7, 7, "M2 0h3v1h-3z M1 1h5v1h-5z M0 2h7v3h-7z M1 5h5v1h-5z M2 6h3v1h-3z")
    val LampTriangle = PixelIconData(7, 7, "M3 0h1v2h-1z M2 2h3v2h-3z M1 4h5v2h-5z M0 6h7v1h-7z")
    val LampCross = PixelIconData(7, 7, "M0 0h2v1h-2z M5 0h2v1h-2z M0 1h3v1h-3z M4 1h3v1h-3z M1 2h5v1h-5z M2 3h3v1h-3z M1 4h5v1h-5z M0 5h3v1h-3z M4 5h3v1h-3z M0 6h2v1h-2z M5 6h2v1h-2z")
    val LampPlus = PixelIconData(7, 7, "M2 0h3v2h-3z M0 2h7v3h-7z M2 5h3v2h-3z")
    val LampRing = PixelIconData(7, 7, "M2 0h3v1h-3z M1 1h1v1h-1z M5 1h1v1h-1z M0 2h1v3h-1z M6 2h1v3h-1z M1 5h1v1h-1z M5 5h1v1h-1z M2 6h3v1h-3z")

    // Controls (7 x 7, 4 x 7)
    val Plus = LampPlus
    val Minus = PixelIconData(7, 7, "M0 2h7v3h-7z")
    val Swap = PixelIconData(7, 7, "M1 0h1v7h-1z M0 1h3v1h-3z M5 0h1v7h-1z M4 5h3v1h-3z")
    val Locate = PixelIconData(7, 7, "M2 0h3v1h-3z M1 1h1v1h-1z M5 1h1v1h-1z M0 2h1v3h-1z M6 2h1v3h-1z M1 5h1v1h-1z M5 5h1v1h-1z M2 6h3v1h-3z M3 3h1v1h-1z")
    val ChevronRight = PixelIconData(4, 7, "M0 0h1v7h-1z M1 1h1v5h-1z M2 2h1v3h-1z M3 3h1v1h-1z")
    val ChevronLeft = PixelIconData(4, 7, "M3 0h1v7h-1z M2 1h1v5h-1z M1 2h1v3h-1z M0 3h1v1h-1z")

    // Navigation (12 x 12)
    val Train = PixelIconData(12, 12, "M2 0h8v1h-8z M1 1h10v1h-10z M1 2h1v3h-1z M10 2h1v3h-1z M1 5h10v2h-10z M1 7h2v1h-2z M4 7h4v1h-4z M9 7h2v1h-2z M1 8h10v1h-10z M2 9h8v1h-8z M2 10h2v1h-2z M8 10h2v1h-2z M1 11h2v1h-2z M9 11h2v1h-2z")
    val MapPin = PixelIconData(12, 12, "M3 0h6v1h-6z M2 1h8v1h-8z M1 2h3v1h-3z M8 2h3v1h-3z M1 3h2v2h-2z M9 3h2v2h-2z M1 5h3v1h-3z M8 5h3v1h-3z M2 6h8v1h-8z M3 7h6v1h-6z M4 8h4v2h-4z M5 10h2v2h-2z")
    val Warning = PixelIconData(12, 12, "M5 0h2v2h-2z M4 2h4v1h-4z M4 3h1v1h-1z M7 3h1v1h-1z M3 4h2v2h-2z M7 4h2v2h-2z M2 6h3v1h-3z M7 6h3v1h-3z M2 7h8v1h-8z M1 8h4v1h-4z M7 8h4v1h-4z M1 9h10v1h-10z M0 10h12v2h-12z")
    val Bell = PixelIconData(12, 12, "M5 0h2v1h-2z M4 1h4v1h-4z M3 2h6v1h-6z M2 3h8v4h-8z M1 7h10v1h-10z M0 8h12v2h-12z M4 11h4v1h-4z")
    val Sliders = PixelIconData(12, 12, "M2 1h2v3h-2z M0 2h12v1h-12z M7 5h2v3h-2z M0 6h12v1h-12z M4 9h2v3h-2z M0 10h12v1h-12z")
    val Info = PixelIconData(12, 12, "M0 0h12v2h-12z M0 10h12v2h-12z M0 2h2v8h-2z M10 2h2v8h-2z M5 3h2v2h-2z M5 6h2v3h-2z")

    // Places (12 x 12)
    val House = PixelIconData(12, 12, "M5 0h2v1h-2z M4 1h4v1h-4z M3 2h6v1h-6z M2 3h8v1h-8z M1 4h10v1h-10z M0 5h12v1h-12z M1 6h10v2h-10z M1 8h4v4h-4z M7 8h4v4h-4z")
    val Briefcase = PixelIconData(12, 12, "M4 1h4v1h-4z M4 2h1v1h-1z M7 2h1v1h-1z M0 3h12v3h-12z M0 6h5v1h-5z M7 6h5v1h-5z M0 7h12v4h-12z")
    val Pin = MapPin

    /** The lamp shape for a status [lamp]. */
    fun lamp(lamp: Lamp): PixelIconData = when (lamp) {
        Lamp.CIRCLE -> LampCircle
        Lamp.TRIANGLE -> LampTriangle
        Lamp.CROSS -> LampCross
        Lamp.PLUS -> LampPlus
        Lamp.RING -> LampRing
    }
}

/**
 * Draws [icon] in [tint]. [size] is the nominal width (mockup px = dp); each grid cell is snapped
 * to a whole number of device pixels, so the drawn size can differ from [size] by a pixel or two.
 * The default makes one cell one dot. Decorative unless [contentDescription] is given.
 */
@Composable
fun PixelIcon(
    icon: PixelIconData,
    modifier: Modifier = Modifier,
    tint: Color = TtTheme.colors.text,
    size: Dp? = null,
    contentDescription: String? = null,
) {
    val density = LocalDensity.current
    val cellPx = if (size == null) TtTheme.dot.px.toFloat()
    else max(1, (with(density) { size.toPx() } / icon.width).roundToInt()).toFloat()
    val w = with(density) { (cellPx * icon.width).toDp() }
    val h = with(density) { (cellPx * icon.height).toDp() }
    val sem = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Canvas(modifier.then(sem).size(w, h)) { drawPixelIcon(icon, tint, cellPx) }
}

/** Draws [icon] with its top-left at ([left], [top]); [cellPx] device pixels per grid cell. */
fun DrawScope.drawPixelIcon(icon: PixelIconData, tint: Color, cellPx: Float, left: Float = 0f, top: Float = 0f) {
    for (r in icon.rects) {
        drawRect(tint, Offset(left + r[0] * cellPx, top + r[1] * cellPx), Size(r[2] * cellPx, r[3] * cellPx))
    }
}
