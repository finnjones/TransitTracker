package app.transittracker.ui.feature.settings

import app.transittracker.model.DataStatus
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.model.WatchDto
import app.transittracker.ui.places.PlacesStore
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.platform.PlatformServices
import app.transittracker.ui.platform.SignInResult
import app.transittracker.ui.platform.UpdateState
import app.transittracker.ui.settings.CardDensity
import app.transittracker.ui.settings.ClockFormat
import app.transittracker.ui.settings.SettingsStore
import app.transittracker.ui.settings.ThemePreference
import app.transittracker.ui.settings.UiSettings
import app.transittracker.ui.state.StateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/** What the Data group draws. */
data class DataSectionState(
    val live: LiveWords = liveWords(null, 0),
    val packs: List<PackRow> = emptyList(),
    val attribution: String = "",
    /** The server's own message, if it has one. */
    val message: String? = null,
    /** Why the timetable has not arrived (server has none, download failed, not joined...). */
    val problem: String? = null,
    /** CHECK NOW is running. */
    val checking: Boolean = false,
)

/** SEND TEST: nothing yet, working, or the result line. */
data class TestState(val sending: Boolean = false, val outcome: TestOutcome? = null, val detail: String? = null) {
    val words: String? get() = outcome?.let { testWords(it, detail) }
}

/** Everything the Settings page draws. */
data class SettingsUiState(
    val loaded: Boolean = false,
    val savedPlaces: Int = 0,
    val savedRoutes: Int = 0,
    val data: DataSectionState = DataSectionState(),
    val display: UiSettings = UiSettings(),
    /** The phone's own reduce-motion setting is on (it also stops motion). */
    val systemReduceMotion: Boolean = false,
    val notifications: PermissionState = PermissionState.NOT_ASKED,
    /** Location permission, for Follow my journey. */
    val location: PermissionState = PermissionState.NOT_ASKED,
    val batterySaver: Boolean = false,
    /** Where this phone's push registration stands (see [PlatformServices.pushRegistration]). */
    val pushRegistration: String? = null,
    val test: TestState = TestState(),
    val appVersion: String = "",
    val deviceId: String? = null,
    /** The build signs in with accounts (About shows the account and SIGN OUT). */
    val accountsAvailable: Boolean = false,
    /** The signed-in account ("rider@example.com"), or null. */
    val account: String? = null,
    /** SIGN IN or SIGN OUT is running. */
    val signingOut: Boolean = false,
    /** Why the last SIGN IN did not work. */
    val accountMessage: String? = null,
    /** The app-update rows of About. */
    val update: UpdateWords = updateWords(null),
    /** A developer build: the debug gallery row is shown. */
    val debugBuild: Boolean = false,
)

/**
 * State for the Settings tab: display settings (written to the [SettingsStore] at once, which the
 * shell reads to restyle the app), the core's data status, the platform's permission and ids.
 */
