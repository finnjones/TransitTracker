package app.transittracker.server.app

import app.transittracker.model.LatLng
import app.transittracker.model.PlanEndpoint
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanTimeKind
import app.transittracker.model.WallClock
import app.transittracker.server.live.TfnswQuota
import app.transittracker.server.planner.HttpPlannerFetcher
import app.transittracker.server.planner.PlannerEndpoint
import app.transittracker.server.planner.PlannerFetch
import app.transittracker.server.planner.PlannerFetcher
import app.transittracker.server.planner.TripPlannerRequests
import app.transittracker.server.spi.ServerConfig
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.ZoneId

internal const val PLANNER_CAPTURE_USAGE = """  planner-capture places <text> <out.json>
  planner-capture plan <lat,lon> <lat,lon> [--arrive-by] [--at YYYY-MM-DDTHH:MM] <out.json>
                                            One real TfNSW Trip Planner call with the server's key
                                            (counted in today's budget); saves the raw JSON answer
                                            (no headers, no key) for replay (TT_REPLAY_DIR/planner/)
                                            or for checking the mapping. Sydney local time."""

/**
 * `tt-admin planner-capture`: makes one real `/v1/tp/stop_finder` or `/v1/tp/trip` call with the
 * server's key, exactly as tt-server would, and writes TfNSW's raw answer body to a file. Nothing
 * else is written: no request, no headers, no key. Returns the exit code.
 */
internal fun plannerCapture(
    args: List<String>,
    config: ServerConfig,
    out: PrintStream,
    err: PrintStream,
    clock: WallClock,
    fetcher: PlannerFetcher = HttpPlannerFetcher(config),
): Int {
    val usage = { message: String -> err.println("tt-admin: $message"); err.println(PLANNER_CAPTURE_USAGE); 2 }
    val kind = args.firstOrNull() ?: return usage("planner-capture needs 'places' or 'plan'")
    val (endpoint, params, file) = when (kind) {
        "places" -> {
            if (args.size != 3) return usage("planner-capture places needs <text> <out.json>")
            Triple(PlannerEndpoint.STOP_FINDER, TripPlannerRequests.stopFinder(args[1].trim()), args[2])
        }
        "plan" -> {
            val positional = ArrayList<String>()
            var arriveBy = false
            var at: Long? = null
            var i = 1
            while (i < args.size) {
                when (val a = args[i]) {
                    "--arrive-by" -> arriveBy = true
                    "--at" -> {
                        val value = args.getOrNull(i + 1) ?: return usage("--at needs a value")
                        at = runCatching { LocalDateTime.parse(value).atZone(ZoneId.of("Australia/Sydney")).toInstant().toEpochMilli() }
                            .getOrElse { return usage("--at must look like 2026-11-11T08:30") }
                        i++
                    }
                    else -> positional += a
                }
                i++
            }
            if (positional.size != 3) return usage("planner-capture plan needs <lat,lon> <lat,lon> <out.json>")
            val from = latLng(positional[0]) ?: return usage("bad position '${positional[0]}' (expected lat,lon)")
            val to = latLng(positional[1]) ?: return usage("bad position '${positional[1]}' (expected lat,lon)")
            val request = PlanRequest(
                from = PlanEndpoint(from),
                to = PlanEndpoint(to),
                timeKind = if (arriveBy) PlanTimeKind.ARRIVE_BY else PlanTimeKind.DEPART_AT,
            )
            Triple(PlannerEndpoint.TRIP, TripPlannerRequests.trip(request, at ?: clock.nowMillis()), positional[2])
        }
        else -> return usage("unknown planner-capture kind '$kind'")
    }
    if (!fetcher.configured) {
        err.println("tt-admin: TFNSW_API_KEY is not set; nothing to capture")
        return 1
    }
    if (fetcher.isRemote && !runBlocking { TfnswQuota.fromConfig(config, clock).acquire() }) {
        err.println("tt-admin: today's TfNSW call budget is used up")
        return 1
    }
    return when (val result = runBlocking { fetcher.fetch(endpoint, params) }) {
        is PlannerFetch.Success -> {
            val target = Paths.get(file)
            target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.writeString(target, result.body)
            out.println("Saved TfNSW's ${endpoint.path} answer (${result.body.length} characters) to ${target.toAbsolutePath()}")
            out.println("It holds the places you asked about: keep it private, or replace them before sharing.")
            0
        }
        is PlannerFetch.HttpError -> {
            err.println("tt-admin: TfNSW answered HTTP ${result.status}: ${result.detail.take(200)}")
            1
        }
        is PlannerFetch.NetworkError -> {
            err.println("tt-admin: no answer from TfNSW (${result.detail})")
            1
        }
        is PlannerFetch.NotConfigured -> {
            err.println("tt-admin: ${result.reason}")
            1
        }
    }
}

private fun latLng(text: String): LatLng? {
    val parts = text.split(',').map { it.trim().toDoubleOrNull() }
    if (parts.size != 2 || parts.any { it == null }) return null
    return LatLng(parts[0]!!, parts[1]!!)
}
