package app.transittracker.ui.strip

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
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

/**
 * Draws [ground] across the full width with its surface at [groundY] (device px). [dot] is the
 * dot in device px. For [Ground.WIRE] the wire hangs where a light-rail pantograph standing on
 * the ground touches it.
 */
fun DrawScope.drawGround(ground: Ground, groundY: Float, dot: Float, colors: TtColors) {
    val w = size.width
    when (ground) {
        Ground.RAIL -> {
            drawRect(colors.textSecondary, Offset(0f, groundY), Size(w, dot))
            var x = 0f
            while (x < w) {
                drawRect(colors.divider, Offset(x, groundY + dot), Size(dot, 2 * dot))
                x += 4 * dot
            }
        }
        Ground.WIRE -> {
            drawRect(colors.textSecondary, Offset(0f, groundY), Size(w, dot))
            val wireY = groundY - SpriteSheet.LIGHT_RAIL.groundRow * dot
            drawRect(colors.divider, Offset(0f, wireY), Size(w, dot))
        }
        Ground.ROAD -> {
            drawRect(colors.divider, Offset(0f, groundY), Size(w, dot))
            drawDashes(colors.textSecondary, dot, groundY + 3 * dot, on = 3, off = 4)
        }
        Ground.WATER -> drawRect(colors.water, Offset(0f, groundY), Size(w, 4 * dot))
        Ground.LINE -> drawRect(colors.textSecondary, Offset(0f, groundY), Size(w, dot))
    }
}
