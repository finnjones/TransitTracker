package app.transittracker.ui

import app.transittracker.core.FakeData
import app.transittracker.model.SavedTrip
import app.transittracker.model.TransitCore
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.feature.places.EditRouteStateHolder
import app.transittracker.ui.feature.places.ManageRoutesStateHolder
import app.transittracker.ui.places.PlacesStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Manage routes and Edit route state holders against the fakes. */
class RoutesStateHolderTest {
    private fun manage(deps: AppDeps, scope: kotlinx.coroutines.CoroutineScope, core: TransitCore = deps.core) =
        ManageRoutesStateHolder(core, deps.places, scope)

    private fun edit(deps: AppDeps, scope: kotlinx.coroutines.CoroutineScope, id: String, core: TransitCore = deps.core, places: PlacesStore = deps.places) =
        EditRouteStateHolder(core, places, id, scope)

    // ---------------------------------------------------------------- manage

    @Test
    fun routesAreGroupedByFolderWithTheirEndsAndLines() = runTest {
        val deps = AppDeps.fake()
        val s = manage(deps, backgroundScope).state.first { !it.loading }
        assertEquals(listOf("To work", "Home"), s.folders.map { it.name })
        assertEquals(4, s.count)
        val homeWork = s.folders[0].rows[0]
        assertEquals(FakeSetup.TRIP_HOME_WORK, homeWork.id)
        assertEquals(listOf("Home", "Work"), homeWork.titleParts) // linked: the place names
        assertEquals("STRATHFIELD TO CENTRAL", homeWork.stationsLine)
        assertEquals(listOf("T1", "T9"), homeWork.lines.map { it.name })
        val chatswood = s.folders[0].rows[1]
        assertEquals(listOf("Chatswood", "Tallawong"), chatswood.titleParts)
        assertNull(chatswood.stationsLine)
        assertEquals(listOf("M1"), chatswood.lines.map { it.name })
        val multi = s.folders[1].rows.last()
        assertEquals(1, multi.changes)
        assertEquals(setOf("T1", "T9", "M1"), multi.lines.map { it.name }.toSet())
    }

    @Test
    fun theEndsOfTheListCannotMoveFurther() = runTest {
        val deps = AppDeps.fake()
        val s = manage(deps, backgroundScope).state.first { !it.loading }
        val first = s.folders.first().rows.first()
        val last = s.folders.last().rows.last()
        assertFalse(first.canMoveUp)
        assertTrue(first.canMoveDown)
        assertTrue(last.canMoveUp)
        assertFalse(last.canMoveDown)
        // At a folder's edge the words say where it goes.
        assertEquals("Move down into Home", s.folders[0].rows.last().downWords)
    }

    @Test
    fun movingWithinAFolderReordersTheCoreTrips() = runTest {
        val deps = AppDeps.fake()
        val h = manage(deps, backgroundScope)
        h.state.first { !it.loading }
        h.move(FakeSetup.TRIP_HOME_WORK, 1)
        runCurrent()
        val trips = deps.core.trips.first()
        assertEquals(
            listOf(FakeSetup.TRIP_CHATSWOOD_TALLAWONG, FakeSetup.TRIP_HOME_WORK, FakeSetup.TRIP_CENTRAL_STRATHFIELD, FakeSetup.TRIP_TOWNHALL_TALLAWONG),
            trips.sortedBy { it.order }.map { it.id },
        )
        assertEquals(listOf(FakeSetup.TRIP_CHATSWOOD_TALLAWONG, FakeSetup.TRIP_HOME_WORK), h.state.value.folders[0].rows.map { it.id })
    }

    @Test
    fun movingAcrossAFolderEdgeChangesTheRoutesFolder() = runTest {
        val deps = AppDeps.fake()
        val h = manage(deps, backgroundScope)
        h.state.first { !it.loading }
        // The last route of To work moves down to the front of Home.
        h.move(FakeSetup.TRIP_CHATSWOOD_TALLAWONG, 1)
        runCurrent()
        val trips = deps.core.trips.first().associateBy { it.id }
        assertEquals("Home", trips.getValue(FakeSetup.TRIP_CHATSWOOD_TALLAWONG).folder)
        assertEquals("To work", trips.getValue(FakeSetup.TRIP_HOME_WORK).folder)
        val folders = h.state.value.folders
        assertEquals(listOf("To work", "Home"), folders.map { it.name })
        assertEquals(listOf(FakeSetup.TRIP_HOME_WORK), folders[0].rows.map { it.id })
        assertEquals(FakeSetup.TRIP_CHATSWOOD_TALLAWONG, folders[1].rows.first().id)
        // The first route of Home moves back up to the end of To work.
        h.move(FakeSetup.TRIP_CHATSWOOD_TALLAWONG, -1)
        runCurrent()
        assertEquals("To work", deps.core.trips.first().first { it.id == FakeSetup.TRIP_CHATSWOOD_TALLAWONG }.folder)
    }

