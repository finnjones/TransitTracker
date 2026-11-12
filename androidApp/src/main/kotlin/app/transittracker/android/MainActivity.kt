package app.transittracker.android

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.transittracker.ui.app.AppController
import app.transittracker.ui.app.TransitTrackerApp

/**
 * The one activity: hosts [TransitTrackerApp] edge to edge and forwards the system's back, visibility and
 * notification taps to the [AppController].
 */
class MainActivity : ComponentActivity() {
    private val controller = AppController()
    private var barsDark: Boolean? = null
    private val graph: AppGraph get() = (application as TransitTrackerApplication).graph

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        graph.platform.onPermissionResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Night until the app says otherwise: light bar icons over the dark window background.
        applySystemBars(dark = true)
        super.onCreate(savedInstanceState)
        graph.platform.attach(this, permissionLauncher)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // false: on the Trips root, so back leaves the app.
                    if (!controller.onBack()) finish()
                }
            },
        )

        // The controller holds a tap until the app is composed and the core is attached.
        if (savedInstanceState == null) handleNotificationTap(intent)

        setContent {
            val startup by graph.startup.collectAsState()
            when (val s = startup) {
                Startup.Loading -> Unit // the window background (Screen colour) shows
                is Startup.Ready -> TransitTrackerApp(s.deps, controller = controller, onDarkTheme = ::applySystemBars)
            }
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

    override fun onResume() {
        super.onResume()
        // Permissions and notification settings may have changed in system settings.
        graph.platform.refresh()
    }

    override fun onStop() {
        controller.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        graph.platform.detach(this)
        super.onDestroy()
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
