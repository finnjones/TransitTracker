package app.transittracker.ui.feature.home

import app.transittracker.model.LatLng
import app.transittracker.model.SavedTrip
import app.transittracker.model.TripLeg
import app.transittracker.ui.places.PlaceIcon
import app.transittracker.ui.places.PlaceStop
import app.transittracker.ui.places.SavedPlace
import app.transittracker.ui.places.TripPlaces
import app.transittracker.ui.settings.CardDensity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Home puts the trips from where the user is first: at Home, Home to Work leads (its full card),
 * and Jas House to Home, which ends where they already are, goes last.
 */
class HomeOrderTest {
    private val home = SavedPlace("p-home", "Home", PlaceIcon.HOME, "Coogee", LatLng(-33.9200, 151.2550), listOf(PlaceStop("coogee-stop", "Coogee", 3, 200)))
    private val work = SavedPlace("p-work", "Work", PlaceIcon.WORK, "Bridge St", LatLng(-33.8640, 151.2100), listOf(PlaceStop("work-stop", "Wynyard", 5, 400)))
    private val jas = SavedPlace("p-jas", "Jas House", PlaceIcon.PIN, "Cherrybrook", LatLng(-33.7230, 151.0460), listOf(PlaceStop("cherrybrook", "Cherrybrook", 6, 500)))
    private val places = listOf(home, work, jas)

    private fun trip(id: String, from: String, to: String, order: Int, folder: String? = null) =
        SavedTrip(id, from, to, listOf(TripLeg(setOf(from.lowercase()), setOf(to.lowercase()))), folder = folder, order = order)

    private val jasHome = trip("jas-home", "Cherrybrook", "Coogee-stop", 0)
    private val homeWork = trip("home-work", "Coogee-stop", "Work-stop", 1)
    // Not linked to places: Waterloo is none of them, Cherrybrook is a stop of Jas House.
    private val waterlooJas = trip("waterloo-jas", "Waterloo", "Cherrybrook", 2)
    private val trips = listOf(jasHome, homeWork, waterlooJas)
    private val links = mapOf(
        jasHome.id to TripPlaces(jasHome.id, jas.id, home.id),
        homeWork.id to TripPlaces(homeWork.id, home.id, work.id),
    )

    private fun state(here: LatLng?, trips: List<SavedTrip> = this.trips) = buildHomeState(
        HomeInputs(trips, emptyMap(), null, CardDensity.COMFORTABLE, places, links, here = here),
        nowMs = 0, today = "20261125",
    )

    @Test
    fun atHomeTheTripsFromHomeComeFirst() {
        val s = state(LatLng(-33.9210, 151.2545))
        val cards = s.folders.single().cards
        assertEquals(listOf("home-work", "waterloo-jas", "jas-home"), cards.map { it.trip.id })
        // The first is the full card.
        assertEquals(listOf(true, false, false), cards.map { it.full })
    }

    @Test
    fun atAPlaceTheTripsLeavingFromItsStopsCountToo() {
        // At Jas House: Jas House to Home leads; Waterloo to Jas House arrives at its stop, so it goes last.
        val s = state(LatLng(-33.7235, 151.0465))
        assertEquals(listOf("jas-home", "home-work", "waterloo-jas"), s.folders.single().cards.map { it.trip.id })
    }

    @Test
    fun awayFromEveryPlaceTheUsersOwnOrderStands() {
        assertEquals(listOf("jas-home", "home-work", "waterloo-jas"), state(LatLng(-33.8830, 151.2060)).folders.single().cards.map { it.trip.id })
        assertEquals(listOf("jas-home", "home-work", "waterloo-jas"), state(null).folders.single().cards.map { it.trip.id })
    }

    @Test
    fun foldersFollowTheirFirstTrip() {
        val foldered = listOf(jasHome.copy(folder = "Weekend"), homeWork.copy(folder = "Work"), waterlooJas.copy(folder = "Weekend"))
        val s = state(LatLng(-33.9210, 151.2545), foldered)
        assertEquals(listOf("Work", "Weekend"), s.folders.map { it.name })
        assertEquals(listOf("waterloo-jas", "jas-home"), s.folders[1].cards.map { it.trip.id })
        assertTrue(s.folders.all { it.cards.first().full })
    }

    @Test
    fun theNearestPlaceWithinReachIsTheOneTheUserIsAt() {
        assertEquals(home, placeAt(LatLng(-33.9210, 151.2545), places))
        // About 1 km from Home: not at it.
        assertNull(placeAt(LatLng(-33.9290, 151.2550), places))
        assertNull(placeAt(null, places))
    }
}
