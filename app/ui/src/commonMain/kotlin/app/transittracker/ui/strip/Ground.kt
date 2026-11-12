package app.transittracker.ui.strip

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import app.transittracker.model.Mode
import app.transittracker.ui.components.drawDashes
import app.transittracker.ui.sprites.SpriteSheet
import app.transittracker.ui.theme.TtColors

/** What a strip's track looks like, by mode (plan 3.3). */
enum class Ground {
    /** Rails and sleepers: train and metro. */
    RAIL,
    /** A rail under an overhead wire: light rail. */
    WIRE,
    /** A road edge with a dashed centre line: bus and coach. */
    ROAD,
    /** A band of water: ferry. */
    WATER,
    /** A plain line: a mode the app does not know. */
    LINE,
    ;

    companion object {
        fun forMode(mode: Mode): Ground = when (mode) {
            Mode.TRAIN, Mode.METRO -> RAIL
            Mode.LIGHT_RAIL -> WIRE
            Mode.BUS, Mode.COACH -> ROAD
            Mode.FERRY -> WATER
            Mode.OTHER -> LINE
        }
    }
}

/** Sleepers are one dot wide, one every this many dots. */
private const val SLEEPER_STEP = 4
/** The road's centre line: dots of dash, dots of gap. */
private const val ROAD_DASH_ON = 3
private const val ROAD_DASH_OFF = 4

/**
 * Draws [ground] from the left edge across [width] (the full width by default) with its surface
 * at [groundY] (device px). [dot] is the dot in device px. For [Ground.WIRE] the wire hangs where
 * a light-rail pantograph standing on the ground touches it. [scrollDots] moves the sleepers and
 * the road's centre line that many dots to the left, for a vehicle that runs on the spot.
 */
fun DrawScope.drawGround(ground: Ground, groundY: Float, dot: Float, colors: TtColors, scrollDots: Int = 0, width: Float = size.width) {
    val w = width
    when (ground) {
        Ground.RAIL -> {
            drawRect(colors.textSecondary, Offset(0f, groundY), Size(w, dot))
            var x = (SLEEPER_STEP - scrollDots.mod(SLEEPER_STEP)).mod(SLEEPER_STEP) * dot
            while (x < w) {
                drawRect(colors.divider, Offset(x, groundY + dot), Size(dot, 2 * dot))
                x += SLEEPER_STEP * dot
            }
        }
        Ground.WIRE -> {
            drawRect(colors.textSecondary, Offset(0f, groundY), Size(w, dot))
            val wireY = groundY - SpriteSheet.LIGHT_RAIL.groundRow * dot
            drawRect(colors.divider, Offset(0f, wireY), Size(w, dot))
        }
        Ground.ROAD -> {
            drawRect(colors.divider, Offset(0f, groundY), Size(w, dot))
            // Start the pattern off the left edge by the scroll and clip, so a dash slides out whole.
            val shift = scrollDots.mod(ROAD_DASH_ON + ROAD_DASH_OFF) * dot
            clipRect(0f, groundY, w, groundY + 4 * dot) {
                drawDashes(colors.textSecondary, dot, groundY + 3 * dot, left = -shift, width = w + shift, on = ROAD_DASH_ON, off = ROAD_DASH_OFF)
            }
        }
        Ground.WATER -> drawRect(colors.water, Offset(0f, groundY), Size(w, 4 * dot))
        Ground.LINE -> drawRect(colors.textSecondary, Offset(0f, groundY), Size(w, dot))
    }
}
