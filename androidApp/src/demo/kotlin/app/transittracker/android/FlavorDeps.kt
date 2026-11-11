package app.transittracker.android

import android.app.Application
import app.transittracker.model.WallClock
import app.transittracker.ui.app.AppDeps

/** Demo flavour: the fake core, planner and sample data on the real clock (like the desktop window). */
internal object FlavorDeps {
    fun create(app: Application): AppDeps = AppDeps.fake(clock = WallClock { System.currentTimeMillis() })
}
