package app.transittracker.ui

import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.fake.FakeSetup
import app.transittracker.ui.feature.places.PlacesStateHolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The saved places list's state holder against the fakes. */
class PlacesStateHolderTest {
    @Test
    fun everyPlaceShowsItsSwitchedOnStopsWithTheirLines() = runTest {
        val deps = AppDeps.fake()
        val s = PlacesStateHolder(deps.places, deps.core, backgroundScope).state.first { !it.loading }
        assertEquals(listOf("Home", "Work", "Gym"), s.cards.map { it.name })
        val home = s.cards.first()
        assertEquals("Albert Rd, Strathfield", home.address)
        assertEquals(listOf("Strathfield", "Homebush"), home.stops.map { it.name })
        assertEquals(listOf(3, 14), home.stops.map { it.walkMinutes })
        assertTrue(home.stops.first().lines.isNotEmpty())
        assertEquals(emptyList(), home.stops.last().lines) // the fake timetable has no Homebush
    }

    @Test
    fun aStopSwitchedOffIsNotListed() = runTest {
        val deps = AppDeps.fake()
        val home = deps.places.currentPlaces().first { it.id == FakeSetup.HOME }
        deps.places.savePlace(home.copy(stops = home.stops.map { if (it.name == "Homebush") it.copy(enabled = false) else it }))
        val s = PlacesStateHolder(deps.places, deps.core, backgroundScope).state.first { !it.loading }
        assertEquals(listOf("Strathfield"), s.cards.first().stops.map { it.name })
    }

    @Test
    fun eachPlaceSaysHowManyRoutesUseIt() = runTest {
        val deps = AppDeps.fake()
        val s = PlacesStateHolder(deps.places, deps.core, backgroundScope).state.first { !it.loading }
        assertEquals(listOf(1, 1, 0), s.cards.map { it.usedBy })
        assertEquals(listOf("USED BY 1 ROUTE", "USED BY 1 ROUTE", "NOT USED YET"), s.cards.map { it.usedByWords })
    }

    @Test
    fun withNoPlacesTheListIsEmptyAndSaysSo() = runTest {
        val deps = AppDeps.fake(withPlaces = false)
        val s = PlacesStateHolder(deps.places, deps.core, backgroundScope).state.first { !it.loading }
        assertTrue(s.isEmpty)
        assertFalse(s.canReorder)
    }

    @Test
    fun placesMoveUpAndDownAndTheOrderIsStored() = runTest {
        val deps = AppDeps.fake()
        val h = PlacesStateHolder(deps.places, deps.core, backgroundScope)
        h.state.first { !it.loading }
        h.move(FakeSetup.GYM, -1)
        runCurrent()
        assertEquals(listOf(FakeSetup.HOME, FakeSetup.GYM, FakeSetup.WORK), deps.places.currentPlaces().map { it.id })
        assertEquals(listOf("Home", "Gym", "Work"), h.state.value.cards.map { it.name })
        h.move(FakeSetup.HOME, 1)
        runCurrent()
        assertEquals(listOf(FakeSetup.GYM, FakeSetup.HOME, FakeSetup.WORK), deps.places.currentPlaces().map { it.id })
        h.move(FakeSetup.GYM, -1) // already first: nothing changes
        runCurrent()
        assertEquals(listOf(FakeSetup.GYM, FakeSetup.HOME, FakeSetup.WORK), deps.places.currentPlaces().map { it.id })
    }

    @Test
    fun reorderingCanBeSwitchedOnAndOff() = runTest {
        val deps = AppDeps.fake()
        val h = PlacesStateHolder(deps.places, deps.core, backgroundScope)
        h.state.first { !it.loading }
        assertTrue(h.state.value.canReorder)
        assertFalse(h.state.value.reordering)
        h.toggleReordering()
        runCurrent()
        assertTrue(h.state.value.reordering)
        h.toggleReordering()
        runCurrent()
        assertFalse(h.state.value.reordering)
    }
}
