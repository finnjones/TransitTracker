package app.transittracker.ui.feature.plan

import app.transittracker.core.FakeData
import app.transittracker.model.PlanEndpoint
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanTimeKind
import app.transittracker.model.rides
import app.transittracker.model.LatLng
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.CoreScenario
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.fake.LiveData
import app.transittracker.ui.gallery.SampleData
import app.transittracker.ui.nav.JourneyEnd
import app.transittracker.ui.planning.FakeJourneyPlanner
import app.transittracker.ui.platform.FakePlatformServices
import app.transittracker.ui.platform.PermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The planner screens' state holders against AppDeps.fake() and the FakeJourneyPlanner's answers. */
class PlanStateHoldersTest {
    private val home = JourneyEnd("Home", FakeJourneyPlanner.HOME, savedPlaceId = FakeSetup.HOME)
    private val work = JourneyEnd("Work", FakeJourneyPlanner.PITT_ST, savedPlaceId = FakeSetup.WORK)
    private val campbell = JourneyEnd("Campbell Pde", FakeJourneyPlanner.CAMPBELL_PDE, plannerPlaceId = "fake:campbell-pde")

    private fun AppDeps.fakePlanner() = planner as FakeJourneyPlanner

    private fun options(deps: AppDeps, scope: CoroutineScope, from: JourneyEnd = home, to: JourneyEnd = campbell) =
        JourneyOptionsStateHolder(deps.planner, deps.core, deps.clock, PlanRequest(from.toPlanEndpoint(), to.toPlanEndpoint()), scope)

    // ------------------------------------------------------------ options

    @Test
    fun optionsFoundAreSortedAndNoteTheLiveModes() = runTest {
        val deps = AppDeps.fake()
        val h = options(deps, backgroundScope)
        val s = h.state.first { it.phase == OptionsPhase.FOUND }
        assertEquals(3, s.options.size)
        assertEquals(listOf(54, 56, 66), s.options.map { it.minutes })
        assertEquals("TRAINS ARE LIVE. BUS TIMES ARE TIMETABLED.", s.liveNote)
        assertEquals("T9 4 MIN LATE · CHANGE STILL OK", s.options.first().live?.words)
        h.sort(OptionSort.FEWER_CHANGES)
        val sorted = h.state.first { it.sort == OptionSort.FEWER_CHANGES }
        assertEquals(1, sorted.options.first().changes)
        assertEquals(1, deps.fakePlanner().requests.size)
    }

    @Test
    fun nothingFoundShowsTheStopsNearEachEnd() = runTest {
        val deps = AppDeps.fake()
        deps.fakePlanner().answer = FakeJourneyPlanner.Answer.NOTHING_FOUND
        val s = options(deps, backgroundScope).state.first { it.phase == OptionsPhase.NOTHING_FOUND && it.nearFrom.isNotEmpty() }
        assertEquals(listOf("Strathfield"), s.nearFrom)
        assertTrue(s.nearTo.isEmpty())
    }

    @Test
    fun offlineUnavailableAndFailedThenRetry() = runTest {
        val deps = AppDeps.fake(scenario = CoreScenario(live = LiveData.OFFLINE))
        val planner = deps.fakePlanner()
        planner.answer = FakeJourneyPlanner.Answer.OFFLINE
        val h = options(deps, backgroundScope)
        val off = h.state.first { it.phase == OptionsPhase.OFFLINE && it.savedRoutes.isNotEmpty() }
        assertEquals(FakeSetup.trips.size, off.savedRoutes.size)

        planner.answer = FakeJourneyPlanner.Answer.UNAVAILABLE
        h.retry()
        h.state.first { it.phase == OptionsPhase.UNAVAILABLE }

        planner.answer = FakeJourneyPlanner.Answer.FAILED
        h.retry()
        val failed = h.state.first { it.phase == OptionsPhase.FAILED }
        assertEquals("The planner had a problem", failed.failMessage)

        planner.answer = FakeJourneyPlanner.Answer.NORMAL
        h.retry()
        h.state.first { it.phase == OptionsPhase.FOUND }
        assertEquals(4, planner.requests.size)
    }

