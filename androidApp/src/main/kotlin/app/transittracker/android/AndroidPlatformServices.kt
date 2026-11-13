package app.transittracker.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.transittracker.model.LatLng
import app.transittracker.ui.platform.AccountService
import app.transittracker.ui.platform.LocationFix
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.platform.PlatformServices
import app.transittracker.ui.platform.ReleaseFeedUpdater
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * [PlatformServices] on Android. One per process (owned by the Application); the visible
 * activity attaches itself ([attach]) so permission prompts have a window to show in.
 *
 * Location uses the framework [LocationManager] (no Play services). Reduce motion follows the
 * system animator duration scale (0 = animations off); battery saver follows [PowerManager].
 */
class AndroidPlatformServices(
    private val app: Application,
    override val appVersion: String,
    override val deviceId: StateFlow<String?>,
    /** In-app updates (Settings > About); null in builds that cannot update themselves. */
    override val updater: ReleaseFeedUpdater? = null,
) : PlatformServices {
    /** Sign-in ([FirebaseAccountService]); set by the live graph when the build has Firebase. */
    override var accounts: AccountService? = null
        internal set

    /** The activity on screen, for what needs a window (the sign-in sheet); null in the background. */
    internal val currentActivity: Activity? get() = activity

    private val prefs = app.getSharedPreferences("platform", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private val locationState = MutableStateFlow(PermissionState.NOT_ASKED)
    private val notificationState = MutableStateFlow(PermissionState.NOT_ASKED)
    private val reduceMotionState = MutableStateFlow(false)
    private val batterySaverState = MutableStateFlow(false)

    override val locationPermission: StateFlow<PermissionState> = locationState.asStateFlow()
    override val notificationPermission: StateFlow<PermissionState> = notificationState.asStateFlow()
    override val systemReduceMotion: StateFlow<Boolean> = reduceMotionState.asStateFlow()
    override val batterySaver: StateFlow<Boolean> = batterySaverState.asStateFlow()
    /** Every registration step in debug builds; in release builds only a failure (else the line is hidden). */
    override val pushRegistration: StateFlow<String?> =
        (if (BuildConfig.DEBUG) Push.status else Push.problem).asStateFlow()

    /** Debug APKs show the Settings "Debug gallery" row; release builds do not. */
    override val isDebugBuild: Boolean get() = BuildConfig.DEBUG

    private var activity: Activity? = null
    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var pending: CompletableDeferred<Unit>? = null

    init {
        refresh()
        val scaleUri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        app.contentResolver.registerContentObserver(
            scaleUri,
            false,
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    reduceMotionState.value = animationsOff()
                }
            },
        )
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    batterySaverState.value = powerSaveMode()
                }
            },
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Called by the activity in onCreate with its permission launcher; [detach] in onDestroy. */
    fun attach(activity: Activity, launcher: ActivityResultLauncher<Array<String>>) {
        this.activity = activity
        this.launcher = launcher
    }

    fun detach(activity: Activity) {
        if (this.activity !== activity) return
        this.activity = null
        launcher = null
        pending?.complete(Unit)
        pending = null
    }

    /** The launcher's callback: the prompt closed. */
    fun onPermissionResult() {
        refresh()
        pending?.complete(Unit)
        pending = null
    }

    /** Re-reads every state (the user may have changed them in system settings). */
    fun refresh() {
        locationState.value = permissionState(LOCATION_PERMISSIONS, KEY_ASKED_LOCATION)
        notificationState.value = notificationPermissionState()
        reduceMotionState.value = animationsOff()
        batterySaverState.value = powerSaveMode()
        // Back from "Install unknown apps": an update waiting for it carries on.
        updater?.onResume()
    }

    // ---- Location

    override suspend fun requestLocationPermission(): PermissionState {
        if (locationState.value == PermissionState.NOT_ASKED) request(LOCATION_PERMISSIONS, KEY_ASKED_LOCATION)
        return locationState.value
    }

    // Each call is guarded by hasLocationPermission(); lint cannot see through the helper.
    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): LatLng? {
        if (!hasLocationPermission()) return null
        val lm = app.getSystemService(LocationManager::class.java) ?: return null
        // Location switched off for the whole phone: no provider will ever answer.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !lm.isLocationEnabled) return null
        val known = lastKnown(lm)
        // A fix from the last two minutes is good enough for "where am I".
        if (known != null && age(known) < FRESH_NANOS) return known.toLatLng()
        val providers = liveProviders(lm)
        // Ask every provider at once and take whichever answers first: GPS alone often never
        // gets a fix indoors or on a train, while the network or fused provider answers in seconds.
        val fix = if (providers.isEmpty()) null else withTimeoutOrNull(LOCATION_TIMEOUT_MS) { firstFix(lm, providers) }
        // Nothing new in time: an older fix still beats "could not find where you are".
        return (fix ?: known?.takeIf { age(it) < USABLE_NANOS })?.toLatLng()
    }

    // Each call is guarded by hasLocationPermission(); lint cannot see through the helper.
    @SuppressLint("MissingPermission")
    override suspend fun currentFix(nowMs: Long): LocationFix? {
        if (!hasLocationPermission()) return null
        val lm = app.getSystemService(LocationManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !lm.isLocationEnabled) return null
        val known = lastKnown(lm)
        // Following a journey needs a fix from the last half minute: a train covers a kilometre in one.
        val fresh: Location? = known?.takeIf { age(it) < FOLLOW_FRESH_NANOS }
        val providers = if (fresh == null) liveProviders(lm) else emptyList()
        val location: Location = fresh
            ?: (if (providers.isEmpty()) null else withTimeoutOrNull(LOCATION_TIMEOUT_MS) { firstFix(lm, providers) })
            ?: return null
        // When the fix was taken, on the same clock as nowMs (the phone's wall clock may differ).
        val takenMs = nowMs - age(location) / 1_000_000L
        return LocationFix(
            location.toLatLng(),
            takenMs,
            accuracyM = location.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
            // Speed and heading tell a train from a car beside it; only GPS fixes on the move carry them.
            speedMps = location.takeIf { it.hasSpeed() }?.speed?.toDouble(),
            bearingDeg = location.takeIf { it.hasBearing() && it.hasSpeed() && it.speed > 0f }?.bearing?.toDouble(),
        )
    }

    /** The providers worth asking for a new fix, best first. GPS needs the precise permission. */
    private fun liveProviders(lm: LocationManager): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.hasProvider(LocationManager.FUSED_PROVIDER)) {
            add(LocationManager.FUSED_PROVIDER)
        }
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

    /** The newest fix any provider (passive included) already holds. */
    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? =
        runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }

    private fun age(location: Location): Long = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos

    /** The first fix from any of [providers]; null once they have all given up. */
    private suspend fun firstFix(lm: LocationManager, providers: List<String>): Location? = coroutineScope {
        val first = CompletableDeferred<Location?>()
        val attempts = providers.map { provider ->
            launch { singleFix(lm, provider)?.let { first.complete(it) } }
        }
        launch {
            attempts.joinAll()
            first.complete(null)
        }
        try {
            first.await()
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun singleFix(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(app)) { location ->
                        if (cont.isActive) cont.resume(location)
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            lm.removeUpdates(this)
                            if (cont.isActive) cont.resume(location)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                        override fun onProviderEnabled(provider: String) = Unit

                        override fun onProviderDisabled(provider: String) {
                            lm.removeUpdates(this)
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    cont.invokeOnCancellation { main.post { lm.removeUpdates(listener) } }
                    lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
            } catch (e: RuntimeException) {
                // The provider vanished or the permission was taken away mid-request: no fix from it.
                if (cont.isActive) cont.resume(null)
            }
        }

    // ---- Notifications

    override suspend fun requestNotificationPermission(): PermissionState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && notificationState.value == PermissionState.NOT_ASKED) {
            request(arrayOf(Manifest.permission.POST_NOTIFICATIONS), KEY_ASKED_NOTIFICATIONS)
        }
        refresh()
        return notificationState.value
    }

    override fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
        startActivity(intent)
    }

    // ---- Links

    override fun openUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    /** Starts [intent] from the visible activity when there is one (else as a new task). */
    fun startActivity(intent: Intent) {
        val from: Context = activity ?: app
        if (from !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            from.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Nothing can open it (no browser); there is nothing more useful to do.
            android.util.Log.w("TransitTracker", "No activity for ${intent.action}")
        }
    }

    // ---- Helpers

    private suspend fun request(permissions: Array<String>, askedKey: String) {
        val l = launcher ?: return
        val done = CompletableDeferred<Unit>()
        pending?.complete(Unit)
        pending = done
        prefs.edit().putBoolean(askedKey, true).apply()
        l.launch(permissions)
        done.await()
        refresh()
    }

    private fun hasLocationPermission(): Boolean = LOCATION_PERMISSIONS.any(::granted)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    /** GRANTED; NOT_ASKED while a prompt can still be shown; DENIED once only settings can change it. */
    private fun permissionState(permissions: Array<String>, askedKey: String): PermissionState {
        if (permissions.any(::granted)) return PermissionState.GRANTED
        if (!prefs.getBoolean(askedKey, false)) return PermissionState.NOT_ASKED
        val a = activity ?: return PermissionState.DENIED
        // After one refusal the system still allows asking again (it shows a rationale flag).
        return if (permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(a, it) }) {
            PermissionState.NOT_ASKED
        } else {
            PermissionState.DENIED
        }
    }

    private fun notificationPermissionState(): PermissionState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val state = permissionState(arrayOf(Manifest.permission.POST_NOTIFICATIONS), KEY_ASKED_NOTIFICATIONS)
            if (state != PermissionState.GRANTED) return state
        }
        // Granted (or not a runtime permission), but the user can still switch the app's notifications off.
        return if (NotificationManagerCompat.from(app).areNotificationsEnabled()) PermissionState.GRANTED else PermissionState.DENIED
    }

    private fun animationsOff(): Boolean =
        Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun powerSaveMode(): Boolean = app.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    private fun Location.toLatLng() = LatLng(latitude, longitude)

    companion object {
        val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        private const val KEY_ASKED_LOCATION = "asked.location"
        private const val KEY_ASKED_NOTIFICATIONS = "asked.notifications"
        private const val LOCATION_TIMEOUT_MS = 20_000L
        private const val FRESH_NANOS = 2 * 60 * 1_000_000_000L
        private const val FOLLOW_FRESH_NANOS = 30 * 1_000_000_000L
        private const val USABLE_NANOS = 15 * 60 * 1_000_000_000L
    }
}
