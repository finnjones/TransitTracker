package app.transittracker.ui.feature.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.transittracker.model.Mode
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.sprites.VehicleSprite
import app.transittracker.ui.sprites.VehicleState
import app.transittracker.ui.theme.TtTheme
import app.transittracker.ui.format.DefaultLineColor
import kotlin.math.roundToInt

/** "STEP 2 OF 4" and four blocks: filled for the steps done, bright for this one. */
@Composable
fun StepIndicator(step: OnboardingStep, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val spoken = step.words.lowercase().replaceFirstChar { it.uppercase() }
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(step.words, color = c.textSecondary, bold = true, size = LabelSize.L11)
        Row(horizontalArrangement = Arrangement.spacedBy(TtTheme.dot * 2)) {
            for (s in OnboardingStep.entries) {
                val color = when {
                    s == step -> c.text
                    s.ordinal < step.ordinal -> c.go
                    else -> c.divider
                }
                Box(Modifier.size(width = TtTheme.dot * 8, height = TtTheme.dot * 3).drawBehind { drawRect(color) })
            }
        }
    }
}

/**
 * The invite ticket: a card with a notch on each side where it tears, a dashed perforation, a
 * stub on top and the [bottom] part (code field, JOIN) under it. Plain rectangles, no curves.
 */
@Composable
fun InviteTicket(modifier: Modifier = Modifier, bottom: @Composable () -> Unit) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    var perf by remember { mutableIntStateOf(0) }
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val d = dot.px(1)
                // body and border
                drawRect(c.panel)
                drawRect(c.divider, Offset(d, 0f), Size(size.width - 2 * d, d))
                drawRect(c.divider, Offset(d, size.height - d), Size(size.width - 2 * d, d))
                drawRect(c.divider, Offset(0f, d), Size(d, size.height - 2 * d))
                drawRect(c.divider, Offset(size.width - d, d), Size(d, size.height - 2 * d))
                // the tear notches on the perforation, in the screen colour with a border
                val n = dot.px(5)
                val depth = dot.px(3)
                val y0 = perf - n / 2
                drawRect(c.screen, Offset(0f, y0), Size(depth, n))
                drawRect(c.screen, Offset(size.width - depth, y0), Size(depth, n))
                for (x in listOf(0f, size.width - depth)) {
                    drawRect(c.divider, Offset(x, y0), Size(depth, d))
                    drawRect(c.divider, Offset(x, y0 + n - d), Size(depth, d))
                }
                drawRect(c.divider, Offset(depth - d, y0), Size(d, n))
                drawRect(c.divider, Offset(size.width - depth, y0), Size(d, n))
                // the perforation
                var x = depth + d
                val end = size.width - depth - d
                while (x < end) {
                    drawRect(c.divider, Offset(x, perf - d / 2), Size(minOf(2 * d, end - x), d))
                    x += 4 * d
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().onSizeChanged { perf = it.height }
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixelIcon(PixelIcons.Train, tint = c.text, size = 32.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label("TRANSITTRACKER", bold = true, size = LabelSize.L13)
                Label("ADMIT ONE DEVICE", color = c.textSecondary, size = LabelSize.L10)
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            bottom()
        }
    }
}

/**
 * The timetable download scene: a train on a track that fills as the packs arrive. The train
 * moves with [fraction] (it eases there unless reduce motion is on, when it jumps), runs while
 * downloading, stands at the end when [ready], and sits grey and still when it [failed]. The
 * percentage is written beside it by the caller, so nothing is only in the motion.
 */
@Composable
fun DownloadScene(fraction: Float, ready: Boolean, failed: Boolean, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    val reduce = TtTheme.motion.reduceMotion
    val target = fraction.coerceIn(0f, 1f)
    val shown by animateFloatAsState(target, tween(if (reduce) 0 else 600), label = "train")
    val state = when {
        failed -> VehicleState.Stale
        ready -> VehicleState.AtStop
        else -> VehicleState.Moving
    }
    BoxWithConstraints(modifier.fillMaxWidth().height(76.dp)) {
        val trainWidth = 128.dp
        val travel = maxWidth - trainWidth
        val x = travel * shown
        VehicleSprite(
            Mode.TRAIN, DefaultLineColor, state, scale = 2,
            modifier = Modifier.align(Alignment.TopStart).offset { IntOffset(x.roundToPx(), 0) },
        )
        Canvas(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(dot * 6)) {
            val d = dot.px(1)
            val railY = d
            val laid = if (failed) c.textSecondary else c.go
            val filledTo = dot.snapPx(travel.toPx() * shown + trainWidth.toPx() / 2).coerceAtMost(size.width)
            // sleepers every 4 dots, rails 1 dot thick
            var sx = 0f
            while (sx < size.width) {
                val on = sx < filledTo
                drawRect(if (on) laid else c.divider, Offset(sx, railY), Size(d, dot.px(4)))
                sx += 4 * d
            }
            drawRect(c.divider, Offset(0f, railY), Size(size.width, d))
            drawRect(c.divider, Offset(0f, railY + dot.px(3)), Size(size.width, d))
            drawRect(laid, Offset(0f, railY), Size(filledTo, d))
            drawRect(laid, Offset(0f, railY + dot.px(3)), Size(filledTo, d))
        }
    }
}
