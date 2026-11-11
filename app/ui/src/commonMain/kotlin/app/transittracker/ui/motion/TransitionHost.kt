package app.transittracker.ui.motion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import app.transittracker.ui.nav.NavChange
import app.transittracker.ui.nav.NavEntry
import app.transittracker.ui.nav.Navigator
import app.transittracker.ui.sprites.Facing
import app.transittracker.ui.sprites.VehicleSprite
import app.transittracker.ui.sprites.VehicleState
import app.transittracker.ui.theme.TtColors
import app.transittracker.ui.theme.TtTheme
import kotlin.math.roundToInt

/**
 * Shows [navigator]'s current page and plays each navigation step's transition (plan 3.4).
 * Every page keeps its `rememberSaveable` state while it is on a back stack; state is dropped
 * when the page leaves every stack. [content] draws one page for its entry.
 *
 * [drive] false freezes time (the controller is moved by hand): for filmstrips and tests.
 */
@Composable
fun TransitionHost(
    navigator: Navigator,
    controller: TransitionController,
    modifier: Modifier = Modifier,
    drive: Boolean = true,
    content: @Composable (NavEntry) -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    BoxWithConstraints(modifier.fillMaxSize().transitionTaps(controller)) {
        val widthDp = maxWidth.value
        val change = navigator.lastChange
        // Start in composition, so the first frame after a navigation is already the first step.
        remember(change?.serial) {
            if (change != null && controller.lastSerial < change.serial) {
                controller.lastSerial = change.serial
                if (change.to.id == navigator.current.id) controller.start(change, widthDp)
            }
            Unit
        }
        val run = controller.running
        if (drive && run != null) {
            LaunchedEffect(run) {
                while (controller.running === run) withFrameMillis { controller.onFrame(it) }
            }
        }
        val current = navigator.current
        val entries = if (run != null && run.change.to.id == current.id) listOf(run.change.from, current) else listOf(current)
        TransitionStage(controller, entries, holder, content)

        // Forget the saved state of pages that left every stack, once nothing shows them.
        val live = navigator.allEntries.map { it.id }.toSet()
        val shown = remember { mutableSetOf<Long>() }
        LaunchedEffect(live, run) {
            if (run != null) return@LaunchedEffect
            val gone = shown.filter { it !in live }
            for (id in gone) holder.removeState(id.toString())
            shown.removeAll(gone.toSet())
        }
        shown.addAll(entries.map { it.id })
    }
}

/**
 * Draws one transition frame: [entries] is the page alone, or (outgoing, incoming) while
 * [controller] runs a transition. Shared by [TransitionHost] and [TransitionPreview].
 */
@Composable
internal fun TransitionStage(
    controller: TransitionController,
    entries: List<NavEntry>,
    holder: SaveableStateHolder,
    content: @Composable (NavEntry) -> Unit,
) {
    val run = controller.running
    val colors = TtTheme.colors
    Box(Modifier.fillMaxSize()) {
        for ((i, entry) in entries.withIndex()) {
            key(entry.id) {
                val incoming = run != null && entry.id == run.change.to.id
                val flap = remember(entry.id) { EntryFlapClock(controller, entry.id) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (incoming) incomingLayer(run, controller) else Modifier)
                        .background(colors.screen),
                ) {
                    CompositionLocalProvider(LocalFlapClock provides flap) {
                        holder.SaveableStateProvider(entry.id.toString()) { content(entry) }
                    }
                }
                // Nothing under the incoming page takes touches, even where it has no handler.
                if (run != null && i == 0 && entries.size > 1) Box(Modifier.fillMaxSize().pointerInput(Unit) { swallowAll() })
            }
        }
        if (run != null) Decoration(run, controller, colors)
    }
}

/** Clip, fade or nothing for the incoming page, read from the controller in the draw phase. */
private fun incomingLayer(run: RunningTransition, controller: TransitionController): Modifier =
    when (run.transition) {
        is PageTransition.VehiclePass -> Modifier.drawWithContent {
            val f = Timeline.wipeFraction(controller.elapsedMs)
            val edge = (size.width * f).roundToInt().toFloat()
            if (run.change.reverse) clipRect(left = size.width - edge) { this@drawWithContent.drawContent() }
            else clipRect(right = edge) { this@drawWithContent.drawContent() }
        }
        PageTransition.Doors -> Modifier.graphicsLayer { alpha = if (Timeline.doorsShowNewPage(controller.elapsedMs)) 1f else 0f }
        PageTransition.SplitFlap, PageTransition.Cut -> Modifier
    }

@Composable
private fun Decoration(run: RunningTransition, controller: TransitionController, colors: TtColors) {
    when (val t = run.transition) {
        is PageTransition.VehiclePass -> PassingVehicle(t, run.change.reverse, controller)
        PageTransition.Doors -> {
            val dot = TtTheme.dot.px.toFloat()
            Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                drawDoors(Timeline.doorsClosed(controller.elapsedMs), colors, dot)
            }
        }
        PageTransition.SplitFlap, PageTransition.Cut -> Unit
    }
}

