package app.transittracker.server.app

import app.transittracker.model.WallClock
import app.transittracker.server.live.FeedFetcher
import app.transittracker.server.live.HttpFeedFetcher
import app.transittracker.server.live.LiveDataImpl
import app.transittracker.server.live.ReplayFetcher
import app.transittracker.server.live.TfnswQuota
import app.transittracker.server.planner.HttpPlannerFetcher
import app.transittracker.server.planner.PlannerFetcher
import app.transittracker.server.planner.ReplayPlannerFetcher
import app.transittracker.server.planner.TfnswPlanner
import app.transittracker.server.pipeline.PipelineOptions
import app.transittracker.server.pipeline.PipelineTimetableRepository
import app.transittracker.server.spi.DeviceRepository
import app.transittracker.server.spi.NotificationLog
import app.transittracker.server.spi.PushSender
import app.transittracker.server.spi.ServerConfig
import app.transittracker.server.spi.WatchRepository
import app.transittracker.server.watch.PushSenders
import app.transittracker.server.watch.WatchEngine
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.time.Duration.Companion.hours

private val log = LoggerFactory.getLogger("app.transittracker.server.app.Wiring")

/** The module implementations tt-server runs with. */
class Services(
    val timetables: PipelineTimetableRepository,
    val live: LiveDataImpl,
    val push: PushSender,
    val watches: WatchEngine,
    /** The one TfNSW daily budget and per-second limiter, shared by [live] and [planner]. */
    val quota: TfnswQuota,
    val planner: TfnswPlanner,
) : AutoCloseable {
    /**
     * Starts every module's background work in [scope]. The timetable goes first: its `start`
     * loads the published packs synchronously, so live matching and watch coverage see them.
     */
    fun start(scope: CoroutineScope) {
        timetables.start(scope)
        live.start(scope)
        watches.start(scope)
    }

    /** Closes the open timetable packs. Cancel the scope passed to [start] first. */
    override fun close() {
        timetables.close()
    }
}

/**
 * Builds the module implementations over the app's SQLite repositories.
 *
 * @param fetcher live feed source; null picks [ReplayFetcher] when `TT_REPLAY_DIR` is set, else
 *   [HttpFeedFetcher] (TfNSW).
 * @param push push sender; null picks FCM, or a logging sender when Firebase is not configured.
 * @param plannerFetcher Trip Planner source; null picks [ReplayPlannerFetcher] over
 *   `TT_REPLAY_DIR/planner` when `TT_REPLAY_DIR` is set, else [HttpPlannerFetcher] (TfNSW).
 */
fun wireServices(
    config: ServerConfig,
    devices: DeviceRepository,
    watchRepository: WatchRepository,
    notificationLog: NotificationLog,
    clock: WallClock = SystemClock,
    fetcher: FeedFetcher? = null,
    push: PushSender? = null,
    pipelineOptions: PipelineOptions = PipelineOptions.fromEnv(),
    plannerFetcher: PlannerFetcher? = null,
): Services {
    val timetables = PipelineTimetableRepository(config, options = pipelineOptions, clock = clock)
    val replayDir = config.replayDir
    // TfNSW counts calls per key across every endpoint: live feeds and the planner share one quota.
    val quota = TfnswQuota.fromConfig(config, clock)
    val live = when {
        fetcher != null -> LiveDataImpl(config, timetables.current, fetcher, clock, quota = quota)
        replayDir != null -> {
            if (!Files.isDirectory(replayDir)) log.warn("TT_REPLAY_DIR {} is not a directory: no live data", replayDir)
            log.info("Live feeds are replayed from {} (TfNSW is not called)", replayDir.toAbsolutePath())
            // No recording while replaying: it would copy the replayed files into the recordings.
            LiveDataImpl(config, timetables.current, ReplayFetcher(replayDir), clock, recorder = null, quota = quota)
        }
        else -> LiveDataImpl(config, timetables.current, HttpFeedFetcher(config), clock, quota = quota)
    }
    val tripPlanner = plannerFetcher ?: when {
        replayDir != null -> ReplayPlannerFetcher(replayDir.resolve(REPLAY_PLANNER_DIR)).also {
            if (it.configured) {
                log.info("Journey planning is replayed from {} (TfNSW is not called)", replayDir.resolve(REPLAY_PLANNER_DIR).toAbsolutePath())
            } else {
                log.warn("TT_REPLAY_DIR has no {}/trip.json or stop_finder.json: journey planning is off", REPLAY_PLANNER_DIR)
            }
        }
        else -> HttpPlannerFetcher(config)
    }
    // Rides on these modes get live data on the phone; the rest are shown as timetabled.
    val liveModes = if (fetcher != null || replayDir != null || !config.tfnswApiKey.isNullOrBlank()) {
        config.feeds.filter { it.tripUpdatesPath != null }.map { it.mode }.distinct()
    } else {
        emptyList()
    }
    val planner = TfnswPlanner(timetables.current, tripPlanner, quota, clock, liveModes)
    val pushSender = push ?: PushSenders.fromConfig(config)
    val watches = WatchEngine(
        config = config,
        watches = watchRepository,
        devices = devices,
        log = notificationLog,
        live = live,
        timetable = timetables.current,
        push = pushSender,
        clock = clock,
    )
    return Services(timetables, live, pushSender, watches, quota, planner)
}

/** Subdirectory of `TT_REPLAY_DIR` holding Trip Planner replay files (see testdata/planner/README.md). */
const val REPLAY_PLANNER_DIR = "planner"

/**
 * One tt-server instance: the SQLite database and repositories, the wired modules and what the
 * HTTP API needs. `Main.kt` runs it with the system clock; tests build it with a fixed clock and
 * fake feeds / push.
 */
class TtServer(
    val config: ServerConfig,
    val clock: WallClock = SystemClock,
    fetcher: FeedFetcher? = null,
    push: PushSender? = null,
    pipelineOptions: PipelineOptions = PipelineOptions.fromEnv(),
    plannerFetcher: PlannerFetcher? = null,
) : AutoCloseable {
    init {
        listOf(config.dataDir, config.packsDir, config.gtfsArchiveDir, config.recordingsDir)
            .forEach { Files.createDirectories(it) }
    }

    val db: Database = Database.open(config.dbPath)
    val devices = SqliteDeviceStore(db, clock)
    val watchRepository = SqliteWatchRepository(db, clock)
    val notificationLog = SqliteNotificationLog(db)
    val services: Services = try {
        wireServices(config, devices, watchRepository, notificationLog, clock, fetcher, push, pipelineOptions, plannerFetcher)
    } catch (e: Throwable) {
        db.close()
        throw e
    }

    /** Dependencies for [transitTrackerApi]. */
    val api = ApiDependencies(
        config = config,
        devices = devices,
        notifications = notificationLog,
        timetables = services.timetables,
        live = services.live,
        watches = services.watches,
        clock = clock,
        planner = services.planner,
    )

    /** Starts the modules and the daily prune in [scope]; cancel the scope to stop them. */
    fun start(scope: CoroutineScope) {
        services.start(scope)
        scope.launch(Dispatchers.IO + CoroutineName("prune")) {
            while (isActive) {
                runCatching { devices.prune() }.onFailure { log.error("Prune failed", it) }
                delay(24.hours)
            }
        }
    }

    /** Closes the timetable packs and the database. Cancel the [start] scope first. */
    override fun close() {
        runCatching { services.close() }.onFailure { log.warn("Closing timetables failed", it) }
        db.close()
    }
}
