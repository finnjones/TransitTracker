package app.transittracker.ui.loading

import androidx.compose.ui.graphics.Color
import app.transittracker.model.Mode

/** A vehicle a loader shows while the answer, and so its mode, is not known yet. */
class LoaderVehicle(val mode: Mode, val livery: Color)

/**
 * The vehicles a loader turns through, in Sydney's network colours (T1, the bus fleet, F1, L2,
 * M1). A loader runs before any timetable answer, so there is no `Route.color` to take them from.
 */
val LoaderFleet: List<LoaderVehicle> = listOf(
    LoaderVehicle(Mode.TRAIN, Color(0xFFF99D1C)),
    LoaderVehicle(Mode.BUS, Color(0xFF00B5EF)),
    LoaderVehicle(Mode.FERRY, Color(0xFF00774B)),
    LoaderVehicle(Mode.LIGHT_RAIL, Color(0xFFE4022D)),
    LoaderVehicle(Mode.METRO, Color(0xFF168388)),
)

/**
 * The timing of the loaders, in ticks of the shared sprite clock (8 a second, plan 3.6). Pure,
 * so it is tested without a screen. Every loader is settled at tick 0, which is what a
 * screenshot or a fixed clock shows, and holds that frame under reduce motion.
 */
object LoaderTiming {
    /** Each vehicle is shown for this long (1.125 s) before the next takes its place. */
    const val VEHICLE_TICKS = 9
    /** Added to the tick so that tick 0 falls in the middle of a vehicle's turn. */
    const val PHASE = 4
    /** The ground runs this many dots a tick under a treadmill vehicle. */
    const val SCROLL_DOTS = 2
    /** Sleepers repeat every 4 dots and road dashes every 7: the scroll wraps where both do. */
    const val SCROLL_WRAP = 28
    /** Letters of a flap board that settle on each tick after a flip. */
    const val LETTERS_PER_TICK = 6
    /** A board row flips once in this many ticks (1.75 s); each row is one tick behind the row above. */
    const val ROW_TICKS = 14
    /** Added to the tick so that every row of a five-row board is open at tick 0. */
    const val ROW_PHASE = 7
}

/**
 * One frame of a treadmill: which [vehicle] of the fleet, how far its body is from the centre
 * ([offsetDots], negative while it drives in from the left), how far the ground has run
 * ([scrollDots]) and the sprite's moving frame.
 */
data class TreadmillFrame(val vehicle: Int, val offsetDots: Int, val scrollDots: Int, val spriteFrame: Int)

/**
 * The treadmill at [tick]: the vehicle holds the centre while the ground runs under it. With
 * more than one vehicle, each drives off to the right in two steps and the next drives in from
 * the left in two.
 */
fun treadmillFrame(tick: Int, vehicles: Int, reduceMotion: Boolean): TreadmillFrame {
    if (reduceMotion || vehicles <= 0) return TreadmillFrame(0, 0, 0, 0)
    val t = tick + LoaderTiming.PHASE
    val step = t.mod(LoaderTiming.VEHICLE_TICKS)
    val offset = if (vehicles == 1) 0 else when (step) {
        0 -> -30
        1 -> -10
        LoaderTiming.VEHICLE_TICKS - 2 -> 10
        LoaderTiming.VEHICLE_TICKS - 1 -> 30
        else -> 0
    }
    return TreadmillFrame(
        vehicle = t.floorDiv(LoaderTiming.VEHICLE_TICKS).mod(vehicles),
        offsetDots = offset,
        scrollDots = (t * LoaderTiming.SCROLL_DOTS).mod(LoaderTiming.SCROLL_WRAP),
        spriteFrame = t.mod(4),
    )
}

/** One frame of a flap tile: its [vehicle], and whether the flap is [half] way over. */
data class FlapTileFrame(val vehicle: Int, val half: Boolean, val scrollDots: Int, val spriteFrame: Int)

/**
 * The flap tile at [tick]: a vehicle running on the spot. On the last tick of its turn the flap
 * is half over; on the first tick of the next turn it is half over with the next vehicle on it.
 */
fun flapTileFrame(tick: Int, vehicles: Int, reduceMotion: Boolean): FlapTileFrame {
    if (reduceMotion || vehicles <= 0) return FlapTileFrame(0, false, 0, 0)
    val t = tick + LoaderTiming.PHASE
    val step = t.mod(LoaderTiming.VEHICLE_TICKS)
    return FlapTileFrame(
        vehicle = t.floorDiv(LoaderTiming.VEHICLE_TICKS).mod(vehicles),
        half = vehicles > 1 && (step == 0 || step == LoaderTiming.VEHICLE_TICKS - 1),
        scrollDots = (t * LoaderTiming.SCROLL_DOTS).mod(LoaderTiming.SCROLL_WRAP),
        spriteFrame = t.mod(4),
    )
}

/**
 * The character shown in cell [index] of a flap board whose settled text has [target] there.
 * The cells spin through the alphabet when the tile flips and settle from the left,
 * [LoaderTiming.LETTERS_PER_TICK] a tick. A blank cell stays blank.
 */
fun boardLetter(target: Char, index: Int, tick: Int, reduceMotion: Boolean): Char {
    if (reduceMotion || target == ' ') return target
    val t = tick + LoaderTiming.PHASE
    val step = t.mod(LoaderTiming.VEHICLE_TICKS)
    if (step > index / LoaderTiming.LETTERS_PER_TICK) return target
    return 'A' + (index * 7 + t * 5).mod(26)
}

/**
 * How far open board row [row] is at [tick] (1 = open): each row snaps nearly shut and opens
 * again in two steps, one row after another down the board.
 */
fun boardRowOpen(row: Int, tick: Int, reduceMotion: Boolean): Float {
    if (reduceMotion) return 1f
    return when ((tick + LoaderTiming.ROW_PHASE - row).mod(LoaderTiming.ROW_TICKS)) {
        0 -> 0.1f
        1 -> 0.55f
        else -> 1f
    }
}
