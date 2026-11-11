package app.transittracker.ui.app

import app.transittracker.model.Alert
import app.transittracker.model.RouteId
import app.transittracker.model.SavedTrip
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import app.transittracker.ui.alerts.alertTouchesTrip
import app.transittracker.ui.alerts.isActive
import app.transittracker.ui.alerts.tripFootprint
import app.transittracker.ui.alerts.tripLines
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart

/**
 * Alerts in force at [nowMs] that touch the user's saved trips (plan 4.1, the Alerts tab badge),
 * by the app's one rule (`ui.alerts`): the alert names a stop of a trip, a line the trip can use
 * ([lines]: trip id to the lines of its unfiltered legs, see `tripLines`) or one it is limited to.
 *
 * The badge counts what affects a trip now; the Alerts tab also lists what is still to come, so
 * its MY TRIPS count can be higher.
 */
fun alertsOnTrips(
    alerts: List<Alert>,
    trips: List<SavedTrip>,
    nowMs: Long,
    lines: Map<String, Set<RouteId>> = emptyMap(),
): List<Alert> {
    val prints = trips.map { tripFootprint(it, lines = lines[it.id].orEmpty()) }
    return alerts.filter { a -> a.isActive(nowMs) && prints.any { alertTouchesTrip(a, it) } }
}

/** The Alerts tab badge: how many alerts in force touch the user's trips. */
@OptIn(ExperimentalCoroutinesApi::class)
fun alertBadgeCount(core: TransitCore, clock: WallClock): Flow<Int> {
    val lines = core.trips
        .mapLatest { trips -> trips.associate { it.id to tripLines(core, it) } }
        .onStart { emit(emptyMap()) }
    return combine(core.alerts, core.trips, lines) { alerts, trips, known ->
        alertsOnTrips(alerts, trips, clock.nowMillis(), known).size
    }.distinctUntilChanged()
}
