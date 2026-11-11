package app.transittracker.server.planner

import app.transittracker.model.LatLng
import app.transittracker.model.Place
import app.transittracker.model.PlaceKind
import app.transittracker.model.Timetable

/**
 * Turns `/v1/tp/stop_finder` locations into [Place]s, and ranks them.
 *
 * Location `type` values are from the Swagger's StopFinderLocation [S]; `unknown` "likely indicates
 * bad data ... you can safely ignore" it [S], so it is dropped, as are locations without a position.
 */
internal class PlaceMapper(private val timetable: Timetable?) {

    fun map(response: StopFinderResponseDto): List<RankedPlace> =
        response.locations.mapIndexedNotNull { index, location -> map(location)?.let { RankedPlace(it, location.matchQuality ?: 0, location.isBest == true, index) } }
            .distinctBy { it.place.id }

    private fun map(location: StopFinderLocationDto): Place? {
        val id = location.id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val kind = when (location.type?.lowercase()) {
            "stop", "platform" -> PlaceKind.STOP
            "singlehouse", "street", "address" -> PlaceKind.ADDRESS
            "poi" -> PlaceKind.POI
            "locality", "suburb" -> PlaceKind.LOCALITY
            "unknown", null -> return null
            else -> PlaceKind.OTHER
        }
        val position = TripMapper.latLng(location.coord) ?: return null
        // Places keep " Station" ("Strathfield Station" in the contract); only the platform goes.
        val name = (if (kind == PlaceKind.STOP) Names.withoutPlatform(location.disassembledName ?: location.name) else null)
            ?: Names.placeName(location.disassembledName, location.name) ?: return null
        return Place(
            id = id,
            kind = kind,
            name = name,
            detail = detail(location, name),
            position = position,
            stopId = if (kind == PlaceKind.STOP) stopId(location) else null,
            modes = (location.modes ?: location.productClasses).orEmpty().mapNotNull(ProductClasses::mode).distinct(),
        )
    }

    /** The suburb: the parent locality, else what follows the name in the long form. */
    private fun detail(location: StopFinderLocationDto, name: String): String {
        location.parent?.takeIf { it.type == "locality" || it.type == "suburb" }?.name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val long = location.name ?: return ""
        val rest = long.split(',').map { it.trim() }.filter { it.isNotEmpty() }.drop(1)
        return rest.lastOrNull()?.takeIf { it != name } ?: ""
    }

    private fun stopId(location: StopFinderLocationDto): String? {
        val tt = timetable ?: return null
        return listOfNotNull(location.id, location.properties.string("stopId"), location.properties.string("stopId_global"))
            .firstOrNull { tt.stop(it) != null }
    }
}

/** A place with what ranking needs. */
internal data class RankedPlace(val place: Place, val matchQuality: Int, val isBest: Boolean, val order: Int)

internal object PlaceRanking {
    /** Distance bands in metres: within a band, TfNSW's own match quality decides. */
    private val BANDS = doubleArrayOf(2_000.0, 10_000.0, 50_000.0)
    private val WORD = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Ranks in three tiers, then by distance band ([near]) and TfNSW's `matchQuality` and order:
     *
     * 1. TfNSW's best match (`isBest`);
     * 2. stops whose name has every word searched ("strathfield" -> Strathfield Station, North
     *    Strathfield Station), so a station-like search finds stations first;
     * 3. everything else.
     *
     * Checked on the live API (11 Nov 2026): stop_finder lists locations alphabetically within each
     * type, scores stops ~980 and streets, suburbs and POIs ~240, and flags one location `isBest`.
     * Ranking by distance first put "Strathfield College, Haymarket" above Strathfield Station for a
     * phone in the CBD.
     */
    fun rank(places: List<RankedPlace>, query: String, near: LatLng?, limit: Int): List<Place> {
        val words = words(query)
        val tier = { p: RankedPlace ->
            when {
                p.isBest -> 0
                p.place.kind == PlaceKind.STOP && words.isNotEmpty() && words(p.place.name).let { name -> words.all { w -> name.any { it.startsWith(w) } } } -> 1
                else -> 2
            }
        }
        val byQuality = compareByDescending<RankedPlace> { it.matchQuality }.thenBy { it.order }
        val inTier = if (near == null) byQuality else compareBy<RankedPlace> { band(Geo.distanceMetres(near, it.place.position)) }.then(byQuality)
        return places.sortedWith(compareBy(tier).then(inTier)).take(limit).map { it.place }
    }

    private fun words(text: String): List<String> = text.lowercase().split(WORD).filter { it.isNotEmpty() }

    private fun band(metres: Double): Int = BANDS.indexOfFirst { metres <= it }.let { if (it < 0) BANDS.size else it }
}

/**
 * Turns a `/v1/tp/coord` answer (`type_1=STOP`) into [PlaceKind.STOP] places, nearest first.
 *
 * Checked on the live API (11 Nov 2026, see `testdata/planner/live/coord-central.json`): every
 * location is a `stop` whose `name` is "undefined, undefined"; the real name is the property
 * `STOP_NAME_WITH_PLACE` ("Central Station, Sydney", "Carrington Rd at Coogee St, Randwick"), the
 * walking-free distance in metres is the property `distance`, and `productClasses` lists what calls
 * there. Ids are TfNSW global stop ids: stations as their GTFS id ("200060"), many bus stops with a
 * `G` in front ("G203199"). Stops served only by classes the app does not show (on-demand) are
 * dropped.
 */
internal class NearStopMapper(private val timetable: Timetable?) {

    fun map(response: StopFinderResponseDto, limit: Int): List<Place> =
        response.locations.mapNotNull { location -> map(location)?.let { it to (location.properties.string("distance")?.toDoubleOrNull() ?: Double.MAX_VALUE) } }
            .distinctBy { it.first.id }
            .sortedWith(compareBy({ it.second }, { it.first.id }))
            .take(limit)
            .map { it.first }

    private fun map(location: StopFinderLocationDto): Place? {
        if (location.type?.lowercase() !in STOP_TYPES) return null
        val id = location.id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val position = TripMapper.latLng(location.coord) ?: return null
        val modes = (location.productClasses ?: location.modes).orEmpty().mapNotNull(ProductClasses::mode).distinct()
        if (modes.isEmpty()) return null
        val long = listOfNotNull(location.properties.string("STOP_NAME_WITH_PLACE"), location.name, location.disassembledName)
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("undefined") }
            ?: return null
        val parts = long.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val name = Names.withoutPlatform(parts.first()) ?: parts.first()
        return Place(
            id = id,
            kind = PlaceKind.STOP,
            name = name,
            detail = parts.drop(1).lastOrNull()?.takeIf { it != name }.orEmpty(),
            position = position,
            stopId = timetable?.let { tt ->
                listOfNotNull(id, id.removePrefix("G"), location.properties.string("stopId")).firstOrNull { tt.stop(it) != null }
            },
            modes = modes,
        )
    }

    private companion object {
        val STOP_TYPES = setOf("stop", "platform")
    }
}
