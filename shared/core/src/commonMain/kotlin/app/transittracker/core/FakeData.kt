package app.transittracker.core

import app.transittracker.model.AddedStop
import app.transittracker.model.Alert
import app.transittracker.model.AlertSeverity
import app.transittracker.model.LatLng
import app.transittracker.model.Mode
import app.transittracker.model.Occupancy
import app.transittracker.model.Route
import app.transittracker.model.ScheduledStopTime
import app.transittracker.model.ScheduledTrip
import app.transittracker.model.ServiceTime
import app.transittracker.model.Stop
import app.transittracker.model.StopDelta
import app.transittracker.model.StopId
import app.transittracker.model.TimeRange
import app.transittracker.model.TripDelta
import app.transittracker.model.TripStatus

/**
 * Canned Sydney sample data for [FakeTransitCore]: six stations with six platforms each,
 * T1 / T9 / M1 services every day, and deterministic live data relative to "now".
 */
object FakeData {
    const val CENTRAL = "200060"
    const val TOWN_HALL = "200070"
    const val STRATHFIELD = "213510"
    const val PARRAMATTA = "215020"
    const val CHATSWOOD = "206710"
    const val TALLAWONG = "2155384"

    const val T1 = "T1"
    const val T9 = "T9"
    const val M1 = "M1"

    private val stations = listOf(
        Triple(CENTRAL, "Central", LatLng(-33.8840, 151.2062)),
        Triple(TOWN_HALL, "Town Hall", LatLng(-33.8732, 151.2061)),
        Triple(STRATHFIELD, "Strathfield", LatLng(-33.8718, 151.0943)),
        Triple(PARRAMATTA, "Parramatta", LatLng(-33.8170, 151.0047)),
        Triple(CHATSWOOD, "Chatswood", LatLng(-33.7969, 151.1803)),
        Triple(TALLAWONG, "Tallawong", LatLng(-33.6913, 150.9061)),
    )

    /** Platform stop id of [station]. */
    fun platform(station: StopId, number: Int): StopId = "$station-P$number"

    val stops: List<Stop> = stations.flatMap { (id, name, pos) ->
        val modes = if (id == TALLAWONG) setOf(Mode.METRO) else if (id == CHATSWOOD) setOf(Mode.TRAIN, Mode.METRO) else setOf(Mode.TRAIN)
        listOf(Stop(id, "$name Station", null, pos, null, modes, true)) + (1..6).map { n ->
            Stop(platform(id, n), "$name Station Platform $n", id, pos, n.toString(), modes, true)
        }
    }

    val routes: List<Route> = listOf(
        Route(T1, "T1", "North Shore & Western Line", Mode.TRAIN, "F99D1C", "000000", "SydneyTrains"),
        Route(T9, "T9", "Northern Line", Mode.TRAIN, "D11F2F", "FFFFFF", "SydneyTrains"),
        Route(M1, "M1", "Metro North West & Bankstown Line", Mode.METRO, "168388", "FFFFFF", "SydneyMetro"),
    )

    private class Pattern(
        val prefix: String,
        val routeId: String,
        val headsign: String,
        val calls: List<Pair<StopId, Int>>, // platform, minutes from start
        val firstMin: Int,
        val lastMin: Int,
        val everyMin: Int,
    )

    private val patterns = listOf(
        Pattern("T1W", T1, "Emu Plains", listOf(platform(CHATSWOOD, 1) to 0, platform(TOWN_HALL, 1) to 17, platform(CENTRAL, 1) to 20, platform(STRATHFIELD, 1) to 33, platform(PARRAMATTA, 1) to 45), 4 * 60 + 30, 24 * 60 + 40, 10),
        Pattern("T1E", T1, "Berowra", listOf(platform(PARRAMATTA, 2) to 0, platform(STRATHFIELD, 2) to 12, platform(CENTRAL, 2) to 25, platform(TOWN_HALL, 2) to 28, platform(CHATSWOOD, 2) to 45), 4 * 60 + 35, 24 * 60 + 45, 10),
        Pattern("T9N", T9, "Gordon", listOf(platform(STRATHFIELD, 3) to 0, platform(CENTRAL, 3) to 14, platform(TOWN_HALL, 3) to 17, platform(CHATSWOOD, 3) to 35), 5 * 60, 24 * 60, 15),
        Pattern("T9S", T9, "Hornsby", listOf(platform(CHATSWOOD, 4) to 0, platform(TOWN_HALL, 4) to 18, platform(CENTRAL, 4) to 21, platform(STRATHFIELD, 4) to 35), 5 * 60 + 5, 24 * 60 + 5, 15),
        Pattern("M1N", M1, "Tallawong", listOf(platform(CHATSWOOD, 1) to 0, platform(TALLAWONG, 1) to 35), 5 * 60, 25 * 60, 5),
        Pattern("M1S", M1, "Sydenham", listOf(platform(TALLAWONG, 2) to 0, platform(CHATSWOOD, 2) to 35), 5 * 60, 25 * 60, 5),
    ).map { p ->
        // Metro platforms at Chatswood are separate from the train platforms.
        if (p.routeId == M1) {
            Pattern(p.prefix, p.routeId, p.headsign, p.calls.map { (s, m) -> (if (s.startsWith(CHATSWOOD)) s.replace("-P1", "-P5").replace("-P2", "-P6") else s) to m }, p.firstMin, p.lastMin, p.everyMin)
        } else {
            p
        }
    }

