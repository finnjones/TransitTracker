package app.transittracker.ui.feature.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.transittracker.model.WallClock
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelIconButton
import app.transittracker.ui.components.PixelIconData
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.pixelBorder
import app.transittracker.ui.components.pixelFill
import app.transittracker.ui.feature.map.TopDown.drawTurned
import app.transittracker.ui.format.badgeTextColor
import app.transittracker.ui.sprites.blinkOn
import app.transittracker.ui.sprites.frame
import app.transittracker.ui.theme.ColorRole
import app.transittracker.ui.theme.TtColors
import app.transittracker.ui.theme.TtTheme
import app.transittracker.ui.theme.TtType
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** The RECENTRE icon: a crosshair on the 7 x 7 grid. */
internal val RecentreIcon = PixelIconData(7, 7, "M3 0h1v2h-1z M0 3h2v1h-2z M5 3h2v1h-2z M3 5h1v2h-1z M3 3h1v1h-1z")

/** Grid spacing of the map ground, in dots (26 dp in the Track mockup). */
private const val GRID_DOTS = 13

/**
 * The app's own pixel line map (decision F5): a dark grid, lines as whole-dot polylines in their
 * line colour, stations as square markers with names that never collide, vehicles from above.
 * One Canvas draws everything (no node per element), so dozens of vehicles stay cheap; it redraws
 * on the shared sprite clock so vehicles creep between reports.
 *
 * Pan with a drag, zoom with a pinch or the +/- buttons (within [ZoomLimits]); RECENTRE goes back
 * to [MapScene.focus]. [fitKey] changing also recentres (a new service, NEAR ME). [topInsetDp] /
 * [bottomInsetDp] are covered by overlays: fitting keeps content out from under them.
 *
 * Taps: [onStationTap], [onVehicleTap] (with a 48 dp target), else [onEmptyTap]. For the screen
 * reader the map is one image described by [description], with [actions] as custom actions.
 */
