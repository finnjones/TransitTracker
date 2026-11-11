package app.transittracker.server.pipeline

import java.time.LocalDate

/**
 * Basic integrity checks over a loaded (possibly merged) feed: referential integrity, stop-time
 * ordering and calendar sanity. This is not a full GTFS validator; the MobilityData validator
 * can be run in addition (see [MobilityDataValidator]).
 */
object GtfsValidator {
    /** Time zone every agency must use, since all time maths assumes it. */
    const val EXPECTED_TIME_ZONE = "Australia/Sydney"

    /**
     * Validates [feed] into [report].
     *
     * @param today used for "feed has expired" warnings; null skips them.
     */
    fun validate(feed: GtfsFeed, report: ValidationReport.Builder, today: LocalDate? = null) {
        checkAgencies(feed, report)
        checkStops(feed, report)
        checkRoutes(feed, report)
        checkTrips(feed, report)
        checkStopTimes(feed, report)
        checkCalendars(feed, report, today)
        checkShapesAndTransfers(feed, report)
    }

    private fun checkAgencies(feed: GtfsFeed, report: ValidationReport.Builder) {
        if (feed.agencies.isEmpty()) report.error("no_agency", "No agencies", "agency.txt")
        if (feed.agencies.size > 1 && feed.agencies.any { it.id == null }) {
            report.error("missing_agency_id", "agency_id is required when there is more than one agency", "agency.txt")
        }
        feed.agencies.filter { it.timezone != EXPECTED_TIME_ZONE }.forEach {
            report.warning("unexpected_time_zone", "Agency ${it.id ?: it.name} uses ${it.timezone}, times are interpreted as $EXPECTED_TIME_ZONE", "agency.txt")
        }
    }

    private fun checkStops(feed: GtfsFeed, report: ValidationReport.Builder) {
        val byId = feed.stopsById
        for (stop in feed.stops) {
            val parent = stop.parentStation?.let { byId[it] }
            when {
                stop.parentStation != null && parent == null ->
                    report.error("unknown_parent_station", "Stop ${stop.id} has unknown parent_station ${stop.parentStation}", "stops.txt")
                stop.locationType == 1 && stop.parentStation != null ->
                    report.error("station_with_parent", "Station ${stop.id} must not have a parent_station", "stops.txt")
                stop.locationType == 0 && parent != null && parent.locationType != 1 ->
                    report.error("parent_not_station", "Stop ${stop.id} has parent ${parent.id} which is not a station", "stops.txt")
                stop.locationType in 2..4 && stop.parentStation == null ->
                    report.error("missing_parent_station", "Stop ${stop.id} (location_type ${stop.locationType}) needs a parent_station", "stops.txt")
            }
            if (stop.locationType in 0..1 && stop.lat == 0.0 && stop.lon == 0.0) {
                report.warning("zero_position", "Stop ${stop.id} is at 0,0", "stops.txt")
            }
            if (stop.locationType !in 0..4) report.error("bad_location_type", "Stop ${stop.id} has location_type ${stop.locationType}", "stops.txt")
        }
    }

    private fun checkRoutes(feed: GtfsFeed, report: ValidationReport.Builder) {
        val agencyIds = feed.agencies.mapNotNullTo(HashSet()) { it.id }
        val routesWithTrips = feed.trips.mapTo(HashSet()) { it.routeId }
        for (route in feed.routes) {
            if (route.agencyId != null && route.agencyId !in agencyIds) {
                report.error("unknown_agency", "Route ${route.id} has unknown agency_id ${route.agencyId}", "routes.txt")
            } else if (route.agencyId == null && feed.agencies.size > 1) {
                report.error("missing_route_agency", "Route ${route.id} needs agency_id (several agencies)", "routes.txt")
            }
            if (route.id !in routesWithTrips) report.warning("route_without_trips", "Route ${route.id} has no trips", "routes.txt")
        }
    }

    private fun checkTrips(feed: GtfsFeed, report: ValidationReport.Builder) {
        val services = feed.serviceIds
        for (trip in feed.trips) {
            if (trip.routeId !in feed.routesById) report.error("unknown_route", "Trip ${trip.id} has unknown route_id ${trip.routeId}", "trips.txt")
            if (trip.serviceId !in services) report.error("unknown_service", "Trip ${trip.id} has unknown service_id ${trip.serviceId}", "trips.txt")
            if (trip.shapeId != null && trip.shapeId !in feed.shapes) {
                report.error("unknown_shape", "Trip ${trip.id} has unknown shape_id ${trip.shapeId}", "trips.txt")
            }
            val times = feed.stopTimes[trip.id]
            when {
                times == null -> report.warning("trip_without_stop_times", "Trip ${trip.id} has no stop times; it is dropped", "trips.txt")
                times.size < 2 -> report.warning("trip_too_short", "Trip ${trip.id} has fewer than 2 stop times; it is dropped", "stop_times.txt")
            }
        }
    }