    @Test
    fun homeToWorkRidesTheFakeCoresT9WithThePhonesLiveData() = runTest {
        val deps = AppDeps.fake()
        val s = options(deps, backgroundScope, home, work).state.first { st ->
            st.phase == OptionsPhase.FOUND && st.options.all { o -> o.timeline.rides.all { it.times.fromPhone } }
        }
        assertTrue(s.options.isNotEmpty())
        for (o in s.options) {
            val ride = o.itinerary.rides.single()
            assertNotNull(ride.serviceRef)
            assertEquals(FakeData.CENTRAL, ride.to.stopId!!.substringBefore("-P"))
            assertTrue(o.leaveMs >= SampleData.NOW_MS)
        }
    }

    // ------------------------------------------------------------ the form

    private fun form(deps: AppDeps, scope: CoroutineScope, from: String? = null, to: String? = null, recents: RecentSearchStore = InMemoryRecentSearches()) =
        PlanJourneyStateHolder(deps.planner, deps.core, deps.places, deps.platform, deps.clock, recents, from, to, scope)

    @Test
    fun placesFillTheFormAndFindMakesTheOptionsRoute() = runTest {
        val deps = AppDeps.fake()
        val recents = InMemoryRecentSearches()
        val h = form(deps, backgroundScope, from = FakeSetup.HOME, recents = recents)
        h.state.first { it.from?.end?.name == "Home" && it.places.size == 3 }
        assertNull(h.find())
        assertEquals(Field.TO, h.state.value.target)
        h.choosePlace(FakeSetup.places.first { it.id == FakeSetup.WORK })
        val s = h.state.first { it.to != null }
        assertEquals(EndIcon.WORK, s.to!!.icon)
        assertTrue(s.canFind)
        h.swap()
        assertEquals("Work", h.state.first { it.from?.end?.name == "Work" }.from!!.end.name)
        h.swap()
        h.setWhen(WhenKind.ARRIVE_BY)
        h.stepTime(20)
        val route = assertNotNull(h.find())
        assertEquals(home, route.from)
        assertEquals(work, route.to)
        assertEquals(PlanTimeKind.ARRIVE_BY, route.timeKind)
        assertEquals(SampleData.NOW_MS / 60_000 * 60_000 + 22 * 60_000, route.timeMs) // 07:40 + 20 min
        assertEquals(listOf(RecentSearch(home, work)), recents.recent.value)
    }

    @Test
    fun aStationFromTheMapFillsFromUnlessASavedPlaceIsGiven() = runTest {
        val deps = AppDeps.fake()
        val station = JourneyEnd("Strathfield", LatLng(-33.8718, 151.0943))
        fun holder(fromPlace: String?) = PlanJourneyStateHolder(
            deps.planner, deps.core, deps.places, deps.platform, deps.clock, InMemoryRecentSearches(),
            fromPlace, null, backgroundScope, initialFrom = station,
        )
        val s = holder(null).state.first { it.from != null }
        assertEquals(station, s.from!!.end)
        assertEquals(EndIcon.STOP, s.from!!.icon)
        assertEquals(Field.TO, s.target)
        // A saved place given for From wins over the station.
        assertEquals("Home", holder(FakeSetup.HOME).state.first { it.from != null }.from!!.end.name)
    }

    @Test
    fun searchIsDebouncedAndSaysWhenNothingMatches() = runTest {
        val deps = AppDeps.fake()
        val h = form(deps, backgroundScope)
        h.edit(Field.TO)
        h.setQuery("b")
        h.setQuery("bo")
        h.setQuery("bondi")
        val found = h.state.first { it.search is SearchState.Found }
        assertEquals(3, (found.search as SearchState.Found).places.size)
        assertEquals(listOf("bondi"), deps.fakePlanner().searches) // only the last query was sent
        h.chooseResult((found.search as SearchState.Found).places.first())
        val chosen = h.state.first { it.to != null }
        assertEquals("Bondi Beach", chosen.to!!.end.name)
        assertEquals("fake:bondi-beach", chosen.to!!.end.plannerPlaceId)
        assertNull(chosen.editing)

        h.edit(Field.FROM)
        h.setQuery("zzz")
        h.state.first { it.search == SearchState.NoResults }

        deps.fakePlanner().answer = FakeJourneyPlanner.Answer.UNAVAILABLE
        h.setQuery("central")
        val un = h.state.first { it.search == SearchState.Unavailable }
        assertTrue(un.plannerUnavailable)
    }

