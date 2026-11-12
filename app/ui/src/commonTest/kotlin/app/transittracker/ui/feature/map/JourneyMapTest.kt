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
        // Strathfield (left already), the change at Central, and Bondi Beach.
        assertEquals(listOf("Strathfield", "Central", "Bondi Beach"), major.map { it.name })
        assertEquals(listOf(StationKind.AHEAD, StationKind.CHANGE, StationKind.ALIGHT), major.map { it.kind })
        // Ten stations between on the train, twelve stops on the bus, and where the bus is boarded.
        assertEquals(10 + 12 + 1, st.count { it.minor })
        assertTrue(st.filter { it.minor }.all { it.priority > 1 && it.flag == null })
    }

    @Test
    fun theChangeIsOneFlagThatSaysHowLongAndWhatComesNext() {
        val change = state().scene.stations.single { it.kind == StationKind.CHANGE }
        assertEquals("CHANGE · 6 MIN", change.flag)
        assertTrue(change.detail!!.startsWith("THEN 333 · "))
        assertNull(change.flagRole)
        assertEquals(0, change.priority)
        val end = state().scene.stations.single { it.kind == StationKind.ALIGHT }
        assertTrue(end.flag!!.startsWith("GET OFF · "))
        assertNull(end.detail) // the time is in the flag
    }

    @Test
    fun aTightChangeIsFlaggedInCaution() {
        // Allow eight minutes to change: six is tight.
        val input = JourneyMapSamples.input(t0).let { i ->
            i.copy(trip = i.trip!!.copy(legs = i.trip!!.legs.mapIndexed { k, l -> if (k == 1) l.copy(minConnectionMinutes = 8) else l }))
        }
        val change = buildJourneyMapState(input, t0).scene.stations.single { it.kind == StationKind.CHANGE }
        assertEquals("TIGHT CHANGE · 6 MIN", change.flag)
        assertEquals(ColorRole.CAUTION, change.flagRole)
    }

    @Test
    fun theWaitingBusIsNotDrawnUntilItLeavesAndTheChangeGoesOnceItHas() {
        assertEquals(listOf("vehicle-0"), state().scene.vehicles.map { it.id })
        // At Central, waiting: the train is in, the bus not drawn; the CHANGE flag stays.
        val waiting = state(12, riding = false)
        assertTrue(waiting.scene.vehicles.isEmpty())
        assertTrue(waiting.scene.stations.any { it.kind == StationKind.CHANGE })
        // On the bus: its ghost is drawn and Central is a plain name now.
        val onBus = state(20, riding = false)
        assertEquals(listOf("vehicle-1"), onBus.scene.vehicles.map { it.id })
        assertFalse(onBus.scene.stations.any { it.kind == StationKind.CHANGE })
        assertEquals(StationKind.AHEAD, onBus.scene.stations.single { it.name == "Central" }.kind)
        // The train ridden already is dimmed; the bus is not.
        assertEquals(listOf(true, false), onBus.scene.lines.filter { !it.walk }.map { it.done })
    }
}
