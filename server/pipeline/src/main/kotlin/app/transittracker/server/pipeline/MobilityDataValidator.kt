package app.transittracker.server.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.time.Duration

/**
 * Optional hook running the MobilityData GTFS Validator (canonical CLI JAR, installed by the
 * operator; never downloaded here) on a GTFS zip. Its ERROR notices become validation errors
 * (blocking publication) and WARNING notices become warnings.
 */
class MobilityDataValidator(
    private val jar: Path,
    private val timeout: Duration,
    private val javaBin: String = ProcessHandle.current().info().command().orElse("java"),
) {
    private val log = LoggerFactory.getLogger(MobilityDataValidator::class.java)

    fun validate(zip: Path, report: ValidationReport.Builder) {
        val out = Files.createTempDirectory("gtfs-validator")
        try {
            val process = ProcessBuilder(
                javaBin, "-jar", jar.absolutePathString(),
                "--input", zip.absolutePathString(),
                "--output_base", out.absolutePathString(),
            ).redirectErrorStream(true).redirectOutput(out.resolve("validator.log").toFile()).start()
            if (!process.waitFor(timeout.inWholeSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                report.error("mobilitydata_timeout", "GTFS validator did not finish within $timeout", file = zip.fileName.toString())
                return
            }
            val reportFile = out.resolve("report.json")
            if (!Files.exists(reportFile)) {
                report.error("mobilitydata_failed", "GTFS validator exited with ${process.exitValue()} and no report", file = zip.fileName.toString())
                return
            }
            parseReport(Files.readString(reportFile), zip.fileName.toString(), report)
        } catch (e: Exception) {
            log.warn("GTFS validator could not run on {}", zip, e)
            report.error("mobilitydata_failed", "GTFS validator could not run: ${e.message}", file = zip.fileName.toString())
        } finally {
            out.toFile().deleteRecursively()
        }
    }

    companion object {
        /** Adds the notices of a validator `report.json` to [report]. */
        fun parseReport(json: String, source: String, report: ValidationReport.Builder) {
            val root = Json.parseToJsonElement(json).jsonObject
            for (notice in root["notices"]?.jsonArray.orEmpty()) {
                val n = notice as? JsonObject ?: continue
                val code = n["code"]?.jsonPrimitive?.content ?: continue
                val count = n["totalNotices"]?.jsonPrimitive?.intOrNull ?: 1
                val message = "$count notice(s) of $code from the MobilityData validator"
                when (n["severity"]?.jsonPrimitive?.content) {
                    "ERROR" -> report.error("mobilitydata.$code", message, file = source)
                    "WARNING" -> report.warning("mobilitydata.$code", message, file = source)
                }
            }
        }
    }
}
