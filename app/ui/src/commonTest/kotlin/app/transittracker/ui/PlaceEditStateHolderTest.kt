package app.transittracker.ui

import app.transittracker.core.FakeData
import app.transittracker.model.JourneyPlanner
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.Place
import app.transittracker.model.PlaceKind
import app.transittracker.model.PlacesResult
import app.transittracker.model.TransitCore
import app.transittracker.model.Stop
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.feature.places.AddressSearch
import app.transittracker.ui.feature.places.PlaceEditState
import app.transittracker.ui.feature.places.PlaceEditStateHolder
import app.transittracker.ui.feature.places.estimateWalkMinutes
import app.transittracker.ui.places.PlaceIcon
import app.transittracker.ui.places.PlacesStore
import app.transittracker.ui.places.SavedPlace
import app.transittracker.ui.planning.FakeJourneyPlanner
import app.transittracker.ui.platform.FakePlatformServices
import app.transittracker.ui.platform.PermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The add / edit place state holder against the fakes: search, location, stops, save, delete. */
class PlaceEditStateHolderTest {
    private val albertRd = FakeJourneyPlanner.PLACES.first { it.name == "Albert Rd" }

    private fun holder(
        deps: AppDeps,
        scope: CoroutineScope,
        placeId: String? = null,
        platform: FakePlatformServices = deps.platform as FakePlatformServices,
        places: PlacesStore = deps.places,
        planner: JourneyPlanner = deps.planner,
        core: TransitCore = deps.core,
    ) = PlaceEditStateHolder(core, planner, places, platform, placeId, scope, newId = { "new-place" })

    private fun PlaceEditStateHolder.now(): PlaceEditState = state.value

    // ---------------------------------------------------------------- address search

