package app.transittracker.ui.gallery

import androidx.compose.runtime.Composable
import app.transittracker.ui.settings.ClockFormat
import app.transittracker.ui.sprites.FixedSpriteClock
import app.transittracker.ui.theme.DayColors
import app.transittracker.ui.theme.NightColors
import app.transittracker.ui.theme.TtColors
import app.transittracker.ui.theme.TtMotion
import app.transittracker.ui.theme.TtTheme
import app.transittracker.ui.theme.TtTime
import kotlinx.datetime.TimeZone

/**
 * One named screenshot: [content] rendered at 390 x 844 dp inside a [TtTheme] with [colors], a
 * fixed sprite clock at [tick], Sydney time and 24 h clock. Add new shots to [Screenshots.all].
 */
class Shot(
    val name: String,
    val colors: TtColors = NightColors,
    val tick: Int = 0,
    val reduceMotion: Boolean = false,
    val content: @Composable () -> Unit,
)

/** Wraps [shot] in its theme; used by the JVM harness (and usable in an Android debug gallery). */
@Composable
fun ShotContent(shot: Shot) {
    TtTheme(
        colors = shot.colors,
        motion = TtMotion(reduceMotion = shot.reduceMotion),
        time = TtTime(ClockFormat.H24, TimeZone.of("Australia/Sydney")),
        spriteClock = FixedSpriteClock(shot.tick),
    ) { shot.content() }
}

object Screenshots {
    val all: List<Shot> = listOf(
        Shot("components-1-night") { ComponentGalleryPage1() },
        Shot("components-2-night") { ComponentGalleryPage2() },
        Shot("components-1-day", DayColors) { ComponentGalleryPage1() },
        Shot("components-2-day", DayColors) { ComponentGalleryPage2() },
        Shot("vehicles-1-night") { VehicleGalleryPage1() },
        Shot("vehicles-2-night", tick = 1) { VehicleGalleryPage2() },
        Shot("vehicles-1-day", DayColors) { VehicleGalleryPage1() },
        Shot("vehicles-2-day", DayColors, tick = 1) { VehicleGalleryPage2() },
        Shot("strips-night") { JourneyStripGallery() },
        Shot("strips-day", DayColors) { JourneyStripGallery() },
        Shot("legs-night") { LegStripGallery() },
        Shot("card-night") { SampleCardPage() },
        Shot("card-day", DayColors) { SampleCardPage() },
        Shot("legs-day", DayColors) { LegStripGallery() },
    )
}
