package app.transittracker.ui

import app.transittracker.model.Mode
import app.transittracker.model.Route
import app.transittracker.model.SavedTrip
import app.transittracker.model.TripLeg
import app.transittracker.ui.feature.places.RouteEntry
import app.transittracker.ui.feature.places.canMove
import app.transittracker.ui.feature.places.cleanFolder
import app.transittracker.ui.feature.places.entriesOf
import app.transittracker.ui.feature.places.folderChanges
import app.transittracker.ui.feature.places.folderNames
import app.transittracker.ui.feature.places.groupEntries
import app.transittracker.ui.feature.places.leaveByWarning
import app.transittracker.ui.feature.places.legLines
import app.transittracker.ui.feature.places.lineBadges
import app.transittracker.ui.feature.places.moveRoute
import app.transittracker.ui.feature.places.moveWords
import app.transittracker.ui.feature.places.renameFolder
import app.transittracker.ui.feature.places.routeSaveProblem
import app.transittracker.ui.feature.places.routeStationsLine
import app.transittracker.ui.feature.places.routeTitleParts
import app.transittracker.ui.places.PlaceIcon
import app.transittracker.ui.places.PlaceStop
import app.transittracker.ui.places.SavedPlace
import app.transittracker.model.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reordering across folders, folder rename, titles and line lists of saved routes (plan 5.2, 5.6). */
class RouteRulesTest {
    private fun e(id: String, folder: String?) = RouteEntry(id, folder)

    // To work: a, b   Home: c, d   (no folder): z
    private val list = listOf(e("a", "To work"), e("b", "To work"), e("c", "Home"), e("d", "Home"), e("z", null))

    @Test
    fun aRouteSwapsWithItsNeighbourInsideAFolder() {
        assertEquals(listOf(e("b", "To work"), e("a", "To work")), moveRoute(list, "a", 1).take(2))
        assertEquals(listOf(e("b", "To work"), e("a", "To work")), moveRoute(list, "b", -1).take(2))
        val down = moveRoute(list, "c", 1)
        assertEquals(listOf("a", "b", "d", "c", "z"), down.map { it.id })
    }

    @Test
    fun atTheEndOfAFolderARouteCrossesIntoTheNextAndTakesItsName() {
        // b is last in To work: down puts it first in Home.
        val down = moveRoute(list, "b", 1)
        assertEquals(listOf("a", "b", "c", "d", "z"), down.map { it.id })
        assertEquals(e("b", "Home"), down[1])
        assertEquals(listOf("To work", "Home", "Home", "Home", null), down.map { it.folder })
        // c is first in Home: up puts it last in To work.
        val up = moveRoute(list, "c", -1)
        assertEquals(listOf("a", "b", "c", "d", "z"), up.map { it.id })
        assertEquals("To work", up.first { it.id == "c" }.folder)
    }

    @Test
    fun aRouteCanLeaveAFolderForTheUnfiledOnesAndBack() {
        val out = moveRoute(list, "d", 1)
        assertEquals(e("d", null), out.first { it.id == "d" })
        assertEquals(listOf("a", "b", "c", "d", "z"), out.map { it.id })
        assertNull(out.first { it.id == "d" }.folder)
        val back = moveRoute(out, "d", -1)
        assertEquals("Home", back.first { it.id == "d" }.folder)
    }

    @Test
    fun movingTheOnlyRouteOfAFolderRemovesThatFolder() {
        val trips = listOf(e("a", "One"), e("b", "Two"))
        val moved = moveRoute(trips, "a", 1)
        assertEquals(listOf("Two"), groupEntries(moved).map { it.first })
        assertEquals(listOf("a", "b").sorted(), moved.map { it.id }.sorted())
    }

    @Test
    fun theFirstAndLastRoutesCannotMovePastTheEnds() {
        assertEquals(list, moveRoute(list, "a", -1))
        assertEquals(list, moveRoute(list, "z", 1))
        assertEquals(list, moveRoute(list, "missing", 1))
        assertEquals(false, canMove(list, "a", -1))
        assertEquals(false, canMove(list, "z", 1))
        assertEquals(true, canMove(list, "a", 1))
        assertEquals(true, canMove(list, "z", -1))
    }

    @Test
    fun foldersAreGroupedInTheOrderTheirFirstRouteAppears() {
        val scattered = listOf(e("a", "X"), e("b", "Y"), e("c", "X"))
        assertEquals(listOf("X", "Y"), groupEntries(scattered).map { it.first })
        assertEquals(listOf("a", "c"), groupEntries(scattered)[0].second.map { it.id })
        // Moving anything normalises the list into contiguous folders.
        assertEquals(listOf("c", "a", "b"), moveRoute(scattered, "a", 1).map { it.id })
    }

    @Test
    fun theMoveButtonsSayWhatTheyDoAtAFolderEdge() {
        assertEquals("Move up", moveWords(list, "b", -1))
        assertEquals("Move down into Home", moveWords(list, "b", 1))
        assertEquals("Move up into To work", moveWords(list, "c", -1))
        assertEquals("Move down out of its folder", moveWords(list, "d", 1))
    }

