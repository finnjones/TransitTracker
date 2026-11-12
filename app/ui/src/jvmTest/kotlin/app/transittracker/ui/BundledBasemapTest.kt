package app.transittracker.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import app.transittracker.model.LatLng
import app.transittracker.ui.feature.map.BaseKind
import app.transittracker.ui.feature.map.BasemapColors
import app.transittracker.ui.feature.map.BasemapStore
import app.transittracker.ui.feature.map.MapCamera
import app.transittracker.ui.feature.map.MapProjection
import app.transittracker.ui.feature.map.rasteriseBasemap
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The base map bundled with the app loads, and draws Sydney where Sydney is. */
class BundledBasemapTest {
    private val water = Color(0xFF0000FF)
    private val colors = BasemapColors(water, Color.Gray, Color.Gray, Color.White, Color.Red)

    @Test
    fun theBundledMapLoadsAndTheHarbourIsWater() {
        runBlocking { BasemapStore.load() }
        val map = assertNotNull(BasemapStore.state.value, "the bundled base map did not load")
        assertTrue(map.size > 50_000, "${map.size} features")
        for (kind in BaseKind.entries.filter { it != BaseKind.HOLE }) assertTrue(kind in map.kinds, "no $kind")

        fun isWater(at: LatLng): Boolean {
            // A 21 x 21 dot view at 10 m a dot, centred on the place: its middle pixel.
            val image = rasteriseBasemap(map, MapCamera(MapProjection.Sydney.project(at), 10.0), 21, 21, colors)
            return image.toPixelMap()[10, 10] == water
        }
        assertTrue(isWater(LatLng(-33.853, 151.217)), "Sydney Harbour, off the Opera House")
        assertTrue(isWater(LatLng(-33.835, 151.10)), "the Parramatta River")
        assertTrue(isWater(LatLng(-33.99, 151.20)), "Botany Bay")
        assertTrue(!isWater(LatLng(-33.8731, 151.2111)), "Hyde Park")
        assertEquals(false, isWater(LatLng(-33.8150, 151.0011)), "Parramatta")
    }
}
