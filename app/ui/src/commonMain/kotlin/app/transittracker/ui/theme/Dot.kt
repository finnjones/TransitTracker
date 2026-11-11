package app.transittracker.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The dot (plan 2.3): one art pixel, nominally 2 dp, snapped to a whole number of device pixels
 * so sprites and borders are always sharp. At 2.625x density a dot is 5 px, not 5.25.
 *
 * Use `dot * n` for any size that is part of the pixel art (borders, lamps, pips, sprites):
 * the result is an exact multiple of [px] device pixels.
 */
@Immutable
class Dot(
    /** Device pixels per dot (at least 1). */
    val px: Int,
    private val density: Float,
) {
    /** One dot in dp (2 dp before snapping). */
    val dp: Dp get() = (px / density).dp

    operator fun times(n: Int): Dp = (px * n / density).dp

    /** [n] dots in device pixels. */
    fun px(n: Int): Float = (px * n).toFloat()

    /** Rounds a length in device pixels down to a whole number of dots. */
    fun snapPx(lengthPx: Float): Float = kotlin.math.floor(lengthPx / px) * px

    override fun equals(other: Any?) = other is Dot && other.px == px && other.density == density
    override fun hashCode() = px * 31 + density.hashCode()
    override fun toString() = "Dot(${px}px at ${density}x)"

    companion object {
        /** The dot for a screen [density] (device pixels per dp). */
        fun forDensity(density: Float): Dot = Dot(max(1, (2f * density).roundToInt()), density)
    }
}

/** Spacing scale from the System mockup ("SPACING 4 8 12 16"), plus the 48 dp touch target. */
@Immutable
data class TtSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    /** Side gutter of every screen. */
    val gutter: Dp = 16.dp,
    /** Minimum touch target (plan 2.3). */
    val touch: Dp = 48.dp,
)
