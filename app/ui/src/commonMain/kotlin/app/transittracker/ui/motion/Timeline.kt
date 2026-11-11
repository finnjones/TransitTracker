package app.transittracker.ui.motion

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.transittracker.model.Mode
import app.transittracker.ui.nav.NavChange
import app.transittracker.ui.sprites.SpriteSheet
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * The timings of plan 3.4, as prototyped in the "Page transitions" artboard
 * (`Transitions.dc.html`). Pure, so they are tested without a screen.
 *
 * Everything is stepped: the vehicle and the wipe move in 20 ms steps (the prototype's
 * `steps(15)` over 300 ms), the doors in five 120 ms steps, each flap row in five 56 ms steps.
 * The wipe edge travels at screen width / 300 ms and the vehicle's nose rides on it, so the page
 * is uncovered right behind the vehicle; a long vehicle keeps going until its tail leaves.
 */
object Timeline {
    /** One step of the wipe and the vehicle. */
    const val STEP_MS = 20L

    /** The wipe uncovers the whole page in this time; the vehicle's nose crosses the screen in it. */
    const val WIPE_MS = 300L

    /** Doors: close, hold, open. The page swaps behind them at [DOORS_SWAP_MS]. */
    const val DOORS_MS = 600L
    const val DOORS_SWAP_MS = 300L
    private const val DOORS_STEP_MS = 120L

    /** One flap row's flip, and the delay between rows; every row is done by [FLAP_MS]. */
    const val FLAP_ROW_MS = 280L
    const val FLAP_ROW_DELAY_MS = 60L
    const val FLAP_MS = 500L
    private const val FLAP_ROW_STEPS = 5

    /** The new page takes touches by this time, whatever the decoration is doing (plan 3.4). */
    const val INPUT_MS = 300L

    /** Vehicle crossing the screen: sprite scale (dots per art pixel). Plan 2.3's one exception. */
    const val VEHICLE_SCALE = 2

    /** Elapsed time rounded down to a whole step. */
    fun stepped(elapsedMs: Long, step: Long = STEP_MS): Long = (elapsedMs / step) * step

    /** The sprite sheet that crosses for [mode]: four cars / coupled pair where there is one. */
    fun vehicleSheet(mode: Mode): SpriteSheet = SpriteSheet.forMode(mode, long = true)

    /** Width of the crossing vehicle in dp (2 dp dots, [VEHICLE_SCALE]). */
    fun vehicleWidthDp(mode: Mode): Float = vehicleSheet(mode).frameWidth * VEHICLE_SCALE * 2f

    /**
     * How long a vehicle pass lasts on a screen [screenWidthDp] wide: until the vehicle's tail
     * has left the screen (a four-car train on a phone: about 640 ms; a bus: about 400 ms).
     */
    fun vehiclePassMs(mode: Mode, screenWidthDp: Float): Long {
        val total = WIPE_MS * (screenWidthDp + vehicleWidthDp(mode)) / screenWidthDp
        return ceil(total / STEP_MS).toLong() * STEP_MS
    }

    /** Fraction of the page uncovered (0..1). */
    fun wipeFraction(elapsedMs: Long): Float = min(1f, stepped(elapsedMs).toFloat() / WIPE_MS)

    /**
     * Where the vehicle's nose is, as a fraction of the screen width travelled from the starting
     * edge (0 = at the edge, 1 = at the far edge; beyond 1 the tail is still crossing).
     */
    fun noseFraction(elapsedMs: Long): Float = stepped(elapsedMs).toFloat() / WIPE_MS

    /** How far the doors are closed: 0 open, 1 shut. Steps 0, ½, 1, 1, ½, then 0. */
    fun doorsClosed(elapsedMs: Long): Float {
        if (elapsedMs >= DOORS_MS) return 0f
        return when ((elapsedMs.coerceAtLeast(0) / DOORS_STEP_MS).toInt()) {
            0 -> 0f
            1 -> 0.5f
            2, 3 -> 1f
            else -> 0.5f
        }
    }

    /** Doors: true once the new page is behind the doors. */
    fun doorsShowNewPage(elapsedMs: Long): Boolean = elapsedMs >= DOORS_SWAP_MS

    /** Start of flap row [index]: 60 ms apart, never later than [FLAP_MS] - [FLAP_ROW_MS]. */
    fun flapRowStartMs(index: Int): Long = min(index.coerceAtLeast(0) * FLAP_ROW_DELAY_MS, FLAP_MS - FLAP_ROW_MS)

