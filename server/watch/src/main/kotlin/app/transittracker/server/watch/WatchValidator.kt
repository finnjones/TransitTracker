package app.transittracker.server.watch

import app.transittracker.model.ServiceTime
import app.transittracker.model.Timetable
import app.transittracker.model.WatchDto
import app.transittracker.model.WatchKind
import app.transittracker.server.spi.ApiException

/**
 * Validates watches on upsert. Every failure throws [ApiException] with status 400 and a
 * stable, machine-readable code. Timetable-dependent checks (stations, routes, trips) are
 * skipped while no timetable is loaded.
 */
internal class WatchValidator(private val settings: WatchEngineSettings) {

    /**
     * @param otherWatchCount how many *other* watches the device already has (excluding [watch]'s id).
     */
    fun validate(watch: WatchDto, timetable: Timetable?, otherWatchCount: Int) {
        if (watch.id.isBlank() || watch.id.length > MAX_ID_LENGTH) fail("invalid_id", "Watch id must be 1..$MAX_ID_LENGTH characters")
        if (watch.label.length > MAX_LABEL_LENGTH) fail("invalid_label", "Label must be at most $MAX_LABEL_LENGTH characters")
        if (otherWatchCount >= settings.maxWatchesPerDevice) {
            fail("too_many_watches", "A device can have at most ${settings.maxWatchesPerDevice} watches")
        }
        if (watch.originStationId.isBlank() || watch.destinationStationId.isBlank()) {
            fail("missing_station", "Origin and destination stations are required")
        }
        if (watch.originStationId == watch.destinationStationId) fail("same_station", "Origin and destination must differ")
        if (watch.leadMinutes !in 0..settings.maxLeadMinutes) {
            fail("invalid_lead", "Lead time must be 0..${settings.maxLeadMinutes} minutes")
        }
        validateRules(watch)
        when (watch.kind) {
            WatchKind.COMMUTE -> validateCommute(watch)
            WatchKind.SINGLE_SERVICE -> validateSingleServiceFields(watch)
        }
        if (timetable != null) validateAgainstTimetable(watch, timetable)
    }

    private fun validateRules(watch: WatchDto) {
        val max = settings.maxDelayRuleMinutes
        if (watch.rules.delayThresholdMin !in 1..max) fail("invalid_rules", "Delay threshold must be 1..$max minutes")
        if (watch.rules.delayStepMin !in 1..max) fail("invalid_rules", "Delay step must be 1..$max minutes")
    }

    private fun validateCommute(watch: WatchDto) {
        val start = watch.windowStartSecs
        val end = watch.windowEndSecs
        if (start < 0 || end > SECONDS_PER_DAY || start >= end) {
            fail("invalid_window", "Window must satisfy 0 <= start < end <= 86400 seconds after midnight")
        }
        if (watch.daysOfWeek.isEmpty() || watch.daysOfWeek.any { it !in 1..7 }) {
            fail("invalid_days", "Days of week must be a non-empty list of 1 (Monday) .. 7 (Sunday)")
        }
    }

    private fun validateSingleServiceFields(watch: WatchDto) {
        if (watch.tripId.isNullOrBlank() || watch.serviceDate.isNullOrBlank()) {
            fail("missing_trip", "A single-service watch needs tripId and serviceDate")
        }
        runCatching { ServiceTime.parseDate(watch.serviceDate!!) }
            .onFailure { fail("invalid_service_date", "serviceDate must be YYYYMMDD") }
    }

    private fun validateAgainstTimetable(watch: WatchDto, timetable: Timetable) {
        if (timetable.stop(watch.originStationId) == null) fail("unknown_station", "Unknown origin station ${watch.originStationId}")
        if (timetable.stop(watch.destinationStationId) == null) {
            fail("unknown_station", "Unknown destination station ${watch.destinationStationId}")
        }
        val originStops = timetable.stopsAtStation(watch.originStationId).map { it.id }.toSet() + watch.originStationId
        val destinationStops = timetable.stopsAtStation(watch.destinationStationId).map { it.id }.toSet() + watch.destinationStationId
        if (originStops.any { it in destinationStops }) fail("same_station", "Origin and destination must differ")

        val unknownRoutes = watch.routeIds.filter { timetable.route(it) == null }
        if (unknownRoutes.isNotEmpty()) fail("unknown_route", "Unknown route(s): ${unknownRoutes.joinToString()}")

        val originRoutes = originStops.flatMap { timetable.routesAtStop(it) }.map { it.id }.toSet()
        val connecting = destinationStops.flatMap { timetable.routesAtStop(it) }.map { it.id }.filter { it in originRoutes }.toSet()
        val usable = if (watch.routeIds.isEmpty()) connecting else connecting.intersect(watch.routeIds.toSet())
        if (usable.isEmpty()) fail("no_route", "No route connects these stations")

        if (watch.kind == WatchKind.SINGLE_SERVICE) {
            val tripId = watch.tripId!!
            val date = watch.serviceDate!!
            val trip = timetable.trip(tripId) ?: fail("unknown_trip", "Unknown trip $tripId")
            if (!timetable.runsOn(tripId, date)) fail("trip_not_running", "Trip $tripId does not run on $date")
            CoverageCalculator.findLeg(trip, originStops, destinationStops)
                ?: fail("trip_not_serving_stations", "Trip $tripId does not run from origin to destination")
        }
    }

    private fun fail(code: String, message: String): Nothing = throw ApiException(400, code, message)

    companion object {
        const val SECONDS_PER_DAY = 86_400
        const val MAX_ID_LENGTH = 64
        const val MAX_LABEL_LENGTH = 100
    }
}
