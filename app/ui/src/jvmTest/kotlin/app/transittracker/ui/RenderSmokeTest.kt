package app.transittracker.ui

import app.transittracker.ui.gallery.Screenshots
import app.transittracker.ui.gallery.ShotContent
import app.transittracker.ui.screenshots.renderPng
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renders two catalogue shots off-screen: fonts, sprites and strips load and draw on the JVM. */
class RenderSmokeTest {
    @Test
    fun shotsRender() {
        for (name in listOf("strips-night", "components-1-day")) {
            val shot = Screenshots.all.single { it.name == name }
            val png = renderPng { ShotContent(shot) }
            val image = Image.makeFromEncoded(png)
            assertEquals(780, image.width)
            assertEquals(1688, image.height)
            assertTrue(png.size > 10_000, "$name looks empty (${png.size} bytes)")
        }
    }
}
