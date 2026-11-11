package app.transittracker.ui.sprites

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Mode
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.drawPixelIcon
import app.transittracker.ui.format.delayMinutes
import app.transittracker.ui.theme.TtTheme

/** What the vehicle tells you (plan 3.2). */
@Immutable
sealed interface VehicleState {
    /** Live, between two stops: wheels turn, speed lines trail. */
    data object Moving : VehicleState

    /** Live, between a stop's expected arrival and departure: still, doors open and lit. */
    data object AtStop : VehicleState

    /** `delaySeconds` of 60 or more: as Moving / AtStop, with an amber "+[minutes]" tag above it. */
    data class Late(val minutes: Int, val atStop: Boolean = false) : VehicleState

    /** TIMETABLED (no live data): a grey, faded ghost at the timetabled position. */
    data object Ghost : VehicleState

    /** Stale live data: stops animating and fades. */
    data object Stale : VehicleState

    /** CANCELLED: greyed, crossed out, never moves. */
    data object Cancelled : VehicleState
}

/**
 * The sprite state for a service (plan 3.2), in priority order: cancelled, timetabled (ghost),
 * stale, late (delay of 60 s or more), at a stop, moving.
 */
fun vehicleState(status: DepartureStatus, stale: Boolean, delaySeconds: Int?, atStop: Boolean): VehicleState = when {
    status == DepartureStatus.CANCELLED -> VehicleState.Cancelled
    status == DepartureStatus.TIMETABLED -> VehicleState.Ghost
    stale -> VehicleState.Stale
    delaySeconds != null && delaySeconds >= 60 -> VehicleState.Late(delayMinutes(delaySeconds), atStop)
    atStop -> VehicleState.AtStop
    else -> VehicleState.Moving
}

enum class Facing { RIGHT, LEFT }

/** Frame to show for [state] at [tick] (shared by the strip, which draws sprites itself). */
fun vehicleFrame(state: VehicleState, clock: SpriteClock, reduceMotion: Boolean): Int = when (state) {
    VehicleState.AtStop -> SpriteSheet.STOPPED_FRAME
    is VehicleState.Late -> if (state.atStop) SpriteSheet.STOPPED_FRAME else clock.frame(SpriteSheet.MOVING_FRAMES, reduceMotion)
    VehicleState.Moving -> clock.frame(SpriteSheet.MOVING_FRAMES, reduceMotion)
    VehicleState.Ghost -> clock.frame(SpriteSheet.MOVING_FRAMES, reduceMotion)
    VehicleState.Stale, VehicleState.Cancelled -> 0
}

fun vehicleLook(state: VehicleState): SpriteLook = when (state) {
    VehicleState.Ghost -> SpriteLook.GHOST
    VehicleState.Stale -> SpriteLook.FADED
    VehicleState.Cancelled -> SpriteLook.CANCELLED
    else -> SpriteLook.NORMAL
}

/**
 * A vehicle sprite (plan 3.1, 3.2). [liveryColor] tints the stripe (the route colour). [scale] is
 * whole dots per art pixel: 1 on strips and cards (a train is 64 x 32 dp), 2 for galleries and
 * the page transition. [long] picks the four-car / coupled transition sprite where the mode has one.
 * Animates from the shared clock; holds a still frame under reduce motion. Decorative: it is
 * hidden from the screen reader (the strip or row carries the sentence).
 *
 * The composable is exactly the sheet frame (with its 1-dot padding); a Late tag overhangs the top.
 */
