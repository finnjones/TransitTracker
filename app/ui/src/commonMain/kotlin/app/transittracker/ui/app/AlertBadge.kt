package app.transittracker.ui.app

import app.transittracker.model.Alert
import app.transittracker.model.SavedTrip
import app.transittracker.model.TransitCore
import app.transittracker.model.WallClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** True when [alert] applies at [nowMs] (no periods = always). */
fun Alert.isActive(nowMs: Long): Boolean = activePeriods.isEmpty() || activePeriods.any { p ->
    (p.startMs == null || p.startMs!! <= nowMs) && (p.endMs == null || nowMs < p.endMs!!)
}

/**
 * Active alerts that touch the user's saved trips (plan 4.1, the Alerts tab count): an alert names
 * a stop of a trip (an origin or destination stop id of any leg, as saved) or a line a trip is
 * limited to. Trips without a line filter are matched on stops only; ids are compared exactly.
 */
fun alertsOnTrips(alerts: List<Alert>, trips: List<SavedTrip>, nowMs: Long): List<Alert> {
    val stops = trips.flatMap { t -> t.legs.flatMap { it.originStopIds + it.destinationStopIds } }.toSet()
    val routes = trips.flatMap { t -> t.legs.flatMap { it.routeIds.orEmpty() } }.toSet()
    return alerts.filter { a ->
        a.isActive(nowMs) && (
            a.stopIds.any { it in stops } || a.routeIds.any { it in routes }
            )
    }
}

/** The Alerts tab badge: how many active alerts touch the user's trips. */
fun alertBadgeCount(core: TransitCore, clock: WallClock): Flow<Int> =
    combine(core.alerts, core.trips) { alerts, trips -> alertsOnTrips(alerts, trips, clock.nowMillis()).size }
        .distinctUntilChanged()
