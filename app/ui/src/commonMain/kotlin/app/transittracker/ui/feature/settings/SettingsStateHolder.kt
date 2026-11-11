package app.transittracker.ui.feature.settings

import app.transittracker.model.DataStatus
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.model.WatchDto
import app.transittracker.ui.places.PlacesStore
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.platform.PlatformServices
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
data class TestState(val sending: Boolean = false, val outcome: TestOutcome? = null) {
    val words: String? get() = outcome?.let { testWords(it) }
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
    val batterySaver: Boolean = false,
    val test: TestState = TestState(),
    val appVersion: String = "",
    val deviceId: String? = null,
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

    private class Phone(val permission: PermissionState, val saver: Boolean, val systemMotion: Boolean, val deviceId: String?)

    private val phone = combine(
        platform.notificationPermission, platform.batterySaver, platform.systemReduceMotion, platform.deviceId,
    ) { p, b, m, id -> Phone(p, b, m, id) }

    override val state: StateFlow<SettingsUiState> = combine(dataInputs, settings.settings, phone, test, checking) { d, ui, ph, t, chk ->
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
            batterySaver = ph.saver,
            test = t,
            appVersion = platform.appVersion,
            deviceId = ph.deviceId,
            debugBuild = platform.isDebugBuild,
        )
    }.distinctUntilChanged().stateIn(
        scope, SharingStarted.Eagerly, SettingsUiState(appVersion = platform.appVersion, debugBuild = platform.isDebugBuild),
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

    private fun change(f: (UiSettings) -> UiSettings) {
        scope.launch { settings.update(f) }
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
            val outcome = try {
                val watch = pickWatch(core.watches.first())
                if (watch == null) TestOutcome.NO_WATCH
                else if (core.sendTestNotification(watch.id)) TestOutcome.SENT else TestOutcome.REFUSED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TestOutcome.UNREACHABLE
            }
            test.value = TestState(outcome = outcome)
        }
    }

    private fun pickWatch(watches: List<WatchDto>): WatchDto? {
        val now = clock.nowMillis()
        return watches.firstOrNull { (it.pausedUntilMs ?: 0L) <= now } ?: watches.firstOrNull()
    }
}
