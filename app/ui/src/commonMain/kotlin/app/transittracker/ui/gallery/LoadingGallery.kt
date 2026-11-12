package app.transittracker.ui.gallery

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.transittracker.ui.components.SectionLabel
import app.transittracker.ui.loading.FlapBoardLoader
import app.transittracker.ui.loading.LoaderFleet
import app.transittracker.ui.loading.Treadmill
import app.transittracker.ui.loading.TreadmillLine

/** The loaders: the treadmill for small waits and the flap board for a journey search. */
@Composable
fun LoadingGallery() = GalleryPage {
    SectionLabel("Treadmill · small waits")
    TreadmillLine("SEARCHING...")
    TreadmillLine("LOOKING FOR STOPS...")
    SectionLabel("Treadmill · one vehicle")
    Treadmill(fleet = LoaderFleet.take(1))
    SectionLabel("Flap board · finding journeys")
    FlapBoardLoader("FINDING", "JOURNEYS", Modifier.fillMaxWidth(), rows = 3)
}