    private fun checkStopTimes(feed: GtfsFeed, report: ValidationReport.Builder) {
        val stops = feed.stopsById
        val trips = feed.tripsById
        for ((tripId, times) in feed.stopTimes) {
            if (tripId !in trips) {
                report.error("unknown_trip", "stop_times reference unknown trip_id $tripId", "stop_times.txt")
                continue
            }
            var lastTime = -1
            var lastSeq: Int? = null
            for ((i, st) in times.withIndex()) {
                val stop = stops[st.stopId]
                if (stop == null) {
                    report.error("unknown_stop", "Trip $tripId calls at unknown stop_id ${st.stopId}", "stop_times.txt")
                } else if (stop.locationType != 0) {
                    report.error("stop_time_at_station", "Trip $tripId calls at ${st.stopId} which is not a stop/platform", "stop_times.txt")
                }
                if (lastSeq != null && st.sequence == lastSeq) {
                    report.error("duplicate_stop_sequence", "Trip $tripId repeats stop_sequence ${st.sequence}", "stop_times.txt")
                }
                if (st.sequence < 0) report.error("negative_stop_sequence", "Trip $tripId has stop_sequence ${st.sequence}", "stop_times.txt")
                lastSeq = st.sequence
                if ((i == 0 || i == times.lastIndex) && (st.arrival == null || st.departure == null)) {
                    report.error("missing_trip_edge_time", "Trip $tripId has no time at its ${if (i == 0) "first" else "last"} stop", "stop_times.txt")
                }
                val arr = st.arrival
                val dep = st.departure
                if (arr != null && dep != null && dep < arr) {
                    report.error("departure_before_arrival", "Trip $tripId departs ${st.stopId} before arriving", "stop_times.txt")
                }
                if (arr != null) {
                    if (arr < lastTime) report.error("decreasing_time", "Trip $tripId goes back in time at sequence ${st.sequence}", "stop_times.txt")
                    lastTime = maxOf(lastTime, dep ?: arr)
                }
                if (st.pickupType !in 0..3 || st.dropOffType !in 0..3) {
                    report.error("bad_pickup_drop_off", "Trip $tripId has a bad pickup/drop-off type at ${st.sequence}", "stop_times.txt")
                }
            }
            if (lastTime > 48 * 3600) report.warning("very_late_trip", "Trip $tripId runs past 48:00:00", "stop_times.txt")
        }
    }

    private fun checkCalendars(feed: GtfsFeed, report: ValidationReport.Builder, today: LocalDate?) {
        val seen = HashSet<String>()
        for (c in feed.calendars) {
            if (!seen.add(c.serviceId)) report.error("duplicate_service_id", "calendar.txt repeats service_id ${c.serviceId}", "calendar.txt")
            if (c.endDate < c.startDate) report.error("calendar_end_before_start", "Service ${c.serviceId} ends before it starts", "calendar.txt")
            if (c.days.none { it }) report.warning("calendar_no_days", "Service ${c.serviceId} runs on no weekday", "calendar.txt")
        }
        val dates = HashMap<Pair<String, LocalDate>, Int>()
        for (d in feed.calendarDates) {
            if (d.exceptionType != 1 && d.exceptionType != 2) {
                report.error("bad_exception_type", "Service ${d.serviceId} on ${d.date} has exception_type ${d.exceptionType}", "calendar_dates.txt")
            }
            val previous = dates.put(d.serviceId to d.date, d.exceptionType)
            if (previous != null) {
                if (previous != d.exceptionType) {
                    report.error("conflicting_calendar_date", "Service ${d.serviceId} on ${d.date} is both added and removed", "calendar_dates.txt")
                } else {
                    report.warning("duplicate_calendar_date", "Service ${d.serviceId} on ${d.date} is listed twice", "calendar_dates.txt")
                }
            }
        }
        if (today != null) {
            val lastDay = (feed.calendars.map { it.endDate } + feed.calendarDates.filter { it.exceptionType == 1 }.map { it.date }).maxOrNull()
            if (lastDay != null && lastDay < today) report.warning("feed_expired", "No service after $lastDay")
        }
    }

    private fun checkShapesAndTransfers(feed: GtfsFeed, report: ValidationReport.Builder) {
        for ((id, points) in feed.shapes) {
            if (points.size < 2) report.warning("short_shape", "Shape $id has fewer than 2 points", "shapes.txt")
            if (points.zipWithNext().any { (a, b) -> a.sequence == b.sequence }) {
                report.error("duplicate_shape_sequence", "Shape $id repeats a shape_pt_sequence", "shapes.txt")
            }
        }
        for (t in feed.transfers) {
            if (t.fromStopId !in feed.stopsById || t.toStopId !in feed.stopsById) {
                report.error("transfer_unknown_stop", "Transfer ${t.fromStopId} -> ${t.toStopId} references an unknown stop", "transfers.txt")
            }
            if (t.transferType !in 0..5) report.error("bad_transfer_type", "Transfer ${t.fromStopId} -> ${t.toStopId} has type ${t.transferType}", "transfers.txt")
        }
    }
}
