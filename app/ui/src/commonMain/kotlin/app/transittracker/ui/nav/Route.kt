package app.transittracker.ui.nav

import app.transittracker.model.Itinerary
import app.transittracker.model.LatLng
import app.transittracker.model.PlanEndpoint
import app.transittracker.model.PlanTimeKind
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopId
import kotlinx.serialization.Serializable

/**
 * Every screen of the app (plan 4.1, 4.2). A route is plain data: it is saved with the back stack
 * (process death, configuration change), so every parameter is serializable and small. Screens
 * load what they show from `AppDeps` using these ids; never put live data in a route.
 *
 * Tab roots ([Trips], [MapTab], [Alerts], [Notify], [Settings]) carry the bottom bar; every
 * other route is a pushed, full-screen page with a back button.
 */
@Serializable
sealed interface Route {
    /** Shown by placeholders and read by the screen reader as the page title. */
    val title: String

    // ---------------------------------------------------------------- tab roots

    @Serializable
    data object Trips : Route {
        override val title get() = "Trips"
    }

    /** The Map tab: lines, live vehicles, nearby stations. */
    @Serializable
    data object MapTab : Route {
        override val title get() = "Map"
    }

    @Serializable
    data object Alerts : Route {
        override val title get() = "Alerts"
    }

    /** Push watches list. */
    @Serializable
    data object Notify : Route {
        override val title get() = "Notify"
    }

    @Serializable
    data object Settings : Route {
        override val title get() = "Settings"
    }

    // ---------------------------------------------------------------- pushed screens

    /** All services of one saved trip on one day ([serviceDate] null = today). Plan 5.3. */
    @Serializable
    data class TripTimetable(val tripId: String, val serviceDate: ServiceDate? = null) : Route {
        override val title get() = "Timetable"
    }

    /**
     * One service, every stop (plan 5.4). [boardStopId] / [alightStopId] are the user's stops
     * (platform ids from the `Departure`) when the service was opened from a saved trip; null when
     * not known (e.g. opened from a notification or the map).
     */
    @Serializable
    data class Service(
        val ref: ServiceRef,
        val boardStopId: StopId? = null,
        val alightStopId: StopId? = null,
    ) : Route {
        override val title get() = "Service"
    }

    /** Live tracking of one service on a line map (plan 5.5). Same stop ids as [Service]. */
    @Serializable
    data class Tracking(
        val ref: ServiceRef,
        val boardStopId: StopId? = null,
        val alightStopId: StopId? = null,
    ) : Route {
        override val title get() = "Live tracking"
    }

    /**
     * One journey of a saved trip on one map (Follow my journey): every ride from where it is
     * boarded to where it is left, the walks between them, the vehicles and the user's position.
     */
    @Serializable
    data class JourneyMap(val tripId: String, val legs: List<MapLeg>) : Route {
        override val title get() = "Your journey"
    }

    /**
     * Plan a journey: where and when (plan 5.6 step 1). Optional pre-fill with saved place ids
     * ([fromPlaceId] / [toPlaceId], `PlacesStore` ids). [fromOnboarding]: onboarding step 4; once
     * a route is saved the app opens Home with the doors transition.
     */
    @Serializable
    data class PlanJourney(
        val fromPlaceId: String? = null,
        val toPlaceId: String? = null,
        val fromOnboarding: Boolean = false,
        /** A start that is not a saved place: a station tapped on the map. [fromPlaceId] wins when both are given. */
        val from: JourneyEnd? = null,
    ) : Route {
        override val title get() = "Plan a journey"
    }

    /** Journey options for one search (plan 5.6 step 2). */
    @Serializable
    data class JourneyOptions(
        val from: JourneyEnd,
        val to: JourneyEnd,
        /** Epoch ms; null = leave now. */
        val timeMs: Long? = null,
        val timeKind: PlanTimeKind = PlanTimeKind.DEPART_AT,
        val fromOnboarding: Boolean = false,
        /** To look at only (other ways from the next stop, while on the way): nothing is offered to save. */
        val browseOnly: Boolean = false,
    ) : Route {
        override val title get() = "Journey options"
    }

    /** One journey, step by step (plan 5.6 step 3), with SAVE ROUTE. */
    @Serializable
    data class JourneyDetail(
        val from: JourneyEnd,
        val to: JourneyEnd,
        val itinerary: Itinerary,
        val fromOnboarding: Boolean = false,
        /** To look at only: no SAVE ROUTE (see [JourneyOptions.browseOnly]). */
        val browseOnly: Boolean = false,
    ) : Route {
        override val title get() = "Journey"
    }

    /** The saved places list (plan 5.7). */
    @Serializable
    data object SavedPlaces : Route {
        override val title get() = "Saved places"
    }

    /** Add ([placeId] null) or edit a saved place. */
    @Serializable
    data class PlaceEdit(val placeId: String? = null) : Route {
        override val title get() = if (placeId == null) "Add a place" else "Edit place"
    }

    /** Reorder, rename, folder, delete saved routes (the home long-press). */
    @Serializable
    data object ManageRoutes : Route {
        override val title get() = "Manage routes"
    }

    /** Edit one saved route: name, folder, line filter, legs by hand. */
    @Serializable
    data class EditRoute(val tripId: String) : Route {
        override val title get() = "Edit route"
    }

    /**
     * Watch editor (plan 5.9). [watchId] null = a new watch, optionally pre-filled from a saved
     * trip ([fromTripId]) or one service ([fromService], with the user's stops when known).
     */
    @Serializable
    data class WatchEditor(
        val watchId: String? = null,
        val fromTripId: String? = null,
        val fromService: ServiceRef? = null,
        val boardStopId: StopId? = null,
        val alightStopId: StopId? = null,
    ) : Route {
        override val title get() = if (watchId == null) "New watch" else "Edit watch"
    }

    /** Join, timetable download, notifications, first journey (plan 5.1). Shown before joining. */
    @Serializable
    data object Onboarding : Route {
        override val title get() = "Welcome"
    }

    /** Component, vehicle and screenshot galleries (debug builds). */
    @Serializable
    data object DebugGallery : Route {
        override val title get() = "Debug gallery"
    }
}

/**
 * One end of a planned journey as the user chose it: a display name, a position, the planner's
 * place id when it came from a place search, and the saved place when it came from `PlacesStore`
 * (so a saved route can be linked to its places, plan 5.7).
 */
@Serializable
data class JourneyEnd(
    val name: String,
    val position: LatLng,
    val plannerPlaceId: String? = null,
    val savedPlaceId: String? = null,
) {
    fun toPlanEndpoint(): PlanEndpoint = PlanEndpoint(position, plannerPlaceId)
}

/** One ride of a [Route.JourneyMap]: the service and the stops (platforms) it is boarded and left at. */
@Serializable
data class MapLeg(val ref: ServiceRef, val boardStopId: StopId, val alightStopId: StopId)

/** The five bottom-bar destinations (plan 4.1), in bar order. */
enum class Tab(val root: Route, val label: String) {
    TRIPS(Route.Trips, "TRIPS"),
    MAP(Route.MapTab, "MAP"),
    ALERTS(Route.Alerts, "ALERTS"),
    NOTIFY(Route.Notify, "NOTIFY"),
    SETTINGS(Route.Settings, "SETTINGS"),
}

/** True for the five tab roots. */
val Route.isTabRoot: Boolean get() = Tab.entries.any { it.root == this }

/** True for routes that show the live map (doors on the way in and out, plan 3.4). */
val Route.isMap: Boolean get() = this is Route.MapTab || this is Route.Tracking || this is Route.JourneyMap
