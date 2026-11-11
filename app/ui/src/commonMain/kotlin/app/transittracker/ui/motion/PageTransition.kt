package app.transittracker.ui.motion

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.transittracker.model.Mode
import app.transittracker.ui.format.DefaultLineColor
import app.transittracker.ui.format.parseLineColor
import kotlinx.serialization.Serializable

/**
 * The four page transitions (plan 3.4). Each is tied to one kind of move so the motion tells you
 * where you went. All are stepped, never eased; the new page takes touches within 300 ms; a tap
 * during a transition finishes it; reduce motion (or battery saver) turns every one into [Cut].
 *
 * Going back plays the transition that brought the page in, in reverse; the navigator remembers it.
 */
@Serializable
sealed interface PageTransition {
    /**
     * A vehicle crosses the screen and the new page is wiped in behind it (right-going forwards,
     * left-going back). [mode] and [lineColorArgb] are those of the service (or the first ride of
     * the journey) being opened. Train and metro cross as four cars, light rail as a coupled pair,
     * bus, coach and ferry as one vehicle.
     */
    @Serializable
    data class VehiclePass(val mode: Mode, val lineColorArgb: Int) : PageTransition {
        val lineColor: Color get() = Color(lineColorArgb)
    }

    /** Carriage doors with amber edge strips close over the page and open on the new one. */
    @Serializable
    data object Doors : PageTransition

    /**
     * Rows of the new page flip in from top to bottom, like a departure board. Rows are the
     * elements marked with `Modifier.flapRow(index)`; anything unmarked appears at once.
     */
    @Serializable
    data object SplitFlap : PageTransition

    /** Instant. */
    @Serializable
    data object Cut : PageTransition

    companion object {
        /** A vehicle pass from a GTFS colour string (`Departure.routeColor`, `Route.color`). */
        fun vehiclePass(mode: Mode, lineColorHex: String?): VehiclePass =
            VehiclePass(mode, (parseLineColor(lineColorHex) ?: DefaultLineColor).toArgb())

        fun vehiclePass(mode: Mode, lineColor: Color): VehiclePass = VehiclePass(mode, lineColor.toArgb())

        /** The vehicle used when a list opens a page that has no service of its own (a plain train). */
        val DefaultVehiclePass: VehiclePass = VehiclePass(Mode.TRAIN, DefaultLineColor.toArgb())
    }
}
