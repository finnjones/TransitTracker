package app.transittracker.ui

import app.transittracker.ui.loading.LoaderFleet
import app.transittracker.ui.loading.LoaderTiming
import app.transittracker.ui.loading.TreadmillFrame
import app.transittracker.ui.loading.boardLetter
import app.transittracker.ui.loading.boardRowOpen
import app.transittracker.ui.loading.flapTileFrame
import app.transittracker.ui.loading.treadmillFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoadingModelTest {
    private val n = LoaderFleet.size
    private val turn = LoaderTiming.VEHICLE_TICKS
    /** The first tick of the second vehicle's turn. */
    private val flip = turn - LoaderTiming.PHASE

    @Test
    fun everyLoaderIsSettledAtTickZero() {
        val t = treadmillFrame(0, n, reduceMotion = false)
        assertEquals(0, t.vehicle)
        assertEquals(0, t.offsetDots)
        assertFalse(flapTileFrame(0, n, reduceMotion = false).half)
        for ((i, ch) in "FINDING JOURNEYS".withIndex()) assertEquals(ch, boardLetter(ch, i, 0, reduceMotion = false))
        for (row in 0 until 5) assertEquals(1f, boardRowOpen(row, 0, reduceMotion = false))
    }

    @Test
    fun treadmillVehicleDrivesOffAndTheNextDrivesIn() {
        val offsets = (flip - 3..flip + 2).map { treadmillFrame(it, n, false).offsetDots }
        assertEquals(listOf(0, 10, 30, -30, -10, 0), offsets)
        assertEquals(0, treadmillFrame(flip - 1, n, false).vehicle)
        assertEquals(1, treadmillFrame(flip, n, false).vehicle)
    }

    @Test
    fun treadmillTurnsThroughTheWholeFleetAndStartsAgain() {
        val seen = (0 until n * turn).map { treadmillFrame(it, n, false).vehicle }.distinct()
        assertEquals((0 until n).toList(), seen)
        assertEquals(treadmillFrame(0, n, false), treadmillFrame(n * turn * LoaderTiming.SCROLL_WRAP, n, false))
    }

    @Test
    fun treadmillWithOneVehicleNeverLeavesTheCentre() {
        for (tick in 0 until 3 * turn) {
            val f = treadmillFrame(tick, 1, false)
            assertEquals(0, f.vehicle)
            assertEquals(0, f.offsetDots)
        }
    }

    @Test
    fun treadmillGroundRunsTwoDotsATickAndTheWheelsTurn() {
        val a = treadmillFrame(0, n, false)
        val b = treadmillFrame(1, n, false)
        assertEquals(LoaderTiming.SCROLL_DOTS, (b.scrollDots - a.scrollDots).mod(LoaderTiming.SCROLL_WRAP))
        assertEquals((a.spriteFrame + 1) % 4, b.spriteFrame)
    }

    @Test
    fun reduceMotionHoldsOneSettledFrame() {
        for (tick in listOf(0, flip, flip + 1, 99)) {
            assertEquals(TreadmillFrame(0, 0, 0, 0), treadmillFrame(tick, n, reduceMotion = true))
            assertFalse(flapTileFrame(tick, n, reduceMotion = true).half)
            assertEquals('J', boardLetter('J', 8, tick, reduceMotion = true))
            assertEquals(1f, boardRowOpen(0, tick, reduceMotion = true))
        }
    }

    @Test
    fun tileIsHalfOverOnTheLastTickOfOneTurnAndTheFirstOfTheNext() {
        val before = flapTileFrame(flip - 1, n, false)
        val after = flapTileFrame(flip, n, false)
        assertTrue(before.half)
        assertEquals(0, before.vehicle)
        assertTrue(after.half)
        assertEquals(1, after.vehicle)
        assertFalse(flapTileFrame(flip - 2, n, false).half)
        assertFalse(flapTileFrame(flip + 1, n, false).half)
    }

    @Test
    fun lettersSpinOnTheFlipAndSettleFromTheLeft() {
        val text = "FINDING JOURNEYS"
        fun shown(tick: Int) = text.withIndex().map { (i, ch) -> boardLetter(ch, i, tick, false) }.joinToString("")
        fun settled(tick: Int) = text.indices.count { boardLetter(text[it], it, tick, false) == text[it] && text[it] != ' ' }
        // On the flip every letter is spinning (a spinning cell can land on its own letter by chance).
        assertTrue(shown(flip).all { it == ' ' || it in 'A'..'Z' })
        assertEquals(' ', shown(flip)[7])
        assertTrue(settled(flip) < 4)
        assertEquals(text.take(6), shown(flip + 1).take(6))
        assertEquals(text.take(12), shown(flip + 2).take(12))
        assertEquals(text, shown(flip + 3))
        assertEquals(text, shown(flip + turn - 1))
    }

    @Test
    fun rowsFlipOneAfterAnotherDownTheBoard() {
        val first = LoaderTiming.ROW_TICKS - LoaderTiming.ROW_PHASE
        for (row in 0 until 5) {
            assertEquals(0.1f, boardRowOpen(row, first + row, false))
            assertEquals(0.55f, boardRowOpen(row, first + row + 1, false))
            assertEquals(1f, boardRowOpen(row, first + row + 2, false))
            assertEquals(0.1f, boardRowOpen(row, first + row + LoaderTiming.ROW_TICKS, false))
        }
    }
}
