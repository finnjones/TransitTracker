package app.transittracker.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens (plan 2.4). Read them through [TtTheme.colors]; never hard-code a hex value in a
 * screen. Line colours are not tokens: they come from the timetable (`Route.color`).
 */
@Immutable
data class TtColors(
    val isDay: Boolean,
    /** App background. */
    val screen: Color,
    /** Cards, bottom bar. */
    val panel: Color,
    /** Secondary buttons, steppers. */
    val raised: Color,
    /** Borders, dashed rules, sleepers. */
    val divider: Color,
    /** Labels, past stops, rails. */
    val textSecondary: Color,
    /** Text; also the fill of the selected item and the primary button. */
    val text: Color,
    /** Long reading text (alert descriptions, help), a step softer than [text]. */
    val textReading: Color,
    /** On time, live, switch on. */
    val go: Color,
    /** Late, early, platform changed, warning alerts. */
    val caution: Color,
    /** Cancelled, severe alerts. */
    val stop: Color,
    /** Timetable-only, info alerts. */
    val info: Color,
    /** The hard lip under a primary button. */
    val lipPrimary: Color,
    /** The hard lip under a secondary button. */
    val lipSecondary: Color,
    /** Open doors and lit interiors (the AT A STOP swatch). */
    val lit: Color,
    /** The band of water under ferries on a journey strip. */
    val water: Color,
    /** Sprite outline; drawn only when [outlineSprites] is true (Day theme). */
    val spriteOutline: Color,
    val outlineSprites: Boolean,
) {
    /** The colour of a status [role]. */
    fun of(role: ColorRole): Color = when (role) {
        ColorRole.TEXT -> text
        ColorRole.SECONDARY -> textSecondary
        ColorRole.GO -> go
        ColorRole.CAUTION -> caution
        ColorRole.STOP -> stop
        ColorRole.INFO -> info
    }
}

/** Which token a piece of status text or a lamp is drawn in (plan 2.7). */
enum class ColorRole { TEXT, SECONDARY, GO, CAUTION, STOP, INFO }

/** Night theme, the default. Values from plan 2.4 and the System mockup. */
val NightColors = TtColors(
    isDay = false,
    screen = Color(0xFF0C0C0A),
    panel = Color(0xFF181816),
    raised = Color(0xFF252522),
    divider = Color(0xFF3D3D38),
    textSecondary = Color(0xFFA3A092),
    text = Color(0xFFF3ECD8),
    textReading = Color(0xFFD9D2BE),
    go = Color(0xFF4ADE80),
    caution = Color(0xFFFFB020),
    stop = Color(0xFFFF5A5F),
    info = Color(0xFF6CB6FF),
    lipPrimary = Color(0xFF8C8468),
    lipSecondary = Color(0xFF000000),
    lit = Color(0xFFFFD98A),
    water = Color(0xFF1E3A52),
    spriteOutline = Color(0xFF0C0C0A),
    outlineSprites = false,
)

/**
 * Day theme: the same tokens inverted (plan 2.4). Screen, Panel, Text, Secondary text and the
 * four status colours are the plan's starting values. Raised, Divider, the lips, reading text and
 * water are not given in the plan and were chosen here to keep the same relationships as Night.
 */
val DayColors = TtColors(
    isDay = true,
    screen = Color(0xFFF3ECD8),
    panel = Color(0xFFFFFBF0),
    raised = Color(0xFFE6DEC6),
    divider = Color(0xFFB4AD98),
    textSecondary = Color(0xFF5C5A4E),
    text = Color(0xFF0C0C0A),
    textReading = Color(0xFF2B2A25),
    go = Color(0xFF14703A),
    caution = Color(0xFF9A5200),
    stop = Color(0xFFB3261E),
    info = Color(0xFF1F5FBF),
    lipPrimary = Color(0xFF5C5A4E),
    lipSecondary = Color(0xFF8C8468),
    lit = Color(0xFFFFD98A),
    water = Color(0xFF9CC3DD),
    spriteOutline = Color(0xFF0C0C0A),
    outlineSprites = true,
)
