package app.transittracker.android

import android.app.Application
import app.transittracker.model.WallClock
import app.transittracker.ui.app.AppDeps

/** Live flavour. Placeholder until the Android core glue lands: fake data like the demo. */
internal object FlavorDeps {
    fun create(app: Application): AppDeps = AppDeps.fake(clock = WallClock { System.currentTimeMillis() })
}
