package app.transittracker.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.transittracker.ui.components.DashedDivider
import app.transittracker.ui.components.PageHeader
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.theme.TtTheme

/** Debug gallery: every screenshot in the catalogue, viewable on the device. */
@Composable
fun DebugGalleryScreen() {
    val nav = LocalNavigator.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val shot = open?.let { name -> Screenshots.all.firstOrNull { it.name == name } }
    if (shot != null) {
        Column(Modifier.fillMaxSize().background(TtTheme.colors.screen)) {
            PageHeader(shot.name, onBack = { open = null })
            Box(Modifier.weight(1f).fillMaxWidth()) { ShotContent(shot) }
        }
        return
    }
    Column(Modifier.fillMaxSize().background(TtTheme.colors.screen)) {
        PageHeader("Debug gallery", onBack = { nav.back() })
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            items(Screenshots.all, key = { it.name }) { s ->
                Column {
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = s.name }, contentAlignment = Alignment.CenterStart) {
                        PixelText(s.name)
                    }
                    DashedDivider()
                }
            }
        }
    }
}
