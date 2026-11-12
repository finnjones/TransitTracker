package app.transittracker.ui.fake

import app.transittracker.core.FakeData
import app.transittracker.core.FakeTransitCore
import app.transittracker.model.LatLng
import app.transittracker.model.SavedTrip
import app.transittracker.model.TripLeg
import app.transittracker.ui.places.KeyValuePlacesStore
import app.transittracker.ui.places.PlaceIcon
import app.transittracker.ui.places.PlaceStop
import app.transittracker.ui.places.SavedPlace
import app.transittracker.ui.places.TripPlaces
import app.transittracker.ui.settings.KeyValueSettingsStore
import app.transittracker.ui.settings.UiSettings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Sample trips and places matching the Main and Places mockups, over [FakeData]'s stations. */
object FakeSetup {
    const val HOME = "place-home"
    const val WORK = "place-work"
    const val GYM = "place-gym"

    const val TRIP_HOME_WORK = "trip-home-work"
    const val TRIP_CHATSWOOD_TALLAWONG = "trip-chatswood-tallawong"
    const val TRIP_CENTRAL_STRATHFIELD = "trip-central-strathfield"
    const val TRIP_TOWNHALL_TALLAWONG = "trip-townhall-tallawong"

    /** Main mockup: two trips to work, one home, and a multi-leg trip. The first runs Home to Work. */
    val trips: List<SavedTrip> = listOf(
        SavedTrip(
            TRIP_HOME_WORK, "Strathfield", "Central",
            listOf(TripLeg(setOf(FakeData.STRATHFIELD), setOf(FakeData.CENTRAL))), folder = "To work", order = 0,
        ),
        SavedTrip(
            TRIP_CHATSWOOD_TALLAWONG, "Chatswood", "Tallawong",
            listOf(TripLeg(setOf(FakeData.CHATSWOOD), setOf(FakeData.TALLAWONG))), folder = "To work", order = 1,
        ),
        SavedTrip(
            TRIP_CENTRAL_STRATHFIELD, "Central", "Strathfield",
            listOf(TripLeg(setOf(FakeData.CENTRAL), setOf(FakeData.STRATHFIELD))), folder = "Home", order = 2,
        ),
        SavedTrip(
            TRIP_TOWNHALL_TALLAWONG, "Town Hall", "Tallawong",
            listOf(
                TripLeg(setOf(FakeData.TOWN_HALL), setOf(FakeData.CHATSWOOD)),
                TripLeg(setOf(FakeData.CHATSWOOD), setOf(FakeData.TALLAWONG), minConnectionMinutes = 4),
            ),
            folder = "Home", order = 3,
        ),
    )

    /** Places mockup: Home, Work, Gym. */
    val places: List<SavedPlace> = listOf(
        SavedPlace(
            HOME, "Home", PlaceIcon.HOME, "Albert Rd, Strathfield", LatLng(-33.8745, 151.0890),
            listOf(
                PlaceStop(FakeData.STRATHFIELD, "Strathfield", walkMinutes = 3, distanceMeters = 250),
                PlaceStop("214010", "Homebush", walkMinutes = 14, distanceMeters = 1100, estimated = true),
            ),
        ),
        SavedPlace(
            WORK, "Work", PlaceIcon.WORK, "Pitt St, Haymarket", LatLng(-33.8810, 151.2065),
            listOf(PlaceStop(FakeData.CENTRAL, "Central", walkMinutes = 4, distanceMeters = 350)),
        ),
        SavedPlace(
            GYM, "Gym", PlaceIcon.PIN, "Victoria Ave, Chatswood", LatLng(-33.7975, 151.1830),
            listOf(PlaceStop(FakeData.CHATSWOOD, "Chatswood", walkMinutes = 5, distanceMeters = 400)),
        ),
    )

    val tripPlaces: List<TripPlaces> = listOf(TripPlaces(TRIP_HOME_WORK, HOME, WORK))

    private val json = Json

    /** Stored form of [places] and [tripPlaces] for an `InMemoryKeyValueStore`. */
    fun placesValues(): Map<String, String> = mapOf(
        KeyValuePlacesStore.KEY_PLACES to json.encodeToString(ListSerializer(SavedPlace.serializer()), places),
        KeyValuePlacesStore.KEY_LINKS to json.encodeToString(ListSerializer(TripPlaces.serializer()), tripPlaces),
    )

    /** Stored form of [settings] for an `InMemoryKeyValueStore`. */
    fun settingsValues(settings: UiSettings): Map<String, String> = mapOf(
        KeyValueSettingsStore.KEY_THEME to settings.theme.name,
        KeyValueSettingsStore.KEY_CLOCK to settings.clockFormat.name,
        KeyValueSettingsStore.KEY_DENSITY to settings.cardDensity.name,
        KeyValueSettingsStore.KEY_REDUCE_MOTION to settings.reduceMotion.toString(),
        KeyValueSettingsStore.KEY_SOUNDS to settings.sounds.toString(),
        KeyValueSettingsStore.KEY_HAPTICS to settings.haptics.toString(),
        KeyValueSettingsStore.KEY_FOLLOW to settings.followJourney.toString(),
    )

    /**
     * Replaces the fake core's saved trips with [trips]. [FakeTransitCore]'s writes complete without
     * suspending, so this runs them directly (no scope needed); it fails loudly if that changes.
     */
    fun replaceTrips(core: FakeTransitCore, trips: List<SavedTrip>) {
        runNow {
            for (t in FakeTransitCore.SAMPLE_TRIPS) core.deleteTrip(t.id)
            for (t in trips.sortedBy { it.order }) core.saveTrip(t)
        }
    }

    private fun runNow(block: suspend () -> Unit) {
        var done = false
        var failure: Throwable? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { r -> done = true; failure = r.exceptionOrNull() })
        failure?.let { throw it }
        check(done) { "FakeTransitCore suspended while saving trips" }
    }
}
