package app.transittracker.ui.feature.map

import app.transittracker.core.decodePolyline
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
 * A train-then-bus journey on real stops and line shapes, for the journey map's shots and tests:
 * the T2 all stops from Strathfield to Central (12 stations), a walk up to Museum, then the 333
 * along Oxford Street and Bondi Road to Bondi Beach (20 stops). The sample core has no buses, and a long bus ride
 * with many stops is where the map has to work hardest. Both lines are blue, as they are in
 * Sydney, so the map cannot rely on colour alone to tell the rides apart.
 */
object JourneyMapSamples {
    private const val MIN = 60_000L

    // Real stops and line shapes from TfNSW's timetable (shapes IWL_2a and 161318, simplified to
    // 8 m and polyline-encoded).
    private val trainStops = listOf(
        "Strathfield Station Platform 7" to LatLng(-33.87163, 151.09379),
        "Burwood Station Platform 5" to LatLng(-33.877291, 151.104183),
        "Croydon Station Platform 4" to LatLng(-33.883202, 151.115367),
        "Ashfield Station Platform 3" to LatLng(-33.887643, 151.125733),
        "Summer Hill Station Platform 2" to LatLng(-33.890377, 151.138954),
        "Lewisham Station Platform 1" to LatLng(-33.893278, 151.147459),
        "Petersham Station Platform 1" to LatLng(-33.893874, 151.155342),
        "Stanmore Station Platform 2" to LatLng(-33.89453, 151.164053),
        "Newtown Station Platform 1" to LatLng(-33.897899, 151.179853),
        "Macdonaldtown Station Platform 1" to LatLng(-33.896907, 151.185808),
        "Redfern Station Platform 5" to LatLng(-33.891954, 151.198592),
        "Central Station Platform 17" to LatLng(-33.884098, 151.206823),
    )

    private val busStops = listOf(
        "Museum Station, Elizabeth St, Stand B" to LatLng(-33.876079, 151.209638),
        "Oxford St opp Brisbane St" to LatLng(-33.877483, 151.213254),
        "Oxford St at Palmer St" to LatLng(-33.879418, 151.215605),
        "Taylor Square, Oxford St, Stand D" to LatLng(-33.880766, 151.217555),
        "Oxford St at Glenmore Rd" to LatLng(-33.883629, 151.221947),
        "Oxford St opp Paddington Town Hall" to LatLng(-33.884878, 151.225943),
        "Oxford St after William St" to LatLng(-33.886316, 151.229351),
        "The Light Brigade Hotel, Oxford St" to LatLng(-33.888699, 151.232315),
        "Oxford St at Nelson St" to LatLng(-33.890415, 151.243024),
        "Oxford St at Newland St" to LatLng(-33.891648, 151.247137),
        "Bondi Junction Station, Stand G" to LatLng(-33.891214, 151.247729),
        "Bondi Junction Station, Stand A" to LatLng(-33.891415, 151.249576),
        "Westfield Bondi Junction, Oxford St, Stand B" to LatLng(-33.8917, 151.251621),
        "Bondi Rd opp Waverley Council Chambers" to LatLng(-33.892787, 151.256216),
        "Bondi Rd at Penkivil St" to LatLng(-33.89352, 151.261499),
        "Bondi Rd at Wellington St" to LatLng(-33.893976, 151.264456),
        "Bondi Rd at Castlefield St" to LatLng(-33.89439, 151.26727),
        "Bondi Rd opp Dudley St" to LatLng(-33.89524, 151.271631),
        "Campbell Pde opp South Bondi Beach" to LatLng(-33.894413, 151.273097),
        "Bondi Beach, Campbell Pde, Stand C" to LatLng(-33.890613, 151.27466),
    )

