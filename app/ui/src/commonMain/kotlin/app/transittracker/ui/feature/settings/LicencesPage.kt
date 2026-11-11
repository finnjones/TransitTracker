package app.transittracker.ui.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PageHeader
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.SectionLabel
import app.transittracker.ui.res.Res
import app.transittracker.ui.theme.TtTheme

/**
 * Font and data licences: the four fonts with their copyright line and licence name, the full
 * text a tap away (read from the licence files bundled with the app), and the data attribution
 * the server sends. [texts] lets a preview supply the licence files.
 */
@Composable
fun LicencesPage(attribution: String, onBack: () -> Unit, texts: Map<String, String>? = null) {
    val loaded = texts ?: rememberLicenceTexts()
    LicencesContent(attribution, onBack, loaded)
}

/** Reads each bundled licence file once; a file that cannot be read is simply left out. */
@Composable
private fun rememberLicenceTexts(): Map<String, String> {
    var texts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        texts = FONT_LICENCES.mapNotNull { f ->
            runCatching { f.file to Res.readBytes(f.file).decodeToString() }.getOrNull()
        }.toMap()
    }
    return texts
}

@Composable
fun LicencesContent(attribution: String, onBack: () -> Unit, texts: Map<String, String>, modifier: Modifier = Modifier) {
    val c = TtTheme.colors
    var open by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize().background(c.screen)) {
        PageHeader("Licences", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PixelText(
                "TransitTracker is drawn with four free pixel fonts. Each comes with its own copy of the $LICENCE_NAME.",
                style = TtTheme.type.reading15, color = c.textReading,
            )
            SectionLabel("Fonts")
            for (font in FONT_LICENCES) {
                val text = texts[font.file]
                val isOpen = open == font.file
                PixelPanel(Modifier.fillMaxWidth()) {
                    PixelText(font.name, style = TtTheme.type.name17)
                    Label(font.use.uppercase(), color = c.textSecondary, size = LabelSize.L10)
                    if (text != null) {
                        val line = copyrightLine(text)
                        if (line.isNotEmpty()) PixelText(line, style = TtTheme.type.reading15, color = c.textSecondary)
                        modifiedNote(text)?.let { PixelText(it, style = TtTheme.type.reading15, color = c.textSecondary) }
                    }
                    Label(LICENCE_NAME.uppercase(), color = c.textSecondary, bold = true, size = LabelSize.L10)
                    if (text != null) {
                        PixelButton(
                            if (isOpen) "HIDE LICENCE" else "READ LICENCE",
                            { open = if (isOpen) null else font.file },
                            Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, style = TtTheme.type.label11Bold,
                        )
                        if (isOpen) PixelText(text.trim(), style = TtTheme.type.reading15, color = c.textReading)
                    }
                }
            }
            SectionLabel("Timetable and live data")
            PixelPanel(Modifier.fillMaxWidth()) {
                PixelText(
                    attribution.ifBlank { "The attribution for the timetable and live data comes from the server once you have joined." },
                    style = TtTheme.type.reading15, color = c.textReading,
                )
            }
        }
    }
}