@Composable
fun PixelMap(
    scene: MapScene,
    clock: WallClock,
    description: String,
    modifier: Modifier = Modifier,
    fitKey: Any? = null,
    topInsetDp: Dp = 0.dp,
    bottomInsetDp: Dp = 0.dp,
    onStationTap: ((SceneStation) -> Unit)? = null,
    onVehicleTap: ((SceneVehicle) -> Unit)? = null,
    onEmptyTap: (() -> Unit)? = null,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val c = TtTheme.colors
    val type = TtTheme.type
    val dot = TtTheme.dot
    val spriteClock = TtTheme.spriteClock
    val reduce = TtTheme.motion.reduceMotion
    val measurer = rememberTextMeasurer()
    var viewPx by remember { mutableStateOf(IntSize.Zero) }
    // null = follow the focus (fit); set once the user pans or zooms.
    var camera by remember { mutableStateOf<MapCamera?>(null) }
    LaunchedEffect(fitKey) { camera = null }
    val currentScene by rememberUpdatedState(scene)
    val labels = remember(scene.stations, type, measurer) { measureLabels(scene.stations, measurer, type) }
    val badges = remember(scene.lines, type, measurer) {
        scene.lines.mapNotNull { l -> l.badge?.let { l.id to measurer.measure(numeralText(it, type.numerals), type.label11Bold, maxLines = 1) } }.toMap()
    }
    val captions = remember(scene.vehicles, type, measurer) {
        scene.vehicles.mapNotNull { v -> v.caption?.let { v.id to measurer.measure(it, type.label10Bold.copy(letterSpacing = 1.sp)) } }.toMap()
    }
    val rasters = remember { RasterCache() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topInsetDots = with(density) { (topInsetDp.toPx() / dot.px).roundToInt() }
    val bottomInsetDots = with(density) { (bottomInsetDp.toPx() / dot.px).roundToInt() }
    val controlsWidthPx = with(density) { (48.dp + 16.dp + 8.dp).toPx() }
    val controlsHeightPx = with(density) { (topInsetDp + 16.dp + 52.dp * 3 + 8.dp * 2).toPx() }

    fun viewDots(): Pair<Int, Int> = max(1, viewPx.width / dot.px) to max(1, viewPx.height / dot.px)

    fun fitCamera(s: MapScene): MapCamera {
        val (w, h) = viewDots()
        val box = (s.focus ?: s.bounds ?: MapBox(-500.0, -500.0, 500.0, 500.0)).let {
            if (it.width < 400 && it.height < 400) it.grow(400.0) else it
        }
        val margin = 24
        return MapCamera.fit(box, w, h, limitsFor(s, w, h), marginDots = margin, marginTop = margin + topInsetDots, marginBottom = margin + bottomInsetDots)
    }

    fun cameraNow(): MapCamera = camera ?: fitCamera(currentScene)

    val sem = Modifier.semantics {
        contentDescription = description
        if (actions.isNotEmpty()) customActions = actions.map { (label, run) -> CustomAccessibilityAction(label) { run(); true } }
    }

    Box(modifier.onSizeChanged { viewPx = it }) {
        Canvas(
            Modifier
                .fillMaxSize()
                .then(sem)
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val (w, h) = viewDots()
                        val s = currentScene
                        var cam = cameraNow()
                        if (zoom != 1f) cam = cam.zoomAbout(zoom.toDouble(), centroid.x / dot.px.toDouble(), centroid.y / dot.px.toDouble(), w, h, limitsFor(s, w, h))
                        cam = cam.panBy(pan.x / dot.px.toDouble(), pan.y / dot.px.toDouble())
                        camera = cam.clampTo(s.bounds?.grow(2_000.0))
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val (w, h) = viewDots()
                        val hit = hitTest(currentScene, cameraNow(), w, h, dot.px.toFloat(), pos, 24.dp.toPx(), clock.nowMillis(), reduce)
                        when (hit) {
                            is SceneStation -> onStationTap?.invoke(hit)
                            is SceneVehicle -> onVehicleTap?.invoke(hit)
                            else -> onEmptyTap?.invoke()
                        }
                    }
                },
        ) {
            val dotPx = dot.px.toFloat()
            val (w, h) = viewDots()
            val cam = cameraNow()
            val nowMs = clock.nowMillis()
            // blinkOn / frame read the sprite clock's tick, so the map redraws on every tick
            // (8 a second) and vehicles creep between reports.
            drawGround(c, cam, w, h, dotPx)
            val spots = scene.vehicles.associate { it.id to it.spotAt(nowMs, reduce) }
            drawLines(scene, cam, w, h, dotPx, rasters, spots, c)
            scene.you?.let { drawYou(cam.toDots(it, w, h), dotPx, c) }
            val markers = drawStations(scene, cam, w, h, dotPx, c, spriteClock.blinkOn(reduce))
            val vehicleBoxes = drawVehicles(scene, spots, cam, w, h, dotPx, c, spriteClock.frame(2, reduce))
            // The zoom controls, and your position (labels and badges keep off it).
            val youBox = scene.you?.let { cam.toDots(it, w, h) }?.let { p -> Box((p.ix - 8) * dotPx, (p.iy - 8) * dotPx, (p.ix + 8) * dotPx, (p.iy + 8) * dotPx) }
            val obstacles = listOfNotNull(Box(size.width - controlsWidthPx, 0f, size.width, controlsHeightPx), youBox)
            drawLabels(scene, cam, w, h, labels, badges, markers, obstacles, captions, vehicleBoxes, dotPx, c)
        }
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 16.dp + topInsetDp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            fun zoomBy(f: Double) {
                val (w, h) = viewDots()
                camera = cameraNow().zoomAbout(f, w / 2.0, h / 2.0, w, h, limitsFor(currentScene, w, h))
            }
            PixelIconButton(PixelIcons.Plus, "Zoom in", { zoomBy(1.6) })
            PixelIconButton(PixelIcons.Minus, "Zoom out", { zoomBy(1 / 1.6) })
            PixelIconButton(RecentreIcon, "Recentre", { camera = null })
        }
        if (scene.schematic) {
            Box(
                Modifier.align(Alignment.TopStart).padding(top = 16.dp + topInsetDp, start = 16.dp)
                    .pixelFill(c.panel).pixelBorder(c.divider).padding(horizontal = dot.dp + 6.dp, vertical = dot.dp + 4.dp),
            ) { Label("SCHEMATIC · NOT TO SCALE", color = c.textSecondary, size = LabelSize.L10) }
        }
    }
}

