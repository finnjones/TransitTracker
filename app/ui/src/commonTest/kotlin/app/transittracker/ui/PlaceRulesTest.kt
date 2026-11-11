package app.transittracker.ui

import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.Place
import app.transittracker.model.PlaceKind
import app.transittracker.model.Stop
import app.transittracker.ui.feature.places.LOCAL_STOP_LIMIT
import app.transittracker.ui.feature.places.STATION_LIMIT
import app.transittracker.ui.feature.places.addressText
import app.transittracker.ui.feature.places.distanceMeters
import app.transittracker.ui.feature.places.estimateWalkMinutes
import app.transittracker.ui.feature.places.formatDistance
import app.transittracker.ui.feature.places.formatWalk
import app.transittracker.ui.feature.places.keepChoices
import app.transittracker.ui.feature.places.modesLabel
import app.transittracker.ui.feature.places.movedIds
import app.transittracker.ui.feature.places.placeSaveProblem
import app.transittracker.ui.feature.places.rankPlaces
import app.transittracker.ui.feature.places.routesUsingPlace
import app.transittracker.ui.feature.places.spokenWalk
import app.transittracker.ui.feature.places.steppedWalk
import app.transittracker.ui.feature.places.usedByWords
import app.transittracker.ui.feature.places.walkingStops
import app.transittracker.ui.places.PlaceStop
import app.transittracker.ui.places.TripPlaces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure rules of saved places (plan 5.7): walking distance, walk time, search order, save conditions. */
class PlaceRulesTest {
    private val origin = LatLng(-33.8700, 151.0900)

    /** A position [meters] due north of [origin] (one degree of latitude is about 111.2 km). */
    private fun north(meters: Double) = LatLng(origin.lat + meters / 111_195.0, origin.lon)

    private fun stop(id: String, meters: Double, modes: Set<Mode>, parent: String? = null, name: String = "Stop $id") =
        Stop(id, name, parent, north(meters), if (parent != null) "1" else null, modes)

    private val rail = setOf(Mode.TRAIN)

    @Test
    fun distanceIsStraightLineInMetres() {
        assertEquals(1000.0, distanceMeters(origin, north(1000.0)), 2.0)
        assertEquals(0.0, distanceMeters(origin, origin), 0.001)
        // Sydney Central to Town Hall is about 1.2 km.
        assertEquals(1220.0, distanceMeters(LatLng(-33.8840, 151.2062), LatLng(-33.8732, 151.2061)), 30.0)
    }

    @Test
    fun walkMinutesAreEightyMetresAMinuteRoundedToNearest() {
        // The Add place mockup's figures.
        assertEquals(3, estimateWalkMinutes(250))
        assertEquals(4, estimateWalkMinutes(350))
        assertEquals(5, estimateWalkMinutes(400))
        assertEquals(14, estimateWalkMinutes(1100))
        assertEquals(19, estimateWalkMinutes(1500))
        // Rounding is to the nearest minute: 100 m is 1.25 minutes, 120 m is 1.5.
        assertEquals(1, estimateWalkMinutes(100))
        assertEquals(2, estimateWalkMinutes(120))
        assertEquals(3, estimateWalkMinutes(200)) // 2.5 rounds up
    }

    @Test
    fun aWalkIsNeverUnderOneMinuteOrOverAnHour() {
        assertEquals(1, estimateWalkMinutes(0))
        assertEquals(1, estimateWalkMinutes(20))
        assertEquals(60, estimateWalkMinutes(50_000))
    }

    @Test
    fun distancesAndWalksAreWrittenForTheScreen() {
        assertEquals("250 M", formatDistance(250))
        assertEquals("999 M", formatDistance(999))
        assertEquals("1.0 KM", formatDistance(1000))
        assertEquals("1.1 KM", formatDistance(1100))
        assertEquals("1.5 KM", formatDistance(1500))
        assertEquals("3 MIN", formatWalk(3))
        assertEquals("about 3 minutes' walk", spokenWalk(3, estimated = true))
        assertEquals("1 minute's walk", spokenWalk(1, estimated = false))
    }

    @Test
    fun stationsWithin1500MetresNearestThreeAreOffered() {
        val candidates = listOf(
            stop("far", 1600.0, rail),
            stop("d", 1400.0, rail),
            stop("a", 200.0, rail),
            stop("c", 900.0, rail),
            stop("b", 500.0, rail),
        )
        val found = walkingStops(origin, candidates)
        assertEquals(listOf("a", "b", "c"), found.map { it.stopId })
        assertEquals(STATION_LIMIT, found.size)
    }

    @Test
    fun theLimitsAreInclusive() {
        val found = walkingStops(origin, listOf(stop("edge", 1499.0, rail), stop("bus", 499.0, setOf(Mode.BUS))))
        assertEquals(setOf("edge", "bus"), found.map { it.stopId }.toSet())
    }

