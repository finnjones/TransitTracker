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
    /**
     * Serve live feeds from recorded / fixture files under this directory instead of calling TfNSW
     * (local development without an API key). See `ReplayFetcher` in `:server:live`.
     */
    val replayDir: Path? = null,
    /**
     * Reverse proxy in front of tt-server whose client-address header can be trusted.
     * [TrustedProxy.CLOUDFLARE]: requests arrive only through a Cloudflare Tunnel (cloudflared),
     * so `CF-Connecting-IP` holds the real client address.
     */
    val trustedProxy: TrustedProxy = TrustedProxy.NONE,
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
                replayDir = env["TT_REPLAY_DIR"]?.let { Paths.get(it) },
                trustedProxy = env["TT_TRUSTED_PROXY"]?.let { TrustedProxy.parse(it) } ?: d.trustedProxy,
            )
        }
    }
}

/** See [ServerConfig.trustedProxy]. */
enum class TrustedProxy {
    NONE,
    CLOUDFLARE,
    ;

    companion object {
        fun parse(value: String): TrustedProxy =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("TT_TRUSTED_PROXY must be one of ${entries.joinToString { it.name.lowercase() }}")
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
 * Release 1 sources, checked against the live API on 11 Nov 2026 (every path answered 200 and
 * decoded; see testdata/planner/README.md for the run).
 *
 * - Sydney Trains static: `/v1/gtfs/schedule/sydneytrains` (there is no v2 bundle: 404); its trip
 *   ids are the ones the v2 trip update and vehicle position feeds use.
 * - Metro static: `/v2/gtfs/schedule/metro` (its trip ids match every v2 Metro trip update). The v1
 *   bundle (`/v1/gtfs/schedule/metro`) is frozen at version 17092024 (Metro North West only, 13
 *   stations) and none of its trips run in the current window: a pack built from it has no metro.
 * - Light rail static: one v1 bundle per network (there is no v2: 404). `/v1/gtfs/schedule/lightrail`
 *   is the Inner West line alone, so the three Sydney networks are listed by name. They merge with
 *   the train bundle at shared stations (Central, Circular Quay, Town Hall, Wynyard, Parramatta) with no
 *   conflicts; with them the pack is about 2 MB compressed (checked 11 Nov 2026).
 * - Light rail live: CBD & South East and Parramatta answer on v1 only, Inner West on v2 only
 *   (realtime and vehiclepos alike); every one of them uses the static bundles' trip ids.
 *   Alerts: `/v2/gtfs/alerts/lightrail`.
 * - Buses: static `/v1/gtfs/schedule/buses` (111 MB zip, all operators; no v2) builds a pack of
 *   about 6.6 MB compressed / 27 MB SQLite in under a minute within a 1.5 GB heap. Live
 *   `/v1/gtfs/realtime/buses` and `/v1/gtfs/vehiclepos/buses` use its trip ids; alerts
 *   `/v2/gtfs/alerts/buses`.
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
        // Light rail: one feed per network, on whichever API version answers for it (the others
        // 404). Alerts are one feed for all light rail, so only the first network carries it.
        FeedDefinition(
            id = "lightrail-cbdandsoutheast",
            mode = Mode.LIGHT_RAIL,
            tripUpdatesPath = "/v1/gtfs/realtime/lightrail/cbdandsoutheast",
            vehiclePositionsPath = "/v1/gtfs/vehiclepos/lightrail/cbdandsoutheast",
            alertsPath = "/v2/gtfs/alerts/lightrail",
        ),
        FeedDefinition(
            id = "lightrail-innerwest",
            mode = Mode.LIGHT_RAIL,
            tripUpdatesPath = "/v2/gtfs/realtime/lightrail/innerwest",
            vehiclePositionsPath = "/v2/gtfs/vehiclepos/lightrail/innerwest",
            alertsPath = null,
        ),
        FeedDefinition(
            id = "lightrail-parramatta",
            mode = Mode.LIGHT_RAIL,
            tripUpdatesPath = "/v1/gtfs/realtime/lightrail/parramatta",
            vehiclePositionsPath = "/v1/gtfs/vehiclepos/lightrail/parramatta",
            alertsPath = null,
        ),
        // Every Sydney bus operator in one feed. Trip updates are about 5 MB a fetch, so they are
        // fetched less often than the rail feeds.
        FeedDefinition(
            id = "buses",
            mode = Mode.BUS,
            tripUpdatesPath = "/v1/gtfs/realtime/buses",
            vehiclePositionsPath = "/v1/gtfs/vehiclepos/buses",
            alertsPath = "/v2/gtfs/alerts/buses",
            minAgeOnScreenS = 30,
            minAgeWatchS = 60,
        ),
    )

    val packs: List<PackDefinition> = listOf(
        PackDefinition(
            name = "syd-rail",
            modes = setOf(Mode.TRAIN, Mode.METRO, Mode.LIGHT_RAIL),
            gtfsPaths = listOf(
                "/v1/gtfs/schedule/sydneytrains",
                "/v2/gtfs/schedule/metro",
                "/v1/gtfs/schedule/lightrail/cbdandsoutheast", // L2, L3
                "/v1/gtfs/schedule/lightrail/innerwest", // L1
                "/v1/gtfs/schedule/lightrail/parramatta", // L4
            ),
        ),
        // Its own pack so the rail pack stays small. It also carries one Newcastle ferry route.
        PackDefinition(
            name = "syd-bus",
            modes = setOf(Mode.BUS, Mode.FERRY),
            gtfsPaths = listOf("/v1/gtfs/schedule/buses"),
        ),
    )
}
