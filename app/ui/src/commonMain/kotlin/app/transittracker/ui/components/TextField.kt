package app.transittracker.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.transittracker.ui.theme.TtTheme

/** What a [PixelTextField] is for; it decides the on-screen keyboard. */
enum class FieldInput {
    /** Names: each word capitalised, DONE key. */
    NAME,

    /** Addresses, stations and stops: no auto-correct, SEARCH key. */
    SEARCH,

    /** Invite codes: capitals, no suggestions, DONE key. */
    CODE,

    /** Web addresses: URL keyboard, no capitals, DONE key. */
    URL,
}

/**
 * A single-line text field (Add place mockup): Panel fill with the pixel border, Divider when
 * idle and Text when it has focus. 48 dp high by default, so it is its own touch target.
 *
 * [label] is what a screen reader calls the field; it is not drawn (put a `SectionLabel` or
 * `Label` above the field for sighted users). [placeholder] shows in secondary text while the
 * field is empty. [onSubmit] runs on the keyboard's action key. Digits are set in the numeral
 * face as in [PixelText], so a typed 5 never reads as an S.
 *
 * [leading] and [trailing] sit inside the border (an icon, a CLEAR button).
 */
@Composable
fun PixelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    input: FieldInput = FieldInput.NAME,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    style: TextStyle = TtTheme.type.name17,
    onSubmit: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = TtTheme.colors
    val numerals = TtTheme.type.numerals
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val textColor = if (enabled) colors.text else colors.textSecondary
    val transformation = remember(numerals) {
        VisualTransformation { text -> TransformedText(withNumerals(text.text, numerals), OffsetMapping.Identity) }
    }
    val selection = remember(colors) { TextSelectionColors(handleColor = colors.text, backgroundColor = colors.divider) }
    CompositionLocalProvider(LocalTextSelectionColors provides selection) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .semantics { contentDescription = label },
            enabled = enabled,
            textStyle = style.copy(color = textColor),
            keyboardOptions = keyboardOptions(input),
            keyboardActions = KeyboardActions(
                onDone = { onSubmit?.invoke() ?: defaultKeyboardAction(ImeAction.Done) },
                onSearch = { onSubmit?.invoke() ?: defaultKeyboardAction(ImeAction.Search) },
            ),
            singleLine = true,
            visualTransformation = transformation,
            interactionSource = source,
            cursorBrush = SolidColor(colors.text),
            decorationBox = { field ->
                Row(
                    Modifier
                        .height(height)
                        .pixelFill(colors.panel)
                        .pixelBorder(if (focused) colors.text else colors.divider)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    leading?.invoke()
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            PixelText(placeholder, style = style, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        field()
                    }
                    trailing?.invoke()
                }
            },
        )
    }
}

private fun keyboardOptions(input: FieldInput): KeyboardOptions = when (input) {
    FieldInput.NAME -> KeyboardOptions(KeyboardCapitalization.Words, autoCorrectEnabled = true, imeAction = ImeAction.Done)
    FieldInput.SEARCH -> KeyboardOptions(KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Search)
    FieldInput.CODE -> KeyboardOptions(
        KeyboardCapitalization.Characters,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Ascii,
        imeAction = ImeAction.Done,
    )
    FieldInput.URL -> KeyboardOptions(
        KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Uri,
        imeAction = ImeAction.Done,
    )
}
