package app.transittracker.ui

import app.transittracker.model.DepartureStatus
import app.transittracker.model.Mode
import app.transittracker.model.StopCall
import app.transittracker.ui.feature.service.ChipKind
import app.transittracker.ui.feature.service.FooterKind
import app.transittracker.ui.feature.service.ListItem
import app.transittracker.ui.feature.service.MarkerKind
import app.transittracker.ui.feature.service.StopRole
import app.transittracker.ui.feature.service.ageWords
import app.transittracker.ui.feature.service.footerFor
import app.transittracker.ui.feature.service.listItems
import app.transittracker.ui.feature.service.modeNoun
import app.transittracker.ui.feature.service.resolveStops
import app.transittracker.ui.feature.service.serviceChip
import app.transittracker.ui.feature.service.serviceDelaySeconds
import app.transittracker.ui.feature.service.spokenCountdown
import app.transittracker.ui.feature.service.stopTimes
import app.transittracker.ui.feature.service.vehicleMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure rules of the service screen: footer countdown, vehicle marker, stop times, stops. */
class ServiceRulesTest {
    private val min = 60_000L

    /** Five stops one every ten minutes from t = 0, dwell 30 s; [delayMin] on every expected time. */
    private fun calls(nowMin: Double, delayMin: Int = 0, live: Boolean = true): List<StopCall> {
        val now = (nowMin * min).toLong()
        return (0 until 5).map { i ->
            val arr = i * 10 * min
            val dep = arr + 30_000
            val d = delayMin * min
            StopCall(
                stopSequence = i + 1, stopId = "S$i", stopName = "Stop $i", platform = "${i + 1}", platformChanged = false,
                scheduledArrivalMs = arr, scheduledDepartureMs = dep,
                expectedArrivalMs = if (live) arr + d else null, expectedDepartureMs = if (live) dep + d else null,
                skipped = false, passed = (if (live) dep + d else dep) <= now,
            )
        }
    }

    // ---------------------------------------------------------------- footer

    @Test
    fun footerCountsToYourStopUntilTheVehicleHasLeftIt() {
        val c = calls(nowMin = 12.0) // Stop 0 and Stop 1 (dep 10:30) passed
        val f = footerFor(c, boardIndex = 2, alightIndex = 4, status = DepartureStatus.ON_TIME, stale = false)
        assertEquals(FooterKind.LEAVES, f.kind)
        assertEquals("Stop 2", f.stopName)
        assertEquals(20 * min + 30_000, f.targetMs)
        assertEquals("3", f.platform)
    }

    @Test
    fun footerSwitchesToArrivesOnceYourStopIsPassed() {
        val c = calls(nowMin = 21.0) // Stop 2 (dep 20:30) passed
        val f = footerFor(c, 2, 4, DepartureStatus.ON_TIME, false)
        assertEquals(FooterKind.ARRIVES, f.kind)
        assertEquals("Stop 4", f.stopName)
        assertEquals(40 * min, f.targetMs) // the arrival, not the departure
        assertEquals("5", f.platform)
    }

    @Test
    fun footerIsDoneOnceTheDestinationIsPassed() {
        val c = calls(nowMin = 41.0)
        assertEquals(FooterKind.DONE, footerFor(c, 2, 4, DepartureStatus.ON_TIME, false).kind)
    }

    @Test
    fun footerWithoutYourStopsCountsToTheNextStop() {
        val c = calls(nowMin = 12.0)
        val f = footerFor(c, null, null, DepartureStatus.ON_TIME, false)
        assertEquals(FooterKind.NEXT, f.kind)
        assertEquals("Stop 2", f.stopName)
        assertEquals(20 * min, f.targetMs)
    }

    @Test
    fun footerWithOnlyABoardingStopCountsToTheNextStopOnceItIsPassed() {
        val c = calls(nowMin = 21.0)
        val f = footerFor(c, 2, null, DepartureStatus.ON_TIME, false)
        assertEquals(FooterKind.NEXT, f.kind)
        assertEquals("Stop 3", f.stopName)
    }

    @Test
    fun footerWithOnlyADestinationCountsToIt() {
        val f = footerFor(calls(5.0), null, 3, DepartureStatus.ON_TIME, false)
        assertEquals(FooterKind.ARRIVES, f.kind)
        assertEquals("Stop 3", f.stopName)
    }

