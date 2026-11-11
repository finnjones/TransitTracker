package app.transittracker.timetable

import app.transittracker.model.Mode
import app.transittracker.model.ServiceDate

/** Keys of the `meta` table. */
object PackMeta {
    /** Bump when the pack schema or a blob format changes incompatibly. */
    const val CURRENT_SCHEMA_VERSION: Int = 1

    const val SCHEMA_VERSION = "schema_version"
    const val NAME = "name"
    const val VERSION = "version"
    /** First and last service date (YYYYMMDD) the pack has service-day data for. */
    const val VALID_FROM = "valid_from"
    const val VALID_TO = "valid_to"
    /** Build date (YYYYMMDD, Sydney). */
    const val BUILD_DATE = "build_date"
    /** Comma-separated `Mode` names. */
    const val MODES = "modes"
    /** Newline-separated `source=feed_version` lines describing the input GTFS bundles. */
    const val SOURCES = "sources"
    /** Longest trip duration in seconds (first arrival to last departure); bounds departure queries. */
    const val MAX_TRIP_SECS = "max_trip_secs"
    const val TIME_ZONE = "time_zone"
}

/** GTFS `pickup_type` / `drop_off_type` value meaning "not available". */
const val GTFS_NO_PICKUP_OR_DROP_OFF: Int = 1

/**
 * A distinct ordered list of stops a route runs, with per-stop GTFS stop sequences and
 * pickup/drop-off types. Trips sharing all of these share one pattern.
 *
 * @property stopIds pack-internal stop ids in calling order.
 * @property stopSequences GTFS `stop_sequence` per position (needed to match GTFS-R updates).
 * @property pickupTypes GTFS `pickup_type` per position (0 regular, 1 none, 2 phone, 3 driver).
 * @property dropOffTypes GTFS `drop_off_type` per position.
 */
class Pattern(
    val id: Int,
    val routeId: Int,
    val stopIds: IntArray,
    val stopSequences: IntArray = IntArray(stopIds.size) { it + 1 },
    val pickupTypes: IntArray = IntArray(stopIds.size),
    val dropOffTypes: IntArray = IntArray(stopIds.size),
) {
    init {
        require(stopSequences.size == stopIds.size && pickupTypes.size == stopIds.size && dropOffTypes.size == stopIds.size) {
            "Pattern $id arrays differ in length"
        }
    }

    val size: Int get() = stopIds.size

    fun pickupAllowed(position: Int): Boolean = pickupTypes[position] != GTFS_NO_PICKUP_OR_DROP_OFF

    fun dropOffAllowed(position: Int): Boolean = dropOffTypes[position] != GTFS_NO_PICKUP_OR_DROP_OFF
}

/**
 * Stop-by-stop times shared by trips, as offsets in seconds from the trip's start (the arrival
 * at the first stop, so `arrivals[0] == 0`).
 */
class TimeProfile(val id: Int, val arrivals: IntArray, val departures: IntArray) {
    init {
        require(arrivals.size == departures.size) { "Profile $id arrays differ in length" }
    }

    val size: Int get() = arrivals.size

    /** Seconds from first arrival to last departure. */
    val durationSecs: Int get() = if (departures.isEmpty()) 0 else departures.last()
}

/** A stop row to be written into a pack. */
data class PackStop(
    val id: Int,
    val gtfsId: String,
    val parentId: Int?,
    val name: String,
    val lat: Double,
    val lon: Double,
    val platformCode: String?,
    /** GTFS location_type: 0 stop/platform, 1 station. */
    val locationType: Int,
    val modes: Set<Mode>,
    /** GTFS wheelchair_boarding after inheritance: 0 unknown, 1 yes, 2 no. */
    val wheelchair: Int,
)

/** A route row to be written into a pack. */
data class PackRoute(
    val id: Int,
    val gtfsId: String,
    val agencyId: String?,
    val shortName: String,
    val longName: String,
    val routeType: Int,
    val mode: Mode,
    val color: String?,
    val textColor: String?,
    val sortOrder: Int?,
)

/** A service calendar flattened to an active-day bitset starting at [startDate]. */
class PackService(val id: Int, val gtfsId: String, val startDate: ServiceDate, val days: BooleanArray)

/** A trip row: pattern + time profile + start time. */
data class PackTrip(
    val id: Int,
    val gtfsId: String,
    val patternId: Int,
    val profileId: Int,
    /** GTFS seconds of the arrival at the first stop. */
    val startSecs: Int,
    val serviceId: Int,
    val headsign: String,
    val directionId: Int?,
    val shapeId: Int?,
    /** GTFS wheelchair_accessible: 0 unknown, 1 yes, 2 no. */
    val wheelchair: Int = 0,
    /** GTFS bikes_allowed: 0 unknown, 1 yes, 2 no. */
    val bikes: Int = 0,
)

data class PackTransfer(val fromStopId: Int, val toStopId: Int, val transferType: Int, val minTransferSecs: Int)

/** A shape stored as a Google encoded polyline (precision 5), already simplified. */
data class PackShape(val id: Int, val gtfsId: String, val polyline: String)

/** Everything that goes into one pack file. Rows are written in the order given. */
class PackContent(
    val meta: Map<String, String>,
    val stops: List<PackStop>,
    val routes: List<PackRoute>,
    val patterns: List<Pattern>,
    val profiles: List<TimeProfile>,
    val services: List<PackService>,
    val trips: List<PackTrip>,
    val transfers: List<PackTransfer> = emptyList(),
    val shapes: List<PackShape> = emptyList(),
)
