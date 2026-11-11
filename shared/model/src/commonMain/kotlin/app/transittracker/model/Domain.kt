package app.transittracker.model

import kotlinx.serialization.Serializable

/** GTFS identifiers are kept as the raw TfNSW strings everywhere (wire, storage, logs). */
typealias StopId = String
typealias RouteId = String
typealias TripId = String
typealias ShapeId = String
typealias ServiceId = String

/** GTFS service date, always formatted `YYYYMMDD` (the GTFS-R `start_date` format). */
typealias ServiceDate = String

@Serializable
enum class Mode { TRAIN, METRO, LIGHT_RAIL, BUS, FERRY, COACH, OTHER }

@Serializable
data class LatLng(val lat: Double, val lon: Double)

@Serializable
data class Stop(
    val id: StopId,
    val name: String,
    /** Parent station id for platform-level stops; null for stations / standalone stops. */
    val parentId: StopId? = null,
    val position: LatLng,
    val platformCode: String? = null,
    val modes: Set<Mode> = emptySet(),
    val wheelchairAccessible: Boolean? = null,
)

@Serializable
data class Route(
    val id: RouteId,
    val shortName: String,
    val longName: String,
    val mode: Mode,
    /** `RRGGBB` without '#', as in GTFS. */
    val color: String? = null,
    val textColor: String? = null,
    val agencyId: String? = null,
)

/** Mirrors GTFS-R OccupancyStatus. */
@Serializable
enum class Occupancy {
    UNKNOWN, EMPTY, MANY_SEATS_AVAILABLE, FEW_SEATS_AVAILABLE, STANDING_ROOM_ONLY,
    CRUSHED_STANDING_ROOM_ONLY, FULL, NOT_ACCEPTING_PASSENGERS,
}

/** Wall clock abstraction so every module can be tested with a fixed time. */
fun interface WallClock {
    fun nowMillis(): Long
}
