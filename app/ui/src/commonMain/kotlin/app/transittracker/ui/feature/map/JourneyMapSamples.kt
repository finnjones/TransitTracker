package app.transittracker.ui.feature.map

import app.transittracker.model.DepartureStatus
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.Route
import app.transittracker.model.SavedTrip
import app.transittracker.model.ServiceDetail
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopCall
import app.transittracker.model.TrackingState
import app.transittracker.model.TripLeg
import app.transittracker.ui.feature.follow.FollowMatch
import app.transittracker.ui.feature.follow.FollowStage
import app.transittracker.ui.feature.follow.FollowState
import app.transittracker.ui.feature.follow.FollowStatus
import app.transittracker.ui.nav.MapLeg
import app.transittracker.ui.platform.LocationFix

/**
 * A train-then-bus journey with real stop positions, for the journey map's shots and tests: the
 * T2 all stops from Strathfield to Central (12 stations), a short walk to Eddy Avenue, then a bus
 * down Oxford Street to Bondi Beach (14 stops). The sample core has no buses, and a long bus ride
 * with many stops is where the map has to work hardest. Both lines are blue, as they are in
 * Sydney, so the map cannot rely on colour alone to tell the rides apart.
 */
object JourneyMapSamples {
    private const val MIN = 60_000L

    private val trainStops = listOf(
        "Strathfield" to LatLng(-33.8718, 151.0942),
        "Burwood" to LatLng(-33.8771, 151.1035),
        "Croydon" to LatLng(-33.8829, 151.1157),
        "Ashfield" to LatLng(-33.8877, 151.1253),
        "Summer Hill" to LatLng(-33.8916, 151.1385),
        "Lewisham" to LatLng(-33.8935, 151.1475),
        "Petersham" to LatLng(-33.8946, 151.1550),
        "Stanmore" to LatLng(-33.8935, 151.1641),
        "Newtown" to LatLng(-33.8979, 151.1793),
        "Macdonaldtown" to LatLng(-33.8966, 151.1868),
        "Redfern" to LatLng(-33.8916, 151.1984),
        "Central" to LatLng(-33.8832, 151.2063),
    )

    private val busStops = listOf(
        "Central Station, Eddy Av" to LatLng(-33.8826, 151.2085),
        "Elizabeth St, Albion St" to LatLng(-33.8812, 151.2110),
        "Taylor Square" to LatLng(-33.8805, 151.2172),
        "Oxford St, Barcom Av" to LatLng(-33.8818, 151.2215),
        "Paddington Town Hall" to LatLng(-33.8843, 151.2262),
        "Oxford St, Glenmore Rd" to LatLng(-33.8858, 151.2297),
        "Oxford St, Centennial Sq" to LatLng(-33.8878, 151.2335),
        "Oxford St, Ocean St" to LatLng(-33.8895, 151.2398),
        "Bondi Junction" to LatLng(-33.8916, 151.2481),
        "Bondi Rd, Denison St" to LatLng(-33.8920, 151.2525),
        "Bondi Rd, Flood St" to LatLng(-33.8936, 151.2580),
        "Bondi Rd, Wellington St" to LatLng(-33.8938, 151.2640),
        "Bondi Rd, Campbell Pde" to LatLng(-33.8930, 151.2700),
        "Bondi Beach" to LatLng(-33.8913, 151.2748),
    )

    const val TRIP_ID = "sample-strathfield-bondi"

    val trip = SavedTrip(
        TRIP_ID, "Strathfield", "Bondi Beach",
        legs = listOf(
            TripLeg(setOf("T-0"), setOf("T-11")),
            TripLeg(setOf("B-0"), setOf("B-13"), minConnectionMinutes = 4),
        ),
    )

    private val trainRef = ServiceRef("T2-0726", "20261111")
    private val busRef = ServiceRef("333-0750", "20261111")

    private fun calls(prefix: String, stops: List<Pair<String, LatLng>>, firstDep: Long, stepMin: Int, nowMs: Long): List<StopCall> =
        stops.mapIndexed { k, (name, at) ->
            val t = firstDep + k * stepMin * MIN
            StopCall(
                k + 1, "$prefix-$k", name, if (prefix == "T" && k == 0) "4" else null, false,
                if (k == 0) null else t, if (k == stops.lastIndex) null else t,
                if (k == 0) null else t, if (k == stops.lastIndex) null else t,
                skipped = false, passed = t <= nowMs, position = at,
            )
        }

    private fun leg(ref: ServiceRef, route: Route, headsign: String, calls: List<StopCall>, nowMs: Long): JourneyLegInput {
        val detail = ServiceDetail(ref, route, headsign, DepartureStatus.ON_TIME, calls, dataAgeMs = 20_000)
        val tracking = TrackingState(detail, vehicle = null, nextCall = calls.firstOrNull { !it.passed }, updatedMs = nowMs)
        return JourneyLegInput(
            MapLeg(ref, calls.first().stopId, calls.last().stopId), tracking,
            geometryFromStops(calls, calls.map { it.position }), reckoning = null,
        )
    }

    /**
     * The journey seen at [nowMs], timed relative to [startMs]: the train leaves Strathfield 12
     * minutes before it and reaches Central
     * 10 after; the bus leaves 16 after and reaches Bondi Beach 42 after. [riding] puts the phone
     * on the train near Petersham (followed); otherwise nothing is followed.
     */
    fun input(startMs: Long, nowMs: Long = startMs, riding: Boolean = true): JourneyMapInput {
        val train = calls("T", trainStops, startMs - 12 * MIN, 2, nowMs)
        val bus = calls("B", busStops, startMs + 16 * MIN, 2, nowMs)
        val legs = listOf(
            leg(trainRef, Route("T2", "T2", "Inner West & Leppington Line", Mode.TRAIN, "0098CD"), "City Circle", train, nowMs),
            leg(busRef, Route("333", "333", "Circular Quay to North Bondi", Mode.BUS, "00B5EF"), "North Bondi", bus, nowMs),
        )
        val follow = if (riding) {
            FollowState(
                FollowStatus.FOLLOWING,
                FollowMatch(TRIP_ID, trainRef, 0, FollowStage.RIDING, 0.0),
                LocationFix(LatLng(-33.8944, 151.1560), nowMs, 20.0),
            )
        } else {
            FollowState()
        }
        return JourneyMapInput(trip, legs, follow)
    }
}
