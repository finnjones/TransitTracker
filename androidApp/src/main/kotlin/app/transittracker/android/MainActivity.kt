package app.transittracker.android

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.transittracker.ui.app.AppController
import app.transittracker.ui.app.TransitTrackerApp

/**
 * The one activity: hosts [TransitTrackerApp] edge to edge and forwards the system's back,
 * visibility and notification taps to the [AppController].
 */
class MainActivity : ComponentActivity() {
    private val controller = AppController()
    private var barsDark: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Night until the app says otherwise: light bar icons over the dark window background.
        applySystemBars(dark = true)
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // false: on the Trips root, so back leaves the app.
                    if (!controller.onBack()) finish()
                }
            },
        )

        if (savedInstanceState == null) handleNotificationTap(intent)

        val deps = (application as TransitTrackerApplication).deps
        setContent {
            TransitTrackerApp(deps, controller = controller, onDarkTheme = ::applySystemBars)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationTap(intent)
    }

    override fun onStart() {
        super.onStart()
        controller.onForeground()
    }

    override fun onStop() {
        controller.onBackground()
        super.onStop()
    }

    /** Light icons on the Night theme, dark icons on Day; the app's own colour shows behind the bars. */
    private fun applySystemBars(dark: Boolean) {
        if (barsDark == dark) return
        barsDark = dark
        val style = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private fun handleNotificationTap(intent: Intent?) {
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val data = NotificationData.fromIntent(intent) ?: return
        controller.openFromNotification(data)
    }
}
