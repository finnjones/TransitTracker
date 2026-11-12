package app.transittracker.ui.feature.follow

import app.transittracker.model.Departure
import app.transittracker.model.DepartureStatus
import app.transittracker.model.Itinerary
import app.transittracker.model.ItineraryLeg
import app.transittracker.model.Journey
import app.transittracker.model.Mode
import app.transittracker.model.ServiceDetail
import app.transittracker.model.ServiceRef
import app.transittracker.model.StopId
import app.transittracker.model.rides
import app.transittracker.ui.nav.TakeFrom

/*
 * TAKE THIS ONE: a journey the planner found while the user is on the way, followed for this trip
 * only. It is the ride the user is on up to where they get off, then the planner's rides; each
 * ride is a timetable service, so the phone's own live data keeps its times. It is not saved.
 */

/** One ride of a one-off journey: the service, boarded at [board] and left at [alight]. */
data class OneOffRide(
    val ref: ServiceRef,
    val board: StopId,
    val alight: StopId,
    /** The planner's ride, for its times and names when the phone has no live view of it. */
    val planned: ItineraryLeg? = null,
)

/**
 * A journey followed for saved trip [tripId] only, as the way [wayId] ("one-off:" and the
 * planner's journey id). [changing]: the user was already off the first ride when they took it.
 */
data class OneOffJourney(val tripId: String, val wayId: String, val rides: List<OneOffRide>, val changing: Boolean) {
    /** What to follow from now: the ride the user is on (or, changing, the one they walk to). */
    val match: FollowMatch
        get() = FollowMatch(
            tripId, rides.first().ref,
            legIndex = if (changing) 1.coerceAtMost(rides.lastIndex) else 0,
            stage = if (changing && rides.size > 1) FollowStage.CHANGING else FollowStage.RIDING,
            offMetres = 0.0, wayId = wayId,
        )
}

/** The way id of a one-off journey from the planner's journey [itineraryId]. */
fun oneOffWayId(itineraryId: String) = "one-off:$itineraryId"

/** True when [wayId] is a one-off journey's, not a saved way. */
fun isOneOff(wayId: String?) = wayId?.startsWith("one-off:") == true

/** Why [itinerary] cannot be taken from [from] (every ride must be a service in the phone's timetable), or null. */
fun takeBlocker(itinerary: Itinerary): String? {
    val rides = itinerary.rides
    if (rides.isEmpty()) return "This journey is a walk, so there is nothing to follow."
    val bad = rides.firstOrNull { !it.matched || it.from.stopId == null || it.to.stopId == null } ?: return null
    val line = bad.routeName ?: "service"
    return "The $line from ${bad.from.name} is not in the phone's timetable, so this journey cannot be followed."
}

/**
 * [itinerary] taken from [from] (call only when [takeBlocker] is null): the ride the user is on
 * up to [TakeFrom.nextStopId], then the planner's rides. When the planner's first ride is that
 * same service (stay on), the two are one ride.
 */
fun oneOffOf(from: TakeFrom, itinerary: Itinerary): OneOffJourney {
    val planned = itinerary.rides.map { r ->
        OneOffRide(ServiceRef(r.tripId!!, r.serviceDate!!), r.from.stopId!!, r.to.stopId!!, r)
    }
    val first = planned.first()
    val rides = if (first.ref == from.ride) {
        listOf(OneOffRide(from.ride, from.boardStopId, first.alight, first.planned)) + planned.drop(1)
    } else {
        listOf(OneOffRide(from.ride, from.boardStopId, from.nextStopId)) + planned
    }
    return OneOffJourney(from.tripId, oneOffWayId(itinerary.id), rides, from.changing && first.ref != from.ride)
}

/**
 * The one-off journey with the latest times: each ride from its service's stops in [details]
 * (live when there is live data), else from the planner's ride. Null when a ride has neither.
 */
fun OneOffJourney.journeyFrom(details: Map<ServiceRef, ServiceDetail>): Journey? {
    val legs = rides.map { r -> details[r.ref]?.let { departureOf(it, r.board, r.alight) } ?: r.planned?.let { plannedDeparture(r, it) } ?: return null }
    return Journey(legs, wayId)
}

/** The ride of [detail] from [board] to [alight] as a departure; null when either stop is not among its calls. */
fun departureOf(detail: ServiceDetail, board: StopId, alight: StopId): Departure? {
    val from = detail.calls.firstOrNull { it.stopId == board } ?: return null
    val to = detail.calls.lastOrNull { it.stopId == alight && it.stopSequence > from.stopSequence } ?: return null
    val schedDep = from.scheduledDepartureMs ?: from.scheduledArrivalMs ?: return null
    val schedArr = to.scheduledArrivalMs ?: to.scheduledDepartureMs ?: return null
    val expDep = from.expectedDepartureMs ?: from.expectedArrivalMs ?: schedDep
    val expArr = to.expectedArrivalMs ?: to.expectedDepartureMs ?: schedArr
    val route = detail.route
    return Departure(
        ref = detail.ref,
        routeId = route?.id.orEmpty(),
        routeName = route?.shortName.orEmpty(),
        routeColor = route?.color,
        mode = route?.mode ?: Mode.OTHER,
        headsign = detail.headsign,
        originStopId = board,
        originPlatform = from.platform,
        platformChanged = from.platformChanged,
        scheduledDepartureMs = schedDep,
        expectedDepartureMs = expDep,
        destinationStopId = alight,
        scheduledArrivalMs = schedArr,
        expectedArrivalMs = expArr,
        delaySeconds = if (detail.status == DepartureStatus.TIMETABLED) null else ((expDep - schedDep) / 1000).toInt(),
        status = detail.status,
        occupancy = detail.occupancy,
        carCount = detail.carCount,
        dataAgeMs = detail.dataAgeMs,
        stale = detail.stale,
    )
}

private fun plannedDeparture(r: OneOffRide, leg: ItineraryLeg) = Departure(
    ref = r.ref,
    routeId = leg.routeId.orEmpty(),
    routeName = leg.routeName.orEmpty(),
    routeColor = leg.routeColor,
    mode = leg.mode ?: Mode.OTHER,
    headsign = leg.headsign.orEmpty(),
    originStopId = r.board,
    originPlatform = leg.from.platform,
    platformChanged = false,
    scheduledDepartureMs = leg.from.scheduledMs,
    expectedDepartureMs = leg.from.expectedMs ?: leg.from.scheduledMs,
    destinationStopId = r.alight,
    scheduledArrivalMs = leg.to.scheduledMs,
    expectedArrivalMs = leg.to.expectedMs ?: leg.to.scheduledMs,
    delaySeconds = null,
    status = if (leg.cancelled) DepartureStatus.CANCELLED else DepartureStatus.TIMETABLED,
)
