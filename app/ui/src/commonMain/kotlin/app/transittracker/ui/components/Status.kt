package app.transittracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.transittracker.model.Occupancy
import app.transittracker.ui.format.CountdownText
import app.transittracker.ui.format.DefaultLineColor
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.format.StatusPresentation
import app.transittracker.ui.format.badgeTextColor
import app.transittracker.ui.format.carCountWords
import app.transittracker.ui.format.occupancyPresentation
import app.transittracker.ui.format.parseLineColor
import app.transittracker.ui.format.platformPresentation
import app.transittracker.ui.sprites.SpriteClock
import app.transittracker.ui.sprites.blinkOn
import app.transittracker.ui.theme.TtTheme
import kotlinx.coroutines.delay
import kotlin.math.max

// ---------------------------------------------------------------- route badge

enum class BadgeStyle {
    /** Silkscreen Bold 12 with a 1-dot Text border (cards, rows). */
    NORMAL,
    /** Silkscreen Bold 10, no border (under a leg strip). */
    COMPACT,
}

/**
 * The line badge (plan 2.4). [lineColor] is the route colour from the timetable; the text is the
 * feed's colour only when it reaches 4.5 : 1, else black or white ([badgeTextColor]).
 * [width] fixes the width (34 dp in departure rows) and centres the name.
 */