    /** The T2's line from Parramatta to Central. */
    private const val TRAIN_SHAPE = """nblmEsjtx[bIoGfFoA`GC~MlBpETrDQrDg@zKwDlHoEnLoQlHyMh[ko@vK_N`PiNt^k`@xg@c^d_@kUdH}FtEgHjDaL`@eEp@ux@C{AsBaN_@}V^{PrFsf@BaHcBySO}Hx@eKbIk`@fEuKtOa[fQkWbLyKfFcKx@_EbD_\lCgMpFgMbVg^zN}\jHe\tJy_@dBcMtA}a@pTo}@nBqLXeGEsH_B_VZyDxCuQV{GMiIsAaNDsHh@eFdEiPhB}FbEsHdC_K^cPEoT_DkRcW}aAsBoE{DaH}M{Mm@}BoMoPwPuPyDiBwHaB_OiBmCn@eEnCiDfE_B|@{AFgNwAyOxCyMv@eEOeUtBcCQkC}AyAiCk@mDxAc_@p@mFl@wAfCaBjB?lAX|CfC~EzFlB~@tf@tEpGfA~GbCvHtDza@nGpEtAr@x@"""

    /** The 333's line from Circular Quay to North Bondi. */
    private const val BUS_SHAPE = """jrtmEoq|y[Bm@hTlBfDzArAnAr`A`GNqQ|Um[lGsP|GuFrGqVbBuQ`LkQ`KmLh@_BlEwQd@wD{@uJuBgHYoE`B{@`Iia@}BQVKn@wKa@UpCQq@aIiBgEy@_EnHwHv@eCrLu}AlB}GHuAk@yAkAc@kIz@cF[uDiD_LuMq@wCDiEn@sDrBkFUyH`AiCQu@}A_BRS"""

    const val TRIP_ID = "sample-strathfield-bondi"

    val trip = SavedTrip(
        TRIP_ID, "Strathfield", "Bondi Beach",
        legs = listOf(
            TripLeg(setOf("T-0"), setOf("T-11")),
            TripLeg(setOf("B-0"), setOf("B-19"), minConnectionMinutes = 8),
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

    private fun leg(ref: ServiceRef, route: Route, headsign: String, calls: List<StopCall>, shape: String?, nowMs: Long): JourneyLegInput {
        val detail = ServiceDetail(ref, route, headsign, DepartureStatus.ON_TIME, calls, dataAgeMs = 20_000)
        val tracking = TrackingState(detail, vehicle = null, nextCall = calls.firstOrNull { !it.passed }, updatedMs = nowMs)
        return JourneyLegInput(
            MapLeg(ref, calls.first().stopId, calls.last().stopId), tracking,
            geometry(calls, shape), reckoning = null,
        )
    }

    /** The stops' geometry, along [shape] (encoded) when given; straight stop to stop otherwise. */
    private fun geometry(calls: List<StopCall>, shape: String?): ServiceGeometry {
        val straight = geometryFromStops(calls, calls.map { it.position })
        val line = shape ?: return straight
        return followShape(straight, MapPath(decodePolyline(line).map(MapProjection.Sydney::project)))
    }

    /**
     * The journey seen at [nowMs], timed relative to [startMs]: the train leaves Strathfield 12
     * minutes before it and reaches Central 10 after; the bus leaves 20 after and reaches Bondi
     * Beach 58 after. [riding] puts the phone on the train near Petersham (followed). [shapes]
     * false draws the rides straight from stop to stop, as without the timetable's shapes.
     */
    fun input(startMs: Long, nowMs: Long = startMs, riding: Boolean = true, shapes: Boolean = true): JourneyMapInput {
        val train = calls("T", trainStops, startMs - 12 * MIN, 2, nowMs)
        val bus = calls("B", busStops, startMs + 20 * MIN, 2, nowMs)
        val legs = listOf(
            leg(trainRef, Route("T2", "T2", "Inner West & Leppington Line", Mode.TRAIN, "0098CD"), "City Circle", train, TRAIN_SHAPE.takeIf { shapes }, nowMs),
            leg(busRef, Route("333", "333", "Circular Quay to North Bondi", Mode.BUS, "00B5EF"), "North Bondi", bus, BUS_SHAPE.takeIf { shapes }, nowMs),
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
