package app.transittracker.server.app

import app.transittracker.server.spi.ServerConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("app.transittracker.server.app.Main")

/**
 * tt-server entry point: reads [ServerConfig] from the environment, opens the database, wires the
 * modules, serves the HTTP API and shuts everything down cleanly on SIGTERM (`docker stop`).
 */
fun main() {
    val config = ServerConfig.fromEnv()
    val tt = TtServer(config)

    val job = SupervisorJob()
    val scope = CoroutineScope(
        job + Dispatchers.Default + CoroutineExceptionHandler { _, e -> log.error("Background task failed", e) },
    )
    tt.start(scope)

    val server = embeddedServer(Netty, port = config.port, host = "0.0.0.0") { transitTrackerApi(tt.api) }

    Runtime.getRuntime().addShutdownHook(
        Thread({
            log.info("Shutting down")
            server.stop(gracePeriodMillis = 2_000, timeoutMillis = 10_000)
            runBlocking { withTimeoutOrNull(5_000) { job.cancelAndJoin() } }
            tt.close()
            log.info("Stopped")
        }, "tt-server-shutdown"),
    )

    log.info(
        "tt-server listening on port {} (public URL {}, data dir {}, live {}, push {}, admin token {}, timetable {})",
        config.port, config.publicBaseUrl, config.dataDir.toAbsolutePath(),
        when {
            config.replayDir != null -> "replay from ${config.replayDir}"
            config.tfnswApiKey != null -> "TfNSW"
            else -> "off (TFNSW_API_KEY missing)"
        },
        if (config.firebaseServiceAccountPath != null) "configured" else "logged only",
        if (config.adminToken != null) "set" else "off",
        tt.services.timetables.current.value?.version ?: "none yet",
    )
    server.start(wait = true)
}
