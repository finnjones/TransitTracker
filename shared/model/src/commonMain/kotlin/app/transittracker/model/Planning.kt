@file:OptIn(ExperimentalSerializationApi::class)

package app.transittracker.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/*
 * Journey planning: "how do I get from A to B?"
 *
 * One file, two uses:
 *  - wire payloads between the phone and tt-server for GET /v1/places and POST /v1/plan (JSON by
 *    default, like the rest of Api.kt; field numbers are part of the contract: never reuse or
 *    renumber, only add new fields with new numbers and defaults);
 *  - the front end's contract with whatever plans journeys: [JourneyPlanner].
 *
 * tt-server answers these by asking TfNSW's Trip Planner and trimming the result. The phone never
 * talks to TfNSW. Rides are matched to the timetable pack where possible, so the phone can apply
 * its own live data; a ride that could not be matched keeps the planner's times and is shown as
 * timetabled.
 */

// ---- Places (address and stop search) ----

@Serializable
enum class PlaceKind { STOP, ADDRESS, POI, LOCALITY, OTHER }

@Serializable
data class Place(
    /** Opaque planner identifier. Send it back in [PlanEndpoint.placeId]; do not parse it. */
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val kind: PlaceKind,
    /** First line: "Albert Rd", "Strathfield Station", "Bondi Beach". */
    @ProtoNumber(3) val name: String,
    /** Second line: suburb or other context; may be empty. */
    @ProtoNumber(4) val detail: String = "",
    @ProtoNumber(5) val position: LatLng,
    /** GTFS station or stop id, only when [kind] is STOP and the id exists in the server's timetable. */
    @ProtoNumber(6) val stopId: StopId? = null,
    @ProtoNumber(7) val modes: List<Mode> = emptyList(),
)

@Serializable
data class PlacesResponse(
    @ProtoNumber(1) val serverTimeMs: Long,
    @ProtoNumber(2) val places: List<Place> = emptyList(),
)

// ---- Plan request ----

/** One end of a journey. [position] is always sent; [placeId] makes the planner's answer more exact. */
@Serializable
data class PlanEndpoint(
    @ProtoNumber(1) val position: LatLng,
    /** [Place.id] when this end came from a place search. */
    @ProtoNumber(2) val placeId: String? = null,
)

@Serializable
enum class PlanTimeKind { DEPART_AT, ARRIVE_BY }

@Serializable
data class PlanRequest(
    @ProtoNumber(1) val from: PlanEndpoint,
    @ProtoNumber(2) val to: PlanEndpoint,
    /** Epoch ms; null means "now" by the server's clock. */
    @ProtoNumber(3) val timeMs: Long? = null,
    @ProtoNumber(4) val timeKind: PlanTimeKind = PlanTimeKind.DEPART_AT,
    @ProtoNumber(5) val maxItineraries: Int = 5,
    /** Modes not to ride. Walking cannot be excluded. */
    @ProtoNumber(6) val excludedModes: List<Mode> = emptyList(),
)

// ---- Plan result ----

@Serializable
enum class LegKind { WALK, RIDE }

/** The start or the end of a leg. */
@Serializable
data class LegPoint(
    /** Display name without the platform: "Strathfield", "Bondi Junction"; an address for a journey's own ends. */
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val position: LatLng,
    /** GTFS stop id (platform level when known), only when it exists in the server's timetable. */
    @ProtoNumber(3) val stopId: StopId? = null,
    /** As shown to passengers: "3", "Stand B", "Wharf 3". */
    @ProtoNumber(4) val platform: String? = null,
    /** Timetabled time at this point (epoch ms): departure at a leg's start, arrival at its end. */
    @ProtoNumber(5) val scheduledMs: Long,
    /** The planner's live estimate, when it had one. */
    @ProtoNumber(6) val expectedMs: Long? = null,
)

