package app.transittracker.ui.feature.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import app.transittracker.model.Mode
import app.transittracker.ui.sprites.SpriteLook
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Vehicles seen from above for the map (plan 3.1 "Train from above"). The train is the art of
 * `tools/sprites/make_sprites.py` `train_top` (8 dots wide, 12 per car, heading down the screen),
 * drawn here from code because the app's sprite set has no top-down sheet yet. Metro and light
 * rail use the same cars; bus, coach and ferry have simple overhead shapes of their own; OTHER is
 * an arrow. One art pixel is one dot.
 *
 * Turning: the grid is rotated by nearest-neighbour sampling onto the dot grid, so a turned
 * vehicle is still made of whole dots (no smoothing), in [HEADING_STEPS] directions.
 */
internal object TopDown {
    const val HEADING_STEPS = 16

    // Cell values: 0 empty, then palette roles.
    const val BODY = 1
    const val LIVERY = 2
    const val UNDER = 3
    const val TAIL = 4
    const val GLASS = 5
    const val HEAD = 6
    const val SHADE = 7

    /** The sprite art's own palette (same values as make_sprites.py; art, not theme tokens). */
    private val palette = mapOf(
        BODY to Color(0xFFF3ECD8),
        UNDER to Color(0xFF3D3D38),
        TAIL to Color(0xFFFF5A5F),
        GLASS to Color(0xFF2B2B28),
        SHADE to Color(0xFFC9C2AE),
    )
    private val headOn = Color(0xFFFFB020)
    private val headLit = Color(0xFFFFD98A)

    /** A sprite grid: [w] x [h] cells, row-major. */
    class Grid(val w: Int, val h: Int, val cells: IntArray = IntArray(w * h)) {
        operator fun get(x: Int, y: Int): Int = if (x in 0 until w && y in 0 until h) cells[y * w + x] else 0
        fun set(x: Int, y: Int, v: Int) {
            if (x in 0 until w && y in 0 until h) cells[y * w + x] = v
        }

        /** Bounds of the filled cells: minX, minY, maxX, maxY (all 0 when empty). */
        fun extent(): IntArray {
            var x0 = w
            var y0 = h
            var x1 = -1
            var y1 = -1
            for (y in 0 until h) for (x in 0 until w) if (cells[y * w + x] != 0) {
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
            return if (x1 < 0) intArrayOf(0, 0, 0, 0) else intArrayOf(x0, y0, x1, y1)
        }

        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, v: Int) {
            for (y in y0..y1) for (x in x0..x1) set(x, y, v)
        }
    }

    /** make_sprites.py `train_top`: [cars] cars of 12 dots, couplers between, head at the bottom. */
    fun trainTop(cars: Int): Grid {
        val g = Grid(8, cars * 12 + (cars - 1))
        var y = 0
        for (i in 0 until cars) {
            g.rect(0, y, 7, y + 11, BODY)
            g.rect(0, y + 1, 0, y + 10, LIVERY)
            g.rect(7, y + 1, 7, y + 10, LIVERY)
            g.rect(3, y + 2, 4, y + 3, UNDER)
            g.rect(3, y + 8, 4, y + 9, UNDER)
            if (i == 0) {
                g.rect(0, y, 7, y, 0)
                g.set(1, y, TAIL)
                g.set(6, y, TAIL)
            }
            if (i == cars - 1) {
                g.rect(0, y + 11, 7, y + 11, 0)
                g.rect(2, y + 10, 5, y + 10, GLASS)
                g.set(1, y + 11, HEAD)
                g.set(6, y + 11, HEAD)
            }
            y += 12
            if (i < cars - 1) {
                g.rect(3, y, 4, y, UNDER)
                y += 1
            }
        }
        return g
    }

    /** A bus or coach from above: roof with a livery band, windscreen and lamps at the front. */
    fun busTop(long: Boolean): Grid {
        val h = if (long) 18 else 15
        val g = Grid(7, h)
        g.rect(0, 1, 6, h - 2, BODY)
        g.rect(1, 0, 5, 0, BODY)
        g.rect(0, 2, 0, h - 3, LIVERY)
        g.rect(6, 2, 6, h - 3, LIVERY)
        g.rect(2, 3, 4, 4, SHADE)
        g.rect(2, h / 2, 4, h / 2 + 1, SHADE)
        g.rect(1, h - 3, 5, h - 2, GLASS)
        g.rect(1, h - 1, 5, h - 1, BODY)
        g.set(1, h - 1, HEAD)
        g.set(5, h - 1, HEAD)
        g.set(1, 0, TAIL)
        g.set(5, 0, TAIL)
        return g
    }