/** Zoom range for [scene] in a view of [w] x [h] dots: never wider than 1.6 x the whole map. */
private fun limitsFor(scene: MapScene, w: Int, h: Int): ZoomLimits {
    val box = scene.bounds ?: return ZoomLimits.Default
    val all = max(box.width / max(1, w - 48), box.height / max(1, h - 48))
    return ZoomLimits(ZoomLimits.Default.min, max(all * 1.6, 60.0))
}

// ---------------------------------------------------------------- drawing

private fun DrawScope.drawGround(c: TtColors, cam: MapCamera, w: Int, h: Int, dotPx: Float) {
    drawRect(c.screen)
    // Grid lines anchored to the map plane, so they move with the map.
    val origin = cam.toDots(MapPoint(0.0, 0.0), w, h)
    val ox = floor(origin.x).toInt().mod(GRID_DOTS)
    val oy = floor(origin.y).toInt().mod(GRID_DOTS)
    val grid = if (c.isDay) c.raised else c.panel
    var x = ox
    while (x < w) {
        drawRect(grid, Offset(x * dotPx, 0f), Size(dotPx, size.height))
        x += GRID_DOTS
    }
    var y = oy
    while (y < h) {
        drawRect(grid, Offset(0f, y * dotPx), Size(size.width, dotPx))
        y += GRID_DOTS
    }
}

/** Rasterised lines for one camera (rebuilt only when the camera, view or scene changes). */
private class RasterCache {
    var key: Any? = null
    var value: List<Pair<SceneLine, List<PathCell>>> = emptyList()
}

private fun DrawScope.drawLines(
    scene: MapScene,
    cam: MapCamera,
    w: Int,
    h: Int,
    dotPx: Float,
    cache: RasterCache,
    spots: Map<String, VehicleSpot?>,
    c: TtColors,
) {
    val key = listOf(scene.lines, cam, w, h)
    if (cache.key != key) {
        val lw = scene.lineWidthDots
        cache.value = scene.lines.flatMap { line ->
            line.segments.map { seg ->
                val lane = scene.lanes[seg.key to line.id] ?: 0.0
                val (sx, sy) = if (lane == 0.0 || seg.path.points.size < 2) 0 to 0 else laneStep(seg.path.points.first(), seg.path.points.last())
                val shift = (lane * lw).roundToInt()
                val cells = rasterPath(seg.path, cam, w, h, sx * shift, sy * shift)
                line to if (seg.offset == 0.0) cells else cells.map { it.copy(distance = it.distance + seg.offset) }
            }
        }
        cache.key = key
    }
    val lw = scene.lineWidthDots
    val half = lw / 2
    val brush = Size(lw * dotPx, lw * dotPx)
    for ((line, cells) in cache.value) {
        val travelled = line.travelledBy?.let { spots[it]?.distance }
        val dim = lerp(line.color, c.screen, if (c.isDay) 0.55f else 0.5f)
        for ((i, cell) in cells.withIndex()) {
            // A walk: dashes of two line widths, in the secondary text colour.
            if (line.walk) {
                if ((i / (lw * 2)) % 2 == 1) continue
                val x = (cell.x - half) * dotPx
                val y = (cell.y - half) * dotPx
                drawRect(c.textSecondary, Offset(x, y), Size(dotPx * maxOf(1, lw / 2), dotPx * maxOf(1, lw / 2)))
                continue
            }
            val x = (cell.x - half) * dotPx
            val y = (cell.y - half) * dotPx
            if (x > size.width || y > size.height || x < -brush.width || y < -brush.height) continue
            val color = if (line.done || (travelled != null && cell.distance < travelled)) dim else line.color
            drawRect(color, Offset(x, y), brush)
        }
    }
}

