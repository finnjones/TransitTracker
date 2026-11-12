package app.transittracker.ui.feature.map

import app.transittracker.ui.theme.ColorRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the journey map draws for a train-then-bus journey ([JourneyMapSamples]). */
class JourneyMapTest {
    private val min = 60_000L
    private val t0 = 1_794_343_080_000L
    private fun state(atMin: Int = 0, riding: Boolean = true) =
        buildJourneyMapState(JourneyMapSamples.input(t0, t0 + atMin * min, riding), t0 + atMin * min)

    @Test
    fun eachRideCarriesItsRouteBadgeSoSameColouredLinesAreToldApart() {
        val s = state()
        assertEquals(mapOf("leg-0" to "T2", "leg-1" to "333"), s.scene.lines.filter { !it.walk }.associate { it.id to it.badge })
        assertNull(s.scene.lines.single { it.walk }.badge)
    }

    @Test
    fun onlyTheEndsAndTheChangeAreMarkedTheStopsBetweenAreNotches() {
        val st = state().scene.stations
        val major = st.filter { !it.minor }
        // Strathfield (left already), the change at Central, the bus stop at Museum (a walk
        // away, so it has its own flag) and Bondi Beach.
        assertEquals(listOf("Strathfield", "Central", "Museum Station", "Bondi Beach"), major.map { it.name })
        assertEquals(listOf(StationKind.AHEAD, StationKind.CHANGE, StationKind.BOARD, StationKind.ALIGHT), major.map { it.kind })
        assertTrue(major[2].flag!!.startsWith("BOARD 333 · "))
        // Ten stations between on the train and eighteen stops on the bus.
        assertEquals(10 + 18, st.count { it.minor })
        assertTrue(st.filter { it.minor }.all { it.priority > 1 && it.flag == null })
    }

    @Test
    fun theChangeIsOneFlagThatSaysHowLongAndWhatComesNext() {
        val change = state().scene.stations.single { it.kind == StationKind.CHANGE }
        assertEquals("CHANGE · 10 MIN", change.flag)
        assertTrue(change.detail!!.startsWith("THEN 333 · "))
        assertNull(change.flagRole)
        assertEquals(0, change.priority)
        val end = state().scene.stations.single { it.kind == StationKind.ALIGHT }
        assertTrue(end.flag!!.startsWith("GET OFF · "))
        assertNull(end.detail) // the time is in the flag
    }

    @Test
    fun aBusBoardedWhereTheTrainEndsIsOneChangeFlag() {
        // Move the bus's first stop next to Central's platform: no separate BOARD flag.
        val i = JourneyMapSamples.input(t0)
        val train = i.legs[0].geometry
        val bus = i.legs[1].geometry
        val near = bus.copy(stopPoints = listOf(train.stopPoints.last()) + bus.stopPoints.drop(1))
        val s = buildJourneyMapState(i.copy(legs = listOf(i.legs[0], i.legs[1].copy(geometry = near))), t0)
        assertEquals(listOf(StationKind.AHEAD, StationKind.CHANGE, StationKind.ALIGHT), s.scene.stations.filter { !it.minor }.map { it.kind })
    }

    @Test
    fun aTightChangeIsFlaggedInCaution() {
        // Allow twelve minutes to change: ten is tight.
        val input = JourneyMapSamples.input(t0).let { i ->
            i.copy(trip = i.trip!!.copy(legs = i.trip!!.legs.mapIndexed { k, l -> if (k == 1) l.copy(minConnectionMinutes = 12) else l }))
        }
        val change = buildJourneyMapState(input, t0).scene.stations.single { it.kind == StationKind.CHANGE }
        assertEquals("TIGHT CHANGE · 10 MIN", change.flag)
        assertEquals(ColorRole.CAUTION, change.flagRole)
    }

    @Test
    fun ridesFollowTheirLineShapesNotStraightLinesBetweenStops() {
        val shaped = state().scene.lines.single { it.id == "leg-1" }.segments.single().path
        val straight = buildJourneyMapState(JourneyMapSamples.input(t0, shapes = false), t0).scene.lines.single { it.id == "leg-1" }.segments.single().path
        // The 333 bends along Oxford St and Bondi Rd: many more points than its 20 stops, and longer.
        assertTrue(shaped.points.size > straight.points.size + 10, "${shaped.points.size} vs ${straight.points.size}")
        assertTrue(shaped.length > straight.length)
    }

    private fun MapBox.has(p: MapPoint) = p.x in minX..maxX && p.y in minY..maxY
    private fun JourneyMapUiState.at(name: String) = scene.stations.first { it.name == name }.point

    @Test
    fun recentreShowsWhatIsHappeningNowNotTheWholeJourney() {
        // On the train near Petersham: the rest of the train ride, the change and the start of
        // the bus; not Strathfield (behind you) nor Bondi Beach (the far end).
        val riding = state()
        val focus = riding.scene.focus!!
        assertTrue(focus.has(riding.at("Central")))
        assertTrue(focus.has(riding.scene.you!!))
        assertFalse(focus.has(riding.at("Strathfield")))
        assertFalse(focus.has(riding.at("Bondi Beach")))
        assertTrue(focus.width < riding.scene.bounds!!.width / 2)
        // Changing at Central: only the walk and the start of the bus.
        val changing = state(12, riding = false)
        assertTrue(changing.scene.focus!!.has(changing.at("Central")))
        assertTrue(changing.scene.focus!!.width < 2_500)
        // Each stage recentres: the fit key moves on.
        assertTrue(riding.fitKey != changing.fitKey)
        // Arrived: the last stop.
        val done = state(70, riding = false)
        assertTrue(done.scene.focus!!.has(done.at("Bondi Beach")))
    }

    @Test
    fun theWaitingBusIsNotDrawnUntilItLeavesAndTheChangeGoesOnceItHas() {
        assertEquals(listOf("vehicle-0"), state().scene.vehicles.map { it.id })
        // At Central, waiting: the train is in, the bus not drawn; the CHANGE flag stays.
        val waiting = state(12, riding = false)
        assertTrue(waiting.scene.vehicles.isEmpty())
        assertTrue(waiting.scene.stations.any { it.kind == StationKind.CHANGE })
        // On the bus: its ghost is drawn and Central is a plain name now.
        val onBus = state(24, riding = false)
        assertEquals(listOf("vehicle-1"), onBus.scene.vehicles.map { it.id })
        assertFalse(onBus.scene.stations.any { it.kind == StationKind.CHANGE })
        assertEquals(StationKind.AHEAD, onBus.scene.stations.single { it.name == "Central" }.kind)
        // The train ridden already is dimmed; the bus is not.
        assertEquals(listOf(true, false), onBus.scene.lines.filter { !it.walk }.map { it.done })
    }
}