@Serializable
data class ItineraryLeg(
    @ProtoNumber(1) val kind: LegKind,
    @ProtoNumber(2) val from: LegPoint,
    @ProtoNumber(3) val to: LegPoint,
    @ProtoNumber(4) val durationS: Int,
    @ProtoNumber(5) val distanceM: Int? = null,
    /** Encoded polyline (precision 5) of the leg's path, when the planner gives one. */
    @ProtoNumber(6) val path: String? = null,

    // ---- RIDE only ----
    @ProtoNumber(10) val mode: Mode? = null,
    /** What passengers call the line: "T9", "333", "F1". */
    @ProtoNumber(11) val routeName: String? = null,
    @ProtoNumber(12) val headsign: String? = null,
    /** `RRGGBB` without '#', from the timetable when the ride was matched. */
    @ProtoNumber(13) val routeColor: String? = null,
    /** Stops ridden: the alighting stop counts, the boarding stop does not. */
    @ProtoNumber(14) val stopCount: Int? = null,
    /** True when the planner used live data for this ride's times. */
    @ProtoNumber(15) val realtime: Boolean = false,
    @ProtoNumber(16) val cancelled: Boolean = false,
    @ProtoNumber(17) val occupancy: Occupancy = Occupancy.UNKNOWN,
    /**
     * The next three are set only when the ride was matched to a trip in the server's timetable.
     * With them the phone can show its own live data, track the vehicle and create a watch.
     * They are never guessed.
     */
    @ProtoNumber(18) val routeId: RouteId? = null,
    @ProtoNumber(19) val tripId: TripId? = null,
    @ProtoNumber(20) val serviceDate: ServiceDate? = null,
) {
    /** True when [tripId] and [serviceDate] identify a trip in the timetable. */
    val matched: Boolean get() = tripId != null && serviceDate != null
}

/** One way of making the journey. Never empty; walks and rides are in travel order. */
@Serializable
data class Itinerary(
    /** The same for the same rides at the same times, so a list can be refreshed in place. */
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val legs: List<ItineraryLeg>,
)

/** When to leave the start: live estimate when there is one. */
val Itinerary.departMs: Long get() = legs.first().from.let { it.expectedMs ?: it.scheduledMs }

/** When the journey ends: live estimate when there is one. */
val Itinerary.arriveMs: Long get() = legs.last().to.let { it.expectedMs ?: it.scheduledMs }

val Itinerary.rides: List<ItineraryLeg> get() = legs.filter { it.kind == LegKind.RIDE }

/** Number of changes between services. */
val Itinerary.changes: Int get() = (rides.size - 1).coerceAtLeast(0)

/** Total walking, in seconds. */
val Itinerary.walkSeconds: Int get() = legs.filter { it.kind == LegKind.WALK }.sumOf { it.durationS }

@Serializable
data class PlanResponse(
    @ProtoNumber(1) val serverTimeMs: Long,
    /** Best first. Empty when no journey was found. */
    @ProtoNumber(2) val itineraries: List<Itinerary> = emptyList(),
    /** Timetable version the rides were matched against. */
    @ProtoNumber(3) val timetableVersion: String = "",
    /** Modes this server has live feeds for; rides on other modes are timetabled. */
    @ProtoNumber(4) val liveModes: List<Mode> = emptyList(),
)

// ---- The front end's view of a planner ----

sealed interface PlacesResult {
    data class Found(val places: List<Place>) : PlacesResult

    /** The server could not be reached. */
    data object Offline : PlacesResult

    /** The server has no planner right now: no key, or today's quota is used up. */
    data object Unavailable : PlacesResult

    data class Failed(val message: String) : PlacesResult
}

sealed interface PlanResult {
    /** [PlanResponse.itineraries] may be empty: nothing was found. */
    data class Found(val response: PlanResponse) : PlanResult

    /** The server could not be reached. */
    data object Offline : PlanResult

    /** The server has no planner right now: no key, or today's quota is used up. */
    data object Unavailable : PlanResult

    data class Failed(val message: String) : PlanResult
}

/** Plans journeys and finds places. Implemented over tt-server; a fake exists for the front end. */
interface JourneyPlanner {
    suspend fun searchPlaces(query: String, near: LatLng? = null, limit: Int = 10): PlacesResult

    suspend fun plan(request: PlanRequest): PlanResult
}