    @Test
    fun typingAsksTheLookupAfterAPauseAndShowsAddressesFirst() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope)
        h.setQuery("Bondi")
        assertEquals(AddressSearch.Searching, h.now().search)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(AddressSearch.Searching, h.now().search) // still waiting for the pause
        advanceTimeBy(100)
        runCurrent()
        val results = assertIs<AddressSearch.Results>(h.now().search)
        assertEquals(PlaceKind.ADDRESS, results.places.first().kind) // Campbell Pde before Bondi Beach
        assertEquals(listOf("Campbell Pde", "Bondi Beach", "Bondi Junction Station"), results.places.map { it.name })
    }

    @Test
    fun keepingOnTypingAsksOnlyOnce() = runTest {
        val deps = AppDeps.fake()
        val asked = mutableListOf<String>()
        val planner = object : JourneyPlanner by deps.planner {
            override suspend fun searchPlaces(query: String, near: LatLng?, limit: Int): PlacesResult {
                asked += query
                return deps.planner.searchPlaces(query, near, limit)
            }
        }
        val h = holder(deps, backgroundScope, planner = planner)
        h.setQuery("Alb")
        advanceTimeBy(200)
        h.setQuery("Albe")
        advanceTimeBy(200)
        h.setQuery("Alber")
        advanceTimeBy(400)
        runCurrent()
        assertEquals(listOf("Alber"), asked)
        assertIs<AddressSearch.Results>(h.now().search)
    }

    @Test
    fun shortTextDoesNotSearch() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope)
        h.setQuery("Al")
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(AddressSearch.Idle, h.now().search)
    }

    @Test
    fun theLookupBeingOfflineUnavailableOrEmptyEachHaveTheirOwnState() = runTest {
        val deps = AppDeps.fake()
        val planner = deps.planner as FakeJourneyPlanner
        val h = holder(deps, backgroundScope)

        planner.answer = FakeJourneyPlanner.Answer.OFFLINE
        h.setQuery("Albert")
        advanceTimeBy(400); runCurrent()
        assertEquals(AddressSearch.Offline, h.now().search)

        planner.answer = FakeJourneyPlanner.Answer.UNAVAILABLE
        h.retrySearch()
        runCurrent()
        assertEquals(AddressSearch.Unavailable, h.now().search)

        planner.answer = FakeJourneyPlanner.Answer.FAILED
        h.retrySearch()
        runCurrent()
        assertEquals(AddressSearch.Failed, h.now().search)

        planner.answer = FakeJourneyPlanner.Answer.NORMAL
        h.setQuery("Zzzzz")
        advanceTimeBy(400); runCurrent()
        assertEquals(AddressSearch.NothingFound, h.now().search)

        h.setQuery("Albert")
        advanceTimeBy(400); runCurrent()
        assertIs<AddressSearch.Results>(h.now().search)
    }

    @Test
    fun aLookupThatThrowsIsAFailureNotACrash() = runTest {
        val deps = AppDeps.fake()
        val planner = object : JourneyPlanner by deps.planner {
            override suspend fun searchPlaces(query: String, near: LatLng?, limit: Int): PlacesResult = error("boom")
        }
        val h = holder(deps, backgroundScope, planner = planner)
        h.setQuery("Albert")
        advanceTimeBy(400); runCurrent()
        assertEquals(AddressSearch.Failed, h.now().search)
    }

    @Test
    fun anAnswerThatArrivesAfterMoreTypingIsIgnored() = runTest {
        val deps = AppDeps.fake()
        val h = holder(deps, backgroundScope)
        h.setQuery("Albert")
        advanceTimeBy(400); runCurrent()
        h.setQuery("Albert Rd, Str") // the old answer is gone with the old text
        assertEquals(AddressSearch.Searching, h.now().search)
    }

    // ---------------------------------------------------------------- picking an address

    @Test
    fun pickingAnAddressListsTheStationsWithinWalkingDistanceWithEstimatedWalks() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope)
        h.pick(albertRd)
        runCurrent()
        val s = h.now()
        assertEquals("Albert Rd, Strathfield", s.query)
        assertEquals(albertRd.position, s.position)
        assertEquals(AddressSearch.Idle, s.search)
        val strathfield = s.stops.single().stop
        assertEquals(FakeData.STRATHFIELD, strathfield.stopId)
        assertEquals("Strathfield", strathfield.name)
        assertTrue(strathfield.estimated)
        assertTrue(strathfield.enabled)
        assertEquals(estimateWalkMinutes(strathfield.distanceMeters), strathfield.walkMinutes)
        assertTrue(s.stops.single().lines.isNotEmpty()) // T1, T9
        assertFalse(s.stopsLoading)
    }

    @Test
    fun typingAfterPickingDropsThePositionAndTheStopsButRemembersChoices() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope)
        h.pick(albertRd)
        runCurrent()
        h.setStopEnabled(FakeData.STRATHFIELD, false)
        h.setQuery("Albert Rd, Strathfield NSW")
        assertNull(h.now().position)
        assertTrue(h.now().stops.isEmpty())
        h.pick(albertRd)
        runCurrent()
        assertFalse(h.now().stops.single().stop.enabled) // still switched off
    }

    @Test
    fun aPositionWithNoStationsNearbySaysSoAndCannotBeSaved() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope)
        h.setName("Camp")
        h.pick(FakeJourneyPlanner.PLACES.first { it.name == "Bondi Beach" }.copy(position = LatLng(-33.5, 150.5)))
        runCurrent()
        assertTrue(h.now().stops.isEmpty())
        assertFalse(h.now().canSave)
        assertEquals("No stations or stops are within walking distance of here. Try another address.", h.now().saveProblem)
    }

    @Test
    fun busAndLightRailStopsFromThePlannerAreOfferedWhereThereIsNoStation() = runTest {
        // Like Coogee St, Randwick: no train station within 1.5 km, buses and light rail close by.
        val deps = AppDeps.fake()
        val home = LatLng(-33.5, 150.5)
        val planner = deps.planner as FakeJourneyPlanner
        planner.nearStops = listOf(
            Place("G1", PlaceKind.STOP, "Carrington Rd at Coogee St", "Randwick", LatLng(-33.5, 150.5025), modes = listOf(Mode.BUS)),
            Place("2", PlaceKind.STOP, "Randwick Light Rail", "Randwick", LatLng(-33.5, 150.5056), modes = listOf(Mode.LIGHT_RAIL)),
            Place("G3", PlaceKind.STOP, "Far Bus Stop", "Randwick", LatLng(-33.5, 150.5080), modes = listOf(Mode.BUS)),
        )
        val h = holder(deps, backgroundScope)
        h.setName("Home")
        h.pick(albertRd.copy(position = home))
        runCurrent()
        assertEquals(listOf(home), planner.nearLookups)
        assertEquals(listOf("Carrington Rd at Coogee St", "Randwick"), h.now().stops.map { it.stop.name })
        assertEquals(listOf(Mode.LIGHT_RAIL), h.now().stops[1].stop.modes)
        assertNull(h.now().stopsNote)
        assertTrue(h.now().canSave)
    }

    @Test
    fun withoutThePlannerTheTimetablesStationsAreOfferedWithANote() = runTest {
        val deps = AppDeps.fake()
        (deps.planner as FakeJourneyPlanner).answer = FakeJourneyPlanner.Answer.OFFLINE
        val h = holder(deps, backgroundScope)
        h.setName("Home")
        h.pick(albertRd)
        runCurrent()
        assertTrue(h.now().stops.isNotEmpty())
        assertNotNull(h.now().stopsNote)
        assertTrue(h.now().canSave)
    }

    @Test
    fun withoutThePlannerAndNoStationNearbyItDoesNotClaimNothingIsNear() = runTest {
        val deps = AppDeps.fake()
        val planner = deps.planner as FakeJourneyPlanner
        planner.answer = FakeJourneyPlanner.Answer.OFFLINE
        val h = holder(deps, backgroundScope)
        h.setName("Home")
        h.pick(albertRd.copy(position = LatLng(-33.5, 150.5)))
        runCurrent()
        assertEquals("Could not look up the stops near here. Check the connection and try again.", h.now().stopsProblem)
        assertFalse(h.now().canSave)
    }

    @Test
    fun aFailedStopLookupOffersToTryAgain() = runTest {
        val deps = AppDeps.fake()
        val planner = deps.planner as FakeJourneyPlanner
        planner.answer = FakeJourneyPlanner.Answer.FAILED
        var fail = true
        val core = object : TransitCore by deps.core {
            override suspend fun stopsNear(position: LatLng, radiusMeters: Double): List<Stop> =
                if (fail) error("no timetable") else deps.core.stopsNear(position, radiusMeters)
        }
        val h = holder(deps, backgroundScope, core = core)
        h.setName("Home")
        h.pick(albertRd)
        runCurrent()
        assertNotNull(h.now().stopsProblem)
        assertFalse(h.now().canSave)
        fail = false
        planner.answer = FakeJourneyPlanner.Answer.NORMAL
        h.retryStops()
        runCurrent()
        assertNull(h.now().stopsProblem)
        assertTrue(h.now().canSave)
    }

    // ---------------------------------------------------------------- where I am now

    @Test
    fun locationIsAskedOnlyWhenTheButtonIsTapped() = runTest {
        val platform = FakePlatformServices(locationPermission = PermissionState.NOT_ASKED, location = albertRd.position)
        val h = holder(AppDeps.fake(platform = platform), backgroundScope)
        h.setName("Home")
        h.setQuery("Albert")
        advanceTimeBy(400); runCurrent()
        assertEquals(PermissionState.NOT_ASKED, platform.locationPermission.value) // typing never asks
        h.useMyLocation()
        runCurrent()
        assertEquals(PermissionState.GRANTED, platform.locationPermission.value)
        val s = h.now()
        assertEquals(albertRd.position, s.position)
        assertEquals("Near Strathfield", s.query)
        assertEquals(AddressSearch.Idle, s.search)
        assertFalse(s.locating)
        assertEquals(FakeData.STRATHFIELD, s.stops.single().stop.stopId)
        assertTrue(s.canSave)
    }

    @Test
    fun refusingLocationLeavesAPlainSentenceAndTypingStillWorks() = runTest {
        val platform = FakePlatformServices(locationPermission = PermissionState.NOT_ASKED).also { it.grantOnRequest = false }
        val h = holder(AppDeps.fake(platform = platform), backgroundScope)
        h.useMyLocation()
        runCurrent()
        assertNull(h.now().position)
        assertFalse(h.now().locating)
        assertEquals("Location was not allowed, so type an address instead.", h.now().locationProblem)
        h.pick(albertRd) // the other way in still works
        runCurrent()
        assertNull(h.now().locationProblem)
        assertEquals(albertRd.position, h.now().position)
    }

    @Test
    fun locationRefusedEarlierPointsToThePhoneSettingsWithoutAskingAgain() = runTest {
        val platform = FakePlatformServices(locationPermission = PermissionState.DENIED)
        val h = holder(AppDeps.fake(platform = platform), backgroundScope)
        h.useMyLocation()
        runCurrent()
        assertTrue(h.now().locationProblem!!.contains("phone's settings"))
        assertNull(h.now().position)
    }

    @Test
    fun noFixIsAPlainSentenceToo() = runTest {
        val platform = FakePlatformServices(location = null)
        val h = holder(AppDeps.fake(platform = platform), backgroundScope)
        h.useMyLocation()
        runCurrent()
        assertEquals("Could not find where you are. Try again, or type an address.", h.now().locationProblem)
        assertFalse(h.now().locating)
    }

    // ---------------------------------------------------------------- stops

    @Test
    fun aStopCanBeSwitchedOffAndTheWalkCorrectedWithAStepper() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope)
        h.pick(albertRd)
        runCurrent()
        val id = FakeData.STRATHFIELD
        val before = h.now().stops.single().stop.walkMinutes
        h.toggleWalk(id)
        assertEquals(id, h.now().editingWalk)
        h.stepWalk(id, 1)
        h.stepWalk(id, 1)
        var s = h.now().stops.single().stop
        assertEquals(before + 2, s.walkMinutes)
        assertFalse(s.estimated) // now the user's figure
        h.stepWalk(id, -1)
        assertEquals(before + 1, h.now().stops.single().stop.walkMinutes)
        h.toggleWalk(id)
        assertNull(h.now().editingWalk)
        h.setStopEnabled(id, false)
        s = h.now().stops.single().stop
        assertFalse(s.enabled)
    }

    @Test
    fun theStepperDoesNotGoBelowOneMinute() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope)
        h.pick(albertRd)
        runCurrent()
        repeat(40) { h.stepWalk(FakeData.STRATHFIELD, -1) }
        assertEquals(1, h.now().stops.single().stop.walkMinutes)
    }

    // ---------------------------------------------------------------- saving

    @Test
    fun saveStaysOffUntilThereIsANameAnAddressAndAStop() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope)
        assertFalse(h.now().canSave)
        assertEquals("Give the place a name.", h.now().saveProblem)
        h.setName("Home")
        assertEquals("Pick an address from the list, or use where you are now.", h.now().saveProblem)
        h.pick(albertRd)
        assertEquals("Looking up the stops near here.", h.now().saveProblem)
        runCurrent()
        assertNull(h.now().saveProblem)
        assertTrue(h.now().canSave)
        h.setStopEnabled(FakeData.STRATHFIELD, false)
        assertEquals("Switch on at least one stop you would walk to.", h.now().saveProblem)
        assertFalse(h.now().canSave)
        h.setStopEnabled(FakeData.STRATHFIELD, true)
        h.setName("   ")
        assertFalse(h.now().canSave)
    }

    @Test
    fun savingStoresTheNewPlaceAndFinishes() = runTest {
        val deps = AppDeps.fake()
        val h = holder(deps, backgroundScope)
        h.setName("  Mum's  ")
        h.setIcon(PlaceIcon.HOME)
        h.pick(albertRd)
        runCurrent()
        h.save()
        runCurrent()
        assertTrue(h.now().finished)
        val saved = deps.places.currentPlaces().first { it.id == "new-place" }
        assertEquals("Mum's", saved.name)
        assertEquals(PlaceIcon.HOME, saved.icon)
        assertEquals("Albert Rd, Strathfield", saved.address)
        assertEquals(albertRd.position, saved.position)
        assertEquals(listOf(FakeData.STRATHFIELD), saved.stops.map { it.stopId })
        assertEquals(4, deps.places.currentPlaces().size) // Home, Work, Gym and the new one
    }

    @Test
    fun aFailedSaveIsAPlainSentenceAndRetryTriesAgain() = runTest {
        val deps = AppDeps.fake()
        var fail = true
        val places = object : PlacesStore by deps.places {
            override suspend fun savePlace(place: SavedPlace) {
                if (fail) error("storage full")
                deps.places.savePlace(place)
            }
        }
        val h = holder(deps, backgroundScope, places = places)
        h.setName("Mum's")
        h.pick(albertRd)
        runCurrent()
        h.save()
        runCurrent()
        val problem = assertNotNull(h.now().problem)
        assertEquals("Could not save the place", problem.title)
        assertEquals("The place was not saved. Try again in a moment.", problem.words)
        assertFalse(h.now().finished)
        assertFalse(h.now().saving)
        assertEquals(3, deps.places.currentPlaces().size)

        fail = false
        h.retry()
        runCurrent()
        assertNull(h.now().problem)
        assertTrue(h.now().finished)
        assertEquals(4, deps.places.currentPlaces().size)
    }

    @Test
    fun closingTheProblemKeepsWhatWasTyped() = runTest {
        val deps = AppDeps.fake()
        val places = object : PlacesStore by deps.places {
            override suspend fun savePlace(place: SavedPlace) = error("no")
        }
        val h = holder(deps, backgroundScope, places = places)
        h.setName("Mum's")
        h.pick(albertRd)
        runCurrent()
        h.save()
        runCurrent()
        h.dismissProblem()
        assertNull(h.now().problem)
        assertEquals("Mum's", h.now().name)
        assertTrue(h.now().canSave)
    }

    // ---------------------------------------------------------------- editing and deleting

    @Test
    fun anExistingPlaceOpensWithItsStopsAndLines() = runTest {
        val deps = AppDeps.fake()
        val h = holder(deps, backgroundScope, FakeSetup.HOME)
        val s = h.state.first { !it.loading }
        runCurrent()
        assertFalse(s.isNew)
        assertTrue(h.now().canDelete)
        assertEquals("Home", h.now().name)
        assertEquals(PlaceIcon.HOME, h.now().icon)
        assertEquals("Albert Rd, Strathfield", h.now().query)
        assertEquals(listOf(FakeData.STRATHFIELD, "214010"), h.now().stops.map { it.stop.stopId })
        assertTrue(h.now().stops.first().lines.isNotEmpty())
        assertTrue(h.now().canSave) // nothing to retype
    }

    @Test
    fun editingKeepsTheIdAndTheOrderInTheList() = runTest {
        val deps = AppDeps.fake()
        val h = holder(deps, backgroundScope, FakeSetup.WORK)
        h.state.first { !it.loading }
        h.setName("Office")
        h.setStopEnabled(FakeData.CENTRAL, true)
        h.stepWalk(FakeData.CENTRAL, 2)
        h.save()
        runCurrent()
        val all = deps.places.currentPlaces()
        assertEquals(listOf(FakeSetup.HOME, FakeSetup.WORK, FakeSetup.GYM), all.map { it.id })
        val work = all[1]
        assertEquals("Office", work.name)
        assertEquals(6, work.stops.single().walkMinutes)
        assertFalse(work.stops.single().estimated)
    }

    @Test
    fun aPlaceThatIsGoneSaysSo() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope, "nowhere")
        val s = h.state.first { !it.loading }
        assertTrue(s.missing)
        assertFalse(s.canSave)
    }

    @Test
    fun deleteAsksFirstAndSaysHowManyRoutesUseThePlace() = runTest {
        val deps = AppDeps.fake()
        val h = holder(deps, backgroundScope, FakeSetup.HOME)
        h.state.first { !it.loading }
        h.askDelete()
        runCurrent()
        assertTrue(h.now().confirmDelete)
        assertEquals(1, h.now().routesUsing) // Home to Work
        assertEquals(3, deps.places.currentPlaces().size) // nothing deleted yet
        h.cancelDelete()
        assertFalse(h.now().confirmDelete)
        h.askDelete()
        runCurrent()
        h.confirmDelete()
        runCurrent()
        assertTrue(h.now().finished)
        assertEquals(listOf(FakeSetup.WORK, FakeSetup.GYM), deps.places.currentPlaces().map { it.id })
        // The route keeps its other end; only the deleted place is cleared from its link.
        val link = deps.places.tripPlaces.first().getValue(FakeSetup.TRIP_HOME_WORK)
        assertNull(link.fromPlaceId)
        assertEquals(FakeSetup.WORK, link.toPlaceId)
    }

    @Test
    fun aPlaceNoRouteUsesCountsZero() = runTest {
        val deps = AppDeps.fake()
        val h = holder(deps, backgroundScope, FakeSetup.GYM)
        h.state.first { !it.loading }
        h.askDelete()
        runCurrent()
        assertEquals(0, h.now().routesUsing)
    }

    @Test
    fun aFailedDeleteCanBeRetried() = runTest {
        val deps = AppDeps.fake()
        var fail = true
        val places = object : PlacesStore by deps.places {
            override suspend fun deletePlace(id: String) {
                if (fail) error("locked")
                deps.places.deletePlace(id)
            }
        }
        val h = holder(deps, backgroundScope, FakeSetup.GYM, places = places)
        h.state.first { !it.loading }
        h.askDelete()
        runCurrent()
        h.confirmDelete()
        runCurrent()
        assertEquals("Could not delete the place", assertNotNull(h.now().problem).title)
        assertFalse(h.now().finished)
        fail = false
        h.retry()
        runCurrent()
        assertTrue(h.now().finished)
        assertEquals(2, deps.places.currentPlaces().size)
    }

    @Test
    fun aNewPlaceCannotBeDeleted() = runTest {
        val h = holder(AppDeps.fake(), backgroundScope)
        assertFalse(h.now().canDelete)
        h.askDelete()
        runCurrent()
        assertFalse(h.now().confirmDelete)
    }
}
