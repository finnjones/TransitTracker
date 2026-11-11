package app.transittracker.timetable

import app.transittracker.model.Mode

/**
 * Stable bit assignment for [Mode] in pack files. Explicit (not `ordinal`) so that reordering
 * the enum can never change the meaning of an existing pack.
 */
object ModeBits {
    private val bits: Map<Mode, Int> = mapOf(
        Mode.TRAIN to 1,
        Mode.METRO to 2,
        Mode.LIGHT_RAIL to 4,
        Mode.BUS to 8,
        Mode.FERRY to 16,
        Mode.COACH to 32,
        Mode.OTHER to 64,
    )

    fun bit(mode: Mode): Int = bits.getValue(mode)

    fun encode(modes: Collection<Mode>): Int = modes.fold(0) { acc, m -> acc or bit(m) }

    fun decode(mask: Int): Set<Mode> = bits.entries.filter { mask and it.value != 0 }.mapTo(LinkedHashSet()) { it.key }

    /** Single mode from a one-bit mask (OTHER if none or unknown). */
    fun single(mask: Int): Mode = bits.entries.firstOrNull { it.value == mask }?.key ?: Mode.OTHER

    /**
     * Maps a GTFS `route_type` (basic or extended) to a [Mode]. TfNSW uses 2 (Sydney Trains),
     * 401 (Metro), 900 (light rail), 4 (ferries), 700/712/714 (buses) and 204 (coaches).
     */
    fun modeForRouteType(routeType: Int): Mode = when (routeType) {
        0, in 900..999 -> Mode.LIGHT_RAIL
        1, in 400..499 -> Mode.METRO
        2, in 100..199 -> Mode.TRAIN
        3, 11, in 700..799 -> Mode.BUS
        4, in 1000..1099, in 1200..1299 -> Mode.FERRY
        in 200..299 -> Mode.COACH
        else -> Mode.OTHER
    }
}
