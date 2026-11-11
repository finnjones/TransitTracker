package app.transittracker.ui.strip

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Mode
import app.transittracker.model.ServiceDetail
import app.transittracker.model.StopCall
import app.transittracker.model.StopId
import app.transittracker.model.WallClock
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.drawPixelIcon
import app.transittracker.ui.format.DefaultLineColor
import app.transittracker.ui.format.parseLineColor
import app.transittracker.ui.sprites.SpriteSheet
import app.transittracker.ui.sprites.VehicleState
import app.transittracker.ui.sprites.blinkOn
import app.transittracker.ui.sprites.drawSpriteFrame
import app.transittracker.ui.sprites.rememberSpriteImages
import app.transittracker.ui.sprites.vehicleFrame
import app.transittracker.ui.sprites.vehicleLook
import app.transittracker.ui.sprites.vehicleState
import app.transittracker.ui.theme.TtColors
import app.transittracker.ui.theme.TtTheme
import kotlin.math.roundToInt

/** Strip geometry in dots (Main mockup at 2 dp per dot). */
private object StripDots {
    const val GROUND = 15
    const val STOP = 5
    const val YOURS = 8
    const val LABEL_TOP = 21
    const val HEIGHT = 27
}

/**
 * The journey strip (plan 3.3, Main mockup): a track with a vehicle on it. Up to three stops
 * before yours on the left, your stop two-thirds along (blinking at 1 Hz), your destination at the
 * right end; the ground follows [mode]. The vehicle is placed from [calls] and [clock] on every
 * sprite-clock tick, in whole dots, and steps to a new estimate over one second when [calls]
 * change. Its sprite state follows [status] / [stale] / [delaySeconds] (plan 3.2): a ghost when
 * TIMETABLED. Read by the screen reader as one sentence. 54 dp high, fills the width.
 */
@Composable
fun JourneyStrip(
    calls: List<StopCall>,
    yourStopId: StopId,
    destinationStopId: StopId,
    mode: Mode,
    liveryColor: Color,
    status: DepartureStatus,
    clock: WallClock,
    modifier: Modifier = Modifier,
    stale: Boolean = false,
    /** Delay for the late tag; null takes it from the vehicle's next call. */
    delaySeconds: Int? = null,
) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    val spriteClock = TtTheme.spriteClock
    val reduce = TtTheme.motion.reduceMotion
    val ghost = status == DepartureStatus.TIMETABLED
    val sheet = SpriteSheet.forMode(mode)
    val images = rememberSpriteImages(sheet)
    val ground = Ground.forMode(mode)
    val outline = if (c.outlineSprites) c.spriteOutline else null

    // Model at composition time: stops, labels and the screen-reader sentence.
    val nowAtCompose = clock.nowMillis()
    val model = remember(calls, yourStopId, destinationStopId, mode, ghost) {
        journeyStripModel(calls, yourStopId, destinationStopId, nowAtCompose, mode, ghost)
    }

    // When new data moves the estimate, step from the old place to the new one over a second.
    val previous = remember { arrayOfNulls<List<StopCall>>(1) }
    val jump = remember(calls) {
        val old = previous[0]
        previous[0] = calls
        if (old == null) 0f else {
            val now = clock.nowMillis()
            val a = journeyStripModel(old, yourStopId, destinationStopId, now).vehicleX
            val b = journeyStripModel(calls, yourStopId, destinationStopId, now).vehicleX
            if (a != null && b != null) a - b else 0f
        }
    }
    val pending = remember(calls) { mutableStateOf(jump != 0f) }
    val offset = remember { Animatable(0f) }
    LaunchedEffect(calls) {
        if (jump != 0f) {
            offset.snapTo(jump)
            pending.value = false
            if (reduce) offset.snapTo(0f) else offset.animateTo(0f, tween(1000, easing = LinearEasing))
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(dot * StripDots.HEIGHT)
            .clearAndSetSemantics { contentDescription = model.sentence; role = Role.Image },
    ) {
        fun stateFor(live: JourneyStripModel): VehicleState? {
            val p = live.vehicle ?: return null
            val delay = delaySeconds ?: calls.getOrNull(p.toIndex)?.let { call ->
                val e = call.expectedArrivalMs ?: call.expectedDepartureMs
                val s = call.scheduledArrivalMs ?: call.scheduledDepartureMs
                if (e != null && s != null) ((e - s) / 1000).toInt() else null
            }
            return vehicleState(status, stale, delay, p.atStop)
        }

        // Where the vehicle is drawn, recomputed from the clock on every tick. Call only from
        // draw or placement code, never from composition.
        fun placement(widthPx: Float): StripVehicle? {
            spriteClock.tick // re-read on every tick
            val live = journeyStripModel(calls, yourStopId, destinationStopId, clock.nowMillis(), mode, ghost)
            val x = live.vehicleX ?: return null
            val state = stateFor(live) ?: return null
            val shift = if (pending.value) jump else offset.value
            val d = dot.px.toFloat()
            val bodyMid = (sheet.bodyLeft + sheet.bodyRight) / 2f
            val bodyHalf = (sheet.bodyRight - sheet.bodyLeft) / 2f
            // Keep the whole body on the strip; snap to whole dots.
            val centre = ((x + shift) * widthPx).coerceIn(bodyHalf * d, widthPx - bodyHalf * d)
            val left = ((centre / d - bodyMid).roundToInt()) * d
            return StripVehicle(state, left, StripDots.GROUND * d - sheet.groundRow * d, live)
        }

        Canvas(Modifier.matchParentSize().clipToBounds()) {
            val d = dot.px.toFloat()
            val groundY = StripDots.GROUND * d
            drawGround(ground, groundY, d, c)
            val v = placement(size.width)
            val stops = v?.model?.stops ?: model.stops
            val blink = spriteClock.blinkOn(reduce)
            for (s in stops) drawStripStop(s, groundY, d, blink, c)
            if (v == null) return@Canvas
            drawSpriteFrame(sheet, images, vehicleFrame(v.state, spriteClock, reduce), v.left, v.top, dot.px, liveryColor, vehicleLook(v.state), outline)
            if (v.state == VehicleState.Cancelled) {
                val bodyMid = (sheet.bodyLeft + sheet.bodyRight) / 2f
                drawPixelIcon(PixelIcons.LampCross, c.stop, d, v.left + (bodyMid - 3.5f).roundToInt() * d, v.top + d)
            }
        }
        for (s in model.stops.filter { it.labelled }) StripLabel(s)
        // The "+4" tag rides above a late vehicle (plan 3.2); it may overhang the top of the strip.
        val late = stateFor(model) as? VehicleState.Late
        if (late != null) {
            Box(
                Modifier
                    .layout { m, cons ->
                        val pl = m.measure(cons.copy(minWidth = 0, minHeight = 0))
                        layout(0, 0) {
                            val v = placement(cons.maxWidth.toFloat())
                            if (v != null) {
                                val mid = v.left + (sheet.bodyLeft + sheet.bodyRight) / 2f * dot.px
                                pl.place((mid - pl.width / 2f).roundToInt(), (v.top - pl.height).roundToInt())
                            }
                        }
                    }
                    .graphicsLayer { alpha = if (spriteClock.blinkOn(reduce)) 1f else 0.3f }
                    .background(c.caution)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) { PixelText("+${late.minutes}", style = TtTheme.type.label10Bold, color = c.screen, maxLines = 1) }
        }
    }
}

