package app.transittracker.ui.strip

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.transittracker.model.Mode
import app.transittracker.ui.components.BadgeStyle
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.RouteBadge
import app.transittracker.ui.components.drawDashes
import app.transittracker.ui.sprites.SpriteSheet
import app.transittracker.ui.sprites.VehicleSprite
import app.transittracker.ui.sprites.WalkerPose
import app.transittracker.ui.sprites.WalkerSprite
import app.transittracker.ui.theme.TtTheme
import kotlin.math.floor
import kotlin.math.roundToInt

/** One part of a journey, for [LegStrip]. */
@Immutable
sealed interface Leg {
    val minutes: Int

    /** Walking, including changes between platforms. Drawn dotted. */
    data class Walk(override val minutes: Int) : Leg

    /** A ride: a bar in the line colour with a Text border, the line badge under it. */
    data class Ride(
        override val minutes: Int,
        val mode: Mode,
        val lineName: String,
        val lineColor: Color,
        val feedTextColor: Color? = null,
    ) : Leg
}

/** A leg's horizontal extent in device pixels. */
data class LegSpan(val left: Float, val width: Float)

/**
 * Splits [widthPx] among [legs] in proportion to their minutes, with a [gapPx] between legs and
 * every edge on a whole multiple of [dotPx]; each leg gets at least one dot. Pure, for tests.
 */
fun legSpans(legs: List<Leg>, widthPx: Float, gapPx: Float, dotPx: Float): List<LegSpan> {
    if (legs.isEmpty()) return emptyList()
    val total = legs.sumOf { it.minutes.coerceAtLeast(0) }.coerceAtLeast(1)
    val usable = widthPx - gapPx * (legs.size - 1)
    var cum = 0
    var x = 0f
    return legs.mapIndexed { i, leg ->
        cum += leg.minutes.coerceAtLeast(0)
        val end = if (i == legs.size - 1) usable else floor(usable * cum / total / dotPx) * dotPx
        val left = x + i * gapPx
        val w = (end - x).coerceAtLeast(dotPx)
        x = end
        LegSpan(left, w)
    }
}

/** "Walk 3 minutes, T9 train 14 minutes, …" for the screen reader. */
fun legSentence(legs: List<Leg>): String = legs.mapIndexed { i, leg ->
    val m = if (leg.minutes == 1) "1 minute" else "${leg.minutes} minutes"
    when (leg) {
        is Leg.Walk -> if (i == 0 || i == legs.lastIndex) "walk $m" else "change $m"
        is Leg.Ride -> "${leg.lineName} ${modeWord(leg.mode)} $m"
    }
}.joinToString(", ").replaceFirstChar { it.uppercase() }

private fun modeWord(mode: Mode) = when (mode) {
    Mode.TRAIN -> "train"
    Mode.METRO -> "metro"
    Mode.LIGHT_RAIL -> "light rail"
    Mode.BUS -> "bus"
    Mode.COACH -> "coach"
    Mode.FERRY -> "ferry"
    Mode.OTHER -> "service"
}

/**
 * A journey with several rides as a row of legs (plan 3.3, Options mockup): each leg as wide as
 * its minutes; rides are line-coloured bars with their badge underneath, walks are dotted. With
 * [sprites], a walking figure stands over each walk and the vehicle over each ride, where there
 * is room (62 dp high; 32 dp without). Long walks get "31 MIN WALK" written under them.
 * Read as one sentence.
 */
@Composable
fun LegStrip(legs: List<Leg>, modifier: Modifier = Modifier, sprites: Boolean = false) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    val density = LocalDensity.current
    val barTop: Dp = if (sprites) 33.dp else 0.dp
    val height: Dp = if (sprites) 62.dp else 32.dp
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { contentDescription = legSentence(legs); role = Role.Image },
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val spans = legSpans(legs, widthPx, gapPx = with(density) { 1.dp.toPx() }, dotPx = dot.px.toFloat())
        val barTopPx = with(density) { barTop.toPx() }
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val d = dot.px.toFloat()
            legs.forEachIndexed { i, leg ->
                val s = spans[i]
                when (leg) {
                    is Leg.Walk -> drawDashes(c.textSecondary, d, barTopPx + 3.dp.toPx().roundToInt(), s.left, s.width, on = 2, off = 2, thick = 2)
                    is Leg.Ride -> {
                        val h = 5 * d
                        drawRect(c.text, Offset(s.left, barTopPx), Size(s.width, h))
                        drawRect(leg.lineColor, Offset(s.left + d, barTopPx + d), Size((s.width - 2 * d).coerceAtLeast(0f), h - 2 * d))
                    }
                }
            }
        }
        legs.forEachIndexed { i, leg ->
            val s = spans[i]
            val leftDp = with(density) { s.left.toDp() }
            val widthDp = with(density) { s.width.toDp() }
            val labelTop = barTop + 15.dp
            when (leg) {
                is Leg.Ride -> {
                    Box(Modifier.offset(leftDp, labelTop)) {
                        RouteBadge(leg.lineName, leg.lineColor, feedTextColor = leg.feedTextColor, style = BadgeStyle.COMPACT)
                    }
                    if (sprites) {
                        val sheet = SpriteSheet.forMode(leg.mode)
                        val spriteW = dot * sheet.frameWidth
                        if (widthDp >= spriteW - dot * 6) {
                            val bodyMid = (sheet.bodyLeft + sheet.bodyRight) / 2f
                            val x = with(density) { (s.left + s.width / 2 - bodyMid * dot.px).roundToInt().toDp() }
                            // Stand the sprite 3 dp above the bar.
                            // The ferry brings its own water: stand the water, not the hull, on the bar.
                            val rest = if (sheet == SpriteSheet.FERRY) sheet.frameHeight - 2 else sheet.groundRow
                            val y = barTop - 3.dp - dot * rest
                            VehicleSprite(leg.mode, leg.lineColor, modifier = Modifier.offset(x, y))
                        }
                    }
                }
                is Leg.Walk -> {
                    if (sprites) {
                        val sheet = SpriteSheet.WALKER
                        val bodyW = dot * (sheet.frameWidth - 2)
                        if (widthDp >= bodyW) {
                            val x = with(density) { (s.left + s.width / 2 - sheet.frameWidth / 2f * dot.px).roundToInt().toDp() }
                            val y = barTop - 3.dp - dot * sheet.groundRow
                            WalkerSprite(WalkerPose.STAND, Modifier.offset(x, y))
                        }
                    }
                    if (widthDp >= 96.dp) {
                        PixelText(
                            "${leg.minutes} MIN WALK",
                            Modifier.offset(leftDp, labelTop + 1.dp).width(widthDp),
                            TtTheme.type.label10,
                            c.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