@Composable
fun VehicleSprite(
    mode: Mode,
    liveryColor: Color,
    state: VehicleState = VehicleState.Moving,
    facing: Facing = Facing.RIGHT,
    scale: Int = 1,
    modifier: Modifier = Modifier,
    long: Boolean = false,
) {
    val sheet = SpriteSheet.forMode(mode, long)
    val images = rememberSpriteImages(sheet)
    val dot = TtTheme.dot
    val c = TtTheme.colors
    val clock = TtTheme.spriteClock
    val reduce = TtTheme.motion.reduceMotion
    val cell = dot.px * scale
    val outline = if (c.outlineSprites) c.spriteOutline else null
    Box(modifier.clearAndSetSemantics { }) {
        Canvas(Modifier.size(dot * (sheet.frameWidth * scale), dot * (sheet.frameHeight * scale))) {
            val frame = vehicleFrame(state, clock, reduce)
            drawSpriteFrame(sheet, images, frame, 0f, 0f, cell, liveryColor, vehicleLook(state), outline, facing == Facing.LEFT)
            if (state == VehicleState.Cancelled) {
                val crossCell = cell.toFloat()
                val bodyMid = (sheet.bodyLeft + sheet.bodyRight) / 2f
                val mid = if (facing == Facing.LEFT) sheet.frameWidth - bodyMid else bodyMid
                val x = ((mid - 3.5f) * cell).toInt().toFloat()
                val y = (((sheet.groundRow - 1) / 2f - 3.5f) * cell).toInt().toFloat().coerceAtLeast(0f)
                drawPixelIcon(PixelIcons.LampCross, c.stop, crossCell, x, y)
            }
        }
        if (state is VehicleState.Late) LateTag(state.minutes, sheet, scale, facing)
    }
}

/** The amber "+4" riding above a late vehicle; blinks at 1 Hz (steady under reduce motion). */
@Composable
private fun LateTag(minutes: Int, sheet: SpriteSheet, scale: Int, facing: Facing) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    val clock = TtTheme.spriteClock
    val reduce = TtTheme.motion.reduceMotion
    val bodyMid = (sheet.bodyLeft + sheet.bodyRight) / 2f
    val midDots = if (facing == Facing.LEFT) sheet.frameWidth - bodyMid else bodyMid
    Box(
        Modifier
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(0, 0) {
                    val x = (midDots * dot.px * scale - p.width / 2f).toInt()
                    val y = -p.height - 2.dp.roundToPx()
                    p.place(x, y)
                }
            }
            .graphicsLayer { alpha = if (clock.blinkOn(reduce)) 1f else 0.3f }
            .background(c.caution)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        PixelText("+$minutes", style = TtTheme.type.label10Bold, color = c.screen, maxLines = 1)
    }
}

enum class WalkerPose { STAND, WALK, RUN }

/**
 * The walking figure (plan 3.5): stands beside every walk time, walks when it is time to leave,
 * runs when you are cutting it fine. 8 x 12 art pixels (16 x 24 dp at scale 1) plus padding.
 */
@Composable
fun WalkerSprite(
    pose: WalkerPose = WalkerPose.STAND,
    modifier: Modifier = Modifier,
    facing: Facing = Facing.RIGHT,
    scale: Int = 1,
) {
    val sheet = SpriteSheet.WALKER
    val images = rememberSpriteImages(sheet)
    val dot = TtTheme.dot
    val c = TtTheme.colors
    val clock = TtTheme.spriteClock
    val reduce = TtTheme.motion.reduceMotion
    val outline = if (c.outlineSprites) c.spriteOutline else null
    Canvas(modifier.clearAndSetSemantics { }.size(dot * (sheet.frameWidth * scale), dot * (sheet.frameHeight * scale))) {
        drawSpriteFrame(sheet, images, walkerFrame(pose, clock, reduce), 0f, 0f, dot.px * scale, c.text, SpriteLook.NORMAL, outline, facing == Facing.LEFT)
    }
}

fun walkerFrame(pose: WalkerPose, clock: SpriteClock, reduceMotion: Boolean): Int = when (pose) {
    WalkerPose.STAND -> 4
    WalkerPose.WALK -> clock.frame(4, reduceMotion)
    WalkerPose.RUN -> 5 + clock.frame(4, reduceMotion)
}
