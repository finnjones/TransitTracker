package app.transittracker.ui.gallery

import androidx.compose.runtime.Composable
import app.transittracker.ui.feature.alerts.AlertsShots
import app.transittracker.ui.feature.home.HomeShots
import app.transittracker.ui.feature.map.MapShots
import app.transittracker.ui.feature.notify.NotifyShots
import app.transittracker.ui.feature.onboarding.OnboardingShots
import app.transittracker.ui.feature.places.PlacesShots
import app.transittracker.ui.feature.plan.PlanShots
import app.transittracker.ui.feature.service.ServiceShots
import app.transittracker.ui.feature.settings.SettingsShots
import app.transittracker.ui.feature.timetable.TimetableShots
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
 * One named screenshot: [content] rendered at [widthDp] x [heightDp] (a phone, 390 x 844, by
 * default) and [density] inside a [TtTheme] with [colors], a fixed sprite clock at [tick], Sydney
 * time and the 24 h clock. Screens that run on `AppDeps.fake()` render the same every time
 * (fixed clock, fake data).
 */
class Shot(
    val name: String,
    val colors: TtColors = NightColors,
    val tick: Int = 0,
    val reduceMotion: Boolean = false,
    val widthDp: Int = 390,
    val heightDp: Int = 844,
    val density: Float = 2f,
    val content: @Composable () -> Unit,
)

/** Wraps [shot] in its theme; used by the JVM harness and the debug gallery. */
@Composable
fun ShotContent(shot: Shot) {
    TtTheme(
        colors = shot.colors,
        motion = TtMotion(reduceMotion = shot.reduceMotion),
        time = TtTime(ClockFormat.H24, SYDNEY),
        spriteClock = FixedSpriteClock(shot.tick),
    ) { shot.content() }
}

internal val SYDNEY: TimeZone = TimeZone.of("Australia/Sydney")

/**
 * The screenshot catalogue. Each feature contributes its own list from its own package
 * (`feature/<area>/<Area>Shots.kt`), so adding shots never touches this file.
 */
object Screenshots {
    /** The design system: galleries of components, vehicles and strips. */
    val designSystem: List<Shot> = listOf(
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

    /** Every shot: the design system, the shell and transitions, then each feature's. */
    val all: List<Shot> by lazy {
        val list = designSystem + AppShots.shots + HomeShots.shots + TimetableShots.shots + ServiceShots.shots +
            PlanShots.shots + PlacesShots.shots + AlertsShots.shots + NotifyShots.shots + MapShots.shots +
            OnboardingShots.shots + SettingsShots.shots
        val dupes = list.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(dupes.isEmpty()) { "duplicate screenshot names: $dupes" }
        list
    }
}
