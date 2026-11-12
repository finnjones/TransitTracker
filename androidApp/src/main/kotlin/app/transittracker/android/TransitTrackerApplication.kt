package app.transittracker.android

import android.app.Application
import app.transittracker.ui.app.AppDeps
import kotlinx.coroutines.flow.StateFlow

/** Owns the process's single [AppGraph] (and so its single [AppDeps]); the flavour decides what goes into it. */
class TransitTrackerApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        graph = FlavorGraph.create(this)
    }
}

/** What the app shows at start. */
sealed interface Startup {
    /** Reading local state (a few milliseconds); the window background shows. */
    data object Loading : Startup

    data class Ready(val deps: AppDeps) : Startup
}

/** The process-wide objects, built by the flavour's `FlavorGraph`. */
interface AppGraph {
    val startup: StateFlow<Startup>
    val platform: AndroidPlatformServices

    /** A Firebase push token, now or after a refresh. */
    fun onPushToken(token: String)
}

/** "0.1.0 (1)", shown in Settings > About. */
internal val appVersionText: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
