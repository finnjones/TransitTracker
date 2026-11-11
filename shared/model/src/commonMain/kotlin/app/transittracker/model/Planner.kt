@file:OptIn(ExperimentalSerializationApi::class)

package app.transittracker.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/*
 * Journey planning (front-end plan sections 8.3 and 8.5).
 *
 * GET  /v1/places?q=…&near=lat,lon   -> PlacesResponse   (TfNSW stop finder, through tt-server)
 * POST /v1/plan  (PlanRequest)       -> PlanResponse     (TfNSW Trip Planner, through tt-server)
 *
 * These are our own formats, not TfNSW's, so the engine behind them can change (e.g. to MOTIS
 * on the home server) without touching the app. Field numbers follow the same rules as Api.kt.
 * Start and end points are sent to TfNSW without any device identifier and are never stored
 * or logged by the server.
 */

@Serializable
enum class PlaceKind { STATION, STOP, ADDRESS, POI, SUBURB, OTHER }

@Serializable
data class Place(
    /** Opaque id from the planner; pass it back in [PlaceRef.placeId]. */
    @ProtoNumber(1) val id: String,
    /** Display name, e.g. "Central Station" or "1 Martin Place". */
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val kind: PlaceKind = PlaceKind.OTHER,
    @ProtoNumber(4) val position: LatLng? = null,
    /** Our GTFS stop/station id when this place is a stop we know (timetable packs). */
    @ProtoNumber(5) val stopId: StopId? = null,
    /** Suburb or area, to tell same-named places apart. */
    @ProtoNumber(6) val locality: String? = null,
    @ProtoNumber(7) val modes: List<Mode> = emptyList(),
    /** The planner's best match for the query. */
    @ProtoNumber(8) val best: Boolean = false,
)

@Serializable
data class PlacesResponse(
    @ProtoNumber(1) val serverTimeMs: Long,
    @ProtoNumber(2) val places: List<Place> = emptyList(),
)

/** A start or end point. Exactly one of [placeId], [stopId] or [position] must be set. */
@Serializable
data class PlaceRef(
    @ProtoNumber(1) val placeId: String? = null,
    @ProtoNumber(2) val stopId: StopId? = null,
    @ProtoNumber(3) val position: LatLng? = null,
    /** Display name for the point (e.g. "Home"); never needed by the server. */
    @ProtoNumber(4) val name: String? = null,
)

@Serializable
data class PlanRequest(
    @ProtoNumber(1) val from: PlaceRef,
    @ProtoNumber(2) val to: PlaceRef,
    /** Leave after (or arrive by, see [arriveBy]) this time; null = now. */
    @ProtoNumber(3) val timeMs: Long? = null,
    @ProtoNumber(4) val arriveBy: Boolean = false,
    /** 1..10 */
    @ProtoNumber(5) val maxJourneys: Int = 5,
    @ProtoNumber(6) val wheelchair: Boolean = false,
    @ProtoNumber(7) val excludedModes: List<Mode> = emptyList(),
)

@Serializable
enum class LegKind { WALK, RIDE }

@Serializable
data class LegPoint(
    @ProtoNumber(1) val name: String,
    /** Our GTFS stop id (usually a platform) when known. */
    @ProtoNumber(2) val stopId: StopId? = null,
    /** Our GTFS parent station id when known. */
    @ProtoNumber(3) val stationId: StopId? = null,
    @ProtoNumber(4) val position: LatLng? = null,
    @ProtoNumber(5) val platform: String? = null,
)

@Serializable
data class LegStop(
    @ProtoNumber(1) val stopId: StopId? = null,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val position: LatLng? = null,
    @ProtoNumber(4) val arrivalMs: Long? = null,
    @ProtoNumber(5) val departureMs: Long? = null,
)

@Serializable
data class PlannedLeg(
    @ProtoNumber(1) val kind: LegKind,
    @ProtoNumber(2) val from: LegPoint,
    @ProtoNumber(3) val to: LegPoint,
    /** Timetabled times (epoch ms). */
    @ProtoNumber(4) val departureMs: Long,
    @ProtoNumber(5) val arrivalMs: Long,
    /** The planner's own live estimate at planning time, if it gave one. */
    @ProtoNumber(6) val estimatedDepartureMs: Long? = null,
    @ProtoNumber(7) val estimatedArrivalMs: Long? = null,
    @ProtoNumber(8) val durationS: Int,
    @ProtoNumber(9) val distanceM: Int? = null,
    /** Path of the leg as a Google encoded polyline (precision 5). */
    @ProtoNumber(10) val encodedPath: String? = null,
    // ---- RIDE only ----
    @ProtoNumber(11) val mode: Mode? = null,
    /** Our GTFS route id when matched. */
    @ProtoNumber(12) val routeId: RouteId? = null,
    /** Line as shown to people, e.g. "T1", "M1", "333". */
    @ProtoNumber(13) val lineName: String? = null,
    /** `RRGGBB` */
    @ProtoNumber(14) val lineColor: String? = null,
    @ProtoNumber(15) val headsign: String? = null,
    /** GTFS trip id matched to our timetable and live feeds; null when not matched. */
    @ProtoNumber(16) val tripId: TripId? = null,
    @ProtoNumber(17) val serviceDate: ServiceDate? = null,
    /** The planner's own trip id, kept for diagnostics. */
    @ProtoNumber(18) val plannerTripId: String? = null,
    /** True when [tripId]/[serviceDate] were matched to our timetable (live data, tracking and watches work). */
    @ProtoNumber(19) val matched: Boolean = false,
    /** Stops between [from] and [to], in order. */
    @ProtoNumber(20) val stops: List<LegStop> = emptyList(),
    @ProtoNumber(21) val cancelled: Boolean = false,
)

@Serializable
data class PlannedJourney(
    @ProtoNumber(1) val legs: List<PlannedLeg>,
    @ProtoNumber(2) val departureMs: Long,
    @ProtoNumber(3) val arrivalMs: Long,
    @ProtoNumber(4) val durationS: Int,
    @ProtoNumber(5) val interchanges: Int,
    @ProtoNumber(6) val walkMeters: Int = 0,
)

@Serializable
data class PlanResponse(
    @ProtoNumber(1) val serverTimeMs: Long,
    @ProtoNumber(2) val journeys: List<PlannedJourney> = emptyList(),
    /** Which engine answered, e.g. "tfnsw-trip-planner". */
    @ProtoNumber(3) val source: String = "",
)
