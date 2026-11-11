package app.transittracker.server.planner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/**
 * Answers Trip Planner calls from JSON files instead of calling TfNSW, for tests and for running
 * tt-server locally without an API key (`TT_REPLAY_DIR`; planner files go in its `planner/`
 * subdirectory, see `testdata/planner/README.md`):
 *
 * - `stop_finder-<query>.json`: the answer for one search (`<query>` lower-cased, runs of anything
 *   but letters and digits replaced by `-`, e.g. `stop_finder-central.json`); else
 * - `stop_finder.json`: every search, keeping only locations whose name contains each word searched;
 * - `coord.json`: every stops-near-a-position request (the same answer wherever is asked);
 * - `trip.json`: every journey request. Its times are moved by whole weeks to the week of the
 *   requested date, so a recorded weekday journey stays on the same weekday and time of day.
 *
 * Files are raw `/v1/tp/stop_finder`, `/v1/tp/coord` and `/v1/tp/trip` bodies, as saved by
 * `tt-admin planner-capture`. Replay calls are not counted against the TfNSW budget.
 */
class ReplayPlannerFetcher(private val dir: Path) : PlannerFetcher {
    override val isRemote: Boolean get() = false

    override val configured: Boolean
        get() = Files.isRegularFile(dir.resolve("trip.json")) || Files.isRegularFile(dir.resolve("stop_finder.json"))

    override suspend fun fetch(endpoint: PlannerEndpoint, params: List<Pair<String, String>>): PlannerFetch {
        val values = params.toMap()
        return withContext(Dispatchers.IO) {
            when (endpoint) {
                PlannerEndpoint.STOP_FINDER -> stopFinder(values["name_sf"].orEmpty())
                PlannerEndpoint.TRIP -> trip(values["itdDate"])
                PlannerEndpoint.COORD -> coord()
            }
        }
    }

    private fun stopFinder(query: String): PlannerFetch {
        val specific = dir.resolve("stop_finder-${slug(query)}.json")
        if (Files.isRegularFile(specific)) return PlannerFetch.Success(Files.readString(specific))
        val all = dir.resolve("stop_finder.json")
        if (!Files.isRegularFile(all)) return PlannerFetch.NotConfigured("no stop_finder.json under $dir")
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val root = TfnswJson.parseToJsonElement(Files.readString(all)).jsonObject
        val locations = (root["locations"] as? JsonArray).orEmpty().filter { location ->
            val o = location as? JsonObject ?: return@filter false
            val text = listOf("name", "disassembledName").joinToString(" ") { (o[it] as? JsonPrimitive)?.content.orEmpty() }.lowercase()
            words.all { it in text }
        }
        return PlannerFetch.Success(JsonObject(root + ("locations" to JsonArray(locations))).toString())
    }

    private fun coord(): PlannerFetch {
        val file = dir.resolve("coord.json")
        if (!Files.isRegularFile(file)) return PlannerFetch.NotConfigured("no coord.json under $dir")
        return PlannerFetch.Success(Files.readString(file))
    }

    private fun trip(itdDate: String?): PlannerFetch {
        val file = dir.resolve("trip.json")
        if (!Files.isRegularFile(file)) return PlannerFetch.NotConfigured("no trip.json under $dir")
        val body = Files.readString(file)
        val requested = itdDate?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() }
            ?: return PlannerFetch.Success(body)
        val first = INSTANT.find(body)?.groupValues?.get(1)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return PlannerFetch.Success(body)
        val recorded = first.atZone(SYDNEY).toLocalDate()
        val weeks = (ChronoUnit.DAYS.between(recorded, requested) / 7.0).roundToLong()
        if (weeks == 0L) return PlannerFetch.Success(body)
        val shifted = INSTANT.replace(body) { match ->
            val instant = runCatching { Instant.parse(match.groupValues[1]) }.getOrNull() ?: return@replace match.value
            "\"" + instant.atZone(SYDNEY).plusWeeks(weeks).toInstant().toString() + "\""
        }
        return PlannerFetch.Success(shifted)
    }

    companion object {
        private val SYDNEY: ZoneId = ZoneId.of("Australia/Sydney")
        private val INSTANT = Regex("\"(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z)\"")

        /** File-name form of a search: "Central Stn!" -> "central-stn". */
        fun slug(query: String): String =
            query.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(60)
    }
}