    @Test
    fun whereIAmAsksForLocationOnlyWhenTapped() = runTest {
        val platform = FakePlatformServices(locationPermission = PermissionState.NOT_ASKED)
        val deps = AppDeps.fake(platform = platform)
        val h = form(deps, backgroundScope)
        h.state.first { it.places.isNotEmpty() }
        assertEquals(PermissionState.NOT_ASKED, platform.locationPermission.value)
        h.useLocation()
        val s = h.state.first { it.from != null }
        assertEquals(WHERE_I_AM, s.from!!.end.name)
        assertEquals(EndIcon.HERE, s.from!!.icon)
        assertEquals(PermissionState.GRANTED, platform.locationPermission.value)

        val refused = FakePlatformServices(locationPermission = PermissionState.NOT_ASKED).apply { grantOnRequest = false }
        val h2 = form(AppDeps.fake(platform = refused), backgroundScope)
        h2.useLocation()
        val n = h2.state.first { it.notice != null }
        assertNull(n.from)
        assertTrue(n.notice!!.startsWith("Location is off"))
    }

    @Test
    fun offlineFormOffersTheSavedRoutes() = runTest {
        val h = form(AppDeps.fake(scenario = CoreScenario(live = LiveData.OFFLINE)), backgroundScope)
        val s = h.state.first { it.offline && it.savedRoutes.isNotEmpty() }
        assertEquals(FakeSetup.trips.size, s.savedRoutes.size)
    }

    // ------------------------------------------------------------ one journey and saving

    private suspend fun homeWorkItinerary(deps: AppDeps) =
        (deps.planner.plan(PlanRequest(PlanEndpoint(home.position), PlanEndpoint(work.position))) as app.transittracker.model.PlanResult.Found)
            .response.itineraries.first()

    @Test
    fun homeToWorkCanBeSavedWithItsReturnAndLinkedToThePlaces() = runTest {
        val deps = AppDeps.fake(trips = emptyList())
        val it = homeWorkItinerary(deps)
        val h = JourneyStateHolder(deps.core, deps.places, deps.clock, it, home, work, backgroundScope)
        val ready = assertIs<SaveState.Ready>(h.state.first { s -> s.save !is SaveState.Checking }.save)
        assertEquals(setOf(FakeData.STRATHFIELD), ready.trip.legs.single().originStopIds)
        assertEquals(setOf(FakeData.CENTRAL), ready.trip.legs.single().destinationStopIds)
        assertEquals(setOf(it.rides.single().routeId!!), ready.trip.legs.single().routeIds)
        assertTrue(it.rides.single().routeId in setOf(FakeData.T9, FakeData.T1))
        h.save(withReturn = true)
        val saved = assertIs<SaveState.Saved>(h.state.first { s -> s.save is SaveState.Saved }.save)
        assertEquals(2, saved.tripIds.size)
        val trips = deps.core.trips.first { t -> t.size == 2 }
        assertEquals(listOf("Strathfield", "Central"), trips.map { t -> t.originName })
        val links = deps.places.tripPlaces.first { l -> l.size >= 3 }
        assertEquals(FakeSetup.HOME, links.getValue(ready.trip.id).fromPlaceId)
        assertEquals(FakeSetup.WORK, links.getValue(ready.trip.id).toPlaceId)
        assertEquals(FakeSetup.WORK, links.getValue(ready.returnTrip.id).fromPlaceId)
        // The saved route shows on Home: the core finds its next services.
        val upcoming = deps.core.upcoming(ready.trip.id, 3).first { j -> j.isNotEmpty() }
        assertTrue(upcoming.isNotEmpty())
    }

    @Test
    fun theMockupJourneyCannotBeSavedAndSaysWhy() = runTest {
        val deps = AppDeps.fake()
        val it = FakeJourneyPlanner.itineraries(SampleData.NOW_MS / 60_000 * 60_000).first()
        val h = JourneyStateHolder(deps.core, deps.places, deps.clock, it, home, campbell, backgroundScope)
        val blocked = assertIs<SaveState.Blocked>(h.state.first { s -> s.save !is SaveState.Checking }.save)
        assertTrue("not in the phone's timetable" in blocked.reason)
        h.save(false) // does nothing
        assertIs<SaveState.Blocked>(h.state.value.save)
        assertEquals(54, h.state.value.timeline.minutes)
    }

    // ------------------------------------------------------------ ways of one route