    @Test
    fun busAndCoachStopsCountWithin500MetresLightRailAndFerryWithin1Km() {
        val candidates = listOf(
            stop("bus-near", 300.0, setOf(Mode.BUS)),
            stop("bus-far", 700.0, setOf(Mode.BUS)),
            stop("lr", 450.0, setOf(Mode.LIGHT_RAIL)),
            stop("ferry", 520.0, setOf(Mode.FERRY)),
            stop("ferry-far", 1100.0, setOf(Mode.FERRY)),
            stop("coach", 100.0, setOf(Mode.COACH)),
            stop("noinfo", 100.0, emptySet()),
        )
        val ids = walkingStops(origin, candidates).map { it.stopId }
        // Nearest first; buses past 500 m and ferries past 1 km are out.
        assertEquals(setOf("coach", "noinfo"), ids.take(2).toSet())
        assertEquals(listOf("bus-near", "lr", "ferry"), ids.drop(2))
        assertTrue("bus-far" !in ids)
        assertTrue("ferry-far" !in ids)
    }

    @Test
    fun onlyTheNearestTwoLightRailOrFerryStopsAreOfferedAndAStopTheyShareWithBusesCountsAsLightRail() {
        val candidates = listOf(
            stop("lr1", 529.0, setOf(Mode.LIGHT_RAIL)),
            stop("lr-bus", 600.0, setOf(Mode.LIGHT_RAIL, Mode.BUS)),
            stop("lr3", 979.0, setOf(Mode.LIGHT_RAIL)),
            stop("bus", 259.0, setOf(Mode.BUS)),
        )
        val found = walkingStops(origin, candidates)
        assertEquals(listOf("bus", "lr1", "lr-bus"), found.map { it.stopId })
        assertEquals(listOf(Mode.LIGHT_RAIL, Mode.BUS), found.single { it.stopId == "lr-bus" }.modes)
    }

    @Test
    fun aStationFromTheTimetableAndThePlannerIsOfferedOnceWithEveryMode() {
        val platform = stop("p1", 120.0, rail, parent = "station")
        val fromTimetable = stop("station", 100.0, rail)
        val fromPlanner = stop("station", 100.0, setOf(Mode.TRAIN, Mode.BUS))
        val found = walkingStops(origin, listOf(platform, fromTimetable, fromPlanner))
        assertEquals(listOf("station"), found.map { it.stopId })
        assertEquals(listOf(Mode.TRAIN, Mode.BUS), found.single().modes)
    }

    @Test
    fun stopsWithoutLinesSayWhatCallsThere() {
        assertEquals("BUS", modesLabel(listOf(Mode.BUS)))
        assertEquals("LIGHT RAIL · BUS", modesLabel(listOf(Mode.BUS, Mode.LIGHT_RAIL, Mode.BUS)))
        assertNull(modesLabel(emptyList()))
        assertNull(modesLabel(listOf(Mode.OTHER)))
    }

    @Test
    fun theThreeStationLimitDoesNotCrowdOutLocalStopsAndTheListIsNearestFirst() {
        val candidates = listOf(
            stop("r1", 100.0, rail), stop("r2", 300.0, rail), stop("r3", 600.0, rail), stop("r4", 700.0, rail),
            stop("bus", 200.0, setOf(Mode.BUS)),
        )
        val found = walkingStops(origin, candidates)
        assertEquals(listOf("r1", "bus", "r2", "r3"), found.map { it.stopId })
        assertEquals(found.map { it.distanceMeters }.sorted(), found.map { it.distanceMeters })
    }

    @Test
    fun onlyTheNearestFewLocalStopsAreOffered() {
        val buses = (1..9).map { stop("bus$it", it * 50.0, setOf(Mode.BUS)) }
        val found = walkingStops(origin, buses)
        assertEquals(LOCAL_STOP_LIMIT, found.size)
        assertEquals("bus1", found.first().stopId)
    }

    @Test
    fun platformsFoldIntoTheirStationAndTheStationKeepsItsOwnName() {
        val station = stop("200060", 400.0, rail, name = "Central Station")
        val platforms = (1..3).map { stop("200060-P$it", 400.0 + it, rail, parent = "200060", name = "Central Station Platform $it") }
        val found = walkingStops(origin, platforms + station)
        assertEquals(1, found.size)
        assertEquals("200060", found[0].stopId)
        assertEquals("Central", found[0].name)
        // Only platforms returned: the station is named from the platform.
        val alone = walkingStops(origin, platforms)
        assertEquals("200060", alone.single().stopId)
        assertEquals("Central", alone.single().name)
    }

    @Test
    fun aStationWithTrainsAndABusIsStillARailStation() {
        // 1 km away: only rail stations reach that far.
        val found = walkingStops(origin, listOf(stop("mixed", 1000.0, setOf(Mode.BUS, Mode.TRAIN))))
        assertEquals(listOf("mixed"), found.map { it.stopId })
    }

