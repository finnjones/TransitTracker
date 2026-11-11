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
    settleFrames: Int = 3,
    content: @Composable () -> Unit,
): ByteArray {
    val scene = ImageComposeScene(
        width = (widthDp * density).toInt(),
        height = (heightDp * density).toInt(),
        density = Density(density),
        content = content,
    )
    try {
        // Frames so resources, layout, launched effects and flows from fake data settle. Screens
        // load data in coroutines, so give them a little real time between frames as well.
        var t = 0L
        repeat(settleFrames) {
            scene.render(t)
            t += 16_000_000L
            if (settleFrames > 3) Thread.sleep(15)
        }
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
        file.writeBytes(renderShot(shot))
        println("wrote ${file.path}")
    }
}

/** Renders one catalogue [shot] at its own size and density. */
fun renderShot(shot: app.transittracker.ui.gallery.Shot): ByteArray =
    renderPng(shot.widthDp, shot.heightDp, shot.density, settleFrames = 16) { ShotContent(shot) }
