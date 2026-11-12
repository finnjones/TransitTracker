package app.transittracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.transittracker.ui.theme.TtTheme

// ---------------------------------------------------------------- switch

/**
 * The ON / OFF switch (System and Notify mockups): a 64 x 26 box with a pixel border, a square
 * knob (Go when on) and the word. On it is drawn in Text, off in Secondary text. Purely visual;
 * make the whole row toggleable with [SwitchRow], or pass [onCheckedChange] to make the switch
 * itself a 48 dp touch target.
 */
@Composable
fun PixelSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    val ink = if (checked) c.text else c.textSecondary
    val knob = if (checked) c.go else c.textSecondary
    val toggle = if (onCheckedChange != null) {
        Modifier.heightIn(min = TtTheme.spacing.touch)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
    } else Modifier
    Box(modifier.then(toggle), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .size(64.dp + dot * 2, 26.dp + dot * 2)
                .pixelBorder(ink)
                .padding(dot.dp)
                .padding(start = if (checked) 8.dp else 3.dp, end = if (checked) 3.dp else 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val word = @Composable { PixelText(if (checked) "ON" else "OFF", style = TtTheme.type.label10Bold, color = ink) }
            val square = @Composable { Canvas(Modifier.size(dot * 10)) { drawRect(knob) } }
            if (checked) { word(); square() } else { square(); word() }
        }
    }
}

/**
 * A settings row with a label and a [PixelSwitch]; the whole row (at least 48 dp high) toggles.
 * [divider] draws the dashed rule above it, as between the rows on the Notify screen.
 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    divider: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        if (divider) DashedDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = TtTheme.spacing.touch)
                .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixelText(label, Modifier.weight(1f), color = if (enabled) TtTheme.colors.text else TtTheme.colors.textSecondary)
            PixelSwitch(checked)
        }
    }
}

// ---------------------------------------------------------------- stepper

/**
 * One stepper button: 44 dp inside a 1-dot border (48 dp touch target), Raised fill, a 7 x 7
 * minus or plus. Darkens to Divider while pressed. [label] is read by the screen reader.
 */
@Composable
fun StepperButton(
    icon: PixelIconData,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill = if (pressed && enabled) c.divider else c.raised
    Box(
        modifier
            .size(44.dp + dot * 2)
            .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .pixelFill(fill)
            .pixelBorder(c.divider),
        contentAlignment = Alignment.Center,
    ) { PixelIcon(icon, tint = if (enabled) c.text else c.textSecondary) }
}

/**
 * [-] value [+] (Notify mockup). [value] is drawn in [valueStyle] (Pixelify 20 Bold; use
 * `TtTheme.type.digits30` for times). Set [canDecrement] / [canIncrement] false at the limits.
 */
@Composable
fun Stepper(
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    decrementLabel: String,
    incrementLabel: String,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = TtTheme.type.title20,
    canDecrement: Boolean = true,
    canIncrement: Boolean = true,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        StepperButton(PixelIcons.Minus, decrementLabel, onDecrement, enabled = canDecrement)
        PixelText(value, Modifier.weight(1f), valueStyle, textAlign = TextAlign.Center, maxLines = 1)
        StepperButton(PixelIcons.Plus, incrementLabel, onIncrement, enabled = canIncrement)
    }
}

/** A labelled stepper row as on the Notify screen: label column of [labelWidth], then the stepper. */
@Composable
fun StepperRow(
    label: String,
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    decrementLabel: String,
    incrementLabel: String,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = TtTheme.type.title20,
    labelWidth: Dp = 92.dp,
    canDecrement: Boolean = true,
    canIncrement: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = TtTheme.spacing.touch).padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelText(label, Modifier.width(labelWidth))
        Stepper(
            value, onDecrement, onIncrement, decrementLabel, incrementLabel, Modifier.weight(1f),
            valueStyle, canDecrement, canIncrement,
        )
    }
}

// ---------------------------------------------------------------- toggles

/**
 * A pressable block that is either selected (inverted: Text fill, Screen label) or not (Panel
 * fill, Divider border, Secondary label). Used by [DayToggle] and [ToggleChip].
 */
@Composable
private fun ToggleBlock(
    text: String,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier,
    style: TextStyle,
    selectedLabel: Color,
    contentDescription: String?,
) {
    val c = TtTheme.colors
    val dot = TtTheme.dot
    val sem = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Box(
        modifier
            .heightIn(min = 44.dp + dot * 2)
            .toggleable(selected, role = Role.Checkbox, onValueChange = onSelectedChange)
            .then(sem)
            .pixelFill(if (selected) c.text else c.panel)
            .pixelBorder(if (selected) c.text else c.divider)
            .padding(horizontal = dot.dp + 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        PixelText(text, style = style, color = if (selected) selectedLabel else c.textSecondary, maxLines = 1)
    }
}

/** A day of the week toggle (Notify mockup): one letter, inverted when selected. */
@Composable
fun DayToggle(
    letter: String,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) = ToggleBlock(letter, selected, onSelectedChange, modifier, TtTheme.type.label13Bold, TtTheme.colors.screen, contentDescription)

/** Monday to Sunday as seven [DayToggle]s. Days are ISO numbers, 1 = Monday (as in `AlarmSpec`). */
@Composable
fun DayToggleRow(
    selectedDays: Set<Int>,
    onChange: (Set<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val letters = listOf("M", "T", "W", "T", "F", "S", "S")
    val names = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until 7) {
            val day = i + 1
            DayToggle(
                letters[i], day in selectedDays,
                { on -> onChange(if (on) selectedDays + day else selectedDays - day) },
                names[i], Modifier.weight(1f),
            )
        }
    }
}

/** A filter chip (ARRIVE FIRST / FEWER CHANGES on the options screen): Silkscreen Bold 10, inverted when selected. */
@Composable
fun ToggleChip(
    text: String,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) = ToggleBlock(text, selected, onSelectedChange, modifier, TtTheme.type.label10Bold, TtTheme.colors.screen, null)

/** Draws [color] as a one-dot square marker; helper for small inline swatches. */
@Composable
fun Swatch(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawRect(color, Offset.Zero, this.size) }
}
