package app.transittracker.ui.loading

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.pixelBorder
import app.transittracker.ui.components.pixelFill
import app.transittracker.ui.sprites.SpriteImages
import app.transittracker.ui.sprites.SpriteSheet
import app.transittracker.ui.sprites.drawSpriteFrame
import app.transittracker.ui.sprites.rememberSpriteImages
import app.transittracker.ui.strip.Ground
import app.transittracker.ui.strip.drawGround
import app.transittracker.ui.theme.TtColors
import app.transittracker.ui.theme.TtTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** A running vehicle's window, in dots: wide enough for the light rail, tall enough for its wire. */
private const val WINDOW_W = 48
private const val WINDOW_H = 22
/** The row the vehicle stands on inside the window. */
private const val WINDOW_GROUND = 16

/** A flap board's letter cell, in dots, and the gaps between cells and lines. */
private const val CELL_W = 7
private const val CELL_H = 10
private const val CELL_GAP = 1
private const val LINE_GAP = 2

/**
 * Draws [vehicle] running on the spot in a window [widthDots] wide whose top-left is the current
 * origin: its ground scrolled by [scrollDots], and its body [offsetDots] from the centre.
 */
private fun DrawScope.drawRunningVehicle(
    vehicle: LoaderVehicle,
    images: SpriteImages,
    spriteFrame: Int,
    offsetDots: Int,
    scrollDots: Int,
    widthDots: Int,
    dotPx: Int,
    colors: TtColors,
    outline: Color?,
) {
    val sheet = SpriteSheet.forMode(vehicle.mode)
    val d = dotPx.toFloat()
    clipRect(0f, 0f, widthDots * d, WINDOW_H * d) {
        drawGround(Ground.forMode(vehicle.mode), WINDOW_GROUND * d, d, colors, scrollDots, width = widthDots * d)
        val bodyMid = (sheet.bodyLeft + sheet.bodyRight) / 2f
        val left = (widthDots / 2f - bodyMid).roundToInt() + offsetDots
        drawSpriteFrame(sheet, images, spriteFrame, left * d, (WINDOW_GROUND - sheet.groundRow) * d, dotPx, vehicle.livery, outline = outline)
    }
}

/**
 * The small loader (plan 3.5): a vehicle holds still while the ground runs under it, and gives
 * way to the next of [fleet] about once a second. For waits where the mode is not known yet (a
 * place search, a stop lookup). 96 x 44 dp. Runs on the shared sprite clock and holds one frame
 * under reduce motion. Decorative: put the words beside it (see [TreadmillLine]).
 */
@Composable
fun Treadmill(modifier: Modifier = Modifier, fleet: List<LoaderVehicle> = LoaderFleet) {
    val dot = TtTheme.dot
    val c = TtTheme.colors
    val clock = TtTheme.spriteClock
    val reduce = TtTheme.motion.reduceMotion
    val images = fleet.map { rememberSpriteImages(SpriteSheet.forMode(it.mode)) }
    val outline = if (c.outlineSprites) c.spriteOutline else null
    Canvas(modifier.clearAndSetSemantics { }.size(dot * WINDOW_W, dot * WINDOW_H)) {
        if (fleet.isEmpty()) return@Canvas
        val f = treadmillFrame(clock.tick, fleet.size, reduce)
        drawRunningVehicle(fleet[f.vehicle], images[f.vehicle], f.spriteFrame, f.offsetDots, f.scrollDots, WINDOW_W, dot.px, c, outline)
    }
}

/** A [Treadmill] with the words of the wait beside it ("SEARCHING..."), standing on its ground line. */
@Composable
fun TreadmillLine(text: String, modifier: Modifier = Modifier) {
    val dot = TtTheme.dot
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        Treadmill()
        Label(text, Modifier.padding(bottom = dot * 3), color = TtTheme.colors.textSecondary, bold = true)
    }
}

/**
 * True once [waiting] has lasted [delayMs]; false again as soon as the wait ends. Gate a loader
 * with it where the wait is usually too short to see, so a quick read never flashes one.
 */
@Composable
fun rememberSlowWait(waiting: Boolean, delayMs: Long = LoaderTiming.SHOW_AFTER_MS): Boolean {
    var slow by remember { mutableStateOf(waiting && delayMs <= 0) }
    LaunchedEffect(waiting, delayMs) {
        if (waiting) {
            delay(delayMs)
            slow = true
        } else slow = false
    }
    return waiting && slow
}

/**
 * The page loader (plan 3.5): a departure board at work. A flap tile turns over through the
 * vehicles of [fleet] beside [line1] and [line2], whose letters spin and settle from the left on
 * each flip, above [rows] blank board rows that flip one after another. For the wait on a journey
 * search. Runs on the shared sprite clock; under reduce motion it is a still board with the words
 * set. Decorative: give the caller's container one sentence for the screen reader.
 */