    val trips: List<ScheduledTrip> = patterns.flatMap { p ->
        (p.firstMin..p.lastMin step p.everyMin).map { start ->
            ScheduledTrip(
                tripId = "${p.prefix}-${ServiceTime.formatGtfsTime(start * 60).substring(0, 5).replace(":", "")}",
                routeId = p.routeId,
                serviceId = "daily",
                headsign = p.headsign,
                shapeId = "shape-${p.prefix}",
                wheelchairAccessible = true,
                stopTimes = p.calls.mapIndexed { i, (stop, offset) ->
                    val t = (start + offset) * 60
                    ScheduledStopTime(stopSequence = i + 1, stopId = stop, arrivalSecs = t, departureSecs = if (i == 0) t else t + 30)
                },
            )
        }
    }

    private val shapes = patterns.associate { p ->
        "shape-${p.prefix}" to p.calls.map { (stop, _) -> stops.first { it.id == stop }.position }
    }

    /** The sample timetable (every service runs every day). */
    fun timetable(): InMemoryTimetable = InMemoryTimetable(stops, routes, trips, version = "fake-1", shapes = shapes)

    /** Deterministic 0..9999 hash of a string (stable across platforms). */
    private fun hash(s: String): Int {
        var h = 17
        for (c in s) h = (h * 31 + c.code) and 0x7fffffff
        return h % 10_000
    }

    /**
     * Live data for services around [nowMs]: mostly on time, some late, one in ~13 cancelled,
     * some early, platform changes, plus one added T1 Central → Parramatta service.
     */
    fun liveSnapshot(timetable: InMemoryTimetable, nowMs: Long): LiveSnapshot {
        val trips = HashMap<TripKey, LiveTrip>()
        for (date in ServiceTime.candidateServiceDates(nowMs)) {
            for (trip in this.trips) {
                val start = ServiceTime.toEpochMillis(date, trip.stopTimes.first().departureSecs)
                val end = ServiceTime.toEpochMillis(date, trip.stopTimes.last().arrivalSecs)
                if (end < nowMs - 10 * 60_000 || start > nowMs + 3 * 3600_000) continue
                val h = hash(trip.tripId + date)
                val delta = when {
                    h % 13 == 7 -> TripDelta(trip.tripId, date, trip.routeId, TripStatus.CANCELLED, updatedMs = nowMs)
                    else -> {
                        val delay = when (h % 10) {
                            3 -> 240
                            6 -> 90
                            1 -> -70
                            else -> h % 31
                        }
                        val platformChange = h % 10 == 8
                        TripDelta(
                            tripId = trip.tripId,
                            startDate = date,
                            routeId = trip.routeId,
                            stops = trip.stopTimes.map { st ->
                                val changed = platformChange && st.stopSequence <= 2
                                StopDelta(
                                    stopSequence = st.stopSequence,
                                    stopId = st.stopId,
                                    arrivalDelayS = delay,
                                    departureDelayS = delay,
                                    actualStopId = if (changed) swapPlatform(st.stopId) else null,
                                )
                            },
                            occupancy = Occupancy.entries[1 + h % 4],
                            carCount = if (trip.routeId == M1) 6 else 8,
                            updatedMs = nowMs,
                        )
                    }
                }
                trips[TripKey(trip.tripId, date)] = LiveTrip(delta, nowMs)
            }
        }
        // One added service, anchored to the half hour so it does not drift with the clock.
        val anchor = nowMs - nowMs.mod(30 * 60_000L) + 42 * 60_000L
        val date = ServiceTime.localDate(anchor)
        val added = TripDelta(
            tripId = "ADDED-T1-${formatLocalTime(anchor).replace(":", "")}",
            startDate = date,
            routeId = T1,
            status = TripStatus.ADDED,
            occupancy = Occupancy.MANY_SEATS_AVAILABLE,
            carCount = 8,
            updatedMs = nowMs,
            headsign = "Parramatta",
            addedStops = listOf(
                AddedStop(1, platform(CENTRAL, 4), arrivalMs = anchor, departureMs = anchor),
                AddedStop(2, platform(STRATHFIELD, 1), arrivalMs = anchor + 12 * 60_000, departureMs = anchor + 12 * 60_000 + 30_000),
                AddedStop(3, platform(PARRAMATTA, 1), arrivalMs = anchor + 23 * 60_000, departureMs = anchor + 23 * 60_000),
            ),
        )
        trips[TripKey(added.tripId, added.startDate)] = LiveTrip(added, nowMs)
        return LiveSnapshot(trips, pollIntervalMs = 30_000, lastUpdateMs = nowMs)
    }

    private fun swapPlatform(stopId: StopId): StopId {
        val station = stopId.substringBefore("-P")
        val n = stopId.substringAfter("-P").toIntOrNull() ?: 1
        return platform(station, if (n == 4) 3 else 4)
    }

    /** Canned disruptions. */
    fun alerts(nowMs: Long): List<Alert> = listOf(
        Alert(
            id = "fake-alert-trackwork",
            headline = "Trackwork: buses replace T9 trains between Strathfield and Hornsby this weekend",
            description = "Allow an extra 30 minutes. Buses run every 15 minutes.",
            severity = AlertSeverity.WARNING,
            cause = "MAINTENANCE",
            effect = "REDUCED_SERVICE",
            activePeriods = listOf(TimeRange(nowMs + 2 * 86_400_000L, nowMs + 4 * 86_400_000L)),
            routeIds = listOf(T9),
            modes = listOf(Mode.TRAIN),
            updatedMs = nowMs - 3_600_000,
        ),
        Alert(
            id = "fake-alert-lift",
            headline = "Town Hall: lift between concourse and platforms 1 & 2 out of service",
            severity = AlertSeverity.INFO,
            cause = "TECHNICAL_PROBLEM",
            effect = "ACCESSIBILITY_ISSUE",
            stopIds = listOf(TOWN_HALL),
            modes = listOf(Mode.TRAIN),
            updatedMs = nowMs - 7_200_000,
        ),
    )
}