class SettingsStateHolder(
    private val core: TransitCore,
    private val settings: SettingsStore,
    private val places: PlacesStore,
    private val platform: PlatformServices,
    private val clock: WallClock,
    scope: CoroutineScope,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val tickMs: Long = 15_000,
) : StateHolder<SettingsUiState>(scope) {
    private val test = MutableStateFlow(TestState())
    private val checking = MutableStateFlow(false)
    private val signingOut = MutableStateFlow(false)

    /** Why the last SIGN IN in Settings did not work. */
    private val accountMessage = MutableStateFlow<String?>(null)

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(tickMs)
        }
    }

    private class Data(val status: DataStatus?, val now: Long, val places: Int, val routes: Int)

    private val dataInputs = combine(
        core.dataStatus.map<_, DataStatus?> { it }.onStart { emit(null) },
        ticker,
        places.places.map { it.size },
        core.trips.map { it.size },
    ) { status, now, p, r -> Data(status, now, p, r) }

    private class Phone(
        val permission: PermissionState,
        val saver: Boolean,
        val systemMotion: Boolean,
        val deviceId: String?,
        val location: PermissionState,
    )

    private val phone = combine(
        platform.notificationPermission, platform.batterySaver, platform.systemReduceMotion, platform.deviceId, platform.locationPermission,
    ) { p, b, m, id, loc -> Phone(p, b, m, id, loc) }

    private class Actions(
        val test: TestState,
        val checking: Boolean,
        val update: UpdateState?,
        val account: AccountWords,
        val push: String?,
    )

    private class AccountWords(val label: String?, val busy: Boolean, val message: String?)

    /** What the page's own buttons have started (SEND TEST, CHECK NOW, the app update, SIGN OUT) and the account. */
    private val actions = combine(
        test, checking, platform.updater?.state ?: flowOf(null),
        combine(platform.accounts?.account?.map { it?.label } ?: flowOf(null), signingOut, accountMessage) { l, b, m -> AccountWords(l, b, m) },
        platform.pushRegistration,
    ) { t, chk, u, acc, push -> Actions(t, chk, u, acc, push) }

    override val state: StateFlow<SettingsUiState> = combine(dataInputs, settings.settings, phone, actions) { d, ui, ph, a ->
        val t = a.test
        val chk = a.checking
        val u = a.update
        val status = d.status
        SettingsUiState(
            loaded = true,
            savedPlaces = d.places,
            savedRoutes = d.routes,
            data = DataSectionState(
                live = liveWords(status, d.now),
                packs = status?.packs.orEmpty().map { packRow(it, today(d.now, zone)) },
                attribution = status?.attribution.orEmpty(),
                message = status?.statusMessage?.takeIf { it.isNotBlank() },
                problem = status?.timetableProblem?.takeIf { it.isNotBlank() }
                    ?: if (status != null && !status.joined) "This phone has not joined a server yet, so there is no timetable to download." else null,
                checking = chk,
            ),
            display = ui,
            systemReduceMotion = ph.systemMotion,
            notifications = ph.permission,
            location = ph.location,
            batterySaver = ph.saver,
            pushRegistration = a.push,
            test = t,
            appVersion = platform.appVersion,
            deviceId = ph.deviceId,
            accountsAvailable = platform.accounts != null,
            account = a.account.label,
            signingOut = a.account.busy,
            accountMessage = a.account.message,
            update = updateWords(u),
            debugBuild = platform.isDebugBuild,
        )
    }.distinctUntilChanged().stateIn(
        scope,
        SharingStarted.Eagerly,
        SettingsUiState(
            appVersion = platform.appVersion,
            accountsAvailable = platform.accounts != null,
            account = platform.accounts?.account?.value?.label,
            update = updateWords(platform.updater?.state?.value),
            debugBuild = platform.isDebugBuild,
        ),
    )

    /** CHECK NOW: ask the server for the timetable again. */
    fun checkNow() {
        if (checking.value) return
        scope.launch {
            checking.value = true
            try {
                core.checkForUpdates()
            } finally {
                checking.value = false
            }
        }
    }

    // ---- display: every change is stored at once

    fun setTheme(theme: ThemePreference) = change { it.copy(theme = theme) }
    fun setClockFormat(format: ClockFormat) = change { it.copy(clockFormat = format) }
    fun setCardDensity(density: CardDensity) = change { it.copy(cardDensity = density) }
    fun setReduceMotion(on: Boolean) = change { it.copy(reduceMotion = on) }
    fun setSounds(on: Boolean) = change { it.copy(sounds = on) }
    fun setHaptics(on: Boolean) = change { it.copy(haptics = on) }

    /** ALLOW LOCATION: the system prompt (shown only while it can still be asked). */
    fun allowLocation() {
        scope.launch { platform.requestLocationPermission() }
    }

    /** Follow my journey; turning it on asks for location when it has not been asked yet. */
    fun setFollowJourney(on: Boolean) {
        change { it.copy(followJourney = on) }
        if (on && platform.locationPermission.value == PermissionState.NOT_ASKED) {
            scope.launch { platform.requestLocationPermission() }
        }
    }

    private fun change(f: (UiSettings) -> UiSettings) {
        scope.launch { settings.update(f) }
    }

    // ---- account

    /**
     * SIGN IN on a phone that joined with an invite code: signs in so its saved data is also kept
     * in the account (and brought down from it). The phone stays joined to the server as it is.
     */
    fun signIn() {
        val accounts = platform.accounts ?: return
        if (signingOut.value) return
        signingOut.value = true
        scope.launch {
            try {
                when (val result = accounts.signIn()) {
                    is SignInResult.Success -> {
                        accountMessage.value = null
                        if (result.restored) {
                            core.reloadTrips()
                            places.reload()
                            settings.reload()
                        }
                    }
                    is SignInResult.Failed -> accountMessage.value = result.message
                    SignInResult.Cancelled -> Unit
                }
            } finally {
                signingOut.value = false
            }
        }
    }

    /**
     * SIGN OUT: signs out of the account and forgets this phone's join, so onboarding shows again.
     * Saved trips, places and settings stay on the phone and in the account.
     */
    fun signOut() {
        if (signingOut.value) return
        signingOut.value = true
        scope.launch {
            try {
                runCatching { platform.accounts?.signOut() }
                core.signOut()
            } finally {
                signingOut.value = false
            }
        }
    }

    // ---- app updates

    /** The update button: check the release feed, or install what it found. */
    fun updateApp(action: UpdateAction) {
        val updater = platform.updater ?: return
        when (action) {
            UpdateAction.CHECK -> updater.check()
            UpdateAction.INSTALL -> updater.install()
        }
    }

    // ---- notifications

    /** ALLOW: the system prompt; once refused for good, the phone's settings. */
    fun allowNotifications() {
        scope.launch {
            if (platform.notificationPermission.value == PermissionState.DENIED) platform.openNotificationSettings()
            else platform.requestNotificationPermission()
        }
    }

    fun openPhoneSettings() = platform.openNotificationSettings()

    /** SEND TEST: through the first watch that is not paused, else the first; plainly says when there is none. */
    fun sendTest() {
        if (test.value.sending) return
        test.value = TestState(sending = true)
        scope.launch {
            test.value = try {
                val watch = pickWatch(core.watches.first())
                if (watch == null) {
                    TestState(outcome = TestOutcome.NO_WATCH)
                } else {
                    val response = core.sendTestNotification(watch.id)
                    if (response.sent) TestState(outcome = TestOutcome.SENT)
                    else TestState(outcome = TestOutcome.REFUSED, detail = response.detail)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TestState(outcome = TestOutcome.UNREACHABLE)
            }
        }
    }

    private fun pickWatch(watches: List<WatchDto>): WatchDto? {
        val now = clock.nowMillis()
        return watches.firstOrNull { (it.pausedUntilMs ?: 0L) <= now } ?: watches.firstOrNull()
    }
}
