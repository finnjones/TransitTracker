package app.transittracker.server.planner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The parts of TfNSW Trip Planner (EFA "rapidJSON") responses that tt-server reads.
 *
 * Sources for the field names, in order of authority:
 *  [S] Trip Planner Swagger `tripplanner_v1_swag_efa11_20251002.yml` (Open Data Hub, "Trip Planner
 *      APIs", dated 2 Oct 2025, after the 27 Aug 2025 upgrade): definitions StopFinderResponse,
 *      StopFinderLocation, TripRequestResponse, TripRequestResponseJourneyLeg(Stop), TripTransportation,
 *      RouteProduct, TripRequestResponseJourneyLegStopFootpathInfo, ...Interchange.
 *  [K] KRAIL (open-source Sydney transit app): its client models and a real `/v1/tp/trip` response
 *      captured in 2026 (`trip_seven_hills_redfern_715_backtrack.json`, API version 10.6.21.17).
 *      Fields the Swagger does not list come from there and are marked [K].
 *
 * NOT VERIFIED against the live API from this repository (no key here): see
 * testdata/planner/README.md for what must be checked once a key is available. Every field is
 * optional and unknown fields are ignored, so a renamed field degrades to "missing", never to a crash.
 */

/** Lenient: TfNSW sends some string fields as bare numbers [K]. */
internal val TfnswJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

// ---- stop_finder ----

@Serializable
internal data class StopFinderResponseDto(
    val version: String? = null,
    val locations: List<StopFinderLocationDto> = emptyList(),
    val error: ApiErrorDto? = null,
)

@Serializable
internal data class StopFinderLocationDto(
    val id: String? = null,
    val isGlobalId: Boolean? = null,
    val name: String? = null,
    val disassembledName: String? = null,
    /** [lat, lon] with `coordOutputFormat=EPSG:4326` [S]. */
    val coord: List<Double>? = null,
    /** poi, singlehouse, stop, platform, street, locality, suburb, address, unknown [S]. */
    val type: String? = null,
    val matchQuality: Int? = null,
    val isBest: Boolean? = null,
    val parent: ParentDto? = null,
    /** Product classes serving a stop: [S] calls it `modes`, KRAIL reads `productClasses` [K]. */
    val modes: List<Int>? = null,
    val productClasses: List<Int>? = null,
    val properties: JsonObject? = null,
)

@Serializable
internal data class ParentDto(
    val id: String? = null,
    val name: String? = null,
    val disassembledName: String? = null,
    val type: String? = null,
    val parent: ParentDto? = null,
    /** `stopId`: the legacy stop id, e.g. "10101100" [K]. */
    val properties: JsonObject? = null,
)

@Serializable
internal data class ApiErrorDto(val message: String? = null)

// ---- trip ----

@Serializable
internal data class TripResponseDto(
    val version: String? = null,
    val journeys: List<JourneyDto>? = null,
    val error: ApiErrorDto? = null,
    /** An object with `responseMessages` in [S], a list in [K]; only logged as a count. */
    val systemMessages: JsonElement? = null,
)

@Serializable
internal data class JourneyDto(
    val legs: List<LegDto> = emptyList(),
    val interchanges: Int? = null,
)

@Serializable
internal data class LegDto(
    /** Seconds [S]. */
    val duration: Long? = null,
    /** Metres [S]. */
    val distance: Long? = null,
    val origin: LegStopDto? = null,
    val destination: LegStopDto? = null,
    val transportation: TransportationDto? = null,
    val stopSequence: List<LegStopDto>? = null,
    /** [[lat, lon], ...] [S]. */
    val coords: List<List<Double>>? = null,
    val footPathInfo: List<FootPathInfoDto>? = null,
    /** True when [footPathInfo] repeats a walk that is also its own leg [K]. */
    val footPathInfoRedundant: Boolean? = null,
    val interchange: InterchangeDto? = null,
    val isRealtimeControlled: Boolean? = null,
    /** e.g. ["MONITORED"] [K]; a cancelled trip is assumed to carry "TRIP_CANCELLED" (unverified). */
    val realtimeStatus: List<String>? = null,
    /** Some EFA versions flag cancellation here (unverified). */
    val isCancelled: Boolean? = null,
)

@Serializable
internal data class LegStopDto(
    val id: String? = null,
    val isGlobalId: Boolean? = null,
    val name: String? = null,
    val disassembledName: String? = null,
    /** poi, singlehouse, stop, platform, street, locality, suburb, unknown [S]. */
    val type: String? = null,
    val coord: List<Double>? = null,
    val parent: ParentDto? = null,
    /** `YYYY-MM-DDTHH:MM:SSZ` [S]. */
    val departureTimePlanned: String? = null,
    val departureTimeEstimated: String? = null,
    /** The base timetable time [K]. */
    val departureTimeBaseTimetable: String? = null,
    val arrivalTimePlanned: String? = null,
    val arrivalTimeEstimated: String? = null,
    val arrivalTimeBaseTimetable: String? = null,
    /** `platform` ("SVH2"), `platformName` ("Platform 2"), `plannedPlatformName`, `occupancy` ("MANY_SEATS") [K]. */
    val properties: JsonObject? = null,
)

@Serializable
internal data class TransportationDto(
    val id: String? = null,
    val name: String? = null,
    /** The short line name passengers see: "T1", "715" [S][K]. */
    val disassembledName: String? = null,
    val number: String? = null,
    val description: String? = null,
    val iconId: Int? = null,
    val product: ProductDto? = null,
    /** Where vehicles on this route terminate: the headsign [S]. */
    val destination: NamedDto? = null,
    /** `RealtimeTripId`, `AVMSTripID`, `gtfsTripId` (planner-internal) [K]. */
    val properties: JsonObject? = null,
)

@Serializable
internal data class ProductDto(
    /** 1 train, 2 metro, 4 light rail, 5 bus, 7 coach, 9 ferry, 11 school bus, 99/100 walking [S];
     *  101 bicycle ... 105 taxi, 106 car [K]. */
    @SerialName("class") val productClass: Int? = null,
    val name: String? = null,
    val iconId: Int? = null,
)

@Serializable
internal data class NamedDto(val id: String? = null, val name: String? = null)

@Serializable
internal data class FootPathInfoDto(
    /** Seconds [S]. */
    val duration: Long? = null,
    /** BEFORE, AFTER or IDEST (the whole leg is the walk) [S]. */
    val position: String? = null,
)

@Serializable
internal data class InterchangeDto(
    val desc: String? = null,
    /** 99 or 100: walking [S]. */
    val type: Int? = null,
    val coords: List<List<Double>>? = null,
)

/** A string property, whether TfNSW sent it as a string or a bare number. */
internal fun JsonObject?.string(key: String): String? {
    val value = this?.get(key) as? JsonPrimitive ?: return null
    return value.content.takeIf { it.isNotBlank() && it != "null" }
}
