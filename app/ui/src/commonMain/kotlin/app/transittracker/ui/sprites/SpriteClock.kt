package app.transittracker.ui.sprites

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.delay

/**
 * The one shared animation clock (plan 3.6): about 8 ticks a second. Sprites take their frame
 * from it, "your stop" and the platform-change sign blink from it (1 Hz = 4 ticks on, 4 off),
 * and journey strips re-place their vehicle on every tick.
 *
 * Read [tick] only inside a draw lambda (Canvas, drawBehind, graphicsLayer) so a tick redraws
 * without recomposing. [TtTheme] provides a running clock; screenshots and tests pass a
 * [FixedSpriteClock].
 */
@Stable
interface SpriteClock {
    val tick: Int

    companion object {
        const val TICK_MS = 125L
        const val TICKS_PER_SECOND = 8
    }
}

/** A clock that never moves; for screenshots, tests and previews. */
class FixedSpriteClock(override val tick: Int = 0) : SpriteClock

/** Ticks while composed. Frames are requested only 8 times a second, and stop with the window. */
class RunningSpriteClock : SpriteClock {
    override var tick by mutableIntStateOf(0)
        internal set
}

@Composable
internal fun rememberRunningSpriteClock(): RunningSpriteClock {
    val clock = remember { RunningSpriteClock() }
    LaunchedEffect(clock) {
        var start = -1L
        while (true) {
            // Wait most of the tick off the frame clock, then align to a frame so ticks pause
            // whenever the platform stops producing frames (app hidden).
            delay(SpriteClock.TICK_MS - 10)
            withFrameMillis { now ->
                if (start < 0) start = now
                clock.tick = ((now - start) / SpriteClock.TICK_MS).toInt()
            }
        }
    }
    return clock
}

/** Animation frame for a [frames]-long cycle, or 0 when motion is reduced. */
fun SpriteClock.frame(frames: Int, reduceMotion: Boolean): Int =
    if (reduceMotion || frames <= 1) 0 else tick.mod(frames)

/** 1 Hz blink: true for the first half of each second. Always true when motion is reduced. */
fun SpriteClock.blinkOn(reduceMotion: Boolean): Boolean =
    reduceMotion || (tick / (SpriteClock.TICKS_PER_SECOND / 2)) % 2 == 0
