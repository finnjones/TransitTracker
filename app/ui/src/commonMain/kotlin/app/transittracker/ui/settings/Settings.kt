package app.transittracker.ui.settings

import app.transittracker.core.KeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ThemePreference { NIGHT, DAY, SYSTEM }

enum class ClockFormat { H24, H12 }

enum class CardDensity { COMFORTABLE, COMPACT }

/** Front-end preferences (plan 5.11). Defaults follow the plan: Night, 24 h, sound off. */
data class UiSettings(
    val theme: ThemePreference = ThemePreference.NIGHT,
    val clockFormat: ClockFormat = ClockFormat.H24,
    val cardDensity: CardDensity = CardDensity.COMFORTABLE,
    /** App-level switch; the system reduce-motion setting and battery saver also apply. */
    val reduceMotion: Boolean = false,
    val sounds: Boolean = false,
    val haptics: Boolean = true,
    /**
     * Follow my journey: while the app is open, the phone's location tells which journey of a
     * saved trip you are on, so Home shows your connection instead of the next departure.
     */
    val followJourney: Boolean = true,
)

/** Where [UiSettings] live. Screens read [settings] and write with [update]. */
interface SettingsStore {
    /** Current settings; emits the stored values first, then every change. */
    val settings: Flow<UiSettings>

    suspend fun current(): UiSettings

    /** Applies [change] atomically and persists the result. */
    suspend fun update(change: (UiSettings) -> UiSettings)

    /** Reads the stored settings again (after they were restored from the user's account). */
    suspend fun reload() {}
}

/**
 * [SettingsStore] over the core's [KeyValueStore]. One key per setting (`ui.theme`, …) so adding
 * a setting never invalidates stored ones; unknown or missing values fall back to the default.
 * For previews and tests use it with `InMemoryKeyValueStore()`.
 */
class KeyValueSettingsStore(private val kv: KeyValueStore) : SettingsStore {
    private val state = MutableStateFlow<UiSettings?>(null)
    private val mutex = Mutex()

    override val settings: Flow<UiSettings> =
        state.onStart { ensureLoaded() }.filterNotNull()

    override suspend fun current(): UiSettings = ensureLoaded()

    override suspend fun update(change: (UiSettings) -> UiSettings) {
        mutex.withLock {
            val old = state.value ?: load()
            val new = change(old)
            if (new != old) save(old, new)
            state.value = new
        }
    }

    override suspend fun reload() {
        mutex.withLock { state.value = load() }
    }

    private suspend fun ensureLoaded(): UiSettings = state.value ?: mutex.withLock {
        state.value ?: load().also { state.value = it }
    }

    private suspend fun load(): UiSettings {
        val d = UiSettings()
        return UiSettings(
            theme = enumOr(kv.get(KEY_THEME), d.theme),
            clockFormat = enumOr(kv.get(KEY_CLOCK), d.clockFormat),
            cardDensity = enumOr(kv.get(KEY_DENSITY), d.cardDensity),
            reduceMotion = kv.get(KEY_REDUCE_MOTION)?.toBooleanStrictOrNull() ?: d.reduceMotion,
            sounds = kv.get(KEY_SOUNDS)?.toBooleanStrictOrNull() ?: d.sounds,
            haptics = kv.get(KEY_HAPTICS)?.toBooleanStrictOrNull() ?: d.haptics,
            followJourney = kv.get(KEY_FOLLOW)?.toBooleanStrictOrNull() ?: d.followJourney,
        )
    }

    private suspend fun save(old: UiSettings, new: UiSettings) {
        if (old.theme != new.theme) kv.put(KEY_THEME, new.theme.name)
        if (old.clockFormat != new.clockFormat) kv.put(KEY_CLOCK, new.clockFormat.name)
        if (old.cardDensity != new.cardDensity) kv.put(KEY_DENSITY, new.cardDensity.name)
        if (old.reduceMotion != new.reduceMotion) kv.put(KEY_REDUCE_MOTION, new.reduceMotion.toString())
        if (old.sounds != new.sounds) kv.put(KEY_SOUNDS, new.sounds.toString())
        if (old.haptics != new.haptics) kv.put(KEY_HAPTICS, new.haptics.toString())
        if (old.followJourney != new.followJourney) kv.put(KEY_FOLLOW, new.followJourney.toString())
    }

    private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == value } ?: default

    companion object {
        const val KEY_THEME = "ui.theme"
        const val KEY_CLOCK = "ui.clockFormat"
        const val KEY_DENSITY = "ui.cardDensity"
        const val KEY_REDUCE_MOTION = "ui.reduceMotion"
        const val KEY_SOUNDS = "ui.sounds"
        const val KEY_HAPTICS = "ui.haptics"
        const val KEY_FOLLOW = "ui.followJourney"
    }
}
