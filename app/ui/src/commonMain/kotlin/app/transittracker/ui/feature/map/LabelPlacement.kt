package app.transittracker.ui.feature.map

/** A rectangle in screen pixels (left, top inclusive; right, bottom exclusive). */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun overlaps(o: Box, gap: Float = 0f): Boolean =
        left < o.right + gap && o.left < right + gap && top < o.bottom + gap && o.top < bottom + gap

    fun inside(o: Box): Boolean = left >= o.left && top >= o.top && right <= o.right && bottom <= o.bottom

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/** Where a label may go relative to its marker. */
enum class LabelSide { RIGHT, LEFT, BELOW, ABOVE }

/**
 * One label to place: [anchor] is its marker's box, [width] x [height] the label's size,
 * [sides] the places to try in order, [priority] lower first (your stops before the rest).
 */
data class LabelRequest(
    val id: String,
    val anchor: Box,
    val width: Float,
    val height: Float,
    val priority: Int,
    val sides: List<LabelSide> = DEFAULT_SIDES,
)

val DEFAULT_SIDES = listOf(LabelSide.RIGHT, LabelSide.LEFT, LabelSide.ABOVE, LabelSide.BELOW)

/** The box of a label of [w] x [h] on [side] of [anchor], [gap] pixels away. */
fun labelBox(anchor: Box, w: Float, h: Float, side: LabelSide, gap: Float): Box {
    val cy = (anchor.top + anchor.bottom) / 2
    val cx = (anchor.left + anchor.right) / 2
    return when (side) {
        LabelSide.RIGHT -> Box(anchor.right + gap, cy - h / 2, anchor.right + gap + w, cy + h / 2)
        LabelSide.LEFT -> Box(anchor.left - gap - w, cy - h / 2, anchor.left - gap, cy + h / 2)
        LabelSide.BELOW -> Box(cx - w / 2, anchor.bottom + gap, cx + w / 2, anchor.bottom + gap + h)
        LabelSide.ABOVE -> Box(cx - w / 2, anchor.top - gap - h, cx + w / 2, anchor.top - gap)
    }
}

/**
 * Places labels so that none collide (plan 5.5: station names that do not collide):
 *
 * 1. Requests go in [LabelRequest.priority] order (ties keep the given order).
 * 2. Each tries its sides in order; the first box that lies inside [bounds], overlaps no label
 *    already placed (with [gap] pixels clear), no marker other than its own ([markers]) and no
 *    [obstacles] (controls drawn over the map) wins. A box that runs off the edge slides along
 *    its side, by up to half its length, to come back on screen ([slideInside]): a flag over a
 *    stop near the edge stays over its stop instead of being dropped.
 * 3. A label with no free side is left out: the marker stays, the name is dropped. Zooming in
 *    makes room, so names come back.
 *
 * Returns the placed boxes by id. Pure.
 */
fun placeLabels(
    requests: List<LabelRequest>,
    bounds: Box,
    markers: List<Box> = emptyList(),
    obstacles: List<Box> = emptyList(),
    gap: Float = 4f,
): Map<String, Box> {
    val placed = LinkedHashMap<String, Box>()
    val sorted = requests.withIndex().sortedWith(compareBy({ it.value.priority }, { it.index })).map { it.value }
    for (r in sorted) {
        for (side in r.sides) {
            val b = slideInside(labelBox(r.anchor, r.width, r.height, side, gap), side, bounds) ?: continue
            if (placed.values.any { it.overlaps(b, gap) }) continue
            if (markers.any { it != r.anchor && it.overlaps(b) }) continue
            if (obstacles.any { it.overlaps(b) }) continue
            placed[r.id] = b
            break
        }
    }
    return placed
}

/**
 * [b] (a label on [side] of its marker) moved along that side, by at most half its own length,
 * so it lies inside [bounds]: across for a label above or below, up or down for one beside.
 * The label still reaches past its marker's centre. Null when no such move fits.
 */
fun slideInside(b: Box, side: LabelSide, bounds: Box): Box? {
    if (b.inside(bounds)) return b
    val across = side == LabelSide.ABOVE || side == LabelSide.BELOW
    val (lo, hi, min, max) = if (across) listOf(b.left, b.right, bounds.left, bounds.right) else listOf(b.top, b.bottom, bounds.top, bounds.bottom)
    val limit = (hi - lo) / 2
    val shift = when {
        lo < min -> min - lo
        hi > max -> max - hi
        else -> 0f
    }
    if (kotlin.math.abs(shift) > limit) return null
    val moved = if (across) b.copy(left = b.left + shift, right = b.right + shift) else b.copy(top = b.top + shift, bottom = b.bottom + shift)
    return moved.takeIf { it.inside(bounds) }
}