    @Test
    fun footerUsesExpectedTimesAndMarksStaleDataApproximate() {
        val late = calls(nowMin = 5.0, delayMin = 4)
        val f = footerFor(late, 2, 4, DepartureStatus.LATE, stale = true)
        assertEquals(24 * min + 30_000, f.targetMs)
        assertTrue(f.approximate)
        assertFalse(footerFor(late, 2, 4, DepartureStatus.LATE, stale = false).approximate)
    }

    @Test
    fun footerOfACancelledServiceHasNoCountdown() {
        val f = footerFor(calls(5.0), 2, 4, DepartureStatus.CANCELLED, false)
        assertEquals(FooterKind.CANCELLED, f.kind)
        assertNull(f.targetMs)
    }

    @Test
    fun footerUsesTheChangedPlatform() {
        val c = calls(5.0).toMutableList()
        c[2] = c[2].copy(platform = "6", platformChanged = true)
        val f = footerFor(c, 2, 4, DepartureStatus.ON_TIME, false)
        assertEquals("6", f.platform)
        assertTrue(f.platformChanged)
    }

    // ---------------------------------------------------------------- marker

    @Test
    fun markerSitsBetweenTheLastPassedStopAndTheNext() {
        val m = assertNotNull(vehicleMarker(calls(nowMin = 15.0), DepartureStatus.ON_TIME, false, (15 * min)))
        assertEquals(1, m.afterIndex) // Stop 1 passed at 10:30, Stop 2 arrives at 20:00
        assertFalse(m.atStop)
        assertEquals(MarkerKind.LIVE, m.kind)
    }

    @Test
    fun markerMovesToTheStopWhereTheVehicleStands() {
        // Stop 2 arrives at 20:00 and leaves at 20:30.
        val m = assertNotNull(vehicleMarker(calls(nowMin = 20.2), DepartureStatus.ON_TIME, false, (20.2 * min).toLong()))
        assertEquals(2, m.afterIndex)
        assertTrue(m.atStop)
    }

    @Test
    fun markerFollowsTheDelay() {
        // 4 minutes late: Stop 2 arrives at 24:00, so at 21:00 the vehicle is still on the way.
        val now = 21 * min
        val m = assertNotNull(vehicleMarker(calls(nowMin = 21.0, delayMin = 4), DepartureStatus.LATE, false, now))
        assertEquals(1, m.afterIndex)
    }

    @Test
    fun noMarkerBeforeTheFirstStopIsPassedOrAfterTheLast() {
        assertNull(vehicleMarker(calls(nowMin = -5.0), DepartureStatus.ON_TIME, false, -5 * min))
        assertNull(vehicleMarker(calls(nowMin = 41.0), DepartureStatus.ON_TIME, false, 41 * min))
    }

    @Test
    fun noMarkerForACancelledService() {
        assertNull(vehicleMarker(calls(nowMin = 15.0), DepartureStatus.CANCELLED, false, 15 * min))
    }

    @Test
    fun markerKindFollowsTheData() {
        assertEquals(MarkerKind.STALE, vehicleMarker(calls(15.0), DepartureStatus.ON_TIME, true, 15 * min)?.kind)
        assertEquals(MarkerKind.TIMETABLE, vehicleMarker(calls(15.0, live = false), DepartureStatus.TIMETABLED, false, 15 * min)?.kind)
    }

    @Test
    fun theMarkerIsPlacedAfterItsRow() {
        val c = calls(nowMin = 15.0)
        val rows = c.mapIndexed { i, call ->
            app.transittracker.ui.feature.service.StopRow(i, call, StopRole.PLAIN, call.passed, false, null)
        }
        val items = listItems(rows, vehicleMarker(c, DepartureStatus.ON_TIME, false, 15 * min))
        assertEquals(6, items.size)
        assertTrue(items[2] is ListItem.Marker)
        assertEquals(1, (items[1] as ListItem.Stop).row.index)
        assertEquals(5, listItems(rows, null).size)
    }

    // ---------------------------------------------------------------- stop times and delay

    @Test
    fun aStopShowsItsDepartureAndTheDestinationItsArrival() {
        val c = calls(0.0, delayMin = 4)[1]
        assertEquals(10 * min + 30_000, stopTimes(c, useArrival = false)?.scheduledMs)
        assertEquals(10 * min, stopTimes(c, useArrival = true)?.scheduledMs)
        assertEquals(14 * min, stopTimes(c, useArrival = true)?.expectedMs)
    }