    @Test
    fun aJourneyBetweenSavedPlacesCanJoinTheirRouteAsAnotherWay() = runTest {
        val deps = AppDeps.fake()
        val it = homeWorkItinerary(deps)
        val h = JourneyStateHolder(deps.core, deps.places, deps.clock, it, home, work, backgroundScope)
        val ready = assertIs<SaveState.Ready>(h.state.first { s -> s.save !is SaveState.Checking }.save)
        assertEquals(FakeSetup.TRIP_HOME_WORK, ready.joinTo?.id)
        assertEquals("Home to Work", ready.joinTitle)
        assertTrue(!ready.alreadyThere)
        h.save(withReturn = false)
        val saved = assertIs<SaveState.Saved>(h.state.first { s -> s.save is SaveState.Saved }.save)
        assertEquals(listOf(FakeSetup.TRIP_HOME_WORK), saved.tripIds)
        val trips = deps.core.trips.first { t -> t.first { r -> r.id == FakeSetup.TRIP_HOME_WORK }.ways.size == 2 }
        assertEquals(FakeSetup.trips.size, trips.size, "no new route")
        val route = trips.first { r -> r.id == FakeSetup.TRIP_HOME_WORK }
        assertEquals(FakeSetup.trips.first().legs, route.legs, "the first way stays first")
        assertEquals(ready.trip.legs, route.ways[1].legs)
        // Home finds services of both ways, each tagged.
        val upcoming = deps.core.upcoming(route.id, 3).first { j -> j.isNotEmpty() }
        assertTrue(upcoming.all { j -> j.wayId != null })
    }

    @Test
    fun aJourneyCanStillBeSavedAsARouteOfItsOwn() = runTest {
        val deps = AppDeps.fake()
        val h = JourneyStateHolder(deps.core, deps.places, deps.clock, homeWorkItinerary(deps), home, work, backgroundScope)
        h.state.first { s -> s.save is SaveState.Ready }
        h.save(withReturn = false, join = false)
        val saved = assertIs<SaveState.Saved>(h.state.first { s -> s.save is SaveState.Saved }.save)
        assertTrue(FakeSetup.TRIP_HOME_WORK !in saved.tripIds)
        assertEquals(FakeSetup.trips.size + 1, deps.core.trips.first { t -> t.size > FakeSetup.trips.size }.size)
    }

    @Test
    fun theJourneysFoundCanBeSavedAsTheWaysOfOneRoute() = runTest {
        val deps = AppDeps.fake(trips = emptyList())
        val h = JourneyOptionsStateHolder(
            deps.planner, deps.core, deps.clock, PlanRequest(home.toPlanEndpoint(), work.toPlanEndpoint()), backgroundScope,
            from = home, to = work, places = deps.places,
        )
        assertTrue(h.state.first { it.phase == OptionsPhase.FOUND }.canSaveWays)
        h.startSaveWays()
        val choosing = assertIs<SaveWaysState.Choosing>(h.state.first { it.saveWays is SaveWaysState.Choosing }.saveWays)
        val savable = choosing.ways.filter { it.trip != null }
        assertTrue(savable.isNotEmpty())
        assertEquals(savable.map { it.id }.toSet(), choosing.selected)
        assertEquals(savable.size, savable.map { it.id }.distinct().size, "the same way at another time is listed once")
        assertNull(choosing.joinTitle, "no route saved yet")
        h.confirmSaveWays(withReturn = true, join = true)
        val saved = assertIs<SaveWaysState.Saved>(h.state.first { it.saveWays is SaveWaysState.Saved }.saveWays)
        assertEquals(2, saved.tripIds.size)
        val trips = deps.core.trips.first { t -> t.size == 2 }
        assertEquals(savable.size, trips.first { it.id == saved.tripIds[0] }.ways.size)
        assertEquals(savable.size, trips.first { it.id == saved.tripIds[1] }.ways.size)
        val links = deps.places.tripPlaces.first { l -> saved.tripIds.all { it in l } }
        assertEquals(FakeSetup.HOME, links.getValue(saved.tripIds[0]).fromPlaceId)
        assertEquals(FakeSetup.WORK, links.getValue(saved.tripIds[1]).fromPlaceId)
        // Asked again, every way is already there.
        val again = JourneyOptionsStateHolder(
            deps.planner, deps.core, deps.clock, PlanRequest(home.toPlanEndpoint(), work.toPlanEndpoint()), backgroundScope,
            from = home, to = work, places = deps.places,
        )
        again.state.first { it.phase == OptionsPhase.FOUND }
        again.startSaveWays()
        val second = assertIs<SaveWaysState.Choosing>(again.state.first { it.saveWays is SaveWaysState.Choosing }.saveWays)
        assertEquals("Home to Work", second.joinTitle)
        assertTrue(second.selected.isEmpty())
        assertTrue(second.ways.filter { it.trip != null }.all { it.alreadySaved })
    }
}
