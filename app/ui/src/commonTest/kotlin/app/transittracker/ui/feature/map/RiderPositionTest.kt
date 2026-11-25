package app.transittracker.ui.feature.map

import app.transittracker.model.LatLng
import app.transittracker.ui.platform.LocationFix
import app.transittracker.ui.sprites.SpriteLook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** On board, the train is drawn where the phone is ([riderReport]), not beside it. */
class RiderPositionTest {
    private val min = 60_000L
    private val t0 = 1_794_343_080_000L
    private val input = JourneyMapSamples.input(t0)
    private val train = input.legs[0]
    private val fix = input.follow.fix!!

    @Test
    fun aFixOnTheLineIsTheVehiclesPosition() {
        val r = assertNotNull(riderReport(input.follow, train.geometry, t0))
        assertEquals(fix.position, r.position)
        assertEquals(fix.timeMs, r.timestampMs)
    }

    @Test
    fun aFixOffTheLineVagueOrOldIsNot() {
        val bondi = input.follow.copy(fix = fix.copy(position = LatLng(-33.8915, 151.2767)))
        assertNull(riderReport(bondi, train.geometry, t0))
        assertNull(riderReport(input.follow.copy(fix = fix.copy(accuracyM = 500.0)), train.geometry, t0))
        assertNull(riderReport(input.follow, train.geometry, t0 + 5 * min))
        assertNull(riderReport(input.follow.copy(fix = null), train.geometry, t0))
        assertNull(riderReport(input.follow, schematicGeometry(train.tracking.detail.calls), t0))
    }

    @Test
    fun theTrainYouAreOnIsDrawnAtYourFix() {
        val report = riderReport(input.follow, train.geometry, t0)!!
        val reck = reckon(report, train.tracking.detail.calls, train.geometry, t0)
        val legs = listOf(train.copy(reckoning = reck, fromRider = true)) + input.legs.drop(1)
        val s = buildJourneyMapState(input.copy(legs = legs), t0)
        val v = s.scene.vehicles.single { it.id == "vehicle-0" }
        assertTrue(v.motion is VehicleMotion.Reckoned, "${v.motion}")
        assertEquals(SpriteLook.NORMAL, v.look)
        val spot = v.spotAt(t0, reduceMotion = true)!!
        val you = s.scene.you!!
        assertTrue(spot.point.distanceTo(you) < RIDER_MAX_OFF_LINE_M, "train ${spot.point} vs you $you")
    }
}
