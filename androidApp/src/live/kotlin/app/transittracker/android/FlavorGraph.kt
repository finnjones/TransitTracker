package app.transittracker.android

import app.transittracker.core.AndroidFileStore
import app.transittracker.core.AndroidNetworkStatus
import app.transittracker.core.AndroidTimetableOpener
import app.transittracker.core.AndroidZstdDecompressor
import app.transittracker.core.CoreConfig
import app.transittracker.core.CoreDependencies
import app.transittracker.core.KeyValueStore
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Live flavour: the real core against the build's tt-server (https://tt.finnjones.me unless the
 * build names another). With Firebase in the build, the user signs in with Google and their saved
 * data is also kept in their account.
 */
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
    private val phoneKv = SplitKeyValueStore(
        first = PropertiesKeyValueStore(File(app.filesDir, "kv/backup.properties")),
        second = PropertiesKeyValueStore(File(app.noBackupFilesDir, "kv/local.properties")),
        toFirst = { key -> key.startsWith("ui.") || key.startsWith("places.") || key == KvKeys.TRIPS },
    )

    /** With Firebase: the same store, with the user's own data also kept in their account. */
    private val cloud: CloudKeyValueStore? = if (Push.isAvailable(app)) CloudKeyValueStore(phoneKv) else null
    private val kv: KeyValueStore = cloud ?: phoneKv
    private val deviceId = MutableStateFlow<String?>(null)
    private val state = MutableStateFlow<Startup>(Startup.Loading)

    @Volatile
    private var core: TransitCore? = null

    @Volatile
    private var pendingPushToken: String? = null

    /** Updates from the GitHub releases CI publishes (null when the build names no repository). */
    private val updater = AppUpdates.create(app, scope)

    override val platform = AndroidPlatformServices(app, appVersionText, deviceId, updater).also { p ->
        if (cloud != null) p.accounts = FirebaseAccountService(app, { p.currentActivity }, cloud)
    }
    override val startup: StateFlow<Startup> = state.asStateFlow()

    init {
        start(ServerAddress.normalize(BuildConfig.BASE_URL) ?: error("Bad server address in the build: ${BuildConfig.BASE_URL}"))
    }

    override fun onPushToken(token: String) {
        Push.status.value = Push.GOT_TOKEN
        val c = core
        if (c == null) {
            pendingPushToken = token
        } else {
            scope.launch { sendPushToken(c, token) }
        }
    }

    /** Hands [token] to the core (which sends it to the server) and says whether the server took it. */
    private suspend fun sendPushToken(c: TransitCore, token: String) {
        Push.status.value = try {
            c.updatePushToken(token)
            if (kv.get(KvKeys.PUSH_TOKEN_PENDING) == null) Push.REGISTERED else Push.NOT_ACCEPTED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Could not send the token to the server: ${e.message ?: e.javaClass.simpleName}"
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
        val deps = AppDeps(
            core = created,
            // The real planner: tt-server's /v1/places and /v1/plan through the core's client.
            planner = created.planner,
            settings = KeyValueSettingsStore(kv),
            places = KeyValuePlacesStore(kv),
            platform = platform,
            clock = clock,
            // The planner's recent searches, kept on the phone (not backed up) and in the account.
            kv = kv,
        )
        state.value = Startup.Ready(deps)
        // Signed in already: bring down what changed in the account (another phone may have
        // saved trips or places) and show it. Starting never waits for this.
        val uid = platform.accounts?.account?.value?.uid
        if (cloud != null && uid != null) {
            scope.launch { if (cloud.pull(uid)) deps.reloadSaved() }
        }
        // Settings > About shows the device id once the phone has joined.
        scope.launch {
            created.joined.collect { joined -> deviceId.value = if (joined) kv.get(KvKeys.DEVICE_ID) else null }
        }
        pendingPushToken?.let { token ->
            pendingPushToken = null
            scope.launch { sendPushToken(created, token) }
        }
        Push.register(app)
    }
}