@Composable
fun FlapBoardLoader(
    line1: String,
    line2: String,
    modifier: Modifier = Modifier,
    rows: Int = 5,
    fleet: List<LoaderVehicle> = LoaderFleet,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FlapTile(fleet)
            FlapWords(listOf(line1, line2))
        }
        repeat(rows) { BoardRow(it) }
    }
}

/** The tile of a [FlapBoardLoader]: a bordered flap with a notch at each side, a vehicle running on it. */
@Composable
private fun FlapTile(fleet: List<LoaderVehicle>) {
    val dot = TtTheme.dot
    val c = TtTheme.colors
    val clock = TtTheme.spriteClock
    val reduce = TtTheme.motion.reduceMotion
    val images = fleet.map { rememberSpriteImages(SpriteSheet.forMode(it.mode)) }
    val outline = if (c.outlineSprites) c.spriteOutline else null
    Canvas(Modifier.size(dot * (WINDOW_W + 2), dot * (WINDOW_H + 2)).pixelFill(c.panel).pixelBorder(c.divider)) {
        if (fleet.isEmpty()) return@Canvas
        val d = dot.px.toFloat()
        val f = flapTileFrame(clock.tick, fleet.size, reduce)
        translate(d, d) {
            val draw: DrawScope.() -> Unit = {
                drawRunningVehicle(fleet[f.vehicle], images[f.vehicle], f.spriteFrame, 0, f.scrollDots, WINDOW_W, dot.px, c, outline)
            }
            if (f.half) scale(1f, 0.5f, pivot = Offset(WINDOW_W * d / 2f, WINDOW_H * d / 2f), block = draw) else draw()
            // The hinge: a notch in each side at mid-height.
            val notchY = (WINDOW_H / 2 - 1) * d
            drawRect(c.screen, Offset(0f, notchY), Size(2 * d, 2 * d))
            drawRect(c.screen, Offset((WINDOW_W - 2) * d, notchY), Size(2 * d, 2 * d))
        }
    }
}

/** The words of a [FlapBoardLoader]: one letter to a cell, drawn from the clock without recomposing. */
@Composable
private fun FlapWords(lines: List<String>) {
    val dot = TtTheme.dot
    val c = TtTheme.colors
    val clock = TtTheme.spriteClock
    val reduce = TtTheme.motion.reduceMotion
    val style = TtTheme.type.label12Bold
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val columns = lines.maxOf { it.length }
    val text = remember(lines) { lines.map { it.uppercase().padEnd(columns) } }
    val glyphs = remember(measurer, style, density, text) {
        (('A'..'Z') + text.flatMap { it.toList() }).filter { it != ' ' }.distinct().associateWith { measurer.measure(it.toString(), style) }
    }
    Canvas(
        Modifier.size(
            dot * (columns * CELL_W + (columns - 1) * CELL_GAP),
            dot * (text.size * CELL_H + (text.size - 1) * LINE_GAP),
        ),
    ) {
        val d = dot.px.toFloat()
        val tick = clock.tick
        for ((row, line) in text.withIndex()) {
            for ((col, target) in line.withIndex()) {
                val x = col * (CELL_W + CELL_GAP) * d
                val y = row * (CELL_H + LINE_GAP) * d
                drawRect(c.raised, Offset(x, y), Size(CELL_W * d, CELL_H * d))
                val glyph = glyphs[boardLetter(target, row * columns + col, tick, reduce)] ?: continue
                drawText(
                    glyph, c.text,
                    Offset(
                        x + ((CELL_W * d - glyph.size.width) / 2f).roundToInt(),
                        y + ((CELL_H * d - glyph.size.height) / 2f).roundToInt(),
                    ),
                )
            }
        }
    }
}

/** One blank row of a [FlapBoardLoader]: a time, a destination and a platform still to come. */
@Composable
private fun BoardRow(index: Int) {
    val dot = TtTheme.dot
    val c = TtTheme.colors
    val clock = TtTheme.spriteClock
    val reduce = TtTheme.motion.reduceMotion
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer {
                val open = boardRowOpen(index, clock.tick, reduce)
                scaleY = open
                alpha = open
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
            .height(48.dp)
            .pixelFill(c.screen).pixelBorder(c.divider)
            .padding(dot.dp).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(44.dp).height(12.dp).pixelFill(c.raised, dots = 0))
        Box(Modifier.weight(1f).height(12.dp).pixelFill(c.raised, dots = 0))
        Box(Modifier.width(24.dp).height(12.dp).pixelFill(c.raised, dots = 0))
    }
}