    @Test
    fun renamingAFolderRenamesItInEveryRoute() = runTest {
        val deps = AppDeps.fake()
        val h = manage(deps, backgroundScope)
        h.state.first { !it.loading }
        h.startRename("To work")
        runCurrent()
        assertEquals("To work", h.state.value.renaming?.text)
        h.setRenameText("Commute")
        runCurrent()
        assertTrue(h.state.value.renaming!!.canSave)
        h.confirmRename()
        runCurrent()
        assertNull(h.state.value.renaming)
        val trips = deps.core.trips.first().associateBy { it.id }
        assertEquals("Commute", trips.getValue(FakeSetup.TRIP_HOME_WORK).folder)
        assertEquals("Commute", trips.getValue(FakeSetup.TRIP_CHATSWOOD_TALLAWONG).folder)
        assertEquals("Home", trips.getValue(FakeSetup.TRIP_CENTRAL_STRATHFIELD).folder)
        assertEquals(listOf("Commute", "Home"), h.state.value.folders.map { it.name })
    }

    @Test
    fun aBlankFolderNameCannotBeSavedAndCancelLeavesEverything() = runTest {
        val deps = AppDeps.fake()
        val h = manage(deps, backgroundScope)
        h.state.first { !it.loading }
        h.startRename("Home")
        h.setRenameText("   ")
        runCurrent()
        assertFalse(h.state.value.renaming!!.canSave)
        h.confirmRename() // ignored
        runCurrent()
        assertNotNull(h.state.value.renaming)
        h.cancelRename()
        runCurrent()
        assertNull(h.state.value.renaming)
        assertEquals("Home", deps.core.trips.first().first { it.id == FakeSetup.TRIP_CENTRAL_STRATHFIELD }.folder)
    }

    @Test
    fun aFailedChangeShowsAPlainSentenceAndRetryTriesAgain() = runTest {
        val deps = AppDeps.fake()
        var fail = true
        val core = object : TransitCore by deps.core {
            override suspend fun reorderTrips(ids: List<String>) {
                if (fail) error("disk")
                deps.core.reorderTrips(ids)
            }
        }
        val h = manage(deps, backgroundScope, core)
        h.state.first { !it.loading }
        h.move(FakeSetup.TRIP_HOME_WORK, 1)
        runCurrent()
        val problem = assertNotNull(h.state.value.problem)
        assertEquals("Could not move the route", problem.title)
        assertTrue(problem.words.startsWith("The change was not saved."))
        fail = false
        h.retry()
        runCurrent()
        assertNull(h.state.value.problem)
        assertEquals(FakeSetup.TRIP_CHATSWOOD_TALLAWONG, deps.core.trips.first().minBy { it.order }.id)
    }

    @Test
    fun closingAProblemForgetsTheRetry() = runTest {
        val deps = AppDeps.fake()
        val core = object : TransitCore by deps.core {
            override suspend fun reorderTrips(ids: List<String>) = error("disk")
        }
        val h = manage(deps, backgroundScope, core)
        h.state.first { !it.loading }
        h.move(FakeSetup.TRIP_HOME_WORK, 1)
        runCurrent()
        h.dismissProblem()
        h.retry() // nothing to retry
        runCurrent()
        assertNull(h.state.value.problem)
    }

    @Test
    fun withNoRoutesTheListIsEmpty() = runTest {
        val deps = AppDeps.fake(trips = emptyList())
        val s = manage(deps, backgroundScope).state.first { !it.loading }
        assertTrue(s.isEmpty)
        assertEquals(0, s.count)
    }

    // ---------------------------------------------------------------- edit

