package app.transittracker.ui.platform

import app.transittracker.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val NoPushRegistration: StateFlow<String?> = MutableStateFlow(null)

/** A runtime permission as the app sees it. */
enum class PermissionState {
    GRANTED,
    /** Not asked yet, or the user can still be asked. */
    NOT_ASKED,
    /** Refused; only the system settings can change it now. */
    DENIED,
}

/** One position from the phone, taken at [timeMs] (epoch ms), with its accuracy radius when known. */
data class LocationFix(val position: LatLng, val timeMs: Long, val accuracyM: Double? = null)

/**
 * What only the platform shell (Android activity, iOS app) can do. Screens use it through
 * `AppDeps.platform`; [FakePlatformServices] stands in for previews, screenshots and tests.
 */
interface PlatformServices {
    // ---- Location (USE WHERE I AM NOW, Plan a journey, nearby stops on the map)
    val locationPermission: StateFlow<PermissionState>

    /** Shows the system prompt if it can; returns the state afterwards. */
    suspend fun requestLocationPermission(): PermissionState

    /** One current position, or null without permission / no fix in reasonable time. */
    suspend fun currentLocation(): LatLng?

    /**
     * A recent position with the time it was taken, for following a journey (a fix minutes old
     * puts a train kilometres back). Null without permission or a fix. [nowMs] is used when the
     * platform cannot say when its fix was taken.
     */
    suspend fun currentFix(nowMs: Long): LocationFix? = currentLocation()?.let { LocationFix(it, nowMs) }

    // ---- Notifications (onboarding step 3, Notify banner, Settings)
    val notificationPermission: StateFlow<PermissionState>

    suspend fun requestNotificationPermission(): PermissionState

    /** Opens the system's notification settings for the app (Android channel settings). */
    fun openNotificationSettings()

    /**
     * Where this phone's push registration stands, as one plain sentence (Settings >
     * Notifications), e.g. the push service's error. Null hides the line: Android release builds
     * report only failures, debug builds every step.
     */
    val pushRegistration: StateFlow<String?> get() = NoPushRegistration

    // ---- Links and about
    /** Opens [url] in the browser (alert MORE INFO, licences). */
    fun openUrl(url: String)

    /** e.g. "0.1.0 (12)". */
    val appVersion: String

    /** True in a developer build; developer-only things (the debug gallery row in Settings) show only then. */
    val isDebugBuild: Boolean get() = false

    /** The device id text shown in Settings > About (needed to revoke a device); null before joining. */
    val deviceId: StateFlow<String?>

    /**
     * Checks for and installs newer builds of the app (Settings > About, CHECK FOR UPDATES);
     * null where the build cannot update itself (demo, desktop, builds without a release feed).
     */
    val updater: AppUpdater? get() = null

    /**
     * Sign-in with an account (Firebase Authentication), which replaces the invite code; null in
     * builds without it, where onboarding asks for an invite code.
     */
    val accounts: AccountService? get() = null

    // ---- System motion state (plan 2.3: sprites hold still and transitions cut)
    val systemReduceMotion: StateFlow<Boolean>
    val batterySaver: StateFlow<Boolean>
}

/**
 * [PlatformServices] for previews, screenshots and tests: everything is a settable state, and
 * calls are recorded ([openedUrls], [notificationSettingsOpened]).
 */
class FakePlatformServices(
    location: LatLng? = LatLng(-33.8718, 151.0943),
    locationPermission: PermissionState = PermissionState.GRANTED,
    notificationPermission: PermissionState = PermissionState.GRANTED,
    override val appVersion: String = "0.1.0 (fake)",
    deviceId: String? = "fake-device",
    reduceMotion: Boolean = false,
    batterySaver: Boolean = false,
    override val isDebugBuild: Boolean = true,
    override val updater: AppUpdater? = FakeAppUpdater(),
    override val accounts: AccountService? = null,
) : PlatformServices {
    val location = MutableStateFlow(location)
    val locationPermissionState = MutableStateFlow(locationPermission)
    val notificationPermissionState = MutableStateFlow(notificationPermission)
    val deviceIdState = MutableStateFlow(deviceId)
    val reduceMotionState = MutableStateFlow(reduceMotion)
    val batterySaverState = MutableStateFlow(batterySaver)
    val pushRegistrationState = MutableStateFlow<String?>(null)

    /** What a permission request turns NOT_ASKED into. */
    var grantOnRequest: Boolean = true

    val openedUrls = mutableListOf<String>()
    var notificationSettingsOpened = 0
        private set

    override val locationPermission: StateFlow<PermissionState> = locationPermissionState.asStateFlow()
    override val notificationPermission: StateFlow<PermissionState> = notificationPermissionState.asStateFlow()
    override val deviceId: StateFlow<String?> = deviceIdState.asStateFlow()
    override val systemReduceMotion: StateFlow<Boolean> = reduceMotionState.asStateFlow()
    override val batterySaver: StateFlow<Boolean> = batterySaverState.asStateFlow()
    override val pushRegistration: StateFlow<String?> = pushRegistrationState.asStateFlow()

    override suspend fun requestLocationPermission(): PermissionState = request(locationPermissionState)

    override suspend fun currentLocation(): LatLng? =
        if (locationPermissionState.value == PermissionState.GRANTED) location.value else null

    override suspend fun requestNotificationPermission(): PermissionState = request(notificationPermissionState)

    override fun openNotificationSettings() {
        notificationSettingsOpened++
    }

    override fun openUrl(url: String) {
        openedUrls += url
    }

    private fun request(state: MutableStateFlow<PermissionState>): PermissionState {
        if (state.value == PermissionState.NOT_ASKED) {
            state.value = if (grantOnRequest) PermissionState.GRANTED else PermissionState.DENIED
        }
        return state.value
    }
}