@Composable
fun RouteBadge(
    name: String,
    lineColor: Color,
    modifier: Modifier = Modifier,
    feedTextColor: Color? = null,
    style: BadgeStyle = BadgeStyle.NORMAL,
    width: Dp? = null,
) {
    val dot = TtTheme.dot
    val ink = badgeTextColor(lineColor, feedTextColor)
    val sized = if (width != null) Modifier.width(width) else Modifier
    val m = when (style) {
        BadgeStyle.NORMAL -> modifier.then(sized)
            .background(lineColor)
            .border(dot.dp, TtTheme.colors.text, RectangleShape)
            .padding(horizontal = dot.dp + (if (width != null) 0.dp else 5.dp), vertical = dot.dp + 1.dp)
        BadgeStyle.COMPACT -> modifier.then(sized).background(lineColor).padding(horizontal = 4.dp, vertical = 1.dp)
    }
    val text = if (style == BadgeStyle.NORMAL) TtTheme.type.label12Bold else TtTheme.type.label10Bold
    Box(m, contentAlignment = Alignment.Center) {
        PixelText(name, style = text, color = ink, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** [RouteBadge] from GTFS hex strings (`Route.color`, `Route.textColor`, `Departure.routeColor`). */
@Composable
fun RouteBadge(
    name: String,
    colorHex: String?,
    textColorHex: String?,
    modifier: Modifier = Modifier,
    style: BadgeStyle = BadgeStyle.NORMAL,
    width: Dp? = null,
) = RouteBadge(name, parseLineColor(colorHex) ?: DefaultLineColor, modifier, parseLineColor(textColorHex), style, width)

// ---------------------------------------------------------------- status

/** A status lamp on its 7 x 7 grid; [size] 14 dp is one dot per cell. Decorative (the words carry it). */
@Composable
fun StatusLamp(lamp: Lamp, color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    PixelIcon(PixelIcons.lamp(lamp), modifier, tint = color, size = size)
}

enum class StatusSize {
    /** Lamp 14, Silkscreen Bold 12 (card status line). */
    NORMAL,
    /** Lamp 10, Silkscreen Bold 10 (departure rows). */
    SMALL,
}

/**
 * Lamp + words in the status colour (plan 2.7): shape, word, then colour. [suffix] is appended
 * after " · " in the same colour (e.g. "PLAT 5"). Read as one item: the spoken words.
 */
@Composable
fun StatusLine(
    status: StatusPresentation,
    modifier: Modifier = Modifier,
    size: StatusSize = StatusSize.NORMAL,
    suffix: String? = null,
) {
    val color = TtTheme.colors.of(status.role)
    val words = if (suffix != null) "${status.words} · $suffix" else status.words
    Row(
        modifier.clearAndSetSemantics { contentDescription = status.spoken + (suffix?.let { ", " + it.lowercase() } ?: "") },
        horizontalArrangement = Arrangement.spacedBy(if (size == StatusSize.NORMAL) 8.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusLamp(status.lamp, color, size = if (size == StatusSize.NORMAL) 14.dp else 10.dp)
        PixelText(
            words,
            style = if (size == StatusSize.NORMAL) TtTheme.type.label12Bold else TtTheme.type.label10Bold,
            color = color,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------- platform

/**
 * Platform sign (System mockup). Normal: "PLAT 3" in a Text-bordered box. Changed: "NOW PLAT 6"
 * inverted in Caution; it blinks at 1 Hz for 5 s after it appears, then stays inverted (plan 2.7).
 * No blinking under reduce motion.
 */
@Composable
fun PlatformSign(platform: String, changed: Boolean, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    val p = platformPresentation(platform, changed)
    var blinking by remember(changed) { mutableStateOf(changed) }
    LaunchedEffect(changed) {
        if (changed) { delay(5_000); blinking = false }
    }
    val clock: SpriteClock = TtTheme.spriteClock
    val reduce = TtTheme.motion.reduceMotion
    val inverted = changed && (!blinking || clock.blinkOn(reduce))
    val fill = if (inverted) c.caution else Color.Transparent
    val border = if (changed) c.caution else c.text
    val ink = if (inverted) c.screen else if (changed) c.caution else c.text
    Row(
        modifier
            .clearAndSetSemantics { contentDescription = p.spoken }
            .pixelFill(fill)
            .pixelBorder(border)
            .padding(horizontal = dot.dp + 8.dp, vertical = dot.dp + 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        PixelText(
            p.prefix,
            Modifier.alignByBaseline(),
            if (changed) TtTheme.type.label10Bold else TtTheme.type.label10,
            ink,
            maxLines = 1,
        )
        PixelText(p.platform, Modifier.alignByBaseline(), TtTheme.type.title20, ink, maxLines = 1)
    }
}

// ---------------------------------------------------------------- seats and cars

/**
 * Four seat pips and the words (plan 2.7). Filled pips are Text, empty ones a thin Secondary
 * outline. Shows nothing for [Occupancy.UNKNOWN].
 */
@Composable
fun SeatPips(occupancy: Occupancy, modifier: Modifier = Modifier, showWords: Boolean = true) {
    val p = occupancyPresentation(occupancy) ?: return
    val c = TtTheme.colors
    val dot = TtTheme.dot
    Row(
        modifier.clearAndSetSemantics { contentDescription = p.spoken },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(dot * (4 * 3 + 3), dot * 3)) {
            val pip = dot.px(3)
            val hair = max(1, dot.px / 2).toFloat()
            for (i in 0 until 4) {
                val x = i * dot.px(4)
                if (i < p.filled) drawRect(c.text, Offset(x, 0f), Size(pip, pip))
                else drawRect(c.textSecondary, Offset(x + hair / 2, hair / 2), Size(pip - hair, pip - hair), style = Stroke(hair))
            }
        }
        if (showWords) PixelText(p.words, style = TtTheme.type.label10, color = c.textSecondary, maxLines = 1)
    }
}

/**
 * Train length (System mockup): one block per car and an amber marker at the front, then
 * "8 CARS". [diagram] false shows the words only.
 */
@Composable
fun CarCount(cars: Int, modifier: Modifier = Modifier, diagram: Boolean = true) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    Row(
        modifier.clearAndSetSemantics { contentDescription = carCountWords(cars).lowercase() },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (diagram && cars > 0) {
            Canvas(Modifier.size(dot * (cars * 7 + 2), dot * 4)) {
                for (i in 0 until cars) drawRect(c.text, Offset(i * dot.px(7), 0f), Size(dot.px(6), dot.px(4)))
                drawRect(c.caution, Offset(cars * dot.px(7), dot.px(1)), Size(dot.px(2), dot.px(2)))
            }
        }
        PixelText(carCountWords(cars), style = TtTheme.type.label10, color = c.textSecondary, maxLines = 1)
    }
}

// ---------------------------------------------------------------- countdown

enum class CountdownSize { XL76, L60, M44, S30 }

/**
 * Countdown numerals (plan 2.5): Doto Black digits with "MIN" in Silkscreen beside them, or "NOW",
 * or a clock time. Build [text] with `countdown(...)`. Read as [CountdownText.spoken].
 */
@Composable
fun Countdown(
    text: CountdownText,
    modifier: Modifier = Modifier,
    size: CountdownSize = CountdownSize.XL76,
    color: Color = TtTheme.colors.text,
) {
    val t = TtTheme.type
    val (digits, unit) = when (size) {
        CountdownSize.XL76 -> t.digits76 to t.label13Bold
        CountdownSize.L60 -> t.digits60 to t.label13Bold
        CountdownSize.M44 -> t.digits44 to t.label12Bold
        CountdownSize.S30 -> t.digits30 to t.label11Bold
    }
    Row(
        modifier.clearAndSetSemantics { contentDescription = text.spoken },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        PixelText(text.value, Modifier.alignByBaseline(), digits, color, maxLines = 1)
        if (text.unit != null) PixelText(text.unit, Modifier.alignByBaseline(), unit, color, maxLines = 1)
    }
}

/** A small count badge (the Alerts tab "2"): Caution block, Screen text, at least 16 dp square. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.widthIn(min = 16.dp).background(TtTheme.colors.caution).padding(horizontal = 3.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) { PixelText(count.toString(), style = TtTheme.type.label10Bold, color = TtTheme.colors.screen, maxLines = 1) }
}