    @Test
    fun aRouteOpensWithItsNameFolderPlacesAndLegs() = runTest {
        val deps = AppDeps.fake()
        val h = edit(deps, backgroundScope, FakeSetup.TRIP_TOWNHALL_TALLAWONG)
        h.state.first { !it.loading }
        runCurrent()
        val s = h.state.value
        assertEquals("Home", s.folder)
        assertEquals("Town Hall to Tallawong", s.defaultName)
        assertEquals("", s.name)
        assertNull(s.fromPlaceId)
        assertEquals(listOf("Home", "Work", "Gym"), s.places.map { it.name })
        assertEquals(2, s.legs.size)
        assertEquals("Town Hall", s.legs[0].board)
        assertEquals("Tallawong", s.legs[1].alight)
        assertNull(s.legs[0].changeMinutes)
        assertEquals(4, s.legs[1].changeMinutes)
        assertEquals(listOf("To work", "Home"), s.folders)
        assertTrue(s.legs[1].lines.any { it.name == "M1" })
        assertTrue(s.canSave)
    }

    @Test
    fun theChangeStationOfALegIsNamedFromTheTimetable() = runTest {
        val deps = AppDeps.fake()
        val h = edit(deps, backgroundScope, FakeSetup.TRIP_TOWNHALL_TALLAWONG)
        h.state.first { !it.loading }
        runCurrent()
        val legs = h.state.value.legs
        assertEquals("Chatswood", legs[0].alight)
        assertEquals("Chatswood", legs[1].board)
    }

    @Test
    fun aLinkedRouteShowsItsPlaces() = runTest {
        val deps = AppDeps.fake()
        val h = edit(deps, backgroundScope, FakeSetup.TRIP_HOME_WORK)
        val s = h.state.first { !it.loading }
        assertEquals(FakeSetup.HOME, s.fromPlaceId)
        assertEquals(FakeSetup.WORK, s.toPlaceId)
        assertEquals("Home to Work", s.title)
        assertNull(s.warning) // Home has Strathfield switched on
    }

    @Test
    fun savingKeepsTheNameFolderAndPlaceLinks() = runTest {
        val deps = AppDeps.fake()
        val h = edit(deps, backgroundScope, FakeSetup.TRIP_CENTRAL_STRATHFIELD)
        h.state.first { !it.loading }
        h.setName("  Back home ")
        h.setFolder("Evening")
        h.setFrom(FakeSetup.WORK)
        h.setTo(FakeSetup.HOME)
        h.save()
        runCurrent()
        assertTrue(h.state.value.finished)
        val trip = deps.core.trips.first().first { it.id == FakeSetup.TRIP_CENTRAL_STRATHFIELD }
        assertEquals("Back home", trip.customName)
        assertEquals("Evening", trip.folder)
        val link = deps.places.tripPlaces.first().getValue(FakeSetup.TRIP_CENTRAL_STRATHFIELD)
        assertEquals(FakeSetup.WORK, link.fromPlaceId)
        assertEquals(FakeSetup.HOME, link.toPlaceId)
        assertEquals(1, deps.core.trips.first().count { it.id == FakeSetup.TRIP_CENTRAL_STRATHFIELD })
    }

    @Test
    fun clearingTheNameFolderAndPlacesPutsThemBack() = runTest {
        val deps = AppDeps.fake()
        val h = edit(deps, backgroundScope, FakeSetup.TRIP_HOME_WORK)
        h.state.first { !it.loading }
        h.setFolder("  ")
        h.setFrom(null)
        h.setTo(null)
        h.save()
        runCurrent()
        val trip = deps.core.trips.first().first { it.id == FakeSetup.TRIP_HOME_WORK }
        assertNull(trip.customName)
        assertNull(trip.folder)
        assertNull(deps.places.tripPlaces.first()[FakeSetup.TRIP_HOME_WORK])
    }

    @Test
    fun aRouteCannotRunFromAPlaceToItself() = runTest {
        val deps = AppDeps.fake()
        val h = edit(deps, backgroundScope, FakeSetup.TRIP_HOME_WORK)
        h.state.first { !it.loading }
        h.setTo(FakeSetup.HOME)
        assertFalse(h.state.value.canSave)
        assertEquals("A route cannot start and end at the same place.", h.state.value.saveProblem)
        h.save()
        runCurrent()
        assertFalse(h.state.value.finished)
        assertEquals(FakeSetup.WORK, deps.places.tripPlaces.first().getValue(FakeSetup.TRIP_HOME_WORK).toPlaceId)
    }