    @Test
    fun timesDifferFromAMinuteApart() {
        val base = calls(0.0)[1]
        assertFalse(stopTimes(base.copy(expectedDepartureMs = base.scheduledDepartureMs!! + 59_000), false)!!.differs)
        assertTrue(stopTimes(base.copy(expectedDepartureMs = base.scheduledDepartureMs!! + 60_000), false)!!.differs)
        assertTrue(stopTimes(base.copy(expectedDepartureMs = base.scheduledDepartureMs!! - 70_000), false)!!.differs) // early
        assertFalse(stopTimes(base.copy(expectedDepartureMs = null, expectedArrivalMs = null), false)!!.differs)
    }

    @Test
    fun aCallWithoutAnyTimeHasNoTimes() {
        val none = calls(0.0)[1].copy(
            scheduledArrivalMs = null, scheduledDepartureMs = null, expectedArrivalMs = null, expectedDepartureMs = null,
        )
        assertNull(stopTimes(none, false))
    }

    @Test
    fun delayIsThatOfTheNextStopStillToCome() {
        assertEquals(240, serviceDelaySeconds(calls(15.0, delayMin = 4)))
        assertNull(serviceDelaySeconds(calls(15.0, live = false)))
        // Everything passed: the last stop's arrival still gives the delay.
        assertEquals(240, serviceDelaySeconds(calls(60.0, delayMin = 4)))
    }

    // ---------------------------------------------------------------- your stops, chip, words

    @Test
    fun yourStopsAreFoundById() {
        val r = resolveStops(calls(0.0), "S2", "S4")
        assertEquals(2, r.boardIndex)
        assertEquals(4, r.alightIndex)
    }

    @Test
    fun theDestinationMustComeAfterTheBoardingStop() {
        val r = resolveStops(calls(0.0), "S3", "S1")
        assertEquals(3, r.boardIndex)
        assertNull(r.alightIndex)
    }

    @Test
    fun aPlatformChangeKeepsYourStopsThroughTheirSequence() {
        val c = calls(0.0).toMutableList()
        c[2] = c[2].copy(stopId = "S2-new") // platform moved after the page opened
        val lost = resolveStops(c, "S2", "S4")
        assertNull(lost.boardIndex)
        val kept = resolveStops(c, "S2", "S4", boardSeq = 3, alightSeq = 5)
        assertEquals(2, kept.boardIndex)
        assertEquals(4, kept.alightIndex)
    }

    @Test
    fun unknownStopsResolveToNothing() {
        assertEquals(null to null, resolveStops(calls(0.0), null, null).let { it.boardIndex to it.alightIndex })
        assertNull(resolveStops(calls(0.0), "nope", "nada").boardIndex)
    }

    @Test
    fun chipFollowsTheData() {
        assertEquals(ChipKind.LIVE, serviceChip(DepartureStatus.ON_TIME, false, 12_000)?.kind)
        assertEquals(ChipKind.LIVE, serviceChip(DepartureStatus.ADDED, false, 12_000)?.kind)
        assertEquals(ChipKind.STALE, serviceChip(DepartureStatus.LATE, true, 180_000)?.kind)
        assertEquals(ChipKind.TIMETABLE, serviceChip(DepartureStatus.TIMETABLED, false, null)?.kind)
        assertNull(serviceChip(DepartureStatus.CANCELLED, false, null))
    }

    @Test
    fun wordsForAgesCountdownsAndModes() {
        assertEquals("12S", ageWords(12_000))
        assertEquals("3 MIN", ageWords(180_000))
        assertEquals("leaves strathfield in 4 minutes 12 seconds", spokenCountdown("LEAVES STRATHFIELD IN", 252_000))
        assertEquals("arrives central in 1 minute", spokenCountdown("ARRIVES CENTRAL IN", 60_000))
        assertEquals("arrives central in 0 seconds", spokenCountdown("ARRIVES CENTRAL IN", -5_000))
        assertEquals("TRAM", modeNoun(Mode.LIGHT_RAIL))
        assertEquals("TRAIN", modeNoun(Mode.TRAIN))
    }
}
