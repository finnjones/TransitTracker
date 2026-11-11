package app.transittracker.ui

import app.transittracker.core.InMemoryKeyValueStore
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.settings.CardDensity
import app.transittracker.ui.settings.ClockFormat
import app.transittracker.ui.settings.KeyValueSettingsStore
import app.transittracker.ui.settings.ThemePreference
import app.transittracker.ui.settings.UiSettings
import app.transittracker.ui.theme.Dot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsTest {
    @Test
    fun defaultsThenPersistedChanges() = runTest {
        val kv = InMemoryKeyValueStore()
        val store = KeyValueSettingsStore(kv)
        assertEquals(UiSettings(), store.settings.first())
        store.update { it.copy(theme = ThemePreference.DAY, clockFormat = ClockFormat.H12, reduceMotion = true) }
        assertEquals(ThemePreference.DAY, store.current().theme)
        assertEquals(mapOf("ui.theme" to "DAY", "ui.clockFormat" to "H12", "ui.reduceMotion" to "true"), kv.snapshot())

        // A new store over the same key-value store reads them back.
        val again = KeyValueSettingsStore(kv).settings.first()
        assertEquals(UiSettings(theme = ThemePreference.DAY, clockFormat = ClockFormat.H12, reduceMotion = true), again)
    }

    @Test
    fun unknownValuesFallBackToDefaults() = runTest {
        val kv = InMemoryKeyValueStore(mapOf("ui.theme" to "PURPLE", "ui.cardDensity" to "COMPACT", "ui.haptics" to "maybe"))
        val s = KeyValueSettingsStore(kv).current()
        assertEquals(ThemePreference.NIGHT, s.theme)
        assertEquals(CardDensity.COMPACT, s.cardDensity)
        assertEquals(true, s.haptics)
    }

    @Test
    fun dotSnapsToWholePixels() {
        assertEquals(2, Dot.forDensity(1f).px)
        assertEquals(4, Dot.forDensity(2f).px)
        assertEquals(5, Dot.forDensity(2.625f).px)
        assertEquals(6, Dot.forDensity(3f).px)
        assertEquals(1, Dot.forDensity(0.4f).px)
        assertEquals(15f, Dot.forDensity(2.625f).px(3))
    }

    @Test
    fun iconPathsParse() {
        assertEquals(5, PixelIcons.LampCircle.rects.size)
        assertEquals(listOf(0, 2, 7, 3), PixelIcons.Minus.rects.single().toList())
    }
}
