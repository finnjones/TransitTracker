package app.transittracker.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.FieldInput
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.PixelTextField
import app.transittracker.ui.components.SectionLabel
import app.transittracker.ui.theme.TtTheme
import java.net.URI

/** Checks and tidies a server address typed by the user or set at build time. */
internal object ServerAddress {
    /** `https://host[:port][/path]` without a trailing slash, or null when [input] is not one. */
    fun normalize(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        if (!trimmed.startsWith("https://", ignoreCase = true)) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
        return "https://" + trimmed.substring("https://".length)
    }
}

/**
 * Live builds without a built-in server address ask for it once, before anything else: one
 * field and one button, in the Night theme. The address is stored on the phone only.
 */
@Composable
internal fun ServerAddressScreen(onConnect: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("https://") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val colors = TtTheme.colors
    val spacing = TtTheme.spacing
    fun submit() {
        val url = ServerAddress.normalize(text)
        if (url == null) {
            error = "Enter the full address, starting with https://"
        } else {
            error = null
            onConnect(url)
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.screen)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.gutter, vertical = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        SectionLabel("Server")
        PixelText("Where is your TransitTracker server?", style = TtTheme.type.title20)
        PixelText(
            "Ask the person who invited you for the server address. It starts with https:// " +
                "and is kept on this phone.",
            style = TtTheme.type.reading15,
            color = colors.textReading,
        )
        PixelTextField(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            label = "Server address",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "https://",
            input = FieldInput.URL,
            onSubmit = ::submit,
        )
        error?.let { PixelText(it, style = TtTheme.type.reading15, color = colors.stop) }
        Spacer(Modifier.height(4.dp))
        PixelButton("CONNECT", onClick = ::submit, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.PRIMARY)
    }
}
