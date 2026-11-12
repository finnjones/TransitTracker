package app.transittracker.ui.feature.map

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import app.transittracker.model.LatLng
import app.transittracker.ui.res.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/*
 * The base map under the lines: water, roads and railways, so a journey can be read against the
 * harbour and the streets. The data is bundled (composeResources/files/basemap/sydney.txt, built
 * by tools/basemap/build_basemap.py from OpenStreetMap; ODbL, the map shows the credit). It is
 * drawn into a bitmap of one pixel per dot without anti-aliasing, then scaled up, so the coast and
 * the streets come out as whole dots like everything else on the map.
 */

/** What a base map feature is; the file's kind letters. */
enum class BaseKind(val letter: Char) {
    WATER('W'),
    /** An island in the water polygon before it. */
    HOLE('H'),
    MAIN('M'),
    SECONDARY('S'),
    LOCAL('L'),
    RAIL('R'),
    ;

    companion object {
        fun of(letter: Char): BaseKind? = entries.firstOrNull { it.letter == letter }
    }
}

/**
 * The base map's features on the map plane ([MapProjection.Sydney]), in flat arrays: feature i
 * is [kinds] i, its points are [xs] / [ys] from [starts] i until [starts] i + 1, and its box is
 * [boxes] 4i..4i+3 (min x, min y, max x, max y).
 */
class Basemap internal constructor(
    val kinds: Array<BaseKind>,
    val starts: IntArray,
    val xs: FloatArray,
    val ys: FloatArray,
    val boxes: FloatArray,
    /** Everything the data covers. */
    val bounds: MapBox,
) {
    val size: Int get() = kinds.size

    /** Whether feature [i]'s box meets [box]. */
    fun meets(i: Int, box: MapBox): Boolean =
        boxes[4 * i] <= box.maxX && boxes[4 * i + 2] >= box.minX && boxes[4 * i + 1] <= box.maxY && boxes[4 * i + 3] >= box.minY

    companion object {
        /**
         * Parses the bundled text: a header "TTBASEMAP 1 south west north east", then one feature
         * a line, its kind letter and a Google encoded polyline (precision 5). Unknown kinds and
         * features of fewer than two points are skipped. Null when the header is not there.
         */
        fun parse(text: String, projection: MapProjection = MapProjection.Sydney): Basemap? {
            val lines = text.lineSequence().iterator()
            if (!lines.hasNext()) return null
            val header = lines.next().trim().split(' ')
            if (header.size < 6 || header[0] != "TTBASEMAP") return null
            val (s, w, n, e) = header.subList(2, 6).map { it.toDoubleOrNull() ?: return null }
            val corners = listOf(projection.project(LatLng(s, w)), projection.project(LatLng(n, e)))
            val kinds = ArrayList<BaseKind>()
            val starts = IntList()
            val xs = FloatList()
            val ys = FloatList()
            val boxes = FloatList()
            for (line in lines) {
                if (line.length < 3) continue
                val kind = BaseKind.of(line[0]) ?: continue
                val from = xs.size
                decodeInto(line, 1, projection, xs, ys)
                if (xs.size - from < 2) {
                    xs.size = from
                    ys.size = from
                    continue
                }
                kinds += kind
                starts.add(from)
                var x0 = Float.MAX_VALUE
                var y0 = Float.MAX_VALUE
                var x1 = -Float.MAX_VALUE
                var y1 = -Float.MAX_VALUE
                for (k in from until xs.size) {
                    x0 = minOf(x0, xs[k]); x1 = maxOf(x1, xs[k])
                    y0 = minOf(y0, ys[k]); y1 = maxOf(y1, ys[k])
                }
                boxes.add(x0); boxes.add(y0); boxes.add(x1); boxes.add(y1)
            }
            starts.add(xs.size)
            return Basemap(
                kinds.toTypedArray(), starts.toArray(), xs.toArray(), ys.toArray(), boxes.toArray(),
                MapBox.around(corners) ?: return null,
            )
        }

        /** Decodes the polyline in [s] from [at] straight into [xs] / [ys], projected. */
        private fun decodeInto(s: String, at: Int, projection: MapProjection, xs: FloatList, ys: FloatList) {
            var i = at
            var lat = 0
            var lon = 0
            while (i < s.length) {
                for (k in 0..1) {
                    var result = 0
                    var shift = 0
                    while (true) {
                        if (i >= s.length) return
                        val b = s[i++].code - 63
                        result = result or ((b and 0x1F) shl shift)
                        shift += 5
                        if (b < 0x20) break
                    }
                    val d = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                    if (k == 0) lat += d else lon += d
                }
                val p = projection.project(LatLng(lat / 1e5, lon / 1e5))
                xs.add(p.x.toFloat())
                ys.add(p.y.toFloat())
            }
        }
    }
}

