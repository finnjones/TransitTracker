package app.transittracker.server.app

import app.transittracker.server.spi.ServerConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.time.Duration.Companion.hours

private val log = LoggerFactory.getLogger("app.transittracker.server.app.Main")

/**
 * tt-server entry point: reads [ServerConfig] from the environment, opens the database, wires the
 * modules, serves the HTTP API and shuts everything down cleanly on SIGTERM (`docker stop`).
 */
fun main() {
    val config = ServerConfig.fromEnv()
    listOf(config.dataDir, config.packsDir, config.gtfsArchiveDir, config.recordingsDir)
        .forEach { Files.createDirectories(it) }

    val db = Database.open(config.dbPath)
    val devices = SqliteDeviceStore(db)
    val watchRepository = SqliteWatchRepository(db)
    val notificationLog = SqliteNotificationLog(db)
    val services = wireServices(config, devices, watchRepository, notificationLog)

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> log.error("Background task failed", e) },
    )
    services.start(scope)
    scope.launch(Dispatchers.IO) {
        while (isActive) {
            runCatching { devices.prune() }.onFailure { log.error("Prune failed", it) }
            delay(24.hours)
        }
    }

    val deps = ApiDependencies(
        config = config,
        devices = devices,
        notifications = notificationLog,
        timetables = services.timetables,
        live = services.live,
        watches = services.watches,
    )
    val server = embeddedServer(Netty, port = config.port, host = "0.0.0.0") { transitTrackerApi(deps) }

    Runtime.getRuntime().addShutdownHook(
        Thread({
            log.info("Shutting down")
            server.stop(gracePeriodMillis = 2_000, timeoutMillis = 10_000)
            scope.cancel()
            db.close()
            log.info("Stopped")
        }, "tt-server-shutdown"),
    )

    log.info(
        "tt-server listening on port {} (public URL {}, data dir {}, TfNSW key {}, push {}, admin token {})",
        config.port, config.publicBaseUrl, config.dataDir.toAbsolutePath(),
        if (config.tfnswApiKey != null) "set" else "MISSING",
        if (config.firebaseServiceAccountPath != null) "configured" else "off",
        if (config.adminToken != null) "set" else "off",
    )
    server.start(wait = true)
}
