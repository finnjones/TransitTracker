package app.transittracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import app.transittracker.ui.settings.ClockFormat
import app.transittracker.ui.settings.ThemePreference
import app.transittracker.ui.settings.UiSettings
import app.transittracker.ui.sprites.SpriteClock
import app.transittracker.ui.sprites.rememberRunningSpriteClock
import kotlinx.datetime.TimeZone

/** Behaviour switches that components read (from [UiSettings] plus the system). */
@Immutable
data class TtMotion(
    /** Sprites hold one frame, nothing blinks, transitions cut (plan 2.3, 3.6). */
    val reduceMotion: Boolean = false,
    /** A light haptic tick on button presses (plan 3.5). */
    val haptics: Boolean = true,
    val sounds: Boolean = false,
)

/** How clock times are written (plan 2.5). */
@Immutable
data class TtTime(
    val clockFormat: ClockFormat = ClockFormat.H24,
    val timeZone: TimeZone = TimeZone.currentSystemDefault(),
)

private val LocalTtColors = staticCompositionLocalOf { NightColors }
private val LocalTtType = staticCompositionLocalOf<TtType> { error("TtTheme is missing: wrap content in TtTheme { }") }
private val LocalTtFonts = staticCompositionLocalOf<TtFonts> { error("TtTheme is missing: wrap content in TtTheme { }") }
private val LocalDot = staticCompositionLocalOf { Dot.forDensity(1f) }
private val LocalSpacing = staticCompositionLocalOf { TtSpacing() }
private val LocalMotion = staticCompositionLocalOf { TtMotion() }
private val LocalTime = staticCompositionLocalOf { TtTime() }
private val LocalSpriteClock = staticCompositionLocalOf<SpriteClock> { error("TtTheme is missing: wrap content in TtTheme { }") }

/**
 * The design system root. Everything under it reads tokens through [TtTheme.colors],
 * [TtTheme.type], [TtTheme.dot] and friends. Night is the default.
 *
 * [spriteClock]: leave null for the running 8 fps clock; pass a FixedSpriteClock for screenshots.
 */
@Composable
fun TtTheme(
    colors: TtColors = NightColors,
    motion: TtMotion = TtMotion(),
    time: TtTime = TtTime(),
    spriteClock: SpriteClock? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current.density
    val dot = remember(density) { Dot.forDensity(density) }
    val fonts = rememberTtFonts()
    val type = remember(fonts) { ttType(fonts) }
    val clock = spriteClock ?: rememberRunningSpriteClock()
    CompositionLocalProvider(
        LocalTtColors provides colors,
        LocalTtFonts provides fonts,
        LocalTtType provides type,
        LocalDot provides dot,
        LocalSpacing provides TtSpacing(),
        LocalMotion provides motion,
        LocalTime provides time,
        LocalSpriteClock provides clock,
        content = content,
    )
}

/**
 * [TtTheme] driven by stored [settings]. [systemReduceMotion] is the platform's reduce-motion or
 * battery-saver state (supplied by the Android / iOS shell).
 */
@Composable
fun TtTheme(
    settings: UiSettings,
    systemDark: Boolean = isSystemInDarkTheme(),
    systemReduceMotion: Boolean = false,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    spriteClock: SpriteClock? = null,
    content: @Composable () -> Unit,
) {
    val colors = when (settings.theme) {
        ThemePreference.NIGHT -> NightColors
        ThemePreference.DAY -> DayColors
        ThemePreference.SYSTEM -> if (systemDark) NightColors else DayColors
    }
    TtTheme(
        colors = colors,
        motion = TtMotion(settings.reduceMotion || systemReduceMotion, settings.haptics, settings.sounds),
        time = TtTime(settings.clockFormat, timeZone),
        spriteClock = spriteClock,
        content = content,
    )
}

/** Accessors for the current theme values. */
object TtTheme {
    val colors: TtColors @Composable @ReadOnlyComposable get() = LocalTtColors.current
    val type: TtType @Composable @ReadOnlyComposable get() = LocalTtType.current
    val fonts: TtFonts @Composable @ReadOnlyComposable get() = LocalTtFonts.current
    val dot: Dot @Composable @ReadOnlyComposable get() = LocalDot.current
    val spacing: TtSpacing @Composable @ReadOnlyComposable get() = LocalSpacing.current
    val motion: TtMotion @Composable @ReadOnlyComposable get() = LocalMotion.current
    val time: TtTime @Composable @ReadOnlyComposable get() = LocalTime.current
    val spriteClock: SpriteClock @Composable @ReadOnlyComposable get() = LocalSpriteClock.current
}