    /** How far row [index] has flipped in (0 hidden .. 1 fully shown), in five steps. */
    fun flapRowProgress(index: Int, elapsedMs: Long): Float {
        val local = elapsedMs - flapRowStartMs(index)
        if (local >= FLAP_ROW_MS) return 1f
        if (local < 0) return 0f
        val p = local.toFloat() / FLAP_ROW_MS
        return floor(p * FLAP_ROW_STEPS) / FLAP_ROW_STEPS
    }

    /** Total length of [transition] (0 for a cut). */
    fun durationMs(transition: PageTransition, screenWidthDp: Float): Long = when (transition) {
        is PageTransition.VehiclePass -> vehiclePassMs(transition.mode, screenWidthDp)
        PageTransition.Doors -> DOORS_MS
        PageTransition.SplitFlap -> FLAP_MS
        PageTransition.Cut -> 0L
    }

    /** When the new page starts taking touches: never later than [INPUT_MS]. */
    fun inputAfterMs(transition: PageTransition): Long = when (transition) {
        is PageTransition.VehiclePass -> WIPE_MS
        PageTransition.Doors -> DOORS_SWAP_MS
        PageTransition.SplitFlap -> FLAP_ROW_MS
        PageTransition.Cut -> 0L
    }.coerceAtMost(INPUT_MS)
}

/** A transition in progress. */
data class RunningTransition(
    val change: NavChange,
    /** What is actually played (a cut never becomes a running transition). */
    val transition: PageTransition,
    val durationMs: Long,
)

/**
 * Runs at most one transition at a time (plan 3.4) and decides what plays:
 *  - reduce motion / battery saver: everything is a cut;
 *  - a navigation while a transition runs finishes the running one and is itself a cut;
 *  - a tap during a transition finishes it; the tap is swallowed only before the new page takes
 *    input ([Timeline.inputAfterMs]), so the new page always gets touches by 300 ms.
 *
 * Time is fed in by the host ([onFrame]) so the rules are tested without a clock.
 */
@Stable
class TransitionController(private val reduceMotion: () -> Boolean = { false }) {
    var running: RunningTransition? by mutableStateOf(null)
        private set

    /** Milliseconds since [running] started. Snapshot state: read it in draw or layout lambdas. */
    var elapsedMs: Long by mutableLongStateOf(0L)
        private set

    private var startFrameMs = -1L

    /** The last [NavChange.serial] the host handed over (so a change is played once). */
    internal var lastSerial = 0L

    /** Plays [change] on a screen [screenWidthDp] wide. */
    fun start(change: NavChange, screenWidthDp: Float) {
        val wasRunning = running != null
        if (wasRunning) finish()
        val t = if (reduceMotion() || wasRunning) PageTransition.Cut else change.transition
        val duration = Timeline.durationMs(t, screenWidthDp)
        if (duration <= 0) return
        running = RunningTransition(change, t, duration)
        elapsedMs = 0
        startFrameMs = -1
    }

    /** A frame at [frameTimeMs] (any monotonic clock). */
    fun onFrame(frameTimeMs: Long) {
        val r = running ?: return
        if (startFrameMs < 0) startFrameMs = frameTimeMs
        elapsedMs = frameTimeMs - startFrameMs
        if (elapsedMs >= r.durationMs) finish()
    }

    /** Advances by [deltaMs] (tests, filmstrips). */
    fun advanceBy(deltaMs: Long) {
        val r = running ?: return
        if (startFrameMs < 0) startFrameMs = 0
        elapsedMs += deltaMs
        if (elapsedMs >= r.durationMs) finish()
    }

    /** True when the new page should get touches now. */
    val acceptsInput: Boolean
        get() {
            val r = running ?: return true
            return elapsedMs >= Timeline.inputAfterMs(r.transition)
        }

    /**
     * A finger went down. Finishes a running transition; returns true when the touch must be
     * swallowed (the new page was not yet taking input).
     */
    fun onTap(): Boolean {
        if (running == null) return false
        val swallow = !acceptsInput
        finish()
        return swallow
    }

    /** Jumps to the end. */
    fun finish() {
        running = null
        elapsedMs = 0
        startFrameMs = -1
    }
}
