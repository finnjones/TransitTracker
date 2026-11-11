package app.transittracker.ui

import app.transittracker.core.InMemoryKeyValueStore
import app.transittracker.model.LatLng
import app.transittracker.ui.app.alertsOnTrips
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.places.KeyValuePlacesStore
import app.transittracker.ui.places.PlaceIcon
import app.transittracker.ui.places.SavedPlace
import app.transittracker.ui.places.TripPlaces
import app.transittracker.core.FakeData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlacesStoreTest {
    @Test
    fun placesAndLinksPersistAndDeleteCleansLinks() = runTest {
        val kv = InMemoryKeyValueStore()
        val store = KeyValuePlacesStore(kv)
        assertEquals(emptyList(), store.places.first())
        val home = SavedPlace("h", "Home", PlaceIcon.HOME, "Albert Rd", LatLng(1.0, 2.0))
        val work = SavedPlace("w", "Work", PlaceIcon.WORK, "Pitt St", LatLng(3.0, 4.0))
        store.savePlace(home)
        store.savePlace(work)
        store.linkTrip("t1", "h", "w")
        store.savePlace(home.copy(name = "House"))
        // A second store over the same storage reads it all back.
        val again = KeyValuePlacesStore(kv)
        assertEquals(listOf("House", "Work"), again.places.first().map { it.name })
        assertEquals(TripPlaces("t1", "h", "w"), again.tripPlaces.first()["t1"])
        again.deletePlace("h")
        assertEquals(TripPlaces("t1", null, "w"), again.tripPlaces.first()["t1"])
        again.deletePlace("w")
        assertTrue(again.tripPlaces.first().isEmpty())
        again.savePlace(home)
        again.savePlace(work)
        again.reorderPlaces(listOf("w"))
        assertEquals(listOf("w", "h"), again.currentPlaces().map { it.id })
    }

    @Test
    fun fakePlacesAreSeeded() = runTest {
        val store = KeyValuePlacesStore(InMemoryKeyValueStore(FakeSetup.placesValues()))
        assertEquals(listOf("Home", "Work", "Gym"), store.places.first().map { it.name })
        assertEquals(TripPlaces(FakeSetup.TRIP_HOME_WORK, FakeSetup.HOME, FakeSetup.WORK), store.tripPlaces.first()[FakeSetup.TRIP_HOME_WORK])
    }

    @Test
    fun alertCountIsActiveAlertsOnMyTrips() {
        val now = 1_794_343_080_000L
        val alerts = FakeData.alerts(now)
        // The lift at Town Hall touches the Town Hall trip; the T9 trackwork is two days away.
        assertEquals(listOf("fake-alert-lift"), alertsOnTrips(alerts, FakeSetup.trips, now).map { it.id })
        assertEquals(emptyList(), alertsOnTrips(alerts, FakeSetup.trips.take(1), now).map { it.id })
    }
}