/** Marker size in dots for a station; a minor stop is as wide as the line it notches. */
private fun markerDots(s: SceneStation, lineWidthDots: Int): Int = when {
    s.minor -> lineWidthDots
    else -> when (s.kind) {
        StationKind.PASSED -> 6
        StationKind.AHEAD, StationKind.PLAIN, StationKind.NEARBY -> 8
        StationKind.BOARD, StationKind.ALIGHT, StationKind.CHANGE -> 12
    }
}

/** Marker boxes in pixels, by station id. */
private fun markerBox(s: SceneStation, lineWidthDots: Int, cam: MapCamera, w: Int, h: Int, dotPx: Float): Box {
    val p = cam.toDots(s.point, w, h)
    val n = markerDots(s, lineWidthDots)
    val left = (p.ix - n / 2) * dotPx
    val top = (p.iy - n / 2) * dotPx
    return Box(left, top, left + n * dotPx, top + n * dotPx)
}

private fun DrawScope.drawStations(scene: MapScene, cam: MapCamera, w: Int, h: Int, dotPx: Float, c: TtColors, blinkOn: Boolean): Map<String, Box> {
    val out = LinkedHashMap<String, Box>()
    for (s in scene.stations) out[s.id] = markerBox(s, scene.lineWidthDots, cam, w, h, dotPx)
    // Minor stops: a notch punched in the line, so the line stays a line however many stops it
    // has. A notch closer than NOTCH_SPACING_DOTS to a marker or another notch is left out (a
    // bus's stops at the whole journey's zoom would turn the line into a chain); zooming in
    // brings them back.
    val majors = scene.stations.filter { !it.minor }.mapNotNull { out[it.id] }
    val notches = ArrayList<Box>()
    val room = NOTCH_SPACING_DOTS * dotPx
    val hole = max(1, scene.lineWidthDots - 2)
    val inset = (scene.lineWidthDots - hole) / 2 * dotPx
    for (s in scene.stations) {
        if (!s.minor) continue
        val b = out.getValue(s.id)
        if (b.right < 0 || b.bottom < 0 || b.left > size.width || b.top > size.height) continue
        if (majors.any { it.overlaps(b, room) } || notches.any { it.overlaps(b, room) }) continue
        notches += b
        drawRect(c.screen, Offset(b.left + inset, b.top + inset), Size(hole * dotPx, hole * dotPx))
    }
    for (s in scene.stations) {
        if (s.minor) continue
        val b = out.getValue(s.id)
        if (b.right < 0 || b.bottom < 0 || b.left > size.width || b.top > size.height) continue
        val tl = Offset(b.left, b.top)
        val sz = Size(b.width, b.height)
        val border = 2 * dotPx
        when (s.kind) {
            StationKind.PASSED -> drawRect(c.textSecondary, tl, sz)
            StationKind.AHEAD, StationKind.PLAIN -> {
                drawRect(if (s.selected) c.text else c.text, tl, sz)
                drawRect(if (s.selected) c.text else c.screen, Offset(b.left + border, b.top + border), Size(b.width - 2 * border, b.height - 2 * border))
            }
            StationKind.NEARBY -> {
                drawRect(c.info, tl, sz)
                drawRect(if (s.selected) c.info else c.screen, Offset(b.left + border, b.top + border), Size(b.width - 2 * border, b.height - 2 * border))
            }
            StationKind.BOARD, StationKind.ALIGHT, StationKind.CHANGE -> {
                // Filled in Text with a Screen-coloured rim; your boarding stop blinks at 1 Hz.
                val on = s.kind != StationKind.BOARD || blinkOn
                drawRect(c.screen, tl, sz)
                drawRect(c.text.copy(alpha = if (on) 1f else 0.3f), Offset(b.left + border, b.top + border), Size(b.width - 2 * border, b.height - 2 * border))
            }
        }
    }
    return out
}

/** Least room, in dots, between a minor stop's notch and any other marker or notch. */
private const val NOTCH_SPACING_DOTS = 6