    @Test
    fun aPlaceWithoutTheStartStationSwitchedOnGetsAWarning() = runTest {
        val deps = AppDeps.fake()
        val h = edit(deps, backgroundScope, FakeSetup.TRIP_CHATSWOOD_TALLAWONG)
        h.state.first { !it.loading }
        assertNull(h.state.value.warning)
        h.setFrom(FakeSetup.HOME) // Home's stops are Strathfield and Homebush, not Chatswood
        assertTrue(h.state.value.warning!!.startsWith("Home has no stop at Chatswood"))
        h.setFrom(FakeSetup.GYM) // Gym has Chatswood
        assertNull(h.state.value.warning)
        assertEquals(FakeData.CHATSWOOD, deps.places.currentPlaces().first { it.id == FakeSetup.GYM }.stops.single().stopId)
    }

    @Test
    fun aFailedSaveIsAPlainSentenceAndRetryTriesAgain() = runTest {
        val deps = AppDeps.fake()
        var fail = true
        val core = object : TransitCore by deps.core {
            override suspend fun saveTrip(trip: SavedTrip) {
                if (fail) error("disk")
                deps.core.saveTrip(trip)
            }
        }
        val h = edit(deps, backgroundScope, FakeSetup.TRIP_HOME_WORK, core)
        h.state.first { !it.loading }
        h.setName("Commute")
        h.save()
        runCurrent()
        val s = h.state.value
        assertEquals("Could not save the route", assertNotNull(s.problem).title)
        assertEquals("The route was not saved. Try again in a moment.", s.problem!!.words)
        assertFalse(s.saving)
        assertFalse(s.finished)
        assertEquals("Commute", s.name) // still what was typed
        fail = false
        h.retry()
        runCurrent()
        assertTrue(h.state.value.finished)
        assertEquals("Commute", deps.core.trips.first().first { it.id == FakeSetup.TRIP_HOME_WORK }.customName)
    }

    @Test
    fun aFailedLinkSaysTheRouteWasSavedButNotLinked() = runTest {
        val deps = AppDeps.fake()
        val places = object : PlacesStore by deps.places {
            override suspend fun linkTrip(tripId: String, fromPlaceId: String?, toPlaceId: String?) = error("disk")
        }
        val h = edit(deps, backgroundScope, FakeSetup.TRIP_HOME_WORK, places = places)
        h.state.first { !it.loading }
        h.save()
        runCurrent()
        assertEquals("Could not link the places", assertNotNull(h.state.value.problem).title)
        assertTrue(h.state.value.problem!!.words.contains("saved"))
    }

    @Test
    fun deleteAsksFirstThenRemovesTheRouteAndItsLink() = runTest {
        val deps = AppDeps.fake()
        val h = edit(deps, backgroundScope, FakeSetup.TRIP_HOME_WORK)
        h.state.first { !it.loading }
        h.askDelete()
        assertTrue(h.state.value.confirmDelete)
        assertEquals(4, deps.core.trips.first().size)
        h.cancelDelete()
        assertEquals(4, deps.core.trips.first().size)
        h.askDelete()
        h.confirmDelete()
        runCurrent()
        assertTrue(h.state.value.finished)
        assertEquals(3, deps.core.trips.first().size)
        assertTrue(deps.places.tripPlaces.first().isEmpty())
    }

    @Test
    fun aFailedDeleteCanBeRetried() = runTest {
        val deps = AppDeps.fake()
        var fail = true
        val core = object : TransitCore by deps.core {
            override suspend fun deleteTrip(id: String) {
                if (fail) error("disk")
                deps.core.deleteTrip(id)
            }
        }
        val h = edit(deps, backgroundScope, FakeSetup.TRIP_HOME_WORK, core)
        h.state.first { !it.loading }
        h.askDelete()
        h.confirmDelete()
        runCurrent()
        assertEquals("Could not delete the route", assertNotNull(h.state.value.problem).title)
        assertFalse(h.state.value.deleting)
        fail = false
        h.retry()
        runCurrent()
        assertTrue(h.state.value.finished)
    }

    @Test
    fun aRouteThatIsGoneSaysSo() = runTest {
        val h = edit(AppDeps.fake(), backgroundScope, "nowhere")
        val s = h.state.first { !it.loading }
        assertTrue(s.missing)
        assertFalse(s.canSave)
    }
}
