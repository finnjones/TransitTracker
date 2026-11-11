package app.transittracker.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import app.transittracker.model.TransitCore
import app.transittracker.ui.nav.Navigator
import app.transittracker.ui.nav.Route

/**
 * The hooks the platform shell calls (Android activity / iOS scene). Create one per window with
 * [rememberAppController] (or keep one in the activity) and pass it to [TransitTrackerApp]:
 *
 * - [onBack]: system back / swipe-back. Returns false when the app should close (Trips root).
 * - [onForeground] / [onBackground]: visibility; the core polls live data only in the foreground.
 * - [openFromNotification]: a tapped notification's data payload (plan 5.12), opened with a cut.
 */
@Stable
class AppController {
    internal var navigator: Navigator? = null
        set(value) {
            field = value
            if (value != null) pendingRoute?.let { value.openFromNotification(it); pendingRoute = null }
        }
    internal var core: TransitCore? = null
        set(value) {
            field = value
            if (value != null) {
                pendingNotification?.let { openFromNotification(it); pendingNotification = null }
                if (foreground) value.onForeground()
            }
        }

    private var pendingRoute: Route? = null
    private var pendingNotification: Map<String, String>? = null

    /** True while the app is visible. */
    var foreground: Boolean = false
        private set

    /** System back. Plays the reverse of the transition that opened the page. */
    fun onBack(): Boolean = navigator?.back() ?: false

    fun onForeground() {
        foreground = true
        core?.onForeground()
    }

    fun onBackground() {
        foreground = false
        core?.onBackground()
    }

    /** Opens the service a notification is about, or Alerts when it names none. */
    fun openFromNotification(data: Map<String, String>) {
        val c = core
        if (c == null) {
            pendingNotification = data
            return
        }
        val ref = c.openFromNotification(data)
        open(if (ref != null) Route.Service(ref) else Route.Alerts)
    }

    private fun open(route: Route) {
        val nav = navigator
        if (nav == null) pendingRoute = route else nav.openFromNotification(route)
    }
}

@Composable
fun rememberAppController(): AppController = remember { AppController() }