    @Test
    fun foundStopsCarryAnEstimatedWalkFromStraightLineDistance() {
        val s = walkingStops(origin, listOf(stop("a", 1100.0, rail))).single()
        assertEquals(true, s.estimated)
        assertEquals(true, s.enabled)
        assertEquals(estimateWalkMinutes(s.distanceMeters), s.walkMinutes)
        assertTrue(kotlin.math.abs(s.distanceMeters - 1100) <= 2)
        assertEquals(14, s.walkMinutes)
    }

    @Test
    fun nothingNearbyGivesNoStops() {
        assertTrue(walkingStops(origin, emptyList()).isEmpty())
        assertTrue(walkingStops(origin, listOf(stop("far", 5000.0, rail))).isEmpty())
    }

    @Test
    fun choicesSurviveAMovedAddress() {
        val before = listOf(
            PlaceStop("a", "A", 5, 400, enabled = false, estimated = true),
            PlaceStop("b", "B", 9, 700, enabled = true, estimated = false), // corrected by hand
            PlaceStop("c", "C", 3, 200, enabled = true, estimated = true),
        )
        val found = listOf(
            PlaceStop("a", "A", 6, 480, estimated = true),
            PlaceStop("b", "B", 10, 800, estimated = true),
            PlaceStop("d", "D", 4, 300, estimated = true),
        )
        val kept = keepChoices(found, before)
        assertEquals(false, kept[0].enabled) // switched off stays off
        assertEquals(6, kept[0].walkMinutes) // an estimate is recomputed
        assertEquals(9, kept[1].walkMinutes) // a correction stays
        assertEquals(false, kept[1].estimated)
        assertEquals(found[2], kept[2]) // a new stop starts fresh
    }

    @Test
    fun theWalkStepperStopsAtItsEnds() {
        assertEquals(4, steppedWalk(3, 1))
        assertEquals(2, steppedWalk(3, -1))
        assertNull(steppedWalk(1, -1))
        assertNull(steppedWalk(60, 1))
        assertNotNull(steppedWalk(60, -1))
    }

    @Test
    fun addressesComeFirstInSearchAnswers() {
        fun p(id: String, kind: PlaceKind) = Place(id, kind, id, "", origin)
        val ranked = rankPlaces(
            listOf(p("stop", PlaceKind.STOP), p("suburb", PlaceKind.LOCALITY), p("addr1", PlaceKind.ADDRESS), p("poi", PlaceKind.POI), p("addr2", PlaceKind.ADDRESS), p("other", PlaceKind.OTHER)),
        )
        assertEquals(listOf("addr1", "addr2", "poi", "suburb", "stop", "other"), ranked.map { it.id })
    }

    @Test
    fun anAnswerBecomesTheAddressText() {
        assertEquals("Albert Rd, Strathfield", addressText(Place("x", PlaceKind.ADDRESS, "Albert Rd", "Strathfield", origin)))
        assertEquals("Bondi Beach", addressText(Place("x", PlaceKind.LOCALITY, "Bondi Beach", "", origin)))
    }

    @Test
    fun aPlaceNeedsANameAPositionAndAStopSwitchedOn() {
        assertEquals("Give the place a name.", placeSaveProblem("", hasPosition = true, hasStops = true, enabledStops = 2))
        assertEquals("Give the place a name.", placeSaveProblem("   ", hasPosition = false, hasStops = false, enabledStops = 0))
        assertEquals(
            "Pick an address from the list, or use where you are now.",
            placeSaveProblem("Home", hasPosition = false, hasStops = false, enabledStops = 0),
        )
        assertEquals(
            "No stations or stops are within walking distance of here. Try another address.",
            placeSaveProblem("Home", hasPosition = true, hasStops = false, enabledStops = 0),
        )
        assertEquals(
            "Switch on at least one stop you would walk to.",
            placeSaveProblem("Home", hasPosition = true, hasStops = true, enabledStops = 0),
        )
        assertNull(placeSaveProblem("Home", hasPosition = true, hasStops = true, enabledStops = 1))
    }

    @Test
    fun routesUsingAPlaceAreCountedAtEitherEnd() {
        val links = listOf(
            TripPlaces("t1", fromPlaceId = "home", toPlaceId = "work"),
            TripPlaces("t2", fromPlaceId = "work", toPlaceId = "home"),
            TripPlaces("t3", fromPlaceId = "gym"),
        )
        assertEquals(2, routesUsingPlace("home", links))
        assertEquals(1, routesUsingPlace("gym", links))
        assertEquals(0, routesUsingPlace("nowhere", links))
        assertEquals("USED BY 2 ROUTES", usedByWords(2))
        assertEquals("USED BY 1 ROUTE", usedByWords(1))
        assertEquals("NOT USED YET", usedByWords(0))
    }

    @Test
    fun placesMoveOneStepAndStopAtTheEnds() {
        val ids = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), movedIds(ids, "a", 1))
        assertEquals(listOf("a", "c", "b"), movedIds(ids, "c", -1))
        assertEquals(ids, movedIds(ids, "a", -1))
        assertEquals(ids, movedIds(ids, "c", 1))
        assertEquals(ids, movedIds(ids, "missing", 1))
    }
}
