package app.transittracker.ui.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import app.transittracker.model.WallClock
import app.transittracker.ui.app.AppController
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.app.TransitTrackerApp

/**
 * `./gradlew :app:ui:runDesktop`: the app on fake data in a phone-sized window, running on the
 * real clock (fake services run every day from about 04:30 to midnight). Escape is system back.
 * Needs a display; not part of the build.
 */
fun main() = application {
    val controller = remember { AppController() }
    val deps = remember { AppDeps.fake(clock = WallClock { System.currentTimeMillis() }) }
    Window(
        onCloseRequest = ::exitApplication,
        title = "TransitTracker (fake data)",
        state = WindowState(size = DpSize(390.dp, 844.dp)),
        onKeyEvent = { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { controller.onBack(); true } else false
        },
    ) {
        remember { controller.onForeground(); Unit }
        TransitTrackerApp(deps, controller = controller)
    }
}
