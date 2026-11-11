package app.transittracker.android

import android.app.Application
import app.transittracker.ui.app.AppDeps

/** Owns the process's single [AppDeps]; the flavour decides what goes into it. */
class TransitTrackerApplication : Application() {
    val deps: AppDeps by lazy { FlavorDeps.create(this) }
}
