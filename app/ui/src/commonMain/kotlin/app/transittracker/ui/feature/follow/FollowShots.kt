package app.transittracker.ui.feature.follow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.transittracker.model.Journey
import app.transittracker.model.LatLng
import app.transittracker.model.StopCall
import app.transittracker.model.WallClock
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.gallery.AppShot
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.gallery.Shot
import app.transittracker.ui.motion.PageTransition
import app.transittracker.ui.nav.MapLeg
import app.transittracker.ui.nav.Navigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.platform.LocationFix
import app.transittracker.ui.settings.ThemePreference
import app.transittracker.ui.settings.UiSettings
import app.transittracker.ui.theme.DayColors
import kotlinx.coroutines.flow.first

/**
 * Follow my journey on the sample data: the Town Hall to Tallawong trip (the T1 to Chatswood,
 * then the M1). At 07:38 a T1 of that trip is under way; the shots pin it as if the phone were
 * on it ([JourneyFollower.showForPreview]), or, [MINUTES_LATER] on, walking to the metro.
 */
object FollowShots {
    private const val TRIP = FakeSetup.TRIP_TOWNHALL_TALLAWONG

    /** Where the expected times put a rider at [t], between the positions of [calls]. */
    private fun positionAt(calls: List<StopCall>, t: Long): LatLng? {
        val located = calls.filter { it.position != null }
        for (i in 0 until located.size - 1) {
            val a = located[i]
            val b = located[i + 1]
            val dep = a.expectedDepartureMs ?: a.scheduledDepartureMs ?: continue
            val arr = b.expectedArrivalMs ?: b.scheduledArrivalMs ?: continue
            if (t in dep..arr) {
                val f = if (arr > dep) (t - dep).toDouble() / (arr - dep) else 0.0
                val p = a.position!!
                val q = b.position!!
                return LatLng(p.lat + (q.lat - p.lat) * f, p.lon + (q.lon - p.lon) * f)
            }
        }
        return located.lastOrNull()?.position
    }

    private class Scene(val deps: AppDeps, val journey: Journey)

    /**
     * The trip's journey under way at 07:38, pinned on a fresh [AppDeps] whose clock is [laterMs]
     * on: riding the first leg, or ([changing]) walking to the second.
     */
    private suspend fun scene(settings: UiSettings, changing: Boolean): Scene? {
        val probe = AppDeps.fake()
        val underway = probe.core.underway(TRIP).first().filter { it.legs.size > 1 }
        // The one whose train is between Town Hall and Chatswood at 07:38.
        val journey = underway.firstOrNull { SampleData.NOW_MS < it.legs[0].expectedArrivalMs } ?: underway.firstOrNull() ?: return null
        val train = journey.legs[0]
        val metro = journey.legs[1]
        val at = if (changing) train.expectedArrivalMs + 60_000 else SampleData.NOW_MS
        val deps = AppDeps.fake(clock = WallClock { at }, settings = settings)
        val position = if (changing) {
            val calls = deps.core.service(metro.ref).first().calls
            riddenCalls(calls, metro.originStopId, metro.destinationStopId).firstOrNull()?.position
        } else {
            val calls = deps.core.service(train.ref).first().calls
            positionAt(riddenCalls(calls, train.originStopId, train.destinationStopId), at)
        }
        val match = FollowMatch(TRIP, train.ref, if (changing) 1 else 0, if (changing) FollowStage.CHANGING else FollowStage.RIDING, 0.0)
        deps.follower.showForPreview(match, position?.let { LocationFix(it, at, 20.0) })
        return Scene(deps, journey)
    }

    @Composable
    private fun FollowApp(settings: UiSettings = UiSettings(), changing: Boolean = false, map: Boolean = false) {
        var s by remember { mutableStateOf<Scene?>(null) }
        LaunchedEffect(Unit) { s = scene(settings, changing) }
        val scene = s ?: return
        val nav = remember(scene) {
            Navigator().apply {
                if (map) {
                    val legs = scene.journey.legs.map { MapLeg(it.ref, it.originStopId, it.destinationStopId) }
                    push(Route.JourneyMap(TRIP, legs), PageTransition.Cut)
                }
            }
        }
        AppShot(scene.deps, nav)
    }

    private val day = UiSettings(theme = ThemePreference.DAY)

    val shots: List<Shot> = listOf(
        Shot("follow-home-riding") { FollowApp() },
        Shot("follow-home-changing") { FollowApp(changing = true) },
        Shot("follow-home-day", DayColors) { FollowApp(day) },
        Shot("follow-map", tick = 1) { FollowApp(map = true) },
        Shot("follow-map-changing", tick = 1) { FollowApp(changing = true, map = true) },
        Shot("follow-map-day", DayColors, tick = 1) { FollowApp(day, map = true) },
    )
}
