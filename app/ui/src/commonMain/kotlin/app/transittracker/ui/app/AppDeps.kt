package app.transittracker.ui.app

import androidx.compose.runtime.staticCompositionLocalOf
import app.transittracker.core.FakeTransitCore
import app.transittracker.core.InMemoryKeyValueStore
import app.transittracker.core.KeyValueStore
import app.transittracker.model.JourneyPlanner
import app.transittracker.model.SavedTrip
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.feature.follow.JourneyFollower
import app.transittracker.ui.fake.ScenarioTransitCore
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.places.KeyValuePlacesStore
import app.transittracker.ui.places.PlacesStore
import app.transittracker.ui.planning.FakeJourneyPlanner
import app.transittracker.ui.platform.FakePlatformServices
import app.transittracker.ui.platform.PlatformServices
import app.transittracker.ui.settings.KeyValueSettingsStore
import app.transittracker.ui.settings.SettingsStore
import app.transittracker.ui.settings.UiSettings

/**
 * Everything a screen may depend on. Screens read it with `LocalAppDeps.current` (provided by
 * `TransitTrackerApp`) and pass what they need to their state holder. Nothing else is global.
 *
 * - [core]: the on-device core (trips, departures, services, alerts, data status, watches).
 * - [planner]: journey planning and place search (`Planning.kt`).
 * - [settings]: display settings (theme, clock, density, reduce motion, sounds, haptics).
 * - [places]: saved places and which places each saved trip runs between (phone only).
 * - [platform]: what only the platform shell can do (location, permissions, links, versions).
 * - [clock]: the time every screen uses ("now"); the core's clock, fixed in previews and tests.
 * - [follower]: Follow my journey (which saved-trip journey the phone is on), one per app.
 * - [kv]: the phone's small store, for screen-level things that should survive a restart (the
 *   planner's recent searches). Optional: without it they are kept in memory only.
 */
class AppDeps(
    val core: TransitCore,
    val planner: JourneyPlanner,
    val settings: SettingsStore,
    val places: PlacesStore,
    val platform: PlatformServices,
    val clock: WallClock,
    val kv: KeyValueStore? = null,
) {
    val follower: JourneyFollower by lazy { JourneyFollower(core, platform, settings, clock) }

    /**
     * Reads saved trips, places and display settings from storage again: after signing in
     * brought them down from the user's account. (Recent searches are read when the planner first opens.)
     */
    suspend fun reloadSaved() {
        core.reloadTrips()
        places.reload()
        settings.reload()
    }

    companion object {
        /**
         * Fake dependencies for previews, screenshots, tests and the desktop window:
         * [FakeTransitCore] (wrapped so [scenario] can make data stale or offline), the
         * [FakeJourneyPlanner], in-memory settings and places, [FakePlatformServices] and a fixed
         * clock (Wed 11 Nov 07:38 in Sydney, [SampleData.NOW_MS]).
         *
         * Trips are the Main mockup's ([FakeSetup.trips], the first one linked Home to Work) and
         * places the Places mockup's Home, Work and Gym, unless [trips] / [withPlaces] say otherwise.
         */
        fun fake(
            clock: WallClock = SampleData.clock,
            joined: Boolean = true,
            settings: UiSettings = UiSettings(),
            trips: List<SavedTrip>? = FakeSetup.trips,
            withPlaces: Boolean = true,
            scenario: CoreScenario = CoreScenario(),
            platform: PlatformServices = FakePlatformServices(),
            kv: KeyValueStore? = null,
        ): AppDeps {
            val fakeCore = FakeTransitCore(clock, joined = joined)
            if (trips != null) FakeSetup.replaceTrips(fakeCore, trips)
            val store = kv ?: InMemoryKeyValueStore(
                FakeSetup.settingsValues(settings) + (if (withPlaces) FakeSetup.placesValues() else emptyMap()),
            )
            return AppDeps(
                core = ScenarioTransitCore(fakeCore, clock, scenario),
                planner = FakeJourneyPlanner(clock),
                settings = KeyValueSettingsStore(store),
                places = KeyValuePlacesStore(store),
                platform = platform,
                clock = clock,
            )
        }
    }
}

/** The running app's dependencies. */
val LocalAppDeps = staticCompositionLocalOf<AppDeps> { error("No AppDeps: screens run inside TransitTrackerApp") }
