package app.transittracker.android

import app.transittracker.model.WallClock
import app.transittracker.ui.app.AppDeps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Demo flavour: the fake core, planner and sample trips and places on the real clock (like the
 * desktop window), with the real platform services (permissions, location, links). Nothing is
 * stored between launches and no server is contacted; push is off.
 */
internal object FlavorGraph {
    fun create(app: TransitTrackerApplication): AppGraph = DemoGraph(app)
}

private class DemoGraph(app: TransitTrackerApplication) : AppGraph {
    override val platform = AndroidPlatformServices(app, "$appVersionText demo", MutableStateFlow("demo-device"))

    override val startup: StateFlow<Startup> = MutableStateFlow(
        Startup.Ready(AppDeps.fake(clock = WallClock { System.currentTimeMillis() }, platform = platform)),
    )

    override fun setServerAddress(url: String) = Unit

    override fun onPushToken(token: String) = Unit
}