private class StripVehicle(val state: VehicleState, val left: Float, val top: Float, val model: JourneyStripModel)

/** [JourneyStrip] for a [ServiceDetail] from `TransitCore.service(ref)`. */
@Composable
fun JourneyStrip(
    detail: ServiceDetail,
    yourStopId: StopId,
    destinationStopId: StopId,
    clock: WallClock,
    modifier: Modifier = Modifier,
) = JourneyStrip(
    detail.calls, yourStopId, destinationStopId,
    detail.route?.mode ?: Mode.OTHER,
    parseLineColor(detail.route?.color) ?: DefaultLineColor,
    detail.status, clock, modifier, detail.stale,
)

private fun DrawScope.drawStripStop(s: StripStop, groundY: Float, d: Float, blinkOn: Boolean, colors: TtColors) {
    val cx = (s.x * size.width / d).roundToInt() * d
    when (s.kind) {
        StripStopKind.YOURS -> {
            val side = StripDots.YOURS * d
            val color = if (blinkOn) colors.text else colors.text.copy(alpha = 0.3f)
            drawRect(color, Offset(cx - 4 * d, groundY - 4 * d), Size(side, side))
        }
        else -> {
            val side = StripDots.STOP * d
            val left = cx - 2 * d
            val top = groundY - 2 * d
            if (s.kind == StripStopKind.PASSED) {
                drawRect(colors.textSecondary, Offset(left, top), Size(side, side))
            } else {
                drawRect(colors.panel, Offset(left, top), Size(side, side))
                drawRect(colors.text, Offset(left, top), Size(side, d))
                drawRect(colors.text, Offset(left, top + side - d), Size(side, d))
                drawRect(colors.text, Offset(left, top), Size(d, side))
                drawRect(colors.text, Offset(left + side - d, top), Size(d, side))
            }
        }
    }
}

@Composable
private fun StripLabel(s: StripStop) {
    val dot = TtTheme.dot
    val c = TtTheme.colors
    val yours = s.kind == StripStopKind.YOURS
    val text = if (yours) "YOU" else s.name.uppercase()
    PixelText(
        text,
        Modifier.layout { m, cons ->
            val p = m.measure(cons.copy(minWidth = 0))
            layout(cons.maxWidth, p.height) {
                val w = cons.maxWidth
                val x = when (s.kind) {
                    StripStopKind.DESTINATION -> w - p.width
                    StripStopKind.YOURS -> (s.x * w - p.width / 2f).roundToInt()
                    else -> if (s.x < 0.1f) 0 else (s.x * w - p.width / 2f).roundToInt()
                }.coerceIn(0, maxOf(0, w - p.width))
                p.place(x, StripDots.LABEL_TOP * dot.px)
            }
        },
        if (yours) TtTheme.type.label10Bold else TtTheme.type.label10,
        if (yours) c.text else c.textSecondary,
        maxLines = 1,
    )
}
