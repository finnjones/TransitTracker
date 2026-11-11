package app.transittracker.server.spi

import app.transittracker.model.Mode
import java.nio.file.Path
import java.nio.file.Paths

/**
 * All tt-server settings, read from environment variables (see deploy/.env.example).
 * Secrets (TfNSW key, Firebase service account) only ever come from the environment / files
 * on the server, never from the repo.
 */
data class ServerConfig(
    val port: Int = 8080,
    /** Public base URL friends' phones use, e.g. https://tt.example-tailnet.ts.net */
    val publicBaseUrl: String = "http://localhost:8080",
    val dataDir: Path = Paths.get("data"),
    val tfnswApiKey: String? = null,
    val tfnswBaseUrl: String = "https://api.transport.nsw.gov.au",
    val pollIntervalS: Int = 30,
    val trackerPollIntervalS: Int = 15,
    /** Stop calling TfNSW for the rest of the (Sydney) day after this many calls. */
    val dailyCallBudget: Int = 50_000,
    /** TfNSW allows 5/s; stay under it. */
    val maxCallsPerSecond: Int = 4,
    val firebaseServiceAccountPath: Path? = null,
    val firebaseProjectId: String? = null,
    val attribution: String = "Contains Transport for NSW data",
    val statusMessage: String? = null,
    val minAppVersion: String = "0",
    /** Local hour (Australia/Sydney) for the nightly timetable check. */
    val nightlyBuildHour: Int = 3,
    val packs: List<PackDefinition> = DefaultSources.packs,
    val feeds: List<FeedDefinition> = DefaultSources.feeds,
    /** Admin token for /status and tt-admin over HTTP; null = /status only from localhost. */
    val adminToken: String? = null,
    /** Save a raw copy of each live feed at most this often (for replay tests); 0 disables recording. */
    val recordIntervalMinutes: Int = 10,
    /** Days of raw feed recordings to keep. */
    val recordRetentionDays: Int = 7,
) {
    val dbPath: Path get() = dataDir.resolve("tt-server.db")
    val packsDir: Path get() = dataDir.resolve("packs")
    val gtfsArchiveDir: Path get() = dataDir.resolve("gtfs")
    val recordingsDir: Path get() = dataDir.resolve("recordings")

    companion object {
        fun fromEnv(environment: Map<String, String> = System.getenv()): ServerConfig {
            // Blank values (e.g. `TT_PORT=` left empty in .env) mean "use the default".
            val env = environment.filterValues { it.isNotBlank() }
            val d = ServerConfig()
            return ServerConfig(
                port = env["TT_PORT"]?.toInt() ?: d.port,
                publicBaseUrl = env["TT_PUBLIC_BASE_URL"] ?: d.publicBaseUrl,
                dataDir = env["TT_DATA_DIR"]?.let { Paths.get(it) } ?: d.dataDir,
                tfnswApiKey = env["TFNSW_API_KEY"]?.takeIf { it.isNotBlank() },
                tfnswBaseUrl = env["TFNSW_BASE_URL"] ?: d.tfnswBaseUrl,
                pollIntervalS = env["TT_POLL_INTERVAL_S"]?.toInt() ?: d.pollIntervalS,
                trackerPollIntervalS = env["TT_TRACKER_POLL_INTERVAL_S"]?.toInt() ?: d.trackerPollIntervalS,
                dailyCallBudget = env["TT_DAILY_CALL_BUDGET"]?.toInt() ?: d.dailyCallBudget,
                maxCallsPerSecond = env["TT_MAX_CALLS_PER_SECOND"]?.toInt() ?: d.maxCallsPerSecond,
                firebaseServiceAccountPath = env["FIREBASE_SERVICE_ACCOUNT"]?.takeIf { it.isNotBlank() }?.let { Paths.get(it) },
                firebaseProjectId = env["FIREBASE_PROJECT_ID"]?.takeIf { it.isNotBlank() },
                attribution = env["TT_ATTRIBUTION"] ?: d.attribution,
                statusMessage = env["TT_STATUS_MESSAGE"]?.takeIf { it.isNotBlank() },
                minAppVersion = env["TT_MIN_APP_VERSION"] ?: d.minAppVersion,
                nightlyBuildHour = env["TT_NIGHTLY_BUILD_HOUR"]?.toInt() ?: d.nightlyBuildHour,
                adminToken = env["TT_ADMIN_TOKEN"]?.takeIf { it.isNotBlank() },
                recordIntervalMinutes = env["TT_RECORD_INTERVAL_MIN"]?.toInt() ?: d.recordIntervalMinutes,
                recordRetentionDays = env["TT_RECORD_RETENTION_DAYS"]?.toInt() ?: d.recordRetentionDays,
            )
        }
    }
}

/** A timetable pack built from one or more TfNSW GTFS static bundles. */
data class PackDefinition(
    val name: String,
    val modes: Set<Mode>,
    /** API paths of GTFS static bundles, relative to [ServerConfig.tfnswBaseUrl]. */
    val gtfsPaths: List<String>,
)

/** A group of GTFS-R feeds for one mode / operator. Paths are relative to the TfNSW base URL. */
data class FeedDefinition(
    val id: String,
    val mode: Mode,
    val tripUpdatesPath: String?,
    val vehiclePositionsPath: String?,
    val alertsPath: String?,
    /** Minimum age before re-fetching while a phone is looking (SCREEN / TRACKING demand). */
    val minAgeOnScreenS: Int = 15,
    /** Minimum age while only watches need it (WATCH demand). */
    val minAgeWatchS: Int = 30,
    val minAgeAlertsS: Int = 60,
)

/**
 * Release 1 sources. PATHS MUST BE VERIFIED against the TfNSW developer portal and the
 * TfNSW GTFS/GTFS-R Implementation Specification v2 before relying on them; they are config,
 * not code, so they can be corrected without a rebuild of other modules.
 */
object DefaultSources {
    val feeds: List<FeedDefinition> = listOf(
        FeedDefinition(
            id = "sydneytrains",
            mode = Mode.TRAIN,
            tripUpdatesPath = "/v2/gtfs/realtime/sydneytrains",
            vehiclePositionsPath = "/v2/gtfs/vehiclepos/sydneytrains",
            alertsPath = "/v2/gtfs/alerts/sydneytrains",
        ),
        FeedDefinition(
            id = "metro",
            mode = Mode.METRO,
            tripUpdatesPath = "/v2/gtfs/realtime/metro",
            vehiclePositionsPath = "/v2/gtfs/vehiclepos/metro",
            alertsPath = "/v2/gtfs/alerts/metro",
        ),
    )

    val packs: List<PackDefinition> = listOf(
        PackDefinition(
            name = "syd-rail",
            modes = setOf(Mode.TRAIN, Mode.METRO),
            gtfsPaths = listOf("/v1/gtfs/schedule/sydneytrains", "/v1/gtfs/schedule/metro"),
        ),
    )
}