private fun DrawScope.drawYou(p: DotXY, dotPx: Float, c: TtColors) {
    // A ring with a centre dot, the Locate icon at two dots per cell, in Info.
    val cell = 2 * dotPx
    val left = (p.ix - 7) * dotPx
    val top = (p.iy - 7) * dotPx
    drawRect(c.screen, Offset(left - dotPx, top - dotPx), Size(16 * dotPx, 16 * dotPx))
    for (r in PixelIcons.Locate.rects) {
        drawRect(c.info, Offset(left + r[0] * cell, top + r[1] * cell), Size(r[2] * cell, r[3] * cell))
    }
}

private fun DrawScope.drawVehicles(
    scene: MapScene,
    spots: Map<String, VehicleSpot?>,
    cam: MapCamera,
    w: Int,
    h: Int,
    dotPx: Float,
    c: TtColors,
    frame: Int,
): Map<String, Box> {
    val out = HashMap<String, Box>()
    val outline = if (c.outlineSprites) c.spriteOutline else null
    for (v in scene.vehicles) {
        val spot = spots[v.id] ?: continue
        val p = cam.toDots(spot.point, w, h)
        val (sx, sy) = vehicleLaneShift(scene, v, spot)
        val cx = (p.ix + sx) * dotPx
        val cy = (p.iy + sy) * dotPx
        if (cx < -80 * dotPx || cy < -80 * dotPx || cx > size.width + 80 * dotPx || cy > size.height + 80 * dotPx) continue
        val grid = TopDown.turned(v.mode, v.cars, headingStep(spot.headingDeg, TopDown.HEADING_STEPS))
        drawTurned(grid, cx, cy, dotPx, v.color, v.look, frame, outline)
        if (v.cancelled) {
            val cross = PixelIcons.LampCross
            val left = floor(cx / dotPx - 3.5f) * dotPx
            val top = floor(cy / dotPx - 3.5f) * dotPx
            for (r in cross.rects) drawRect(c.stop, Offset(left + r[0] * dotPx, top + r[1] * dotPx), Size(r[2] * dotPx, r[3] * dotPx))
        }
        if (v.selected) drawSelection(cx, cy, dotPx, c)
        // Labels keep clear of the drawn cells (the turned grid's corners are mostly empty).
        val left = floor(cx / dotPx - grid.w / 2f) * dotPx
        val top = floor(cy / dotPx - grid.h / 2f) * dotPx
        val e = grid.extent()
        out[v.id] = Box(left + e[0] * dotPx, top + e[1] * dotPx, left + (e[2] + 1) * dotPx, top + (e[3] + 1) * dotPx)
    }
    return out
}

/** Corner brackets around the selected vehicle, in Text, 11 dots out from its centre. */
private fun DrawScope.drawSelection(cx: Float, cy: Float, dotPx: Float, c: TtColors) {
    val r = 11
    val arm = 3
    val x0 = floor(cx / dotPx).toInt() - r
    val y0 = floor(cy / dotPx).toInt() - r
    val x1 = x0 + 2 * r
    val y1 = y0 + 2 * r
    fun bar(x: Int, y: Int, w: Int, h: Int) = drawRect(c.text, Offset(x * dotPx, y * dotPx), Size(w * dotPx, h * dotPx))
    for ((x, y) in listOf(x0 to y0, x1 - 1 to y0, x0 to y1 - 1, x1 - 1 to y1 - 1)) {
        val hx = if (x == x0) x0 else x1 - arm
        val vy = if (y == y0) y0 else y1 - arm
        bar(hx, y, arm, 1)
        bar(x, vy, 1, arm)
    }
}

/** The lane shift (dots) of a vehicle on shared track, so it rides on its own line's lane. */
private fun vehicleLaneShift(scene: MapScene, v: SceneVehicle, spot: VehicleSpot): Pair<Int, Int> {
    val g = spot.geometry ?: return 0 to 0
    val d = spot.distance ?: return 0 to 0
    val route = v.routeId ?: return 0 to 0
    if (g.names.size < 2) return 0 to 0
    val i = g.callIndexAt(d).coerceAtMost(g.names.size - 2)
    val lane = scene.lanes[g.segmentKeyAt(i) to route] ?: return 0 to 0
    if (lane == 0.0) return 0 to 0
    val a = g.names[i]
    val b = g.names[i + 1]
    val (pa, pb) = if (a <= b) g.stopPoints[i] to g.stopPoints[i + 1] else g.stopPoints[i + 1] to g.stopPoints[i]
    val (sx, sy) = laneStep(pa, pb)
    val shift = (lane * scene.lineWidthDots).roundToInt()
    return sx * shift to sy * shift
}