/** The vehicle crossing the middle of the screen, nose on the wipe edge. Never takes touches. */
@Composable
private fun PassingVehicle(t: PageTransition.VehiclePass, reverse: Boolean, controller: TransitionController) {
    Box(
        Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val p = measurable.measure(Constraints())
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val w = constraints.maxWidth
                    val nose = (w * Timeline.noseFraction(controller.elapsedMs)).roundToInt()
                    val x = if (reverse) w - nose else nose - p.width
                    p.placeRelative(x, (constraints.maxHeight - p.height) / 2)
                }
            },
    ) {
        VehicleSprite(
            t.mode, t.lineColor, VehicleState.Moving,
            facing = if (reverse) Facing.LEFT else Facing.RIGHT,
            scale = Timeline.VEHICLE_SCALE, long = true,
        )
    }
}

/**
 * Two carriage doors (Transitions artboard): Raised panels, each half the screen, with a 3-dot
 * amber strip on the closing edge, a window and a kick rail. [closed] 0 open .. 1 shut.
 */
private fun DrawScope.drawDoors(closed: Float, c: TtColors, dot: Float) {
    if (closed <= 0f) return
    val half = size.width / 2f
    val travel = (half * (1f - closed)).roundToInt().toFloat()
    val h = size.height
    fun door(left: Float, stripLeft: Float) {
        drawRect(c.raised, Offset(left, 0f), Size(half, h))
        drawRect(c.caution, Offset(stripLeft, 0f), Size(dot * 3, h))
        val inset = 30f / 390f * size.width
        val winTop = h * 150f / 844f
        val winH = h * 300f / 844f
        drawRect(c.divider, Offset(left + inset, winTop), Size(half - 2 * inset, winH))
        drawRect(c.screen, Offset(left + inset + 2 * dot, winTop + 2 * dot), Size(half - 2 * inset - 4 * dot, winH - 4 * dot))
        drawRect(c.divider, Offset(left + inset, h * 520f / 844f), Size(half - 2 * inset, 2 * dot))
    }
    val leftDoor = -travel
    door(leftDoor, leftDoor + half - dot * 3)
    val rightDoor = half + travel
    door(rightDoor, rightDoor)
}

// ---------------------------------------------------------------- split-flap rows

/** How far each marked row of a page has flipped in. */
@Stable
fun interface FlapClock {
    /** 0 hidden .. 1 shown. Read only in draw / layer lambdas. */
    fun rowProgress(index: Int): Float

    companion object {
        val Settled = FlapClock { 1f }
    }
}

private class EntryFlapClock(private val controller: TransitionController, private val entryId: Long) : FlapClock {
    override fun rowProgress(index: Int): Float {
        val run = controller.running ?: return 1f
        if (run.transition != PageTransition.SplitFlap || run.change.to.id != entryId) return 1f
        return Timeline.flapRowProgress(index, controller.elapsedMs)
    }
}

/** The page's flap clock; [TransitionHost] provides one per page. */
val LocalFlapClock = staticCompositionLocalOf { FlapClock.Settled }

/**
 * Marks a row for the split-flap transition (plan 3.4): when the page arrives by split-flap, the
 * row flips in from its top edge, row [index] 60 ms after row [index] - 1 (all by 500 ms). Number
 * rows from the top of the page, starting at 0. Rows without the modifier appear at once.
 * Costs nothing when no transition runs (it only reads a clock in the layer phase).
 */
@Composable
fun Modifier.flapRow(index: Int): Modifier {
    val clock = LocalFlapClock.current
    return graphicsLayer {
        val p = clock.rowProgress(index)
        scaleY = p
        alpha = p
        transformOrigin = TransformOrigin(0.5f, 0f)
    }
}

// ---------------------------------------------------------------- input

/** Finishes a running transition on any touch; swallows the touch only before input is allowed. */
private fun Modifier.transitionTaps(controller: TransitionController): Modifier = pointerInput(controller) {
    awaitPointerEventScope {
        val swallowed = mutableSetOf<PointerId>()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            for (change in event.changes) {
                if (change.pressed && !change.previousPressed && controller.running != null) {
                    if (controller.onTap()) swallowed += change.id
                }
                if (change.id in swallowed) {
                    change.consume()
                    if (!change.pressed) swallowed -= change.id
                }
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.swallowAll() {
    awaitPointerEventScope {
        while (true) awaitPointerEvent().changes.forEach { it.consume() }
    }
}

// ---------------------------------------------------------------- previews

/**
 * One frozen frame of [change] at [elapsedMs] with [from] and [to] as the two pages: for
 * filmstrips and the debug gallery. Reduce motion is ignored here (it shows the effect).
 */
@Composable
fun TransitionPreview(
    change: NavChange,
    elapsedMs: Long,
    widthDp: Float,
    from: @Composable () -> Unit,
    to: @Composable () -> Unit,
) {
    val controller = remember(change, elapsedMs) {
        TransitionController().also {
            it.start(change, widthDp)
            it.advanceBy(elapsedMs)
        }
    }
    val holder = rememberSaveableStateHolder()
    val entries = if (controller.running != null) listOf(change.from, change.to) else listOf(change.to)
    TransitionStage(controller, entries, holder) { e -> if (e.id == change.from.id) from() else to() }
}
