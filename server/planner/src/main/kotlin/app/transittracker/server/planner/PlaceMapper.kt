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

    /** TfNSW's order (best match first); with [near], nearer bands first. Then [limit]. */
    fun rank(places: List<RankedPlace>, near: LatLng?, limit: Int): List<Place> {
        val byQuality = compareByDescending<RankedPlace> { it.isBest }.thenByDescending { it.matchQuality }.thenBy { it.order }
        val sorted = if (near == null) {
            places.sortedWith(byQuality)
        } else {
            places.sortedWith(compareBy<RankedPlace> { band(Geo.distanceMetres(near, it.place.position)) }.then(byQuality))
        }
        return sorted.take(limit).map { it.place }
    }

    private fun band(metres: Double): Int = BANDS.indexOfFirst { metres <= it }.let { if (it < 0) BANDS.size else it }
}