    /** A ferry from above: square stern, pointed bow, livery deck house. */
    fun ferryTop(): Grid {
        val g = Grid(8, 18)
        g.rect(0, 0, 7, 13, BODY)
        g.rect(1, 14, 6, 15, BODY)
        g.rect(2, 16, 5, 16, BODY)
        g.rect(3, 17, 4, 17, BODY)
        g.rect(2, 4, 5, 10, LIVERY)
        g.rect(3, 6, 4, 8, GLASS)
        g.rect(0, 0, 7, 0, UNDER)
        g.set(3, 17, HEAD)
        g.set(4, 17, HEAD)
        return g
    }

    /** The OTHER marker: a plain arrow pointing down, in the line colour. */
    fun arrow(): Grid {
        val g = Grid(7, 9)
        g.rect(2, 0, 4, 4, LIVERY)
        g.rect(0, 5, 6, 5, LIVERY)
        g.rect(1, 6, 5, 6, LIVERY)
        g.rect(2, 7, 4, 7, LIVERY)
        g.set(3, 8, LIVERY)
        return g
    }

    fun forMode(mode: Mode, cars: Int): Grid = when (mode) {
        Mode.TRAIN, Mode.METRO, Mode.LIGHT_RAIL -> trainTop(cars)
        Mode.BUS -> busTop(false)
        Mode.COACH -> busTop(true)
        Mode.FERRY -> ferryTop()
        Mode.OTHER -> arrow()
    }

    /**
     * [g] turned clockwise by [degrees] about its centre, sampled nearest-neighbour onto a square
     * grid big enough for any angle. Whole cells only.
     */
    fun rotate(g: Grid, degrees: Double): Grid {
        val side = ceil(hypot(g.w.toDouble(), g.h.toDouble())).toInt() + 1
        val out = Grid(side, side)
        val r = degrees * PI / 180.0
        val c = cos(r)
        val s = sin(r)
        val half = side / 2.0
        for (oy in 0 until side) for (ox in 0 until side) {
            val x = ox + 0.5 - half
            val y = oy + 0.5 - half
            // Inverse of the clockwise (screen, y down) rotation.
            val sx = x * c + y * s + g.w / 2.0
            val sy = -x * s + y * c + g.h / 2.0
            val v = g[floor(sx).toInt(), floor(sy).toInt()]
            if (v != 0) out.set(ox, oy, v)
        }
        return out
    }

    private val cache = HashMap<Triple<Mode, Int, Int>, Grid>()

    /** The grid for [mode] with [cars], turned to heading step [step] (cached). */
    fun turned(mode: Mode, cars: Int, step: Int): Grid {
        val key = Triple(mode, cars, step)
        return cache.getOrPut(key) { rotate(forMode(mode, cars), step * 360.0 / HEADING_STEPS) }
    }

    private fun grey(c: Color): Color {
        val l = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
        return Color(l, l, l, c.alpha)
    }

    /**
     * Draws [grid] centred on ([cx], [cy]) pixels, snapped to whole dots of [dotPx]. [frame]
     * flickers the head lamps (0 holds them). [outline] draws a one-dot outline (Day theme).
     */
    fun DrawScope.drawTurned(
        grid: Grid,
        cx: Float,
        cy: Float,
        dotPx: Float,
        livery: Color,
        look: SpriteLook,
        frame: Int,
        outline: Color?,
    ) {
        val left = floor((cx / dotPx) - grid.w / 2.0).toFloat() * dotPx
        val top = floor((cy / dotPx) - grid.h / 2.0).toFloat() * dotPx
        val alpha = when (look) {
            SpriteLook.NORMAL -> 1f
            SpriteLook.GHOST -> 0.45f
            SpriteLook.FADED -> 0.6f
            SpriteLook.CANCELLED -> 0.3f
        }
        val isGrey = look == SpriteLook.GHOST || look == SpriteLook.CANCELLED
        val cell = Size(dotPx, dotPx)
        if (outline != null) {
            for (y in -1..grid.h) for (x in -1..grid.w) {
                if (grid[x, y] != 0) continue
                val near = grid[x - 1, y] != 0 || grid[x + 1, y] != 0 || grid[x, y - 1] != 0 || grid[x, y + 1] != 0
                if (near) drawRect(outline, Offset(left + x * dotPx, top + y * dotPx), cell, alpha)
            }
        }
        for (y in 0 until grid.h) for (x in 0 until grid.w) {
            val v = grid[x, y]
            if (v == 0) continue
            var color = when (v) {
                LIVERY -> livery
                HEAD -> if (frame % 2 == 0) headOn else headLit
                else -> palette[v] ?: livery
            }
            if (isGrey) color = grey(color)
            drawRect(color, Offset(left + x * dotPx, top + y * dotPx), cell, alpha)
        }
    }
}
