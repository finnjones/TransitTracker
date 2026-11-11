package app.transittracker.ui

import app.transittracker.model.Mode
import app.transittracker.ui.motion.PageTransition
import app.transittracker.ui.motion.Timeline
import app.transittracker.ui.motion.TransitionController
import app.transittracker.ui.nav.NavChange
import app.transittracker.ui.nav.NavEntry
import app.transittracker.ui.nav.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan 3.4 timing rules and the transition controller. */
class TransitionTest {
    private val width = 390f
    private val train = PageTransition.VehiclePass(Mode.TRAIN, 0)
    private val bus = PageTransition.VehiclePass(Mode.BUS, 0)
    private val all = listOf(train, bus, PageTransition.Doors, PageTransition.SplitFlap)

    private fun change(t: PageTransition, serial: Long = 1) =
        NavChange(NavEntry(0, Route.Trips), NavEntry(serial + 10, Route.Alerts), t, reverse = false, serial = serial)

    @Test
    fun vehiclesClearTheScreenInThePlannedTime() {
        // Plan 3.4: a four-car train or metro clears by about 640 ms, a bus or ferry by about 400 ms.
        assertTrue(Timeline.vehiclePassMs(Mode.TRAIN, width) in 600..700, "${Timeline.vehiclePassMs(Mode.TRAIN, width)}")
        assertTrue(Timeline.vehiclePassMs(Mode.METRO, width) in 600..700)
        assertTrue(Timeline.vehiclePassMs(Mode.BUS, width) in 360..440, "${Timeline.vehiclePassMs(Mode.BUS, width)}")
        assertTrue(Timeline.vehiclePassMs(Mode.FERRY, width) in 360..440)
    }

    @Test
    fun everythingIsStepped() {
        // 20 ms steps: no movement inside a step.
        assertEquals(Timeline.wipeFraction(20), Timeline.wipeFraction(39))
        assertTrue(Timeline.wipeFraction(40) > Timeline.wipeFraction(39))
        assertEquals(1f, Timeline.wipeFraction(300))
        // The vehicle's nose rides on the wipe edge.
        assertEquals(Timeline.wipeFraction(160), Timeline.noseFraction(160))
        // Doors: open, half, shut, shut, half, open.
        assertEquals(listOf(0f, 0.5f, 1f, 1f, 0.5f, 0f), listOf(0L, 120, 240, 360, 480, 600).map { Timeline.doorsClosed(it) })
        assertFalse(Timeline.doorsShowNewPage(299))
        assertTrue(Timeline.doorsShowNewPage(300))
        // Flap rows: five steps each, 60 ms apart, all done by 500 ms.
        assertEquals(0f, Timeline.flapRowProgress(0, 0))
        assertEquals(1f, Timeline.flapRowProgress(0, 280))
        assertEquals(0f, Timeline.flapRowProgress(1, 59))
        assertEquals(Timeline.flapRowProgress(0, 100), Timeline.flapRowProgress(1, 160))
        for (row in 0..20) assertEquals(1f, Timeline.flapRowProgress(row, Timeline.FLAP_MS), "row $row")
        assertEquals(0.2f, Timeline.flapRowProgress(0, 56))
    }

    @Test
    fun theNewPageTakesInputBy300ms() {
        for (t in all) {
            val c = TransitionController()
            c.start(change(t), width)
            assertNotNull(c.running, "$t runs")
            assertFalse(c.acceptsInput, "$t blocks input at the start")
            c.advanceBy(Timeline.INPUT_MS)
            assertTrue(c.acceptsInput, "$t takes input by 300 ms")
        }
        // The train is still crossing after 300 ms: decoration finishes later, input is already on.
        val c = TransitionController()
        c.start(change(train), width)
        c.advanceBy(400)
        assertNotNull(c.running)
        assertTrue(c.acceptsInput)
    }

    @Test
    fun aTapFinishesTheTransition() {
        val c = TransitionController()
        c.start(change(PageTransition.Doors), width)
        c.advanceBy(100)
        assertTrue(c.onTap(), "a tap before input is allowed is swallowed")
        assertNull(c.running)
        // After 300 ms the tap reaches the page and still ends the decoration.
        c.start(change(train, serial = 2), width)
        c.advanceBy(320)
        assertFalse(c.onTap())
        assertNull(c.running)
        assertFalse(c.onTap(), "no transition: taps go straight through")
    }

    @Test
    fun oneAtATimeAndTheSecondIsACut() {
        val c = TransitionController()
        c.start(change(train), width)
        c.advanceBy(100)
        c.start(change(PageTransition.SplitFlap, serial = 2), width)
        assertNull(c.running, "navigating during a transition finishes it and cuts")
    }

    @Test
    fun reduceMotionCutsEverything() {
        var reduce = true
        val c = TransitionController { reduce }
        for (t in all) {
            c.start(change(t), width)
            assertNull(c.running, "$t is a cut under reduce motion")
            assertTrue(c.acceptsInput)
        }
        reduce = false
        c.start(change(PageTransition.Doors), width)
        assertNotNull(c.running)
    }

    @Test
    fun runsEndOnTime() {
        val c = TransitionController()
        c.start(change(PageTransition.Doors), width)
        c.onFrame(1_000)
        c.onFrame(1_599)
        assertNotNull(c.running)
        c.onFrame(1_600)
        assertNull(c.running)
        c.start(change(PageTransition.Cut, serial = 3), width)
        assertNull(c.running, "a cut never runs")
    }
}