// ---------------------------------------------------------------- labels

/** A station's measured label: the name, an optional detail line, or a BOARD HERE / GET OFF flag. */
private class MeasuredLabel(
    val station: SceneStation,
    val name: TextLayoutResult,
    val detail: TextLayoutResult?,
    val flag: TextLayoutResult?,
)

private fun numeralText(text: String, numerals: FontFamily): AnnotatedString = buildAnnotatedString {
    for (ch in text) {
        if (ch.isDigit()) {
            pushStyle(SpanStyle(fontFamily = numerals))
            append(ch)
            pop()
        } else append(ch)
    }
}

private fun measureLabels(stations: List<SceneStation>, m: TextMeasurer, t: TtType): Map<String, MeasuredLabel> =
    stations.associate { s ->
        val flagWord = s.flag ?: when (s.kind) {
            StationKind.BOARD -> "BOARD HERE"
            StationKind.ALIGHT -> "GET OFF"
            StationKind.CHANGE -> "CHANGE"
            else -> null
        }
        val nameStyle: TextStyle = when (s.kind) {
            StationKind.BOARD, StationKind.ALIGHT, StationKind.CHANGE -> t.name17.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            else -> t.row15
        }
        val nameText = numeralText(s.name, t.numerals)
        s.id to MeasuredLabel(
            s,
            m.measure(nameText, nameStyle.copy(textDecoration = if (s.skipped) TextDecoration.LineThrough else null), maxLines = 1),
            s.detail?.let { m.measure(numeralText(it, t.numerals), t.label10, maxLines = 1) },
            flagWord?.let { m.measure(numeralText(it, t.numerals), t.label10Bold, maxLines = 1) },
        )
    }

/** Labels with this priority or lower (your stops) are placed before the lines' badges. */
private const val BADGE_AFTER_PRIORITY = 1

private fun DrawScope.drawLabels(
    scene: MapScene,
    cam: MapCamera,
    w: Int,
    h: Int,
    labels: Map<String, MeasuredLabel>,
    badges: Map<String, TextLayoutResult>,
    markers: Map<String, Box>,
    obstacles: List<Box>,
    captions: Map<String, TextLayoutResult>,
    vehicles: Map<String, Box>,
    dotPx: Float,
    c: TtColors,
) {
    val padX = 4 * dotPx
    val padY = 2.5f * dotPx
    val minorNames = cam.metresPerDot <= MINOR_LABEL_MAX_MPD
    val requests = ArrayList<LabelRequest>()
    for (s in scene.stations) {
        if (s.minor && !minorNames) continue
        val l = labels[s.id] ?: continue
        val anchor = markers[s.id] ?: continue
        val (lw, lh) = labelSize(l, padX, padY, dotPx)
        val sides = if (l.flag != null) listOf(LabelSide.BELOW, LabelSide.ABOVE, LabelSide.RIGHT, LabelSide.LEFT) else DEFAULT_SIDES
        requests += LabelRequest(s.id, anchor, lw, lh, s.priority, sides)
    }
    val bounds = Box(0f, 0f, size.width, size.height)
    // Minor stops' notches sit inside the line: names may cover them.
    val minorIds = scene.stations.filter { it.minor }.map { it.id }.toSet()
    val blocking = markers.filterKeys { it !in minorIds }.values.toList()
    val gap = 3 * dotPx
    // Your stops first, then the lines' route badges, then every other name in the room left.
    val (first, rest) = requests.partition { it.priority <= BADGE_AFTER_PRIORITY }
    val placedFirst = placeLabels(first, bounds, blocking, obstacles + vehicles.values, gap)
    val badgeBoxes = placeBadges(scene, cam, w, h, badges, bounds, blocking + placedFirst.values + obstacles + vehicles.values, dotPx)
    val placed = placedFirst + placeLabels(rest, bounds, blocking, obstacles + vehicles.values + placedFirst.values + badgeBoxes.values, gap)
    for ((id, b) in placed) {
        val l = labels[id] ?: continue
        drawStationLabel(l, b, padX, padY, dotPx, c)
    }
    for ((lineId, b) in badgeBoxes) {
        val line = scene.lines.firstOrNull { it.id == lineId } ?: continue
        val text = badges[lineId] ?: continue
        drawBadge(line, text, b, dotPx, c)
    }
    // Vehicle captions ("YOUR TRAIN") go where they fit around the vehicle, after the stations.
    val taken = placed.values + badgeBoxes.values + blocking + obstacles
    for ((id, text) in captions) {
        val vb = vehicles[id] ?: continue
        val role = scene.vehicles.firstOrNull { it.id == id }?.captionRole ?: ColorRole.CAUTION
        val inner = Box(vb.left + 2 * dotPx, vb.top + 2 * dotPx, vb.right - 2 * dotPx, vb.bottom - 2 * dotPx)
        for (side in listOf(LabelSide.RIGHT, LabelSide.LEFT, LabelSide.BELOW, LabelSide.ABOVE)) {
            val b = labelBox(inner, text.size.width.toFloat(), text.size.height.toFloat(), side, 4 * dotPx)
            if (!b.inside(bounds) || taken.any { it.overlaps(b) }) continue
            drawText(text, c.of(role), Offset(b.left, b.top))
            break
        }
    }
}

