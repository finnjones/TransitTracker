package app.transittracker.ui.planning

import app.transittracker.core.FakeData
import app.transittracker.model.Itinerary
import app.transittracker.model.ItineraryLeg
import app.transittracker.model.LatLng
import app.transittracker.model.LegKind
import app.transittracker.model.LegPoint
import app.transittracker.model.Place
import app.transittracker.model.PlanRequest
import app.transittracker.model.PlanTimeKind
import app.transittracker.model.ServiceTime
import app.transittracker.model.Stop
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Direct journeys on the fake core's own services ([FakeData]), for [FakeJourneyPlanner]: when
 * both ends are within [WALK_RADIUS_M] of a different station and a T1 / T9 / M1 service calls at
 * the first and later at the second, the next three such services become walk, ride, walk
 * journeys. Rides are matched to the timetable (trip id, service date, route id, platform stop
 * ids) and carry no live estimate of their own: the phone applies its own live data.
 */
internal object FakeNetwork {
    private const val MIN = 60_000L
    const val WALK_RADIUS_M = 1_500.0

    private val stations: List<Stop> = FakeData.stops.filter { it.parentId == null }
    private val stopsById = FakeData.stops.associateBy { it.id }

    /** Walks the places mockup already states (Home to Strathfield, Work to Central). */
    private val knownWalks: Map<Pair<LatLng, String>, Pair<Int, Int>> = mapOf(
        (FakeJourneyPlanner.HOME to FakeData.STRATHFIELD) to (3 to 250),
        (FakeJourneyPlanner.PITT_ST to FakeData.CENTRAL) to (4 to 350),
    )

    fun plan(request: PlanRequest, timeMs: Long, places: List<Place>): List<Itinerary>? {
        val from = request.from.position
        val to = request.to.position
        val a = nearestStation(from) ?: return null
        val b = nearestStation(to) ?: return null
        if (a.id == b.id) return null
        val (walk1Min, walk1M) = walk(from, a)
        val (walk2Min, walk2M) = walk(to, b)
        val fromName = places.firstOrNull { distanceMeters(it.position, from) < 60 }?.name ?: "Start"
        val toName = places.firstOrNull { distanceMeters(it.position, to) < 60 }?.name ?: "Destination"

        data class Candidate(val tripIndex: Int, val date: String, val i: Int, val j: Int, val dep: Long, val arr: Long)

        val dates = ServiceTime.candidateServiceDates(timeMs) +
            ServiceTime.candidateServiceDates(timeMs + 24 * 60 * MIN).last()
        val candidates = mutableListOf<Candidate>()
        for ((index, trip) in FakeData.trips.withIndex()) {
            val st = trip.stopTimes
            val i = st.indexOfFirst { station(it.stopId) == a.id }
            if (i < 0) continue
            val j = st.indexOfFirst { station(it.stopId) == b.id }
            if (j <= i) continue
            for (date in dates.distinct()) {
                val dep = ServiceTime.toEpochMillis(date, st[i].departureSecs)
                val arr = ServiceTime.toEpochMillis(date, st[j].arrivalSecs)
                candidates += Candidate(index, date, i, j, dep, arr)
            }
        }
        val chosen = if (request.timeKind == PlanTimeKind.ARRIVE_BY) {
            candidates.filter { it.arr + walk2Min * MIN <= timeMs }.sortedByDescending { it.arr }.take(3).sortedBy { it.dep }
        } else {
            candidates.filter { it.dep - walk1Min * MIN >= timeMs }.sortedBy { it.dep }.take(3)
        }
        if (chosen.isEmpty()) return emptyList()
        return chosen.map { c ->
            val trip = FakeData.trips[c.tripIndex]
            val route = FakeData.routes.first { it.id == trip.routeId }
            val board = stopsById.getValue(trip.stopTimes[c.i].stopId)
            val alight = stopsById.getValue(trip.stopTimes[c.j].stopId)
            val boardPoint = LegPoint(shortName(a), a.position, board.id, board.platformCode, c.dep)
            val alightPoint = LegPoint(shortName(b), b.position, alight.id, alight.platformCode, c.arr)
            Itinerary(
                id = "fake-net-${trip.tripId}-${c.date}",
                legs = listOf(
                    ItineraryLeg(
                        LegKind.WALK, LegPoint(fromName, from, scheduledMs = c.dep - walk1Min * MIN),
                        boardPoint.copy(scheduledMs = c.dep), walk1Min * 60, walk1M,
                    ),
                    ItineraryLeg(
                        LegKind.RIDE, boardPoint, alightPoint, ((c.arr - c.dep) / 1000).toInt(),
                        mode = route.mode, routeName = route.shortName, headsign = trip.headsign,
                        routeColor = route.color, stopCount = c.j - c.i, realtime = false,
                        routeId = route.id, tripId = trip.tripId, serviceDate = c.date,
                    ),
                    ItineraryLeg(
                        LegKind.WALK, alightPoint.copy(stopId = null, platform = null),
                        LegPoint(toName, to, scheduledMs = c.arr + walk2Min * MIN), walk2Min * 60, walk2M,
                    ),
                ),
            )
        }
    }

    private fun station(stopId: String): String = stopsById[stopId]?.parentId ?: stopId

    private fun shortName(s: Stop) = s.name.removeSuffix(" Station")

    private fun nearestStation(p: LatLng): Stop? =
        stations.map { it to distanceMeters(p, it.position) }
            .filter { it.second <= WALK_RADIUS_M }
            .minByOrNull { it.second }?.first

    /** Minutes and metres of the walk between [p] and station [s]. */
    private fun walk(p: LatLng, s: Stop): Pair<Int, Int> {
        knownWalks.entries.firstOrNull { (k, _) -> k.second == s.id && distanceMeters(k.first, p) < 60 }?.let { return it.value }
        val meters = (distanceMeters(p, s.position) * 1.25).roundToInt().coerceAtLeast(50)
        return ceil(meters / 80.0).toInt().coerceAtLeast(1) to meters
    }

    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val r = 6_371_000.0
        val dLat = (b.lat - a.lat) * PI / 180
        val dLon = (b.lon - a.lon) * PI / 180
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(a.lat * PI / 180) * cos(b.lat * PI / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h))
    }
}
