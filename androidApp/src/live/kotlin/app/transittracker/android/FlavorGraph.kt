package app.transittracker.android

import app.transittracker.core.AndroidFileStore
import app.transittracker.core.AndroidNetworkStatus
import app.transittracker.core.AndroidTimetableOpener
import app.transittracker.core.AndroidZstdDecompressor
import app.transittracker.core.CoreConfig
import app.transittracker.core.CoreDependencies
import app.transittracker.core.KvKeys
import app.transittracker.core.PropertiesKeyValueStore
import app.transittracker.core.SplitKeyValueStore
import app.transittracker.core.TransitCoreFactory
import app.transittracker.core.androidHttpClient
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.ui.app.AppDeps
import app.transittracker.ui.places.KeyValuePlacesStore
import app.transittracker.ui.settings.KeyValueSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Live flavour: the real core against the tt-server at the build's or the user's address. */
internal object FlavorGraph {
    fun create(app: TransitTrackerApplication): AppGraph = LiveGraph(app)
}

private class LiveGraph(private val app: TransitTrackerApplication) : AppGraph {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * One key-value store for the core, settings and places. Display settings, saved trips and
     * places go to `files/kv/backup.properties` (the only file auto backup includes); everything
     * else, the device token and id, push token and server address above all, goes to
     * `no_backup/kv/local.properties`, which Android never backs up.
     */
    private val kv = SplitKeyValueStore(
        first = PropertiesKeyValueStore(File(app.filesDir, "kv/backup.properties")),
        second = PropertiesKeyValueStore(File(app.noBackupFilesDir, "kv/local.properties")),
        toFirst = { key -> key.startsWith("ui.") || key.startsWith("places.") || key == KvKeys.TRIPS },
    )
    private val deviceId = MutableStateFlow<String?>(null)
    private val state = MutableStateFlow<Startup>(Startup.Loading)

    @Volatile
    private var core: TransitCore? = null

    @Volatile
    private var pendingPushToken: String? = null

    override val platform = AndroidPlatformServices(app, appVersionText, deviceId)
    override val startup: StateFlow<Startup> = state.asStateFlow()

    init {
        val built = ServerAddress.normalize(BuildConfig.BASE_URL)
        if (built != null) {
            start(built)
        } else {
            scope.launch {
                val saved = kv.get(KEY_BASE_URL)?.let(ServerAddress::normalize)
                if (saved != null) start(saved) else state.value = Startup.NeedsServerAddress
            }
        }
    }

    override fun setServerAddress(url: String) {
        val normalized = ServerAddress.normalize(url) ?: return
        scope.launch {
            kv.put(KEY_BASE_URL, normalized)
            start(normalized)
        }
    }

    override fun onPushToken(token: String) {
        val c = core
        if (c == null) {
            pendingPushToken = token
        } else {
            scope.launch { c.updatePushToken(token) }
        }
    }

    @Synchronized
    private fun start(baseUrl: String) {
        if (core != null) return
        val clock = WallClock { System.currentTimeMillis() }
        val created = TransitCoreFactory.create(
            CoreConfig(baseUrl = baseUrl, appVersion = BuildConfig.VERSION_NAME, platform = "android"),
            CoreDependencies(
                httpClient = androidHttpClient(),
                kv = kv,
                files = AndroidFileStore(File(app.noBackupFilesDir, "packs")),
                opener = AndroidTimetableOpener,
                decompressor = AndroidZstdDecompressor,
                network = AndroidNetworkStatus(app),
                clock = clock,
                scope = scope,
                ioContext = Dispatchers.IO,
            ),
        )
        core = created
        state.value = Startup.Ready(
            AppDeps(
                core = created,
                planner = livePlanner(),
                settings = KeyValueSettingsStore(kv),
                places = KeyValuePlacesStore(kv),
                platform = platform,
                clock = clock,
                // The planner's recent searches, kept on the phone (not backed up).
                kv = kv,
            ),
        )
        // Settings > About shows the device id once the phone has joined.
        scope.launch {
            created.joined.collect { joined -> deviceId.value = if (joined) kv.get(KvKeys.DEVICE_ID) else null }
        }
        pendingPushToken?.let { token ->
            pendingPushToken = null
            scope.launch { created.updatePushToken(token) }
        }
        Push.register(app)
    }

    private companion object {
        /** The server address the user entered (when the build has none), on the phone only. */
        const val KEY_BASE_URL = "app.baseUrl"
    }
}
