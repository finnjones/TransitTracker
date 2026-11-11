package app.transittracker.ui.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.transittracker.ui.gallery.Screenshots
import app.transittracker.ui.gallery.ShotContent
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** Phone size used by every shot, in dp. */
const val SHOT_WIDTH_DP = 390
const val SHOT_HEIGHT_DP = 844

/**
 * Renders [content] off-screen at [widthDp] x [heightDp] and [density] (2x by default) and
 * returns PNG bytes. Needs no display (Skia raster surface).
 */
fun renderPng(
    widthDp: Int = SHOT_WIDTH_DP,
    heightDp: Int = SHOT_HEIGHT_DP,
    density: Float = 2f,
    content: @Composable () -> Unit,
): ByteArray {
    val scene = ImageComposeScene(
        width = (widthDp * density).toInt(),
        height = (heightDp * density).toInt(),
        density = Density(density),
        content = content,
    )
    try {
        // A few frames so resources, layout and launched effects settle.
        var t = 0L
        repeat(3) { scene.render(t); t += 16_000_000L }
        val image = scene.render(t)
        return image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encoding failed")
    } finally {
        scene.close()
    }
}

/**
 * `./gradlew :app:ui:screenshots [-Pshots=name1,name2]`: renders [Screenshots.all] to
 * `<outDir>/<name>.png`. Args: output directory, then an optional comma-separated name filter.
 */
fun main(args: Array<String>) {
    val out = File(args.getOrNull(0) ?: "build/screenshots").apply { mkdirs() }
    val only = args.getOrNull(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
    val shots = Screenshots.all.filter { only == null || it.name in only }
    require(shots.isNotEmpty()) { "no screenshot matches $only; known: ${Screenshots.all.map { it.name }}" }
    for (shot in shots) {
        val file = File(out, shot.name + ".png")
        file.writeBytes(renderPng { ShotContent(shot) })
        println("wrote ${file.path}")
    }
}
