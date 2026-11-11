package app.transittracker.server.pipeline

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Pipeline settings not covered by `ServerConfig`.
 *
 * @property windowDays days of service data from the build date (plan: ~70).
 * @property lookbackDays days before the build date also included (after-midnight running).
 * @property zstdLevel compression level of published packs.
 * @property keepVersions published versions kept per pack.
 * @property archiveRetentionDays raw GTFS zips older than this are deleted (the newest per source is always kept).
 * @property gtfsValidatorJar MobilityData GTFS Validator JAR to run on downloaded zips; null skips it.
 * @property retiredPackCloseDelay how long a replaced pack stays open for in-flight queries.
 */
data class PipelineOptions(
    val windowDays: Int = 70,
    val lookbackDays: Int = 1,
    val zstdLevel: Int = 19,
    val keepVersions: Int = 3,
    val archiveRetentionDays: Int = 30,
    val shapeToleranceMeters: Double = 10.0,
    val gtfsValidatorJar: Path? = null,
    val gtfsValidatorTimeout: Duration = 15.minutes,
    val retiredPackCloseDelay: Duration = 2.minutes,
) {
    companion object {
        /** Reads `TT_PACK_WINDOW_DAYS` and `TT_GTFS_VALIDATOR_JAR`; everything else keeps its default. */
        fun fromEnv(env: Map<String, String> = System.getenv()): PipelineOptions {
            val d = PipelineOptions()
            return d.copy(
                windowDays = env["TT_PACK_WINDOW_DAYS"]?.toIntOrNull() ?: d.windowDays,
                gtfsValidatorJar = env["TT_GTFS_VALIDATOR_JAR"]?.takeIf { it.isNotBlank() }?.let { Paths.get(it) },
            )
        }
    }
}