/** The colours the base map is drawn in (from the theme, see [PixelMap]). */
data class BasemapColors(val water: Color, val local: Color, val secondary: Color, val main: Color, val rail: Color)

/** Which kinds are drawn at [metresPerDot], and how many dots wide: roads thin out as the map zooms out. */
fun baseWidthDots(kind: BaseKind, metresPerDot: Double): Int = when (kind) {
    BaseKind.WATER, BaseKind.HOLE -> 1
    BaseKind.LOCAL -> if (metresPerDot <= 8.0) 1 else 0
    BaseKind.SECONDARY -> if (metresPerDot <= 25.0) 1 else 0
    BaseKind.MAIN -> when {
        metresPerDot <= 5.0 -> 2
        metresPerDot <= 60.0 -> 1
        else -> 0
    }
    BaseKind.RAIL -> if (metresPerDot <= 150.0) 1 else 0
}

/**
 * The part of [map] under [camera], drawn into a bitmap of [w] x [h] (one pixel per dot): water
 * filled, then local streets, secondary roads, main roads and railways on top. Pixels with
 * nothing on them stay transparent, so the map's ground shows through.
 */
fun rasteriseBasemap(map: Basemap, camera: MapCamera, w: Int, h: Int, colors: BasemapColors): ImageBitmap {
    val image = ImageBitmap(maxOf(1, w), maxOf(1, h))
    val canvas = Canvas(image)
    val mpd = camera.metresPerDot
    val view = MapBox(
        camera.center.x - w / 2.0 * mpd, camera.center.y - h / 2.0 * mpd,
        camera.center.x + w / 2.0 * mpd, camera.center.y + h / 2.0 * mpd,
    ).grow(4 * mpd)
    val cx = camera.center.x.toFloat()
    val cy = camera.center.y.toFloat()
    val k = (1.0 / mpd).toFloat()
    val hw = w / 2f
    val hh = h / 2f

    fun addTo(path: Path, i: Int, close: Boolean) {
        val from = map.starts[i]
        val to = map.starts[i + 1]
        path.moveTo((map.xs[from] - cx) * k + hw, (map.ys[from] - cy) * k + hh)
        for (p in from + 1 until to) path.lineTo((map.xs[p] - cx) * k + hw, (map.ys[p] - cy) * k + hh)
        if (close) path.close()
    }

    // Water: every ring in one even-odd path (the polygons do not overlap; holes cut islands).
    val water = Path().apply { fillType = PathFillType.EvenOdd }
    for (i in 0 until map.size) {
        val kind = map.kinds[i]
        if ((kind == BaseKind.WATER || kind == BaseKind.HOLE) && map.meets(i, view)) addTo(water, i, close = true)
    }
    canvas.drawPath(water, Paint().apply { color = colors.water; isAntiAlias = false; style = PaintingStyle.Fill })

    for ((kind, color) in listOf(
        BaseKind.LOCAL to colors.local, BaseKind.SECONDARY to colors.secondary, BaseKind.MAIN to colors.main, BaseKind.RAIL to colors.rail,
    )) {
        val width = baseWidthDots(kind, mpd)
        if (width == 0) continue
        val path = Path()
        var any = false
        for (i in 0 until map.size) {
            if (map.kinds[i] == kind && map.meets(i, view)) {
                addTo(path, i, close = false)
                any = true
            }
        }
        if (!any) continue
        canvas.drawPath(
            path,
            Paint().apply {
                this.color = color
                isAntiAlias = false
                style = PaintingStyle.Stroke
                strokeWidth = width.toFloat()
                strokeCap = StrokeCap.Square
            },
        )
    }
    return image
}

/**
 * The bundled base map, loaded once per process off the main thread. [state] is null until it
 * has loaded (or when it cannot be read: the map then keeps its plain grid).
 */
object BasemapStore {
    const val FILE = "files/basemap/sydney.txt"
    private val lock = Mutex()
    private val loaded = MutableStateFlow<Basemap?>(null)
    private var tried = false
    val state: StateFlow<Basemap?> get() = loaded

    suspend fun load() {
        lock.withLock {
            if (tried) return
            tried = true
            loaded.value = withContext(Dispatchers.Default) {
                runCatching { Basemap.parse(Res.readBytes(FILE).decodeToString()) }.getOrNull()
            }
        }
    }
}

// Growable primitive lists, so parsing 120 000 features does not box a million numbers.

internal class FloatList {
    private var a = FloatArray(1024)
    var size = 0
    operator fun get(i: Int) = a[i]
    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }
    fun toArray(): FloatArray = a.copyOf(size)
}

internal class IntList {
    private var a = IntArray(1024)
    var size = 0
    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }
    fun toArray(): IntArray = a.copyOf(size)
}
