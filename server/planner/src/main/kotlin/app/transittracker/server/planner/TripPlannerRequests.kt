package app.transittracker.server.planner

import app.transittracker.model.PlanEndpoint
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanTimeKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Query parameters of the two Trip Planner calls. Names and values are from the Trip Planner
 * Swagger `tripplanner_v1_swag_efa11_20251002.yml` [S] (operations `/stop_finder` and `/trip`), as
 * KRAIL also sends them [K]. The optional `version` parameter is not sent, so TfNSW answers in its
 * current response version. Nothing identifies the device.
 */
object TripPlannerRequests {
    private val SYDNEY: ZoneId = ZoneId.of("Australia/Sydney")

    /** `/v1/tp/stop_finder`: any kind of location matching [text]. */
    fun stopFinder(text: String): List<Pair<String, String>> = listOf(
        "outputFormat" to "rapidJSON",
        "type_sf" to "any",
        "name_sf" to text,
        "coordOutputFormat" to "EPSG:4326",
        "TfNSWSF" to "true",
    )

    /**
     * `/v1/tp/trip` for [request] at [timeMs] (Sydney local `itdDate` / `itdTime`). An end with a
     * place id is sent as `type_*=any` + the id; otherwise as a coordinate
     * `LONGITUDE:LATITUDE:EPSG:4326` (longitude first) with `type_*=coord` [S]. Excluded modes use
     * `excludedMeans=checkbox` + `exclMOT_<class>=1` [S]; `excludedMeans` is never sent alone (KRAIL:
     * that silently drops trains) [K]. `calcNumberOfTrips` is always 6 (the Swagger default and our
     * maximum), so one cached answer serves every `maxItineraries`.
     */
    fun trip(request: PlanRequest, timeMs: Long): List<Pair<String, String>> {
        val local = Instant.ofEpochMilli(timeMs).atZone(SYDNEY)
        val params = ArrayList<Pair<String, String>>()
        params += "outputFormat" to "rapidJSON"
        params += "coordOutputFormat" to "EPSG:4326"
        params += "depArrMacro" to if (request.timeKind == PlanTimeKind.ARRIVE_BY) "arr" else "dep"
        params += "itdDate" to local.format(DateTimeFormatter.BASIC_ISO_DATE).take(8)
        params += "itdTime" to "%02d%02d".format(Locale.ROOT, local.hour, local.minute)
        params += end("origin", request.from)
        params += end("destination", request.to)
        params += "calcNumberOfTrips" to "6"
        params += "TfNSWTR" to "true"
        val excluded = request.excludedModes.flatMap(ProductClasses::excludedFor).distinct().sorted()
        if (excluded.isNotEmpty()) {
            params += "excludedMeans" to "checkbox"
            excluded.forEach { params += "exclMOT_$it" to "1" }
        }
        return params
    }

    private fun end(which: String, end: PlanEndpoint): List<Pair<String, String>> {
        val placeId = end.placeId
        return if (placeId != null) {
            listOf("type_$which" to "any", "name_$which" to placeId)
        } else {
            listOf("type_$which" to "coord", "name_$which" to "%.6f:%.6f:EPSG:4326".format(Locale.ROOT, end.position.lon, end.position.lat))
        }
    }
}