/** Padding of a route badge, in dots: across, and above and below the name. */
private const val BADGE_PAD_X = 3
private const val BADGE_PAD_Y = 1

/**
 * A box for each line's route badge, centred on the line: halfway along it if that spot is
 * free of [taken] and on screen, else the next free spot out from the middle. A line with no
 * free spot goes without (zooming in makes room).
 */
private fun placeBadges(
    scene: MapScene,
    cam: MapCamera,
    w: Int,
    h: Int,
    badges: Map<String, TextLayoutResult>,
    bounds: Box,
    taken: List<Box>,
    dotPx: Float,
): Map<String, Box> {
    val out = LinkedHashMap<String, Box>()
    for (line in scene.lines) {
        val text = badges[line.id] ?: continue
        val path = line.segments.maxByOrNull { it.path.length }?.path ?: continue
        if (path.length <= 0.0) continue
        val bw = text.size.width + 2 * BADGE_PAD_X * dotPx
        val bh = text.size.height + 2 * BADGE_PAD_Y * dotPx
        for (f in listOf(0.5, 0.4, 0.6, 0.3, 0.7, 0.2, 0.8)) {
            val p = cam.toDots(path.pointAt(path.length * f), w, h)
            // Snapped to whole dots, centred on the line's cell.
            val left = floor(p.ix - bw / 2 / dotPx) * dotPx
            val top = floor(p.iy - bh / 2 / dotPx) * dotPx
            val b = Box(left, top, left + bw, top + bh)
            if (!b.inside(bounds)) continue
            if (taken.any { it.overlaps(b) } || out.values.any { it.overlaps(b, dotPx) }) continue
            out[line.id] = b
            break
        }
    }
    return out
}

/** A route badge on its line: filled in the line's colour with a Screen rim, the name inside. */
private fun DrawScope.drawBadge(line: SceneLine, text: TextLayoutResult, b: Box, dotPx: Float, c: TtColors) {
    drawRect(c.screen, Offset(b.left - dotPx, b.top - dotPx), Size(b.width + 2 * dotPx, b.height + 2 * dotPx))
    drawRect(line.color, Offset(b.left, b.top), Size(b.width, b.height))
    val ink = badgeTextColor(line.color, line.badgeTextColor)
    drawText(text, ink, Offset(b.left + BADGE_PAD_X * dotPx, b.top + BADGE_PAD_Y * dotPx))
}

