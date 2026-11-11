package app.transittracker.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import app.transittracker.model.Mode
import app.transittracker.model.ServiceRef
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.app.AppPage
import app.transittracker.ui.app.PagePreview
import app.transittracker.ui.app.TransitTrackerApp
import app.transittracker.ui.components.Label
import app.transittracker.ui.motion.PageTransition
import app.transittracker.ui.motion.TransitionPreview
import app.transittracker.ui.nav.NavChange
import app.transittracker.ui.nav.NavEntry
import app.transittracker.ui.nav.Navigator
import app.transittracker.ui.nav.Route
import app.transittracker.ui.theme.TtTheme

/**
 * The whole app on [deps] as a screenshot: theme from the deps' settings, Sydney time, the shot's
 * fixed sprite clock, transitions frozen. Give it a [navigator] to open another page first.
 */
@Composable
fun AppShot(deps: AppDeps, navigator: Navigator? = null) {
    val nav = navigator ?: remember { Navigator() }
    TransitTrackerApp(
        deps, navigator = nav, systemDark = true, timeZone = SYDNEY,
        spriteClock = TtTheme.spriteClock, driveTransitions = false,
    )
}

/** Shots of the shell and the page transitions (plan 3.4). */
object AppShots {
    private const val W = 390
    private const val H = 844
    private val sampleRef = ServiceRef("T9N-0730", "20261111")
    private val t9Pass = PageTransition.vehiclePass(Mode.TRAIN, SampleData.T9)

    private val trips = NavEntry(0, Route.Trips)
    private val service = NavEntry(1, Route.Service(sampleRef), t9Pass)
    private val tracking = NavEntry(2, Route.Tracking(sampleRef), PageTransition.Doors)
    private val alerts = NavEntry(3, Route.Alerts)

    private fun forward(from: NavEntry, to: NavEntry) = NavChange(from, to, to.enter, reverse = false, serial = 1)
    private fun back(from: NavEntry, to: NavEntry) = NavChange(from, to, from.enter, reverse = true, serial = 1)

    private fun filmstrip(name: String, change: NavChange, times: List<Long>) = Shot(
        name, widthDp = times.size * (W + 8) + 8, heightDp = H + 36, density = 1f,
    ) {
        val deps = remember { AppDeps.fake() }
        PagePreview(deps) {
            Row(
                Modifier.fillMaxSize().background(TtTheme.colors.divider).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (t in times) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Label("$t MS", bold = true)
                        Box(Modifier.size(W.dp, H.dp).clipToBounds()) {
                            TransitionPreview(change, t, W.toFloat(), { AppPage(change.from.route, 1) }, { AppPage(change.to.route, 1) })
                        }
                    }
                }
            }
        }
    }

    private fun busPass(reverse: Boolean): NavChange {
        val bus = NavEntry(1, Route.Service(sampleRef), PageTransition.vehiclePass(Mode.BUS, SampleData.BUS))
        return if (reverse) back(bus, trips) else forward(trips, bus)
    }

    val shots: List<Shot> = listOf(
        // Vehicle pass: a four-car T9 train wipes the service in, and runs the other way back.
        filmstrip("transition-pass-forward", forward(trips, service), listOf(0, 60, 140, 220, 300, 460)),
        filmstrip("transition-pass-back", back(service, trips), listOf(0, 60, 140, 220, 300, 460)),
        filmstrip("transition-pass-bus", busPass(false), listOf(0, 100, 200, 300, 360)),
        // Doors: close over the service, the map swaps in behind them at 300 ms, they open.
        filmstrip("transition-doors-forward", forward(service, tracking), listOf(60, 180, 280, 320, 420, 540)),
        filmstrip("transition-doors-back", back(tracking, service), listOf(60, 180, 280, 320, 420, 540)),
        // Split-flap: the new tab's rows flip in from the top, 60 ms apart (Trips -> Alerts by the
        // tab, and back to Trips, whose folders and cards are the rows).
        filmstrip("transition-flap-forward", NavChange(trips, alerts, PageTransition.SplitFlap, reverse = false, serial = 1), listOf(0, 60, 120, 200, 280)),
        filmstrip("transition-flap-back", NavChange(alerts, trips, PageTransition.SplitFlap, reverse = true, serial = 1), listOf(0, 60, 120, 180, 260, 380)),
        // A placeholder page, as every unbuilt screen looks.
        Shot("placeholder-service") { PagePreview(remember { AppDeps.fake() }) { AppPage(Route.Service(sampleRef)) } },
    )
}