    @Test
    fun renamingAFolderChangesEveryRouteInIt() {
        val renamed = renameFolder(list, "To work", "  Commute ")
        assertEquals(listOf("Commute", "Commute", "Home", "Home", null), renamed.map { it.folder })
        assertEquals(mapOf("a" to "Commute", "b" to "Commute"), folderChanges(list, renamed))
    }

    @Test
    fun aBlankFolderNameChangesNothingAndTheSameNameMerges() {
        assertEquals(list, renameFolder(list, "To work", "   "))
        val merged = renameFolder(list, "To work", "Home")
        assertEquals(listOf("Home"), groupEntries(merged).mapNotNull { it.first })
        assertEquals(listOf("a", "b", "c", "d", "z"), merged.map { it.id })
    }

    @Test
    fun foldersAreReadFromTripsInSavedOrder() {
        fun trip(id: String, folder: String?, order: Int) =
            SavedTrip(id, "A", "B", listOf(TripLeg(setOf("x"), setOf("y"))), folder = folder, order = order)
        val trips = listOf(trip("c", "Home", 2), trip("a", "To work", 0), trip("b", " ", 1))
        assertEquals(listOf("a", "b", "c"), entriesOf(trips).map { it.id })
        assertNull(entriesOf(trips)[1].folder) // blank means none
        assertEquals(listOf("To work", "Home"), folderNames(trips))
        assertNull(cleanFolder("  "))
        assertEquals("Home", cleanFolder(" Home "))
    }

    private val home = SavedPlace("home", "Home", PlaceIcon.HOME, "Albert Rd", LatLng(0.0, 0.0), listOf(PlaceStop("S1", "Strathfield", 3, 250)))
    private val work = SavedPlace("work", "Work", PlaceIcon.WORK, "Pitt St", LatLng(0.0, 0.0))
    private val trip = SavedTrip("t", "Strathfield", "Central", listOf(TripLeg(setOf("S1"), setOf("C1"))), customName = "Commute")

    @Test
    fun aRouteBetweenPlacesIsTitledWithTheirNamesLikeHome() {
        assertEquals(listOf("Home", "Work"), routeTitleParts(trip, home, work))
        assertEquals(listOf("Home", "Central"), routeTitleParts(trip, home, null))
        assertEquals(listOf("Commute"), routeTitleParts(trip, null, null))
        assertEquals(listOf("Strathfield", "Central"), routeTitleParts(trip.copy(customName = null), null, null))
        assertEquals("STRATHFIELD TO CENTRAL", routeStationsLine(trip, home, work))
        assertEquals("STRATHFIELD TO CENTRAL", routeStationsLine(trip, null, null)) // a custom name hides the stations
        assertNull(routeStationsLine(trip.copy(customName = null), null, null))
    }

    @Test
    fun aRouteCannotStartAndEndAtTheSamePlace() {
        assertNotNull(routeSaveProblem("home", "home"))
        assertNull(routeSaveProblem("home", "work"))
        assertNull(routeSaveProblem(null, null))
        assertNull(routeSaveProblem("home", null))
    }

    @Test
    fun aLeaveByWarningAppearsWhenThePlaceHasNoStopAtTheRouteStart() {
        val leg = trip.legs.first()
        assertNull(leaveByWarning(home, leg, "Strathfield"))
        assertNull(leaveByWarning(null, leg, "Strathfield"))
        val off = home.copy(stops = listOf(PlaceStop("S1", "Strathfield", 3, 250, enabled = false)))
        assertTrue(leaveByWarning(off, leg, "Strathfield")!!.contains("Strathfield"))
        assertTrue(leaveByWarning(home, TripLeg(setOf("other"), setOf("C1")), "Central")!!.startsWith("Home has no stop at Central"))
    }

    private fun route(id: String, name: String, color: String? = null) = Route(id, name, name, Mode.TRAIN, color, null)

    @Test
    fun theLinesOfALegAreThoseServingBothEndsOrTheOnesItIsLimitedTo() {
        val t1 = route("T1", "T1")
        val t9 = route("T9", "T9")
        val m1 = route("M1", "M1")
        val any = TripLeg(setOf("a"), setOf("b"))
        assertEquals(listOf("T1", "T9"), legLines(any, listOf(t1, t9, m1), listOf(t1, t9)).map { it.id })
        val limited = TripLeg(setOf("a"), setOf("b"), routeIds = setOf("T9"))
        assertEquals(listOf("T9"), legLines(limited, listOf(t1, t9), listOf(t1, t9)).map { it.id })
        assertEquals(emptyList(), legLines(any, listOf(m1), listOf(t1)))
    }

    @Test
    fun lineBadgesAreDistinctAndInNameOrder() {
        val badges = lineBadges(listOf(route("T9", "T9", "D11F2F"), route("T1", "T1", "F99D1C"), route("T9b", "T9", "D11F2F")))
        assertEquals(listOf("T1", "T9"), badges.map { it.name })
        assertEquals("F99D1C", badges[0].colorHex)
    }
}