private fun labelSize(l: MeasuredLabel, padX: Float, padY: Float, dotPx: Float): Pair<Float, Float> {
    val name = l.name.size
    if (l.flag != null) {
        val flag = l.flag.size
        val detail = l.detail?.size
        val w = maxOf(name.width, flag.width, detail?.width ?: 0) + 2 * padX + 2 * dotPx
        val h = name.height + flag.height + (detail?.let { it.height + dotPx } ?: 0f) + 2 * padY + dotPx + 2 * dotPx
        return w to h
    }
    val detail = l.detail?.size
    val w = max(name.width, detail?.width ?: 0).toFloat()
    val h = name.height + (detail?.let { it.height + dotPx } ?: 0f)
    return w to h.toFloat()
}

private fun DrawScope.drawStationLabel(l: MeasuredLabel, b: Box, padX: Float, padY: Float, dotPx: Float, c: TtColors) {
    val s = l.station
    if (l.flag != null) {
        val board = s.kind == StationKind.BOARD
        val tone = s.flagRole?.let { c.of(it) } ?: c.text
        // BOARD HERE: inverted block. GET OFF, CHANGE: outlined block (in Caution for a tight change).
        drawRect(tone, Offset(b.left, b.top), Size(b.width, b.height))
        if (!board) drawRect(c.screen, Offset(b.left + dotPx, b.top + dotPx), Size(b.width - 2 * dotPx, b.height - 2 * dotPx))
        val ink = if (board) c.screen else tone
        val x = b.left + dotPx + padX
        val y = b.top + dotPx + padY
        drawText(l.flag, ink, Offset(x, y))
        val nameY = y + l.flag.size.height + dotPx
        drawText(l.name, ink, Offset(x, nameY))
        // A CHANGE flag says what comes next ("THEN 333 · 07:54") under the name.
        if (l.detail != null) drawText(l.detail, if (board) ink else c.of(s.detailRole), Offset(x, nameY + l.name.size.height + dotPx))
        return
    }
    val nameColor = when (s.kind) {
        StationKind.PASSED -> c.textSecondary
        else -> if (s.skipped) c.textSecondary else c.text
    }
    // A screen-coloured backing keeps names readable where they cross a line.
    drawRect(c.screen.copy(alpha = 0.75f), Offset(b.left - dotPx, b.top), Size(l.name.size.width + 2 * dotPx, l.name.size.height.toFloat()))
    drawText(l.name, nameColor, Offset(b.left, b.top))
    if (l.detail != null) {
        drawRect(c.screen.copy(alpha = 0.75f), Offset(b.left - dotPx, b.top + l.name.size.height), Size(l.detail.size.width + 2 * dotPx, l.detail.size.height + dotPx))
        drawText(l.detail, c.of(s.detailRole), Offset(b.left, b.top + l.name.size.height + dotPx))
    }
}

// ---------------------------------------------------------------- taps

/** The station or vehicle under [pos] (within [radius] px of its centre), nearest first. */
private fun hitTest(
    scene: MapScene,
    cam: MapCamera,
    w: Int,
    h: Int,
    dotPx: Float,
    pos: Offset,
    radius: Float,
    nowMs: Long,
    reduce: Boolean,
): Any? {
    var best: Any? = null
    var bestD = radius
    for (v in scene.vehicles) {
        val spot = v.spotAt(nowMs, reduce) ?: continue
        val p = cam.toDots(spot.point, w, h)
        val (sx, sy) = vehicleLaneShift(scene, v, spot)
        val d = Offset((p.ix + sx) * dotPx - pos.x, (p.iy + sy) * dotPx - pos.y).getDistance()
        if (d < bestD) {
            bestD = d
            best = v
        }
    }
    if (best != null) return best
    for (s in scene.stations) {
        if (s.minor && cam.metresPerDot > MINOR_LABEL_MAX_MPD) continue
        val p = cam.toDots(s.point, w, h)
        val d = Offset(p.ix * dotPx - pos.x, p.iy * dotPx - pos.y).getDistance()
        if (d < bestD) {
            bestD = d
            best = s
        }
    }
    return best
}
